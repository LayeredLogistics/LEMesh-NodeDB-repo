package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.tan

class TerrariumTileGrid(
    val zoom: Int,
    private val tiles: Map<Long, IntArray>,
    val tileCount: Int
) {
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
        val pixels = tiles[tileKey(tileX, tileY)] ?: return null
        val argb = pixels[py * 256 + px]
        return TerrariumDemRepository.decodeTerrariumPixel(argb)
    }

    companion object {
        fun tileKey(x: Int, y: Int): Long =
            (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
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

    // Cache up to 160 decoded 256x256 IntArrays (~40 MB max) in memory
    private val memoryCache = LruCache<String, IntArray>(160)

    fun decodeTerrariumPixel(argb: Int): Double {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 256.0 + g + b / 256.0) - 32768.0
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

    suspend fun samplePointElevation(
        context: Context,
        lat: Double,
        lon: Double,
        zoom: Int = 12
    ): Result<Double> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val (tx, ty) = latLonToTile(lat, lon, zoom)
                val pixels = fetchOrLoadTile(context, zoom, tx, ty)
                val grid = TerrariumTileGrid(
                    zoom = zoom,
                    tiles = mapOf(TerrariumTileGrid.tileKey(tx, ty) to pixels),
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
        return withContext(Dispatchers.IO) {
            runCatching {
                val (minTx, minTy) = latLonToTile(northLat, westLon, zoom)
                val (maxTx, maxTy) = latLonToTile(southLat, eastLon, zoom)

                val coords = buildList {
                    for (ty in minTy..maxTy) {
                        for (tx in minTx..maxTx) {
                            add(tx to ty)
                        }
                    }
                }

                val semaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)
                val loadedTiles = coroutineScope {
                    coords.map { (tx, ty) ->
                        async {
                            semaphore.withPermit {
                                val pixels = fetchOrLoadTile(context, zoom, tx, ty)
                                TerrariumTileGrid.tileKey(tx, ty) to pixels
                            }
                        }
                    }.awaitAll()
                }

                TerrariumTileGrid(
                    zoom = zoom,
                    tiles = loadedTiles.toMap(),
                    tileCount = loadedTiles.size
                )
            }
        }
    }

    private fun fetchOrLoadTile(context: Context, zoom: Int, x: Int, y: Int): IntArray {
        val memKey = "${zoom}_${x}_${y}"
        memoryCache.get(memKey)?.let { return it }

        val cacheDir = File(context.cacheDir, "terrarium_tiles").apply { mkdirs() }
        val tileFile = File(cacheDir, "$memKey.png")

        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
            inPremultiplied = false
        }

        if (tileFile.exists() && tileFile.length() > 0L) {
            val cachedBytes = runCatching { tileFile.readBytes() }.getOrNull()
            val cachedBitmap = cachedBytes?.let {
                BitmapFactory.decodeByteArray(it, 0, it.size, options)
            }
            if (cachedBitmap != null) {
                val pixels = IntArray(256 * 256)
                cachedBitmap.getPixels(pixels, 0, 256, 0, 0, 256, 256)
                cachedBitmap.recycle()
                memoryCache.put(memKey, pixels)
                return pixels
            } else {
                runCatching { tileFile.delete() }
            }
        }

        var lastError: Throwable? = null
        for (attempt in 1..MAX_TILE_ATTEMPTS) {
            val url = URL("$TERRARIUM_BASE_URL/$zoom/$x/$y.png")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    throw IllegalStateException("Terrarium DEM tile HTTP $code ($zoom/$x/$y)")
                }
                val bytes = connection.inputStream.use { input ->
                    val buffer = ByteArrayOutputStream()
                    input.copyTo(buffer)
                    buffer.toByteArray()
                }
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    ?: throw IllegalStateException("Failed to decode Terrarium PNG ($zoom/$x/$y)")
                val pixels = IntArray(256 * 256)
                decoded.getPixels(pixels, 0, 256, 0, 0, 256, 256)
                decoded.recycle()

                runCatching { tileFile.writeBytes(bytes) }
                memoryCache.put(memKey, pixels)
                return pixels
            } catch (t: Throwable) {
                lastError = t
                if (attempt < MAX_TILE_ATTEMPTS) {
                    runCatching { Thread.sleep(200L) }
                }
            } finally {
                connection.disconnect()
            }
        }

        throw lastError ?: IllegalStateException("Failed to load Terrarium tile ($zoom/$x/$y)")
    }
}
