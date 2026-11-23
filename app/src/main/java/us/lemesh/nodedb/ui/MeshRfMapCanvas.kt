package us.lemesh.nodedb.ui

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
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import us.lemesh.nodedb.data.AggregateCoverageResult
import us.lemesh.nodedb.data.BridgeHopEvaluation
import us.lemesh.nodedb.data.BridgeObstructionItem
import us.lemesh.nodedb.data.BridgeProfileSample
import us.lemesh.nodedb.data.BridgeResult
import us.lemesh.nodedb.data.LinkDiagnosticSummary
import us.lemesh.nodedb.data.LosAnalysisResult
import us.lemesh.nodedb.data.LosEndpoint
import us.lemesh.nodedb.data.MeshNode
import us.lemesh.nodedb.data.SavedViewshedLayer
import us.lemesh.nodedb.data.ViewshedResult
import us.lemesh.nodedb.ui.state.OverlayContinuityMode
import us.lemesh.nodedb.ui.state.WorkspaceMode

private val DEFAULT_ERIE_COUNTY_PA = GeoPoint(42.1292, -80.0851)
private const val STARTUP_INITIAL_ZOOM = 4.0
private const val STARTUP_TARGET_ZOOM = 13.0
private const val STARTUP_ZOOM_DURATION_MS = 2500L

@Composable
fun MeshRfMapCanvas(
    mode: WorkspaceMode,
    baseLayer: MapBaseLayer,
    overlayContinuityMode: OverlayContinuityMode = OverlayContinuityMode.KEEP_NODEDB_WITH_RF,
    nodes: List<MeshNode> = emptyList(),
    selfNode: MeshNode? = null,
    showRfSightlines: Boolean = true,
    isMeasureMode: Boolean = false,
    measureAnchorA: MeshNode? = null,
    measureAnchorB: MeshNode? = null,
    isLosMode: Boolean = false,
    isAddNodeMode: Boolean = false,
    losPointA: LosEndpoint? = null,
    losPointB: LosEndpoint? = null,
    losResult: LosAnalysisResult? = null,
    sightlineFresnelResults: Map<String, LosAnalysisResult> = emptyMap(),
    sightlineDiagnostics: Map<String, LinkDiagnosticSummary> = emptyMap(),
    aggregateCoverageResult: AggregateCoverageResult? = null,
    isAggregateCoverageEnabled: Boolean = false,
    savedViewshedLayers: List<SavedViewshedLayer> = emptyList(),
    overlayOpacity: Float = 0.68f,
    // Viewshed & Bridge shared endpoint/RF fields
    viewshedTxLat: Double? = null,
    viewshedTxLon: Double? = null,
    viewshedRxLat: Double? = null,
    viewshedRxLon: Double? = null,
    viewshedRadiusKm: Int = 25,
    viewshedIsTxTarget: Boolean = true,
    viewshedResult: ViewshedResult? = null,
    viewshedRxLinkResult: ViewshedRxLinkResult? = null,
    viewshedPinnedSnapshot: ViewshedResult? = null,
    viewshedShowSnapshotOnMap: Boolean = false,
    bridgeTxLat: Double? = null,
    bridgeTxLon: Double? = null,
    bridgeRxLat: Double? = null,
    bridgeRxLon: Double? = null,
    bridgeRadiusKm: Int = 25,
    bridgeIsTxTarget: Boolean = true,
    bridgeResult: BridgeResult? = null,
    bridgeSelectedCandidateIndex: Int = 0,
    bridgeObstructions: List<BridgeObstructionItem> = emptyList(),
    bridgeSelectedObstructionId: String? = null,
    bridgeObstructionFocusNonce: Long = 0L,
    bridgeLiveDirectHop: BridgeHopEvaluation? = null,
    bridgeScrubbedProfileSample: BridgeProfileSample? = null,
    // Camera / drop requests
    dropCenterRequestId: Long = 0L,
    fitBoundsRequestId: Long = 0L,
    centerFocusRequestId: Long = 0L,
    centerFocusLat: Double? = null,
    centerFocusLon: Double? = null,
    centerFocusMinZoom: Double? = null,
    startupTargetLat: Double? = null,
    startupTargetLon: Double? = null,
    hasStartedStartupZoom: Boolean = false,
    savedCameraLat: Double? = null,
    savedCameraLon: Double? = null,
    savedCameraZoom: Double? = null,
    savedCameraBearingDeg: Float = 0f,
    // Callbacks
    onMeshNodeClick: (MeshNode) -> Unit = {},
    onSightlineClick: (MeshNode) -> Unit = onMeshNodeClick,
    onRfNodeTap: (MeshNode) -> Unit = onMeshNodeClick,
    onMapCoordinateTap: (Double, Double) -> Unit = { _, _ -> },
    onDropAtMapCenter: (Double, Double) -> Unit = { _, _ -> },
    onPinDragStart: (WorkspaceMode) -> Unit = {},
    onTxDragEnd: (Double, Double) -> Unit = { _, _ -> },
    onRxDragEnd: (Double, Double) -> Unit = { _, _ -> },
    onEndpointPinTap: (RfPinEndpoint) -> Unit = {},
    onCandidatePinTap: (Int) -> Unit = {},
    onObstructionPinTap: (String) -> Unit = {},
    onStartupZoomStarted: () -> Unit = {},
    onSaveCameraState: (Double, Double, Double, Float) -> Unit = { _, _, _, _ -> },
    onMapViewReady: (MapView?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var lastAppliedLayer by remember { mutableStateOf<MapBaseLayer?>(null) }
    var lastHandledFitId by remember { mutableStateOf(0L) }
    var lastHandledCenterFocusId by remember { mutableStateOf(0L) }
    var lastFittedViewshedResult by remember { mutableStateOf<ViewshedResult?>(viewshedResult) }
    var lastFittedBridgeResult by remember { mutableStateOf<BridgeResult?>(bridgeResult) }

    val dragController = remember { RfPinDragController() }
    val overlayPool = remember { CrispOverlayPool() }

    val currentMode by rememberUpdatedState(mode)
    val currentIsLosMode by rememberUpdatedState(isLosMode)
    val currentIsAddNodeMode by rememberUpdatedState(isAddNodeMode)
    val currentOnMapTap by rememberUpdatedState(onMapCoordinateTap)
    val currentOnDropCenter by rememberUpdatedState(onDropAtMapCenter)
    val currentOnPinDragStart by rememberUpdatedState(onPinDragStart)
    val currentOnTxDragEnd by rememberUpdatedState(onTxDragEnd)
    val currentOnRxDragEnd by rememberUpdatedState(onRxDragEnd)
    val currentOnEndpointPinTap by rememberUpdatedState(onEndpointPinTap)
    val currentOnMeshNodeClick by rememberUpdatedState(onMeshNodeClick)
    val currentOnSightlineClick by rememberUpdatedState(onSightlineClick)
    val currentOnRfNodeTap by rememberUpdatedState(onRfNodeTap)
    val currentOnCandidateTap by rememberUpdatedState(onCandidatePinTap)
    val currentOnObstructionTap by rememberUpdatedState(onObstructionPinTap)
    val currentHasStartedStartupZoom by rememberUpdatedState(hasStartedStartupZoom)
    val currentOnStartupZoomStarted by rememberUpdatedState(onStartupZoomStarted)
    val currentOnSaveCameraState by rememberUpdatedState(onSaveCameraState)
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
                MapView(context).apply {
                    setTileSource(rfMapTileSource(baseLayer))
                    lastAppliedLayer = baseLayer
                    setMultiTouchControls(true)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    isTilesScaledToDpi = true
                    minZoomLevel = 3.0
                    maxZoomLevel = 19.0
                    mapOrientation = savedCameraBearingDeg

                    when {
                        savedCameraLat != null && savedCameraLon != null && savedCameraZoom != null -> {
                            controller.setZoom(savedCameraZoom)
                            controller.setCenter(GeoPoint(savedCameraLat, savedCameraLon))
                        }
                        mode == WorkspaceMode.MESH -> {
                            val initialCenter = if (startupTargetLat != null && startupTargetLon != null) {
                                GeoPoint(startupTargetLat, startupTargetLon)
                            } else {
                                DEFAULT_ERIE_COUNTY_PA
                            }
                            controller.setZoom(if (hasStartedStartupZoom) STARTUP_TARGET_ZOOM else STARTUP_INITIAL_ZOOM)
                            controller.setCenter(initialCenter)
                        }
                        else -> {
                            val txLat = if (mode == WorkspaceMode.VIEWSHED) viewshedTxLat else bridgeTxLat
                            val txLon = if (mode == WorkspaceMode.VIEWSHED) viewshedTxLon else bridgeTxLon
                            val rxLat = if (mode == WorkspaceMode.VIEWSHED) viewshedRxLat else bridgeRxLat
                            val rxLon = if (mode == WorkspaceMode.VIEWSHED) viewshedRxLon else bridgeRxLon
                            controller.setZoom(10.5)
                            controller.setCenter(resolveInitialRfMapCenter(txLat, txLon, rxLat, rxLon, nodes))
                        }
                    }

                    addMapListener(object : MapListener {
                        override fun onScroll(event: ScrollEvent?): Boolean {
                            if (currentHasStartedStartupZoom || currentMode != WorkspaceMode.MESH) {
                                val center = mapCenter
                                currentOnSaveCameraState(
                                    center.latitude,
                                    center.longitude,
                                    zoomLevelDouble,
                                    mapOrientation
                                )
                            }
                            return false
                        }

                        override fun onZoom(event: ZoomEvent?): Boolean {
                            if (currentHasStartedStartupZoom || currentMode != WorkspaceMode.MESH) {
                                val center = mapCenter
                                currentOnSaveCameraState(
                                    center.latitude,
                                    center.longitude,
                                    zoomLevelDouble,
                                    mapOrientation
                                )
                            }
                            return false
                        }
                    })

                    mapViewRef = this
                }
            },
            update = { mapView ->
                if (lastAppliedLayer != baseLayer) {
                    mapView.setTileSource(rfMapTileSource(baseLayer))
                    lastAppliedLayer = baseLayer
                }

                val showContinuityOverlays = overlayContinuityMode == OverlayContinuityMode.KEEP_NODEDB_WITH_RF
                val activeTxPoint: GeoPoint?
                val activeRxPoint: GeoPoint?
                when (mode) {
                    WorkspaceMode.MESH -> {
                        activeTxPoint = null
                        activeRxPoint = null
                    }
                    WorkspaceMode.VIEWSHED -> {
                        activeTxPoint = if (dragController.draggingTarget == RfPinEndpoint.TX) {
                            dragController.txMarker?.position
                        } else if (viewshedTxLat != null && viewshedTxLon != null) {
                            GeoPoint(viewshedTxLat, viewshedTxLon)
                        } else null
                        activeRxPoint = if (dragController.draggingTarget == RfPinEndpoint.RX) {
                            dragController.rxMarker?.position
                        } else if (viewshedRxLat != null && viewshedRxLon != null) {
                            GeoPoint(viewshedRxLat, viewshedRxLon)
                        } else null
                    }
                    WorkspaceMode.BRIDGE -> {
                        activeTxPoint = if (dragController.draggingTarget == RfPinEndpoint.TX) {
                            dragController.txMarker?.position
                        } else if (bridgeTxLat != null && bridgeTxLon != null) {
                            GeoPoint(bridgeTxLat, bridgeTxLon)
                        } else null
                        activeRxPoint = if (dragController.draggingTarget == RfPinEndpoint.RX) {
                            dragController.rxMarker?.position
                        } else if (bridgeRxLat != null && bridgeRxLon != null) {
                            GeoPoint(bridgeRxLat, bridgeRxLon)
                        } else null
                    }
                }

                mapView.overlays.clear()
                dragController.txMarker = null
                dragController.rxMarker = null
                dragController.connectingLine = null

                mapView.overlays.add(
                    MapEventsOverlay(object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                            return when (currentMode) {
                                WorkspaceMode.MESH -> {
                                    if (currentIsLosMode || currentIsAddNodeMode) {
                                        currentOnMapTap(p.latitude, p.longitude)
                                        true
                                    } else {
                                        false
                                    }
                                }
                                WorkspaceMode.VIEWSHED, WorkspaceMode.BRIDGE -> {
                                    currentOnMapTap(p.latitude, p.longitude)
                                    true
                                }
                            }
                        }

                        override fun longPressHelper(p: GeoPoint): Boolean = false
                    })
                )

                val activePoolKeys = HashSet<String>()
                when (mode) {
                    WorkspaceMode.MESH -> {
                        addMeshMapOverlays(
                            context = mapView.context,
                            mapView = mapView,
                            overlayPool = overlayPool,
                            activePoolKeys = activePoolKeys,
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
                            viewshedOverlayOpacity = overlayOpacity,
                            aggregateCoverageResult = aggregateCoverageResult,
                            isAggregateCoverageEnabled = isAggregateCoverageEnabled,
                            sightlineFresnelResults = sightlineFresnelResults,
                            sightlineDiagnostics = sightlineDiagnostics,
                            onNodeClick = { node -> currentOnMeshNodeClick(node) },
                            onSightlineClick = { node -> currentOnSightlineClick(node) }
                        )
                    }
                    WorkspaceMode.VIEWSHED -> {
                        addViewshedOverlays(
                            context = mapView.context,
                            mapView = mapView,
                            dragController = dragController,
                            overlayPool = overlayPool,
                            activePoolKeys = activePoolKeys,
                            activeTxPoint = activeTxPoint,
                            activeRxPoint = activeRxPoint,
                            radiusKm = viewshedRadiusKm,
                            existingNodes = nodes,
                            showNodeDbAndSavedLayers = showContinuityOverlays,
                            currentResult = viewshedResult,
                            rxLinkResult = viewshedRxLinkResult,
                            pinnedSnapshot = viewshedPinnedSnapshot,
                            showSnapshotOnMap = viewshedShowSnapshotOnMap,
                            savedLayers = savedViewshedLayers,
                            overlayOpacity = overlayOpacity,
                            onEndpointPinTap = { endpoint -> currentOnEndpointPinTap(endpoint) },
                            onNodeTap = { node -> currentOnRfNodeTap(node) }
                        )
                    }
                    WorkspaceMode.BRIDGE -> {
                        addBridgeOverlays(
                            context = mapView.context,
                            mapView = mapView,
                            dragController = dragController,
                            overlayPool = overlayPool,
                            activePoolKeys = activePoolKeys,
                            activeTxPoint = activeTxPoint,
                            activeRxPoint = activeRxPoint,
                            radiusKm = bridgeRadiusKm,
                            existingNodes = nodes,
                            showNodeDbAndSavedLayers = showContinuityOverlays,
                            savedLayers = savedViewshedLayers,
                            currentResult = bridgeResult,
                            selectedCandidateIndex = bridgeSelectedCandidateIndex,
                            obstructions = bridgeObstructions,
                            selectedObstructionId = bridgeSelectedObstructionId,
                            overlayOpacity = overlayOpacity,
                            liveDirectHop = bridgeLiveDirectHop,
                            scrubbedProfileSample = bridgeScrubbedProfileSample,
                            onEndpointPinTap = { endpoint -> currentOnEndpointPinTap(endpoint) },
                            onNodeTap = { node -> currentOnRfNodeTap(node) },
                            onCandidateTap = { idx -> currentOnCandidateTap(idx) },
                            onObstructionTap = { obsId -> currentOnObstructionTap(obsId) }
                        )
                    }
                }
                overlayPool.pruneExcept(activePoolKeys)

                if (mode != WorkspaceMode.MESH) {
                    mapView.overlays.add(
                        DirectRfPinDragOverlay(
                            controller = dragController,
                            onTxDragEnd = { lat, lon -> currentOnTxDragEnd(lat, lon) },
                            onRxDragEnd = { lat, lon -> currentOnRxDragEnd(lat, lon) },
                            onEndpointPinTap = { endpoint -> currentOnEndpointPinTap(endpoint) },
                            onDragStart = { currentOnPinDragStart(currentMode) }
                        )
                    )
                }

                mapView.invalidate()
            },
            onRelease = { mapView ->
                if (currentHasStartedStartupZoom || currentMode != WorkspaceMode.MESH) {
                    val center = mapView.mapCenter
                    currentOnSaveCameraState(
                        center.latitude,
                        center.longitude,
                        mapView.zoomLevelDouble,
                        mapView.mapOrientation
                    )
                }
                mapView.onPause()
                mapView.onDetach()
                if (mapViewRef === mapView) {
                    mapViewRef = null
                }
            }
        )

        if (mode == WorkspaceMode.VIEWSHED || mode == WorkspaceMode.BRIDGE) {
            RfCrosshairReticle(
                isTxTarget = if (mode == WorkspaceMode.VIEWSHED) viewshedIsTxTarget else bridgeIsTxTarget,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }

    LaunchedEffect(dropCenterRequestId) {
        if (dropCenterRequestId <= 0L) return@LaunchedEffect
        val mapView = mapViewRef ?: return@LaunchedEffect
        val center = mapView.mapCenter
        currentOnDropCenter(center.latitude, center.longitude)
    }

    LaunchedEffect(startupTargetLat, startupTargetLon, hasStartedStartupZoom, mapViewRef) {
        val mapView = mapViewRef ?: return@LaunchedEffect
        val targetLat = startupTargetLat ?: return@LaunchedEffect
        val targetLon = startupTargetLon ?: return@LaunchedEffect
        if (!hasStartedStartupZoom && mode == WorkspaceMode.MESH) {
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

    LaunchedEffect(centerFocusRequestId, mapViewRef) {
        val mapView = mapViewRef ?: return@LaunchedEffect
        val targetLat = centerFocusLat ?: return@LaunchedEffect
        val targetLon = centerFocusLon ?: return@LaunchedEffect
        if (centerFocusRequestId > 0L && centerFocusRequestId != lastHandledCenterFocusId) {
            lastHandledCenterFocusId = centerFocusRequestId
            val targetPoint = GeoPoint(targetLat, targetLon)
            val minZoom = centerFocusMinZoom
            mapView.post {
                if (mapView.isAttachedToWindow) {
                    if (minZoom != null && mapView.zoomLevelDouble < minZoom) {
                        mapView.controller.animateTo(targetPoint, minZoom, 450L)
                    } else {
                        mapView.controller.animateTo(targetPoint)
                    }
                }
            }
        }
    }

    LaunchedEffect(viewshedResult, mapViewRef) {
        val mapView = mapViewRef ?: return@LaunchedEffect
        if (viewshedResult != null && viewshedResult != lastFittedViewshedResult) {
            lastFittedViewshedResult = viewshedResult
            zoomMapToBoundsSafely(
                mapView = mapView,
                northLat = viewshedResult.northLat,
                eastLon = viewshedResult.eastLon,
                southLat = viewshedResult.southLat,
                westLon = viewshedResult.westLon
            )
        }
    }

    LaunchedEffect(bridgeResult, mapViewRef) {
        val mapView = mapViewRef ?: return@LaunchedEffect
        if (bridgeResult != null && bridgeResult != lastFittedBridgeResult) {
            lastFittedBridgeResult = bridgeResult
            zoomMapToBoundsSafely(
                mapView = mapView,
                northLat = bridgeResult.northLat,
                eastLon = bridgeResult.eastLon,
                southLat = bridgeResult.southLat,
                westLon = bridgeResult.westLon
            )
        }
    }

    LaunchedEffect(bridgeSelectedObstructionId, bridgeObstructionFocusNonce, mapViewRef) {
        if (mode != WorkspaceMode.BRIDGE) return@LaunchedEffect
        val mapView = mapViewRef ?: return@LaunchedEffect
        val obsId = bridgeSelectedObstructionId ?: return@LaunchedEffect
        val target = bridgeObstructions.firstOrNull { it.id == obsId } ?: return@LaunchedEffect
        val targetPoint = GeoPoint(target.point.latitude, target.point.longitude)
        if (mapView.zoomLevelDouble < 13.0) {
            mapView.controller.animateTo(targetPoint, 13.0, 450L)
        } else {
            mapView.controller.animateTo(targetPoint)
        }
    }
}
