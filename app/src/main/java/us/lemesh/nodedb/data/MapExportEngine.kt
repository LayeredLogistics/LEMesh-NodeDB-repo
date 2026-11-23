package us.lemesh.nodedb.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import org.osmdroid.views.MapView
import us.lemesh.nodedb.ui.BridgeUiState
import us.lemesh.nodedb.ui.MapBaseLayer
import us.lemesh.nodedb.ui.NodeMapUiState
import us.lemesh.nodedb.ui.ViewshedUiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class MapExportFormat(
    val extension: String,
    val mimeType: String,
    val displayLabel: String
) {
    KML(
        extension = "kml",
        mimeType = "application/vnd.google-earth.kml+xml",
        displayLabel = "KML (Google Maps / Earth)"
    ),
    NODEDB_JSON(
        extension = "json",
        mimeType = "application/json",
        displayLabel = "NodeDB JSON Backup"
    ),
    GEOJSON(
        extension = "geojson",
        mimeType = "application/geo+json",
        displayLabel = "GeoJSON"
    ),
    PNG(
        extension = "png",
        mimeType = "image/png",
        displayLabel = "PNG (2×)"
    ),
    HOST_OUTREACH_PNG(
        extension = "png",
        mimeType = "image/png",
        displayLabel = "Host Outreach Card (PNG)"
    )
}

enum class ExportLegendShape {
    CIRCLE,
    PIN,
    DIAMOND,
    LINE,
    DASHED_LINE
}

data class ExportLegendItem(
    val label: String,
    val colorArgb: Int,
    val shape: ExportLegendShape = ExportLegendShape.CIRCLE
)

data class MapExportSummary(
    val screenTitle: String,
    val subtitle: String,
    val baseLayerName: String,
    val timestampLabel: String,
    val metricLines: List<String>,
    val legendItems: List<ExportLegendItem>,
    val showRssiGradient: Boolean = false
)

/**
 * Clean thin export facade delegating KML serialization ([MapKmlExportBuilder]),
 * GeoJSON/report serialization ([GeoJsonReportExporter]), and bounded PNG snapshot
 * rendering & SAF delivery ([MapSnapshotRenderer]).
 */
object MapExportEngine {

    private val FILE_TS_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)
        .withZone(ZoneId.systemDefault())
    private val DISPLAY_TS_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z", Locale.US)
        .withZone(ZoneId.systemDefault())
    private val ISO_TS_FORMATTER = DateTimeFormatter.ISO_INSTANT

    fun suggestedFilename(
        prefix: String,
        format: MapExportFormat,
        epochMs: Long = System.currentTimeMillis()
    ): String {
        val safePrefix = prefix.lowercase(Locale.US).replace(Regex("[^a-z0-9_]+"), "_").trim('_')
            .ifEmpty { "lemesh_export" }
        val stamp = FILE_TS_FORMATTER.format(Instant.ofEpochMilli(epochMs))
        return "${safePrefix}_${stamp}.${format.extension}"
    }

    fun formatDisplayTimestamp(epochMs: Long = System.currentTimeMillis()): String =
        DISPLAY_TS_FORMATTER.format(Instant.ofEpochMilli(epochMs))

    fun formatIsoTimestamp(epochMs: Long = System.currentTimeMillis()): String =
        ISO_TS_FORMATTER.format(Instant.ofEpochMilli(epochMs))

    fun maskCoordinateApprox1Km(lat: Double, lon: Double): Pair<Double, Double> =
        GeoJsonReportExporter.maskCoordinateApprox1Km(lat, lon)

    // -------------------------------------------------------------------------
    // Google Maps / Google Earth KML Builders
    // -------------------------------------------------------------------------

    fun buildNodeMapKml(
        uiState: NodeMapUiState,
        savedLayers: List<SavedViewshedLayer> = emptyList(),
        exportedAtIso: String = formatIsoTimestamp()
    ): String = MapKmlExportBuilder.buildNodeMapKml(uiState, savedLayers, exportedAtIso)

    fun buildViewshedKml(
        uiState: ViewshedUiState,
        baseLayerName: String = MapBaseLayer.DARK_MATTER.displayName,
        exportedAtIso: String = formatIsoTimestamp()
    ): String = MapKmlExportBuilder.buildViewshedKml(uiState, baseLayerName, exportedAtIso)

    fun buildBridgeKml(
        uiState: BridgeUiState,
        baseLayerName: String = MapBaseLayer.DARK_MATTER.displayName,
        exportedAtIso: String = formatIsoTimestamp()
    ): String = MapKmlExportBuilder.buildBridgeKml(uiState, baseLayerName, exportedAtIso)

    // -------------------------------------------------------------------------
    // RFC 7946 GeoJSON & Report Builders
    // -------------------------------------------------------------------------

    fun buildNodeMapGeoJson(
        uiState: NodeMapUiState,
        savedLayers: List<SavedViewshedLayer> = emptyList(),
        exportedAtIso: String = formatIsoTimestamp()
    ): String = GeoJsonReportExporter.buildNodeMapGeoJson(uiState, savedLayers, exportedAtIso)

    fun buildViewshedGeoJson(
        uiState: ViewshedUiState,
        baseLayerName: String = MapBaseLayer.DARK_MATTER.displayName,
        exportedAtIso: String = formatIsoTimestamp()
    ): String = GeoJsonReportExporter.buildViewshedGeoJson(uiState, baseLayerName, exportedAtIso)

    fun buildBridgeGeoJson(
        uiState: BridgeUiState,
        baseLayerName: String = MapBaseLayer.DARK_MATTER.displayName,
        exportedAtIso: String = formatIsoTimestamp()
    ): String = GeoJsonReportExporter.buildBridgeGeoJson(uiState, baseLayerName, exportedAtIso)

    fun buildLinkBudgetReportMarkdown(
        bridgeResult: BridgeResult,
        selectedCandidateIndex: Int = 0,
        exportedAtIso: String = formatIsoTimestamp()
    ): String = GeoJsonReportExporter.buildLinkBudgetReportMarkdown(
        bridgeResult = bridgeResult,
        selectedCandidateIndex = selectedCandidateIndex,
        exportedAtIso = exportedAtIso
    )

    // -------------------------------------------------------------------------
    // Summary Metadata Builders for Annotated PNG
    // -------------------------------------------------------------------------

    fun buildNodeMapSummary(
        uiState: NodeMapUiState,
        savedLayers: List<SavedViewshedLayer>
    ): MapExportSummary = GeoJsonReportExporter.buildNodeMapSummary(uiState, savedLayers)

    fun buildViewshedSummary(
        uiState: ViewshedUiState,
        baseLayer: MapBaseLayer
    ): MapExportSummary = GeoJsonReportExporter.buildViewshedSummary(uiState, baseLayer)

    fun buildBridgeSummary(
        uiState: BridgeUiState,
        baseLayer: MapBaseLayer
    ): MapExportSummary = GeoJsonReportExporter.buildBridgeSummary(uiState, baseLayer)

    // -------------------------------------------------------------------------
    // Bounded Snapshot Capture & Delivery
    // -------------------------------------------------------------------------

    fun captureMapViewBitmap(
        mapView: MapView,
        scaleFactor: Float = 2f,
        maxEdgePx: Int = MapSnapshotRenderer.MAX_EXPORT_EDGE_PX
    ): Bitmap? = MapSnapshotRenderer.captureMapViewBitmap(mapView, scaleFactor, maxEdgePx)

    fun buildHostOutreachSpec(
        nodes: List<MeshNode>,
        viewshedState: ViewshedUiState? = null,
        bridgeState: BridgeUiState? = null,
        candidateSiteTitle: String? = null
    ): HostOutreachSpec = GeoJsonReportExporter.buildHostOutreachSpec(
        nodes = nodes,
        viewshedState = viewshedState,
        bridgeState = bridgeState,
        candidateSiteTitle = candidateSiteTitle
    )

    fun composeAnnotatedExportBitmap(
        mapBitmap: Bitmap,
        summary: MapExportSummary
    ): Bitmap = MapSnapshotRenderer.composeAnnotatedExportBitmap(mapBitmap, summary)

    fun composeHostOutreachCardBitmap(
        mapBitmap: Bitmap,
        spec: HostOutreachSpec
    ): Bitmap = MapSnapshotRenderer.composeHostOutreachCardBitmap(mapBitmap, spec)

    fun encodeBitmapToPngBytes(bitmap: Bitmap): ByteArray =
        MapSnapshotRenderer.encodeBitmapToPngBytes(bitmap)

    suspend fun writeBytesToUri(
        context: Context,
        uri: Uri,
        bytes: ByteArray
    ): Result<Unit> = MapSnapshotRenderer.writeBytesToUri(context, uri, bytes)

    suspend fun writeShareCacheFile(
        context: Context,
        fileName: String,
        bytes: ByteArray
    ): Result<Uri> = MapSnapshotRenderer.writeShareCacheFile(context, fileName, bytes)

    fun launchShareChooser(
        context: Context,
        uri: Uri,
        mimeType: String,
        title: String
    ) = MapSnapshotRenderer.launchShareChooser(context, uri, mimeType, title)
}
