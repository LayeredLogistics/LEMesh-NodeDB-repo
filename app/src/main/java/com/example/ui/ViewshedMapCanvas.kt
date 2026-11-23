package com.example.ui

import android.content.Context
import android.graphics.DashPathEffect
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.LayerRenderMode
import com.example.data.MeshNode
import com.example.data.SavedViewshedLayer
import com.example.data.ViewshedPointStatus
import com.example.data.ViewshedResult
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

@Composable
fun ViewshedMapCanvas(
    txLat: Double?,
    txLon: Double?,
    rxLat: Double?,
    rxLon: Double?,
    activePlacementTarget: ViewshedPlacementTarget,
    dropCenterRequestId: Long,
    radiusKm: Int,
    baseLayer: MapBaseLayer,
    existingNodes: List<MeshNode>,
    currentResult: ViewshedResult?,
    rxLinkResult: ViewshedRxLinkResult?,
    pinnedSnapshot: ViewshedResult?,
    showSnapshotOnMap: Boolean,
    savedLayers: List<SavedViewshedLayer>,
    overlayOpacity: Float,
    onMapCoordinateTap: (Double, Double) -> Unit,
    onDropAtMapCenter: (Double, Double) -> Unit,
    onTxDragEnd: (Double, Double) -> Unit,
    onRxDragEnd: (Double, Double) -> Unit,
    onEndpointPinTap: (ViewshedPlacementTarget) -> Unit,
    onExistingNodeTap: (MeshNode) -> Unit,
    onMapViewReady: (MapView?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var lastAppliedLayer by remember { mutableStateOf<MapBaseLayer?>(null) }
    var lastFittedResult by remember { mutableStateOf<ViewshedResult?>(null) }
    val dragController = remember { RfPinDragController() }
    val overlayPool = remember { CrispOverlayPool() }

    val currentOnTap by rememberUpdatedState(onMapCoordinateTap)
    val currentOnDropCenter by rememberUpdatedState(onDropAtMapCenter)
    val currentOnTxDrag by rememberUpdatedState(onTxDragEnd)
    val currentOnRxDrag by rememberUpdatedState(onRxDragEnd)
    val currentOnPinTap by rememberUpdatedState(onEndpointPinTap)
    val currentOnNodeTap by rememberUpdatedState(onExistingNodeTap)
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

                rebuildViewshedOverlays(
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
                    rxLinkResult = rxLinkResult,
                    pinnedSnapshot = pinnedSnapshot,
                    showSnapshotOnMap = showSnapshotOnMap,
                    savedLayers = savedLayers,
                    overlayOpacity = overlayOpacity,
                    onMapTap = { lat, lon -> currentOnTap(lat, lon) },
                    onTxDragEnd = { lat, lon -> currentOnTxDrag(lat, lon) },
                    onRxDragEnd = { lat, lon -> currentOnRxDrag(lat, lon) },
                    onEndpointPinTap = { endpoint ->
                        currentOnPinTap(
                            if (endpoint == RfPinEndpoint.TX) ViewshedPlacementTarget.TX else ViewshedPlacementTarget.RX
                        )
                    },
                    onNodeTap = { node -> currentOnNodeTap(node) }
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
            isTxTarget = activePlacementTarget == ViewshedPlacementTarget.TX,
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
}

private fun rebuildViewshedOverlays(
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
    currentResult: ViewshedResult?,
    rxLinkResult: ViewshedRxLinkResult?,
    pinnedSnapshot: ViewshedResult?,
    showSnapshotOnMap: Boolean,
    savedLayers: List<SavedViewshedLayer>,
    overlayOpacity: Float,
    onMapTap: (Double, Double) -> Unit,
    onTxDragEnd: (Double, Double) -> Unit,
    onRxDragEnd: (Double, Double) -> Unit,
    onEndpointPinTap: (RfPinEndpoint) -> Unit,
    onNodeTap: (MeshNode) -> Unit
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
    val savedTransparency = (1f - overlayOpacity).coerceIn(0f, 1f)
    savedLayers.asReversed().forEach { layer ->
        if (layer.isEnabled) {
            val layerField = layer.polarField
            if (layerField != null) {
                val key = "saved_${layer.id}_${layer.renderMode.name}_${layer.tintColorArgb}"
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
                        setTransparency(savedTransparency)
                    }
                )
            }
        }
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
    overlayPool.pruneExcept(activePoolKeys)

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
