package us.lemesh.nodedb.data

import androidx.compose.ui.graphics.Color
import us.lemesh.nodedb.ui.theme.AlertErrorRed
import us.lemesh.nodedb.ui.theme.NodeDirectGreen
import us.lemesh.nodedb.ui.theme.NodeFavoritePurple
import us.lemesh.nodedb.ui.theme.NodeMqttSlate
import us.lemesh.nodedb.ui.theme.NodeMultiHopSky
import us.lemesh.nodedb.ui.theme.NodeSelfAmber
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class LinkClassification(
    val label: String,
    val color: Color,
    val androidColorInt: Int
) {
    SELF(
        label = "Self Node",
        color = NodeSelfAmber,
        androidColorInt = 0xFFF59E0B.toInt()
    ),
    FAVORITE(
        label = "Favorite",
        color = NodeFavoritePurple,
        androidColorInt = 0xFFA855F7.toInt()
    ),
    DIRECT_RF(
        label = "Direct RF (0-Hop)",
        color = NodeDirectGreen,
        androidColorInt = 0xFF22C55E.toInt()
    ),
    MULTI_HOP_RF(
        label = "Multi-Hop RF",
        color = NodeMultiHopSky,
        androidColorInt = 0xFF38BDF8.toInt()
    ),
    MQTT(
        label = "MQTT Bridged",
        color = NodeMqttSlate,
        androidColorInt = 0xFF64748B.toInt()
    )
}

data class DeviceMetrics(
    val batteryLevel: Int?,
    val voltage: Double?,
    val channelUtilization: Double?,
    val airUtilTx: Double?,
    val uptimeSeconds: Long?
)

data class EnvironmentMetrics(
    val temperature: Double?,
    val relativeHumidity: Double?,
    val barometricPressure: Double?
)

data class NeighborLinkTelemetry(
    val neighborNum: Long?,
    val neighborId: String,
    val snrDb: Double,
    val lastRxTime: Long? = null
)

enum class LinkDiagnosticTier(
    val label: String,
    val shortLabel: String,
    val color: Color,
    val androidColorInt: Int
) {
    NOMINAL(
        label = "Nominal (Within ±6 dB)",
        shortLabel = "Nominal",
        color = NodeDirectGreen,
        androidColorInt = 0xFF22C55E.toInt()
    ),
    CLUTTER_LOSS(
        label = "Clutter / Foliage Loss",
        shortLabel = "Clutter Loss",
        color = NodeSelfAmber,
        androidColorInt = 0xFFF59E0B.toInt()
    ),
    SEVERE_ANOMALY(
        label = "Severe Obstruction / Hardware Anomaly",
        shortLabel = "Severe Loss",
        color = AlertErrorRed,
        androidColorInt = 0xFFEF4444.toInt()
    )
}

data class LinkDiagnosticSummary(
    val observedSnrDb: Double,
    val predictedSnrDb: Double,
    val deltaSnrDb: Double,
    val observedRssiDbm: Int?,
    val predictedRssiDbm: Double,
    val deltaRssiDb: Double?,
    val tier: LinkDiagnosticTier,
    val diagnosticNote: String
)

data class NodeColorOption(
    val name: String,
    val argb: Int
) {
    val color: Color get() = Color(argb)
}

object NodeColorPalette {
    val PRESETS: List<NodeColorOption> = listOf(
        NodeColorOption("Emerald", 0xFF22C55E.toInt()),
        NodeColorOption("Sky", 0xFF38BDF8.toInt()),
        NodeColorOption("Amber", 0xFFF59E0B.toInt()),
        NodeColorOption("Purple", 0xFFA855F7.toInt()),
        NodeColorOption("Coral", 0xFFEF4444.toInt()),
        NodeColorOption("Rose", 0xFFF43F5E.toInt()),
        NodeColorOption("Indigo", 0xFF6366F1.toInt()),
        NodeColorOption("Teal", 0xFF14B8A6.toInt()),
        NodeColorOption("Lime", 0xFF84CC16.toInt()),
        NodeColorOption("Slate", 0xFF64748B.toInt())
    )

    fun colorName(argb: Int?): String? {
        if (argb == null) return null
        val rgb = argb and 0xFFFFFF
        return PRESETS.firstOrNull { (it.argb and 0xFFFFFF) == rgb }?.name
            ?: String.format(java.util.Locale.US, "#%06X", rgb)
    }

    fun parseColorHex(hex: String?): Int? {
        if (hex.isNullOrBlank()) return null
        val clean = hex.trim().removePrefix("#")
        return when (clean.length) {
            6 -> clean.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
            8 -> clean.toLongOrNull(16)?.toInt()
            else -> null
        }
    }
}

data class MeshNode(
    val num: Long?,
    val id: String,
    val longName: String,
    val shortName: String,
    val hwModel: String?,
    val role: String?,
    val latitude: Double?,
    val longitude: Double?,
    val altitude: Int?,
    val hopsAway: Int?,
    val viaMqtt: Boolean,
    val isFavorite: Boolean,
    val isSelf: Boolean,
    val snr: Double?,
    val rssi: Int?,
    val lastHeard: Long?,
    val deviceMetrics: DeviceMetrics?,
    val environmentMetrics: EnvironmentMetrics?,
    val rawJson: String,
    val customColorArgb: Int? = null,
    val isManual: Boolean = false,
    val notes: String? = null,
    val isEdited: Boolean = false,
    val maskLocationOnExport: Boolean = false,
    val neighborLinks: List<NeighborLinkTelemetry> = emptyList()
) {
    val hasValidGps: Boolean
        get() = latitude != null && longitude != null

    val linkClassification: LinkClassification
        get() = when {
            isSelf -> LinkClassification.SELF
            isFavorite -> LinkClassification.FAVORITE
            hopsAway == 0 && !viaMqtt -> LinkClassification.DIRECT_RF
            viaMqtt -> LinkClassification.MQTT
            else -> LinkClassification.MULTI_HOP_RF
        }

    val effectiveColorInt: Int
        get() = customColorArgb ?: linkClassification.androidColorInt

    val effectiveColor: Color
        get() = customColorArgb?.let { Color(it) } ?: linkClassification.color

    val effectiveColorHex: String
        get() = String.format(java.util.Locale.US, "#%06X", effectiveColorInt and 0xFFFFFF)

    val stableKey: String
        get() {
            val cleanId = id.trim().lowercase(java.util.Locale.US)
            if (cleanId.isNotEmpty()) return cleanId
            if (num != null) return "num:$num"
            return "anon:${longName.trim().lowercase(java.util.Locale.US)}_${shortName.trim().lowercase(java.util.Locale.US)}"
        }

    val displayLongName: String
        get() = longName.ifBlank { shortName.ifBlank { id } }

    val displayShortName: String
        get() = shortName.ifBlank {
            if (id.startsWith("!") && id.length >= 5) id.takeLast(4).uppercase()
            else id.take(4).uppercase()
        }

    fun matchesQuery(query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim().lowercase()
        return longName.lowercase().contains(q) ||
            shortName.lowercase().contains(q) ||
            id.lowercase().contains(q) ||
            (hwModel?.lowercase()?.contains(q) == true) ||
            (notes?.lowercase()?.contains(q) == true) ||
            (num?.toString()?.contains(q) == true)
    }
}

data class NodeDbParseResult(
    val nodes: List<MeshNode>,
    val selfNode: MeshNode?,
    val totalCount: Int,
    val plottedGpsCount: Int,
    val rfCount: Int,
    val mqttCount: Int,
    val noGpsCount: Int
)

data class DirectRfLink(
    val node: MeshNode,
    val distanceKm: Double,
    val distanceMiles: Double,
    val bearingDegrees: Double,
    val diagnostic: LinkDiagnosticSummary? = null,
    val predictedLos: LosAnalysisResult? = null
)

object MeshGeoUtils {
    private const val EARTH_RADIUS_KM = 6371.0088
    private const val KM_TO_MILES = 0.62137119

    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)

        val a = sin(dLat / 2).pow(2) +
            cos(rLat1) * cos(rLat2) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_KM * c
    }

    fun kmToMiles(km: Double): Double = km * KM_TO_MILES

    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(rLat2)
        val x = cos(rLat1) * sin(rLat2) - sin(rLat1) * cos(rLat2) * cos(dLon)
        val deg = Math.toDegrees(atan2(y, x))
        return (deg + 360.0) % 360.0
    }

    fun formatCardinalDirection(bearing: Double): String {
        val directions = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        val index = (((bearing + 11.25) % 360.0) / 22.5).toInt()
        return directions[index.coerceIn(0, directions.lastIndex)]
    }

    fun celsiusToFahrenheit(c: Double): Double = c * 9.0 / 5.0 + 32.0

    fun formatRelativeTime(epochSeconds: Long?, nowEpochSeconds: Long = Instant.now().epochSecond): String {
        if (epochSeconds == null || epochSeconds <= 0L) return "Unknown"
        val normalizedSeconds = if (epochSeconds > 10_000_000_000L) epochSeconds / 1000L else epochSeconds
        val diff = nowEpochSeconds - normalizedSeconds
        if (diff < 0) return "Just now"
        return when {
            diff < 60 -> "${diff}s ago"
            diff < 3600 -> "${diff / 60}m ago"
            diff < 86400 -> "${diff / 3600}h ${(diff % 3600) / 60}m ago"
            else -> "${diff / 86400}d ago"
        }
    }

    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
        .withZone(ZoneId.systemDefault())

    fun formatAbsoluteTime(epochSeconds: Long?): String {
        if (epochSeconds == null || epochSeconds <= 0L) return "Unknown"
        val normalizedSeconds = if (epochSeconds > 10_000_000_000L) epochSeconds / 1000L else epochSeconds
        return dateTimeFormatter.format(Instant.ofEpochSecond(normalizedSeconds))
    }
}
