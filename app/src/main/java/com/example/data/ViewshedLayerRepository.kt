package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

enum class LayerRenderMode {
    TINT,
    RSSI;

    companion object {
        fun fromStored(value: String?): LayerRenderMode {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: TINT
        }
    }
}

val LAYER_TINT_PALETTE: List<Int> = listOf(
    0xFF22C55E.toInt(), // Emerald
    0xFF06B6D4.toInt(), // Cyan
    0xFFA855F7.toInt(), // Purple
    0xFFF59E0B.toInt(), // Amber
    0xFF38BDF8.toInt(), // Sky
    0xFFEC4899.toInt(), // Pink
    0xFF84CC16.toInt(), // Lime
    0xFFF97316.toInt()  // Coral
)

data class SavedViewshedLayer(
    val id: String,
    val name: String,
    val createdAtEpochMs: Long,
    val originLat: Double,
    val originLon: Double,
    val originLabel: String,
    val northLat: Double,
    val southLat: Double,
    val westLon: Double,
    val eastLon: Double,
    val antennaHeightMeters: Double,
    val radiusKm: Int,
    val frequencyMhz: Double,
    val txPowerDbm: Double,
    val antennaGainDbi: Double,
    val sensitivityDbm: Double,
    val coveredAreaKm2: Double,
    val maxUnobstructedRangeKm: Double,
    val groundElevationMeters: Double,
    val isEnabled: Boolean,
    val renderMode: LayerRenderMode,
    val tintColorArgb: Int,
    val rssiBitmap: Bitmap,
    val tintedBitmap: Bitmap? = null,
    val polarField: PolarVisibilityField? = null
) {
    fun displayBitmap(): Bitmap {
        return when (renderMode) {
            LayerRenderMode.RSSI -> rssiBitmap
            LayerRenderMode.TINT -> tintedBitmap ?: rssiBitmap
        }
    }
}

fun createTintedLayerBitmap(source: Bitmap, tintColorArgb: Int): Bitmap {
    val width = source.width
    val height = source.height
    val count = width * height
    val pixels = IntArray(count)
    source.getPixels(pixels, 0, width, 0, 0, width, height)

    val tintR = Color.red(tintColorArgb)
    val tintG = Color.green(tintColorArgb)
    val tintB = Color.blue(tintColorArgb)

    for (i in 0 until count) {
        val alpha = (pixels[i] ushr 24) and 0xFF
        if (alpha != 0) {
            pixels[i] = (alpha shl 24) or (tintR shl 16) or (tintG shl 8) or tintB
        }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

object ViewshedLayerRepository {
    private const val DIR_NAME = "viewshed_layers"
    private const val INDEX_FILE_NAME = "layers_index.json"
    private val ioMutex = Mutex()

    private fun storageDir(context: Context): File {
        return File(context.filesDir, DIR_NAME).apply {
            if (!exists()) mkdirs()
        }
    }

    private fun indexFile(dir: File): File = File(dir, INDEX_FILE_NAME)

    private fun bitmapFile(dir: File, id: String): File = File(dir, "rssi_$id.png")

    private fun polarFile(dir: File, id: String): File = File(dir, "polar_$id.bin.gz")

    suspend fun loadAllLayers(context: Context): List<SavedViewshedLayer> = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val dir = storageDir(context)
            val index = indexFile(dir)
            if (!index.exists()) return@withLock emptyList()

            val raw = runCatching { index.readText(Charsets.UTF_8) }.getOrNull() ?: return@withLock emptyList()
            if (raw.isBlank()) return@withLock emptyList()

            val array = runCatching { JSONArray(raw) }.getOrNull() ?: return@withLock emptyList()
            val loaded = mutableListOf<SavedViewshedLayer>()

            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                val png = bitmapFile(dir, id)
                if (!png.exists()) continue

                val rssiBitmap = BitmapFactory.decodeFile(png.absolutePath) ?: continue
                val pFile = polarFile(dir, id)
                val polarField = if (pFile.exists()) {
                    runCatching { FileInputStream(pFile).use { PolarVisibilityField.readFromStream(it) } }.getOrNull()
                } else {
                    null
                }
                val renderMode = LayerRenderMode.fromStored(obj.optString("renderMode"))
                val tintArgb = obj.optInt("tintColorArgb", LAYER_TINT_PALETTE.first())
                val tinted = if (renderMode == LayerRenderMode.TINT) {
                    createTintedLayerBitmap(rssiBitmap, tintArgb)
                } else {
                    null
                }

                loaded.add(
                    SavedViewshedLayer(
                        id = id,
                        name = obj.optString("name", "Saved Viewshed"),
                        createdAtEpochMs = obj.optLong("createdAtEpochMs", 0L),
                        originLat = obj.optDouble("originLat", 0.0),
                        originLon = obj.optDouble("originLon", 0.0),
                        originLabel = obj.optString("originLabel", "Site"),
                        northLat = obj.optDouble("northLat", 0.0),
                        southLat = obj.optDouble("southLat", 0.0),
                        westLon = obj.optDouble("westLon", 0.0),
                        eastLon = obj.optDouble("eastLon", 0.0),
                        antennaHeightMeters = obj.optDouble("antennaHeightMeters", 10.0),
                        radiusKm = obj.optInt("radiusKm", 25),
                        frequencyMhz = obj.optDouble("frequencyMhz", LosAnalyzer.DEFAULT_LORA_US_FREQ_MHZ),
                        txPowerDbm = obj.optDouble("txPowerDbm", RelayBridgeEngine.DEFAULT_TX_POWER_DBM),
                        antennaGainDbi = obj.optDouble("antennaGainDbi", RelayBridgeEngine.DEFAULT_ANTENNA_GAIN_DBI),
                        sensitivityDbm = obj.optDouble("sensitivityDbm", RelayBridgeEngine.DEFAULT_SENSITIVITY_DBM),
                        coveredAreaKm2 = obj.optDouble("coveredAreaKm2", 0.0),
                        maxUnobstructedRangeKm = obj.optDouble("maxUnobstructedRangeKm", 0.0),
                        groundElevationMeters = obj.optDouble("groundElevationMeters", 0.0),
                        isEnabled = obj.optBoolean("isEnabled", true),
                        renderMode = renderMode,
                        tintColorArgb = tintArgb,
                        rssiBitmap = rssiBitmap,
                        tintedBitmap = tinted,
                        polarField = polarField
                    )
                )
            }
            loaded
        }
    }

    suspend fun saveNewLayer(
        context: Context,
        name: String,
        result: ViewshedResult,
        tintColorArgb: Int,
        renderMode: LayerRenderMode,
        existingLayers: List<SavedViewshedLayer>
    ): SavedViewshedLayer = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val copiedBitmap = result.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val tinted = if (renderMode == LayerRenderMode.TINT) {
            createTintedLayerBitmap(copiedBitmap, tintColorArgb)
        } else {
            null
        }

        val newLayer = SavedViewshedLayer(
            id = id,
            name = name.trim().ifEmpty { result.params.originLabel },
            createdAtEpochMs = System.currentTimeMillis(),
            originLat = result.params.originLat,
            originLon = result.params.originLon,
            originLabel = result.params.originLabel,
            northLat = result.northLat,
            southLat = result.southLat,
            westLon = result.westLon,
            eastLon = result.eastLon,
            antennaHeightMeters = result.params.antennaHeightMeters,
            radiusKm = result.params.radiusKm,
            frequencyMhz = result.params.frequencyMhz,
            txPowerDbm = result.params.txPowerDbm,
            antennaGainDbi = result.params.antennaGainDbi,
            sensitivityDbm = result.params.sensitivityDbm,
            coveredAreaKm2 = result.coveredAreaKm2,
            maxUnobstructedRangeKm = result.maxUnobstructedRangeKm,
            groundElevationMeters = result.groundElevationMeters,
            isEnabled = true,
            renderMode = renderMode,
            tintColorArgb = tintColorArgb,
            rssiBitmap = copiedBitmap,
            tintedBitmap = tinted,
            polarField = result.field
        )

        ioMutex.withLock {
            val dir = storageDir(context)
            val png = bitmapFile(dir, id)
            FileOutputStream(png).use { out ->
                copiedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
            }
            result.field?.let { field ->
                val pFile = polarFile(dir, id)
                runCatching {
                    FileOutputStream(pFile).use { out -> field.writeToStream(out) }
                }
            }
            writeIndexLocked(dir, listOf(newLayer) + existingLayers)
        }
        newLayer
    }

    suspend fun persistMetadata(
        context: Context,
        layers: List<SavedViewshedLayer>
    ) = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            writeIndexLocked(storageDir(context), layers)
        }
    }

    suspend fun deleteLayer(
        context: Context,
        layerId: String,
        remainingLayers: List<SavedViewshedLayer>
    ) = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val dir = storageDir(context)
            writeIndexLocked(dir, remainingLayers)
            val png = bitmapFile(dir, layerId)
            if (png.exists()) {
                png.delete()
            }
            val pFile = polarFile(dir, layerId)
            if (pFile.exists()) {
                pFile.delete()
            }
        }
    }

    private fun writeIndexLocked(dir: File, layers: List<SavedViewshedLayer>) {
        val array = JSONArray()
        layers.forEach { layer ->
            array.put(
                JSONObject().apply {
                    put("id", layer.id)
                    put("name", layer.name)
                    put("createdAtEpochMs", layer.createdAtEpochMs)
                    put("originLat", layer.originLat)
                    put("originLon", layer.originLon)
                    put("originLabel", layer.originLabel)
                    put("northLat", layer.northLat)
                    put("southLat", layer.southLat)
                    put("westLon", layer.westLon)
                    put("eastLon", layer.eastLon)
                    put("antennaHeightMeters", layer.antennaHeightMeters)
                    put("radiusKm", layer.radiusKm)
                    put("frequencyMhz", layer.frequencyMhz)
                    put("txPowerDbm", layer.txPowerDbm)
                    put("antennaGainDbi", layer.antennaGainDbi)
                    put("sensitivityDbm", layer.sensitivityDbm)
                    put("coveredAreaKm2", layer.coveredAreaKm2)
                    put("maxUnobstructedRangeKm", layer.maxUnobstructedRangeKm)
                    put("groundElevationMeters", layer.groundElevationMeters)
                    put("isEnabled", layer.isEnabled)
                    put("renderMode", layer.renderMode.name)
                    put("tintColorArgb", layer.tintColorArgb)
                }
            )
        }
        val target = indexFile(dir)
        val tmp = File(dir, "$INDEX_FILE_NAME.tmp")
        tmp.writeText(array.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(array.toString(), Charsets.UTF_8)
            tmp.delete()
        }
    }
}
