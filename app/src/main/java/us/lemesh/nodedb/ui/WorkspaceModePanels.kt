package us.lemesh.nodedb.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.osmdroid.views.MapView
import us.lemesh.nodedb.R
import us.lemesh.nodedb.data.BridgeObstructionItem
import us.lemesh.nodedb.data.BridgeProfileMode
import us.lemesh.nodedb.data.BridgeProfileSample
import us.lemesh.nodedb.data.FadeMarginPreset
import us.lemesh.nodedb.data.MeshNode
import us.lemesh.nodedb.data.RelayCandidate
import us.lemesh.nodedb.data.ViewshedPointStatus
import us.lemesh.nodedb.ui.theme.JetBrainsMonoFontFamily
import us.lemesh.nodedb.ui.theme.NodeDirectGreen
import us.lemesh.nodedb.ui.theme.NodeFavoritePurple
import us.lemesh.nodedb.ui.theme.NodeMqttSlate
import us.lemesh.nodedb.ui.theme.NodeMultiHopSky
import us.lemesh.nodedb.ui.theme.NodeSelfAmber
import us.lemesh.nodedb.ui.theme.TacticalCanvas
import us.lemesh.nodedb.ui.theme.TacticalOutline
import us.lemesh.nodedb.ui.theme.TacticalSurface
import us.lemesh.nodedb.ui.theme.TacticalSurfaceElevated
import us.lemesh.nodedb.ui.theme.TextPrimary
import us.lemesh.nodedb.ui.theme.TextSecondary
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MeshModeSidePanel(
    uiState: NodeMapUiState,
    activeMapView: MapView?,
    savedLayersBadgeCount: Int,
    onLoadJsonClick: () -> Unit,
    onSelectNode: (MeshNode?) -> Unit,
    onOpenFilters: () -> Unit,
    onOpenSavedLayers: () -> Unit,
    onOpenDirectLinksSheet: () -> Unit,
    onOpenNoGpsSheet: () -> Unit,
    onFitBounds: () -> Unit,
    onToggleAddNodeMode: () -> Unit,
    onOpenNewNodeEditor: (Double?, Double?) -> Unit,
    onOpenEditNodeEditor: (MeshNode) -> Unit,
    onAssignViewshedTx: (MeshNode) -> Unit,
    onAssignViewshedRx: (MeshNode) -> Unit,
    onAssignBridgeTx: (MeshNode) -> Unit,
    onAssignBridgeRx: (MeshNode) -> Unit,
    onInspectFresnelSightline: (MeshNode) -> Unit,
    onToggleMeasureMode: () -> Unit,
    onClearMeasureAnchors: () -> Unit,
    onUseSelfAsAnchorA: () -> Unit,
    onAnalyzeLosBetweenNodes: (MeshNode, MeshNode) -> Unit,
    onToggleLosMode: () -> Unit,
    onToggleLosDrawerExpanded: () -> Unit,
    onLosTxHeightChange: (String) -> Unit,
    onLosRxHeightChange: (String) -> Unit,
    onLosFrequencyChange: (String) -> Unit,
    onUseSelfAsLosPointA: () -> Unit,
    onSwapLosEndpoints: () -> Unit,
    onClearLosEndpoints: () -> Unit,
    onRetryLosElevation: () -> Unit,
    onUseFlatTerrainFallback: () -> Unit,
    onLosTxPowerChange: (String) -> Unit,
    onLosTxGainChange: (String) -> Unit,
    onLosRxGainChange: (String) -> Unit,
    onLosSensitivityChange: (String) -> Unit,
    onApplyLosMastFix: () -> Unit,
    onOpenOfflineMaps: (() -> Unit)? = null,
    onSelectFadeMarginPreset: ((FadeMarginPreset) -> Unit)? = null,
    onAlignNode: ((MeshNode) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val selected = uiState.selectedNode
    if (selected != null) {
        NodeInspectorContent(
            node = selected,
            selfNode = uiState.selfNode,
            onDismiss = { onSelectNode(null) },
            onAssignViewshedTx = onAssignViewshedTx,
            onAssignViewshedRx = onAssignViewshedRx,
            onAssignBridgeTx = onAssignBridgeTx,
            onAssignBridgeRx = onAssignBridgeRx,
            onInspectFresnelSightline = if (uiState.selfNode?.hasValidGps == true && !selected.isSelf && selected.hasValidGps) {
                onInspectFresnelSightline
            } else null,
            onEditNode = onOpenEditNodeEditor,
            onAlignAntenna = onAlignNode,
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(16.dp)
                .testTag("mesh_side_inspector")
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when {
            uiState.isAddNodeMode -> {
                AddNodeModeBanner(
                    isPickingForExistingDraft = uiState.editingDraft?.isPickingCoordinateOnMap == true,
                    onUseMapCenter = {
                        val center = activeMapView?.mapCenter
                        onOpenNewNodeEditor(center?.latitude, center?.longitude)
                    },
                    onOpenManualForm = { onOpenNewNodeEditor(null, null) },
                    onDone = onToggleAddNodeMode
                )
            }
            uiState.isLosMode -> {
                LosProfileDrawer(
                    pointA = uiState.losPointA,
                    pointB = uiState.losPointB,
                    selfNode = uiState.selfNode,
                    txHeightText = uiState.losTxHeightText,
                    rxHeightText = uiState.losRxHeightText,
                    frequencyText = uiState.losFrequencyText,
                    result = uiState.losResult,
                    isLoading = uiState.isLosLoading,
                    errorMessage = uiState.losErrorMessage,
                    isExpanded = uiState.isLosDrawerExpanded,
                    onToggleExpanded = onToggleLosDrawerExpanded,
                    onTxHeightChange = onLosTxHeightChange,
                    onRxHeightChange = onLosRxHeightChange,
                    onFrequencyChange = onLosFrequencyChange,
                    onUseSelfAsPointA = onUseSelfAsLosPointA,
                    onSwapEndpoints = onSwapLosEndpoints,
                    onClearEndpoints = onClearLosEndpoints,
                    onRetryElevation = onRetryLosElevation,
                    onUseFlatFallback = onUseFlatTerrainFallback,
                    onDone = onToggleLosMode,
                    txPowerText = uiState.losTxPowerText,
                    txGainText = uiState.losTxGainText,
                    rxGainText = uiState.losRxGainText,
                    sensitivityText = uiState.losSensitivityText,
                    onTxPowerChange = onLosTxPowerChange,
                    onTxGainChange = onLosTxGainChange,
                    onRxGainChange = onLosRxGainChange,
                    onSensitivityChange = onLosSensitivityChange,
                    onApplyMastFix = onApplyLosMastFix,
                    isOfflineTileMissing = uiState.isLosOfflineTileMissing,
                    onOpenOfflineMaps = onOpenOfflineMaps,
                    observedDiagnostic = uiState.losObservedDiagnostic,
                    fadeMarginPreset = uiState.fadeMarginPreset,
                    onSelectFadeMarginPreset = onSelectFadeMarginPreset
                )
            }
            uiState.isMeasureMode -> {
                RfLinkRulerBar(
                    anchorA = uiState.measureAnchorA,
                    anchorB = uiState.measureAnchorB,
                    selfNode = uiState.selfNode,
                    onUseSelfAsAnchorA = onUseSelfAsAnchorA,
                    onInspectAnchorB = { node -> onSelectNode(node) },
                    onAnalyzeLos = {
                        val a = uiState.measureAnchorA
                        val b = uiState.measureAnchorB
                        if (a != null && b != null) onAnalyzeLosBetweenNodes(a, b)
                    },
                    onOpenDirectLinksSheet = onOpenDirectLinksSheet,
                    onClear = onClearMeasureAnchors,
                    onDone = onToggleMeasureMode
                )
            }
        }

        Surface(
            color = TacticalSurfaceElevated,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, TacticalOutline, RoundedCornerShape(14.dp))
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.filters_dataset_section),
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary
                )
                Text(
                    text = uiState.loadedFileName ?: "Standby • Tap Load JSON or Add Node",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (uiState.loadedFileName != null) NodeDirectGreen else TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SideStatPill("Total", uiState.totalCount, TextPrimary)
                    SideStatPill("GPS", uiState.plottedGpsCount, NodeDirectGreen)
                    SideStatPill("RF", uiState.rfCount, NodeMultiHopSky)
                    SideStatPill("MQTT", uiState.mqttCount, NodeMqttSlate)
                    SideStatPill("No-GPS", uiState.noGpsCount, NodeSelfAmber)
                    if (uiState.manualNodeCount > 0) {
                        SideStatPill("Manual", uiState.manualNodeCount, NodeFavoritePurple)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onLoadJsonClick,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NodeDirectGreen,
                            contentColor = TacticalCanvas
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_load_json), fontWeight = FontWeight.Bold)
                    }
                    if (uiState.plottedGpsCount > 0) {
                        OutlinedButton(
                            onClick = onFitBounds,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, TacticalOutline),
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.action_fit_bounds))
                        }
                    }
                }
            }
        }

        Surface(
            color = TacticalSurfaceElevated,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, TacticalOutline, RoundedCornerShape(14.dp))
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.filters_field_tools_section),
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary
                )
                SideActionButton(Icons.Default.Tune, stringResource(R.string.map_filters_tools_title), null, NodeMultiHopSky, onOpenFilters)
                SideActionButton(Icons.Default.Layers, stringResource(R.string.saved_layers_title), savedLayersBadgeCount.takeIf { it > 0 }?.toString(), NodeDirectGreen, onOpenSavedLayers)
                SideActionButton(Icons.Default.AddLocationAlt, stringResource(R.string.action_add_node), if (uiState.isAddNodeMode) "ACTIVE" else null, NodeDirectGreen, onToggleAddNodeMode)
                SideActionButton(Icons.Default.Terrain, stringResource(R.string.filter_los_profile), if (uiState.isLosMode) "ACTIVE" else null, NodeMultiHopSky, onToggleLosMode)
                SideActionButton(Icons.Default.Straighten, stringResource(R.string.filter_measure_rf), if (uiState.isMeasureMode) "ACTIVE" else null, NodeSelfAmber, onToggleMeasureMode)
                HorizontalDivider(color = TacticalOutline.copy(alpha = 0.6f))
                SideActionButton(Icons.Default.Hub, stringResource(R.string.filter_direct_links), uiState.directRfLinksWithGps.size.toString(), NodeDirectGreen, onOpenDirectLinksSheet)
                SideActionButton(Icons.Default.GpsOff, stringResource(R.string.filter_no_gps_list), uiState.noGpsCount.toString(), NodeSelfAmber, onOpenNoGpsSheet)
            }
        }
    }
}

@Composable
internal fun ViewshedModeSidePanel(
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
    onApplyMastFix: (Float, Float) -> Unit,
    onInspectObstructedNode: (MeshNode) -> Unit,
    onDismissError: () -> Unit,
    onSelectFadeMarginPreset: ((FadeMarginPreset) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    var rxCardOffsetY by remember { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .statusBarsPadding()
            .padding(14.dp)
            .testTag("viewshed_side_panel"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ViewshedDrawerHeader(
            uiState = uiState,
            controlsExpanded = true,
            onCalculateCoverage = onCalculateCoverage,
            onToggleExpanded = {}
        )
        HorizontalDivider(color = TacticalOutline.copy(alpha = 0.6f))
        ViewshedControlsContent(
            uiState = uiState,
            plottedNodes = plottedNodes,
            onSelectTxNode = onSelectTxNode,
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
            onOpenSaveLayerDialog = onOpenSaveLayerDialog,
            onTogglePinSnapshot = onTogglePinSnapshot,
            onToggleShowSnapshotOnMap = onToggleShowSnapshotOnMap,
            onSendToBridge = onSendToBridge,
            onDismissError = onDismissError,
            onSelectFadeMarginPreset = onSelectFadeMarginPreset
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
                    rxCardOffsetY = coords.positionInParent().y.roundToInt()
                }
            )
        }
        uiState.currentResult?.let { result ->
            ViewshedScorecardSection(
                result = result,
                pinnedSnapshot = uiState.pinnedSnapshot,
                onInspectObstructedNode = { obstructed ->
                    onInspectObstructedNode(obstructed.node)
                    coroutineScope.launch { scrollState.animateScrollTo(rxCardOffsetY) }
                }
            )
        }
    }
}

@Composable
internal fun BridgeModeSidePanel(
    uiState: BridgeUiState,
    plottedNodes: List<MeshNode>,
    obstructions: List<BridgeObstructionItem>,
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
    onSelectCandidate: (Int) -> Unit,
    onSelectDrawerTab: (BridgeDrawerTab) -> Unit,
    onSelectObstruction: (String?) -> Unit,
    onSelectProfileMode: (BridgeProfileMode) -> Unit,
    onScrubProfileSample: (BridgeProfileSample?) -> Unit,
    onRetryLiveProfile: () -> Unit,
    onDismissError: () -> Unit,
    onOpenOfflineMaps: (() -> Unit)? = null,
    onSelectFadeMarginPreset: ((FadeMarginPreset) -> Unit)? = null,
    onAlignCandidate: ((RelayCandidate) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val obstructionBadgeCount = remember(obstructions) {
        obstructions.count { it.point.status != ViewshedPointStatus.CLEAR }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(14.dp)
            .testTag("bridge_side_panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BridgeDrawerHeader(
            uiState = uiState,
            controlsExpanded = true,
            onCalculateBridge = onCalculateBridge,
            onOpenProfile = { onSelectDrawerTab(BridgeDrawerTab.OBSTRUCTIONS) },
            onToggleExpanded = {}
        )
        BridgeDrawerTabBar(
            activeTab = uiState.activeDrawerTab,
            obstructionCount = obstructionBadgeCount,
            onSelectTab = onSelectDrawerTab
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
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
                        onDismissError = onDismissError,
                        onSelectFadeMarginPreset = onSelectFadeMarginPreset
                    )
                    if (uiState.currentResult != null) {
                        BridgeScorecardSection(
                            result = uiState.currentResult,
                            selectedCandidateIndex = uiState.selectedCandidateIndex,
                            onSelectCandidate = onSelectCandidate,
                            isViewshedStale = uiState.isViewshedStale,
                            onAlignCandidate = onAlignCandidate
                        )
                    } else {
                        BridgeEmptyPrompt()
                    }
                }
                BridgeDrawerTab.OBSTRUCTIONS -> {
                    val hasProfile = uiState.currentResult != null ||
                        uiState.liveDirectHop != null ||
                        uiState.isLiveProfileLoading ||
                        uiState.liveProfileError != null
                    if (hasProfile) {
                        BridgeObstructionsSection(
                            result = uiState.currentResult,
                            selectedCandidateIndex = uiState.selectedCandidateIndex,
                            selectedObstructionId = uiState.selectedObstructionId,
                            txHeightMeters = uiState.txAntennaHeightMeters,
                            rxHeightMeters = uiState.rxAntennaHeightMeters,
                            relayHeightMeters = uiState.relayAntennaHeightMeters,
                            onTxHeightChange = onTxHeightChange,
                            onRxHeightChange = onRxHeightChange,
                            onRelayHeightChange = onRelayHeightChange,
                            onSelectCandidate = onSelectCandidate,
                            onSelectObstruction = onSelectObstruction,
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
                            onRelaySensitivityChange = onRelaySensitivityChange,
                            isLiveProfileOfflineMissing = uiState.isLiveProfileOfflineMissingError,
                            onOpenOfflineMaps = onOpenOfflineMaps
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

@Composable
private fun SideStatPill(label: String, count: Int, color: Color) {
    Row(
        modifier = Modifier
            .background(TacticalSurface, RoundedCornerShape(8.dp))
            .border(1.dp, TacticalOutline.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(color, RoundedCornerShape(3.dp))
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = "$label $count",
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 11.sp,
            color = TextPrimary
        )
    }
}

@Composable
private fun SideActionButton(
    icon: ImageVector,
    label: String,
    badge: String?,
    accent: Color,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, TacticalOutline),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (badge != null) {
            Text(
                text = badge,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = accent
            )
        }
    }
}
