package us.lemesh.nodedb.data

import android.content.Context
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

enum class LidarDataSource(
    val badgeLabel: String,
    val shortLabel: String
) {
    USGS_3DEP_1M("USGS 3DEP 1m LiDAR", "1m USGS LiDAR"),
    CACHED_3DEP_1M("Offline Cached 1m LiDAR", "Offline Cached"),
    LOCAL_GEOTIFF("Local DSM GeoTIFF", "Local DSM GeoTIFF"),
    LOCAL_ASCII_GRID("Local ASC Grid", "Local ASC Grid"),
    TERRARIUM_10M_FALLBACK("10m DEM Fallback", "10m DEM Fallback")
}

data class LidarGeoBounds(
    val northLat: Double,
    val southLat: Double,
    val westLon: Double,
    val eastLon: Double
) {
    fun contains(lat: Double, lon: Double): Boolean =
        lat in southLat..northLat && lon in westLon..eastLon
}

class LidarElevationPatch(
    val cols: Int,
    val rows: Int,
    val northLat: Double,
    val southLat: Double,
    val westLon: Double,
    val eastLon: Double,
    val resolutionMeters: Double,
    val elevationsMeters: FloatArray,
    val noDataValue: Float = Float.NaN,
    val source: LidarDataSource,
    val sourceLabel: String = source.badgeLabel
) {
    private val latSpan = (northLat - southLat).coerceAtLeast(1e-9)
    private val lonSpan = (eastLon - westLon).coerceAtLeast(1e-9)

    val shortBadgeLabel: String
        get() = when (source) {
            LidarDataSource.USGS_3DEP_1M -> when {
                resolutionMeters <= 0.65 -> "0.5m 3DEP"
                resolutionMeters <= 1.35 -> "1m 3DEP"
                else -> String.format(Locale.US, "%.1fm 3DEP", resolutionMeters)
            }
            LidarDataSource.CACHED_3DEP_1M -> when {
                resolutionMeters <= 0.65 -> "Cached 0.5m"
                resolutionMeters <= 1.35 -> "Cached 1m"
                else -> "Offline Cached"
            }
            else -> source.shortLabel
        }

    fun containsCoordinate(lat: Double, lon: Double): Boolean =
        lat in southLat..northLat && lon in westLon..eastLon

    fun sampleElevation(lat: Double, lon: Double): Double? {
        if (cols <= 0 || rows <= 0 || elevationsMeters.size < cols * rows) return null
        if (lat < southLat || lat > northLat || lon < westLon || lon > eastLon) return null

        val u = ((lon - westLon) / lonSpan) * (cols - 1).coerceAtLeast(1)
        val v = ((northLat - lat) / latSpan) * (rows - 1).coerceAtLeast(1)

        val x0 = floor(u).toInt().coerceIn(0, cols - 1)
        val y0 = floor(v).toInt().coerceIn(0, rows - 1)
        val x1 = (x0 + 1).coerceIn(0, cols - 1)
        val y1 = (y0 + 1).coerceIn(0, rows - 1)
        val fx = (u - x0).coerceIn(0.0, 1.0)
        val fy = (v - y0).coerceIn(0.0, 1.0)

        val v00 = rawValid(x0, y0)
        val v10 = rawValid(x1, y0)
        val v01 = rawValid(x0, y1)
        val v11 = rawValid(x1, y1)

        val anchor = v00 ?: v10 ?: v01 ?: v11 ?: return null
        val e00 = v00 ?: anchor
        val e10 = v10 ?: e00
        val e01 = v01 ?: e00
        val e11 = v11 ?: e00

        val top = e00 + (e10 - e00) * fx
        val bottom = e01 + (e11 - e01) * fx
        return top + (bottom - top) * fy
    }

    fun validSampleFraction(): Double {
        if (elevationsMeters.isEmpty()) return 0.0
        val step = max(1, elevationsMeters.size / 4096)
        var checked = 0
        var valid = 0
        var i = 0
        while (i < elevationsMeters.size) {
            checked++
            if (isValidElevation(elevationsMeters[i])) valid++
            i += step
        }
        return if (checked == 0) 0.0 else valid.toDouble() / checked.toDouble()
    }

    private fun rawValid(col: Int, row: Int): Double? {
        val raw = elevationsMeters[row * cols + col]
        return if (isValidElevation(raw)) raw.toDouble() else null
    }

    private fun isValidElevation(value: Float): Boolean {
        if (!value.isFinite()) return false
        if (!noDataValue.isNaN() && abs(value - noDataValue) <= max(1e-3f, abs(noDataValue) * 1e-5f)) {
            return false
        }
        return value in -500f..9000f
    }
}

data class LidarPatchFetchResult(
    val patch: LidarElevationPatch,
    val note: String? = null
)

object LidarDemRepository {

    private const val USGS_3DEP_EXPORT_IMAGE_URL =
        "https://elevation.nationalmap.gov/arcgis/rest/services/3DEPElevation/ImageServer/exportImage"
    internal const val BEST_AVAILABLE_MOSAIC_RULE =
        """{"mosaicMethod":"esriMosaicAttribute","sortField":"BestAvailable","ascending":true}"""
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 25_000
    private const val CACHE_DIR_NAME = "lidar_patches"
    private const val MEMORY_CACHE_MAX_PATCHES = 12
    private const val DISK_CACHE_MAGIC = 0x4C4D4C44 // "LMLD"
    private const val DISK_CACHE_VERSION = 2
    private const val MAX_LOCAL_RASTER_DIM = 1536

    private val memoryCache = LruCache<String, LidarElevationPatch>(MEMORY_CACHE_MAX_PATCHES)

    // Test seam for deterministic GeoTIFF byte injection without external HTTP.
    internal var usgsPatchFetcherForTest: (
        suspend (
            westLon: Double,
            southLat: Double,
            eastLon: Double,
            northLat: Double,
            cols: Int,
            rows: Int
        ) -> ByteArray
    )? = null

    fun clearMemoryCache() {
        memoryCache.evictAll()
    }

    fun computeQueryBounds(
        centerLat: Double,
        centerLon: Double,
        radiusMeters: Int
    ): LidarGeoBounds {
        val bufferedRadiusM = (radiusMeters.coerceIn(100, 1500) * 1.18).coerceAtLeast(140.0)
        val cosLat = cos(Math.toRadians(centerLat)).coerceAtLeast(0.15)
        val latDelta = bufferedRadiusM / 111_132.0
        val lonDelta = bufferedRadiusM / (111_320.0 * cosLat)
        return LidarGeoBounds(
            northLat = (centerLat + latDelta).coerceAtMost(85.0),
            southLat = (centerLat - latDelta).coerceAtLeast(-85.0),
            westLon = (centerLon - lonDelta).coerceAtLeast(-180.0),
            eastLon = (centerLon + lonDelta).coerceAtMost(180.0)
        )
    }

    fun computeGridDimensions(
        centerLat: Double,
        bounds: LidarGeoBounds,
        radiusMeters: Int
    ): Pair<Int, Int> {
        val targetSpacingM = when {
            radiusMeters <= 350 -> 0.5
            radiusMeters <= 650 -> 0.75
            else -> 1.0
        }
        val cosLat = cos(Math.toRadians(centerLat)).coerceAtLeast(0.15)
        val heightM = (bounds.northLat - bounds.southLat) * 111_132.0
        val widthM = (bounds.eastLon - bounds.westLon) * 111_320.0 * cosLat
        val cols = (widthM / targetSpacingM).roundToInt().coerceIn(200, MAX_LOCAL_RASTER_DIM)
        val rows = (heightM / targetSpacingM).roundToInt().coerceIn(200, MAX_LOCAL_RASTER_DIM)
        return cols to rows
    }

    suspend fun fetchPatchForViewshed(
        context: Context,
        centerLat: Double,
        centerLon: Double,
        radiusMeters: Int,
        importedLocalPatch: LidarElevationPatch? = null
    ): LidarPatchFetchResult = withContext(Dispatchers.IO) {
        if (importedLocalPatch != null && importedLocalPatch.containsCoordinate(centerLat, centerLon)) {
            return@withContext LidarPatchFetchResult(
                patch = importedLocalPatch,
                note = "Using ${importedLocalPatch.sourceLabel}"
            )
        }

        val bounds = computeQueryBounds(centerLat, centerLon, radiusMeters)
        val (cols, rows) = computeGridDimensions(centerLat, bounds, radiusMeters)
        val cacheKey = buildCacheKey(centerLat, centerLon, radiusMeters)

        memoryCache.get(cacheKey)?.let { cached ->
            if (cached.containsCoordinate(centerLat, centerLon)) {
                return@withContext LidarPatchFetchResult(cached)
            }
        }

        readDiskCache(context, cacheKey)?.let { diskPatch ->
            if (diskPatch.containsCoordinate(centerLat, centerLon) && diskPatch.validSampleFraction() >= 0.15) {
                memoryCache.put(cacheKey, diskPatch)
                return@withContext LidarPatchFetchResult(diskPatch)
            }
        }

        val offlineOnly = TerrariumDemRepository.isOfflineOnlyEnabled(context)
        if (!offlineOnly || usgsPatchFetcherForTest != null) {
            val usgsPatch = runCatching {
                val bytes = usgsPatchFetcherForTest?.invoke(
                    bounds.westLon,
                    bounds.southLat,
                    bounds.eastLon,
                    bounds.northLat,
                    cols,
                    rows
                ) ?: downloadUsgs3DepTiff(
                    westLon = bounds.westLon,
                    southLat = bounds.southLat,
                    eastLon = bounds.eastLon,
                    northLat = bounds.northLat,
                    cols = cols,
                    rows = rows
                )
                val rawPatch = parseGeoTiffBytes(
                    bytes = bytes,
                    source = LidarDataSource.USGS_3DEP_1M,
                    sourceLabel = LidarDataSource.USGS_3DEP_1M.badgeLabel,
                    fallbackBounds = bounds
                )
                val resolvedLabel = when {
                    rawPatch.resolutionMeters <= 0.65 -> "USGS 3DEP 0.5m LiDAR"
                    rawPatch.resolutionMeters <= 1.35 -> "USGS 3DEP 1m LiDAR"
                    else -> String.format(Locale.US, "USGS 3DEP %.1fm LiDAR", rawPatch.resolutionMeters)
                }
                LidarElevationPatch(
                    cols = rawPatch.cols,
                    rows = rawPatch.rows,
                    northLat = rawPatch.northLat,
                    southLat = rawPatch.southLat,
                    westLon = rawPatch.westLon,
                    eastLon = rawPatch.eastLon,
                    resolutionMeters = rawPatch.resolutionMeters,
                    elevationsMeters = rawPatch.elevationsMeters,
                    noDataValue = rawPatch.noDataValue,
                    source = rawPatch.source,
                    sourceLabel = resolvedLabel
                )
            }.getOrNull()

            if (usgsPatch != null && usgsPatch.validSampleFraction() >= 0.15) {
                memoryCache.put(cacheKey, usgsPatch)
                writeDiskCache(context, cacheKey, usgsPatch)
                return@withContext LidarPatchFetchResult(usgsPatch)
            }
        }

        // Graceful 10m Terrarium DEM fallback when offline or outside USGS 3DEP coverage
        val fallbackRadiusKm = max(1.0, (radiusMeters * 1.25) / 1000.0)
        val terrariumGrid = TerrariumDemRepository.loadTileGridForRadius(
            context = context,
            originLat = centerLat,
            originLon = centerLon,
            radiusKm = fallbackRadiusKm,
            zoom = 14
        ).getOrThrow()
        val fallbackPatch = sampleTerrariumIntoPatch(
            grid = terrariumGrid,
            bounds = bounds,
            cols = min(cols, 320),
            rows = min(rows, 320)
        )
        val fallbackNote = if (offlineOnly) {
            "Offline-Only mode without cached 1m patch — using 10m Terrarium DEM."
        } else {
            "USGS 3DEP 1m LiDAR unavailable here — using 10m Terrarium DEM fallback."
        }
        LidarPatchFetchResult(patch = fallbackPatch, note = fallbackNote)
    }

    internal fun sampleTerrariumIntoPatch(
        grid: TerrariumTileGrid,
        bounds: LidarGeoBounds,
        cols: Int,
        rows: Int
    ): LidarElevationPatch {
        val safeCols = cols.coerceAtLeast(2)
        val safeRows = rows.coerceAtLeast(2)
        val elevations = FloatArray(safeCols * safeRows)
        val latStep = (bounds.northLat - bounds.southLat) / (safeRows - 1)
        val lonStep = (bounds.eastLon - bounds.westLon) / (safeCols - 1)
        for (r in 0 until safeRows) {
            val lat = bounds.northLat - r * latStep
            for (c in 0 until safeCols) {
                val lon = bounds.westLon + c * lonStep
                elevations[r * safeCols + c] = (grid.sampleElevation(lat, lon) ?: Float.NaN.toDouble()).toFloat()
            }
        }
        val midLat = (bounds.northLat + bounds.southLat) * 0.5
        val approxMeters = ((bounds.eastLon - bounds.westLon) * 111_320.0 * cos(Math.toRadians(midLat))) / safeCols
        return LidarElevationPatch(
            cols = safeCols,
            rows = safeRows,
            northLat = bounds.northLat,
            southLat = bounds.southLat,
            westLon = bounds.westLon,
            eastLon = bounds.eastLon,
            resolutionMeters = max(10.0, approxMeters),
            elevationsMeters = elevations,
            noDataValue = Float.NaN,
            source = LidarDataSource.TERRARIUM_10M_FALLBACK,
            sourceLabel = LidarDataSource.TERRARIUM_10M_FALLBACK.badgeLabel
        )
    }

    internal fun buildUsgs3DepExportUrl(
        westLon: Double,
        southLat: Double,
        eastLon: Double,
        northLat: Double,
        cols: Int,
        rows: Int
    ): String {
        val encodedMosaic = URLEncoder.encode(BEST_AVAILABLE_MOSAIC_RULE, "UTF-8")
        return String.format(
            Locale.US,
            "%s?bbox=%.7f,%.7f,%.7f,%.7f&bboxSR=4326&imageSR=4326&size=%d,%d&format=tiff&pixelType=F32&interpolation=RSP_BilinearInterpolation&mosaicRule=%s&f=image",
            USGS_3DEP_EXPORT_IMAGE_URL,
            westLon,
            southLat,
            eastLon,
            northLat,
            cols,
            rows,
            encodedMosaic
        )
    }

    private fun downloadUsgs3DepTiff(
        westLon: Double,
        southLat: Double,
        eastLon: Double,
        northLat: Double,
        cols: Int,
        rows: Int
    ): ByteArray {
        val queryUrl = buildUsgs3DepExportUrl(
            westLon = westLon,
            southLat = southLat,
            eastLon = eastLon,
            northLat = northLat,
            cols = cols,
            rows = rows
        )
        val conn = (URL(queryUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "image/tiff, application/octet-stream")
            setRequestProperty("User-Agent", "LEMeshHorizon/1.0")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("USGS 3DEP HTTP $code")
            }
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    fun parseLocalLidarStream(
        fileName: String,
        inputStream: InputStream
    ): LidarElevationPatch {
        val lower = fileName.lowercase(Locale.US)
        val bytes = inputStream.readBytes()
        return when {
            lower.endsWith(".asc") || lower.endsWith(".grd") || lower.endsWith(".txt") -> {
                parseEsriAsciiGrid(
                    text = bytes.toString(Charsets.UTF_8),
                    sourceLabel = fileName
                )
            }
            lower.endsWith(".tif") || lower.endsWith(".tiff") -> {
                parseGeoTiffBytes(
                    bytes = bytes,
                    source = LidarDataSource.LOCAL_GEOTIFF,
                    sourceLabel = fileName,
                    fallbackBounds = null
                )
            }
            else -> {
                // Sniff TIFF magic bytes ("II*" or "MM*") vs text ASCII grid
                val isTiff = bytes.size >= 4 && (
                    (bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 42.toByte()) ||
                        (bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() && bytes[3] == 42.toByte())
                    )
                if (isTiff) {
                    parseGeoTiffBytes(
                        bytes = bytes,
                        source = LidarDataSource.LOCAL_GEOTIFF,
                        sourceLabel = fileName,
                        fallbackBounds = null
                    )
                } else {
                    parseEsriAsciiGrid(
                        text = bytes.toString(Charsets.UTF_8),
                        sourceLabel = fileName
                    )
                }
            }
        }
    }

    /**
     * Pure Kotlin standard GeoTIFF reader supporting uncompressed 8-bit, 16-bit, and 32-bit
     * IEEE float/integer rasters (stripped or tiled) with geographic WGS84 or UTM tiepoints.
     */
    fun parseGeoTiffBytes(
        bytes: ByteArray,
        source: LidarDataSource = LidarDataSource.LOCAL_GEOTIFF,
        sourceLabel: String = source.badgeLabel,
        fallbackBounds: LidarGeoBounds? = null
    ): LidarElevationPatch {
        if (bytes.size < 16) throw IllegalArgumentException("GeoTIFF file is too small (${bytes.size} bytes).")
        val order = when {
            bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
            bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
            else -> throw IllegalArgumentException("Not a valid TIFF header.")
        }
        val buf = ByteBuffer.wrap(bytes).order(order)
        val magic = buf.getShort(2).toInt() and 0xFFFF
        if (magic != 42) {
            throw IllegalArgumentException("Unsupported TIFF variant (magic=$magic; BigTIFF is not supported).")
        }
        val ifdOffset = buf.getInt(4).toLong() and 0xFFFFFFFFL
        if (ifdOffset < 8L || ifdOffset + 2L > bytes.size) {
            throw IllegalArgumentException("Invalid GeoTIFF IFD offset.")
        }

        val entryCount = buf.getShort(ifdOffset.toInt()).toInt() and 0xFFFF
        var imageWidth = 0
        var imageHeight = 0
        var bitsPerSample = 32
        var compression = 1
        var sampleFormat = 1 // 1=uint, 2=sint, 3=float
        var rowsPerStrip = Int.MAX_VALUE
        var stripOffsets = IntArray(0)
        var tileWidth = 0
        var tileLength = 0
        var tileOffsets = IntArray(0)
        var pixelScale: DoubleArray? = null
        var tiepoint: DoubleArray? = null
        var geoKeys: IntArray? = null
        var gdalNoData = Float.NaN

        for (i in 0 until entryCount) {
            val base = ifdOffset.toInt() + 2 + i * 12
            if (base + 12 > bytes.size) break
            val tag = buf.getShort(base).toInt() and 0xFFFF
            val type = buf.getShort(base + 2).toInt() and 0xFFFF
            val count = buf.getInt(base + 4)
            when (tag) {
                256 -> imageWidth = readIntScalar(buf, base + 8, type, order)
                257 -> imageHeight = readIntScalar(buf, base + 8, type, order)
                258 -> bitsPerSample = readIntArray(buf, base + 8, type, count, order).firstOrNull() ?: 32
                259 -> compression = readIntScalar(buf, base + 8, type, order)
                273 -> stripOffsets = readIntArray(buf, base + 8, type, count, order)
                278 -> rowsPerStrip = readIntScalar(buf, base + 8, type, order).coerceAtLeast(1)
                322 -> tileWidth = readIntScalar(buf, base + 8, type, order)
                323 -> tileLength = readIntScalar(buf, base + 8, type, order)
                324 -> tileOffsets = readIntArray(buf, base + 8, type, count, order)
                339 -> sampleFormat = readIntArray(buf, base + 8, type, count, order).firstOrNull() ?: 1
                33550 -> pixelScale = readDoubleArray(buf, base + 8, type, count, order)
                33922 -> tiepoint = readDoubleArray(buf, base + 8, type, count, order)
                34735 -> geoKeys = readIntArray(buf, base + 8, type, count, order)
                42113 -> {
                    val str = readAsciiTag(bytes, buf, base + 8, type, count)
                    gdalNoData = str?.trim()?.toFloatOrNull() ?: Float.NaN
                }
            }
        }

        if (compression != 1) {
            throw IllegalArgumentException(
                "Compressed GeoTIFF (compression=$compression) is not supported offline. Export as uncompressed GeoTIFF or Esri ASCII (.asc)."
            )
        }
        if (imageWidth <= 0 || imageHeight <= 0) {
            throw IllegalArgumentException("GeoTIFF missing valid ImageWidth/ImageLength.")
        }
        if (bitsPerSample !in listOf(8, 16, 32)) {
            throw IllegalArgumentException("Unsupported GeoTIFF BitsPerSample=$bitsPerSample (expected 8, 16, or 32).")
        }

        // If 32-bit without explicit SampleFormat tag, check IEEE float heuristic if values look float-encoded; default IEEE float when 32-bit elevation.
        val effectiveFormat = if (bitsPerSample == 32 && sampleFormat == 1) 3 else sampleFormat

        val bytesPerSample = bitsPerSample / 8
        val rawGrid = FloatArray(imageWidth * imageHeight) { Float.NaN }

        if (stripOffsets.isNotEmpty()) {
            val rps = rowsPerStrip.coerceIn(1, imageHeight)
            for (sIdx in stripOffsets.indices) {
                val startRow = sIdx * rps
                val endRow = min(imageHeight, startRow + rps)
                var pos = stripOffsets[sIdx]
                for (r in startRow until endRow) {
                    for (c in 0 until imageWidth) {
                        if (pos + bytesPerSample > bytes.size) break
                        rawGrid[r * imageWidth + c] = decodeSample(buf, pos, bitsPerSample, effectiveFormat)
                        pos += bytesPerSample
                    }
                }
            }
        } else if (tileOffsets.isNotEmpty() && tileWidth > 0 && tileLength > 0) {
            val tilesAcross = (imageWidth + tileWidth - 1) / tileWidth
            for (tIdx in tileOffsets.indices) {
                val tileRow = tIdx / tilesAcross
                val tileCol = tIdx % tilesAcross
                val rowStart = tileRow * tileLength
                val colStart = tileCol * tileWidth
                var pos = tileOffsets[tIdx]
                for (tr in 0 until tileLength) {
                    val dstRow = rowStart + tr
                    for (tc in 0 until tileWidth) {
                        val dstCol = colStart + tc
                        if (pos + bytesPerSample <= bytes.size && dstRow < imageHeight && dstCol < imageWidth) {
                            rawGrid[dstRow * imageWidth + dstCol] = decodeSample(buf, pos, bitsPerSample, effectiveFormat)
                        }
                        pos += bytesPerSample
                    }
                }
            }
        } else {
            throw IllegalArgumentException("GeoTIFF missing StripOffsets or TileOffsets.")
        }

        val resolvedBounds = resolveGeoTiffBounds(
            width = imageWidth,
            height = imageHeight,
            pixelScale = pixelScale,
            tiepoint = tiepoint,
            geoKeys = geoKeys,
            fallbackBounds = fallbackBounds
        )

        val (outCols, outRows, finalGrid) = downsampleIfNeeded(
            cols = imageWidth,
            rows = imageHeight,
            data = rawGrid,
            noData = gdalNoData,
            maxDim = MAX_LOCAL_RASTER_DIM
        )

        val midLat = (resolvedBounds.northLat + resolvedBounds.southLat) * 0.5
        val resMeters = ((resolvedBounds.eastLon - resolvedBounds.westLon) * 111_320.0 * cos(Math.toRadians(midLat))) /
            outCols.coerceAtLeast(1)

        return LidarElevationPatch(
            cols = outCols,
            rows = outRows,
            northLat = resolvedBounds.northLat,
            southLat = resolvedBounds.southLat,
            westLon = resolvedBounds.westLon,
            eastLon = resolvedBounds.eastLon,
            resolutionMeters = max(0.25, resMeters),
            elevationsMeters = finalGrid,
            noDataValue = gdalNoData,
            source = source,
            sourceLabel = sourceLabel
        )
    }

    /**
     * Writes a standard uncompressed 32-bit IEEE float stripped GeoTIFF in WGS84 (EPSG:4326)
     * that round-trips identically through [parseGeoTiffBytes] and opens natively in QGIS/GDAL.
     */
    fun buildGeoTiffBytes(patch: LidarElevationPatch): ByteArray {
        val out = ByteArrayOutputStream(
            1024 + patch.cols.coerceAtLeast(1) * patch.rows.coerceAtLeast(1) * 4
        )
        writeGeoTiffStream(patch, out)
        return out.toByteArray()
    }

    fun writeGeoTiffStream(
        patch: LidarElevationPatch,
        outputStream: OutputStream
    ) {
        val cols = patch.cols
        val rows = patch.rows
        require(cols > 0 && rows > 0 && patch.elevationsMeters.size >= cols * rows) {
            "Invalid elevation patch dimensions (${cols}x$rows)."
        }

        val scaleX = ((patch.eastLon - patch.westLon) / cols.toDouble()).coerceAtLeast(1e-12)
        val scaleY = ((patch.northLat - patch.southLat) / rows.toDouble()).coerceAtLeast(1e-12)
        val noDataExport = -9999f
        val noDataAscii = "-9999\u0000".toByteArray(Charsets.US_ASCII)

        val tagCount = 14
        val ifdOffset = 8
        val ifdSize = 2 + tagCount * 12 + 4
        val scaleOffset = ifdOffset + ifdSize               // 24 bytes (3 doubles)
        val tiepointOffset = scaleOffset + 24               // 48 bytes (6 doubles)
        val geoKeysOffset = tiepointOffset + 48             // 32 bytes (16 shorts)
        val noDataOffset = geoKeysOffset + 32               // 6 bytes ("-9999\0")
        val pixelOffset = noDataOffset + noDataAscii.size
        val stripByteCount = cols * rows * 4

        val headerBuf = ByteBuffer.allocate(pixelOffset).order(ByteOrder.LITTLE_ENDIAN)
        // TIFF header: Little-Endian ("II"), magic 42, IFD0 offset
        headerBuf.put('I'.code.toByte())
        headerBuf.put('I'.code.toByte())
        headerBuf.putShort(42)
        headerBuf.putInt(ifdOffset)

        // IFD0 entries in strictly ascending tag order
        headerBuf.position(ifdOffset)
        headerBuf.putShort(tagCount.toShort())

        fun putTag(tag: Int, type: Int, count: Int, valueOrOffset: Int) {
            headerBuf.putShort(tag.toShort())
            headerBuf.putShort(type.toShort())
            headerBuf.putInt(count)
            headerBuf.putInt(valueOrOffset)
        }

        putTag(256, 4, 1, cols)                   // ImageWidth (LONG)
        putTag(257, 4, 1, rows)                   // ImageLength (LONG)
        putTag(258, 3, 1, 32)                     // BitsPerSample (SHORT)
        putTag(259, 3, 1, 1)                      // Compression = None
        putTag(262, 3, 1, 1)                      // PhotometricInterpretation = BlackIsZero
        putTag(273, 4, 1, pixelOffset)            // StripOffsets (LONG)
        putTag(277, 3, 1, 1)                      // SamplesPerPixel (SHORT)
        putTag(278, 4, 1, rows)                   // RowsPerStrip (LONG)
        putTag(279, 4, 1, stripByteCount)         // StripByteCounts (LONG)
        putTag(339, 3, 1, 3)                      // SampleFormat = IEEE float (SHORT)
        putTag(33550, 12, 3, scaleOffset)         // ModelPixelScaleTag (DOUBLE[3])
        putTag(33922, 12, 6, tiepointOffset)      // ModelTiepointTag (DOUBLE[6])
        putTag(34735, 3, 16, geoKeysOffset)       // GeoKeyDirectoryTag (SHORT[16])
        putTag(42113, 2, noDataAscii.size, noDataOffset) // GDAL_NODATA (ASCII)
        headerBuf.putInt(0)                       // Next IFD = 0

        // ModelPixelScaleTag: [scaleX, scaleY, 0.0]
        headerBuf.position(scaleOffset)
        headerBuf.putDouble(scaleX)
        headerBuf.putDouble(scaleY)
        headerBuf.putDouble(0.0)

        // ModelTiepointTag: [0, 0, 0, westLon, northLat, 0]
        headerBuf.position(tiepointOffset)
        headerBuf.putDouble(0.0)
        headerBuf.putDouble(0.0)
        headerBuf.putDouble(0.0)
        headerBuf.putDouble(patch.westLon)
        headerBuf.putDouble(patch.northLat)
        headerBuf.putDouble(0.0)

        // GeoKeyDirectoryTag: WGS84 Geographic 2D (EPSG:4326)
        headerBuf.position(geoKeysOffset)
        val geoKeys = shortArrayOf(
            1, 1, 0, 3,
            1024, 0, 1, 2,    // GTModelTypeGeoKey = ModelTypeGeographic
            1025, 0, 1, 1,    // GTRasterTypeGeoKey = RasterPixelIsArea
            2048, 0, 1, 4326  // GeographicTypeGeoKey = WGS 84
        )
        geoKeys.forEach { headerBuf.putShort(it) }

        headerBuf.position(noDataOffset)
        headerBuf.put(noDataAscii)

        val buffered = BufferedOutputStream(outputStream, 32 * 1024)
        buffered.write(headerBuf.array())

        // Stream row-by-row in little-endian IEEE 754 32-bit float
        val rowBuffer = ByteBuffer.allocate(cols * 4).order(ByteOrder.LITTLE_ENDIAN)
        val srcNoData = patch.noDataValue
        for (r in 0 until rows) {
            rowBuffer.clear()
            val rowStart = r * cols
            for (c in 0 until cols) {
                val raw = patch.elevationsMeters[rowStart + c]
                val invalid = !raw.isFinite() ||
                    (!srcNoData.isNaN() && abs(raw - srcNoData) <= max(1e-3f, abs(srcNoData) * 1e-5f)) ||
                    raw !in -500f..9000f
                rowBuffer.putFloat(if (invalid) noDataExport else raw)
            }
            buffered.write(rowBuffer.array())
        }
        buffered.flush()
    }

    /**
     * Pure Kotlin Esri ASCII Grid (.asc) parser supporting WGS84 geographic coordinates or UTM Zone CRS.
     */
    fun parseEsriAsciiGrid(
        text: String,
        sourceLabel: String = LidarDataSource.LOCAL_ASCII_GRID.badgeLabel,
        utmZoneHint: Int? = null,
        isSouthernHemisphere: Boolean = false
    ): LidarElevationPatch {
        val reader = BufferedReader(InputStreamReader(text.byteInputStream(Charsets.UTF_8)))
        var ncols = 0
        var nrows = 0
        var xllCorner: Double? = null
        var yllCorner: Double? = null
        var xllCenter: Double? = null
        var yllCenter: Double? = null
        var cellSize = Double.NaN
        var noData = -9999f

        val dataTokens = ArrayList<String>()
        var readingHeader = true

        reader.forEachLine { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachLine
            val parts = trimmed.split(Regex("\\s+"))
            if (readingHeader && parts.size == 2) {
                val key = parts[0].lowercase(Locale.US)
                val valNum = parts[1].toDoubleOrNull()
                if (valNum != null) {
                    when (key) {
                        "ncols" -> { ncols = valNum.toInt(); return@forEachLine }
                        "nrows" -> { nrows = valNum.toInt(); return@forEachLine }
                        "xllcorner" -> { xllCorner = valNum; return@forEachLine }
                        "yllcorner" -> { yllCorner = valNum; return@forEachLine }
                        "xllcenter" -> { xllCenter = valNum; return@forEachLine }
                        "yllcenter" -> { yllCenter = valNum; return@forEachLine }
                        "cellsize" -> { cellSize = valNum; return@forEachLine }
                        "nodata_value" -> { noData = valNum.toFloat(); return@forEachLine }
                    }
                }
            }
            readingHeader = false
            dataTokens.addAll(parts)
        }

        if (ncols <= 0 || nrows <= 0 || cellSize.isNaN() || cellSize <= 0.0) {
            throw IllegalArgumentException("Invalid Esri ASCII Grid header (ncols=$ncols, nrows=$nrows, cellsize=$cellSize).")
        }
        val westX = xllCorner ?: xllCenter?.minus(cellSize * 0.5)
            ?: throw IllegalArgumentException("Esri ASCII Grid missing xllcorner / xllcenter.")
        val southY = yllCorner ?: yllCenter?.minus(cellSize * 0.5)
            ?: throw IllegalArgumentException("Esri ASCII Grid missing yllcorner / yllcenter.")

        val total = ncols * nrows
        val rawGrid = FloatArray(total) { Float.NaN }
        val limit = min(total, dataTokens.size)
        for (i in 0 until limit) {
            rawGrid[i] = dataTokens[i].toFloatOrNull() ?: Float.NaN
        }

        val eastX = westX + ncols * cellSize
        val northY = southY + nrows * cellSize

        val bounds = if (abs(westX) <= 180.0 && abs(eastX) <= 180.0 && abs(southY) <= 90.0 && abs(northY) <= 90.0 && cellSize < 0.05) {
            LidarGeoBounds(
                northLat = northY,
                southLat = southY,
                westLon = westX,
                eastLon = eastX
            )
        } else if (utmZoneHint != null && utmZoneHint in 1..60) {
            val (swLat, swLon) = utmToWgs84(westX, southY, utmZoneHint, isSouthernHemisphere)
            val (neLat, neLon) = utmToWgs84(eastX, northY, utmZoneHint, isSouthernHemisphere)
            LidarGeoBounds(
                northLat = max(swLat, neLat),
                southLat = min(swLat, neLat),
                westLon = min(swLon, neLon),
                eastLon = max(swLon, neLon)
            )
        } else {
            throw IllegalArgumentException(
                "ASCII grid uses projected map coordinates without WGS84 CRS bounds. Export as geographic WGS84 (.asc) or GeoTIFF (.tif)."
            )
        }

        val (outCols, outRows, finalGrid) = downsampleIfNeeded(
            cols = ncols,
            rows = nrows,
            data = rawGrid,
            noData = noData,
            maxDim = MAX_LOCAL_RASTER_DIM
        )
        val midLat = (bounds.northLat + bounds.southLat) * 0.5
        val resMeters = ((bounds.eastLon - bounds.westLon) * 111_320.0 * cos(Math.toRadians(midLat))) /
            outCols.coerceAtLeast(1)

        return LidarElevationPatch(
            cols = outCols,
            rows = outRows,
            northLat = bounds.northLat,
            southLat = bounds.southLat,
            westLon = bounds.westLon,
            eastLon = bounds.eastLon,
            resolutionMeters = max(0.25, resMeters),
            elevationsMeters = finalGrid,
            noDataValue = noData,
            source = LidarDataSource.LOCAL_ASCII_GRID,
            sourceLabel = sourceLabel
        )
    }

    private fun resolveGeoTiffBounds(
        width: Int,
        height: Int,
        pixelScale: DoubleArray?,
        tiepoint: DoubleArray?,
        geoKeys: IntArray?,
        fallbackBounds: LidarGeoBounds?
    ): LidarGeoBounds {
        if (pixelScale != null && pixelScale.size >= 2 && tiepoint != null && tiepoint.size >= 6) {
            val scaleX = pixelScale[0]
            val scaleY = pixelScale[1]
            val tieI = tiepoint[0]
            val tieJ = tiepoint[1]
            val tieX = tiepoint[3]
            val tieY = tiepoint[4]

            if (scaleX > 0.0 && scaleY > 0.0) {
                val minX = tieX - tieI * scaleX
                val maxY = tieY + tieJ * scaleY
                val maxX = minX + width * scaleX
                val minY = maxY - height * scaleY

                if (abs(minX) <= 180.0 && abs(maxX) <= 180.0 && abs(minY) <= 90.0 && abs(maxY) <= 90.0 && scaleX < 0.05) {
                    return LidarGeoBounds(
                        northLat = max(minY, maxY),
                        southLat = min(minY, maxY),
                        westLon = min(minX, maxX),
                        eastLon = max(minX, maxX)
                    )
                }

                val epsgCode = extractProjectedEpsgCode(geoKeys)
                val utmInfo = epsgToUtmZone(epsgCode)
                if (utmInfo != null) {
                    val (zone, isSouth) = utmInfo
                    val (swLat, swLon) = utmToWgs84(minX, minY, zone, isSouth)
                    val (neLat, neLon) = utmToWgs84(maxX, maxY, zone, isSouth)
                    return LidarGeoBounds(
                        northLat = max(swLat, neLat),
                        southLat = min(swLat, neLat),
                        westLon = min(swLon, neLon),
                        eastLon = max(swLon, neLon)
                    )
                }
            }
        }
        return fallbackBounds
            ?: throw IllegalArgumentException(
                "GeoTIFF missing WGS84 or supported UTM ModelTiepoint/GeoKeyDirectory CRS metadata."
            )
    }

    private fun extractProjectedEpsgCode(geoKeys: IntArray?): Int? {
        if (geoKeys == null || geoKeys.size < 4) return null
        val keyCount = geoKeys[3]
        for (k in 0 until keyCount) {
            val base = 4 + k * 4
            if (base + 3 >= geoKeys.size) break
            val keyId = geoKeys[base]
            val tiffTagLoc = geoKeys[base + 1]
            val count = geoKeys[base + 2]
            val valueOffset = geoKeys[base + 3]
            if (keyId == 3072 && tiffTagLoc == 0 && count == 1) {
                return valueOffset
            }
        }
        return null
    }

    private fun epsgToUtmZone(epsg: Int?): Pair<Int, Boolean>? {
        if (epsg == null) return null
        return when (epsg) {
            in 32601..32660 -> (epsg - 32600) to false
            in 32701..32760 -> (epsg - 32700) to true
            in 26901..26923 -> (epsg - 26900) to false
            else -> null
        }
    }

    internal fun utmToWgs84(
        easting: Double,
        northing: Double,
        zone: Int,
        southernHemisphere: Boolean
    ): Pair<Double, Double> {
        val a = 6378137.0
        val e2 = 0.0066943799901413165
        val ePrimeSq = e2 / (1.0 - e2)
        val k0 = 0.9996

        val x = easting - 500_000.0
        val y = if (southernHemisphere) northing - 10_000_000.0 else northing

        val m = y / k0
        val mu = m / (a * (1.0 - e2 / 4.0 - 3.0 * e2 * e2 / 64.0 - 5.0 * e2 * e2 * e2 / 256.0))
        val e1 = (1.0 - sqrt(1.0 - e2)) / (1.0 + sqrt(1.0 - e2))

        val phi1 = mu +
            (3.0 * e1 / 2.0 - 27.0 * e1.pow(3) / 32.0) * sin(2.0 * mu) +
            (21.0 * e1.pow(2) / 16.0 - 55.0 * e1.pow(4) / 32.0) * sin(4.0 * mu) +
            (151.0 * e1.pow(3) / 96.0) * sin(6.0 * mu) +
            (1097.0 * e1.pow(4) / 512.0) * sin(8.0 * mu)

        val sinPhi1 = sin(phi1)
        val cosPhi1 = cos(phi1)
        val tanPhi1 = tan(phi1)

        val n1 = a / sqrt(1.0 - e2 * sinPhi1 * sinPhi1)
        val t1 = tanPhi1 * tanPhi1
        val c1 = ePrimeSq * cosPhi1 * cosPhi1
        val r1 = a * (1.0 - e2) / (1.0 - e2 * sinPhi1 * sinPhi1).pow(1.5)
        val d = x / (n1 * k0)

        val latRad = phi1 - (n1 * tanPhi1 / r1) * (
            (d * d) / 2.0 -
                (5.0 + 3.0 * t1 + 10.0 * c1 - 4.0 * c1 * c1 - 9.0 * ePrimeSq) * d.pow(4) / 24.0 +
                (61.0 + 90.0 * t1 + 298.0 * c1 + 45.0 * t1 * t1 - 252.0 * ePrimeSq - 3.0 * c1 * c1) * d.pow(6) / 720.0
            )

        val lonOriginDeg = ((zone - 1) * 6 - 180 + 3).toDouble()
        val lonDeltaRad = (
            d -
                (1.0 + 2.0 * t1 + c1) * d.pow(3) / 6.0 +
                (5.0 - 2.0 * c1 + 28.0 * t1 - 3.0 * c1 * c1 + 8.0 * ePrimeSq + 24.0 * t1 * t1) * d.pow(5) / 120.0
            ) / cosPhi1

        return Math.toDegrees(latRad) to (lonOriginDeg + Math.toDegrees(lonDeltaRad))
    }

    private fun downsampleIfNeeded(
        cols: Int,
        rows: Int,
        data: FloatArray,
        noData: Float,
        maxDim: Int
    ): Triple<Int, Int, FloatArray> {
        if (cols <= maxDim && rows <= maxDim) {
            return Triple(cols, rows, data)
        }
        val scale = max(cols.toDouble() / maxDim, rows.toDouble() / maxDim)
        val outCols = (cols / scale).roundToInt().coerceIn(2, maxDim)
        val outRows = (rows / scale).roundToInt().coerceIn(2, maxDim)
        val out = FloatArray(outCols * outRows)
        for (r in 0 until outRows) {
            val srcR = ((r.toDouble() / (outRows - 1)) * (rows - 1)).roundToInt().coerceIn(0, rows - 1)
            for (c in 0 until outCols) {
                val srcC = ((c.toDouble() / (outCols - 1)) * (cols - 1)).roundToInt().coerceIn(0, cols - 1)
                val v = data[srcR * cols + srcC]
                out[r * outCols + c] = if (!noData.isNaN() && v == noData) Float.NaN else v
            }
        }
        return Triple(outCols, outRows, out)
    }

    private fun decodeSample(
        buf: ByteBuffer,
        offset: Int,
        bitsPerSample: Int,
        sampleFormat: Int
    ): Float {
        return when (bitsPerSample) {
            32 -> when (sampleFormat) {
                3 -> buf.getFloat(offset)
                2 -> buf.getInt(offset).toFloat()
                else -> (buf.getInt(offset).toLong() and 0xFFFFFFFFL).toFloat()
            }
            16 -> when (sampleFormat) {
                2 -> buf.getShort(offset).toFloat()
                else -> (buf.getShort(offset).toInt() and 0xFFFF).toFloat()
            }
            else -> (buf.get(offset).toInt() and 0xFF).toFloat()
        }
    }

    private fun readIntScalar(
        buf: ByteBuffer,
        valueOffsetPos: Int,
        type: Int,
        order: ByteOrder
    ): Int {
        return when (type) {
            3 -> if (order == ByteOrder.LITTLE_ENDIAN) {
                buf.getShort(valueOffsetPos).toInt() and 0xFFFF
            } else {
                buf.getShort(valueOffsetPos).toInt() and 0xFFFF
            }
            4 -> buf.getInt(valueOffsetPos)
            1 -> buf.get(valueOffsetPos).toInt() and 0xFF
            else -> buf.getInt(valueOffsetPos)
        }
    }

    private fun readIntArray(
        buf: ByteBuffer,
        valueOffsetPos: Int,
        type: Int,
        count: Int,
        order: ByteOrder
    ): IntArray {
        if (count <= 0) return IntArray(0)
        val elemSize = when (type) {
            1 -> 1
            3 -> 2
            4 -> 4
            else -> 4
        }
        val totalBytes = count * elemSize
        val startOffset = if (totalBytes <= 4) valueOffsetPos else buf.getInt(valueOffsetPos)
        if (startOffset < 0 || startOffset + totalBytes > buf.limit()) return IntArray(0)
        val out = IntArray(count)
        for (i in 0 until count) {
            val pos = startOffset + i * elemSize
            out[i] = when (type) {
                1 -> buf.get(pos).toInt() and 0xFF
                3 -> buf.getShort(pos).toInt() and 0xFFFF
                else -> buf.getInt(pos)
            }
        }
        return out
    }

    private fun readDoubleArray(
        buf: ByteBuffer,
        valueOffsetPos: Int,
        type: Int,
        count: Int,
        @Suppress("UNUSED_PARAMETER") order: ByteOrder
    ): DoubleArray {
        if (count <= 0) return DoubleArray(0)
        val elemSize = when (type) {
            12 -> 8
            11 -> 4
            else -> return DoubleArray(0)
        }
        val totalBytes = count * elemSize
        val startOffset = if (totalBytes <= 4) valueOffsetPos else buf.getInt(valueOffsetPos)
        if (startOffset < 0 || startOffset + totalBytes > buf.limit()) return DoubleArray(0)
        val out = DoubleArray(count)
        for (i in 0 until count) {
            val pos = startOffset + i * elemSize
            out[i] = if (type == 12) buf.getDouble(pos) else buf.getFloat(pos).toDouble()
        }
        return out
    }

    private fun readAsciiTag(
        bytes: ByteArray,
        buf: ByteBuffer,
        valueOffsetPos: Int,
        type: Int,
        count: Int
    ): String? {
        if (type != 2 || count <= 0) return null
        val start = if (count <= 4) valueOffsetPos else buf.getInt(valueOffsetPos)
        if (start < 0 || start + count > bytes.size) return null
        return String(bytes, start, count, Charsets.US_ASCII).trim('\u0000', ' ')
    }

    private fun buildCacheKey(lat: Double, lon: Double, radiusMeters: Int): String {
        val snapLat = (lat * 2000.0).roundToInt() / 2000.0
        val snapLon = (lon * 2000.0).roundToInt() / 2000.0
        val snapRadius = ((radiusMeters + 99) / 100) * 100
        return String.format(Locale.US, "lidar_v2_%.4f_%.4f_%dm", snapLat, snapLon, snapRadius)
    }

    private fun cacheDir(context: Context): File = File(context.filesDir, CACHE_DIR_NAME).apply {
        if (!exists()) mkdirs()
    }

    internal fun writeDiskCache(context: Context, key: String, patch: LidarElevationPatch) {
        runCatching {
            val outFile = File(cacheDir(context), "$key.bin")
            DataOutputStream(BufferedOutputStream(outFile.outputStream())).use { out ->
                out.writeInt(DISK_CACHE_MAGIC)
                out.writeInt(DISK_CACHE_VERSION)
                out.writeInt(patch.cols)
                out.writeInt(patch.rows)
                out.writeDouble(patch.northLat)
                out.writeDouble(patch.southLat)
                out.writeDouble(patch.westLon)
                out.writeDouble(patch.eastLon)
                out.writeDouble(patch.resolutionMeters)
                out.writeFloat(patch.noDataValue)
                out.writeUTF(patch.source.name)
                out.writeInt(patch.elevationsMeters.size)
                patch.elevationsMeters.forEach { out.writeFloat(it) }
            }
        }
    }

    internal fun readDiskCache(context: Context, key: String): LidarElevationPatch? {
        return runCatching {
            val file = File(cacheDir(context), "$key.bin")
            if (!file.exists()) return null
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                val magic = input.readInt()
                val version = input.readInt()
                if (magic != DISK_CACHE_MAGIC || version != DISK_CACHE_VERSION) return null
                val cols = input.readInt()
                val rows = input.readInt()
                val northLat = input.readDouble()
                val southLat = input.readDouble()
                val westLon = input.readDouble()
                val eastLon = input.readDouble()
                val resMeters = input.readDouble()
                val noData = input.readFloat()
                val rawSource = runCatching { LidarDataSource.valueOf(input.readUTF()) }
                    .getOrDefault(LidarDataSource.CACHED_3DEP_1M)
                val size = input.readInt()
                if (cols <= 0 || rows <= 0 || size != cols * rows) return null
                val elevations = FloatArray(size) { input.readFloat() }
                val effectiveSource = if (rawSource == LidarDataSource.USGS_3DEP_1M) {
                    LidarDataSource.CACHED_3DEP_1M
                } else {
                    rawSource
                }
                LidarElevationPatch(
                    cols = cols,
                    rows = rows,
                    northLat = northLat,
                    southLat = southLat,
                    westLon = westLon,
                    eastLon = eastLon,
                    resolutionMeters = resMeters,
                    elevationsMeters = elevations,
                    noDataValue = noData,
                    source = effectiveSource,
                    sourceLabel = effectiveSource.badgeLabel
                )
            }
        }.getOrNull()
    }
}
