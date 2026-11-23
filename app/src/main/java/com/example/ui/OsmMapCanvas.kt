package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.rememberUpdatedState
import com.example.data.AggregateCoverageResult
import com.example.data.LayerRenderMode
import com.example.data.LinkClassification
import com.example.data.LosAnalysisResult
import com.example.data.LosAnalyzer
import com.example.data.LosEndpoint
import com.example.data.MeshGeoUtils
import com.example.data.MeshNode
import com.example.data.RfViabilityTier
import com.example.data.SavedViewshedLayer
import java.util.Locale
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.GroundOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.max

private val ERIE_COUNTY_PA = GeoPoint(42.1292, -80.0851)
private const val STARTUP_INITIAL_ZOOM = 4.0
private const val STARTUP_TARGET_ZOOM = 13.0
private const val STARTUP_ZOOM_DURATION_MS = 2500L

@Composable
fun OsmMapCanvas(
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
    baseLayer: MapBaseLayer,
    fitBoundsRequestId: Long,
    startupTargetLat: Double? = null,
    startupTargetLon: Double? = null,
    hasStartedStartupZoom: Boolean = false,
    savedCameraLat: Double? = null,
    savedCameraLon: Double? = null,
    savedCameraZoom: Double? = null,
    savedViewshedLayers: List<SavedViewshedLayer> = emptyList(),
    viewshedOverlayOpacity: Float = 0.68f,
    aggregateCoverageResult: AggregateCoverageResult? = null,
    isAggregateCoverageEnabled: Boolean = false,
    onNodeClick: (MeshNode) -> Unit,
    onMapCoordinateTap: (Double, Double) -> Unit,
    onStartupZoomStarted: () -> Unit = {},
    onSaveCameraState: (Double, Double, Double) -> Unit = { _, _, _ -> },
    onMapViewReady: (MapView?) -> Unit = {},
    modifier: Modifier = Modifier,
    sightlineFresnelResults: Map<String, LosAnalysisResult> = emptyMap(),
    onSightlineClick: (MeshNode) -> Unit = onNodeClick,
    isAddNodeMode: Boolean = false
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var lastHandledFitId by remember { mutableStateOf(0L) }
    var lastAppliedLayer by remember { mutableStateOf<MapBaseLayer?>(null) }
    val currentIsLosMode by rememberUpdatedState(isLosMode)
    val currentIsAddNodeMode by rememberUpdatedState(isAddNodeMode)
    val currentOnMapTap by rememberUpdatedState(onMapCoordinateTap)
    val currentOnMapViewReady by rememberUpdatedState(onMapViewReady)
    val currentHasStartedStartupZoom by rememberUpdatedState(hasStartedStartupZoom)
    val currentOnStartupZoomStarted by rememberUpdatedState(onStartupZoomStarted)
    val currentOnSaveCameraState by rememberUpdatedState(onSaveCameraState)
    val overlayPool = remember { CrispOverlayPool() }

    DisposableEffect(Unit) {
        onDispose {
            overlayPool.dispose()
        }
    }

    DisposableEffect(mapViewRef) {
        currentOnMapViewReady(mapViewRef)
        onDispose {
            currentOnMapViewReady(null)
        }
    }

    DisposableEffect(lifecycleOwner, mapViewRef) {
        val mapView = mapViewRef
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView?.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            Configuration.getInstance().userAgentValue = context.packageName
            MapView(context).apply {
                setTileSource(rfMapTileSource(baseLayer))
                lastAppliedLayer = baseLayer
                setMultiTouchControls(true)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                isTilesScaledToDpi = true
                minZoomLevel = 3.0
                maxZoomLevel = 19.0
                if (hasStartedStartupZoom &&
                    savedCameraLat != null &&
                    savedCameraLon != null &&
                    savedCameraZoom != null
                ) {
                    controller.setZoom(savedCameraZoom)
                    controller.setCenter(GeoPoint(savedCameraLat, savedCameraLon))
                } else {
                    val initialCenter = if (startupTargetLat != null && startupTargetLon != null) {
                        GeoPoint(startupTargetLat, startupTargetLon)
                    } else {
                        ERIE_COUNTY_PA
                    }
                    controller.setZoom(STARTUP_INITIAL_ZOOM)
                    controller.setCenter(initialCenter)
                }
                mapViewRef = this
            }
        },
        update = { mapView ->
            if (lastAppliedLayer != baseLayer) {
                mapView.setTileSource(rfMapTileSource(baseLayer))
                lastAppliedLayer = baseLayer
            }

            rebuildOverlays(
                context = mapView.context,
                mapView = mapView,
                overlayPool = overlayPool,
                nodes = nodes,
                selfNode = selfNode,
                showRfSightlines = showRfSightlines,
                isMeasureMode = isMeasureMode,
                measureAnchorA = measureAnchorA,
                measureAnchorB = measureAnchorB,
                isLosMode = isLosMode,
                losPointA = losPointA,
                losPointB = losPointB,
                losResult = losResult,
                savedViewshedLayers = savedViewshedLayers,
                viewshedOverlayOpacity = viewshedOverlayOpacity,
                aggregateCoverageResult = aggregateCoverageResult,
                isAggregateCoverageEnabled = isAggregateCoverageEnabled,
                sightlineFresnelResults = sightlineFresnelResults,
                onNodeClick = onNodeClick,
                onSightlineClick = onSightlineClick,
                tapOverlayProvider = {
                    MapEventsOverlay(object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                            if (currentIsLosMode || currentIsAddNodeMode) {
                                currentOnMapTap(p.latitude, p.longitude)
                                return true
                            }
                            return false
                        }

                        override fun longPressHelper(p: GeoPoint): Boolean = false
                    })
                }
            )
        },
        onRelease = { mapView ->
            if (currentHasStartedStartupZoom) {
                val center = mapView.mapCenter
                currentOnSaveCameraState(center.latitude, center.longitude, mapView.zoomLevelDouble)
            }
            mapView.onPause()
            mapView.onDetach()
            if (mapViewRef === mapView) {
                mapViewRef = null
            }
        }
    )

    LaunchedEffect(startupTargetLat, startupTargetLon, hasStartedStartupZoom, mapViewRef) {
        val mapView = mapViewRef ?: return@LaunchedEffect
        val targetLat = startupTargetLat ?: return@LaunchedEffect
        val targetLon = startupTargetLon ?: return@LaunchedEffect
        if (!hasStartedStartupZoom) {
            mapView.post {
                if (mapView.isAttachedToWindow && !currentHasStartedStartupZoom) {
                    currentOnStartupZoomStarted()
                    mapView.controller.animateTo(
                        GeoPoint(targetLat, targetLon),
                        STARTUP_TARGET_ZOOM,
                        STARTUP_ZOOM_DURATION_MS
                    )
                }
            }
        }
    }

    LaunchedEffect(fitBoundsRequestId, nodes, mapViewRef) {
        val mapView = mapViewRef ?: return@LaunchedEffect
        if (fitBoundsRequestId > 0L && fitBoundsRequestId != lastHandledFitId) {
            val gpsPoints = nodes.mapNotNull { node ->
                val lat = node.latitude
                val lon = node.longitude
                if (lat != null && lon != null) GeoPoint(lat, lon) else null
            }
            if (gpsPoints.isNotEmpty()) {
                lastHandledFitId = fitBoundsRequestId
                mapView.post {
                    if (mapView.isAttachedToWindow && mapView.width > 0 && mapView.height > 0) {
                        fitMapToPoints(mapView, gpsPoints)
                    }
                }
            }
        }
    }
}

private fun fitMapToPoints(mapView: MapView, points: List<GeoPoint>) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        mapView.controller.animateTo(points.first(), 14.0, 450L)
        return
    }
    val rawBox = BoundingBox.fromGeoPoints(points)
    val latSpan = max(rawBox.latitudeSpan, 0.004)
    val lonSpan = max(rawBox.longitudeSpanWithDateLine, 0.004)
    // Expand by 1.2x total span (10% padding on every edge)
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

private fun rebuildOverlays(
    context: Context,
    mapView: MapView,
    overlayPool: CrispOverlayPool,
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
    onNodeClick: (MeshNode) -> Unit,
    onSightlineClick: (MeshNode) -> Unit,
    tapOverlayProvider: () -> MapEventsOverlay
) {
    mapView.overlays.clear()
    mapView.overlays.add(tapOverlayProvider())

    // 0. Aggregate RF Coverage + Saved Viewshed Coverage Layers (above base map, underneath sightlines & markers)
    val activePoolKeys = HashSet<String>()
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

    val layerTransparency = (1f - viewshedOverlayOpacity).coerceIn(0f, 1f)
    savedViewshedLayers.asReversed().forEach { layer ->
        if (layer.isEnabled) {
            val layerField = layer.polarField
            if (layerField != null) {
                val key = "nodemap_saved_${layer.id}_${layer.renderMode.name}_${layer.tintColorArgb}"
                activePoolKeys.add(key)
                val tint = if (layer.renderMode == LayerRenderMode.TINT) layer.tintColorArgb else null
                mapView.overlays.add(
                    overlayPool.obtainOverlay(
                        key = key,
                        source = CrispOverlaySource.SingleField(polarField = layerField, tintColorArgb = tint),
                        opacity = viewshedOverlayOpacity
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
    overlayPool.pruneExcept(activePoolKeys)

    val midpointLabels = mutableListOf<Marker>()

    // 1. RF Sightlines radiating from Self Node to all visible non-MQTT RF nodes with GPS
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
                val fresnelResult = sightlineFresnelResults[LosAnalyzer.nodeSightlineKey(target)]
                val lineColor = when {
                    fresnelResult != null -> (fresnelResult.viabilityTier.androidColorInt and 0x00FFFFFF) or
                        (if (isDirectZeroHop) 0xD8000000.toInt() else 0xB8000000.toInt())
                    isDirectZeroHop -> 0xCC22C55E.toInt() // Emerald green 0-hop direct RF
                    else -> 0xB306B6D4.toInt() // Cyan multi-hop RF
                }
                val isDashed = fresnelResult != null &&
                    (fresnelResult.viabilityTier == RfViabilityTier.OBSTRUCTED ||
                        fresnelResult.viabilityTier == RfViabilityTier.WEAK_MARGIN)
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

                // Show midpoint RF / Fresnel pill on 0-hop links
                if (isDirectZeroHop && !isActiveLosTarget && !isSelectedMeasurePair) {
                    val midPoint = GeoPoint((selfLat + targetLat) / 2.0, (selfLon + targetLon) / 2.0)
                    when {
                        isMeasureMode -> {
                            val km = MeshGeoUtils.haversineKm(selfLat, selfLon, targetLat, targetLon)
                            val miles = MeshGeoUtils.kmToMiles(km)
                            val pillLabel = if (fresnelResult != null) {
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
                                        borderColorInt = fresnelResult?.viabilityTier?.androidColorInt ?: 0xFF22C55E.toInt()
                                    )
                                    infoWindow = null
                                    setOnMarkerClickListener { _, _ ->
                                        onSightlineClick(target)
                                        true
                                    }
                                }
                            )
                        }
                        fresnelResult != null && fresnelMidpointPillsShown < 8 -> {
                            fresnelMidpointPillsShown++
                            val pillText = if (fresnelResult.viabilityTier == RfViabilityTier.OBSTRUCTED) {
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
                                        borderColorInt = fresnelResult.viabilityTier.androidColorInt
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

    // 2. Active Point-to-Point Measurement Line (Anchor A <-> Anchor B)
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

        val measureLine = Polyline(mapView).apply {
            setPoints(listOf(pA, pB))
            outlinePaint.color = 0xFFF59E0B.toInt()
            outlinePaint.strokeWidth = 7.0f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.isAntiAlias = true
        }
        mapView.overlays.add(measureLine)

        val midPoint = GeoPoint((latA + latB) / 2.0, (lonA + lonB) / 2.0)
        midpointLabels.add(
            Marker(mapView).apply {
                position = midPoint
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

    // 3. Line-of-Sight Terrain Path, Endpoints, and Worst Obstruction Marker
    if (isLosMode) {
        if (losPointA != null && losPointB != null) {
            val pathPoints = if (losResult != null && losResult.samples.size >= 2) {
                losResult.samples.map { GeoPoint(it.latitude, it.longitude) }
            } else {
                listOf(
                    GeoPoint(losPointA.latitude, losPointA.longitude),
                    GeoPoint(losPointB.latitude, losPointB.longitude)
                )
            }
            val lineColor = losResult?.viabilityTier?.androidColorInt ?: 0xFF38BDF8.toInt()
            val losLine = Polyline(mapView).apply {
                setPoints(pathPoints)
                outlinePaint.color = lineColor
                outlinePaint.strokeWidth = 7.5f
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.isAntiAlias = true
            }
            mapView.overlays.add(losLine)

            if (losResult != null) {
                val worst = losResult.worstSample
                val badgeText = when (losResult.viabilityTier) {
                    RfViabilityTier.OBSTRUCTED -> String.format(
                        Locale.US,
                        "! %+.0fm • %.0fdBm",
                        worst.clearanceMeters,
                        losResult.predictedRssiDbm
                    )
                    RfViabilityTier.WEAK_MARGIN -> String.format(
                        Locale.US,
                        "Low %.0fdBm",
                        losResult.predictedRssiDbm
                    )
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
    }

    // Add distance/obstruction pills above polylines, below node markers
    midpointLabels.forEach { mapView.overlays.add(it) }

    // 4. Node Markers (Self Node & selected anchors drawn last so they sit on top)
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

        val marker = Marker(mapView).apply {
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
        mapView.overlays.add(marker)
    }

    // 5. Arbitrary LoS Point A / Point B pins (when not backed by a MeshNode)
    if (isLosMode) {
        if (losPointA != null && losPointA.node == null) {
            mapView.overlays.add(
                Marker(mapView).apply {
                    position = GeoPoint(losPointA.latitude, losPointA.longitude)
                    setAnchor(0.5f, 0.5f)
                    icon = createDistancePillDrawable(
                        context = context,
                        text = "TX • A",
                        borderColorInt = 0xFFF59E0B.toInt()
                    )
                    infoWindow = null
                }
            )
        }
        if (losPointB != null && losPointB.node == null) {
            mapView.overlays.add(
                Marker(mapView).apply {
                    position = GeoPoint(losPointB.latitude, losPointB.longitude)
                    setAnchor(0.5f, 0.5f)
                    icon = createDistancePillDrawable(
                        context = context,
                        text = "RX • B",
                        borderColorInt = 0xFF38BDF8.toInt()
                    )
                    infoWindow = null
                }
            )
        }
    }

    mapView.invalidate()
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

private fun createDistancePillDrawable(
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

private fun createNodeMarkerDrawable(
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

    // Outer tactical halo
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

    // Dark separator ring
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0B1120.toInt()
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, circleCenterY, circleRadius + 1.8f * density, borderPaint)

    // Main node fill
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorInt
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, circleCenterY, circleRadius, fillPaint)

    // Crisp inner beacon highlight
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

    // ShortName pill below marker
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
