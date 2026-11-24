package us.lemesh.nodedb.data

import us.lemesh.nodedb.ui.BridgeUiState
import us.lemesh.nodedb.ui.LidarStreetUiState
import us.lemesh.nodedb.ui.MapBaseLayer
import us.lemesh.nodedb.ui.NodeMapUiState
import us.lemesh.nodedb.ui.ViewshedUiState
import java.util.Locale
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

object MapKmlExportBuilder {

    private const val EARTH_RADIUS_KM = 6371.0088
    private const val KML_NODE_ICON_URL = "https://maps.google.com/mapfiles/kml/shapes/placemark_circle.png"
    private const val KML_SITE_PIN_URL = "https://maps.google.com/mapfiles/kml/paddle/wht-blank.png"
    private const val KML_WARN_ICON_URL = "https://maps.google.com/mapfiles/kml/shapes/caution.png"

    fun argbToKmlColor(argb: Int): String {
        val a = (argb ushr 24) and 0xFF
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return String.format(Locale.US, "%02x%02x%02x%02x", a, b, g, r)
    }

    fun buildNodeMapKml(
        uiState: NodeMapUiState,
        savedLayers: List<SavedViewshedLayer> = emptyList(),
        exportedAtIso: String = MapExportEngine.formatIsoTimestamp()
    ): String {
        val plottedNodes = uiState.filteredMapNodes
        val sb = StringBuilder(4096)

        val uniqueNodeStyles = linkedMapOf<String, Int>()
        plottedNodes.forEach { node ->
            val styleId = nodeStyleId(node.effectiveColorInt, node.isSelf)
            uniqueNodeStyles.putIfAbsent(styleId, node.effectiveColorInt)
        }

        val lineStyles = linkedMapOf<String, Pair<Int, Double>>()
        val self = uiState.selfNode?.takeIf { it.hasValidGps }
        if (uiState.showRfSightlines && self != null) {
            plottedNodes.filter { !it.isSelf && !it.viaMqtt && it.hasValidGps }.forEach { peer ->
                val fresnel = uiState.sightlineFresnelResults[LosAnalyzer.nodeSightlineKey(peer)]
                val colorInt = fresnel?.viabilityTier?.androidColorInt
                    ?: if (peer.hopsAway == 0) 0xCC22C55E.toInt() else 0xB338BDF8.toInt()
                val width = if (peer.hopsAway == 0) 3.2 else 2.0
                lineStyles.putIfAbsent(lineStyleId(colorInt, width), colorInt to width)
            }
        }
        lineStyles.putIfAbsent(lineStyleId(0xFFF59E0B.toInt(), 3.5), 0xFFF59E0B.toInt() to 3.5)
        uiState.losResult?.let { los ->
            lineStyles.putIfAbsent(
                lineStyleId(los.viabilityTier.androidColorInt, 4.0),
                los.viabilityTier.androidColorInt to 4.0
            )
        }

        val polyStyles = linkedMapOf<String, Int>()
        savedLayers.filter { it.isEnabled }.forEach { layer ->
            polyStyles.putIfAbsent(polyStyleId(layer.tintColorArgb), layer.tintColorArgb)
        }

        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<kml xmlns="http://www.opengis.net/kml/2.2">""")
        sb.appendLine("  <Document>")
        val docName = if (!uiState.loadedFileName.isNullOrBlank()) {
            "${MapExportEngine.EXPORT_BRAND_ATTRIBUTION} — ${uiState.loadedFileName}"
        } else {
            "${MapExportEngine.EXPORT_BRAND_ATTRIBUTION} — Node Map"
        }
        sb.appendLine("    <name>${escapeXml(docName)}</name>")
        sb.appendLine("    <open>1</open>")
        sb.appendLine(
            "    <description><![CDATA[Exported from ${MapExportEngine.EXPORT_BRAND_ATTRIBUTION} ($exportedAtIso) • " +
                "Base: ${escapeXml(uiState.baseLayer.displayName)} • " +
                "${plottedNodes.size} GPS nodes plotted]]></description>"
        )

        uniqueNodeStyles.forEach { (styleId, colorInt) ->
            val isSelfStyle = styleId.startsWith("node_self_")
            appendIconStyle(
                sb = sb,
                styleId = styleId,
                argb = colorInt,
                scale = if (isSelfStyle) 1.35 else 1.15,
                iconHref = if (isSelfStyle) KML_SITE_PIN_URL else KML_NODE_ICON_URL
            )
        }
        appendIconStyle(
            sb = sb,
            styleId = "pin_endpoint_a",
            argb = 0xFFF59E0B.toInt(),
            scale = 1.2,
            iconHref = KML_SITE_PIN_URL
        )
        appendIconStyle(
            sb = sb,
            styleId = "pin_endpoint_b",
            argb = 0xFF38BDF8.toInt(),
            scale = 1.2,
            iconHref = KML_SITE_PIN_URL
        )
        appendIconStyle(
            sb = sb,
            styleId = "pin_obstruction",
            argb = 0xFFEF4444.toInt(),
            scale = 1.1,
            iconHref = KML_WARN_ICON_URL
        )

        lineStyles.forEach { (styleId, pair) ->
            appendLineStyle(sb, styleId, pair.first, pair.second)
        }
        polyStyles.forEach { (styleId, colorInt) ->
            appendPolyStyle(sb, styleId, colorInt)
        }

        // Folder 1: Mesh Nodes
        sb.appendLine("    <Folder>")
        sb.appendLine("      <name>Mesh Nodes</name>")
        sb.appendLine("      <open>1</open>")
        plottedNodes.forEach { node ->
            val lat = node.latitude ?: return@forEach
            val lon = node.longitude ?: return@forEach
            val styleId = nodeStyleId(node.effectiveColorInt, node.isSelf)
            val altStr = node.altitude?.let { ",$it" } ?: ",0"
            val htmlDesc = buildString {
                append("<b>${escapeXml(node.displayLongName)}</b> (${escapeXml(node.displayShortName)})<br/>")
                append("<b>ID:</b> ${escapeXml(node.id)}<br/>")
                append("<b>Classification:</b> ${escapeXml(node.linkClassification.label)}<br/>")
                append("<b>Color Code:</b> ${escapeXml(NodeColorPalette.colorName(node.customColorArgb) ?: "Auto")} (${node.effectiveColorHex})<br/>")
                if (node.isManual) append("<b>Origin:</b> Manual Node<br/>")
                else if (node.isEdited) append("<b>Origin:</b> Edited Imported Node<br/>")
                node.hwModel?.let { append("<b>Hardware:</b> ${escapeXml(it)}<br/>") }
                node.role?.let { append("<b>Role:</b> ${escapeXml(it)}<br/>") }
                node.hopsAway?.let { append("<b>Hops Away:</b> $it<br/>") }
                node.altitude?.let { append("<b>Altitude:</b> $it m<br/>") }
                node.snr?.let { append(String.format(Locale.US, "<b>SNR:</b> %.1f dB<br/>", it)) }
                node.rssi?.let { append("<b>RSSI:</b> $it dBm<br/>") }
                append("<b>Via MQTT:</b> ${node.viaMqtt} • <b>Favorite:</b> ${node.isFavorite}<br/>")
                node.notes?.takeIf { it.isNotBlank() }?.let {
                    append("<b>Notes:</b> ${escapeXml(it)}<br/>")
                }
            }
            sb.appendLine("      <Placemark>")
            sb.appendLine("        <name>${escapeXml(node.displayLongName)}</name>")
            sb.appendLine("        <styleUrl>#$styleId</styleUrl>")
            sb.appendLine("        <description><![CDATA[$htmlDesc]]></description>")
            appendExtendedData(
                sb = sb,
                indent = "        ",
                fields = listOfNotNull(
                    "id" to node.id,
                    "shortName" to node.displayShortName,
                    "classification" to node.linkClassification.label,
                    "colorHex" to node.effectiveColorHex,
                    "hwModel" to (node.hwModel ?: ""),
                    "role" to (node.role ?: ""),
                    "hopsAway" to (node.hopsAway?.toString() ?: ""),
                    "altitudeM" to (node.altitude?.toString() ?: ""),
                    "snr" to (node.snr?.let { String.format(Locale.US, "%.1f", it) } ?: ""),
                    "rssi" to (node.rssi?.toString() ?: ""),
                    "viaMqtt" to node.viaMqtt.toString(),
                    "isFavorite" to node.isFavorite.toString(),
                    "isSelf" to node.isSelf.toString(),
                    "isManual" to node.isManual.toString(),
                    node.notes?.takeIf { it.isNotBlank() }?.let { "notes" to it }
                )
            )
            sb.appendLine("        <Point>")
            sb.appendLine(String.format(Locale.US, "          <coordinates>%.6f,%.6f%s</coordinates>", lon, lat, altStr))
            sb.appendLine("        </Point>")
            sb.appendLine("      </Placemark>")
        }
        sb.appendLine("    </Folder>")

        // Folder 2: RF Sightlines
        if (uiState.showRfSightlines && self != null) {
            val sLat = self.latitude!!
            val sLon = self.longitude!!
            val peers = plottedNodes.filter { !it.isSelf && !it.viaMqtt && it.hasValidGps }
            if (peers.isNotEmpty()) {
                sb.appendLine("    <Folder>")
                sb.appendLine("      <name>RF Sightlines</name>")
                peers.forEach { peer ->
                    val pLat = peer.latitude ?: return@forEach
                    val pLon = peer.longitude ?: return@forEach
                    val fresnel = uiState.sightlineFresnelResults[LosAnalyzer.nodeSightlineKey(peer)]
                    val colorInt = fresnel?.viabilityTier?.androidColorInt
                        ?: if (peer.hopsAway == 0) 0xCC22C55E.toInt() else 0xB338BDF8.toInt()
                    val width = if (peer.hopsAway == 0) 3.2 else 2.0
                    val distKm = MeshGeoUtils.haversineKm(sLat, sLon, pLat, pLon)
                    val bearing = MeshGeoUtils.bearingDegrees(sLat, sLon, pLat, pLon)
                    val title = "${self.displayShortName} ↔ ${peer.displayShortName} (${String.format(Locale.US, "%.2f km", distKm)})"
                    val desc = buildString {
                        append("<b>Distance:</b> ${String.format(Locale.US, "%.2f km", distKm)} • <b>Bearing:</b> ${String.format(Locale.US, "%.1f°", bearing)}<br/>")
                        fresnel?.let { f ->
                            append("<b>Viability:</b> ${escapeXml(f.viabilityTier.label)}<br/>")
                            append(String.format(Locale.US, "<b>Predicted RSSI:</b> %.1f dBm (Margin %+.1f dB)<br/>", f.predictedRssiDbm, f.linkMarginDb))
                            append(String.format(Locale.US, "<b>Diffraction Loss:</b> %.1f dB<br/>", f.diffractionLossDb))
                            f.worstClearancePercentFresnel?.let {
                                append(String.format(Locale.US, "<b>Worst F1 Clearance:</b> %.0f%%<br/>", it))
                            }
                        }
                    }
                    appendLinePlacemark(
                        sb = sb,
                        indent = "      ",
                        name = title,
                        styleId = lineStyleId(colorInt, width),
                        descriptionHtml = desc,
                        coords = listOf(sLat to sLon, pLat to pLon)
                    )
                }
                sb.appendLine("    </Folder>")
            }
        }

        // Folder 3: Active RF Measure / Fresnel LoS
        val measureA = uiState.measureAnchorA?.takeIf { it.hasValidGps }
        val measureB = uiState.measureAnchorB?.takeIf { it.hasValidGps }
        val los = uiState.losResult
        if ((measureA != null && measureB != null) || los != null) {
            sb.appendLine("    <Folder>")
            sb.appendLine("      <name>Active RF Analysis</name>")
            if (measureA != null && measureB != null) {
                val distKm = MeshGeoUtils.haversineKm(
                    measureA.latitude!!, measureA.longitude!!,
                    measureB.latitude!!, measureB.longitude!!
                )
                appendLinePlacemark(
                    sb = sb,
                    indent = "      ",
                    name = "Measure: ${measureA.displayShortName} ↔ ${measureB.displayShortName}",
                    styleId = lineStyleId(0xFFF59E0B.toInt(), 3.5),
                    descriptionHtml = String.format(Locale.US, "<b>Distance:</b> %.2f km (%.2f mi)", distKm, MeshGeoUtils.kmToMiles(distKm)),
                    coords = listOf(
                        measureA.latitude to measureA.longitude,
                        measureB.latitude to measureB.longitude
                    )
                )
            }
            if (los != null) {
                val desc = String.format(
                    Locale.US,
                    "<b>Status:</b> %s (%s)<br/><b>Distance:</b> %.2f km<br/><b>RSSI:</b> %.1f dBm (%+.1f dB margin)<br/><b>Diffraction:</b> %.1f dB<br/><b>Max F1:</b> %.1f m",
                    los.status.label,
                    los.viabilityTier.label,
                    los.totalDistanceKm,
                    los.predictedRssiDbm,
                    los.linkMarginDb,
                    los.diffractionLossDb,
                    los.maxFresnel1RadiusMeters
                )
                appendLinePlacemark(
                    sb = sb,
                    indent = "      ",
                    name = "Fresnel LoS: ${los.pointA.shortLabel} → ${los.pointB.shortLabel}",
                    styleId = lineStyleId(los.viabilityTier.androidColorInt, 4.0),
                    descriptionHtml = desc,
                    coords = listOf(
                        los.pointA.latitude to los.pointA.longitude,
                        los.pointB.latitude to los.pointB.longitude
                    )
                )
                appendPointPlacemark(
                    sb = sb,
                    indent = "      ",
                    name = "Worst LoS Point (${String.format(Locale.US, "%.1f m", los.worstClearanceMeters)})",
                    styleId = "pin_obstruction",
                    descriptionHtml = String.format(
                        Locale.US,
                        "<b>Terrain:</b> %.1f m<br/><b>Clearance:</b> %.1f m",
                        los.worstSample.rawTerrainMeters,
                        los.worstClearanceMeters
                    ),
                    lat = los.worstSample.latitude,
                    lon = los.worstSample.longitude,
                    alt = los.worstSample.rawTerrainMeters
                )
            }
            sb.appendLine("    </Folder>")
        }

        // Folder 4: Saved Viewshed Layers
        val enabledLayers = savedLayers.filter { it.isEnabled }
        if (enabledLayers.isNotEmpty()) {
            sb.appendLine("    <Folder>")
            sb.appendLine("      <name>Saved Viewshed Layers</name>")
            enabledLayers.forEach { layer ->
                appendCirclePolygonPlacemark(
                    sb = sb,
                    indent = "      ",
                    name = "${layer.name} (${layer.radiusKm} km)",
                    styleId = polyStyleId(layer.tintColorArgb),
                    descriptionHtml = String.format(
                        Locale.US,
                        "<b>Origin:</b> %s<br/><b>Covered Area:</b> %.2f km²<br/><b>Max Clear Range:</b> %.2f km<br/><b>Mast AGL:</b> %.0f m",
                        escapeXml(layer.originLabel),
                        layer.coveredAreaKm2,
                        layer.maxUnobstructedRangeKm,
                        layer.antennaHeightMeters
                    ),
                    centerLat = layer.originLat,
                    centerLon = layer.originLon,
                    radiusKm = layer.radiusKm.toDouble()
                )
            }
            sb.appendLine("    </Folder>")
        }

        sb.appendLine("  </Document>")
        sb.appendLine("</kml>")
        return sb.toString()
    }

    fun buildViewshedKml(
        uiState: ViewshedUiState,
        baseLayerName: String = MapBaseLayer.DARK_MATTER.displayName,
        exportedAtIso: String = MapExportEngine.formatIsoTimestamp()
    ): String {
        val sb = StringBuilder(2048)
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<kml xmlns="http://www.opengis.net/kml/2.2">""")
        sb.appendLine("  <Document>")
        sb.appendLine("    <name>${escapeXml("${MapExportEngine.EXPORT_BRAND_ATTRIBUTION} — Viewshed (${uiState.siteLabel})")}</name>")
        sb.appendLine(
            "    <description><![CDATA[Exported from ${MapExportEngine.EXPORT_BRAND_ATTRIBUTION} ($exportedAtIso) • Base: ${escapeXml(baseLayerName)} • " +
                "Radius: ${uiState.radiusKm} km • ${uiState.txPowerDbm.roundToInt()} dBm • ${uiState.antennaGainDbi} dBi]]></description>"
        )

        appendIconStyle(sb, "pin_tx", 0xFF22C55E.toInt(), 1.3, KML_SITE_PIN_URL)
        appendIconStyle(sb, "pin_rx", 0xFF38BDF8.toInt(), 1.25, KML_SITE_PIN_URL)
        appendIconStyle(sb, "pin_obstruction", 0xFFEF4444.toInt(), 1.1, KML_WARN_ICON_URL)
        appendLineStyle(sb, "line_clear", 0xFF22C55E.toInt(), 3.5)
        appendLineStyle(sb, "line_grazing", 0xFFF59E0B.toInt(), 3.5)
        appendLineStyle(sb, "line_blocked", 0xFFEF4444.toInt(), 3.5)
        appendPolyStyle(sb, "poly_viewshed", 0xFF22C55E.toInt())

        val txLat = uiState.txLat
        val txLon = uiState.txLon
        if (txLat != null && txLon != null) {
            appendPointPlacemark(
                sb = sb,
                indent = "    ",
                name = "TX: ${uiState.siteLabel}",
                styleId = "pin_tx",
                descriptionHtml = String.format(
                    Locale.US,
                    "<b>Mast:</b> %.0f m AGL • <b>TX Power:</b> %.0f dBm • <b>Gain:</b> %.1f dBi",
                    uiState.antennaHeightMeters,
                    uiState.txPowerDbm,
                    uiState.antennaGainDbi
                ),
                lat = txLat,
                lon = txLon,
                alt = uiState.groundElevationMeters
            )
        }

        val rxLat = uiState.rxLat
        val rxLon = uiState.rxLon
        if (rxLat != null && rxLon != null) {
            appendPointPlacemark(
                sb = sb,
                indent = "    ",
                name = "RX: ${uiState.rxLabel}",
                styleId = "pin_rx",
                descriptionHtml = String.format(Locale.US, "<b>RX Mast:</b> %.0f m AGL", uiState.rxAntennaHeightMeters),
                lat = rxLat,
                lon = rxLon,
                alt = uiState.rxGroundElevationMeters
            )
        }

        val rxLink = uiState.rxLinkResult
        if (rxLink != null) {
            val lineId = when (rxLink.hop.status) {
                ViewshedPointStatus.CLEAR -> "line_clear"
                ViewshedPointStatus.GRAZING -> "line_grazing"
                ViewshedPointStatus.OBSTRUCTED -> "line_blocked"
            }
            appendLinePlacemark(
                sb = sb,
                indent = "    ",
                name = "TX ↔ RX Link (${rxLink.hop.status.name})",
                styleId = lineId,
                descriptionHtml = String.format(
                    Locale.US,
                    "<b>Distance:</b> %.2f km<br/><b>RSSI:</b> %.1f dBm (%+.1f dB margin)<br/><b>Min F1:</b> %.0f%%",
                    rxLink.hop.distanceKm,
                    rxLink.hop.predictedRssiDbm,
                    rxLink.hop.linkMarginDb,
                    rxLink.hop.minFresnelRatio * 100.0
                ),
                coords = listOf(rxLink.txLat to rxLink.txLon, rxLink.rxLat to rxLink.rxLon)
            )
            rxLink.hop.worstObstruction?.let { obs ->
                appendPointPlacemark(
                    sb = sb,
                    indent = "    ",
                    name = "Worst Obstruction (${String.format(Locale.US, "%.1f m", obs.clearanceMeters)})",
                    styleId = "pin_obstruction",
                    descriptionHtml = String.format(Locale.US, "<b>Terrain:</b> %.1f m<br/><b>Clearance:</b> %.1f m", obs.terrainElevationMeters, obs.clearanceMeters),
                    lat = obs.latitude,
                    lon = obs.longitude,
                    alt = obs.terrainElevationMeters
                )
            }
        } else if (txLat != null && txLon != null && rxLat != null && rxLon != null) {
            appendLinePlacemark(
                sb = sb,
                indent = "    ",
                name = "TX ↔ RX Baseline",
                styleId = "line_clear",
                descriptionHtml = "Direct path",
                coords = listOf(txLat to txLon, rxLat to rxLon)
            )
        }

        uiState.currentResult?.let { res ->
            appendCirclePolygonPlacemark(
                sb = sb,
                indent = "    ",
                name = "Viewshed Range (${res.params.radiusKm} km)",
                styleId = "poly_viewshed",
                descriptionHtml = String.format(
                    Locale.US,
                    "<b>Covered Area:</b> %.1f km² • <b>Max Clear Range:</b> %.2f km • <b>Clear Nodes:</b> %d",
                    res.coveredAreaKm2,
                    res.maxUnobstructedRangeKm,
                    res.coveredClearNodes.size
                ),
                centerLat = res.params.originLat,
                centerLon = res.params.originLon,
                radiusKm = res.params.radiusKm.toDouble()
            )
        }

        sb.appendLine("  </Document>")
        sb.appendLine("</kml>")
        return sb.toString()
    }

    fun buildBridgeKml(
        uiState: BridgeUiState,
        baseLayerName: String = MapBaseLayer.DARK_MATTER.displayName,
        exportedAtIso: String = MapExportEngine.formatIsoTimestamp()
    ): String {
        val sb = StringBuilder(2048)
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<kml xmlns="http://www.opengis.net/kml/2.2">""")
        sb.appendLine("  <Document>")
        sb.appendLine("    <name>${escapeXml("${MapExportEngine.EXPORT_BRAND_ATTRIBUTION} — Bridge (${uiState.txLabel} to ${uiState.rxLabel})")}</name>")
        sb.appendLine(
            "    <description><![CDATA[Exported from ${MapExportEngine.EXPORT_BRAND_ATTRIBUTION} ($exportedAtIso) • Base: ${escapeXml(baseLayerName)} • " +
                "TX: ${uiState.txPowerDbm.roundToInt()} dBm/${uiState.txAntennaGainDbi} dBi • " +
                "RX: ${uiState.rxPowerDbm.roundToInt()} dBm/${uiState.rxAntennaGainDbi} dBi • " +
                "Relay: ${uiState.relayPowerDbm.roundToInt()} dBm/${uiState.relayAntennaGainDbi} dBi]]></description>"
        )

        appendIconStyle(sb, "pin_tx", 0xFFF59E0B.toInt(), 1.3, KML_SITE_PIN_URL)
        appendIconStyle(sb, "pin_rx", 0xFF38BDF8.toInt(), 1.3, KML_SITE_PIN_URL)
        appendIconStyle(sb, "pin_relay_selected", 0xFF22C55E.toInt(), 1.35, KML_SITE_PIN_URL)
        appendIconStyle(sb, "pin_relay_alt", 0xFFA855F7.toInt(), 1.1, KML_NODE_ICON_URL)
        appendLineStyle(sb, "line_direct", 0xFFEF4444.toInt(), 2.5)
        appendLineStyle(sb, "line_hop", 0xFF22C55E.toInt(), 4.0)

        uiState.txLat?.let { tLat ->
            uiState.txLon?.let { tLon ->
                appendPointPlacemark(
                    sb = sb,
                    indent = "    ",
                    name = "TX: ${uiState.txLabel}",
                    styleId = "pin_tx",
                    descriptionHtml = String.format(
                        Locale.US,
                        "<b>Mast:</b> %.0f m • <b>TX Power:</b> %.0f dBm • <b>Gain:</b> %.1f dBi • <b>Sens:</b> %.0f dBm",
                        uiState.txAntennaHeightMeters,
                        uiState.txPowerDbm,
                        uiState.txAntennaGainDbi,
                        uiState.txSensitivityDbm
                    ),
                    lat = tLat,
                    lon = tLon,
                    alt = uiState.txGroundElevationMeters
                )
            }
        }

        uiState.rxLat?.let { rLat ->
            uiState.rxLon?.let { rLon ->
                appendPointPlacemark(
                    sb = sb,
                    indent = "    ",
                    name = "RX: ${uiState.rxLabel}",
                    styleId = "pin_rx",
                    descriptionHtml = String.format(
                        Locale.US,
                        "<b>Mast:</b> %.0f m • <b>TX Power:</b> %.0f dBm • <b>Gain:</b> %.1f dBi • <b>Sens:</b> %.0f dBm",
                        uiState.rxAntennaHeightMeters,
                        uiState.rxPowerDbm,
                        uiState.rxAntennaGainDbi,
                        uiState.rxSensitivityDbm
                    ),
                    lat = rLat,
                    lon = rLon,
                    alt = uiState.rxGroundElevationMeters
                )
            }
        }

        val result = uiState.currentResult
        if (result != null) {
            appendLinePlacemark(
                sb = sb,
                indent = "    ",
                name = "Direct TX–RX Baseline (${result.directBaseline.status.name})",
                styleId = "line_direct",
                descriptionHtml = String.format(
                    Locale.US,
                    "<b>Distance:</b> %.2f km<br/><b>Fwd Margin:</b> %+.1f dB • <b>Rev Margin:</b> %+.1f dB",
                    result.directDistanceKm,
                    result.directBaseline.linkMarginDb,
                    result.directBaseline.reverseLinkMarginDb
                ),
                coords = listOf(result.params.txLat to result.params.txLon, result.params.rxLat to result.params.rxLon)
            )

            val selected = result.topCandidates.getOrNull(uiState.selectedCandidateIndex)
                ?: result.topCandidates.firstOrNull()
            result.topCandidates.forEach { cand ->
                val isSelected = selected != null && cand.rank == selected.rank
                appendPointPlacemark(
                    sb = sb,
                    indent = "    ",
                    name = "Relay #${cand.rank} (${String.format(Locale.US, "%+.1f dB", cand.worstLinkMarginDb)})",
                    styleId = if (isSelected) "pin_relay_selected" else "pin_relay_alt",
                    descriptionHtml = String.format(
                        Locale.US,
                        "<b>Tier:</b> %s<br/><b>Ground:</b> %.0f m • <b>Relay Mast:</b> %.0f m<br/><b>Worst Bidirectional Margin:</b> %+.1f dB",
                        cand.tier.name,
                        cand.groundElevationMeters,
                        result.params.relayAntennaHeightMeters,
                        cand.worstLinkMarginDb
                    ),
                    lat = cand.latitude,
                    lon = cand.longitude,
                    alt = cand.groundElevationMeters
                )
            }

            if (selected != null) {
                appendLinePlacemark(
                    sb = sb,
                    indent = "    ",
                    name = "TX ↔ Relay #${selected.rank} (${String.format(Locale.US, "%.2f km", selected.txHop.distanceKm)})",
                    styleId = "line_hop",
                    descriptionHtml = String.format(
                        Locale.US,
                        "<b>Bottleneck Margin:</b> %+.1f dB • <b>Min F1:</b> %.0f%%",
                        selected.txHop.bottleneckLinkMarginDb,
                        selected.txHop.minFresnelRatio * 100.0
                    ),
                    coords = listOf(
                        result.params.txLat to result.params.txLon,
                        selected.latitude to selected.longitude
                    )
                )
                appendLinePlacemark(
                    sb = sb,
                    indent = "    ",
                    name = "Relay #${selected.rank} ↔ RX (${String.format(Locale.US, "%.2f km", selected.rxHop.distanceKm)})",
                    styleId = "line_hop",
                    descriptionHtml = String.format(
                        Locale.US,
                        "<b>Bottleneck Margin:</b> %+.1f dB • <b>Min F1:</b> %.0f%%",
                        selected.rxHop.bottleneckLinkMarginDb,
                        selected.rxHop.minFresnelRatio * 100.0
                    ),
                    coords = listOf(
                        selected.latitude to selected.longitude,
                        result.params.rxLat to result.params.rxLon
                    )
                )
            }
        } else if (uiState.txLat != null && uiState.txLon != null && uiState.rxLat != null && uiState.rxLon != null) {
            appendLinePlacemark(
                sb = sb,
                indent = "    ",
                name = "Direct TX–RX Baseline",
                styleId = "line_direct",
                descriptionHtml = "Un-solved direct path",
                coords = listOf(uiState.txLat to uiState.txLon, uiState.rxLat to uiState.rxLon)
            )
        }

        sb.appendLine("  </Document>")
        sb.appendLine("</kml>")
        return sb.toString()
    }

    fun buildLidarKml(
        uiState: LidarStreetUiState,
        baseLayerName: String = MapBaseLayer.DARK_MATTER.displayName,
        exportedAtIso: String = MapExportEngine.formatIsoTimestamp()
    ): String {
        val sb = StringBuilder(2048)
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<kml xmlns="http://www.opengis.net/kml/2.2">""")
        sb.appendLine("  <Document>")
        sb.appendLine("    <name>${escapeXml("${MapExportEngine.EXPORT_BRAND_ATTRIBUTION} — LiDAR Street (${uiState.siteLabel})")}</name>")
        sb.appendLine(
            "    <description><![CDATA[Exported from ${MapExportEngine.EXPORT_BRAND_ATTRIBUTION} ($exportedAtIso) • Base: ${escapeXml(baseLayerName)} • " +
                "Source: ${escapeXml(uiState.activeSourceLabel)} • Radius: ${uiState.radiusMeters} m • TX ${uiState.txHeightFt.roundToInt()} ft AGL • RX ${uiState.rxHeightFt.roundToInt()} ft AGL (SX1262 -134 dBm)]]></description>"
        )

        appendIconStyle(sb, "pin_tx", 0xFFF59E0B.toInt(), 1.3, KML_SITE_PIN_URL)
        appendPolyStyle(sb, "poly_lidar", 0xFF22C55E.toInt())

        val txLat = uiState.txLat
        val txLon = uiState.txLon
        if (txLat != null && txLon != null) {
            appendPointPlacemark(
                sb = sb,
                indent = "    ",
                name = "TX: ${uiState.siteLabel}",
                styleId = "pin_tx",
                descriptionHtml = String.format(
                    Locale.US,
                    "<b>Height:</b> %.0f ft (%.1f m) AGL • <b>TX Power:</b> %.0f dBm • <b>Gain:</b> %.1f dBi",
                    uiState.txHeightFt,
                    uiState.txHeightFt * LidarViewshedEngine.FEET_TO_METERS,
                    uiState.txPowerDbm,
                    uiState.antennaGainDbi
                ),
                lat = txLat,
                lon = txLon,
                alt = uiState.groundElevationMeters
            )
        }

        uiState.currentResult?.let { res ->
            appendCirclePolygonPlacemark(
                sb = sb,
                indent = "    ",
                name = "LiDAR Scan Radius (${res.params.radiusMeters} m)",
                styleId = "poly_lidar",
                descriptionHtml = String.format(
                    Locale.US,
                    "<b>Covered Area:</b> %.2f ac (%,.0f m²)<br/><b>Max Clear Range:</b> %.0f m (%.0f ft)<br/><b>Corridor ≥60%% F1:</b> %.1f%%",
                    res.coveredAreaAcres,
                    res.coveredAreaSqMeters,
                    res.maxClearRangeMeters,
                    res.maxClearRangeMeters * LidarViewshedEngine.METERS_TO_FEET,
                    res.corridorClearancePct
                ),
                centerLat = res.params.originLat,
                centerLon = res.params.originLon,
                radiusKm = res.params.radiusMeters / 1000.0
            )
        }

        sb.appendLine("  </Document>")
        sb.appendLine("</kml>")
        return sb.toString()
    }

    private fun nodeStyleId(colorArgb: Int, isSelf: Boolean): String {
        val hex = String.format(Locale.US, "%06x", colorArgb and 0xFFFFFF)
        return if (isSelf) "node_self_$hex" else "node_$hex"
    }

    private fun lineStyleId(colorArgb: Int, width: Double): String {
        val hex = String.format(Locale.US, "%08x", colorArgb)
        val w = (width * 10).roundToInt()
        return "line_${hex}_$w"
    }

    private fun polyStyleId(colorArgb: Int): String {
        val hex = String.format(Locale.US, "%06x", colorArgb and 0xFFFFFF)
        return "poly_$hex"
    }

    private fun appendIconStyle(
        sb: StringBuilder,
        styleId: String,
        argb: Int,
        scale: Double,
        iconHref: String
    ) {
        val kmlColor = argbToKmlColor(argb)
        sb.appendLine("    <Style id=\"$styleId\">")
        sb.appendLine("      <IconStyle>")
        sb.appendLine("        <color>$kmlColor</color>")
        sb.appendLine(String.format(Locale.US, "        <scale>%.2f</scale>", scale))
        sb.appendLine("        <Icon><href>${escapeXml(iconHref)}</href></Icon>")
        sb.appendLine("      </IconStyle>")
        sb.appendLine("      <LabelStyle><scale>0.85</scale></LabelStyle>")
        sb.appendLine("    </Style>")
    }

    private fun appendLineStyle(sb: StringBuilder, styleId: String, argb: Int, width: Double) {
        val kmlColor = argbToKmlColor(argb)
        sb.appendLine("    <Style id=\"$styleId\">")
        sb.appendLine("      <LineStyle>")
        sb.appendLine("        <color>$kmlColor</color>")
        sb.appendLine(String.format(Locale.US, "        <width>%.1f</width>", width))
        sb.appendLine("      </LineStyle>")
        sb.appendLine("    </Style>")
    }

    private fun appendPolyStyle(sb: StringBuilder, styleId: String, argb: Int) {
        val strokeColor = argbToKmlColor((0xFF shl 24) or (argb and 0xFFFFFF))
        val fillColor = argbToKmlColor((0x38 shl 24) or (argb and 0xFFFFFF))
        sb.appendLine("    <Style id=\"$styleId\">")
        sb.appendLine("      <LineStyle><color>$strokeColor</color><width>2.0</width></LineStyle>")
        sb.appendLine("      <PolyStyle><color>$fillColor</color></PolyStyle>")
        sb.appendLine("    </Style>")
    }

    private fun appendPointPlacemark(
        sb: StringBuilder,
        indent: String,
        name: String,
        styleId: String,
        descriptionHtml: String,
        lat: Double,
        lon: Double,
        alt: Double?
    ) {
        val altStr = alt?.let { String.format(Locale.US, ",%.1f", it) } ?: ",0"
        sb.appendLine("$indent<Placemark>")
        sb.appendLine("$indent  <name>${escapeXml(name)}</name>")
        sb.appendLine("$indent  <styleUrl>#$styleId</styleUrl>")
        sb.appendLine("$indent  <description><![CDATA[$descriptionHtml]]></description>")
        sb.appendLine("$indent  <Point>")
        sb.appendLine(String.format(Locale.US, "$indent    <coordinates>%.6f,%.6f%s</coordinates>", lon, lat, altStr))
        sb.appendLine("$indent  </Point>")
        sb.appendLine("$indent</Placemark>")
    }

    private fun appendLinePlacemark(
        sb: StringBuilder,
        indent: String,
        name: String,
        styleId: String,
        descriptionHtml: String,
        coords: List<Pair<Double, Double>>
    ) {
        sb.appendLine("$indent<Placemark>")
        sb.appendLine("$indent  <name>${escapeXml(name)}</name>")
        sb.appendLine("$indent  <styleUrl>#$styleId</styleUrl>")
        sb.appendLine("$indent  <description><![CDATA[$descriptionHtml]]></description>")
        sb.appendLine("$indent  <LineString>")
        sb.appendLine("$indent    <tessellate>1</tessellate>")
        sb.appendLine("$indent    <coordinates>${coords.joinToString(" ") { (la, lo) -> String.format(Locale.US, "%.6f,%.6f,0", lo, la) }}</coordinates>")
        sb.appendLine("$indent  </LineString>")
        sb.appendLine("$indent</Placemark>")
    }

    private fun appendCirclePolygonPlacemark(
        sb: StringBuilder,
        indent: String,
        name: String,
        styleId: String,
        descriptionHtml: String,
        centerLat: Double,
        centerLon: Double,
        radiusKm: Double,
        segments: Int = 64
    ) {
        val angDist = radiusKm / EARTH_RADIUS_KM
        val lat1 = Math.toRadians(centerLat)
        val lon1 = Math.toRadians(centerLon)
        val ringCoords = ArrayList<String>(segments + 1)
        for (i in 0..segments) {
            val brng = 2.0 * PI * (i % segments) / segments
            val sinLat2 = sin(lat1) * cos(angDist) + cos(lat1) * sin(angDist) * cos(brng)
            val lat2 = asin(sinLat2.coerceIn(-1.0, 1.0))
            val lon2 = lon1 + atan2(
                sin(brng) * sin(angDist) * cos(lat1),
                cos(angDist) - sin(lat1) * sin(lat2)
            )
            ringCoords += String.format(Locale.US, "%.6f,%.6f,0", Math.toDegrees(lon2), Math.toDegrees(lat2))
        }
        sb.appendLine("$indent<Placemark>")
        sb.appendLine("$indent  <name>${escapeXml(name)}</name>")
        sb.appendLine("$indent  <styleUrl>#$styleId</styleUrl>")
        sb.appendLine("$indent  <description><![CDATA[$descriptionHtml]]></description>")
        sb.appendLine("$indent  <Polygon>")
        sb.appendLine("$indent    <outerBoundaryIs><LinearRing><coordinates>${ringCoords.joinToString(" ")}</coordinates></LinearRing></outerBoundaryIs>")
        sb.appendLine("$indent  </Polygon>")
        sb.appendLine("$indent</Placemark>")
    }

    private fun appendExtendedData(
        sb: StringBuilder,
        indent: String,
        fields: List<Pair<String, String>>
    ) {
        if (fields.isEmpty()) return
        sb.appendLine("$indent<ExtendedData>")
        fields.forEach { (k, v) ->
            sb.appendLine("$indent  <Data name=\"${escapeXml(k)}\"><value>${escapeXml(v)}</value></Data>")
        }
        sb.appendLine("$indent</ExtendedData>")
    }

    private fun escapeXml(input: String): String {
        return buildString(input.length + 12) {
            for (ch in input) {
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(ch)
                }
            }
        }
    }
}
