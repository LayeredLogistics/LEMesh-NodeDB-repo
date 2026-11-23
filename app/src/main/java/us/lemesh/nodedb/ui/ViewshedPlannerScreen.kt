package us.lemesh.nodedb.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import us.lemesh.nodedb.R
import us.lemesh.nodedb.data.FadeMarginPreset
import us.lemesh.nodedb.data.LayerRenderMode
import us.lemesh.nodedb.data.MapExportEngine
import us.lemesh.nodedb.data.MeshGeoUtils
import us.lemesh.nodedb.data.MeshNode
import us.lemesh.nodedb.ui.state.WorkspaceMode
import kotlinx.coroutines.launch
import org.osmdroid.views.MapView
import us.lemesh.nodedb.ui.theme.AlertErrorRed
import us.lemesh.nodedb.ui.theme.JetBrainsMonoFontFamily
import us.lemesh.nodedb.ui.theme.NodeDirectGreen
import us.lemesh.nodedb.ui.theme.NodeMultiHopSky
import us.lemesh.nodedb.ui.theme.NodeSelfAmber
import us.lemesh.nodedb.ui.theme.TacticalCanvas
import us.lemesh.nodedb.ui.theme.TacticalOutline
import us.lemesh.nodedb.ui.theme.TacticalSurface
import us.lemesh.nodedb.ui.theme.TacticalSurfaceElevated
import us.lemesh.nodedb.ui.theme.TextMuted
import us.lemesh.nodedb.ui.theme.TextPrimary
import us.lemesh.nodedb.ui.theme.TextSecondary
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ViewshedPlannerScreen(
    uiState: ViewshedUiState,
    existingNodes: List<MeshNode>,
    baseLayer: MapBaseLayer,
    onSelectBaseLayer: (MapBaseLayer) -> Unit,
    @Suppress("UNUSED_PARAMETER") onToggleTopBarExpanded: () -> Unit,
    onSetPlacementMode: (ViewshedPlacementMode) -> Unit,
    onSetPlacementTarget: (ViewshedPlacementTarget) -> Unit,
    onSetCustomCoordinate: (Double, Double) -> Unit,
    onTxDragEnd: (Double, Double) -> Unit,
    onRxDragEnd: (Double, Double) -> Unit,
    onSelectExistingNode: (MeshNode) -> Unit,
    onSelectRxNode: (MeshNode) -> Unit,
    @Suppress("UNUSED_PARAMETER") onExistingNodeTap: (MeshNode) -> Unit,
    onSwapTxRx: () -> Unit,
    onAntennaHeightChange: (Float) -> Unit,
    onRxAntennaHeightChange: (Float) -> Unit,
    onRadiusKmChange: (Int) -> Unit,
    onFrequencyChange: (String) -> Unit,
    onTxPowerChange: (Float) -> Unit,
    onAntennaGainChange: (Float) -> Unit,
    onSensitivityChange: (Float) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onCalculateCoverage: () -> Unit,
    onTogglePinSnapshot: () -> Unit,
    onToggleShowSnapshotOnMap: () -> Unit,
    suggestedLayerName: String,
    suggestedLayerTint: Int,
    onSaveLayer: (name: String, tintColorArgb: Int, renderMode: LayerRenderMode) -> Unit,
    onToggleSavedLayerEnabled: (String) -> Unit,
    onToggleSavedLayerRenderMode: (String) -> Unit,
    onDeleteSavedLayer: (String) -> Unit,
    onClearCurrentViewshed: () -> Unit,
    onClearAllAndStartFresh: () -> Unit,
    onSendToBridge: () -> Unit,
    onApplyMastFix: (Float, Float) -> Unit,
    onInspectObstructedNode: (MeshNode) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val plottedNodes = remember(existingNodes) {
        existingNodes.filter { it.hasValidGps }.sortedBy { it.displayLongName.lowercase() }
    }
    var controlsExpanded by rememberSaveable { mutableStateOf(false) }
    var showSavedLayersSheet by remember { mutableStateOf(false) }
    var showSaveLayerDialog by remember { mutableStateOf(false) }
    var showMapToolsSheet by remember { mutableStateOf(false) }
    var inspectedMapNode by remember { mutableStateOf<MeshNode?>(null) }
    var dropCenterRequestId by remember { mutableLongStateOf(0L) }
    var showExportDialog by remember { mutableStateOf(false) }
    var activeMapView by remember { mutableStateOf<MapView?>(null) }
    val drawerScrollState = rememberScrollState()
    var rxLinkCardOffsetY by remember { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()

    val activeTarget = uiState.effectivePlacementTarget
    val isTxTarget = activeTarget == ViewshedPlacementTarget.TX
    val placementChoice = uiState.placementMode.toRfChoice()
    val hasClearableOverlay = uiState.currentResult != null || uiState.rxLinkResult != null
    val enabledLayersCount = uiState.savedLayers.count { it.isEnabled }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TacticalCanvas)
    ) {
        MeshRfMapCanvas(
            mode = WorkspaceMode.VIEWSHED,
            baseLayer = baseLayer,
            nodes = plottedNodes,
            overlayOpacity = uiState.overlayOpacity,
            viewshedTxLat = uiState.txLat,
            viewshedTxLon = uiState.txLon,
            viewshedRxLat = uiState.rxLat,
            viewshedRxLon = uiState.rxLon,
            viewshedRadiusKm = uiState.radiusKm,
            viewshedIsTxTarget = isTxTarget,
            viewshedResult = uiState.currentResult,
            viewshedRxLinkResult = uiState.rxLinkResult,
            viewshedPinnedSnapshot = uiState.pinnedSnapshot,
            viewshedShowSnapshotOnMap = uiState.showSnapshotOnMap,
            savedViewshedLayers = uiState.savedLayers,
            dropCenterRequestId = dropCenterRequestId,
            onMapCoordinateTap = onSetCustomCoordinate,
            onDropAtMapCenter = onSetCustomCoordinate,
            onTxDragEnd = onTxDragEnd,
            onRxDragEnd = onRxDragEnd,
            onEndpointPinTap = { endpoint ->
                onSetPlacementTarget(
                    if (endpoint == RfPinEndpoint.TX) ViewshedPlacementTarget.TX else ViewshedPlacementTarget.RX
                )
            },
            onRfNodeTap = { tappedNode ->
                inspectedMapNode = tappedNode
            },
            onMapViewReady = { activeMapView = it },
            modifier = Modifier.fillMaxSize()
        )

        // Slim single-card top placement strip
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                color = TacticalSurface.copy(alpha = 0.93f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, TacticalOutline, RoundedCornerShape(16.dp))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    RfQuickPlacementStrip(
                        placementChoice = placementChoice,
                        isTxTarget = isTxTarget,
                        hasSwappableEndpoints = uiState.hasTxSet || uiState.hasRxSet,
                        testTagPrefix = "viewshed",
                        onSelectMode = { onSetPlacementMode(it.toViewshedMode()) },
                        onDropAtCenter = { dropCenterRequestId++ },
                        onSwapTxRx = onSwapTxRx,
                        trailingContent = {
                            Surface(
                                onClick = { showSavedLayersSheet = true },
                                color = if (enabledLayersCount > 0) NodeDirectGreen.copy(alpha = 0.16f) else TacticalSurfaceElevated,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (enabledLayersCount > 0) NodeDirectGreen.copy(alpha = 0.7f) else TacticalOutline
                                ),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("viewshed_layers_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Layers,
                                        contentDescription = stringResource(R.string.saved_layers_title),
                                        tint = if (enabledLayersCount > 0) NodeDirectGreen else NodeMultiHopSky,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    if (enabledLayersCount > 0) {
                                        Box(
                                            modifier = Modifier
                                                .clip(CircleShape)
                                                .background(NodeDirectGreen)
                                                .padding(horizontal = 5.dp, vertical = 1.dp)
                                        ) {
                                            Text(
                                                text = enabledLayersCount.toString(),
                                                fontFamily = JetBrainsMonoFontFamily,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 10.sp,
                                                color = TacticalCanvas
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    )
                    SiteCoordinatesStrip(uiState = uiState)
                }
            }

            AnimatedVisibility(visible = uiState.errorMessage != null) {
                RfFloatingErrorBanner(
                    message = uiState.errorMessage.orEmpty(),
                    onDismiss = onDismissError
                )
            }
        }

        if (uiState.isCalculating) {
            RfPlannerProgressOverlay(
                note = uiState.statusNote ?: "Computing viewshed…",
                borderColor = NodeMultiHopSky,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 720.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                FloatingMapToolsButton(
                    onClick = { showMapToolsSheet = true },
                    testTag = "viewshed_map_tools_fab"
                )
            }

            Surface(
                color = TacticalSurface.copy(alpha = 0.96f),
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, TacticalOutline, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    ViewshedDrawerHeader(
                        uiState = uiState,
                        controlsExpanded = controlsExpanded,
                        onCalculateCoverage = onCalculateCoverage,
                        onToggleExpanded = { controlsExpanded = !controlsExpanded }
                    )

                    AnimatedVisibility(visible = controlsExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp)
                                .verticalScroll(drawerScrollState)
                                .padding(top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            ViewshedControlsContent(
                                uiState = uiState,
                                plottedNodes = plottedNodes,
                                onSelectTxNode = onSelectExistingNode,
                                onSelectRxNode = onSelectRxNode,
                                onAntennaHeightChange = onAntennaHeightChange,
                                onRxAntennaHeightChange = onRxAntennaHeightChange,
                                onRadiusKmChange = onRadiusKmChange,
                                onFrequencyChange = onFrequencyChange,
                                onTxPowerChange = onTxPowerChange,
                                onAntennaGainChange = onAntennaGainChange,
                                onSensitivityChange = onSensitivityChange,
                                onOpacityChange = onOpacityChange,
                                onCalculateCoverage = onCalculateCoverage,
                                onOpenSaveLayerDialog = { showSaveLayerDialog = true },
                                onTogglePinSnapshot = onTogglePinSnapshot,
                                onToggleShowSnapshotOnMap = onToggleShowSnapshotOnMap,
                                onSendToBridge = onSendToBridge,
                                onDismissError = onDismissError
                            )
                            if (uiState.areBothEndpointsSet) {
                                RxLinkCheckCard(
                                    rxLink = uiState.rxLinkResult,
                                    rxLabel = uiState.rxLabel,
                                    isRxLinkLoading = uiState.isRxLinkLoading,
                                    isRefreshingCoverage = uiState.isCalculating,
                                    txHeightMeters = uiState.antennaHeightMeters,
                                    rxHeightMeters = uiState.rxAntennaHeightMeters,
                                    onAcceptMastFix = { option ->
                                        onApplyMastFix(
                                            option.targetTxHeightMeters.toFloat(),
                                            option.targetRxHeightMeters.toFloat()
                                        )
                                    },
                                    onSendToBridge = onSendToBridge,
                                    modifier = Modifier.onGloballyPositioned { coords ->
                                        rxLinkCardOffsetY = coords.positionInParent().y.roundToInt()
                                    }
                                )
                            }
                            uiState.currentResult?.let { result ->
                                ViewshedScorecardSection(
                                    result = result,
                                    pinnedSnapshot = uiState.pinnedSnapshot,
                                    onInspectObstructedNode = { obstructed ->
                                        onInspectObstructedNode(obstructed.node)
                                        coroutineScope.launch {
                                            drawerScrollState.animateScrollTo(rxLinkCardOffsetY)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showMapToolsSheet) {
        val layersLabel = if (uiState.savedLayers.isNotEmpty()) {
            "${stringResource(R.string.filter_saved_layers)} ($enabledLayersCount/${uiState.savedLayers.size})"
        } else {
            stringResource(R.string.filter_saved_layers)
        }
        MapToolsBottomSheet(
            baseLayer = baseLayer,
            legendMode = MapToolsLegendMode.VIEWSHED,
            sensitivityDbm = uiState.sensitivityDbm.toDouble(),
            testTagPrefix = "viewshed",
            onSelectBaseLayer = onSelectBaseLayer,
            onExport = { showExportDialog = true },
            savedLayersSummary = layersLabel,
            onOpenSavedLayers = { showSavedLayersSheet = true },
            hasClearableOverlay = hasClearableOverlay && !uiState.isCalculating,
            onClearOverlay = onClearCurrentViewshed,
            hasResettablePins = uiState.hasAnyOverlayOrPins && !uiState.isCalculating,
            onResetPins = onClearAllAndStartFresh,
            onDismiss = { showMapToolsSheet = false }
        )
    }

    if (showSavedLayersSheet) {
        SavedViewshedLayersSheet(
            layers = uiState.savedLayers,
            overlayOpacity = uiState.overlayOpacity,
            onOpacityChange = onOpacityChange,
            onToggleEnabled = onToggleSavedLayerEnabled,
            onToggleRenderMode = onToggleSavedLayerRenderMode,
            onDeleteLayer = onDeleteSavedLayer,
            onDismiss = { showSavedLayersSheet = false }
        )
    }

    if (showSaveLayerDialog && uiState.currentResult != null) {
        SaveViewshedLayerDialog(
            initialName = suggestedLayerName,
            initialTintArgb = suggestedLayerTint,
            onDismiss = { showSaveLayerDialog = false },
            onConfirmSave = { name, tint, mode ->
                showSaveLayerDialog = false
                onSaveLayer(name, tint, mode)
            }
        )
    }

    inspectedMapNode?.let { tappedNode ->
        val isCurrentTx = uiState.sourceNode?.let { it.id == tappedNode.id && it.num == tappedNode.num } == true
        val isCurrentRx = uiState.rxNode?.let { it.id == tappedNode.id && it.num == tappedNode.num } == true
        RfNodeSiteActionSheet(
            node = tappedNode,
            isCurrentTx = isCurrentTx,
            isCurrentRx = isCurrentRx,
            onSetAsTx = {
                onSelectExistingNode(tappedNode)
                inspectedMapNode = null
            },
            onSetAsRx = {
                onSelectRxNode(tappedNode)
                inspectedMapNode = null
            },
            onDismiss = { inspectedMapNode = null }
        )
    }

    MapExportHost(
        visible = showExportDialog,
        filePrefix = "lemesh_viewshed",
        mapViewProvider = { activeMapView },
        buildGeoJson = {
            MapExportEngine.buildViewshedGeoJson(
                uiState = uiState,
                baseLayerName = baseLayer.displayName
            )
        },
        buildSummary = {
            MapExportEngine.buildViewshedSummary(
                uiState = uiState,
                baseLayer = baseLayer
            )
        },
        buildKml = {
            MapExportEngine.buildViewshedKml(
                uiState = uiState,
                baseLayerName = baseLayer.displayName
            )
        },
        onDismiss = { showExportDialog = false }
    )
}

@Composable
internal fun ViewshedDrawerHeader(
    uiState: ViewshedUiState,
    controlsExpanded: Boolean,
    onCalculateCoverage: () -> Unit,
    onToggleExpanded: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Radio & Mast Controls",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary
            )
            val rxHop = uiState.rxLinkResult?.hop
            Text(
                text = when {
                    uiState.currentResult != null -> String.format(
                        Locale.US,
                        "%.1f km² • %.2f km clear • %d nodes",
                        uiState.currentResult.coveredAreaKm2,
                        uiState.currentResult.maxUnobstructedRangeKm,
                        uiState.currentResult.coveredClearNodes.size
                    )
                    rxHop != null -> String.format(
                        Locale.US,
                        "RX Probe: %.1f dBm (%+.1f dB margin)",
                        rxHop.predictedRssiDbm,
                        rxHop.linkMarginDb
                    )
                    !uiState.hasTxSet -> "Place TX site on the map to calculate"
                    else ->
                        "TX ${uiState.antennaHeightMeters.toInt()}m • RX ${uiState.rxAntennaHeightMeters.toInt()}m • ${uiState.radiusKm}km radius"
                },
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 10.sp,
                color = when {
                    uiState.currentResult != null || rxHop != null -> NodeDirectGreen
                    !uiState.hasTxSet -> NodeSelfAmber
                    else -> TextSecondary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onCalculateCoverage,
                enabled = uiState.hasTxSet && !uiState.isCalculating,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NodeDirectGreen,
                    contentColor = TacticalCanvas
                ),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("compact_calculate_coverage_button")
            ) {
                Text(
                    text = "Calculate",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            IconButton(
                onClick = onToggleExpanded,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("viewshed_drawer_toggle")
            ) {
                Icon(
                    imageVector = if (controlsExpanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                    contentDescription = "Toggle viewshed controls",
                    tint = TextSecondary
                )
            }
        }
    }
}



@Composable
internal fun SiteCoordinatesStrip(uiState: ViewshedUiState) {
    val activeTarget = uiState.effectivePlacementTarget
    val distKm = uiState.directDistanceKm
    val bearingDeg = uiState.directBearingDeg

    val statusText = if (distKm != null && bearingDeg != null) {
        val modeNote = if (uiState.placementMode == ViewshedPlacementMode.AUTO) {
            "Auto next: ${if (activeTarget == ViewshedPlacementTarget.TX) "TX" else "RX"}"
        } else {
            "Placing ${activeTarget.label}"
        }
        String.format(
            Locale.US,
            "TX ↔ RX: %.2f km (%s) • %s",
            distKm,
            MeshGeoUtils.formatCardinalDirection(bearingDeg),
            modeNote
        )
    } else if (uiState.hasTxSet) {
        val elevText = when {
            uiState.isGroundElevLoading -> "DEM…"
            uiState.groundElevationMeters != null -> String.format(Locale.US, "%.0f m ASL", uiState.groundElevationMeters)
            else -> "Elev N/A"
        }
        "TX: ${uiState.siteLabel} ($elevText) • Tap or Drop ${if (activeTarget == ViewshedPlacementTarget.TX) "TX" else "RX"}"
    } else {
        val rxBadge = if (uiState.hasRxSet) "RX Set" else "RX Unset"
        "TX Unset • $rxBadge • Tap map or Drop ${if (activeTarget == ViewshedPlacementTarget.TX) "TX" else "RX"}"
    }

    Text(
        text = statusText,
        fontFamily = JetBrainsMonoFontFamily,
        fontSize = 11.sp,
        color = if (distKm != null) NodeDirectGreen else NodeSelfAmber,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
internal fun ViewshedControlsContent(
    uiState: ViewshedUiState,
    plottedNodes: List<MeshNode>,
    onSelectTxNode: (MeshNode) -> Unit,
    onSelectRxNode: (MeshNode) -> Unit,
    onAntennaHeightChange: (Float) -> Unit,
    onRxAntennaHeightChange: (Float) -> Unit,
    onRadiusKmChange: (Int) -> Unit,
    onFrequencyChange: (String) -> Unit,
    onTxPowerChange: (Float) -> Unit,
    onAntennaGainChange: (Float) -> Unit,
    onSensitivityChange: (Float) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onCalculateCoverage: () -> Unit,
    onOpenSaveLayerDialog: () -> Unit,
    onTogglePinSnapshot: () -> Unit,
    onToggleShowSnapshotOnMap: () -> Unit,
    onSendToBridge: () -> Unit,
    onDismissError: () -> Unit,
    onSelectFadeMarginPreset: ((FadeMarginPreset) -> Unit)? = null
) {
    RfEndpointCard(
        role = "TX",
        label = uiState.siteLabel,
        lat = uiState.txLat,
        lon = uiState.txLon,
        groundElevationMeters = uiState.groundElevationMeters,
        isElevLoading = uiState.isGroundElevLoading,
        accentColor = NodeSelfAmber,
        plottedNodes = plottedNodes,
        pickNodeTestTag = "select_viewshed_tx_node_button",
        onSelectNode = onSelectTxNode
    )

    RfEndpointCard(
        role = "RX",
        label = uiState.rxLabel,
        lat = uiState.rxLat,
        lon = uiState.rxLon,
        groundElevationMeters = uiState.rxGroundElevationMeters,
        isElevLoading = uiState.isRxElevLoading,
        accentColor = NodeMultiHopSky,
        plottedNodes = plottedNodes,
        pickNodeTestTag = "select_viewshed_rx_node_button",
        onSelectNode = onSelectRxNode
    )

    RfLabeledSlider(
        label = "TX Mast AGL",
        valueText = "${uiState.antennaHeightMeters.roundToInt()} m",
        value = uiState.antennaHeightMeters,
        valueRange = 1f..60f,
        accentColor = NodeSelfAmber,
        onValueChange = onAntennaHeightChange
    )

    RfLabeledSlider(
        label = "RX Mast AGL",
        valueText = "${uiState.rxAntennaHeightMeters.roundToInt()} m",
        value = uiState.rxAntennaHeightMeters,
        valueRange = 1f..60f,
        accentColor = NodeMultiHopSky,
        onValueChange = onRxAntennaHeightChange
    )

    RfRadioParamsSection(
        radiusLabel = "Max Calculation Radius",
        radiusKm = uiState.radiusKm,
        radiusTagPrefix = "radius_chip_",
        selectedRadiusColor = NodeMultiHopSky,
        frequencyText = uiState.frequencyText,
        txPowerDbm = uiState.txPowerDbm,
        antennaGainDbi = uiState.antennaGainDbi,
        sensitivityDbm = uiState.sensitivityDbm,
        opacityLabel = "Heatmap Opacity",
        overlayOpacity = uiState.overlayOpacity,
        errorMessage = null,
        onRadiusKmChange = onRadiusKmChange,
        onFrequencyChange = onFrequencyChange,
        onTxPowerChange = onTxPowerChange,
        onAntennaGainChange = onAntennaGainChange,
        onSensitivityChange = onSensitivityChange,
        onOpacityChange = onOpacityChange,
        onDismissError = onDismissError
    )

    if (onSelectFadeMarginPreset != null) {
        PropagationFadeMarginStrip(
            selectedPreset = uiState.fadeMarginPreset,
            onSelectPreset = onSelectFadeMarginPreset,
            testTagPrefix = "viewshed"
        )
    }

    Button(
        onClick = onCalculateCoverage,
        enabled = uiState.hasTxSet && !uiState.isCalculating,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = NodeDirectGreen,
            contentColor = TacticalCanvas
        ),
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .testTag("calculate_coverage_button")
    ) {
        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "Calculate Coverage",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
    }

    if (uiState.currentResult != null || uiState.isSnapshotPinned) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onOpenSaveLayerDialog,
                enabled = uiState.currentResult != null && !uiState.isCalculating,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                modifier = Modifier
                    .weight(1f)
                    .minimumInteractiveComponentSize()
                    .testTag("save_viewshed_layer_button")
            ) {
                Icon(
                    imageVector = Icons.Default.BookmarkAdd,
                    contentDescription = null,
                    tint = if (uiState.currentResult != null) NodeMultiHopSky else TextMuted,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.save_layer_action),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (uiState.currentResult != null) TextPrimary else TextMuted
                )
            }

            FilterChip(
                selected = uiState.isSnapshotPinned,
                onClick = onTogglePinSnapshot,
                enabled = uiState.currentResult != null || uiState.isSnapshotPinned,
                label = {
                    Text(
                        text = if (uiState.isSnapshotPinned) "Pinned" else "Pin Snapshot",
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = if (uiState.isSnapshotPinned) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = TacticalSurfaceElevated,
                    labelColor = TextSecondary,
                    selectedContainerColor = NodeSelfAmber.copy(alpha = 0.22f),
                    selectedLabelColor = NodeSelfAmber,
                    selectedLeadingIconColor = NodeSelfAmber
                ),
                modifier = Modifier
                    .weight(1f)
                    .minimumInteractiveComponentSize()
                    .testTag("pin_snapshot_toggle")
            )
        }
    }

    if (uiState.areBothEndpointsSet) {
        val canSendToBridge = !uiState.isCalculating
        OutlinedButton(
            onClick = onSendToBridge,
            enabled = canSendToBridge,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, NodeMultiHopSky.copy(alpha = 0.75f)),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = NodeMultiHopSky.copy(alpha = 0.14f),
                contentColor = NodeMultiHopSky
            ),
            modifier = Modifier
                .fillMaxWidth()
                .minimumInteractiveComponentSize()
                .testTag("send_viewshed_to_bridge_button")
        ) {
            Icon(
                imageVector = Icons.Default.Hub,
                contentDescription = null,
                tint = NodeMultiHopSky,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.send_to_bridge),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
        }
    }

    if (uiState.isSnapshotPinned && uiState.pinnedSnapshot != null) {
        FilterChip(
            selected = uiState.showSnapshotOnMap,
            onClick = onToggleShowSnapshotOnMap,
            label = {
                Text(
                    text = if (uiState.showSnapshotOnMap) "Snapshot Overlay: Visible" else "Overlay Snapshot on Map",
                    style = MaterialTheme.typography.labelMedium
                )
            },
            colors = FilterChipDefaults.filterChipColors(
                containerColor = TacticalSurfaceElevated,
                labelColor = TextSecondary,
                selectedContainerColor = NodeSelfAmber.copy(alpha = 0.2f),
                selectedLabelColor = NodeSelfAmber
            )
        )
    }
}

internal fun ViewshedPlacementMode.toRfChoice(): RfPlacementChoice = when (this) {
    ViewshedPlacementMode.AUTO -> RfPlacementChoice.AUTO
    ViewshedPlacementMode.TX -> RfPlacementChoice.TX
    ViewshedPlacementMode.RX -> RfPlacementChoice.RX
}

internal fun RfPlacementChoice.toViewshedMode(): ViewshedPlacementMode = when (this) {
    RfPlacementChoice.AUTO -> ViewshedPlacementMode.AUTO
    RfPlacementChoice.TX -> ViewshedPlacementMode.TX
    RfPlacementChoice.RX -> ViewshedPlacementMode.RX
}
