package us.lemesh.nodedb.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object ElevationRepository {

    private const val OPEN_METEO_ELEVATION_URL = "https://api.open-meteo.com/v1/elevation"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    suspend fun fetchElevations(
        coordinates: List<Pair<Double, Double>>,
        context: Context? = null
    ): Result<List<Double>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                if (TerrariumDemRepository.isOfflineOnlyEnabled(context)) {
                    throw OfflineTileMissingException(
                        coord = null,
                        tileLabel = "Open-Meteo network elevation blocked in Offline-Only DEM Mode"
                    )
                }
                if (coordinates.isEmpty()) {
                    throw IllegalArgumentException("No coordinates provided for elevation lookup.")
                }

                val latParam = coordinates.joinToString(",") { (lat, _) ->
                    String.format(Locale.US, "%.5f", lat)
                }
                val lonParam = coordinates.joinToString(",") { (_, lon) ->
                    String.format(Locale.US, "%.5f", lon)
                }

                val requestUrl = "$OPEN_METEO_ELEVATION_URL?latitude=$latParam&longitude=$lonParam"
                val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("Accept", "application/json")
                }

                try {
                    val statusCode = connection.responseCode
                    val stream = if (statusCode in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }
                    val body = stream?.use { input ->
                        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
                    }.orEmpty()

                    if (statusCode !in 200..299) {
                        val reason = runCatching { JSONObject(body).optString("reason") }.getOrNull()
                        throw IllegalStateException(
                            if (!reason.isNullOrBlank()) {
                                "Open-Meteo Elevation API error ($statusCode): $reason"
                            } else {
                                "Open-Meteo Elevation API returned HTTP $statusCode."
                            }
                        )
                    }

                    val json = JSONObject(body)
                    if (json.optBoolean("error", false)) {
                        val reason = json.optString("reason", "Unknown API error")
                        throw IllegalStateException("Open-Meteo Elevation API error: $reason")
                    }

                    val elevationArray = json.optJSONArray("elevation")
                        ?: throw IllegalStateException("Elevation array missing in API response.")

                    if (elevationArray.length() != coordinates.size) {
                        throw IllegalStateException(
                            "Expected ${coordinates.size} elevation points, received ${elevationArray.length()}."
                        )
                    }

                    List(elevationArray.length()) { idx ->
                        val v = elevationArray.optDouble(idx, Double.NaN)
                        if (v.isNaN() || v.isInfinite()) {
                            throw IllegalStateException("Invalid elevation value at sample $idx.")
                        }
                        v
                    }
                } finally {
                    connection.disconnect()
                }
            }
        }
    }
}
