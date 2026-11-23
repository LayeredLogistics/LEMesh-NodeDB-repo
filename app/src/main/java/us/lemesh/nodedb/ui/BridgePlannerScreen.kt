package us.lemesh.nodedb.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import us.lemesh.nodedb.R
import us.lemesh.nodedb.data.BridgeProfileMode
import us.lemesh.nodedb.data.BridgeProfileSample
import us.lemesh.nodedb.data.FadeMarginPreset
import us.lemesh.nodedb.data.MapExportEngine
import us.lemesh.nodedb.data.MeshGeoUtils
import us.lemesh.nodedb.data.MeshNode
import us.lemesh.nodedb.data.RelayBridgeEngine
import us.lemesh.nodedb.data.ViewshedPointStatus
import us.lemesh.nodedb.ui.state.WorkspaceMode
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
fun BridgePlannerScreen(
    uiState: BridgeUiState,
    existingNodes: List<MeshNode>,
    baseLayer: MapBaseLayer,
    onSelectBaseLayer: (MapBaseLayer) -> Unit,
    @Suppress("UNUSED_PARAMETER") onToggleTopBarExpanded: () -> Unit,
    onSetPlacementMode: (BridgePlacementMode) -> Unit,
    onSetPlacementTarget: (BridgePlacementTarget) -> Unit,
    onMapTap: (Double, Double) -> Unit,
    onTxDragEnd: (Double, Double) -> Unit,
    onRxDragEnd: (Double, Double) -> Unit,
    onSelectTxNode: (MeshNode) -> Unit,
    onSelectRxNode: (MeshNode) -> Unit,
    @Suppress("UNUSED_PARAMETER") onExistingNodeTap: (MeshNode) -> Unit,
    onSwapTxRx: () -> Unit,
    onClearOverlay: () -> Unit,
    onResetAll: () -> Unit,
    onTxHeightChange: (Float) -> Unit,
    onRxHeightChange: (Float) -> Unit,
    onRelayHeightChange: (Float) -> Unit,
    onRadiusKmChange: (Int) -> Unit,
    onFrequencyChange: (String) -> Unit,
    onTxPowerChange: (Float) -> Unit,
    onTxAntennaGainChange: (Float) -> Unit,
    onTxSensitivityChange: (Float) -> Unit,
    onRxPowerChange: (Float) -> Unit,
    onRxAntennaGainChange: (Float) -> Unit,
    onRxSensitivityChange: (Float) -> Unit,
    onRelayPowerChange: (Float) -> Unit,
    onRelayAntennaGainChange: (Float) -> Unit,
    onRelaySensitivityChange: (Float) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onCalculateBridge: () -> Unit,
    onSelectCandidate: (Int) -> Unit,
    onSelectDrawerTab: (BridgeDrawerTab) -> Unit,
    onSelectObstruction: (String?) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
    onSelectProfileMode: (BridgeProfileMode) -> Unit = {},
    onScrubProfileSample: (BridgeProfileSample?) -> Unit = {},
    onRetryLiveProfile: () -> Unit = {}
) {
    val plottedNodes = remember(existingNodes) {
        existingNodes.filter { it.hasValidGps }.sortedBy { it.displayLongName.lowercase() }
    }
    var controlsExpanded by rememberSaveable { mutableStateOf(false) }
    var showMapToolsSheet by remember { mutableStateOf(false) }
    var inspectedMapNode by remember { mutableStateOf<MeshNode?>(null) }
    var dropCenterRequestId by remember { mutableLongStateOf(0L) }
    var showExportDialog by remember { mutableStateOf(false) }
    var activeMapView by remember { mutableStateOf<MapView?>(null) }

    val activeTarget = uiState.effectivePlacementTarget
    val isTxTarget = activeTarget == BridgePlacementTarget.TX
    val placementChoice = uiState.placementMode.toRfChoice()

    val obstructions = remember(uiState.currentResult, uiState.selectedCandidateIndex, uiState.liveDirectHop) {
        val solved = uiState.currentResult
        if (solved != null) {
            RelayBridgeEngine.buildObstructionList(solved, uiState.selectedCandidateIndex)
        } else {
            RelayBridgeEngine.buildDirectObstructionList(uiState.liveDirectHop)
        }
    }
    val obstructionBadgeCount = remember(obstructions) {
        obstructions.count { it.point.status != ViewshedPointStatus.CLEAR }
    }
    val selectedObstruction = remember(obstructions, uiState.selectedObstructionId) {
        obstructions.firstOrNull { it.id == uiState.selectedObstructionId }
    }
    val activeCandidateRank = remember(uiState.currentResult, uiState.selectedCandidateIndex) {
        val result = uiState.currentResult ?: return@remember null
        (result.topCandidates.getOrNull(uiState.selectedCandidateIndex)
            ?: result.topCandidates.firstOrNull())?.rank
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TacticalCanvas)
    ) {
        MeshRfMapCanvas(
            mode = WorkspaceMode.BRIDGE,
            baseLayer = baseLayer,
            nodes = plottedNodes,
            overlayOpacity = uiState.overlayOpacity,
            bridgeTxLat = uiState.txLat,
            bridgeTxLon = uiState.txLon,
            bridgeRxLat = uiState.rxLat,
            bridgeRxLon = uiState.rxLon,
            bridgeRadiusKm = uiState.radiusKm,
            bridgeIsTxTarget = isTxTarget,
            bridgeResult = uiState.currentResult,
            bridgeSelectedCandidateIndex = uiState.selectedCandidateIndex,
            bridgeObstructions = obstructions,
            bridgeSelectedObstructionId = uiState.selectedObstructionId,
            bridgeObstructionFocusNonce = uiState.obstructionFocusNonce,
            bridgeLiveDirectHop = uiState.liveDirectHop,
            bridgeScrubbedProfileSample = uiState.scrubbedProfileSample,
            dropCenterRequestId = dropCenterRequestId,
            onMapCoordinateTap = onMapTap,
            onDropAtMapCenter = onMapTap,
            onTxDragEnd = onTxDragEnd,
            onRxDragEnd = onRxDragEnd,
            onEndpointPinTap = { endpoint ->
                onSetPlacementTarget(
                    if (endpoint == RfPinEndpoint.TX) BridgePlacementTarget.TX else BridgePlacementTarget.RX
                )
            },
            onRfNodeTap = { tappedNode ->
                inspectedMapNode = tappedNode
            },
            onCandidatePinTap = onSelectCandidate,
            onObstructionPinTap = { obsId ->
                onSelectObstruction(obsId)
                onSelectDrawerTab(BridgeDrawerTab.OBSTRUCTIONS)
                controlsExpanded = true
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
                        testTagPrefix = "bridge",
                        onSelectMode = { onSetPlacementMode(it.toBridgeMode()) },
                        onDropAtCenter = { dropCenterRequestId++ },
                        onSwapTxRx = onSwapTxRx
                    )
                    BridgeStatusLine(uiState = uiState)
                }
            }

            AnimatedVisibility(visible = uiState.errorMessage != null) {
                RfFloatingErrorBanner(
                    message = uiState.errorMessage.orEmpty(),
                    onDismiss = onDismissError
                )
            }

            AnimatedVisibility(visible = selectedObstruction != null) {
                selectedObstruction?.let { obs ->
                    BridgeSelectedObstructionCallout(
                        item = obs,
                        relayRank = activeCandidateRank,
                        onDismiss = { onSelectObstruction(null) }
                    )
                }
            }
        }

        if (uiState.isCalculating) {
            RfPlannerProgressOverlay(
                note = uiState.statusNote ?: "Solving TX–RX bridge…",
                borderColor = NodeDirectGreen,
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
                    testTag = "bridge_map_tools_fab"
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
                    BridgeDrawerHeader(
                        uiState = uiState,
                        controlsExpanded = controlsExpanded,
                        onCalculateBridge = onCalculateBridge,
                        onOpenProfile = {
                            onSelectDrawerTab(BridgeDrawerTab.OBSTRUCTIONS)
                            controlsExpanded = true
                        },
                        onToggleExpanded = { controlsExpanded = !controlsExpanded }
                    )

                    AnimatedVisibility(visible = controlsExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            BridgeDrawerTabBar(
                                activeTab = uiState.activeDrawerTab,
                                obstructionCount = obstructionBadgeCount,
                                onSelectTab = onSelectDrawerTab
                            )

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 400.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                when (uiState.activeDrawerTab) {
                                    BridgeDrawerTab.RESULTS -> {
                                        BridgeControlsContent(
                                            uiState = uiState,
                                            plottedNodes = plottedNodes,
                                            onSelectTxNode = onSelectTxNode,
                                            onSelectRxNode = onSelectRxNode,
                                            onTxHeightChange = onTxHeightChange,
                                            onRxHeightChange = onRxHeightChange,
                                            onRelayHeightChange = onRelayHeightChange,
                                            onRadiusKmChange = onRadiusKmChange,
                                            onFrequencyChange = onFrequencyChange,
                                            onTxPowerChange = onTxPowerChange,
                                            onTxAntennaGainChange = onTxAntennaGainChange,
                                            onTxSensitivityChange = onTxSensitivityChange,
                                            onRxPowerChange = onRxPowerChange,
                                            onRxAntennaGainChange = onRxAntennaGainChange,
                                            onRxSensitivityChange = onRxSensitivityChange,
                                            onRelayPowerChange = onRelayPowerChange,
                                            onRelayAntennaGainChange = onRelayAntennaGainChange,
                                            onRelaySensitivityChange = onRelaySensitivityChange,
                                            onOpacityChange = onOpacityChange,
                                            onCalculateBridge = onCalculateBridge,
                                            onDismissError = onDismissError
                                        )
                                        if (uiState.currentResult != null) {
                                            BridgeScorecardSection(
                                                result = uiState.currentResult,
                                                selectedCandidateIndex = uiState.selectedCandidateIndex,
                                                onSelectCandidate = onSelectCandidate,
                                                isViewshedStale = uiState.isViewshedStale
                                            )
                                        } else {
                                            BridgeEmptyPrompt()
                                        }
                                    }

                                    BridgeDrawerTab.OBSTRUCTIONS -> {
                                        val result = uiState.currentResult
                                        val hasProfileSource = result != null ||
                                            uiState.liveDirectHop != null ||
                                            uiState.isLiveProfileLoading ||
                                            uiState.liveProfileError != null
                                        if (hasProfileSource) {
                                            BridgeObstructionsSection(
                                                result = result,
                                                selectedCandidateIndex = uiState.selectedCandidateIndex,
                                                selectedObstructionId = uiState.selectedObstructionId,
                                                txHeightMeters = uiState.txAntennaHeightMeters,
                                                rxHeightMeters = uiState.rxAntennaHeightMeters,
                                                relayHeightMeters = uiState.relayAntennaHeightMeters,
                                                onTxHeightChange = onTxHeightChange,
                                                onRxHeightChange = onRxHeightChange,
                                                onRelayHeightChange = onRelayHeightChange,
                                                onSelectCandidate = onSelectCandidate,
                                                onSelectObstruction = { obsId ->
                                                    onSelectObstruction(obsId)
                                                },
                                                liveDirectHop = uiState.liveDirectHop,
                                                isLiveProfileLoading = uiState.isLiveProfileLoading,
                                                liveProfileError = uiState.liveProfileError,
                                                profileMode = uiState.profileMode,
                                                scrubbedProfileSample = uiState.scrubbedProfileSample,
                                                onSelectProfileMode = onSelectProfileMode,
                                                onScrubProfileSample = onScrubProfileSample,
                                                onRetryLiveProfile = onRetryLiveProfile,
                                                relayPowerDbm = uiState.relayPowerDbm,
                                                relayAntennaGainDbi = uiState.relayAntennaGainDbi,
                                                relaySensitivityDbm = uiState.relaySensitivityDbm,
                                                onRelayPowerChange = onRelayPowerChange,
                                                onRelayAntennaGainChange = onRelayAntennaGainChange,
                                                onRelaySensitivityChange = onRelaySensitivityChange
                                            )
                                        } else {
                                            BridgeObstructionsEmptyPrompt(
                                                onSwitchToResults = { onSelectDrawerTab(BridgeDrawerTab.RESULTS) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showMapToolsSheet) {
        MapToolsBottomSheet(
            baseLayer = baseLayer,
            legendMode = MapToolsLegendMode.BRIDGE,
            sensitivityDbm = maxOf(
                uiState.txSensitivityDbm,
                uiState.rxSensitivityDbm,
                uiState.relaySensitivityDbm
            ).toDouble(),
            testTagPrefix = "bridge",
            onSelectBaseLayer = onSelectBaseLayer,
            onExport = { showExportDialog = true },
            hasClearableOverlay = uiState.currentResult != null && !uiState.isCalculating,
            onClearOverlay = onClearOverlay,
            hasResettablePins = uiState.hasAnyOverlayOrPins && !uiState.isCalculating,
            onResetPins = onResetAll,
            onDismiss = { showMapToolsSheet = false }
        )
    }

    inspectedMapNode?.let { tappedNode ->
        val isCurrentTx = uiState.txNode?.let { it.id == tappedNode.id && it.num == tappedNode.num } == true
        val isCurrentRx = uiState.rxNode?.let { it.id == tappedNode.id && it.num == tappedNode.num } == true
        RfNodeSiteActionSheet(
            node = tappedNode,
            isCurrentTx = isCurrentTx,
            isCurrentRx = isCurrentRx,
            onSetAsTx = {
                onSelectTxNode(tappedNode)
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
        filePrefix = "lemesh_bridge",
        mapViewProvider = { activeMapView },
        buildGeoJson = {
            MapExportEngine.buildBridgeGeoJson(
                uiState = uiState,
                baseLayerName = baseLayer.displayName
            )
        },
        buildSummary = {
            MapExportEngine.buildBridgeSummary(
                uiState = uiState,
                baseLayer = baseLayer
            )
        },
        buildKml = {
            MapExportEngine.buildBridgeKml(
                uiState = uiState,
                baseLayerName = baseLayer.displayName
            )
        },
        onDismiss = { showExportDialog = false }
    )
}

@Composable
internal fun BridgeDrawerHeader(
    uiState: BridgeUiState,
    controlsExpanded: Boolean,
    onCalculateBridge: () -> Unit,
    onOpenProfile: () -> Unit,
    onToggleExpanded: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "TX–RX Bridge Solver",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary
            )
            val topCand = uiState.currentResult?.topCandidates?.firstOrNull()
            val liveDirect = uiState.liveDirectHop
            Text(
                text = when {
                    topCand != null -> String.format(
                        Locale.US,
                        "#1 %+.1f dB margin • %.1f km² overlap",
                        topCand.worstLinkMarginDb,
                        uiState.currentResult.mutualOverlapAreaKm2
                    )
                    !uiState.areBothEndpointsSet -> "Place both TX and RX pins on the map to solve"
                    liveDirect != null -> String.format(
                        Locale.US,
                        "Direct %s (%+.1f m) • TX %dm / RX %dm",
                        when (liveDirect.status) {
                            ViewshedPointStatus.OBSTRUCTED -> "Blocked"
                            ViewshedPointStatus.GRAZING -> "Grazing"
                            ViewshedPointStatus.CLEAR -> "Clear"
                        },
                        liveDirect.worstClearanceMeters,
                        uiState.txAntennaHeightMeters.toInt(),
                        uiState.rxAntennaHeightMeters.toInt()
                    )
                    else ->
                        "TX ${uiState.txAntennaHeightMeters.toInt()}m • RX ${uiState.rxAntennaHeightMeters.toInt()}m • Relay ${uiState.relayAntennaHeightMeters.toInt()}m"
                },
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 10.sp,
                color = when {
                    topCand != null -> NodeDirectGreen
                    !uiState.areBothEndpointsSet -> NodeSelfAmber
                    liveDirect?.status == ViewshedPointStatus.OBSTRUCTED -> AlertErrorRed
                    liveDirect?.status == ViewshedPointStatus.GRAZING -> NodeSelfAmber
                    liveDirect?.status == ViewshedPointStatus.CLEAR -> NodeDirectGreen
                    else -> TextSecondary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (uiState.areBothEndpointsSet &&
                (uiState.liveDirectHop != null || uiState.currentResult != null || uiState.isLiveProfileLoading)
            ) {
                OutlinedButton(
                    onClick = onOpenProfile,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, NodeMultiHopSky.copy(alpha = 0.65f)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("bridge_open_profile_button")
                ) {
                    Text(
                        text = stringResource(R.string.bridge_tab_obstructions),
                        style = MaterialTheme.typography.labelLarge,
                        color = NodeMultiHopSky
                    )
                }
            }
            Button(
                onClick = onCalculateBridge,
                enabled = uiState.areBothEndpointsSet && !uiState.isCalculating,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NodeDirectGreen,
                    contentColor = TacticalCanvas
                ),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("compact_find_bridge_button")
            ) {
                Text(
                    text = "Solve",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            IconButton(
                onClick = onToggleExpanded,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("bridge_drawer_toggle")
            ) {
                Icon(
                    imageVector = if (controlsExpanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                    contentDescription = "Toggle bridge planner controls",
                    tint = TextSecondary
                )
            }
        }
    }
}

@Composable
internal fun BridgeStatusLine(uiState: BridgeUiState) {
    val activeTarget = uiState.effectivePlacementTarget
    val distKm = uiState.directDistanceKm
    val bearingDeg = uiState.directBearingDeg
    val statusText = if (distKm != null && bearingDeg != null) {
        val modeNote = if (uiState.placementMode == BridgePlacementMode.AUTO) {
            "Auto next: ${if (activeTarget == BridgePlacementTarget.TX) "TX" else "RX"}"
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
    } else {
        val txBadge = if (uiState.hasTxSet) "TX Set" else "TX Unset"
        val rxBadge = if (uiState.hasRxSet) "RX Set" else "RX Unset"
        "$txBadge • $rxBadge • Tap map or Drop ${if (activeTarget == BridgePlacementTarget.TX) "TX" else "RX"}"
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
internal fun BridgeControlsContent(
    uiState: BridgeUiState,
    plottedNodes: List<MeshNode>,
    onSelectTxNode: (MeshNode) -> Unit,
    onSelectRxNode: (MeshNode) -> Unit,
    onTxHeightChange: (Float) -> Unit,
    onRxHeightChange: (Float) -> Unit,
    onRelayHeightChange: (Float) -> Unit,
    onRadiusKmChange: (Int) -> Unit,
    onFrequencyChange: (String) -> Unit,
    onTxPowerChange: (Float) -> Unit,
    onTxAntennaGainChange: (Float) -> Unit,
    onTxSensitivityChange: (Float) -> Unit,
    onRxPowerChange: (Float) -> Unit,
    onRxAntennaGainChange: (Float) -> Unit,
    onRxSensitivityChange: (Float) -> Unit,
    onRelayPowerChange: (Float) -> Unit,
    onRelayAntennaGainChange: (Float) -> Unit,
    onRelaySensitivityChange: (Float) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onCalculateBridge: () -> Unit,
    onDismissError: () -> Unit,
    onSelectFadeMarginPreset: ((FadeMarginPreset) -> Unit)? = null
) {
    RfEndpointCard(
        role = "TX",
        label = uiState.txLabel,
        lat = uiState.txLat,
        lon = uiState.txLon,
        groundElevationMeters = uiState.txGroundElevationMeters,
        isElevLoading = uiState.isTxElevLoading,
        accentColor = NodeSelfAmber,
        plottedNodes = plottedNodes,
        pickNodeTestTag = "bridge_pick_tx_node",
        onSelectNode = onSelectTxNode,
        extraContent = {
            BridgeEndpointRadioAccordion(
                roleLabel = "TX",
                accentColor = NodeSelfAmber,
                powerDbm = uiState.txPowerDbm,
                antennaGainDbi = uiState.txAntennaGainDbi,
                sensitivityDbm = uiState.txSensitivityDbm,
                tagPrefix = "bridge_tx",
                onPowerChange = onTxPowerChange,
                onAntennaGainChange = onTxAntennaGainChange,
                onSensitivityChange = onTxSensitivityChange
            )
        }
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
        pickNodeTestTag = "bridge_pick_rx_node",
        onSelectNode = onSelectRxNode,
        extraContent = {
            BridgeEndpointRadioAccordion(
                roleLabel = "RX",
                accentColor = NodeMultiHopSky,
                powerDbm = uiState.rxPowerDbm,
                antennaGainDbi = uiState.rxAntennaGainDbi,
                sensitivityDbm = uiState.rxSensitivityDbm,
                tagPrefix = "bridge_rx",
                onPowerChange = onRxPowerChange,
                onAntennaGainChange = onRxAntennaGainChange,
                onSensitivityChange = onRxSensitivityChange
            )
        }
    )

    RfLabeledSlider(
        label = "TX Mast AGL",
        valueText = "${uiState.txAntennaHeightMeters.roundToInt()} m",
        value = uiState.txAntennaHeightMeters,
        valueRange = 1f..60f,
        accentColor = NodeSelfAmber,
        sliderTestTag = "bridge_tx_mast_slider",
        onValueChange = onTxHeightChange
    )
    RfLabeledSlider(
        label = "RX Mast AGL",
        valueText = "${uiState.rxAntennaHeightMeters.roundToInt()} m",
        value = uiState.rxAntennaHeightMeters,
        valueRange = 1f..60f,
        accentColor = NodeMultiHopSky,
        sliderTestTag = "bridge_rx_mast_slider",
        onValueChange = onRxHeightChange
    )

    BridgeRelayRadioCard(
        relayHeightMeters = uiState.relayAntennaHeightMeters,
        relayPowerDbm = uiState.relayPowerDbm,
        relayAntennaGainDbi = uiState.relayAntennaGainDbi,
        relaySensitivityDbm = uiState.relaySensitivityDbm,
        tagPrefix = "bridge_relay",
        onRelayHeightChange = onRelayHeightChange,
        onRelayPowerChange = onRelayPowerChange,
        onRelayAntennaGainChange = onRelayAntennaGainChange,
        onRelaySensitivityChange = onRelaySensitivityChange
    )

    RfRadioParamsSection(
        radiusLabel = "Viewshed Radius per Endpoint",
        radiusKm = uiState.radiusKm,
        radiusTagPrefix = "bridge_radius_",
        selectedRadiusColor = NodeDirectGreen,
        frequencyText = uiState.frequencyText,
        txPowerDbm = uiState.txPowerDbm,
        antennaGainDbi = uiState.txAntennaGainDbi,
        sensitivityDbm = uiState.txSensitivityDbm,
        opacityLabel = "Overlay Opacity",
        overlayOpacity = uiState.overlayOpacity,
        errorMessage = null,
        onRadiusKmChange = onRadiusKmChange,
        onFrequencyChange = onFrequencyChange,
        onTxPowerChange = {},
        onAntennaGainChange = {},
        onSensitivityChange = {},
        onOpacityChange = onOpacityChange,
        onDismissError = onDismissError,
        showPerNodeRadioSliders = false
    )

    if (onSelectFadeMarginPreset != null) {
        PropagationFadeMarginStrip(
            selectedPreset = uiState.fadeMarginPreset,
            onSelectPreset = onSelectFadeMarginPreset,
            testTagPrefix = "bridge"
        )
    }

    Button(
        onClick = onCalculateBridge,
        enabled = uiState.areBothEndpointsSet && !uiState.isCalculating,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = NodeDirectGreen,
            contentColor = TacticalCanvas
        ),
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .testTag("find_best_bridge_button")
    ) {
        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "Find Best Bridge Location",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
internal fun BridgeDrawerTabBar(
    activeTab: BridgeDrawerTab,
    obstructionCount: Int,
    onSelectTab: (BridgeDrawerTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(TacticalCanvas)
            .border(1.dp, TacticalOutline, RoundedCornerShape(10.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        BridgeDrawerTab.entries.forEach { tab ->
            val selected = tab == activeTab
            val label = when (tab) {
                BridgeDrawerTab.RESULTS -> stringResource(R.string.bridge_tab_results)
                BridgeDrawerTab.OBSTRUCTIONS -> if (obstructionCount > 0) {
                    "${stringResource(R.string.bridge_tab_obstructions)} ($obstructionCount)"
                } else {
                    stringResource(R.string.bridge_tab_obstructions)
                }
            }
            val accent = if (tab == BridgeDrawerTab.OBSTRUCTIONS && obstructionCount > 0) {
                NodeSelfAmber
            } else {
                NodeDirectGreen
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) accent.copy(alpha = 0.18f) else Color.Transparent)
                    .border(
                        width = if (selected) 1.dp else 0.dp,
                        color = if (selected) accent.copy(alpha = 0.75f) else Color.Transparent,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable { onSelectTab(tab) }
                    .testTag(
                        if (tab == BridgeDrawerTab.RESULTS) "bridge_tab_results" else "bridge_tab_obstructions"
                    )
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) accent else TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun BridgeEmptyPrompt() {
    Surface(
        color = TacticalSurfaceElevated,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, TacticalOutline, RoundedCornerShape(12.dp))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Ready to Bridge TX ↔ RX",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary
            )
            Text(
                text = "Tap the map or Drop at crosshair to alternate TX & RX, drag either pin directly, adjust mast heights, then tap Find Best Bridge Location.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )
        }
    }
}

@Composable
internal fun BridgeObstructionsEmptyPrompt(
    onSwitchToResults: () -> Unit
) {
    Surface(
        color = TacticalSurfaceElevated,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, TacticalOutline, RoundedCornerShape(12.dp))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.bridge_obstructions_empty_title),
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary
            )
            Text(
                text = stringResource(R.string.bridge_obstructions_empty_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )
            OutlinedButton(
                onClick = onSwitchToResults,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Text(
                    text = stringResource(R.string.bridge_tab_results),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

internal fun BridgePlacementMode.toRfChoice(): RfPlacementChoice = when (this) {
    BridgePlacementMode.AUTO -> RfPlacementChoice.AUTO
    BridgePlacementMode.TX -> RfPlacementChoice.TX
    BridgePlacementMode.RX -> RfPlacementChoice.RX
}

internal fun RfPlacementChoice.toBridgeMode(): BridgePlacementMode = when (this) {
    RfPlacementChoice.AUTO -> BridgePlacementMode.AUTO
    RfPlacementChoice.TX -> BridgePlacementMode.TX
    RfPlacementChoice.RX -> BridgePlacementMode.RX
}
