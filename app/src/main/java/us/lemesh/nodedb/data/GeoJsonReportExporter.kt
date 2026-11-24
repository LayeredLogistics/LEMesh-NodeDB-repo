package us.lemesh.nodedb.data

import org.json.JSONArray
import org.json.JSONObject
import us.lemesh.nodedb.ui.BridgeUiState
import us.lemesh.nodedb.ui.LidarStreetUiState
import us.lemesh.nodedb.ui.MapBaseLayer
import us.lemesh.nodedb.ui.NodeMapUiState
import us.lemesh.nodedb.ui.ViewshedUiState
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

data class HostOutreachSite(
    val id: String,
    val label: String,
    val role: String,
    val latitude: Double,
    val longitude: Double,
    val isMaskedPortable: Boolean
)

data class HostOutreachRelayLink(
    val label: String,
    val distanceKm: Double,
    val distanceMiles: Double,
    val statusSummary: String
)

data class HostOutreachSpec(
    val siteTitle: String,
    val subtitle: String,
    val estimatedRadiusKm: Double,
    val coveredAreaKm2: Double?,
    val hardwareBadgeLines: List<String>,
    val relayLinks: List<HostOutreachRelayLink>,
    val includedSites: List<HostOutreachSite>,
    val omittedPersonalNodeCount: Int,
    val maskedPortableSiteCount: Int,
    val timestampLabel: String,
    val includesPropertyPhotos: Boolean = false
)

/**
 * Pure GeoJSON, export summary, Host Outreach Spec Card privacy filter, Markdown link-budget
 * report, and deterministic ~1 km coordinate obfuscation exporter.
 */
object GeoJsonReportExporter {

    private const val EARTH_RADIUS_KM = 6371.0088
    private const val MASK_GRID_DEG = 0.009 // ~1.0 km latitude cell

    val HOST_OUTREACH_HARDWARE_BADGES = listOf(
        "Self-Contained Solar",
        "Zero Utility Power",
        "Non-Penetrating Mount"
    )

    private val INFRASTRUCTURE_ROLES = setOf(
        "ROUTER",
        "REPEATER",
        "ROUTER_LATE",
        "ROUTER_CLIENT",
        "CLIENT_BASE"
    )

    private val PERSONAL_ROLES = setOf(
        "CLIENT",
        "CLIENT_MUTE",
        "CLIENT_HIDDEN",
        "TRACKER",
        "TAK",
        "TAK_TRACKER",
        "SENSOR",
        "LOST_AND_FOUND"
    )

    fun isPersonalOrHandheldNode(node: MeshNode): Boolean {
        if (node.isManual) return false
        if (node.isSelf && !isFixedInfrastructureRole(node.role)) return true
        val normalizedRole = node.role?.trim()?.uppercase(Locale.US)
        if (normalizedRole != null && normalizedRole in INFRASTRUCTURE_ROLES) return false
        if (normalizedRole != null && normalizedRole in PERSONAL_ROLES) return true
        // Non-manual nodes without an explicit infrastructure role are treated as personal/handheld
        return true
    }

    fun isPortableManualSite(node: MeshNode): Boolean {
        if (!node.isManual) return false
        if (node.maskLocationOnExport) return true
        val normalizedRole = node.role?.trim()?.uppercase(Locale.US)
        return normalizedRole == null || normalizedRole !in INFRASTRUCTURE_ROLES
    }

    private fun isFixedInfrastructureRole(role: String?): Boolean {
        val normalized = role?.trim()?.uppercase(Locale.US) ?: return false
        return normalized in INFRASTRUCTURE_ROLES
    }

    /**
     * Filters nodes for property-owner Host Outreach Cards:
     * - Omits all personal/handheld CLIENT nodes
     * - Masks portable/manual non-fixed sites to ~1 km grid cells
     * - Retains fixed infrastructure relay/router sites
     */
    fun filterNodesForHostOutreach(nodes: List<MeshNode>): Triple<List<HostOutreachSite>, Int, Int> {
        val included = mutableListOf<HostOutreachSite>()
        var omittedPersonal = 0
        var maskedPortable = 0

        nodes.forEach { node ->
            val lat = node.latitude
            val lon = node.longitude
            if (lat == null || lon == null) return@forEach

            if (isPersonalOrHandheldNode(node)) {
                omittedPersonal++
                return@forEach
            }

            val portable = isPortableManualSite(node)
            val (outLat, outLon) = if (portable) {
                maskedPortable++
                maskCoordinateApprox1Km(lat, lon)
            } else {
                round6(lat) to round6(lon)
            }

            included += HostOutreachSite(
                id = node.id,
                label = if (portable) "Community Relay Sector" else node.displayLongName,
                role = node.role ?: if (portable) "PORTABLE_RELAY" else "ROUTER",
                latitude = outLat,
                longitude = outLon,
                isMaskedPortable = portable
            )
        }
        return Triple(included, omittedPersonal, maskedPortable)
    }

    fun buildHostOutreachSpec(
        nodes: List<MeshNode>,
        viewshedState: ViewshedUiState? = null,
        bridgeState: BridgeUiState? = null,
        candidateSiteTitle: String? = null
    ): HostOutreachSpec {
        val (includedSites, omittedPersonal, maskedPortable) = filterNodesForHostOutreach(nodes)
        val viewshedRes = viewshedState?.currentResult
        val bridgeRes = bridgeState?.currentResult
        val selectedCand = bridgeRes?.topCandidates?.getOrNull(bridgeState.selectedCandidateIndex)
            ?: bridgeRes?.topCandidates?.firstOrNull()

        val title = candidateSiteTitle?.takeIf { it.isNotBlank() }
            ?: viewshedRes?.params?.originLabel?.takeIf { it.isNotBlank() }
            ?: selectedCand?.let { "Candidate Relay Site #${it.rank}" }
            ?: "Lake Effect Mesh — Candidate Relay Site"

        val estimatedRadiusKm = when {
            viewshedRes != null -> viewshedRes.maxUnobstructedRangeKm.takeIf { it > 0.1 }
                ?: viewshedRes.params.radiusKm.toDouble()
            bridgeRes != null -> bridgeRes.params.radiusKm.toDouble()
            else -> 25.0
        }
        val coveredAreaKm2 = viewshedRes?.coveredAreaKm2 ?: bridgeRes?.mutualOverlapAreaKm2

        val relayLinks = buildList {
            if (selectedCand != null) {
                add(
                    HostOutreachRelayLink(
                        label = "Relay ↔ ${bridgeRes?.params?.txLabel ?: "Site A"}",
                        distanceKm = selectedCand.txHop.distanceKm,
                        distanceMiles = MeshGeoUtils.kmToMiles(selectedCand.txHop.distanceKm),
                        statusSummary = "${selectedCand.txHop.status.name} • ${String.format(Locale.US, "%+.1f dB", selectedCand.txHop.bottleneckLinkMarginDb)}"
                    )
                )
                add(
                    HostOutreachRelayLink(
                        label = "Relay ↔ ${bridgeRes?.params?.rxLabel ?: "Site B"}",
                        distanceKm = selectedCand.rxHop.distanceKm,
                        distanceMiles = MeshGeoUtils.kmToMiles(selectedCand.rxHop.distanceKm),
                        statusSummary = "${selectedCand.rxHop.status.name} • ${String.format(Locale.US, "%+.1f dB", selectedCand.rxHop.bottleneckLinkMarginDb)}"
                    )
                )
            } else if (viewshedRes != null) {
                val infraClear = viewshedRes.coveredClearNodes
                    .filterNot { isPersonalOrHandheldNode(it.node) }
                    .take(4)
                infraClear.forEach { cov ->
                    add(
                        HostOutreachRelayLink(
                            label = "Site ↔ ${cov.node.displayShortName}",
                            distanceKm = cov.distanceKm,
                            distanceMiles = cov.distanceMiles,
                            statusSummary = String.format(Locale.US, "Clear LoS • %.0f dBm", cov.predictedRssiDbm)
                        )
                    )
                }
            }
        }

        val subtitle = buildString {
            append("Lake Effect Mesh (lemesh.us) • Community LoRa 915 MHz Infrastructure Proposal")
        }

        return HostOutreachSpec(
            siteTitle = title,
            subtitle = subtitle,
            estimatedRadiusKm = estimatedRadiusKm,
            coveredAreaKm2 = coveredAreaKm2,
            hardwareBadgeLines = HOST_OUTREACH_HARDWARE_BADGES,
            relayLinks = relayLinks,
            includedSites = includedSites,
            omittedPersonalNodeCount = omittedPersonal,
            maskedPortableSiteCount = maskedPortable,
            timestampLabel = MapExportEngine.formatDisplayTimestamp(),
            includesPropertyPhotos = false
        )
    }

    /**
     * Deterministically snaps coordinates to the center of an approximate 1 km x 1 km grid cell.
     * Applied exclusively during GeoJSON export for manual nodes with [MeshNode.maskLocationOnExport] == true.
     */
    fun maskCoordinateApprox1Km(lat: Double, lon: Double): Pair<Double, Double> {
        val maskedLat = (floor(lat / MASK_GRID_DEG) + 0.5) * MASK_GRID_DEG
        val cosLat = cos(Math.toRadians(lat)).coerceAtLeast(0.25)
        val lonStep = MASK_GRID_DEG / cosLat
        val maskedLon = (floor(lon / lonStep) + 0.5) * lonStep
        return round6(maskedLat.coerceIn(-85.0, 85.0)) to round6(maskedLon.coerceIn(-180.0, 180.0))
    }

    private fun exportLatLon(node: MeshNode): Pair<Double, Double>? {
        val lat = node.latitude ?: return null
        val lon = node.longitude ?: return null
        return if (node.isManual && node.maskLocationOnExport) {
            maskCoordinateApprox1Km(lat, lon)
        } else {
            lat to lon
        }
    }

    fun buildNodeMapGeoJson(
        uiState: NodeMapUiState,
        savedLayers: List<SavedViewshedLayer> = emptyList(),
        exportedAtIso: String = MapExportEngine.formatIsoTimestamp()
    ): String {
        val features = JSONArray()
        val plottedNodes = uiState.filteredMapNodes

        plottedNodes.forEach { node ->
            val (lat, lon) = exportLatLon(node) ?: return@forEach
            val isMasked = node.isManual && node.maskLocationOnExport
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
                if (isMasked) {
                    put("locationMasked", true)
                }
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

        val self = uiState.selfNode?.takeIf { it.hasValidGps }
        if (uiState.showRfSightlines && self != null) {
            val (sLat, sLon) = exportLatLon(self)!!
            plottedNodes.filter { !it.isSelf && !it.viaMqtt && it.hopsAway == 0 }.forEach { peer ->
                val (pLat, pLon) = exportLatLon(peer) ?: return@forEach
                val distKm = MeshGeoUtils.haversineKm(
                    self.latitude!!,
                    self.longitude!!,
                    peer.latitude!!,
                    peer.longitude!!
                )
                val bearing = MeshGeoUtils.bearingDegrees(
                    self.latitude,
                    self.longitude,
                    peer.latitude,
                    peer.longitude
                )
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

        val anchorA = uiState.measureAnchorA?.takeIf { it.hasValidGps }
        val anchorB = uiState.measureAnchorB?.takeIf { it.hasValidGps }
        if (anchorA != null && anchorB != null) {
            val (aExpLat, aExpLon) = exportLatLon(anchorA)!!
            val (bExpLat, bExpLon) = exportLatLon(anchorB)!!
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
                        aExpLat to aExpLon,
                        bExpLat to bExpLon
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

        savedLayers.filter { it.isEnabled }.forEach { layer ->
            appendSavedLayerFeatures(features, layer)
        }

        val maskedCount = plottedNodes.count { it.isManual && it.maskLocationOnExport && it.hasValidGps }
        val metadata = JSONObject().apply {
            put("app", "LEMesh NodeDB Map")
            put("screen", "Node Map")
            put("baseLayer", uiState.baseLayer.displayName)
            put("exportedAt", exportedAtIso)
            putOpt("loadedFileName", uiState.loadedFileName)
            put("totalNodes", uiState.totalCount)
            put("plottedNodes", plottedNodes.size)
            put("maskedManualNodes", maskedCount)
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
        exportedAtIso: String = MapExportEngine.formatIsoTimestamp()
    ): String {
        val features = JSONArray()
        val txLat = uiState.txLat
        val txLon = uiState.txLon
        val rxLat = uiState.rxLat
        val rxLon = uiState.rxLon

        if (txLat != null && txLon != null) {
            val maskTx = uiState.sourceNode?.let { it.isManual && it.maskLocationOnExport } == true
            val (outTxLat, outTxLon) = if (maskTx) maskCoordinateApprox1Km(txLat, txLon) else txLat to txLon
            features.put(
                pointFeature(
                    lat = outTxLat,
                    lon = outTxLon,
                    alt = uiState.groundElevationMeters,
                    props = JSONObject().apply {
                        put("featureType", "tx_site")
                        put("role", "TX")
                        put("label", uiState.siteLabel)
                        if (maskTx) put("locationMasked", true)
                        putOpt("groundElevationMeters", uiState.groundElevationMeters?.let(::round1))
                        put("antennaHeightMeters", round1(uiState.antennaHeightMeters.toDouble()))
                        putOpt("nodeId", uiState.sourceNode?.id)
                    }
                )
            )
        }

        if (rxLat != null && rxLon != null) {
            val maskRx = uiState.rxNode?.let { it.isManual && it.maskLocationOnExport } == true
            val (outRxLat, outRxLon) = if (maskRx) maskCoordinateApprox1Km(rxLat, rxLon) else rxLat to rxLon
            features.put(
                pointFeature(
                    lat = outRxLat,
                    lon = outRxLon,
                    alt = uiState.rxGroundElevationMeters,
                    props = JSONObject().apply {
                        put("featureType", "rx_site")
                        put("role", "RX")
                        put("label", uiState.rxLabel)
                        if (maskRx) put("locationMasked", true)
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
                val (nLat, nLon) = exportLatLon(covered.node) ?: return@forEach
                val isMasked = covered.node.isManual && covered.node.maskLocationOnExport
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
                            if (isMasked) put("locationMasked", true)
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
        exportedAtIso: String = MapExportEngine.formatIsoTimestamp()
    ): String {
        val features = JSONArray()
        val txLat = uiState.txLat
        val txLon = uiState.txLon
        val rxLat = uiState.rxLat
        val rxLon = uiState.rxLon

        if (txLat != null && txLon != null) {
            val maskTx = uiState.txNode?.let { it.isManual && it.maskLocationOnExport } == true
            val (outTxLat, outTxLon) = if (maskTx) maskCoordinateApprox1Km(txLat, txLon) else txLat to txLon
            features.put(
                pointFeature(
                    lat = outTxLat,
                    lon = outTxLon,
                    alt = uiState.txGroundElevationMeters,
                    props = JSONObject().apply {
                        put("featureType", "tx_site")
                        put("role", "TX")
                        put("label", uiState.txLabel)
                        if (maskTx) put("locationMasked", true)
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
            val maskRx = uiState.rxNode?.let { it.isManual && it.maskLocationOnExport } == true
            val (outRxLat, outRxLon) = if (maskRx) maskCoordinateApprox1Km(rxLat, rxLon) else rxLat to rxLon
            features.put(
                pointFeature(
                    lat = outRxLat,
                    lon = outRxLon,
                    alt = uiState.rxGroundElevationMeters,
                    props = JSONObject().apply {
                        put("featureType", "rx_site")
                        put("role", "RX")
                        put("label", uiState.rxLabel)
                        if (maskRx) put("locationMasked", true)
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

    fun buildLidarGeoJson(
        uiState: LidarStreetUiState,
        baseLayerName: String = MapBaseLayer.DARK_MATTER.displayName,
        exportedAtIso: String = MapExportEngine.formatIsoTimestamp()
    ): String {
        val features = JSONArray()
        val txLat = uiState.txLat
        val txLon = uiState.txLon

        if (txLat != null && txLon != null) {
            val maskTx = uiState.sourceNode?.let { it.isManual && it.maskLocationOnExport } == true
            val (outTxLat, outTxLon) = if (maskTx) maskCoordinateApprox1Km(txLat, txLon) else txLat to txLon
            features.put(
                pointFeature(
                    lat = outTxLat,
                    lon = outTxLon,
                    alt = uiState.groundElevationMeters,
                    props = JSONObject().apply {
                        put("featureType", "lidar_tx_site")
                        put("role", "TX")
                        put("label", uiState.siteLabel)
                        if (maskTx) put("locationMasked", true)
                        putOpt("groundElevationMeters", uiState.groundElevationMeters?.let(::round1))
                        put("txHeightFt", round1(uiState.txHeightFt.toDouble()))
                        put("txHeightMeters", round2(uiState.txHeightFt * LidarViewshedEngine.FEET_TO_METERS))
                        putOpt("nodeId", uiState.sourceNode?.id)
                    }
                )
            )
        }

        uiState.currentResult?.let { res ->
            features.put(
                polygonCircleFeature(
                    centerLat = res.params.originLat,
                    centerLon = res.params.originLon,
                    radiusKm = res.params.radiusMeters / 1000.0,
                    props = JSONObject().apply {
                        put("featureType", "lidar_viewshed_ring")
                        put("originLabel", res.params.originLabel)
                        put("dataSource", res.sourceLabel)
                        put("terrainResolutionMeters", round2(res.terrainResolutionMeters))
                        put("radiusMeters", res.params.radiusMeters)
                        put("coveredAreaSqMeters", round1(res.coveredAreaSqMeters))
                        put("coveredAreaAcres", round3(res.coveredAreaAcres))
                        put("maxClearRangeMeters", round1(res.maxClearRangeMeters))
                        put("corridorClearancePct", round1(res.corridorClearancePct))
                    }
                )
            )
        }

        val metadata = JSONObject().apply {
            put("app", "LEMesh NodeDB Map")
            put("screen", "LiDAR / Street Viewshed")
            put("baseLayer", baseLayerName)
            put("exportedAt", exportedAtIso)
            put("dataSource", uiState.activeSourceLabel)
            put("gridStepMeters", LidarViewshedEngine.STEP_METERS)
            uiState.currentResult?.let { res ->
                put("terrainResolutionMeters", round2(res.terrainResolutionMeters))
            }
            put("radiusMeters", uiState.radiusMeters)
            put("txHeightFt", round1(uiState.txHeightFt.toDouble()))
            put("rxHeightFt", round1(uiState.rxHeightFt.toDouble()))
            put("assumedRxRadio", "Semtech SX1262 LongFast (250 kHz, -114 dBm NF, -134 dBm)")
            put("frequencyMhz", uiState.frequencyText.toDoubleOrNull() ?: LosAnalyzer.DEFAULT_LORA_US_FREQ_MHZ)
            put("txPowerDbm", round1(uiState.txPowerDbm.toDouble()))
            put("antennaGainDbi", round1(uiState.antennaGainDbi.toDouble()))
            put("cableLossDb", round1(uiState.cableLossDb.toDouble()))
            put("sensitivityDbm", LidarViewshedEngine.SX1262_LONGFAST_SENSITIVITY_DBM)
        }

        return wrapFeatureCollection(features, metadata)
    }

    fun buildLinkBudgetReportMarkdown(
        bridgeResult: BridgeResult,
        selectedCandidateIndex: Int = 0,
        exportedAtIso: String = MapExportEngine.formatIsoTimestamp()
    ): String {
        val sb = StringBuilder()
        sb.appendLine("# Lake Effect Mesh — RF Bridge Link Budget Report")
        sb.appendLine("Generated: $exportedAtIso")
        sb.appendLine()
        sb.appendLine("## Direct Baseline")
        sb.appendLine(
            String.format(
                Locale.US,
                "- Distance: %.2f km | Status: %s | Forward Margin: %+.1f dB | Reverse Margin: %+.1f dB",
                bridgeResult.directDistanceKm,
                bridgeResult.directBaseline.status.name,
                bridgeResult.directBaseline.linkMarginDb,
                bridgeResult.directBaseline.reverseLinkMarginDb
            )
        )
        sb.appendLine()
        sb.appendLine("## Ranked Relay Candidates")
        bridgeResult.topCandidates.forEachIndexed { index, cand ->
            val marker = if (index == selectedCandidateIndex) " *(Selected)*" else ""
            sb.appendLine(
                String.format(
                    Locale.US,
                    "- #%d%s: (%.5f, %.5f) @ %.0fm | Worst 2-Way Margin: %+.1f dB | Min F1: %.0f%%",
                    cand.rank,
                    marker,
                    cand.latitude,
                    cand.longitude,
                    cand.groundElevationMeters,
                    cand.worstLinkMarginDb,
                    cand.minFresnelRatio * 100.0
                )
            )
        }
        return sb.toString()
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
            timestampLabel = MapExportEngine.formatDisplayTimestamp(),
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
            timestampLabel = MapExportEngine.formatDisplayTimestamp(),
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
            timestampLabel = MapExportEngine.formatDisplayTimestamp(),
            metricLines = metrics,
            legendItems = legend,
            showRssiGradient = result != null
        )
    }

    fun buildLidarSummary(
        uiState: LidarStreetUiState,
        baseLayer: MapBaseLayer
    ): MapExportSummary {
        val metrics = mutableListOf<String>()
        val txStr = if (uiState.txLat != null && uiState.txLon != null) {
            String.format(Locale.US, "%.5f, %.5f (%.0fft AGL)", uiState.txLat, uiState.txLon, uiState.txHeightFt)
        } else "Unset"
        metrics += "Source: ${uiState.activeSourceLabel} • TX: $txStr • RX ${uiState.rxHeightFt.roundToInt()}ft AGL (SX1262 -134 dBm)"

        uiState.currentResult?.let { res ->
            metrics += String.format(
                Locale.US,
                "Street Viewshed (%d m @ 1.0m): %.2f ac (%,.0f m²) • %.0f m (%.0f ft) max • %.1f%% >=60%% F1",
                res.params.radiusMeters,
                res.coveredAreaAcres,
                res.coveredAreaSqMeters,
                res.maxClearRangeMeters,
                res.maxClearRangeMeters * LidarViewshedEngine.METERS_TO_FEET,
                res.corridorClearancePct
            )
        } ?: run {
            metrics += "Radius: ${uiState.radiusMeters} m (${(uiState.radiusMeters * LidarViewshedEngine.METERS_TO_FEET).roundToInt()} ft) • Freq: ${uiState.frequencyText} MHz • TX ${uiState.txPowerDbm.roundToInt()} dBm"
        }

        val legend = listOf(
            ExportLegendItem("TX Site", 0xFFF59E0B.toInt(), ExportLegendShape.PIN),
            ExportLegendItem("Scan Radius", 0xFF22C55E.toInt(), ExportLegendShape.LINE)
        )

        return MapExportSummary(
            screenTitle = "LiDAR Street Viewshed Export",
            subtitle = "${uiState.siteLabel} • ${uiState.activeSourceLabel}",
            baseLayerName = baseLayer.displayName,
            timestampLabel = MapExportEngine.formatDisplayTimestamp(),
            metricLines = metrics,
            legendItems = legend,
            showRssiGradient = uiState.currentResult != null
        )
    }

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

    private fun round1(v: Double): Double = (v * 10.0).roundToInt() / 10.0
    private fun round2(v: Double): Double = (v * 100.0).roundToInt() / 100.0
    private fun round3(v: Double): Double = (v * 1000.0).roundToInt() / 1000.0
    private fun round6(v: Double): Double = Math.round(v * 1_000_000.0) / 1_000_000.0
}
