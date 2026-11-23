package us.lemesh.nodedb.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.tan

data class TileCoord(
    val zoom: Int,
    val x: Int,
    val y: Int
)

data class DemDiskCacheStats(
    val fileCount: Int,
    val totalBytes: Long
) {
    val totalMb: Double
        get() = totalBytes / (1024.0 * 1024.0)
}

class OfflineTileMissingException(
    val coord: TileCoord?,
    val tileLabel: String
) : IllegalStateException(
    "Offline-Only DEM Mode: uncached terrain tile ($tileLabel). Open Offline Maps to download a regional DEM pack."
)

class TerrariumTileGrid private constructor(
    val zoom: Int,
    private val floatTiles: Map<Long, FloatArray>,
    val tileCount: Int,
    @Suppress("UNUSED_PARAMETER") internalMarker: Unit
) {
    constructor(
        zoom: Int,
        tiles: Map<Long, IntArray>,
        tileCount: Int
    ) : this(
        zoom = zoom,
        floatTiles = tiles.mapValues { (_, pixels) ->
            TerrariumDemRepository.decodePixelArrayToMeters(pixels)
        },
        tileCount = tileCount,
        internalMarker = Unit
    )

    private val nDouble: Double = (1 shl zoom).toDouble()
    private val maxTileIdx: Int = (1 shl zoom) - 1

    fun sampleElevation(lat: Double, lon: Double): Double? {
        val clampedLat = lat.coerceIn(-85.05112878, 85.05112878)
        val latRad = Math.toRadians(clampedLat)
        val worldX = ((lon + 180.0) / 360.0) * nDouble
        val worldY = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * nDouble

        val pxGlobal = worldX * 256.0
        val pyGlobal = worldY * 256.0

        val x0 = floor(pxGlobal).toInt()
        val y0 = floor(pyGlobal).toInt()
        val fx = pxGlobal - x0
        val fy = pyGlobal - y0

        val e00 = getPixelElevation(x0, y0) ?: return null
        val e10 = getPixelElevation(x0 + 1, y0) ?: e00
        val e01 = getPixelElevation(x0, y0 + 1) ?: e00
        val e11 = getPixelElevation(x0 + 1, y0 + 1) ?: e00

        val top = e00 + (e10 - e00) * fx
        val bottom = e01 + (e11 - e01) * fx
        return top + (bottom - top) * fy
    }

    private fun getPixelElevation(globalPixelX: Int, globalPixelY: Int): Double? {
        val tileX = floor(globalPixelX / 256.0).toInt().coerceIn(0, maxTileIdx)
        val tileY = floor(globalPixelY / 256.0).toInt().coerceIn(0, maxTileIdx)
        val px = (globalPixelX - tileX * 256).coerceIn(0, 255)
        val py = (globalPixelY - tileY * 256).coerceIn(0, 255)
        val meters = floatTiles[tileKey(tileX, tileY)] ?: return null
        val idx = py * 256 + px
        return if (idx in meters.indices) meters[idx].toDouble() else null
    }

    companion object {
        fun tileKey(x: Int, y: Int): Long =
            (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

        internal fun fromFloatTiles(
            zoom: Int,
            tiles: Map<Long, FloatArray>,
            tileCount: Int = tiles.size
        ): TerrariumTileGrid {
            return TerrariumTileGrid(
                zoom = zoom,
                floatTiles = tiles,
                tileCount = tileCount,
                internalMarker = Unit
            )
        }
    }
}

object TerrariumDemRepository {

    private const val TERRARIUM_BASE_URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000
    private const val MAX_CONCURRENT_DOWNLOADS = 10
    private const val MAX_GRID_TILES = 56
    private const val MIN_ADAPTIVE_ZOOM = 8
    private const val MAX_TILE_ATTEMPTS = 2
    private const val MEMORY_CACHE_MAX_TILES = 192
    private const val DEM_PREFS_NAME = "lemesh_offline_dem_prefs"
    private const val KEY_OFFLINE_ONLY = "offline_only_dem_mode"
    private const val DEM_DIR_NAME = "dem_tiles"
    private const val LEGACY_CACHE_DIR_NAME = "terrarium_tiles"

    private val poolScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Shared decoded 256x256 meter grids across Viewshed, AggregateCoverage, LoS, and Bridge engines.
    private val memoryCache = LruCache<TileCoord, FloatArray>(MEMORY_CACHE_MAX_TILES)
    private val inFlightRequests = ConcurrentHashMap<TileCoord, Deferred<FloatArray?>>()

    private val _offlineOnlyFlow = MutableStateFlow(false)
    val offlineOnlyFlow: StateFlow<Boolean> = _offlineOnlyFlow.asStateFlow()

    @Volatile
    private var offlineOnlyInMemory: Boolean = false

    @Volatile
    private var hasSyncedPrefs: Boolean = false

    // Optional test seam for deterministic PNG byte injection without external HTTP.
    internal var tileByteFetcherForTest: (suspend (zoom: Int, x: Int, y: Int) -> ByteArray)? = null

    fun decodeTerrariumPixel(argb: Int): Double {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 256.0 + g + b / 256.0) - 32768.0
    }

    fun decodeTerrariumPixelFloat(argb: Int): Float {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 256f + g + b / 256f) - 32768f
    }

    internal fun decodePixelArrayToMeters(pixels: IntArray): FloatArray {
        val out = FloatArray(pixels.size)
        for (i in pixels.indices) {
            out[i] = decodeTerrariumPixelFloat(pixels[i])
        }
        return out
    }

    fun recommendedZoomForRadius(radiusKm: Int): Int =
        if (radiusKm <= 25) 12 else 11

    fun paddedBoundsForRadius(
        originLat: Double,
        originLon: Double,
        radiusKm: Double
    ): DoubleArray {
        val clampedRadius = radiusKm.coerceAtLeast(1.0)
        val latDelta = (clampedRadius / 111.12) * 1.08
        val northLat = (originLat + latDelta).coerceAtMost(85.0)
        val southLat = (originLat - latDelta).coerceAtLeast(-85.0)
        val polewardLat = max(abs(northLat), abs(southLat))
        val cosLat = cos(Math.toRadians(polewardLat)).coerceAtLeast(0.15)
        val lonDelta = (clampedRadius / (111.32 * cosLat)) * 1.08
        val westLon = originLon - lonDelta
        val eastLon = originLon + lonDelta
        return doubleArrayOf(northLat, southLat, westLon, eastLon)
    }

    fun recommendedZoomForBounds(
        northLat: Double,
        southLat: Double,
        westLon: Double,
        eastLon: Double,
        preferredRadiusKm: Int
    ): Int {
        var zoom = recommendedZoomForRadius(preferredRadiusKm)
        while (zoom > MIN_ADAPTIVE_ZOOM) {
            val (minTx, minTy) = latLonToTile(northLat, westLon, zoom)
            val (maxTx, maxTy) = latLonToTile(southLat, eastLon, zoom)
            val count = (maxTx - minTx + 1).coerceAtLeast(1) * (maxTy - minTy + 1).coerceAtLeast(1)
            if (count <= MAX_GRID_TILES) return zoom
            zoom--
        }
        return zoom
    }

    fun latLonToTile(lat: Double, lon: Double, zoom: Int): Pair<Int, Int> {
        val n = (1 shl zoom).toDouble()
        val maxIdx = (1 shl zoom) - 1
        val clampedLat = lat.coerceIn(-85.05112878, 85.05112878)
        val latRad = Math.toRadians(clampedLat)
        val x = floor(((lon + 180.0) / 360.0) * n).toInt().coerceIn(0, maxIdx)
        val y = floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n)
            .toInt()
            .coerceIn(0, maxIdx)
        return x to y
    }

    fun demTileFile(context: Context, zoom: Int, x: Int, y: Int): File {
        return File(File(File(File(context.filesDir, DEM_DIR_NAME), zoom.toString()), x.toString()), "$y.png")
    }

    private fun legacyTileFile(context: Context, zoom: Int, x: Int, y: Int): File {
        return File(File(context.cacheDir, LEGACY_CACHE_DIR_NAME), "${zoom}_${x}_${y}.png")
    }

    fun isTileCachedOnDisk(context: Context, zoom: Int, x: Int, y: Int): Boolean {
        val f = demTileFile(context, zoom, x, y)
        if (f.exists() && f.length() > 0L) return true
        val legacy = legacyTileFile(context, zoom, x, y)
        return legacy.exists() && legacy.length() > 0L
    }

    fun isOfflineOnlyEnabled(context: Context? = null): Boolean {
        if (context != null && !hasSyncedPrefs) {
            syncOfflineSetting(context)
        }
        return offlineOnlyInMemory
    }

    fun syncOfflineSetting(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(DEM_PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getBoolean(KEY_OFFLINE_ONLY, false)
        offlineOnlyInMemory = stored
        hasSyncedPrefs = true
        _offlineOnlyFlow.value = stored
        return stored
    }

    fun setOfflineOnlyMode(context: Context, enabled: Boolean) {
        val appCtx = context.applicationContext
        appCtx.getSharedPreferences(DEM_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OFFLINE_ONLY, enabled)
            .apply()
        offlineOnlyInMemory = enabled
        hasSyncedPrefs = true
        _offlineOnlyFlow.value = enabled
    }

    fun computeDemDiskStats(context: Context): DemDiskCacheStats {
        val root = File(context.filesDir, DEM_DIR_NAME)
        if (!root.exists()) return DemDiskCacheStats(fileCount = 0, totalBytes = 0L)
        var count = 0
        var bytes = 0L
        root.walkTopDown().forEach { file ->
            if (file.isFile && file.extension.equals("png", ignoreCase = true)) {
                count++
                bytes += file.length()
            }
        }
        return DemDiskCacheStats(fileCount = count, totalBytes = bytes)
    }

    fun clearDemCache(context: Context) {
        memoryCache.evictAll()
        inFlightRequests.clear()
        runCatching { File(context.filesDir, DEM_DIR_NAME).deleteRecursively() }
        runCatching { File(context.cacheDir, LEGACY_CACHE_DIR_NAME).deleteRecursively() }
    }

    internal fun clearMemoryCacheForTest() {
        memoryCache.evictAll()
        inFlightRequests.clear()
    }

    internal fun isTileInMemoryForTest(zoom: Int, x: Int, y: Int): Boolean {
        return memoryCache.get(TileCoord(zoom, x, y)) != null
    }

    suspend fun samplePointElevation(
        context: Context,
        lat: Double,
        lon: Double,
        zoom: Int = 12
    ): Result<Double> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val (tx, ty) = latLonToTile(lat, lon, zoom)
                val coord = TileCoord(zoom, tx, ty)
                val meters = fetchOrLoadTileCoalesced(context.applicationContext, coord, allowNetworkWhenOffline = false)
                val grid = TerrariumTileGrid.fromFloatTiles(
                    zoom = zoom,
                    tiles = mapOf(TerrariumTileGrid.tileKey(tx, ty) to meters),
                    tileCount = 1
                )
                grid.sampleElevation(lat, lon)
                    ?: throw IllegalStateException("Unable to decode Terrarium elevation at coordinate.")
            }
        }
    }

    suspend fun loadTileGridForRadius(
        context: Context,
        originLat: Double,
        originLon: Double,
        radiusKm: Double,
        zoom: Int
    ): Result<TerrariumTileGrid> {
        val bounds = paddedBoundsForRadius(originLat, originLon, radiusKm)
        return loadTileGridForBounds(
            context = context,
            northLat = bounds[0],
            southLat = bounds[1],
            westLon = bounds[2],
            eastLon = bounds[3],
            zoom = zoom
        )
    }

    suspend fun loadTileGridForBounds(
        context: Context,
        northLat: Double,
        southLat: Double,
        westLon: Double,
        eastLon: Double,
        zoom: Int
    ): Result<TerrariumTileGrid> {
        val appContext = context.applicationContext
        return withContext(Dispatchers.IO) {
            runCatching {
                val (minTx, minTy) = latLonToTile(northLat, westLon, zoom)
                val (maxTx, maxTy) = latLonToTile(southLat, eastLon, zoom)

                val coords = buildList {
                    for (ty in minTy..maxTy) {
                        for (tx in minTx..maxTx) {
                            add(TileCoord(zoom, tx, ty))
                        }
                    }
                }

                val semaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)
                val loadedTiles = coroutineScope {
                    coords.map { coord ->
                        async {
                            semaphore.withPermit {
                                val meters = fetchOrLoadTileCoalesced(
                                    context = appContext,
                                    coord = coord,
                                    allowNetworkWhenOffline = false
                                )
                                TerrariumTileGrid.tileKey(coord.x, coord.y) to meters
                            }
                        }
                    }.awaitAll()
                }

                TerrariumTileGrid.fromFloatTiles(
                    zoom = zoom,
                    tiles = loadedTiles.toMap(),
                    tileCount = loadedTiles.size
                )
            }
        }
    }

    /**
     * Ensures a tile PNG is stored under `filesDir/dem_tiles/{z}/{x}/{y}.png` and decoded into memory.
     * Used by [OfflineDemPackManager] for regional pack pre-fetching.
     */
    suspend fun ensureTileCached(
        context: Context,
        coord: TileCoord,
        allowNetworkWhenOffline: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val tileFile = demTileFile(context.applicationContext, coord.zoom, coord.x, coord.y)
            if (tileFile.exists() && tileFile.length() > 0L) {
                if (memoryCache.get(coord) != null) return@runCatching
            }
            fetchOrLoadTileCoalesced(
                context = context.applicationContext,
                coord = coord,
                allowNetworkWhenOffline = allowNetworkWhenOffline
            )
            Unit
        }
    }

    private suspend fun fetchOrLoadTileCoalesced(
        context: Context,
        coord: TileCoord,
        allowNetworkWhenOffline: Boolean
    ): FloatArray {
        memoryCache.get(coord)?.let { return it }

        while (true) {
            val existing = inFlightRequests[coord]
            if (existing != null) {
                val resolved = existing.await()
                if (resolved != null) return resolved
            }

            val created = CompletableDeferred<FloatArray?>()
            val prior = inFlightRequests.putIfAbsent(coord, created)
            if (prior != null) {
                val resolved = prior.await()
                if (resolved != null) return resolved
                continue
            }

            val worker = poolScope.async {
                try {
                    val loaded = loadFromDiskOrNetwork(context, coord, allowNetworkWhenOffline)
                    memoryCache.put(coord, loaded)
                    created.complete(loaded)
                    loaded
                } catch (t: Throwable) {
                    created.completeExceptionally(t)
                    throw t
                } finally {
                    inFlightRequests.remove(coord, created)
                }
            }
            return worker.await()
        }
    }

    private suspend fun loadFromDiskOrNetwork(
        context: Context,
        coord: TileCoord,
        allowNetworkWhenOffline: Boolean
    ): FloatArray {
        val primaryFile = demTileFile(context, coord.zoom, coord.x, coord.y)
        if (primaryFile.exists() && primaryFile.length() > 0L) {
            decodePngFileToMeters(primaryFile)?.let { return it }
            runCatching { primaryFile.delete() }
        }

        val legacyFile = legacyTileFile(context, coord.zoom, coord.x, coord.y)
        if (legacyFile.exists() && legacyFile.length() > 0L) {
            val legacyBytes = runCatching { legacyFile.readBytes() }.getOrNull()
            if (legacyBytes != null && legacyBytes.isNotEmpty()) {
                val decoded = decodePngBytesToMeters(legacyBytes)
                if (decoded != null) {
                    writeBytesAtomically(primaryFile, legacyBytes)
                    return decoded
                }
            }
        }

        if (!allowNetworkWhenOffline && isOfflineOnlyEnabled(context)) {
            throw OfflineTileMissingException(
                coord = coord,
                tileLabel = "z${coord.zoom}/${coord.x}/${coord.y}"
            )
        }

        val bytes = fetchTileBytesFromNetwork(coord)
        val meters = decodePngBytesToMeters(bytes)
            ?: throw IllegalStateException("Failed to decode Terrarium PNG (${coord.zoom}/${coord.x}/${coord.y})")
        writeBytesAtomically(primaryFile, bytes)
        return meters
    }

    private suspend fun fetchTileBytesFromNetwork(coord: TileCoord): ByteArray {
        tileByteFetcherForTest?.let { injected ->
            return injected(coord.zoom, coord.x, coord.y)
        }

        var lastError: Throwable? = null
        for (attempt in 1..MAX_TILE_ATTEMPTS) {
            val url = URL("$TERRARIUM_BASE_URL/${coord.zoom}/${coord.x}/${coord.y}.png")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    throw IllegalStateException("Terrarium DEM tile HTTP $code (${coord.zoom}/${coord.x}/${coord.y})")
                }
                return connection.inputStream.use { input ->
                    val buffer = ByteArrayOutputStream()
                    input.copyTo(buffer)
                    buffer.toByteArray()
                }
            } catch (t: Throwable) {
                lastError = t
                if (attempt < MAX_TILE_ATTEMPTS) {
                    runCatching { Thread.sleep(200L) }
                }
            } finally {
                connection.disconnect()
            }
        }
        throw lastError ?: IllegalStateException("Failed to load Terrarium tile (${coord.zoom}/${coord.x}/${coord.y})")
    }

    private fun decodePngFileToMeters(file: File): FloatArray? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        return decodePngBytesToMeters(bytes)
    }

    private fun decodePngBytesToMeters(bytes: ByteArray): FloatArray? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
            inPremultiplied = false
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return try {
            if (bitmap.width != 256 || bitmap.height != 256) {
                val scaled = Bitmap.createScaledBitmap(bitmap, 256, 256, false)
                try {
                    extractMetersFromBitmap(scaled)
                } finally {
                    if (scaled !== bitmap && !scaled.isRecycled) {
                        scaled.recycle()
                    }
                }
            } else {
                extractMetersFromBitmap(bitmap)
            }
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private fun extractMetersFromBitmap(bitmap: Bitmap): FloatArray {
        val row = IntArray(256)
        val meters = FloatArray(256 * 256)
        for (y in 0 until 256) {
            bitmap.getPixels(row, 0, 256, 0, y, 256, 1)
            val rowOffset = y * 256
            for (x in 0 until 256) {
                meters[rowOffset + x] = decodeTerrariumPixelFloat(row[x])
            }
        }
        return meters
    }

    private fun writeBytesAtomically(target: File, bytes: ByteArray) {
        runCatching {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                target.writeBytes(bytes)
                tmp.delete()
            }
        }
    }
}
