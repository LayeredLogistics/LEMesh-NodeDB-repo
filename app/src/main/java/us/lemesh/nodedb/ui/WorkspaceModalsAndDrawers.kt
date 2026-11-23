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
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.osmdroid.views.MapView
import us.lemesh.nodedb.R
import us.lemesh.nodedb.data.BridgeObstructionItem
import us.lemesh.nodedb.data.BridgeProfileMode
import us.lemesh.nodedb.data.BridgeProfileSample
import us.lemesh.nodedb.data.FadeMarginPreset
import us.lemesh.nodedb.data.GeoJsonReportExporter
import us.lemesh.nodedb.data.LayerRenderMode
import us.lemesh.nodedb.data.LiveSyncStatus
import us.lemesh.nodedb.data.MapExportEngine
import us.lemesh.nodedb.data.MeshNode
import us.lemesh.nodedb.data.OfflineDemPreset
import us.lemesh.nodedb.data.OfflineDemUiState
import us.lemesh.nodedb.data.RelayCandidate
import us.lemesh.nodedb.data.ViewshedPointStatus
import us.lemesh.nodedb.ui.state.MapViewportState
import us.lemesh.nodedb.ui.state.OverlayContinuityMode
import us.lemesh.nodedb.ui.state.WorkspaceMode
import us.lemesh.nodedb.ui.theme.AlertErrorRed
import us.lemesh.nodedb.ui.theme.JetBrainsMonoFontFamily
import us.lemesh.nodedb.ui.theme.NodeDirectGreen
import us.lemesh.nodedb.ui.theme.NodeMultiHopSky
import us.lemesh.nodedb.ui.theme.NodeSelfAmber
import us.lemesh.nodedb.ui.theme.TacticalCanvas
import us.lemesh.nodedb.ui.theme.TacticalOutline
import us.lemesh.nodedb.ui.theme.TacticalSurface
import us.lemesh.nodedb.ui.theme.TacticalSurfaceElevated
import us.lemesh.nodedb.ui.theme.TextPrimary
import us.lemesh.nodedb.ui.theme.TextSecondary
import kotlin.math.roundToInt

@Composable
internal fun WorkspaceTopFloatingOverlay(
    activeMode: WorkspaceMode,
    meshState: NodeMapUiState,
    viewshedState: ViewshedUiState,
    bridgeState: BridgeUiState,
    activeFilterCount: Int,
    layersBadgeCount: Int,
    hasFilteredSubset: Boolean,
    selectedBridgeObstruction: BridgeObstructionItem?,
    activeBridgeCandidateRank: Int?,
    onSearchQueryChange: (String) -> Unit,
    onOpenFilters: () -> Unit,
    onOpenLayers: () -> Unit,
    onLoadJsonClick: () -> Unit,
    onToggleAddNodeMode: () -> Unit,
    onResetAllFilters: () -> Unit,
    onCancelAggregateCompute: () -> Unit,
    onDismissMeshError: () -> Unit,
    onDismissAggregateError: () -> Unit,
    onSetViewshedPlacementMode: (ViewshedPlacementMode) -> Unit,
    onDropViewshedAtCenter: () -> Unit,
    onSwapViewshedTxRx: () -> Unit,
    onDismissViewshedError: () -> Unit,
    onSetBridgePlacementMode: (BridgePlacementMode) -> Unit,
    onDropBridgeAtCenter: () -> Unit,
    onSwapBridgeTxRx: () -> Unit,
    onDismissBridgeError: () -> Unit,
    onDismissBridgeObstructionCallout: () -> Unit,
    offlineDemState: OfflineDemUiState = OfflineDemUiState(),
    onOpenOfflineMaps: (() -> Unit)? = null,
    liveSyncStatus: LiveSyncStatus = LiveSyncStatus.OFFLINE,
    onOpenLiveSync: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        when (activeMode) {
            WorkspaceMode.MESH -> {
                CompactNodeMapSearchBar(
                    searchQuery = meshState.searchQuery,
                    activeFilterCount = activeFilterCount,
                    savedLayersActiveCount = layersBadgeCount,
                    onSearchQueryChange = onSearchQueryChange,
                    onOpenFilters = onOpenFilters,
                    onOpenLayers = onOpenLayers,
                    isOfflineOnlyMode = offlineDemState.isOfflineOnlyMode,
                    isOfflineDownloading = offlineDemState.activeDownload != null,
                    onOpenOfflineMaps = onOpenOfflineMaps
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onOpenLiveSync != null) {
                        LiveSyncToolbarBadge(
                            status = liveSyncStatus,
                            onClick = onOpenLiveSync
                        )
                    }
                    if (!meshState.hasLoadedFile && !meshState.isLoading) {
                        Button(
                            onClick = onLoadJsonClick,
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NodeDirectGreen,
                                contentColor = TacticalCanvas
                            ),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("load_json_button")
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.action_load_json),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (!meshState.isAddNodeMode) {
                            OutlinedButton(
                                onClick = onToggleAddNodeMode,
                                shape = RoundedCornerShape(20.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                border = BorderStroke(1.dp, NodeMultiHopSky.copy(alpha = 0.7f)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = TacticalSurface.copy(alpha = 0.92f),
                                    contentColor = NodeMultiHopSky
                                ),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("standby_add_node_button")
                            ) {
                                Icon(Icons.Default.AddLocationAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.action_add_node),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    if (hasFilteredSubset) {
                        Surface(
                            onClick = onResetAllFilters,
                            color = TacticalSurface.copy(alpha = 0.92f),
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(1.dp, NodeMultiHopSky.copy(alpha = 0.5f)),
                            modifier = Modifier.heightIn(min = 36.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "${meshState.filteredMapNodes.size}/${meshState.plottedGpsCount} GPS",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NodeMultiHopSky
                                )
                                Text("• Reset", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = meshState.isAggregateCoverageLoading) {
                    Surface(
                        color = TacticalSurface.copy(alpha = 0.94f),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, NodeDirectGreen.copy(alpha = 0.6f)),
                        modifier = Modifier.testTag("aggregate_progress_pill")
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(Modifier.size(15.dp), color = NodeDirectGreen, strokeWidth = 2.dp)
                            Text(
                                text = "${stringResource(R.string.aggregate_coverage_computing)} ${meshState.aggregateCoverageProgressDone}/${meshState.aggregateCoverageProgressTotal}",
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 11.sp,
                                color = TextPrimary
                            )
                            TextButton(
                                onClick = onCancelAggregateCompute,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("aggregate_progress_cancel_button")
                            ) {
                                Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = meshState.isSightlinesSolving) {
                    Surface(
                        color = TacticalSurface.copy(alpha = 0.94f),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, NodeMultiHopSky.copy(alpha = 0.6f)),
                        modifier = Modifier.testTag("fresnel_sightlines_progress_pill")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(Modifier.size(14.dp), color = NodeMultiHopSky, strokeWidth = 2.dp)
                            Text(
                                text = stringResource(R.string.fresnel_sightlines_solving, meshState.sightlinesSolvedCount, meshState.sightlinesTotalCount),
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 11.sp,
                                color = TextPrimary
                            )
                        }
                    }
                }

                AnimatedVisibility(visible = meshState.errorMessage != null) {
                    ErrorBanner(message = meshState.errorMessage.orEmpty(), onDismiss = onDismissMeshError)
                }
                AnimatedVisibility(visible = meshState.aggregateCoverageError != null) {
                    ErrorBanner(
                        message = meshState.aggregateCoverageError.orEmpty(),
                        onDismiss = onDismissAggregateError,
                        actionLabel = if (meshState.isAggregateOfflineTileMissing) {
                            stringResource(R.string.offline_missing_cta)
                        } else null,
                        onActionClick = onOpenOfflineMaps
                    )
                }
            }

            WorkspaceMode.VIEWSHED -> {
                val enabledLayersCount = viewshedState.savedLayers.count { it.isEnabled }
                val isTxTarget = viewshedState.effectivePlacementTarget == ViewshedPlacementTarget.TX
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
                            placementChoice = viewshedState.placementMode.toRfChoice(),
                            isTxTarget = isTxTarget,
                            hasSwappableEndpoints = viewshedState.hasTxSet || viewshedState.hasRxSet,
                            testTagPrefix = "viewshed",
                            onSelectMode = { onSetViewshedPlacementMode(it.toViewshedMode()) },
                            onDropAtCenter = onDropViewshedAtCenter,
                            onSwapTxRx = onSwapViewshedTxRx,
                            trailingContent = {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        onClick = onOpenLayers,
                                        color = if (enabledLayersCount > 0) NodeDirectGreen.copy(alpha = 0.16f) else TacticalSurfaceElevated,
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, if (enabledLayersCount > 0) NodeDirectGreen.copy(alpha = 0.7f) else TacticalOutline),
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
                                    if (onOpenOfflineMaps != null) {
                                        OfflineMapsToolbarIconButton(
                                            isOfflineOnlyMode = offlineDemState.isOfflineOnlyMode,
                                            isDownloading = offlineDemState.activeDownload != null,
                                            onClick = onOpenOfflineMaps
                                        )
                                    }
                                }
                            }
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                SiteCoordinatesStrip(uiState = viewshedState)
                            }
                            if (onOpenLiveSync != null) {
                                Spacer(Modifier.width(6.dp))
                                LiveSyncToolbarBadge(
                                    status = liveSyncStatus,
                                    onClick = onOpenLiveSync
                                )
                            }
                        }
                    }
                }
                AnimatedVisibility(visible = viewshedState.errorMessage != null) {
                    RfFloatingErrorBanner(
                        message = viewshedState.errorMessage.orEmpty(),
                        onDismiss = onDismissViewshedError,
                        actionLabel = if (viewshedState.isOfflineTileMissingError) {
                            stringResource(R.string.offline_missing_cta)
                        } else null,
                        onActionClick = onOpenOfflineMaps
                    )
                }
            }

            WorkspaceMode.BRIDGE -> {
                val isTxTarget = bridgeState.effectivePlacementTarget == BridgePlacementTarget.TX
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
                            placementChoice = bridgeState.placementMode.toRfChoice(),
                            isTxTarget = isTxTarget,
                            hasSwappableEndpoints = bridgeState.hasTxSet || bridgeState.hasRxSet,
                            testTagPrefix = "bridge",
                            onSelectMode = { onSetBridgePlacementMode(it.toBridgeMode()) },
                            onDropAtCenter = onDropBridgeAtCenter,
                            onSwapTxRx = onSwapBridgeTxRx,
                            trailingContent = if (onOpenOfflineMaps != null) {
                                {
                                    OfflineMapsToolbarIconButton(
                                        isOfflineOnlyMode = offlineDemState.isOfflineOnlyMode,
                                        isDownloading = offlineDemState.activeDownload != null,
                                        onClick = onOpenOfflineMaps
                                    )
                                }
                            } else null
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                BridgeStatusLine(uiState = bridgeState)
                            }
                            if (onOpenLiveSync != null) {
                                Spacer(Modifier.width(6.dp))
                                LiveSyncToolbarBadge(
                                    status = liveSyncStatus,
                                    onClick = onOpenLiveSync
                                )
                            }
                        }
                    }
                }
                AnimatedVisibility(visible = bridgeState.errorMessage != null) {
                    RfFloatingErrorBanner(
                        message = bridgeState.errorMessage.orEmpty(),
                        onDismiss = onDismissBridgeError,
                        actionLabel = if (bridgeState.isOfflineTileMissingError) {
                            stringResource(R.string.offline_missing_cta)
                        } else null,
                        onActionClick = onOpenOfflineMaps
                    )
                }
                AnimatedVisibility(visible = selectedBridgeObstruction != null) {
                    selectedBridgeObstruction?.let { obs ->
                        BridgeSelectedObstructionCallout(
                            item = obs,
                            relayRank = activeBridgeCandidateRank,
                            onDismiss = onDismissBridgeObstructionCallout
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineMapsToolbarIconButton(
    isOfflineOnlyMode: Boolean,
    isDownloading: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = when {
            isOfflineOnlyMode -> NodeSelfAmber.copy(alpha = 0.18f)
            isDownloading -> NodeDirectGreen.copy(alpha = 0.18f)
            else -> TacticalSurfaceElevated
        },
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            1.dp,
            when {
                isOfflineOnlyMode -> NodeSelfAmber.copy(alpha = 0.75f)
                isDownloading -> NodeDirectGreen.copy(alpha = 0.75f)
                else -> TacticalOutline
            }
        ),
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag("offline_maps_toolbar_button")
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isOfflineOnlyMode) Icons.Default.CloudOff else Icons.Default.CloudDownload,
                contentDescription = stringResource(R.string.action_offline_maps),
                tint = when {
                    isOfflineOnlyMode -> NodeSelfAmber
                    isDownloading -> NodeDirectGreen
                    else -> TextSecondary
                },
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
internal fun CompactViewshedBottomDrawer(
    uiState: ViewshedUiState,
    plottedNodes: List<MeshNode>,
    controlsExpanded: Boolean,
    onToggleExpanded: () -> Unit,
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
    onSelectFadeMarginPreset: ((FadeMarginPreset) -> Unit)? = null
) {
    val drawerScrollState = rememberScrollState()
    var rxLinkCardOffsetY by remember { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()

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
                onToggleExpanded = onToggleExpanded
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
                                coroutineScope.launch { drawerScrollState.animateScrollTo(rxLinkCardOffsetY) }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun CompactBridgeBottomDrawer(
    uiState: BridgeUiState,
    plottedNodes: List<MeshNode>,
    obstructions: List<BridgeObstructionItem>,
    controlsExpanded: Boolean,
    onToggleExpanded: () -> Unit,
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
    onAlignCandidate: ((RelayCandidate) -> Unit)? = null
) {
    val obstructionBadgeCount = remember(obstructions) {
        obstructions.count { it.point.status != ViewshedPointStatus.CLEAR }
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
                    if (!controlsExpanded) onToggleExpanded()
                },
                onToggleExpanded = onToggleExpanded
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
        }
    }
}

@Composable
internal fun WorkspaceModalsHost(
    activeMode: WorkspaceMode,
    isSplitPane: Boolean,
    overlayContinuityMode: OverlayContinuityMode,
    meshState: NodeMapUiState,
    viewshedState: ViewshedUiState,
    bridgeState: BridgeUiState,
    activeMapView: MapView?,
    showFiltersSheet: Boolean,
    showSavedLayersSheet: Boolean,
    showMapToolsSheet: Boolean,
    showSaveLayerDialog: Boolean,
    showExportDialog: Boolean,
    inspectedRfNode: MeshNode?,
    onDismissFilters: () -> Unit,
    onDismissSavedLayers: () -> Unit,
    onOpenSavedLayers: () -> Unit,
    onDismissMapTools: () -> Unit,
    onDismissSaveLayerDialog: () -> Unit,
    onOpenExportDialog: () -> Unit,
    onDismissExportDialog: () -> Unit,
    onDismissRfNodeSheet: () -> Unit,
    onLoadJsonClick: () -> Unit,
    onClearJsonClick: () -> Unit,
    onClearAllWithManualClick: (() -> Unit)?,
    onToggleShowMqtt: () -> Unit,
    onToggleRfSightlines: () -> Unit,
    onToggleFavoritesOnly: () -> Unit,
    onToggleHwModelFilter: (String) -> Unit,
    onToggleRoleFilter: (String) -> Unit,
    onResetAllFilters: () -> Unit,
    onToggleLosMode: () -> Unit,
    onToggleMeasureMode: () -> Unit,
    onToggleAddNodeMode: () -> Unit,
    onOpenDirectLinksSheet: () -> Unit,
    onCloseDirectLinksSheet: () -> Unit,
    onToggleDirectLinksSort: () -> Unit,
    onMeasureDirectLinkFromSelf: (MeshNode) -> Unit,
    onAnalyzeLosBetweenNodes: (MeshNode, MeshNode) -> Unit,
    onOpenNoGpsSheet: () -> Unit,
    onCloseNoGpsSheet: () -> Unit,
    onNoGpsSearchQueryChange: (String) -> Unit,
    onSelectNode: (MeshNode?) -> Unit,
    onOpenEditNodeEditor: (MeshNode) -> Unit,
    onStartCoordinatePickForEditor: (NodeEditorDraft) -> Unit,
    onDismissNodeEditor: () -> Unit,
    onSaveNodeEditorDraft: (NodeEditorDraft) -> String?,
    onDeleteManualNode: (MeshNode) -> Unit,
    onRevertNodeEdits: (MeshNode) -> Unit,
    onBuildNodeDbBackupJson: (() -> String)?,
    onConfirmPendingImportMerge: () -> Unit,
    onConfirmPendingImportReplace: () -> Unit,
    onDismissPendingImport: () -> Unit,
    onSelectBaseLayer: (MapBaseLayer) -> Unit,
    onFitBounds: () -> Unit,
    onSelectOverlayContinuityMode: (OverlayContinuityMode) -> Unit,
    onToggleAggregateCoverage: () -> Unit,
    onUpdateAggregateRadiusKm: (Int) -> Unit,
    onUpdateAggregateMastMeters: (Double) -> Unit,
    onRefreshAggregateCoverage: () -> Unit,
    onCancelAggregateCoverageCompute: () -> Unit,
    onDismissAggregateCoverageError: () -> Unit,
    onViewshedOpacityChange: (Float) -> Unit,
    onToggleSavedLayerEnabled: (String) -> Unit,
    onToggleSavedLayerRenderMode: (String) -> Unit,
    onDeleteSavedLayer: (String) -> Unit,
    suggestedLayerName: String,
    suggestedLayerTint: Int,
    onSaveLayer: (String, Int, LayerRenderMode) -> Unit,
    onClearCurrentViewshed: () -> Unit,
    onClearAllViewshed: () -> Unit,
    onClearBridgeOverlay: () -> Unit,
    onResetBridgeAll: () -> Unit,
    onAssignViewshedTx: (MeshNode) -> Unit,
    onAssignViewshedRx: (MeshNode) -> Unit,
    onAssignBridgeTx: (MeshNode) -> Unit,
    onAssignBridgeRx: (MeshNode) -> Unit,
    onInspectFresnelSightline: (MeshNode) -> Unit,
    showOfflineMapsDialog: Boolean = false,
    offlineDemState: OfflineDemUiState = OfflineDemUiState(),
    viewportState: MapViewportState = MapViewportState(),
    onDismissOfflineMaps: () -> Unit = {},
    onToggleOfflineOnly: (Boolean) -> Unit = {},
    onDownloadPreset: (OfflineDemPreset) -> Unit = {},
    onDownloadViewport: (Double, Double, Double, Double) -> Unit = { _, _, _, _ -> },
    onCancelOfflineDownload: () -> Unit = {},
    onClearOfflineCache: () -> Unit = {},
    onAlignNode: ((MeshNode) -> Unit)? = null
) {
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var alsoClearManualNodes by remember { mutableStateOf(false) }
    val savedLayersActiveCount = viewshedState.savedLayers.count { it.isEnabled }
    val layersBadgeCount = savedLayersActiveCount + (if (meshState.isAggregateCoverageEnabled) 1 else 0)

    if (showFiltersSheet) {
        NodeMapFiltersSheet(
            uiState = meshState,
            onToggleShowMqtt = onToggleShowMqtt,
            onToggleRfSightlines = onToggleRfSightlines,
            onToggleFavoritesOnly = onToggleFavoritesOnly,
            onToggleLosMode = onToggleLosMode,
            onToggleMeasureMode = onToggleMeasureMode,
            onOpenDirectLinksSheet = onOpenDirectLinksSheet,
            onOpenNoGpsSheet = onOpenNoGpsSheet,
            onLoadJsonClick = onLoadJsonClick,
            onClearJsonClick = {
                alsoClearManualNodes = !meshState.hasLoadedFile && meshState.manualNodeCount > 0
                showClearConfirmDialog = true
            },
            onResetFilters = onResetAllFilters,
            onToggleAggregateCoverage = onToggleAggregateCoverage,
            onToggleAddNodeMode = onToggleAddNodeMode,
            onToggleHwModelFilter = onToggleHwModelFilter,
            onToggleRoleFilter = onToggleRoleFilter,
            onDismiss = onDismissFilters
        )
    }

    if (showSavedLayersSheet) {
        SavedViewshedLayersSheet(
            layers = viewshedState.savedLayers,
            overlayOpacity = viewshedState.overlayOpacity,
            onOpacityChange = onViewshedOpacityChange,
            onToggleEnabled = onToggleSavedLayerEnabled,
            onToggleRenderMode = onToggleSavedLayerRenderMode,
            onDeleteLayer = onDeleteSavedLayer,
            onDismiss = onDismissSavedLayers,
            overlayContinuityMode = overlayContinuityMode,
            onSelectOverlayContinuityMode = onSelectOverlayContinuityMode,
            aggregateConfig = if (activeMode == WorkspaceMode.MESH) {
                AggregateCoverageSheetConfig(
                    isEnabled = meshState.isAggregateCoverageEnabled,
                    radiusKm = meshState.aggregateCoverageRadiusKm,
                    mastHeightMeters = meshState.aggregateCoverageMastMeters,
                    eligibleRfNodeCount = meshState.eligibleAggregateRfNodeCount,
                    isLoading = meshState.isAggregateCoverageLoading,
                    progressDone = meshState.aggregateCoverageProgressDone,
                    progressTotal = meshState.aggregateCoverageProgressTotal,
                    isStale = meshState.isAggregateCoverageStale,
                    errorMessage = meshState.aggregateCoverageError,
                    result = meshState.aggregateCoverageResult,
                    onToggleEnabled = onToggleAggregateCoverage,
                    onRadiusChange = onUpdateAggregateRadiusKm,
                    onMastHeightChange = onUpdateAggregateMastMeters,
                    onRefresh = onRefreshAggregateCoverage,
                    onCancelCompute = onCancelAggregateCoverageCompute,
                    onDismissError = onDismissAggregateCoverageError
                )
            } else null
        )
    }

    if (showMapToolsSheet) {
        when (activeMode) {
            WorkspaceMode.MESH -> {
                val savedSummary = if (viewshedState.savedLayers.isNotEmpty() || meshState.isAggregateCoverageEnabled) {
                    "${stringResource(R.string.filter_saved_layers)} ($layersBadgeCount)"
                } else stringResource(R.string.filter_saved_layers)
                val showRssiLegend = meshState.isAggregateCoverageEnabled ||
                    viewshedState.savedLayers.any { it.isEnabled && it.renderMode == LayerRenderMode.RSSI }
                MapToolsBottomSheet(
                    baseLayer = meshState.baseLayer,
                    legendMode = MapToolsLegendMode.NODE_MAP,
                    sensitivityDbm = meshState.aggregateCoverageResult?.sensitivityDbm ?: -130.0,
                    testTagPrefix = "map",
                    onSelectBaseLayer = onSelectBaseLayer,
                    onExport = onOpenExportDialog,
                    onFitBounds = if (meshState.plottedGpsCount > 0) onFitBounds else null,
                    onLoadJson = onLoadJsonClick,
                    onClearJson = if (meshState.hasDatasetOrCustomNodes) {
                        {
                            alsoClearManualNodes = !meshState.hasLoadedFile && meshState.manualNodeCount > 0
                            showClearConfirmDialog = true
                        }
                    } else null,
                    savedLayersSummary = savedSummary,
                    onOpenSavedLayers = onOpenSavedLayers,
                    showNodeMapRssiLegend = showRssiLegend,
                    onAddNode = onToggleAddNodeMode,
                    onDismiss = onDismissMapTools
                )
            }
            WorkspaceMode.VIEWSHED -> {
                val layersLabel = if (viewshedState.savedLayers.isNotEmpty()) {
                    "${stringResource(R.string.filter_saved_layers)} ($savedLayersActiveCount/${viewshedState.savedLayers.size})"
                } else stringResource(R.string.filter_saved_layers)
                MapToolsBottomSheet(
                    baseLayer = meshState.baseLayer,
                    legendMode = MapToolsLegendMode.VIEWSHED,
                    sensitivityDbm = viewshedState.sensitivityDbm.toDouble(),
                    testTagPrefix = "viewshed",
                    onSelectBaseLayer = onSelectBaseLayer,
                    onExport = onOpenExportDialog,
                    savedLayersSummary = layersLabel,
                    onOpenSavedLayers = onOpenSavedLayers,
                    hasClearableOverlay = (viewshedState.currentResult != null || viewshedState.rxLinkResult != null) && !viewshedState.isCalculating,
                    onClearOverlay = onClearCurrentViewshed,
                    hasResettablePins = viewshedState.hasAnyOverlayOrPins && !viewshedState.isCalculating,
                    onResetPins = onClearAllViewshed,
                    onDismiss = onDismissMapTools
                )
            }
            WorkspaceMode.BRIDGE -> {
                MapToolsBottomSheet(
                    baseLayer = meshState.baseLayer,
                    legendMode = MapToolsLegendMode.BRIDGE,
                    sensitivityDbm = maxOf(
                        bridgeState.txSensitivityDbm,
                        bridgeState.rxSensitivityDbm,
                        bridgeState.relaySensitivityDbm
                    ).toDouble(),
                    testTagPrefix = "bridge",
                    onSelectBaseLayer = onSelectBaseLayer,
                    onExport = onOpenExportDialog,
                    hasClearableOverlay = bridgeState.currentResult != null && !bridgeState.isCalculating,
                    onClearOverlay = onClearBridgeOverlay,
                    hasResettablePins = bridgeState.hasAnyOverlayOrPins && !bridgeState.isCalculating,
                    onResetPins = onResetBridgeAll,
                    onDismiss = onDismissMapTools
                )
            }
        }
    }

    if (showSaveLayerDialog && viewshedState.currentResult != null) {
        SaveViewshedLayerDialog(
            initialName = suggestedLayerName,
            initialTintArgb = suggestedLayerTint,
            onDismiss = onDismissSaveLayerDialog,
            onConfirmSave = { name, tint, mode ->
                onDismissSaveLayerDialog()
                onSaveLayer(name, tint, mode)
            }
        )
    }

    if (meshState.showDirectLinksSheet) {
        DirectRfLinksBottomSheet(
            links = meshState.directRfLinksWithGps,
            selfNode = meshState.selfNode,
            sortDescending = meshState.directLinksSortDescending,
            onToggleSort = onToggleDirectLinksSort,
            onInspectNode = { node ->
                onCloseDirectLinksSheet()
                onSelectNode(node)
            },
            onMeasureOnMap = onMeasureDirectLinkFromSelf,
            onAnalyzeLos = { target ->
                val self = meshState.selfNode
                if (self != null) onAnalyzeLosBetweenNodes(self, target)
            },
            onDismiss = onCloseDirectLinksSheet
        )
    }

    if (meshState.showNoGpsSheet) {
        NoGpsNodesBottomSheet(
            nodes = meshState.filteredNoGpsNodes,
            searchQuery = meshState.noGpsSearchQuery,
            onSearchQueryChange = onNoGpsSearchQueryChange,
            onNodeClick = { node ->
                onCloseNoGpsSheet()
                onSelectNode(node)
            },
            onDismiss = onCloseNoGpsSheet
        )
    }

    if (!isSplitPane && activeMode == WorkspaceMode.MESH) {
        meshState.selectedNode?.let { selected ->
            NodeInspectorBottomSheet(
                node = selected,
                selfNode = meshState.selfNode,
                onDismiss = { onSelectNode(null) },
                onAssignViewshedTx = onAssignViewshedTx,
                onAssignViewshedRx = onAssignViewshedRx,
                onAssignBridgeTx = onAssignBridgeTx,
                onAssignBridgeRx = onAssignBridgeRx,
                onInspectFresnelSightline = if (meshState.selfNode?.hasValidGps == true && !selected.isSelf && selected.hasValidGps) {
                    onInspectFresnelSightline
                } else null,
                onEditNode = onOpenEditNodeEditor,
                onAlignAntenna = onAlignNode
            )
        }
    }

    inspectedRfNode?.let { tappedNode ->
        val isTx = if (activeMode == WorkspaceMode.VIEWSHED) {
            viewshedState.sourceNode?.let { it.id == tappedNode.id && it.num == tappedNode.num } == true
        } else {
            bridgeState.txNode?.let { it.id == tappedNode.id && it.num == tappedNode.num } == true
        }
        val isRx = if (activeMode == WorkspaceMode.VIEWSHED) {
            viewshedState.rxNode?.let { it.id == tappedNode.id && it.num == tappedNode.num } == true
        } else {
            bridgeState.rxNode?.let { it.id == tappedNode.id && it.num == tappedNode.num } == true
        }
        RfNodeSiteActionSheet(
            node = tappedNode,
            isCurrentTx = isTx,
            isCurrentRx = isRx,
            onSetAsTx = {
                if (activeMode == WorkspaceMode.VIEWSHED) onAssignViewshedTx(tappedNode) else onAssignBridgeTx(tappedNode)
                onDismissRfNodeSheet()
            },
            onSetAsRx = {
                if (activeMode == WorkspaceMode.VIEWSHED) onAssignViewshedRx(tappedNode) else onAssignBridgeRx(tappedNode)
                onDismissRfNodeSheet()
            },
            onAlignAntenna = if (onAlignNode != null && tappedNode.hasValidGps) {
                {
                    onDismissRfNodeSheet()
                    onAlignNode(tappedNode)
                }
            } else null,
            onDismiss = onDismissRfNodeSheet
        )
    }

    val draft = meshState.editingDraft
    if (draft != null && !draft.isPickingCoordinateOnMap) {
        val targetNode = if (!draft.isCreatingNew && draft.originalKey != null) {
            meshState.allNodes.firstOrNull { it.stableKey == draft.originalKey }
        } else null
        NodeEditorBottomSheet(
            initialDraft = draft,
            onSave = onSaveNodeEditorDraft,
            onPickOnMap = onStartCoordinatePickForEditor,
            onDeleteManual = if (!draft.isCreatingNew && draft.isManual && targetNode != null) {
                {
                    onDeleteManualNode(targetNode)
                    onDismissNodeEditor()
                }
            } else null,
            onRevertEdits = if (!draft.isCreatingNew && !draft.isManual && draft.isEdited && targetNode != null) {
                {
                    onRevertNodeEdits(targetNode)
                    onDismissNodeEditor()
                }
            } else null,
            onDismiss = onDismissNodeEditor
        )
    }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            containerColor = TacticalSurface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text(stringResource(R.string.clear_json_dialog_title), style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.clear_json_dialog_message), style = MaterialTheme.typography.bodySmall)
                    if (meshState.manualNodeCount > 0 && meshState.hasLoadedFile && onClearAllWithManualClick != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { alsoClearManualNodes = !alsoClearManualNodes }
                                .testTag("clear_manual_nodes_checkbox_row")
                        ) {
                            Checkbox(
                                checked = alsoClearManualNodes,
                                onCheckedChange = { alsoClearManualNodes = it },
                                colors = CheckboxDefaults.colors(checkedColor = AlertErrorRed, uncheckedColor = TextSecondary)
                            )
                            Text("Also delete ${meshState.manualNodeCount} manual node(s)", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirmDialog = false
                        if (alsoClearManualNodes && onClearAllWithManualClick != null) onClearAllWithManualClick() else onClearJsonClick()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AlertErrorRed, contentColor = TextPrimary),
                    modifier = Modifier.testTag("confirm_clear_json_button")
                ) {
                    Text(stringResource(R.string.action_clear_confirm), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }, modifier = Modifier.testTag("cancel_clear_json_button")) {
                    Text(stringResource(R.string.action_cancel), color = TextSecondary)
                }
            }
        )
    }

    meshState.pendingImport?.let { pending ->
        AlertDialog(
            onDismissRequest = onDismissPendingImport,
            containerColor = TacticalSurface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text(stringResource(R.string.import_db_dialog_title), style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "${pending.fileName} (${pending.incomingResult.totalCount} nodes: ${pending.matchedExistingCount} matching, ${pending.newNodesCount} new).",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary
                    )
                    Text(
                        text = "Merge refreshes matching node coordinates and telemetry while keeping your custom colors, notes, edited specs, and manual nodes. Replace swaps the imported database.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Button(
                        onClick = onConfirmPendingImportMerge,
                        colors = ButtonDefaults.buttonColors(containerColor = NodeDirectGreen, contentColor = TacticalCanvas),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("import_merge_button")
                    ) {
                        Text(stringResource(R.string.import_db_merge_button), fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = onConfirmPendingImportReplace,
                        border = BorderStroke(1.dp, NodeMultiHopSky.copy(alpha = 0.7f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NodeMultiHopSky),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("import_replace_button")
                    ) {
                        Text(stringResource(R.string.import_db_replace_button), fontWeight = FontWeight.SemiBold)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = onDismissPendingImport,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("import_cancel_button")
                ) {
                    Text(stringResource(R.string.action_cancel), color = TextSecondary)
                }
            }
        )
    }

    val exportPrefix = when (activeMode) {
        WorkspaceMode.MESH -> "lemesh_map"
        WorkspaceMode.VIEWSHED -> "lemesh_viewshed"
        WorkspaceMode.BRIDGE -> "lemesh_bridge"
    }
    MapExportHost(
        visible = showExportDialog,
        filePrefix = exportPrefix,
        mapViewProvider = { activeMapView },
        buildGeoJson = {
            when (activeMode) {
                WorkspaceMode.MESH -> MapExportEngine.buildNodeMapGeoJson(meshState, viewshedState.savedLayers)
                WorkspaceMode.VIEWSHED -> MapExportEngine.buildViewshedGeoJson(viewshedState, meshState.baseLayer.displayName)
                WorkspaceMode.BRIDGE -> MapExportEngine.buildBridgeGeoJson(bridgeState, meshState.baseLayer.displayName)
            }
        },
        buildSummary = {
            when (activeMode) {
                WorkspaceMode.MESH -> MapExportEngine.buildNodeMapSummary(meshState, viewshedState.savedLayers)
                WorkspaceMode.VIEWSHED -> MapExportEngine.buildViewshedSummary(viewshedState, meshState.baseLayer)
                WorkspaceMode.BRIDGE -> MapExportEngine.buildBridgeSummary(bridgeState, meshState.baseLayer)
            }
        },
        buildKml = {
            when (activeMode) {
                WorkspaceMode.MESH -> MapExportEngine.buildNodeMapKml(meshState, viewshedState.savedLayers)
                WorkspaceMode.VIEWSHED -> MapExportEngine.buildViewshedKml(viewshedState, meshState.baseLayer.displayName)
                WorkspaceMode.BRIDGE -> MapExportEngine.buildBridgeKml(bridgeState, meshState.baseLayer.displayName)
            }
        },
        buildNodeDbBackupJson = if (activeMode == WorkspaceMode.MESH) onBuildNodeDbBackupJson else null,
        buildHostOutreachSpec = {
            GeoJsonReportExporter.buildHostOutreachSpec(
                nodes = meshState.allNodes,
                viewshedState = if (activeMode == WorkspaceMode.VIEWSHED || viewshedState.currentResult != null) viewshedState else null,
                bridgeState = if (activeMode == WorkspaceMode.BRIDGE || bridgeState.currentResult != null) bridgeState else null
            )
        },
        onDismiss = onDismissExportDialog
    )

    OfflineMapsDialog(
        visible = showOfflineMapsDialog,
        uiState = offlineDemState,
        mapViewProvider = { activeMapView },
        viewportState = viewportState,
        onToggleOfflineOnly = onToggleOfflineOnly,
        onDownloadPreset = onDownloadPreset,
        onDownloadViewport = onDownloadViewport,
        onCancelDownload = onCancelOfflineDownload,
        onClearCache = onClearOfflineCache,
        onDismiss = onDismissOfflineMaps
    )
}
