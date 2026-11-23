package com.example.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import com.example.ui.BridgeUiState
import com.example.ui.MapBaseLayer
import com.example.ui.NodeMapUiState
import com.example.ui.ViewshedUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.views.MapView
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

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

object MapExportEngine {

    private const val EARTH_RADIUS_KM = 6371.0088
    private const val MAX_EXPORT_EDGE_PX = 2400
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

    fun formatDisplayTimestamp(epochMs: Long = System.currentTimeMillis()): String {
        return DISPLAY_TS_FORMATTER.format(Instant.ofEpochMilli(epochMs))
    }

    fun formatIsoTimestamp(epochMs: Long = System.currentTimeMillis()): String {
        return ISO_TS_FORMATTER.format(Instant.ofEpochMilli(epochMs))
    }

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
    // RFC 7946 GeoJSON Builders
    // -------------------------------------------------------------------------

    fun buildNodeMapGeoJson(
        uiState: NodeMapUiState,
        savedLayers: List<SavedViewshedLayer> = emptyList(),
        exportedAtIso: String = formatIsoTimestamp()
    ): String {
        val features = JSONArray()
        val plottedNodes = uiState.filteredMapNodes

        plottedNodes.forEach { node ->
            val lat = node.latitude ?: return@forEach
            val lon = node.longitude ?: return@forEach
            val props = JSONObject().apply {
                put("featureType", "mesh_node")
                put("id", node.id)
                putOpt("num", node.num)
                put("longName", node.displayLongName)
                put("shortName", node.displayShortName)
                put("classification", node.linkClassification.label)
                put("colorHex", node.effectiveColorHex)
                put("hasCustomColor", node.customColorArgb != null)
                put("isSelf", node.isSelf)
                put("isFavorite", node.isFavorite)
                put("viaMqtt", node.viaMqtt)
                put("isManual", node.isManual)
                putOpt("hopsAway", node.hopsAway)
                putOpt("hwModel", node.hwModel)
                putOpt("role", node.role)
                putOpt("altitudeM", node.altitude)
                putOpt("snr", node.snr)
                putOpt("rssi", node.rssi)
                putOpt("notes", node.notes?.takeIf { it.isNotBlank() })
            }
            features.put(pointFeature(lat, lon, node.altitude?.toDouble(), props))
        }

        // Direct RF sightlines from selfNode
        val self = uiState.selfNode?.takeIf { it.hasValidGps }
        if (uiState.showRfSightlines && self != null) {
            val sLat = self.latitude!!
            val sLon = self.longitude!!
            plottedNodes.filter { !it.isSelf && !it.viaMqtt && it.hopsAway == 0 }.forEach { peer ->
                val pLat = peer.latitude ?: return@forEach
                val pLon = peer.longitude ?: return@forEach
                val distKm = MeshGeoUtils.haversineKm(sLat, sLon, pLat, pLon)
                val bearing = MeshGeoUtils.bearingDegrees(sLat, sLon, pLat, pLon)
                val props = JSONObject().apply {
                    put("featureType", "rf_sightline")
                    put("fromId", self.id)
                    put("fromName", self.displayShortName)
                    put("toId", peer.id)
                    put("toName", peer.displayShortName)
                    put("distanceKm", round3(distKm))
                    put("bearingDeg", round1(bearing))
                    putOpt("snr", peer.snr)
                    putOpt("rssi", peer.rssi)
                }
                features.put(
                    lineStringFeature(
                        coords = listOf(sLat to sLon, pLat to pLon),
                        props = props,
                        alts = listOf(self.altitude?.toDouble(), peer.altitude?.toDouble())
                    )
                )
            }
        }

        // Measure RF link ruler
        val anchorA = uiState.measureAnchorA?.takeIf { it.hasValidGps }
        val anchorB = uiState.measureAnchorB?.takeIf { it.hasValidGps }
        if (anchorA != null && anchorB != null) {
            val distKm = MeshGeoUtils.haversineKm(
                anchorA.latitude!!,
                anchorA.longitude!!,
                anchorB.latitude!!,
                anchorB.longitude!!
            )
            val bearing = MeshGeoUtils.bearingDegrees(
                anchorA.latitude,
                anchorA.longitude,
                anchorB.latitude,
                anchorB.longitude
            )
            features.put(
                lineStringFeature(
                    coords = listOf(
                        anchorA.latitude to anchorA.longitude,
                        anchorB.latitude to anchorB.longitude
                    ),
                    props = JSONObject().apply {
                        put("featureType", "rf_measure_line")
                        put("fromName", anchorA.displayLongName)
                        put("toName", anchorB.displayLongName)
                        put("distanceKm", round3(distKm))
                        put("distanceMiles", round3(MeshGeoUtils.kmToMiles(distKm)))
                        put("bearingDeg", round1(bearing))
                    }
                )
            )
        }

        // LoS endpoints & path
        uiState.losPointA?.let { ptA ->
            features.put(
                pointFeature(
                    lat = ptA.latitude,
                    lon = ptA.longitude,
                    props = JSONObject().apply {
                        put("featureType", "los_endpoint_a")
                        put("label", ptA.label)
                        put("shortLabel", ptA.shortLabel)
                        put("txHeightMeters", uiState.losTxHeightText.toDoubleOrNull() ?: 2.0)
                    }
                )
            )
        }
        uiState.losPointB?.let { ptB ->
            features.put(
                pointFeature(
                    lat = ptB.latitude,
                    lon = ptB.longitude,
                    props = JSONObject().apply {
                        put("featureType", "los_endpoint_b")
                        put("label", ptB.label)
                        put("shortLabel", ptB.shortLabel)
                        put("rxHeightMeters", uiState.losRxHeightText.toDoubleOrNull() ?: 2.0)
                    }
                )
            )
        }
        uiState.losResult?.let { los ->
            features.put(
                lineStringFeature(
                    coords = listOf(
                        los.pointA.latitude to los.pointA.longitude,
                        los.pointB.latitude to los.pointB.longitude
                    ),
                    props = JSONObject().apply {
                        put("featureType", "los_path")
                        put("status", los.status.label)
                        put("viabilityTier", los.viabilityTier.label)
                        put("elevationSource", los.elevationSource.label)
                        put("totalDistanceKm", round3(los.totalDistanceKm))
                        put("fsplDb", round2(los.fsplDb))
                        put("diffractionLossDb", round2(los.diffractionLossDb))
                        put("totalPathLossDb", round2(los.totalPathLossDb))
                        put("predictedRssiDbm", round1(los.predictedRssiDbm))
                        put("linkMarginDb", round1(los.linkMarginDb))
                        put("maxFresnel1RadiusMeters", round2(los.maxFresnel1RadiusMeters))
                        put("worstClearanceMeters", round2(los.worstClearanceMeters))
                        putOpt("worstClearancePercentFresnel", los.worstClearancePercentFresnel?.let(::round1))
                        put("requiredEqualMastDeltaMeters", round1(los.requiredEqualMastDeltaMeters))
                        put("txHeightMeters", round1(los.txHeightMeters))
                        put("rxHeightMeters", round1(los.rxHeightMeters))
                        put("frequencyMhz", round3(los.frequencyMhz))
                    }
                )
            )
            val worst = los.worstSample
            features.put(
                pointFeature(
                    lat = worst.latitude,
                    lon = worst.longitude,
                    alt = worst.rawTerrainMeters,
                    props = JSONObject().apply {
                        put("featureType", "los_worst_obstruction")
                        put("distanceFromAKm", round3(worst.distanceFromAKm))
                        put("terrainElevationMeters", round1(worst.rawTerrainMeters))
                        put("earthBulgeMeters", round2(worst.earthBulgeMeters))
                        put("losRayMeters", round1(worst.losRayMeters))
                        put("clearanceMeters", round2(worst.clearanceMeters))
                        putOpt("clearancePercentFresnel", worst.clearancePercentFresnel?.let(::round1))
                    }
                )
            )
        }

        // Enabled saved viewshed layers
        savedLayers.filter { it.isEnabled }.forEach { layer ->
            appendSavedLayerFeatures(features, layer)
        }

        val metadata = JSONObject().apply {
            put("app", "LEMesh NodeDB Map")
            put("screen", "Node Map")
            put("baseLayer", uiState.baseLayer.displayName)
            put("exportedAt", exportedAtIso)
            putOpt("loadedFileName", uiState.loadedFileName)
            put("totalNodes", uiState.totalCount)
            put("plottedNodes", plottedNodes.size)
            put("rfSightlinesEnabled", uiState.showRfSightlines)
            put("favoritesOnly", uiState.favoritesOnly)
            put("activeSavedLayers", savedLayers.count { it.isEnabled })
            put("aggregateCoverageEnabled", uiState.isAggregateCoverageEnabled)
            uiState.aggregateCoverageResult?.takeIf { uiState.isAggregateCoverageEnabled }?.let { agg ->
                put("aggregateCoverageNodes", agg.contributingNodeCount)
                put("aggregateCoverageAreaKm2", round2(agg.estimatedCoveredAreaKm2))
                put("aggregatePeakRssiDbm", round1(agg.peakRssiDbm))
                put("aggregateRadiusKm", uiState.aggregateCoverageRadiusKm)
            }
        }

        return wrapFeatureCollection(features, metadata)
    }

    fun buildViewshedGeoJson(
        uiState: ViewshedUiState,
        baseLayerName: String = MapBaseLayer.DARK_MATTER.displayName,
        exportedAtIso: String = formatIsoTimestamp()
    ): String {
        val features = JSONArray()
        val txLat = uiState.txLat
        val txLon = uiState.txLon
        val rxLat = uiState.rxLat
        val rxLon = uiState.rxLon

        if (txLat != null && txLon != null) {
            features.put(
                pointFeature(
                    lat = txLat,
                    lon = txLon,
                    alt = uiState.groundElevationMeters,
                    props = JSONObject().apply {
                        put("featureType", "tx_site")
                        put("role", "TX")
                        put("label", uiState.siteLabel)
                        putOpt("groundElevationMeters", uiState.groundElevationMeters?.let(::round1))
                        put("antennaHeightMeters", round1(uiState.antennaHeightMeters.toDouble()))
                        putOpt("nodeId", uiState.sourceNode?.id)
                    }
                )
            )
        }

        if (rxLat != null && rxLon != null) {
            features.put(
                pointFeature(
                    lat = rxLat,
                    lon = rxLon,
                    alt = uiState.rxGroundElevationMeters,
                    props = JSONObject().apply {
                        put("featureType", "rx_site")
                        put("role", "RX")
                        put("label", uiState.rxLabel)
                        putOpt("groundElevationMeters", uiState.rxGroundElevationMeters?.let(::round1))
                        put("antennaHeightMeters", round1(uiState.rxAntennaHeightMeters.toDouble()))
                        putOpt("nodeId", uiState.rxNode?.id)
                    }
                )
            )
        }

        val rxLink = uiState.rxLinkResult
        if (rxLink != null) {
            val hop = rxLink.hop
            features.put(
                lineStringFeature(
                    coords = listOf(rxLink.txLat to rxLink.txLon, rxLink.rxLat to rxLink.rxLon),
                    props = JSONObject().apply {
                        put("featureType", "tx_rx_link_path")
                        put("status", hop.status.name)
                        put("distanceKm", round3(hop.distanceKm))
                        put("bearingDegrees", round1(rxLink.bearingDegrees))
                        put("predictedRssiDbm", round1(hop.predictedRssiDbm))
                        put("linkMarginDb", round1(hop.linkMarginDb))
                        put("worstClearanceMeters", round2(hop.worstClearanceMeters))
                        put("minFresnelRatio", round3(hop.minFresnelRatio))
                    }
                )
            )
            hop.worstObstruction?.let { obs ->
                features.put(
                    pointFeature(
                        lat = obs.latitude,
                        lon = obs.longitude,
                        alt = obs.terrainElevationMeters,
                        props = JSONObject().apply {
                            put("featureType", "tx_rx_worst_obstruction")
                            put("status", obs.status.name)
                            put("distanceFromOriginKm", round3(obs.distanceFromOriginKm))
                            put("terrainElevationMeters", round1(obs.terrainElevationMeters))
                            put("earthBulgeMeters", round2(obs.earthBulgeMeters))
                            put("effectiveTerrainMeters", round1(obs.effectiveTerrainMeters))
                            put("rayElevationMeters", round1(obs.rayElevationMeters))
                            put("clearanceMeters", round2(obs.clearanceMeters))
                            put("fresnelRatio", round3(obs.fresnelRatio))
                        }
                    )
                )
            }
        } else if (txLat != null && txLon != null && rxLat != null && rxLon != null) {
            features.put(
                lineStringFeature(
                    coords = listOf(txLat to txLon, rxLat to rxLon),
                    props = JSONObject().apply {
                        put("featureType", "tx_rx_path")
                        putOpt("distanceKm", uiState.directDistanceKm?.let(::round3))
                        putOpt("bearingDegrees", uiState.directBearingDeg?.let(::round1))
                    }
                )
            )
        }

        uiState.currentResult?.let { res ->
            features.put(
                polygonCircleFeature(
                    centerLat = res.params.originLat,
                    centerLon = res.params.originLon,
                    radiusKm = res.params.radiusKm.toDouble(),
                    props = JSONObject().apply {
                        put("featureType", "viewshed_coverage_ring")
                        put("originLabel", res.params.originLabel)
                        put("radiusKm", res.params.radiusKm)
                        put("coveredAreaKm2", round2(res.coveredAreaKm2))
                        put("maxUnobstructedRangeKm", round2(res.maxUnobstructedRangeKm))
                        put("clearNodeCount", res.coveredClearNodes.size)
                        put("tileCountUsed", res.tileCountUsed)
                    }
                )
            )
            res.coveredClearNodes.forEach { covered ->
                val nLat = covered.node.latitude ?: return@forEach
                val nLon = covered.node.longitude ?: return@forEach
                features.put(
                    pointFeature(
                        lat = nLat,
                        lon = nLon,
                        alt = covered.node.altitude?.toDouble(),
                        props = JSONObject().apply {
                            put("featureType", "covered_node")
                            put("id", covered.node.id)
                            put("longName", covered.node.displayLongName)
                            put("shortName", covered.node.displayShortName)
                            put("distanceKm", round3(covered.distanceKm))
                            put("predictedRssiDbm", round1(covered.predictedRssiDbm))
                            put("status", covered.status.name)
                        }
                    )
                )
            }
        }

        uiState.savedLayers.filter { it.isEnabled }.forEach { layer ->
            appendSavedLayerFeatures(features, layer)
        }

        val metadata = JSONObject().apply {
            put("app", "LEMesh NodeDB Map")
            put("screen", "Viewshed Planner")
            put("baseLayer", baseLayerName)
            put("exportedAt", exportedAtIso)
            put("radiusKm", uiState.radiusKm)
            put("frequencyMhz", uiState.frequencyText.toDoubleOrNull() ?: LosAnalyzer.DEFAULT_LORA_US_FREQ_MHZ)
            put("txPowerDbm", round1(uiState.txPowerDbm.toDouble()))
            put("antennaGainDbi", round1(uiState.antennaGainDbi.toDouble()))
            put("sensitivityDbm", round1(uiState.sensitivityDbm.toDouble()))
        }

        return wrapFeatureCollection(features, metadata)
    }

    fun buildBridgeGeoJson(
        uiState: BridgeUiState,
        baseLayerName: String = MapBaseLayer.DARK_MATTER.displayName,
        exportedAtIso: String = formatIsoTimestamp()
    ): String {
        val features = JSONArray()
        val txLat = uiState.txLat
        val txLon = uiState.txLon
        val rxLat = uiState.rxLat
        val rxLon = uiState.rxLon

        if (txLat != null && txLon != null) {
            features.put(
                pointFeature(
                    lat = txLat,
                    lon = txLon,
                    alt = uiState.txGroundElevationMeters,
                    props = JSONObject().apply {
                        put("featureType", "tx_site")
                        put("role", "TX")
                        put("label", uiState.txLabel)
                        putOpt("groundElevationMeters", uiState.txGroundElevationMeters?.let(::round1))
                        put("antennaHeightMeters", round1(uiState.txAntennaHeightMeters.toDouble()))
                        put("txPowerDbm", round1(uiState.txPowerDbm.toDouble()))
                        put("antennaGainDbi", round1(uiState.txAntennaGainDbi.toDouble()))
                        put("sensitivityDbm", round1(uiState.txSensitivityDbm.toDouble()))
                        putOpt("nodeId", uiState.txNode?.id)
                    }
                )
            )
        }

        if (rxLat != null && rxLon != null) {
            features.put(
                pointFeature(
                    lat = rxLat,
                    lon = rxLon,
                    alt = uiState.rxGroundElevationMeters,
                    props = JSONObject().apply {
                        put("featureType", "rx_site")
                        put("role", "RX")
                        put("label", uiState.rxLabel)
                        putOpt("groundElevationMeters", uiState.rxGroundElevationMeters?.let(::round1))
                        put("antennaHeightMeters", round1(uiState.rxAntennaHeightMeters.toDouble()))
                        put("txPowerDbm", round1(uiState.rxPowerDbm.toDouble()))
                        put("antennaGainDbi", round1(uiState.rxAntennaGainDbi.toDouble()))
                        put("sensitivityDbm", round1(uiState.rxSensitivityDbm.toDouble()))
                        putOpt("nodeId", uiState.rxNode?.id)
                    }
                )
            )
        }

        val result = uiState.currentResult
        if (result != null) {
            val base = result.directBaseline
            features.put(
                lineStringFeature(
                    coords = listOf(
                        result.params.txLat to result.params.txLon,
                        result.params.rxLat to result.params.rxLon
                    ),
                    props = JSONObject().apply {
                        put("featureType", "bridge_direct_baseline")
                        put("status", base.status.name)
                        put("distanceKm", round3(base.distanceKm))
                        put("predictedRssiDbm", round1(base.predictedRssiDbm))
                        put("linkMarginDb", round1(base.linkMarginDb))
                        put("reverseRssiDbm", round1(base.reverseRssiDbm))
                        put("reverseLinkMarginDb", round1(base.reverseLinkMarginDb))
                        put("bottleneckLinkMarginDb", round1(base.bottleneckLinkMarginDb))
                        put("worstClearanceMeters", round2(base.worstClearanceMeters))
                        put("minFresnelRatio", round3(base.minFresnelRatio))
                    }
                )
            )

            val selectedCandidate = result.topCandidates.getOrNull(uiState.selectedCandidateIndex)
                ?: result.topCandidates.firstOrNull()

            result.topCandidates.forEach { cand ->
                val isSelected = selectedCandidate != null && cand.rank == selectedCandidate.rank
                features.put(
                    pointFeature(
                        lat = cand.latitude,
                        lon = cand.longitude,
                        alt = cand.groundElevationMeters,
                        props = JSONObject().apply {
                            put("featureType", "relay_candidate")
                            put("rank", cand.rank)
                            put("isSelected", isSelected)
                            put("tier", cand.tier.name)
                            put("groundElevationMeters", round1(cand.groundElevationMeters))
                            put("relayAntennaHeightMeters", round1(result.params.relayAntennaHeightMeters))
                            put("relayPowerDbm", round1(result.params.relayPowerDbm))
                            put("relayAntennaGainDbi", round1(result.params.relayAntennaGainDbi))
                            put("relaySensitivityDbm", round1(result.params.relaySensitivityDbm))
                            put("worstLinkMarginDb", round1(cand.worstLinkMarginDb))
                            put("minFresnelRatio", round3(cand.minFresnelRatio))
                            put("inViewshedOverlap", cand.inViewshedOverlap)
                            put("txHopStatus", cand.txHop.status.name)
                            put("txHopDistanceKm", round3(cand.txHop.distanceKm))
                            put("txHopRssiDbm", round1(cand.txHop.predictedRssiDbm))
                            put("txHopMarginDb", round1(cand.txHop.linkMarginDb))
                            put("txHopReverseRssiDbm", round1(cand.txHop.reverseRssiDbm))
                            put("txHopReverseMarginDb", round1(cand.txHop.reverseLinkMarginDb))
                            put("txHopBottleneckMarginDb", round1(cand.txHop.bottleneckLinkMarginDb))
                            put("rxHopStatus", cand.rxHop.status.name)
                            put("rxHopDistanceKm", round3(cand.rxHop.distanceKm))
                            put("rxHopRssiDbm", round1(cand.rxHop.predictedRssiDbm))
                            put("rxHopMarginDb", round1(cand.rxHop.linkMarginDb))
                            put("rxHopReverseRssiDbm", round1(cand.rxHop.reverseRssiDbm))
                            put("rxHopReverseMarginDb", round1(cand.rxHop.reverseLinkMarginDb))
                            put("rxHopBottleneckMarginDb", round1(cand.rxHop.bottleneckLinkMarginDb))
                        }
                    )
                )
            }

            if (selectedCandidate != null) {
                features.put(
                    lineStringFeature(
                        coords = listOf(
                            result.params.txLat to result.params.txLon,
                            selectedCandidate.latitude to selectedCandidate.longitude
                        ),
                        props = JSONObject().apply {
                            put("featureType", "bridge_hop_tx_to_relay")
                            put("candidateRank", selectedCandidate.rank)
                            put("status", selectedCandidate.txHop.status.name)
                            put("distanceKm", round3(selectedCandidate.txHop.distanceKm))
                            put("predictedRssiDbm", round1(selectedCandidate.txHop.predictedRssiDbm))
                            put("linkMarginDb", round1(selectedCandidate.txHop.linkMarginDb))
                            put("reverseRssiDbm", round1(selectedCandidate.txHop.reverseRssiDbm))
                            put("reverseLinkMarginDb", round1(selectedCandidate.txHop.reverseLinkMarginDb))
                            put("bottleneckLinkMarginDb", round1(selectedCandidate.txHop.bottleneckLinkMarginDb))
                            put("worstClearanceMeters", round2(selectedCandidate.txHop.worstClearanceMeters))
                            put("minFresnelRatio", round3(selectedCandidate.txHop.minFresnelRatio))
                        }
                    )
                )
                features.put(
                    lineStringFeature(
                        coords = listOf(
                            selectedCandidate.latitude to selectedCandidate.longitude,
                            result.params.rxLat to result.params.rxLon
                        ),
                        props = JSONObject().apply {
                            put("featureType", "bridge_hop_relay_to_rx")
                            put("candidateRank", selectedCandidate.rank)
                            put("status", selectedCandidate.rxHop.status.name)
                            put("distanceKm", round3(selectedCandidate.rxHop.distanceKm))
                            put("predictedRssiDbm", round1(selectedCandidate.rxHop.predictedRssiDbm))
                            put("linkMarginDb", round1(selectedCandidate.rxHop.linkMarginDb))
                            put("reverseRssiDbm", round1(selectedCandidate.rxHop.reverseRssiDbm))
                            put("reverseLinkMarginDb", round1(selectedCandidate.rxHop.reverseLinkMarginDb))
                            put("bottleneckLinkMarginDb", round1(selectedCandidate.rxHop.bottleneckLinkMarginDb))
                            put("worstClearanceMeters", round2(selectedCandidate.rxHop.worstClearanceMeters))
                            put("minFresnelRatio", round3(selectedCandidate.rxHop.minFresnelRatio))
                        }
                    )
                )
            }

            val obstructions = RelayBridgeEngine.buildObstructionList(result, uiState.selectedCandidateIndex)
            obstructions.forEach { obs ->
                features.put(
                    pointFeature(
                        lat = obs.point.latitude,
                        lon = obs.point.longitude,
                        alt = obs.point.terrainElevationMeters,
                        props = JSONObject().apply {
                            put("featureType", "obstruction_peak")
                            put("obstructionId", obs.id)
                            put("segment", obs.segment.label)
                            put("rankWithinSegment", obs.rankWithinSegment)
                            put("status", obs.point.status.name)
                            put("distanceFromOriginKm", round3(obs.point.distanceFromOriginKm))
                            put("terrainElevationMeters", round1(obs.point.terrainElevationMeters))
                            put("earthBulgeMeters", round2(obs.point.earthBulgeMeters))
                            put("effectiveTerrainMeters", round1(obs.point.effectiveTerrainMeters))
                            put("rayElevationMeters", round1(obs.point.rayElevationMeters))
                            put("clearanceMeters", round2(obs.point.clearanceMeters))
                            put("fresnelRatio", round3(obs.point.fresnelRatio))
                        }
                    )
                )
            }
        } else if (txLat != null && txLon != null && rxLat != null && rxLon != null) {
            features.put(
                lineStringFeature(
                    coords = listOf(txLat to txLon, rxLat to rxLon),
                    props = JSONObject().apply {
                        put("featureType", "bridge_direct_baseline")
                        putOpt("distanceKm", uiState.directDistanceKm?.let(::round3))
                        putOpt("bearingDegrees", uiState.directBearingDeg?.let(::round1))
                    }
                )
            )
        }

        val metadata = JSONObject().apply {
            put("app", "LEMesh NodeDB Map")
            put("screen", "TX-RX Bridge Planner")
            put("baseLayer", baseLayerName)
            put("exportedAt", exportedAtIso)
            put("radiusKm", uiState.radiusKm)
            put("frequencyMhz", uiState.frequencyText.toDoubleOrNull() ?: LosAnalyzer.DEFAULT_LORA_US_FREQ_MHZ)
            put("bidirectional", true)
            put("txPowerDbm", round1(uiState.txPowerDbm.toDouble()))
            put("txAntennaGainDbi", round1(uiState.txAntennaGainDbi.toDouble()))
            put("txSensitivityDbm", round1(uiState.txSensitivityDbm.toDouble()))
            put("rxPowerDbm", round1(uiState.rxPowerDbm.toDouble()))
            put("rxAntennaGainDbi", round1(uiState.rxAntennaGainDbi.toDouble()))
            put("rxSensitivityDbm", round1(uiState.rxSensitivityDbm.toDouble()))
            put("relayPowerDbm", round1(uiState.relayPowerDbm.toDouble()))
            put("relayAntennaGainDbi", round1(uiState.relayAntennaGainDbi.toDouble()))
            put("relaySensitivityDbm", round1(uiState.relaySensitivityDbm.toDouble()))
            put("antennaGainDbi", round1(uiState.txAntennaGainDbi.toDouble()))
            put("sensitivityDbm", round1(uiState.txSensitivityDbm.toDouble()))
            put("selectedCandidateIndex", uiState.selectedCandidateIndex)
            result?.let {
                put("mutualOverlapAreaKm2", round2(it.mutualOverlapAreaKm2))
                put("candidatesEvaluated", it.candidatesEvaluated)
            }
        }

        return wrapFeatureCollection(features, metadata)
    }

    private fun appendSavedLayerFeatures(features: JSONArray, layer: SavedViewshedLayer) {
        features.put(
            pointFeature(
                lat = layer.originLat,
                lon = layer.originLon,
                alt = layer.groundElevationMeters,
                props = JSONObject().apply {
                    put("featureType", "saved_viewshed_origin")
                    put("layerId", layer.id)
                    put("layerName", layer.name)
                    put("originLabel", layer.originLabel)
                    put("antennaHeightMeters", round1(layer.antennaHeightMeters))
                    put("radiusKm", layer.radiusKm)
                    put("coveredAreaKm2", round2(layer.coveredAreaKm2))
                    put("maxUnobstructedRangeKm", round2(layer.maxUnobstructedRangeKm))
                    put("groundElevationMeters", round1(layer.groundElevationMeters))
                }
            )
        )
        features.put(
            polygonCircleFeature(
                centerLat = layer.originLat,
                centerLon = layer.originLon,
                radiusKm = layer.radiusKm.toDouble(),
                props = JSONObject().apply {
                    put("featureType", "saved_viewshed_boundary")
                    put("layerId", layer.id)
                    put("layerName", layer.name)
                    put("radiusKm", layer.radiusKm)
                    put("coveredAreaKm2", round2(layer.coveredAreaKm2))
                }
            )
        )
    }

    // -------------------------------------------------------------------------
    // Summary Metadata Builders for Annotated 2x PNG
    // -------------------------------------------------------------------------

    fun buildNodeMapSummary(
        uiState: NodeMapUiState,
        savedLayers: List<SavedViewshedLayer>
    ): MapExportSummary {
        val metrics = mutableListOf<String>()
        val sourceName = uiState.loadedFileName ?: "Standby"
        metrics += "$sourceName • ${uiState.plottedGpsCount}/${uiState.totalCount} GPS nodes (${uiState.rfCount} RF, ${uiState.mqttCount} MQTT)"

        val measureA = uiState.measureAnchorA
        val measureB = uiState.measureAnchorB
        if (measureA?.hasValidGps == true && measureB?.hasValidGps == true) {
            val km = MeshGeoUtils.haversineKm(measureA.latitude!!, measureA.longitude!!, measureB.latitude!!, measureB.longitude!!)
            metrics += String.format(
                Locale.US,
                "Measure: %s ↔ %s • %.2f km (%.2f mi)",
                measureA.displayShortName,
                measureB.displayShortName,
                km,
                MeshGeoUtils.kmToMiles(km)
            )
        }

        uiState.losResult?.let { los ->
            val fresnelStr = los.worstClearancePercentFresnel?.let { String.format(Locale.US, "%.0f%% F1", it) } ?: "—"
            metrics += String.format(
                Locale.US,
                "Fresnel: %s • %.2f km • %.1f dBm (%+.1f dB) • Diff %.1f dB (%s)",
                los.viabilityTier.label,
                los.totalDistanceKm,
                los.predictedRssiDbm,
                los.linkMarginDb,
                los.diffractionLossDb,
                fresnelStr
            )
        }

        val activeSaved = savedLayers.count { it.isEnabled }
        if (activeSaved > 0) {
            metrics += "$activeSaved saved viewshed layer(s) overlaid"
        }

        val activeAggregate = uiState.aggregateCoverageResult?.takeIf { uiState.isAggregateCoverageEnabled }
        if (activeAggregate != null) {
            metrics += String.format(
                Locale.US,
                "Aggregate RF: %d nodes • %.1f km² • peak %.0f dBm (%d km)",
                activeAggregate.contributingNodeCount,
                activeAggregate.estimatedCoveredAreaKm2,
                activeAggregate.peakRssiDbm,
                uiState.aggregateCoverageRadiusKm
            )
        }

        val legend = mutableListOf(
            ExportLegendItem("Self", 0xFFF59E0B.toInt(), ExportLegendShape.CIRCLE),
            ExportLegendItem("Favorite", 0xFFA855F7.toInt(), ExportLegendShape.CIRCLE),
            ExportLegendItem("Direct RF", 0xFF22C55E.toInt(), ExportLegendShape.CIRCLE),
            ExportLegendItem("Multi-Hop", 0xFF38BDF8.toInt(), ExportLegendShape.CIRCLE),
            ExportLegendItem("MQTT", 0xFF64748B.toInt(), ExportLegendShape.CIRCLE)
        )
        if (uiState.losResult != null || (measureA != null && measureB != null)) {
            legend += ExportLegendItem("RF / LoS Path", 0xFF38BDF8.toInt(), ExportLegendShape.LINE)
        }

        return MapExportSummary(
            screenTitle = "Node Map Export",
            subtitle = "Mesh Topology & Link Overlay",
            baseLayerName = uiState.baseLayer.displayName,
            timestampLabel = formatDisplayTimestamp(),
            metricLines = metrics,
            legendItems = legend,
            showRssiGradient = activeAggregate != null ||
                savedLayers.any { it.isEnabled && it.renderMode == LayerRenderMode.RSSI }
        )
    }

    fun buildViewshedSummary(
        uiState: ViewshedUiState,
        baseLayer: MapBaseLayer
    ): MapExportSummary {
        val metrics = mutableListOf<String>()
        val txCoord = if (uiState.txLat != null && uiState.txLon != null) {
            String.format(Locale.US, "%.4f, %.4f (%.0fm AGL)", uiState.txLat, uiState.txLon, uiState.antennaHeightMeters)
        } else {
            "Unset"
        }
        val rxCoord = if (uiState.rxLat != null && uiState.rxLon != null) {
            String.format(Locale.US, "%.4f, %.4f (%.0fm AGL)", uiState.rxLat, uiState.rxLon, uiState.rxAntennaHeightMeters)
        } else {
            "Unset"
        }
        metrics += "TX: $txCoord  •  RX: $rxCoord"

        uiState.currentResult?.let { res ->
            metrics += String.format(
                Locale.US,
                "Viewshed (%d km): %.1f km² clear • %.2f km max LoS • %d nodes • %s MHz",
                res.params.radiusKm,
                res.coveredAreaKm2,
                res.maxUnobstructedRangeKm,
                res.coveredClearNodes.size,
                uiState.frequencyText
            )
        } ?: run {
            metrics += "Radius: ${uiState.radiusKm} km • Freq: ${uiState.frequencyText} MHz • TX ${uiState.txPowerDbm.roundToInt()} dBm"
        }

        uiState.rxLinkResult?.let { link ->
            metrics += String.format(
                Locale.US,
                "TX↔RX: %s • %.2f km • %.1f dBm (%+.1f dB margin) • Min F1 %.0f%%",
                link.hop.status.name,
                link.hop.distanceKm,
                link.hop.predictedRssiDbm,
                link.hop.linkMarginDb,
                link.hop.minFresnelRatio * 100.0
            )
        }

        val legend = mutableListOf(
            ExportLegendItem("TX Site", 0xFF22C55E.toInt(), ExportLegendShape.PIN),
            ExportLegendItem("RX Site", 0xFF38BDF8.toInt(), ExportLegendShape.PIN),
            ExportLegendItem("Clear Link", 0xFF22C55E.toInt(), ExportLegendShape.LINE),
            ExportLegendItem("Grazing", 0xFFF59E0B.toInt(), ExportLegendShape.DASHED_LINE),
            ExportLegendItem("Obstructed", 0xFFEF4444.toInt(), ExportLegendShape.DASHED_LINE)
        )

        return MapExportSummary(
            screenTitle = "Viewshed Export",
            subtitle = "${uiState.siteLabel} RF Coverage",
            baseLayerName = baseLayer.displayName,
            timestampLabel = formatDisplayTimestamp(),
            metricLines = metrics,
            legendItems = legend,
            showRssiGradient = uiState.currentResult != null
        )
    }

    fun buildBridgeSummary(
        uiState: BridgeUiState,
        baseLayer: MapBaseLayer
    ): MapExportSummary {
        val metrics = mutableListOf<String>()
        val txStr = if (uiState.txLat != null && uiState.txLon != null) {
            String.format(Locale.US, "%.4f, %.4f (%.0fm)", uiState.txLat, uiState.txLon, uiState.txAntennaHeightMeters)
        } else "Unset"
        val rxStr = if (uiState.rxLat != null && uiState.rxLon != null) {
            String.format(Locale.US, "%.4f, %.4f (%.0fm)", uiState.rxLat, uiState.rxLon, uiState.rxAntennaHeightMeters)
        } else "Unset"
        metrics += String.format(
            Locale.US,
            "TX: %s [%ddBm/%.1fdBi] • RX: %s [%ddBm/%.1fdBi] • Relay: %dm [%ddBm/%.1fdBi]",
            txStr,
            uiState.txPowerDbm.roundToInt(),
            uiState.txAntennaGainDbi,
            rxStr,
            uiState.rxPowerDbm.roundToInt(),
            uiState.rxAntennaGainDbi,
            uiState.relayAntennaHeightMeters.roundToInt(),
            uiState.relayPowerDbm.roundToInt(),
            uiState.relayAntennaGainDbi
        )

        val result = uiState.currentResult
        if (result != null) {
            val selected = result.topCandidates.getOrNull(uiState.selectedCandidateIndex)
                ?: result.topCandidates.firstOrNull()
            val directStr = String.format(
                Locale.US,
                "Direct %.2f km (%s, fwd %+.1f / rev %+.1f dB)",
                result.directDistanceKm,
                result.directBaseline.status.name,
                result.directBaseline.linkMarginDb,
                result.directBaseline.reverseLinkMarginDb
            )
            val relayStr = if (selected != null) {
                String.format(
                    Locale.US,
                    "Relay #%d: %.4f, %.4f • %+.1f dB worst 2-way • %.1f km² overlap",
                    selected.rank,
                    selected.latitude,
                    selected.longitude,
                    selected.worstLinkMarginDb,
                    result.mutualOverlapAreaKm2
                )
            } else {
                "No relay candidates found"
            }
            metrics += "$directStr  •  $relayStr"

            val obsList = RelayBridgeEngine.buildObstructionList(result, uiState.selectedCandidateIndex)
            obsList.firstOrNull()?.let { worst ->
                metrics += String.format(
                    Locale.US,
                    "Top Pinch (%s): %.4f, %.4f • Terrain %.0fm • Clr %+.1fm (%.0f%% F1)",
                    worst.segment.label,
                    worst.point.latitude,
                    worst.point.longitude,
                    worst.point.terrainElevationMeters,
                    worst.point.clearanceMeters,
                    worst.point.fresnelRatio * 100.0
                )
            }
        } else {
            val dist = uiState.directDistanceKm?.let { String.format(Locale.US, "%.2f km", it) } ?: "Unset"
            metrics += "Direct span: $dist • Search radius: ${uiState.radiusKm} km • ${uiState.frequencyText} MHz"
        }

        val legend = listOf(
            ExportLegendItem("TX Pin", 0xFF22C55E.toInt(), ExportLegendShape.PIN),
            ExportLegendItem("RX Pin", 0xFF38BDF8.toInt(), ExportLegendShape.PIN),
            ExportLegendItem("Relay #1–#3", 0xFFF59E0B.toInt(), ExportLegendShape.CIRCLE),
            ExportLegendItem("Relay Hop", 0xFF22C55E.toInt(), ExportLegendShape.LINE),
            ExportLegendItem("Obstruction Peak", 0xFFEF4444.toInt(), ExportLegendShape.DIAMOND)
        )

        return MapExportSummary(
            screenTitle = "Bridge Solver Export",
            subtitle = "TX–RX Relay & Obstruction Documentation",
            baseLayerName = baseLayer.displayName,
            timestampLabel = formatDisplayTimestamp(),
            metricLines = metrics,
            legendItems = legend,
            showRssiGradient = result != null
        )
    }

    // -------------------------------------------------------------------------
    // High-Res 2x Map Capture & Tactical Documentation Compositor
    // -------------------------------------------------------------------------

    /**
     * Must be called on the Android Main thread because MapView & osmdroid overlays are UI-owned.
     */
    fun captureMapViewBitmap(
        mapView: MapView,
        scaleFactor: Float = 2f,
        maxEdgePx: Int = MAX_EXPORT_EDGE_PX
    ): Bitmap? {
        val w = mapView.width
        val h = mapView.height
        if (w <= 0 || h <= 0) return null

        val maxDim = max(w, h).toFloat()
        val effectiveScale = min(scaleFactor, maxEdgePx.toFloat() / maxDim).coerceAtLeast(1f)
        val targetW = (w * effectiveScale).roundToInt().coerceAtLeast(1)
        val targetH = (h * effectiveScale).roundToInt().coerceAtLeast(1)

        val bitmap = createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF0A0F14.toInt())
        canvas.save()
        canvas.scale(effectiveScale, effectiveScale)
        mapView.draw(canvas)
        canvas.restore()
        return bitmap
    }

    fun composeAnnotatedExportBitmap(
        mapBitmap: Bitmap,
        summary: MapExportSummary
    ): Bitmap {
        val width = max(640, mapBitmap.width)
        val mapHeight = mapBitmap.height
        val s = (width / 1080f).coerceIn(0.65f, 2.2f)

        val padH = (24f * s)
        val titleSize = 28f * s
        val subSize = 18f * s
        val metricSize = 16.5f * s
        val legendSize = 15.5f * s
        val metricLineHeight = 23f * s

        val metricCount = summary.metricLines.size.coerceAtLeast(1)
        val headerHeight = (30f * s + titleSize + 10f * s + subSize + 14f * s + metricCount * metricLineHeight + 18f * s)
            .roundToInt()

        // Measure legend wrapping
        val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = legendSize
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val itemGap = 20f * s
        val swatchW = 18f * s
        val maxContentW = width - padH * 2f
        var rowCount = 1
        var curRowX = 0f
        summary.legendItems.forEach { item ->
            val itemW = swatchW + 8f * s + legendPaint.measureText(item.label) + itemGap
            if (curRowX > 0f && curRowX + itemW > maxContentW) {
                rowCount++
                curRowX = itemW
            } else {
                curRowX += itemW
            }
        }
        val legendRowH = 28f * s
        val rssiBarExtra = if (summary.showRssiGradient) (38f * s) else 0f
        val footerHeight = (18f * s + rowCount * legendRowH + rssiBarExtra + 16f * s).roundToInt()

        val totalHeight = headerHeight + mapHeight + footerHeight
        val outBitmap = createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outBitmap)

        // Canvas base
        canvas.drawColor(0xFF0A0F14.toInt())

        // Header panel
        val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF111821.toInt()
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF233042.toInt()
            strokeWidth = max(1.5f, 2f * s)
            style = Paint.Style.STROKE
        }
        canvas.drawRect(0f, 0f, width.toFloat(), headerHeight.toFloat(), panelPaint)
        canvas.drawLine(0f, headerHeight.toFloat() - 1f, width.toFloat(), headerHeight.toFloat() - 1f, borderPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF8FAFC.toInt()
            textSize = titleSize
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF22C55E.toInt()
            textSize = subSize
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF94A3B8.toInt()
            textSize = subSize
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val timestampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF94A3B8.toInt()
            textSize = subSize * 0.92f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            textAlign = Paint.Align.RIGHT
        }
        val metricPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE2E8F0.toInt()
            textSize = metricSize
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }

        var y = 26f * s + titleSize
        canvas.drawText(summary.screenTitle, padH, y, titlePaint)
        canvas.drawText(summary.baseLayerName.uppercase(Locale.US), width - padH, y - 2f * s, badgePaint)

        y += 8f * s + subSize
        canvas.drawText(summary.subtitle, padH, y, subPaint)
        canvas.drawText(summary.timestampLabel, width - padH, y, timestampPaint)

        y += 14f * s
        summary.metricLines.forEach { line ->
            y += metricLineHeight * 0.85f
            val clipped = ellipsizeText(line, metricPaint, maxContentW)
            canvas.drawText(clipped, padH, y, metricPaint)
            y += metricLineHeight * 0.15f
        }

        // Draw Map Snapshot centered horizontally if padded
        val mapX = ((width - mapBitmap.width) / 2f).coerceAtLeast(0f)
        canvas.drawBitmap(mapBitmap, mapX, headerHeight.toFloat(), null)

        // Footer panel
        val footerTop = (headerHeight + mapHeight).toFloat()
        canvas.drawRect(0f, footerTop, width.toFloat(), totalHeight.toFloat(), panelPaint)
        canvas.drawLine(0f, footerTop + 1f, width.toFloat(), footerTop + 1f, borderPaint)

        legendPaint.color = 0xFFE2E8F0.toInt()
        val swatchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        var drawX = padH
        var rowY = footerTop + 22f * s + (legendSize * 0.6f)

        summary.legendItems.forEach { item ->
            val labelW = legendPaint.measureText(item.label)
            val totalItemW = swatchW + 8f * s + labelW + itemGap
            if (drawX > padH && drawX + totalItemW > width - padH) {
                drawX = padH
                rowY += legendRowH
            }
            drawLegendSwatch(
                canvas = canvas,
                paint = swatchPaint,
                shape = item.shape,
                colorArgb = item.colorArgb,
                cx = drawX + swatchW / 2f,
                cy = rowY - legendSize * 0.34f,
                radius = 6.5f * s
            )
            canvas.drawText(item.label, drawX + swatchW + 8f * s, rowY, legendPaint)
            drawX += totalItemW
        }

        if (summary.showRssiGradient) {
            val barTop = footerTop + footerHeight - 28f * s
            val barHeight = 10f * s
            val barLeft = padH + 110f * s
            val barRight = (width - padH - 85f * s).coerceAtLeast(barLeft + 100f * s)
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF94A3B8.toInt()
                textSize = 13.5f * s
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }
            canvas.drawText("RSSI -130", padH, barTop + barHeight, labelPaint)
            val gradPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    barLeft,
                    barTop,
                    barRight,
                    barTop,
                    intArrayOf(
                        0xFFF59E0B.toInt(),
                        0xFF38BDF8.toInt(),
                        0xFF22C55E.toInt()
                    ),
                    null,
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(
                RectF(barLeft, barTop, barRight, barTop + barHeight),
                barHeight / 2f,
                barHeight / 2f,
                gradPaint
            )
            canvas.drawText("-60 dBm", barRight + 10f * s, barTop + barHeight, labelPaint)
        }

        return outBitmap
    }

    private fun drawLegendSwatch(
        canvas: Canvas,
        paint: Paint,
        shape: ExportLegendShape,
        colorArgb: Int,
        cx: Float,
        cy: Float,
        radius: Float
    ) {
        paint.color = colorArgb
        when (shape) {
            ExportLegendShape.CIRCLE -> {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy, radius, paint)
            }
            ExportLegendShape.PIN -> {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy - radius * 0.15f, radius * 0.9f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = max(1.5f, radius * 0.25f)
                paint.color = 0xFFF8FAFC.toInt()
                canvas.drawCircle(cx, cy - radius * 0.15f, radius * 0.9f, paint)
            }
            ExportLegendShape.DIAMOND -> {
                paint.style = Paint.Style.FILL
                val path = Path().apply {
                    moveTo(cx, cy - radius * 1.15f)
                    lineTo(cx + radius * 1.15f, cy)
                    lineTo(cx, cy + radius * 1.15f)
                    lineTo(cx - radius * 1.15f, cy)
                    close()
                }
                canvas.drawPath(path, paint)
            }
            ExportLegendShape.LINE -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = max(2f, radius * 0.55f)
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(cx - radius * 1.3f, cy, cx + radius * 1.3f, cy, paint)
            }
            ExportLegendShape.DASHED_LINE -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = max(2f, radius * 0.5f)
                canvas.drawLine(cx - radius * 1.3f, cy, cx - radius * 0.25f, cy, paint)
                canvas.drawLine(cx + radius * 0.25f, cy, cx + radius * 1.3f, cy, paint)
            }
        }
    }

    fun encodeBitmapToPngBytes(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }

    // -------------------------------------------------------------------------
    // Zero-Permission SAF & Scoped FileProvider Delivery
    // -------------------------------------------------------------------------

    suspend fun writeBytesToUri(
        context: Context,
        uri: Uri,
        bytes: ByteArray
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val out = context.contentResolver.openOutputStream(uri)
                ?: error("Unable to open destination file stream")
            out.use { stream ->
                stream.write(bytes)
                stream.flush()
            }
        }
    }

    suspend fun writeShareCacheFile(
        context: Context,
        fileName: String,
        bytes: ByteArray
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
            // Keep export cache tidy
            exportDir.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(12)
                ?.forEach { it.delete() }

            val targetFile = File(exportDir, fileName)
            targetFile.outputStream().use { stream ->
                stream.write(bytes)
                stream.flush()
            }
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                targetFile
            )
        }
    }

    fun launchShareChooser(
        context: Context,
        uri: Uri,
        mimeType: String,
        title: String
    ) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    // -------------------------------------------------------------------------
    // Geometry & JSON Helpers
    // -------------------------------------------------------------------------

    private fun wrapFeatureCollection(features: JSONArray, metadata: JSONObject): String {
        return JSONObject().apply {
            put("type", "FeatureCollection")
            put("metadata", metadata)
            put("features", features)
        }.toString(2)
    }

    private fun pointFeature(
        lat: Double,
        lon: Double,
        alt: Double? = null,
        props: JSONObject
    ): JSONObject {
        return JSONObject().apply {
            put("type", "Feature")
            put(
                "geometry",
                JSONObject().apply {
                    put("type", "Point")
                    put("coordinates", coord(lat, lon, alt))
                }
            )
            put("properties", props)
        }
    }

    private fun lineStringFeature(
        coords: List<Pair<Double, Double>>,
        props: JSONObject,
        alts: List<Double?>? = null
    ): JSONObject {
        val arr = JSONArray()
        coords.forEachIndexed { idx, (lat, lon) ->
            arr.put(coord(lat, lon, alts?.getOrNull(idx)))
        }
        return JSONObject().apply {
            put("type", "Feature")
            put(
                "geometry",
                JSONObject().apply {
                    put("type", "LineString")
                    put("coordinates", arr)
                }
            )
            put("properties", props)
        }
    }

    private fun polygonCircleFeature(
        centerLat: Double,
        centerLon: Double,
        radiusKm: Double,
        props: JSONObject,
        segments: Int = 64
    ): JSONObject {
        val ring = JSONArray()
        val count = segments.coerceAtLeast(16)
        for (i in 0..count) {
            val bearingDeg = (i % count) * (360.0 / count)
            val (lat, lon) = destinationPoint(centerLat, centerLon, bearingDeg, radiusKm)
            ring.put(coord(lat, lon))
        }
        val rings = JSONArray().apply { put(ring) }
        return JSONObject().apply {
            put("type", "Feature")
            put(
                "geometry",
                JSONObject().apply {
                    put("type", "Polygon")
                    put("coordinates", rings)
                }
            )
            put("properties", props)
        }
    }

    private fun coord(lat: Double, lon: Double, alt: Double? = null): JSONArray {
        return JSONArray().apply {
            put(round6(lon))
            put(round6(lat))
            if (alt != null && alt.isFinite()) {
                put(round1(alt))
            }
        }
    }

    private fun destinationPoint(
        latDeg: Double,
        lonDeg: Double,
        bearingDeg: Double,
        distanceKm: Double
    ): Pair<Double, Double> {
        val angularDist = distanceKm / EARTH_RADIUS_KM
        val bearingRad = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(latDeg)
        val lon1 = Math.toRadians(lonDeg)

        val sinLat1 = sin(lat1)
        val cosLat1 = cos(lat1)
        val sinD = sin(angularDist)
        val cosD = cos(angularDist)

        val lat2 = asin((sinLat1 * cosD + cosLat1 * sinD * cos(bearingRad)).coerceIn(-1.0, 1.0))
        val lon2 = lon1 + atan2(
            sin(bearingRad) * sinD * cosLat1,
            cosD - sinLat1 * sin(lat2)
        )
        var outLon = Math.toDegrees(lon2)
        while (outLon > 180.0) outLon -= 360.0
        while (outLon < -180.0) outLon += 360.0
        return Math.toDegrees(lat2) to outLon
    }

    private fun ellipsizeText(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) {
            end--
        }
        return text.substring(0, end) + "…"
    }

    private fun round1(v: Double): Double = (v * 10.0).roundToInt() / 10.0
    private fun round2(v: Double): Double = (v * 100.0).roundToInt() / 100.0
    private fun round3(v: Double): Double = (v * 1000.0).roundToInt() / 1000.0
    private fun round6(v: Double): Double = Math.round(v * 1_000_000.0) / 1_000_000.0
}
