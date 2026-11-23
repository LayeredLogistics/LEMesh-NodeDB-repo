package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.BridgeHopEvaluation
import com.example.data.BridgeObstructionItem
import com.example.data.BridgeProfileSample
import com.example.data.BridgeResult
import com.example.data.MeshNode
import com.example.data.RelayCandidate
import com.example.data.ViewshedPointStatus
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.GroundOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import kotlin.math.max

@Composable
fun BridgeMapCanvas(
    txLat: Double?,
    txLon: Double?,
    rxLat: Double?,
    rxLon: Double?,
    activePlacementTarget: BridgePlacementTarget,
    dropCenterRequestId: Long,
    radiusKm: Int,
    baseLayer: MapBaseLayer,
    existingNodes: List<MeshNode>,
    currentResult: BridgeResult?,
    selectedCandidateIndex: Int,
    obstructions: List<BridgeObstructionItem>,
    selectedObstructionId: String?,
    obstructionFocusNonce: Long,
    overlayOpacity: Float,
    liveDirectHop: BridgeHopEvaluation? = null,
    scrubbedProfileSample: BridgeProfileSample? = null,
    onMapTap: (Double, Double) -> Unit,
    onDropAtMapCenter: (Double, Double) -> Unit,
    onTxDragEnd: (Double, Double) -> Unit,
    onRxDragEnd: (Double, Double) -> Unit,
    onEndpointPinTap: (BridgePlacementTarget) -> Unit,
    onExistingNodeTap: (MeshNode) -> Unit,
    onCandidatePinTap: (Int) -> Unit,
    onObstructionPinTap: (String) -> Unit,
    onMapViewReady: (MapView?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var lastAppliedLayer by remember { mutableStateOf<MapBaseLayer?>(null) }
    var lastFittedResult by remember { mutableStateOf<BridgeResult?>(null) }
    val dragController = remember { RfPinDragController() }
    val overlayPool = remember { CrispOverlayPool() }

    val currentOnTap by rememberUpdatedState(onMapTap)
    val currentOnDropCenter by rememberUpdatedState(onDropAtMapCenter)
    val currentOnTxDrag by rememberUpdatedState(onTxDragEnd)
    val currentOnRxDrag by rememberUpdatedState(onRxDragEnd)
    val currentOnPinTap by rememberUpdatedState(onEndpointPinTap)
    val currentOnNodeTap by rememberUpdatedState(onExistingNodeTap)
    val currentOnCandidateTap by rememberUpdatedState(onCandidatePinTap)
    val currentOnObstructionTap by rememberUpdatedState(onObstructionPinTap)
    val currentOnMapViewReady by rememberUpdatedState(onMapViewReady)

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

    RfMapLifecycleEffect(lifecycleOwner = lifecycleOwner, mapView = mapViewRef)

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                Configuration.getInstance().userAgentValue = context.packageName
                val initialCenter = resolveInitialRfMapCenter(txLat, txLon, rxLat, rxLon, existingNodes)
                MapView(context).apply {
                    setTileSource(rfMapTileSource(baseLayer))
                    lastAppliedLayer = baseLayer
                    setMultiTouchControls(true)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    isTilesScaledToDpi = true
                    minZoomLevel = 3.0
                    maxZoomLevel = 19.0
                    controller.setZoom(10.5)
                    controller.setCenter(initialCenter)
                    mapViewRef = this
                }
            },
            update = { mapView ->
                if (lastAppliedLayer != baseLayer) {
                    mapView.setTileSource(rfMapTileSource(baseLayer))
                    lastAppliedLayer = baseLayer
                }

                rebuildBridgeOverlays(
                    context = mapView.context,
                    mapView = mapView,
                    dragController = dragController,
                    overlayPool = overlayPool,
                    txLat = txLat,
                    txLon = txLon,
                    rxLat = rxLat,
                    rxLon = rxLon,
                    radiusKm = radiusKm,
                    existingNodes = existingNodes,
                    currentResult = currentResult,
                    selectedCandidateIndex = selectedCandidateIndex,
                    obstructions = obstructions,
                    selectedObstructionId = selectedObstructionId,
                    overlayOpacity = overlayOpacity,
                    liveDirectHop = liveDirectHop,
                    scrubbedProfileSample = scrubbedProfileSample,
                    onMapTap = { lat, lon -> currentOnTap(lat, lon) },
                    onTxDragEnd = { lat, lon -> currentOnTxDrag(lat, lon) },
                    onRxDragEnd = { lat, lon -> currentOnRxDrag(lat, lon) },
                    onEndpointPinTap = { endpoint ->
                        currentOnPinTap(
                            if (endpoint == RfPinEndpoint.TX) BridgePlacementTarget.TX else BridgePlacementTarget.RX
                        )
                    },
                    onNodeTap = { node -> currentOnNodeTap(node) },
                    onCandidateTap = { idx -> currentOnCandidateTap(idx) },
                    onObstructionTap = { id -> currentOnObstructionTap(id) }
                )
            },
            onRelease = { mapView ->
                mapView.onPause()
                mapView.onDetach()
                if (mapViewRef === mapView) {
                    mapViewRef = null
                }
            }
        )

        RfCrosshairReticle(
            isTxTarget = activePlacementTarget == BridgePlacementTarget.TX,
            modifier = Modifier.align(Alignment.Center)
        )
    }

    LaunchedEffect(dropCenterRequestId) {
        if (dropCenterRequestId <= 0L) return@LaunchedEffect
        val mapView = mapViewRef ?: return@LaunchedEffect
        val center = mapView.mapCenter
        currentOnDropCenter(center.latitude, center.longitude)
    }

    LaunchedEffect(currentResult, mapViewRef) {
        val mapView = mapViewRef ?: return@LaunchedEffect
        if (currentResult != null && currentResult != lastFittedResult) {
            lastFittedResult = currentResult
            zoomMapToBoundsSafely(
                mapView = mapView,
                northLat = currentResult.northLat,
                eastLon = currentResult.eastLon,
                southLat = currentResult.southLat,
                westLon = currentResult.westLon
            )
        }
    }

    LaunchedEffect(selectedObstructionId, obstructionFocusNonce, mapViewRef) {
        val mapView = mapViewRef ?: return@LaunchedEffect
        val obsId = selectedObstructionId ?: return@LaunchedEffect
        val target = obstructions.firstOrNull { it.id == obsId } ?: return@LaunchedEffect
        val targetPoint = GeoPoint(target.point.latitude, target.point.longitude)
        if (mapView.zoomLevelDouble < 13.0) {
            mapView.controller.animateTo(targetPoint, 13.0, 450L)
        } else {
            mapView.controller.animateTo(targetPoint)
        }
    }
}

private fun rebuildBridgeOverlays(
    context: Context,
    mapView: MapView,
    dragController: RfPinDragController,
    overlayPool: CrispOverlayPool,
    txLat: Double?,
    txLon: Double?,
    rxLat: Double?,
    rxLon: Double?,
    radiusKm: Int,
    existingNodes: List<MeshNode>,
    currentResult: BridgeResult?,
    selectedCandidateIndex: Int,
    obstructions: List<BridgeObstructionItem>,
    selectedObstructionId: String?,
    overlayOpacity: Float,
    liveDirectHop: BridgeHopEvaluation?,
    scrubbedProfileSample: BridgeProfileSample?,
    onMapTap: (Double, Double) -> Unit,
    onTxDragEnd: (Double, Double) -> Unit,
    onRxDragEnd: (Double, Double) -> Unit,
    onEndpointPinTap: (RfPinEndpoint) -> Unit,
    onNodeTap: (MeshNode) -> Unit,
    onCandidateTap: (Int) -> Unit,
    onObstructionTap: (String) -> Unit
) {
    val activeTxPoint = if (dragController.draggingTarget == RfPinEndpoint.TX) {
        dragController.txMarker?.position
    } else {
        if (txLat != null && txLon != null) GeoPoint(txLat, txLon) else null
    }
    val activeRxPoint = if (dragController.draggingTarget == RfPinEndpoint.RX) {
        dragController.rxMarker?.position
    } else {
        if (rxLat != null && rxLon != null) GeoPoint(rxLat, rxLon) else null
    }

    mapView.overlays.clear()
    dragController.txMarker = null
    dragController.rxMarker = null
    dragController.connectingLine = null

    mapView.overlays.add(
        MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                onMapTap(p.latitude, p.longitude)
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean = false
        })
    )

    val activePoolKeys = HashSet<String>()
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
    overlayPool.pruneExcept(activePoolKeys)

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

    mapView.overlays.add(
        DirectRfPinDragOverlay(
            controller = dragController,
            onTxDragEnd = onTxDragEnd,
            onRxDragEnd = onRxDragEnd,
            onEndpointPinTap = onEndpointPinTap
        )
    )

    mapView.invalidate()
}

private fun createRelayCandidateDrawable(
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

private fun createObstructionMarkerDrawable(
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
    val diamondPath = android.graphics.Path().apply {
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

private fun createTerrainProbeMarkerDrawable(
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


