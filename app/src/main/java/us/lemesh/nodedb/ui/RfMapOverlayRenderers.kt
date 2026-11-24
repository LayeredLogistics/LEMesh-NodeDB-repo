package us.lemesh.nodedb.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.GroundOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import us.lemesh.nodedb.data.AggregateCoverageResult
import us.lemesh.nodedb.data.BridgeHopEvaluation
import us.lemesh.nodedb.data.BridgeObstructionItem
import us.lemesh.nodedb.data.BridgeProfileSample
import us.lemesh.nodedb.data.BridgeResult
import us.lemesh.nodedb.data.LayerRenderMode
import us.lemesh.nodedb.data.LidarViewshedResult
import us.lemesh.nodedb.data.LinkClassification
import us.lemesh.nodedb.data.LinkDiagnosticSummary
import us.lemesh.nodedb.data.LinkDiagnosticTier
import us.lemesh.nodedb.data.LosAnalysisResult
import us.lemesh.nodedb.data.LosAnalyzer
import us.lemesh.nodedb.data.LosEndpoint
import us.lemesh.nodedb.data.MeshGeoUtils
import us.lemesh.nodedb.data.MeshNode
import us.lemesh.nodedb.data.RelayCandidate
import us.lemesh.nodedb.data.RfViabilityTier
import us.lemesh.nodedb.data.SavedViewshedLayer
import us.lemesh.nodedb.data.ViewshedPointStatus
import us.lemesh.nodedb.data.ViewshedResult
import java.util.Locale
import kotlin.math.max

internal fun fitMapToPoints(mapView: MapView, points: List<GeoPoint>) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        mapView.controller.animateTo(points.first(), 14.0, 450L)
        return
    }
    val rawBox = BoundingBox.fromGeoPoints(points)
    val latSpan = max(rawBox.latitudeSpan, 0.004)
    val lonSpan = max(rawBox.longitudeSpanWithDateLine, 0.004)
    val latPad = latSpan * 0.10
    val lonPad = lonSpan * 0.10
    val paddedBox = BoundingBox(
        (rawBox.latNorth + latPad).coerceAtMost(85.0),
        (rawBox.lonEast + lonPad).coerceAtMost(180.0),
        (rawBox.latSouth - latPad).coerceAtLeast(-85.0),
        (rawBox.lonWest - lonPad).coerceAtLeast(-180.0)
    )
    mapView.zoomToBoundingBox(paddedBox, true, 48)
}

internal fun appendSavedViewshedLayers(
    mapView: MapView,
    overlayPool: CrispOverlayPool,
    activePoolKeys: MutableSet<String>,
    keyPrefix: String,
    savedLayers: List<SavedViewshedLayer>,
    overlayOpacity: Float
) {
    val layerTransparency = (1f - overlayOpacity).coerceIn(0f, 1f)
    savedLayers.asReversed().forEach { layer ->
        if (layer.isEnabled) {
            val layerField = layer.polarField
            if (layerField != null) {
                val key = "${keyPrefix}_saved_${layer.id}_${layer.renderMode.name}_${layer.tintColorArgb}"
                activePoolKeys.add(key)
                val tint = if (layer.renderMode == LayerRenderMode.TINT) layer.tintColorArgb else null
                mapView.overlays.add(
                    overlayPool.obtainOverlay(
                        key = key,
                        source = CrispOverlaySource.SingleField(polarField = layerField, tintColorArgb = tint),
                        opacity = overlayOpacity
                    )
                )
            } else {
                mapView.overlays.add(
                    GroundOverlay().apply {
                        setImage(layer.displayBitmap())
                        setPosition(
                            GeoPoint(layer.northLat, layer.westLon),
                            GeoPoint(layer.southLat, layer.eastLon)
                        )
                        setTransparency(layerTransparency)
                    }
                )
            }
        }
    }
}

internal fun addMeshMapOverlays(
    context: Context,
    mapView: MapView,
    overlayPool: CrispOverlayPool,
    activePoolKeys: MutableSet<String>,
    nodes: List<MeshNode>,
    selfNode: MeshNode?,
    showRfSightlines: Boolean,
    isMeasureMode: Boolean,
    measureAnchorA: MeshNode?,
    measureAnchorB: MeshNode?,
    isLosMode: Boolean,
    losPointA: LosEndpoint?,
    losPointB: LosEndpoint?,
    losResult: LosAnalysisResult?,
    savedViewshedLayers: List<SavedViewshedLayer>,
    viewshedOverlayOpacity: Float,
    aggregateCoverageResult: AggregateCoverageResult?,
    isAggregateCoverageEnabled: Boolean,
    sightlineFresnelResults: Map<String, LosAnalysisResult>,
    sightlineDiagnostics: Map<String, LinkDiagnosticSummary> = emptyMap(),
    onNodeClick: (MeshNode) -> Unit,
    onSightlineClick: (MeshNode) -> Unit
) {
    if (isAggregateCoverageEnabled && aggregateCoverageResult != null && aggregateCoverageResult.fields.isNotEmpty()) {
        val aggKey = "nodemap_aggregate_coverage"
        activePoolKeys.add(aggKey)
        mapView.overlays.add(
            overlayPool.obtainOverlay(
                key = aggKey,
                source = CrispOverlaySource.AggregateFields(
                    fields = aggregateCoverageResult.fields,
                    sensitivityDbm = aggregateCoverageResult.sensitivityDbm,
                    signature = aggregateCoverageResult.signature,
                    northLat = aggregateCoverageResult.northLat,
                    southLat = aggregateCoverageResult.southLat,
                    westLon = aggregateCoverageResult.westLon,
                    eastLon = aggregateCoverageResult.eastLon
                ),
                opacity = viewshedOverlayOpacity
            )
        )
    }

    appendSavedViewshedLayers(
        mapView = mapView,
        overlayPool = overlayPool,
        activePoolKeys = activePoolKeys,
        keyPrefix = "nodemap",
        savedLayers = savedViewshedLayers,
        overlayOpacity = viewshedOverlayOpacity
    )

    val midpointLabels = mutableListOf<Marker>()

    if (showRfSightlines && selfNode != null && selfNode.hasValidGps) {
        val selfLat = selfNode.latitude!!
        val selfLon = selfNode.longitude!!
        val selfPoint = GeoPoint(selfLat, selfLon)
        var fresnelMidpointPillsShown = 0
        nodes.forEach { target ->
            if (!target.isSelf && !target.viaMqtt && target.hasValidGps) {
                val targetLat = target.latitude!!
                val targetLon = target.longitude!!
                val targetPoint = GeoPoint(targetLat, targetLon)
                val isDirectZeroHop = target.hopsAway == 0
                val key = LosAnalyzer.nodeSightlineKey(target)
                val fresnelResult = sightlineFresnelResults[key]
                val diagnostic = sightlineDiagnostics[key]
                val effectiveBaseColor = diagnostic?.tier?.androidColorInt
                    ?: fresnelResult?.viabilityTier?.androidColorInt
                val lineColor = when {
                    effectiveBaseColor != null -> (effectiveBaseColor and 0x00FFFFFF) or
                        (if (isDirectZeroHop) 0xE0000000.toInt() else 0xB8000000.toInt())
                    isDirectZeroHop -> 0xCC22C55E.toInt()
                    else -> 0xB306B6D4.toInt()
                }
                val isDashed = (diagnostic != null && diagnostic.tier == LinkDiagnosticTier.SEVERE_ANOMALY) ||
                    (diagnostic == null && fresnelResult != null &&
                        (fresnelResult.viabilityTier == RfViabilityTier.OBSTRUCTED ||
                            fresnelResult.viabilityTier == RfViabilityTier.WEAK_MARGIN))
                val line = Polyline(mapView).apply {
                    setPoints(listOf(selfPoint, targetPoint))
                    outlinePaint.color = lineColor
                    outlinePaint.strokeWidth = if (isDirectZeroHop) 5.5f else 3.4f
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                    outlinePaint.isAntiAlias = true
                    if (isDashed) {
                        outlinePaint.pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
                    }
                    setOnClickListener { _, _, _ ->
                        onSightlineClick(target)
                        true
                    }
                }
                mapView.overlays.add(line)

                val isActiveLosTarget = isLosMode && losPointB?.node?.id == target.id && losPointB.node.num == target.num
                val isSelectedMeasurePair = isMeasureMode && isSamePair(measureAnchorA, measureAnchorB, selfNode, target)

                if (isDirectZeroHop && !isActiveLosTarget && !isSelectedMeasurePair) {
                    val midPoint = GeoPoint((selfLat + targetLat) / 2.0, (selfLon + targetLon) / 2.0)
                    when {
                        isMeasureMode -> {
                            val km = MeshGeoUtils.haversineKm(selfLat, selfLon, targetLat, targetLon)
                            val miles = MeshGeoUtils.kmToMiles(km)
                            val pillLabel = if (diagnostic != null) {
                                String.format(Locale.US, "%.1fmi • Δ%+.0fdB", miles, diagnostic.deltaSnrDb)
                            } else if (fresnelResult != null) {
                                val pct = fresnelResult.worstClearancePercentFresnel?.let {
                                    String.format(Locale.US, "%.0f%%", it)
                                } ?: "—"
                                String.format(Locale.US, "%.1fmi • %s", miles, pct)
                            } else {
                                String.format(Locale.US, "%.2f mi", miles)
                            }
                            midpointLabels.add(
                                Marker(mapView).apply {
                                    position = midPoint
                                    setAnchor(0.5f, 0.5f)
                                    icon = createDistancePillDrawable(
                                        context = context,
                                        text = pillLabel,
                                        borderColorInt = effectiveBaseColor ?: 0xFF22C55E.toInt()
                                    )
                                    infoWindow = null
                                    setOnMarkerClickListener { _, _ ->
                                        onSightlineClick(target)
                                        true
                                    }
                                }
                            )
                        }
                        (diagnostic != null || fresnelResult != null) && fresnelMidpointPillsShown < 8 -> {
                            fresnelMidpointPillsShown++
                            val pillText = if (diagnostic != null) {
                                String.format(
                                    Locale.US,
                                    "Δ%+.1fdB • %s",
                                    diagnostic.deltaSnrDb,
                                    diagnostic.tier.shortLabel
                                )
                            } else if (fresnelResult!!.viabilityTier == RfViabilityTier.OBSTRUCTED) {
                                String.format(Locale.US, "! %+.0fm", fresnelResult.worstClearanceMeters)
                            } else {
                                val pct = fresnelResult.worstClearancePercentFresnel ?: 100.0
                                String.format(Locale.US, "%.0f%% • %.0fdBm", pct, fresnelResult.predictedRssiDbm)
                            }
                            midpointLabels.add(
                                Marker(mapView).apply {
                                    position = midPoint
                                    setAnchor(0.5f, 0.5f)
                                    icon = createDistancePillDrawable(
                                        context = context,
                                        text = pillText,
                                        borderColorInt = effectiveBaseColor ?: 0xFF22C55E.toInt()
                                    )
                                    infoWindow = null
                                    setOnMarkerClickListener { _, _ ->
                                        onSightlineClick(target)
                                        true
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (isMeasureMode &&
        measureAnchorA != null && measureAnchorA.hasValidGps &&
        measureAnchorB != null && measureAnchorB.hasValidGps
    ) {
        val latA = measureAnchorA.latitude!!
        val lonA = measureAnchorA.longitude!!
        val latB = measureAnchorB.latitude!!
        val lonB = measureAnchorB.longitude!!
        val pA = GeoPoint(latA, lonA)
        val pB = GeoPoint(latB, lonB)
        val km = MeshGeoUtils.haversineKm(latA, lonA, latB, lonB)
        val miles = MeshGeoUtils.kmToMiles(km)

        mapView.overlays.add(
            Polyline(mapView).apply {
                setPoints(listOf(pA, pB))
                outlinePaint.color = 0xFFF59E0B.toInt()
                outlinePaint.strokeWidth = 7.0f
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.isAntiAlias = true
            }
        )

        midpointLabels.add(
            Marker(mapView).apply {
                position = GeoPoint((latA + latB) / 2.0, (lonA + lonB) / 2.0)
                setAnchor(0.5f, 0.5f)
                icon = createDistancePillDrawable(
                    context = context,
                    text = String.format(Locale.US, "%.2f mi • %.2f km", miles, km),
                    borderColorInt = 0xFFF59E0B.toInt()
                )
                infoWindow = null
            }
        )
    }

    if (isLosMode && losPointA != null && losPointB != null) {
        val pathPoints = if (losResult != null && losResult.samples.size >= 2) {
            losResult.samples.map { GeoPoint(it.latitude, it.longitude) }
        } else {
            listOf(
                GeoPoint(losPointA.latitude, losPointA.longitude),
                GeoPoint(losPointB.latitude, losPointB.longitude)
            )
        }
        val lineColor = losResult?.viabilityTier?.androidColorInt ?: 0xFF38BDF8.toInt()
        mapView.overlays.add(
            Polyline(mapView).apply {
                setPoints(pathPoints)
                outlinePaint.color = lineColor
                outlinePaint.strokeWidth = 7.5f
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.isAntiAlias = true
            }
        )

        if (losResult != null) {
            val worst = losResult.worstSample
            val badgeText = when (losResult.viabilityTier) {
                RfViabilityTier.OBSTRUCTED -> String.format(
                    Locale.US,
                    "! %+.0fm • %.0fdBm",
                    worst.clearanceMeters,
                    losResult.predictedRssiDbm
                )
                RfViabilityTier.WEAK_MARGIN -> String.format(Locale.US, "Low %.0fdBm", losResult.predictedRssiDbm)
                RfViabilityTier.MARGINAL_GRAZING -> String.format(
                    Locale.US,
                    "~ %.0f%% F1 • %.0fdBm",
                    worst.clearancePercentFresnel ?: 0.0,
                    losResult.predictedRssiDbm
                )
                RfViabilityTier.VIABLE_CLEAR -> String.format(
                    Locale.US,
                    "✓ %.0f%% F1 • %.0fdBm",
                    worst.clearancePercentFresnel ?: 100.0,
                    losResult.predictedRssiDbm
                )
            }
            midpointLabels.add(
                Marker(mapView).apply {
                    position = GeoPoint(worst.latitude, worst.longitude)
                    setAnchor(0.5f, 0.5f)
                    icon = createDistancePillDrawable(
                        context = context,
                        text = badgeText,
                        borderColorInt = losResult.viabilityTier.androidColorInt
                    )
                    infoWindow = null
                }
            )
        }
    }

    midpointLabels.forEach { mapView.overlays.add(it) }

    val orderedNodes = nodes.sortedBy {
        when {
            it == measureAnchorA || it == measureAnchorB -> 2
            it.isSelf -> 1
            else -> 0
        }
    }
    orderedNodes.forEach { node ->
        val lat = node.latitude ?: return@forEach
        val lon = node.longitude ?: return@forEach
        val isAnchor = (isMeasureMode && (
            (measureAnchorA != null && node.id == measureAnchorA.id && node.num == measureAnchorA.num) ||
                (measureAnchorB != null && node.id == measureAnchorB.id && node.num == measureAnchorB.num)
            )) || (isLosMode && (
            (losPointA?.node != null && node.id == losPointA.node.id && node.num == losPointA.node.num) ||
                (losPointB?.node != null && node.id == losPointB.node.id && node.num == losPointB.node.num)
            ))

        mapView.overlays.add(
            Marker(mapView).apply {
                position = GeoPoint(lat, lon)
                title = node.displayLongName
                setAnchor(0.5f, 0.34f)
                icon = createNodeMarkerDrawable(context, node, isHighlighted = isAnchor)
                infoWindow = null
                setOnMarkerClickListener { _, _ ->
                    onNodeClick(node)
                    true
                }
            }
        )
    }

    if (isLosMode) {
        if (losPointA != null && losPointA.node == null) {
            mapView.overlays.add(
                Marker(mapView).apply {
                    position = GeoPoint(losPointA.latitude, losPointA.longitude)
                    setAnchor(0.5f, 0.5f)
                    icon = createDistancePillDrawable(context, "TX • A", 0xFFF59E0B.toInt())
                    infoWindow = null
                }
            )
        }
        if (losPointB != null && losPointB.node == null) {
            mapView.overlays.add(
                Marker(mapView).apply {
                    position = GeoPoint(losPointB.latitude, losPointB.longitude)
                    setAnchor(0.5f, 0.5f)
                    icon = createDistancePillDrawable(context, "RX • B", 0xFF38BDF8.toInt())
                    infoWindow = null
                }
            )
        }
    }
}

internal fun addViewshedOverlays(
    context: Context,
    mapView: MapView,
    dragController: RfPinDragController,
    overlayPool: CrispOverlayPool,
    activePoolKeys: MutableSet<String>,
    activeTxPoint: GeoPoint?,
    activeRxPoint: GeoPoint?,
    radiusKm: Int,
    existingNodes: List<MeshNode>,
    showNodeDbAndSavedLayers: Boolean,
    currentResult: ViewshedResult?,
    rxLinkResult: ViewshedRxLinkResult?,
    pinnedSnapshot: ViewshedResult?,
    showSnapshotOnMap: Boolean,
    savedLayers: List<SavedViewshedLayer>,
    overlayOpacity: Float,
    onEndpointPinTap: (RfPinEndpoint) -> Unit,
    onNodeTap: (MeshNode) -> Unit
) {
    if (showNodeDbAndSavedLayers) {
        appendSavedViewshedLayers(
            mapView = mapView,
            overlayPool = overlayPool,
            activePoolKeys = activePoolKeys,
            keyPrefix = "viewshed",
            savedLayers = savedLayers,
            overlayOpacity = overlayOpacity
        )
    }

    if (showSnapshotOnMap && pinnedSnapshot != null) {
        val snapField = pinnedSnapshot.field
        if (snapField != null) {
            val key = "viewshed_pinned_snapshot"
            activePoolKeys.add(key)
            mapView.overlays.add(
                overlayPool.obtainOverlay(
                    key = key,
                    source = CrispOverlaySource.SingleField(polarField = snapField),
                    opacity = (overlayOpacity * 0.65f).coerceIn(0f, 1f)
                )
            )
        } else {
            mapView.overlays.add(
                GroundOverlay().apply {
                    setImage(pinnedSnapshot.bitmap)
                    setPosition(
                        GeoPoint(pinnedSnapshot.northLat, pinnedSnapshot.westLon),
                        GeoPoint(pinnedSnapshot.southLat, pinnedSnapshot.eastLon)
                    )
                    setTransparency((1f - (overlayOpacity * 0.65f)).coerceIn(0f, 1f))
                }
            )
        }
    }

    if (currentResult != null) {
        val currentField = currentResult.field
        if (currentField != null) {
            val key = "viewshed_current"
            activePoolKeys.add(key)
            mapView.overlays.add(
                overlayPool.obtainOverlay(
                    key = key,
                    source = CrispOverlaySource.SingleField(polarField = currentField),
                    opacity = overlayOpacity
                )
            )
        } else {
            mapView.overlays.add(
                GroundOverlay().apply {
                    setImage(currentResult.bitmap)
                    setPosition(
                        GeoPoint(currentResult.northLat, currentResult.westLon),
                        GeoPoint(currentResult.southLat, currentResult.eastLon)
                    )
                    setTransparency((1f - overlayOpacity).coerceIn(0f, 1f))
                }
            )
        }
    }

    if (activeTxPoint != null) {
        mapView.overlays.add(
            Polygon(mapView).apply {
                points = Polygon.pointsAsCircle(activeTxPoint, radiusKm * 1000.0)
                fillPaint.color = 0x00000000
                outlinePaint.color = 0x8838BDF8.toInt()
                outlinePaint.strokeWidth = 2.5f
                outlinePaint.isAntiAlias = true
            }
        )
    }

    if (activeTxPoint != null && activeRxPoint != null) {
        val sightline = Polyline(mapView).apply {
            setPoints(listOf(activeTxPoint, activeRxPoint))
            if (rxLinkResult != null) {
                outlinePaint.color = colorForRfHopStatus(rxLinkResult.hop.status)
                outlinePaint.strokeWidth = 5.5f
                if (rxLinkResult.hop.status == ViewshedPointStatus.OBSTRUCTED) {
                    outlinePaint.pathEffect = DashPathEffect(floatArrayOf(16f, 10f), 0f)
                }
            } else {
                outlinePaint.color = 0x8894A3B8.toInt()
                outlinePaint.strokeWidth = 3f
                outlinePaint.pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
            }
            outlinePaint.isAntiAlias = true
        }
        dragController.connectingLine = sightline
        mapView.overlays.add(sightline)
    }

    if (showNodeDbAndSavedLayers) {
        val clearIds = currentResult?.coveredClearNodes?.map { "${it.node.id}_${it.node.num}" }?.toSet().orEmpty()
        existingNodes.forEach { node ->
            val lat = node.latitude ?: return@forEach
            val lon = node.longitude ?: return@forEach
            val isCovered = clearIds.contains("${node.id}_${node.num}")
            mapView.overlays.add(
                Marker(mapView).apply {
                    position = GeoPoint(lat, lon)
                    title = node.displayLongName
                    setAnchor(0.5f, 0.5f)
                    icon = createRfNetworkDotDrawable(context, node, isCovered)
                    infoWindow = null
                    setOnMarkerClickListener { _, _ ->
                        onNodeTap(node)
                        true
                    }
                }
            )
        }
    }

    appendRfEndpointBadges(
        context = context,
        mapView = mapView,
        dragController = dragController,
        activeTxPoint = activeTxPoint,
        activeRxPoint = activeRxPoint,
        onEndpointPinTap = onEndpointPinTap
    )
}

internal fun addBridgeOverlays(
    context: Context,
    mapView: MapView,
    dragController: RfPinDragController,
    overlayPool: CrispOverlayPool,
    activePoolKeys: MutableSet<String>,
    activeTxPoint: GeoPoint?,
    activeRxPoint: GeoPoint?,
    radiusKm: Int,
    existingNodes: List<MeshNode>,
    showNodeDbAndSavedLayers: Boolean,
    savedLayers: List<SavedViewshedLayer>,
    currentResult: BridgeResult?,
    selectedCandidateIndex: Int,
    obstructions: List<BridgeObstructionItem>,
    selectedObstructionId: String?,
    overlayOpacity: Float,
    liveDirectHop: BridgeHopEvaluation?,
    scrubbedProfileSample: BridgeProfileSample?,
    onEndpointPinTap: (RfPinEndpoint) -> Unit,
    onNodeTap: (MeshNode) -> Unit,
    onCandidateTap: (Int) -> Unit,
    onObstructionTap: (String) -> Unit
) {
    if (showNodeDbAndSavedLayers) {
        appendSavedViewshedLayers(
            mapView = mapView,
            overlayPool = overlayPool,
            activePoolKeys = activePoolKeys,
            keyPrefix = "bridge",
            savedLayers = savedLayers,
            overlayOpacity = overlayOpacity
        )
    }

    if (currentResult != null) {
        val txF = currentResult.txField
        val rxF = currentResult.rxField
        if (txF != null && rxF != null) {
            val key = "bridge_dual"
            activePoolKeys.add(key)
            mapView.overlays.add(
                overlayPool.obtainOverlay(
                    key = key,
                    source = CrispOverlaySource.BridgeFields(
                        txField = txF,
                        rxField = rxF,
                        sensitivityDbm = currentResult.params.sensitivityDbm,
                        northLat = currentResult.northLat,
                        southLat = currentResult.southLat,
                        westLon = currentResult.westLon,
                        eastLon = currentResult.eastLon
                    ),
                    opacity = overlayOpacity
                )
            )
        } else {
            mapView.overlays.add(
                GroundOverlay().apply {
                    setImage(currentResult.bitmap)
                    setPosition(
                        GeoPoint(currentResult.northLat, currentResult.westLon),
                        GeoPoint(currentResult.southLat, currentResult.eastLon)
                    )
                    setTransparency((1f - overlayOpacity).coerceIn(0f, 1f))
                }
            )
        }
    }

    if (activeTxPoint != null) {
        mapView.overlays.add(
            Polygon(mapView).apply {
                points = Polygon.pointsAsCircle(activeTxPoint, radiusKm * 1000.0)
                fillPaint.color = 0x00000000
                outlinePaint.color = 0x66F59E0B
                outlinePaint.strokeWidth = 2f
                outlinePaint.isAntiAlias = true
            }
        )
    }
    if (activeRxPoint != null) {
        mapView.overlays.add(
            Polygon(mapView).apply {
                points = Polygon.pointsAsCircle(activeRxPoint, radiusKm * 1000.0)
                fillPaint.color = 0x00000000
                outlinePaint.color = 0x6638BDF8
                outlinePaint.strokeWidth = 2f
                outlinePaint.isAntiAlias = true
            }
        )
    }

    if (activeTxPoint != null && activeRxPoint != null) {
        val baseline = Polyline(mapView).apply {
            setPoints(listOf(activeTxPoint, activeRxPoint))
            if (currentResult == null && liveDirectHop != null) {
                outlinePaint.color = colorForRfHopStatus(liveDirectHop.status)
                outlinePaint.strokeWidth = 5f
            } else {
                outlinePaint.color = 0x6694A3B8
                outlinePaint.strokeWidth = 2.5f
                outlinePaint.pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
            }
            outlinePaint.isAntiAlias = true
        }
        dragController.connectingLine = baseline
        mapView.overlays.add(baseline)
    }

    val selectedCand = currentResult?.topCandidates?.getOrNull(selectedCandidateIndex)
        ?: currentResult?.topCandidates?.firstOrNull()
    if (selectedCand != null && activeTxPoint != null && activeRxPoint != null) {
        val relayPoint = GeoPoint(selectedCand.latitude, selectedCand.longitude)
        mapView.overlays.add(
            Polyline(mapView).apply {
                setPoints(listOf(activeTxPoint, relayPoint))
                outlinePaint.color = colorForRfHopStatus(selectedCand.txHop.status)
                outlinePaint.strokeWidth = 6f
                outlinePaint.isAntiAlias = true
            }
        )
        mapView.overlays.add(
            Polyline(mapView).apply {
                setPoints(listOf(relayPoint, activeRxPoint))
                outlinePaint.color = colorForRfHopStatus(selectedCand.rxHop.status)
                outlinePaint.strokeWidth = 6f
                outlinePaint.isAntiAlias = true
            }
        )
    }

    if (showNodeDbAndSavedLayers) {
        existingNodes.forEach { node ->
            val lat = node.latitude ?: return@forEach
            val lon = node.longitude ?: return@forEach
            mapView.overlays.add(
                Marker(mapView).apply {
                    position = GeoPoint(lat, lon)
                    title = node.displayLongName
                    setAnchor(0.5f, 0.5f)
                    icon = createRfNetworkDotDrawable(context, node, isCoveredClear = false)
                    infoWindow = null
                    setOnMarkerClickListener { _, _ ->
                        onNodeTap(node)
                        true
                    }
                }
            )
        }
    }

    currentResult?.topCandidates?.forEachIndexed { idx, candidate ->
        val isSelected = idx == selectedCandidateIndex
        mapView.overlays.add(
            Marker(mapView).apply {
                position = GeoPoint(candidate.latitude, candidate.longitude)
                setAnchor(0.5f, 0.5f)
                icon = createRelayCandidateDrawable(context, candidate, isSelected)
                infoWindow = null
                setOnMarkerClickListener { _, _ ->
                    onCandidateTap(idx)
                    true
                }
            }
        )
    }

    obstructions.forEachIndexed { idx, obs ->
        val isSelected = obs.id == selectedObstructionId
        if (obs.point.status != ViewshedPointStatus.CLEAR || isSelected) {
            mapView.overlays.add(
                Marker(mapView).apply {
                    position = GeoPoint(obs.point.latitude, obs.point.longitude)
                    setAnchor(0.5f, 0.5f)
                    icon = createObstructionMarkerDrawable(
                        context = context,
                        rank = idx + 1,
                        status = obs.point.status,
                        isSelected = isSelected
                    )
                    infoWindow = null
                    setOnMarkerClickListener { _, _ ->
                        onObstructionTap(obs.id)
                        true
                    }
                }
            )
        }
    }

    appendRfEndpointBadges(
        context = context,
        mapView = mapView,
        dragController = dragController,
        activeTxPoint = activeTxPoint,
        activeRxPoint = activeRxPoint,
        onEndpointPinTap = onEndpointPinTap
    )

    scrubbedProfileSample?.let { sample ->
        mapView.overlays.add(
            Marker(mapView).apply {
                position = GeoPoint(sample.latitude, sample.longitude)
                setAnchor(0.5f, 0.85f)
                icon = createTerrainProbeMarkerDrawable(context, sample.terrainElevationMeters)
                infoWindow = null
            }
        )
    }
}

internal fun addLidarStreetOverlays(
    context: Context,
    mapView: MapView,
    dragController: RfPinDragController,
    overlayPool: CrispOverlayPool,
    activePoolKeys: MutableSet<String>,
    activeTxPoint: GeoPoint?,
    radiusMeters: Int,
    existingNodes: List<MeshNode>,
    showNodeDbAndSavedLayers: Boolean,
    currentResult: LidarViewshedResult?,
    overlayOpacity: Float,
    onEndpointPinTap: (RfPinEndpoint) -> Unit,
    onNodeTap: (MeshNode) -> Unit
) {
    if (currentResult != null) {
        val key = "lidar_street_current"
        activePoolKeys.add(key)
        mapView.overlays.add(
            overlayPool.obtainOverlay(
                key = key,
                source = CrispOverlaySource.SingleField(polarField = currentResult.field),
                opacity = overlayOpacity
            )
        )
    }

    if (activeTxPoint != null) {
        mapView.overlays.add(
            Polygon(mapView).apply {
                points = Polygon.pointsAsCircle(activeTxPoint, radiusMeters.toDouble())
                fillPaint.color = 0x00000000
                outlinePaint.color = 0x9922C55E.toInt()
                outlinePaint.strokeWidth = 2.5f
                outlinePaint.isAntiAlias = true
            }
        )
    }

    if (showNodeDbAndSavedLayers) {
        existingNodes.forEach { node ->
            val lat = node.latitude ?: return@forEach
            val lon = node.longitude ?: return@forEach
            mapView.overlays.add(
                Marker(mapView).apply {
                    position = GeoPoint(lat, lon)
                    title = node.displayLongName
                    setAnchor(0.5f, 0.5f)
                    icon = createRfNetworkDotDrawable(context, node, isCoveredClear = false)
                    infoWindow = null
                    setOnMarkerClickListener { _, _ ->
                        onNodeTap(node)
                        true
                    }
                }
            )
        }
    }

    appendRfEndpointBadges(
        context = context,
        mapView = mapView,
        dragController = dragController,
        activeTxPoint = activeTxPoint,
        activeRxPoint = null,
        onEndpointPinTap = onEndpointPinTap
    )
}

private fun appendRfEndpointBadges(
    context: Context,
    mapView: MapView,
    dragController: RfPinDragController,
    activeTxPoint: GeoPoint?,
    activeRxPoint: GeoPoint?,
    onEndpointPinTap: (RfPinEndpoint) -> Unit
) {
    if (activeTxPoint != null) {
        val txMarker = Marker(mapView).apply {
            position = activeTxPoint
            isDraggable = false
            setAnchor(0.5f, 0.5f)
            icon = createRfEndpointBadgeDrawable(context, "TX", 0xFFF59E0B.toInt())
            infoWindow = null
            setOnMarkerClickListener { _, _ ->
                onEndpointPinTap(RfPinEndpoint.TX)
                true
            }
        }
        dragController.txMarker = txMarker
        mapView.overlays.add(txMarker)
    }

    if (activeRxPoint != null) {
        val rxMarker = Marker(mapView).apply {
            position = activeRxPoint
            isDraggable = false
            setAnchor(0.5f, 0.5f)
            icon = createRfEndpointBadgeDrawable(context, "RX", 0xFF38BDF8.toInt())
            infoWindow = null
            setOnMarkerClickListener { _, _ ->
                onEndpointPinTap(RfPinEndpoint.RX)
                true
            }
        }
        dragController.rxMarker = rxMarker
        mapView.overlays.add(rxMarker)
    }
}

private fun isSamePair(
    anchorA: MeshNode?,
    anchorB: MeshNode?,
    selfNode: MeshNode,
    target: MeshNode
): Boolean {
    if (anchorA == null || anchorB == null) return false
    val aIsSelf = anchorA.id == selfNode.id && anchorA.num == selfNode.num
    val bIsSelf = anchorB.id == selfNode.id && anchorB.num == selfNode.num
    val aIsTarget = anchorA.id == target.id && anchorA.num == target.num
    val bIsTarget = anchorB.id == target.id && anchorB.num == target.num
    return (aIsSelf && bIsTarget) || (bIsSelf && aIsTarget)
}

internal fun createDistancePillDrawable(
    context: Context,
    text: String,
    borderColorInt: Int
): BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF8FAFC.toInt()
        textSize = 10.5f * density
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val textWidth = textPaint.measureText(text)
    val width = (textWidth + 16f * density).toInt()
    val height = (20f * density).toInt()

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val rect = RectF(1f * density, 1f * density, width - 1f * density, height - 1f * density)

    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF00B1120.toInt()
        style = Paint.Style.FILL
    }
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = borderColorInt
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
    }
    val radius = 10f * density
    canvas.drawRoundRect(rect, radius, radius, bgPaint)
    canvas.drawRoundRect(rect, radius, radius, strokePaint)

    val baseline = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
    canvas.drawText(text, rect.centerX(), baseline, textPaint)
    return BitmapDrawable(context.resources, bitmap)
}

internal fun createNodeMarkerDrawable(
    context: Context,
    node: MeshNode,
    isHighlighted: Boolean = false
): BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val classification = node.linkClassification
    val colorInt = node.effectiveColorInt

    val label = node.displayShortName.take(5)
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF8FAFC.toInt()
        textSize = 10.5f * density
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val textWidth = textPaint.measureText(label)
    val pillWidth = max(textWidth + 12f * density, 32f * density)
    val pillHeight = 16f * density

    val circleRadius = when {
        isHighlighted -> 12.5f * density
        classification == LinkClassification.SELF -> 11.5f * density
        classification == LinkClassification.FAVORITE -> 10.5f * density
        else -> 9f * density
    }
    val glowRadius = circleRadius + (if (isHighlighted) 6.5f else 4.5f) * density

    val canvasWidth = max(pillWidth + 6f * density, glowRadius * 2f + 6f * density).toInt()
    val circleCenterY = glowRadius + 2f * density
    val pillTop = circleCenterY + circleRadius + 3f * density
    val canvasHeight = (pillTop + pillHeight + 3f * density).toInt()

    val bitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = canvasWidth / 2f

    val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isHighlighted) 0x77F59E0B else (colorInt and 0x00FFFFFF) or 0x44000000
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, circleCenterY, glowRadius, glowPaint)

    if (isHighlighted) {
        val highlightRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF59E0B.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2.0f * density
        }
        canvas.drawCircle(cx, circleCenterY, glowRadius - 1f * density, highlightRing)
    }

    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0B1120.toInt()
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, circleCenterY, circleRadius + 1.8f * density, borderPaint)

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorInt
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, circleCenterY, circleRadius, fillPaint)

    val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = when (classification) {
            LinkClassification.SELF -> 0xFF0B1120.toInt()
            LinkClassification.FAVORITE -> 0xFFFAF5FF.toInt()
            else -> 0xFFF8FAFC.toInt()
        }
        style = Paint.Style.FILL
    }
    canvas.drawCircle(
        cx,
        circleCenterY,
        if (classification == LinkClassification.SELF) 4.2f * density else 3.0f * density,
        centerDotPaint
    )

    if (classification == LinkClassification.SELF) {
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFDE68A.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * density
        }
        canvas.drawCircle(cx, circleCenterY, circleRadius - 2.5f * density, ringPaint)
    }

    val pillRect = RectF(
        cx - pillWidth / 2f,
        pillTop,
        cx + pillWidth / 2f,
        pillTop + pillHeight
    )
    val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xEB0F172A.toInt()
        style = Paint.Style.FILL
    }
    val pillStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (colorInt and 0x00FFFFFF) or 0xCC000000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.1f * density
    }
    val cornerRadius = 5f * density
    canvas.drawRoundRect(pillRect, cornerRadius, cornerRadius, pillBgPaint)
    canvas.drawRoundRect(pillRect, cornerRadius, cornerRadius, pillStrokePaint)

    val textBaseline = pillRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
    canvas.drawText(label, cx, textBaseline, textPaint)

    return BitmapDrawable(context.resources, bitmap)
}

internal fun createRelayCandidateDrawable(
    context: Context,
    candidate: RelayCandidate,
    isSelected: Boolean
): BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val label = if (candidate.rank == 1) "#1 BEST" else "#${candidate.rank}"
    val accent = when {
        candidate.tier.name == "MUTUAL_CLEAR_LOS" -> 0xFF22C55E.toInt()
        candidate.tier.name == "GRAZING_BRIDGE" -> 0xFFF59E0B.toInt()
        else -> 0xFFEF4444.toInt()
    }

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0B1120.toInt()
        textSize = (if (isSelected) 10.5f else 9.5f) * density
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val pillW = max(textPaint.measureText(label) + 12f * density, 38f * density)
    val pillH = 16f * density
    val ringR = (if (isSelected) 14f else 11f) * density
    val w = max(pillW + 4f * density, ringR * 2f + 6f * density).toInt()
    val h = (ringR * 2f + pillH + 10f * density).toInt()

    val bitmap = createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = w / 2f
    val cy = ringR + 2f * density

    if (isSelected) {
        val outerHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x6622C55E
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, ringR, outerHalo)
    }

    val darkBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isSelected) 0xFFFFFFFF.toInt() else 0xFF0B1120.toInt()
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, (if (isSelected) 10.5f else 8.5f) * density, darkBorder)

    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, (if (isSelected) 8.5f else 6.8f) * density, fill)

    val rankTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0B1120.toInt()
        textSize = 9.5f * density
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val rankBase = cy - (rankTextPaint.descent() + rankTextPaint.ascent()) / 2f
    canvas.drawText(candidate.rank.toString(), cx, rankBase, rankTextPaint)

    val pillTop = cy + ringR - 1f * density
    val rect = RectF(cx - pillW / 2f, pillTop, cx + pillW / 2f, pillTop + pillH)
    canvas.drawRoundRect(rect, 5f * density, 5f * density, fill)
    val base = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
    canvas.drawText(label, cx, base, textPaint)

    return bitmap.toDrawable(context.resources)
}

internal fun createObstructionMarkerDrawable(
    context: Context,
    rank: Int,
    status: ViewshedPointStatus,
    isSelected: Boolean
): BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val accent = when (status) {
        ViewshedPointStatus.OBSTRUCTED -> 0xFFEF4444.toInt()
        ViewshedPointStatus.GRAZING -> 0xFFF59E0B.toInt()
        ViewshedPointStatus.CLEAR -> 0xFF22C55E.toInt()
    }
    val sizePx = ((if (isSelected) 30f else 20f) * density).toInt()
    val bitmap = createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val c = sizePx / 2f

    if (isSelected) {
        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = (accent and 0x00FFFFFF) or 0x55000000
            style = Paint.Style.FILL
        }
        canvas.drawCircle(c, c, sizePx * 0.48f, haloPaint)
    }

    val halfDiag = (if (isSelected) 10.5f else 7.2f) * density
    val diamondPath = Path().apply {
        moveTo(c, c - halfDiag)
        lineTo(c + halfDiag, c)
        lineTo(c, c + halfDiag)
        lineTo(c - halfDiag, c)
        close()
    }

    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isSelected) 0xFFFFFFFF.toInt() else 0xFF0B1120.toInt()
        style = Paint.Style.STROKE
        strokeWidth = (if (isSelected) 2.6f else 1.8f) * density
    }
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        style = Paint.Style.FILL
    }
    canvas.drawPath(diamondPath, fillPaint)
    canvas.drawPath(diamondPath, borderPaint)

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0B1120.toInt()
        textSize = (if (isSelected) 9.5f else 7.8f) * density
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val base = c - (textPaint.descent() + textPaint.ascent()) / 2f
    canvas.drawText(rank.toString(), c, base, textPaint)

    return bitmap.toDrawable(context.resources)
}

internal fun createTerrainProbeMarkerDrawable(
    context: Context,
    elevationMeters: Double
): BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val label = "${elevationMeters.toInt()}m"
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0B1120.toInt()
        textSize = 9.5f * density
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val pillW = max(textPaint.measureText(label) + 10f * density, 34f * density)
    val pillH = 16f * density
    val dotR = 5.5f * density
    val w = max(pillW + 4f * density, dotR * 2f + 8f * density).toInt()
    val h = (pillH + 6f * density + dotR * 2f).toInt()

    val bitmap = createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = w / 2f

    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF38BDF8.toInt()
        style = Paint.Style.FILL
    }
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
    }

    val rect = RectF(cx - pillW / 2f, 1.5f * density, cx + pillW / 2f, 1.5f * density + pillH)
    canvas.drawRoundRect(rect, 5f * density, 5f * density, bgPaint)
    canvas.drawRoundRect(rect, 5f * density, 5f * density, borderPaint)
    val base = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
    canvas.drawText(label, cx, base, textPaint)

    val dotY = h - dotR - 1.5f * density
    canvas.drawCircle(cx, dotY, dotR, borderPaint)
    canvas.drawCircle(cx, dotY, dotR - 1.5f * density, bgPaint)

    return bitmap.toDrawable(context.resources)
}
