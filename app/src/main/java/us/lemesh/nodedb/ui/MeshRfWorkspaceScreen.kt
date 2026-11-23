package us.lemesh.nodedb.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.outlined.CellTower
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.osmdroid.views.MapView
import us.lemesh.nodedb.R
import us.lemesh.nodedb.data.AlignmentTarget
import us.lemesh.nodedb.data.FadeMarginPreset
import us.lemesh.nodedb.data.LayerRenderMode
import us.lemesh.nodedb.data.LiveSyncConfig
import us.lemesh.nodedb.data.LiveSyncUiState
import us.lemesh.nodedb.data.MeshNode
import us.lemesh.nodedb.data.OfflineDemPreset
import us.lemesh.nodedb.data.OfflineDemUiState
import us.lemesh.nodedb.data.RelayBridgeEngine
import us.lemesh.nodedb.data.RelayCandidate
import us.lemesh.nodedb.ui.state.MapViewportState
import us.lemesh.nodedb.ui.state.OverlayContinuityMode
import us.lemesh.nodedb.ui.state.WorkspaceMode
import us.lemesh.nodedb.ui.theme.NodeDirectGreen
import us.lemesh.nodedb.ui.theme.NodeMultiHopSky
import us.lemesh.nodedb.ui.theme.TacticalCanvas
import us.lemesh.nodedb.ui.theme.TacticalOutline
import us.lemesh.nodedb.ui.theme.TacticalSurface
import us.lemesh.nodedb.ui.theme.TacticalSurfaceBright
import us.lemesh.nodedb.ui.theme.TextPrimary
import us.lemesh.nodedb.ui.theme.TextSecondary

data class MeshModeActions(
    val onLoadJsonClick: () -> Unit = {},
    val onClearJsonClick: () -> Unit = {},
    val onClearAllWithManualClick: (() -> Unit)? = null,
    val onSearchQueryChange: (String) -> Unit = {},
    val onToggleShowMqtt: () -> Unit = {},
    val onToggleRfSightlines: () -> Unit = {},
    val onToggleFavoritesOnly: () -> Unit = {},
    val onToggleHwModelFilter: (String) -> Unit = {},
    val onToggleRoleFilter: (String) -> Unit = {},
    val onResetAllFilters: () -> Unit = {},
    val onSelectBaseLayer: (MapBaseLayer) -> Unit = {},
    val onSelectOverlayContinuityMode: (OverlayContinuityMode) -> Unit = {},
    val onOpenNoGpsSheet: () -> Unit = {},
    val onCloseNoGpsSheet: () -> Unit = {},
    val onNoGpsSearchQueryChange: (String) -> Unit = {},
    val onSelectNode: (MeshNode?) -> Unit = {},
    val onMapNodeClick: (MeshNode) -> Unit = {},
    val onSightlineClick: (MeshNode) -> Unit = onMapNodeClick,
    val onToggleMeasureMode: () -> Unit = {},
    val onClearMeasureAnchors: () -> Unit = {},
    val onUseSelfAsAnchorA: () -> Unit = {},
    val onOpenDirectLinksSheet: () -> Unit = {},
    val onCloseDirectLinksSheet: () -> Unit = {},
    val onToggleDirectLinksSort: () -> Unit = {},
    val onMeasureDirectLinkFromSelf: (MeshNode) -> Unit = {},
    val onToggleLosMode: () -> Unit = {},
    val onMapCoordinateTap: (Double, Double) -> Unit = { _, _ -> },
    val onToggleLosDrawerExpanded: () -> Unit = {},
    val onLosTxHeightChange: (String) -> Unit = {},
    val onLosRxHeightChange: (String) -> Unit = {},
    val onLosFrequencyChange: (String) -> Unit = {},
    val onLosTxPowerChange: (String) -> Unit = {},
    val onLosTxGainChange: (String) -> Unit = {},
    val onLosRxGainChange: (String) -> Unit = {},
    val onLosSensitivityChange: (String) -> Unit = {},
    val onApplyLosMastFix: () -> Unit = {},
    val onUseSelfAsLosPointA: () -> Unit = {},
    val onSwapLosEndpoints: () -> Unit = {},
    val onClearLosEndpoints: () -> Unit = {},
    val onRetryLosElevation: () -> Unit = {},
    val onUseFlatTerrainFallback: () -> Unit = {},
    val onAnalyzeLosBetweenNodes: (MeshNode, MeshNode) -> Unit = { _, _ -> },
    val onInspectFresnelSightline: (MeshNode) -> Unit = {},
    val onFitBounds: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    val onStartupZoomStarted: () -> Unit = {},
    val onSaveCameraState: (Double, Double, Double, Float) -> Unit = { _, _, _, _ -> },
    val onToggleAggregateCoverage: () -> Unit = {},
    val onUpdateAggregateRadiusKm: (Int) -> Unit = {},
    val onUpdateAggregateMastMeters: (Double) -> Unit = {},
    val onRefreshAggregateCoverage: () -> Unit = {},
    val onCancelAggregateCoverageCompute: () -> Unit = {},
    val onDismissAggregateCoverageError: () -> Unit = {},
    val onToggleAddNodeMode: () -> Unit = {},
    val onOpenNewNodeEditor: (Double?, Double?) -> Unit = { _, _ -> },
    val onOpenEditNodeEditor: (MeshNode) -> Unit = {},
    val onStartCoordinatePickForEditor: (NodeEditorDraft) -> Unit = {},
    val onDismissNodeEditor: () -> Unit = {},
    val onSaveNodeEditorDraft: (NodeEditorDraft) -> String? = { null },
    val onDeleteManualNode: (MeshNode) -> Unit = {},
    val onRevertNodeEdits: (MeshNode) -> Unit = {},
    val onBuildNodeDbBackupJson: (() -> String)? = null,
    val onConfirmPendingImportMerge: () -> Unit = {},
    val onConfirmPendingImportReplace: () -> Unit = {},
    val onDismissPendingImport: () -> Unit = {},
    val onAssignViewshedFromNode: (MeshNode) -> Unit = {},
    val onAssignViewshedRx: (MeshNode) -> Unit = {},
    val onAssignBridgeA: (MeshNode) -> Unit = {},
    val onAssignBridgeB: (MeshNode) -> Unit = {}
)

data class ViewshedModeActions(
    val onSetPlacementMode: (ViewshedPlacementMode) -> Unit = {},
    val onSetPlacementTarget: (ViewshedPlacementTarget) -> Unit = {},
    val onSetCustomCoordinate: (Double, Double) -> Unit = { _, _ -> },
    val onTxDragEnd: (Double, Double) -> Unit = { _, _ -> },
    val onRxDragEnd: (Double, Double) -> Unit = { _, _ -> },
    val onSelectExistingNode: (MeshNode) -> Unit = {},
    val onSelectRxNode: (MeshNode) -> Unit = {},
    val onSwapTxRx: () -> Unit = {},
    val onAntennaHeightChange: (Float) -> Unit = {},
    val onRxAntennaHeightChange: (Float) -> Unit = {},
    val onRadiusKmChange: (Int) -> Unit = {},
    val onFrequencyChange: (String) -> Unit = {},
    val onTxPowerChange: (Float) -> Unit = {},
    val onAntennaGainChange: (Float) -> Unit = {},
    val onSensitivityChange: (Float) -> Unit = {},
    val onOpacityChange: (Float) -> Unit = {},
    val onCalculateCoverage: () -> Unit = {},
    val onTogglePinSnapshot: () -> Unit = {},
    val onToggleShowSnapshotOnMap: () -> Unit = {},
    val suggestedLayerName: String = "Viewshed Layer",
    val suggestedLayerTint: Int = us.lemesh.nodedb.data.LAYER_TINT_PALETTE.first(),
    val onSaveLayer: (String, Int, LayerRenderMode) -> Unit = { _, _, _ -> },
    val onToggleSavedLayerEnabled: (String) -> Unit = {},
    val onToggleSavedLayerRenderMode: (String) -> Unit = {},
    val onDeleteSavedLayer: (String) -> Unit = {},
    val onClearCurrentViewshed: () -> Unit = {},
    val onClearAllAndStartFresh: () -> Unit = {},
    val onSendToBridge: () -> Unit = {},
    val onApplyMastFix: (Float, Float) -> Unit = { _, _ -> },
    val onInspectObstructedNode: (MeshNode) -> Unit = {},
    val onDismissError: () -> Unit = {}
)

data class BridgeModeActions(
    val onSetPlacementMode: (BridgePlacementMode) -> Unit = {},
    val onSetPlacementTarget: (BridgePlacementTarget) -> Unit = {},
    val onMapTap: (Double, Double) -> Unit = { _, _ -> },
    val onTxDragEnd: (Double, Double) -> Unit = { _, _ -> },
    val onRxDragEnd: (Double, Double) -> Unit = { _, _ -> },
    val onSelectTxNode: (MeshNode) -> Unit = {},
    val onSelectRxNode: (MeshNode) -> Unit = {},
    val onSwapTxRx: () -> Unit = {},
    val onClearOverlay: () -> Unit = {},
    val onResetAll: () -> Unit = {},
    val onTxHeightChange: (Float) -> Unit = {},
    val onRxHeightChange: (Float) -> Unit = {},
    val onRelayHeightChange: (Float) -> Unit = {},
    val onRadiusKmChange: (Int) -> Unit = {},
    val onFrequencyChange: (String) -> Unit = {},
    val onTxPowerChange: (Float) -> Unit = {},
    val onTxAntennaGainChange: (Float) -> Unit = {},
    val onTxSensitivityChange: (Float) -> Unit = {},
    val onRxPowerChange: (Float) -> Unit = {},
    val onRxAntennaGainChange: (Float) -> Unit = {},
    val onRxSensitivityChange: (Float) -> Unit = {},
    val onRelayPowerChange: (Float) -> Unit = {},
    val onRelayAntennaGainChange: (Float) -> Unit = {},
    val onRelaySensitivityChange: (Float) -> Unit = {},
    val onOpacityChange: (Float) -> Unit = {},
    val onCalculateBridge: () -> Unit = {},
    val onSelectCandidate: (Int) -> Unit = {},
    val onSelectDrawerTab: (BridgeDrawerTab) -> Unit = {},
    val onSelectObstruction: (String?) -> Unit = {},
    val onDismissError: () -> Unit = {},
    val onSelectProfileMode: (us.lemesh.nodedb.data.BridgeProfileMode) -> Unit = {},
    val onScrubProfileSample: (us.lemesh.nodedb.data.BridgeProfileSample?) -> Unit = {},
    val onRetryLiveProfile: () -> Unit = {}
)

data class OfflineMapsActions(
    val onToggleOfflineOnly: (Boolean) -> Unit = {},
    val onDownloadPreset: (OfflineDemPreset) -> Unit = {},
    val onDownloadViewport: (Double, Double, Double, Double) -> Unit = { _, _, _, _ -> },
    val onCancelDownload: () -> Unit = {},
    val onClearCache: () -> Unit = {},
    val onDismissMessages: () -> Unit = {}
)

data class LiveSyncActions(
    val onUpdateConfig: (LiveSyncConfig) -> Unit = {},
    val onConnect: (LiveSyncConfig) -> Unit = {},
    val onDisconnect: () -> Unit = {},
    val onDismissError: () -> Unit = {}
)

@Composable
fun MeshRfWorkspaceScreen(
    activeMode: WorkspaceMode,
    viewportState: MapViewportState = MapViewportState(),
    meshState: NodeMapUiState = NodeMapUiState(),
    viewshedState: ViewshedUiState = ViewshedUiState(),
    bridgeState: BridgeUiState = BridgeUiState(),
    onSwitchMode: (WorkspaceMode) -> Unit = {},
    onPinDragStart: (WorkspaceMode) -> Unit = {},
    meshActions: MeshModeActions = MeshModeActions(),
    viewshedActions: ViewshedModeActions = ViewshedModeActions(),
    bridgeActions: BridgeModeActions = BridgeModeActions(),
    offlineDemState: OfflineDemUiState = OfflineDemUiState(),
    offlineActions: OfflineMapsActions = OfflineMapsActions(),
    liveSyncState: LiveSyncUiState = LiveSyncUiState(),
    liveSyncActions: LiveSyncActions = LiveSyncActions(),
    onSelectFadeMarginPreset: (FadeMarginPreset) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showSavedLayersSheet by remember { mutableStateOf(false) }
    var showFiltersSheet by remember { mutableStateOf(false) }
    var showMapToolsSheet by remember { mutableStateOf(false) }
    var showSaveLayerDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showOfflineMapsDialog by remember { mutableStateOf(false) }
    var showLiveSyncSheet by remember { mutableStateOf(false) }
    var showAlignmentHud by remember { mutableStateOf(false) }
    var alignmentTarget by remember { mutableStateOf<AlignmentTarget?>(null) }
    var inspectedRfNode by remember { mutableStateOf<MeshNode?>(null) }
    var viewshedDrawerExpanded by rememberSaveable { mutableStateOf(false) }
    var bridgeDrawerExpanded by rememberSaveable { mutableStateOf(false) }
    var dropCenterRequestId by remember { mutableLongStateOf(0L) }
    var activeMapView by remember { mutableStateOf<MapView?>(null) }

    val plottedGpsNodes = remember(meshState.filteredMapNodes) {
        meshState.filteredMapNodes.filter { it.hasValidGps }.sortedBy { it.displayLongName.lowercase() }
    }
    val allPlottedGpsNodes = remember(meshState.allNodes) {
        meshState.allNodes.filter { it.hasValidGps }.sortedBy { it.displayLongName.lowercase() }
    }
    val bridgeObstructions = remember(bridgeState.currentResult, bridgeState.selectedCandidateIndex, bridgeState.liveDirectHop) {
        val solved = bridgeState.currentResult
        if (solved != null) {
            RelayBridgeEngine.buildObstructionList(solved, bridgeState.selectedCandidateIndex)
        } else {
            RelayBridgeEngine.buildDirectObstructionList(bridgeState.liveDirectHop)
        }
    }
    val selectedBridgeObstruction = remember(bridgeObstructions, bridgeState.selectedObstructionId) {
        bridgeObstructions.firstOrNull { it.id == bridgeState.selectedObstructionId }
    }
    val activeBridgeCandidateRank = remember(bridgeState.currentResult, bridgeState.selectedCandidateIndex) {
        val result = bridgeState.currentResult ?: return@remember null
        (result.topCandidates.getOrNull(bridgeState.selectedCandidateIndex) ?: result.topCandidates.firstOrNull())?.rank
    }

    val activeFilterCount = (if (!meshState.showMqtt) 1 else 0) +
        (if (!meshState.showRfSightlines) 1 else 0) +
        (if (meshState.favoritesOnly) 1 else 0) +
        meshState.selectedHwModels.size +
        meshState.selectedRoles.size +
        (if (meshState.isLosMode) 1 else 0) +
        (if (meshState.isMeasureMode) 1 else 0) +
        (if (meshState.isAddNodeMode) 1 else 0)
    val savedLayersActiveCount = viewshedState.savedLayers.count { it.isEnabled }
    val layersBadgeCount = savedLayersActiveCount + (if (meshState.isAggregateCoverageEnabled) 1 else 0)
    val hasFilteredSubset = meshState.hasDatasetOrCustomNodes && (
        meshState.searchQuery.isNotBlank() ||
            !meshState.showMqtt ||
            meshState.favoritesOnly ||
            meshState.selectedHwModels.isNotEmpty() ||
            meshState.selectedRoles.isNotEmpty()
        )

    val availableAlignmentTargets = remember(
        allPlottedGpsNodes,
        bridgeState.currentResult,
        viewshedState.txLat,
        viewshedState.txLon,
        viewshedState.rxLat,
        viewshedState.rxLon
    ) {
        buildList {
            bridgeState.currentResult?.topCandidates?.forEach { cand ->
                add(
                    AlignmentTarget(
                        id = "relay_${cand.rank}",
                        label = "Relay #${cand.rank}",
                        subtitle = String.format(java.util.Locale.US, "%.0fm ASL • %+.1f dB", cand.groundElevationMeters, cand.worstLinkMarginDb),
                        latitude = cand.latitude,
                        longitude = cand.longitude,
                        elevationMeters = cand.groundElevationMeters + bridgeState.relayAntennaHeightMeters
                    )
                )
            }
            val vRxLat = viewshedState.rxLat
            val vRxLon = viewshedState.rxLon
            if (vRxLat != null && vRxLon != null) {
                add(
                    AlignmentTarget(
                        id = "viewshed_rx",
                        label = "RX: ${viewshedState.rxLabel}",
                        subtitle = "Viewshed RX Probe",
                        latitude = vRxLat,
                        longitude = vRxLon,
                        elevationMeters = viewshedState.rxGroundElevationMeters?.plus(viewshedState.rxAntennaHeightMeters)
                    )
                )
            }
            val vTxLat = viewshedState.txLat
            val vTxLon = viewshedState.txLon
            if (vTxLat != null && vTxLon != null) {
                add(
                    AlignmentTarget(
                        id = "viewshed_tx",
                        label = "TX: ${viewshedState.siteLabel}",
                        subtitle = "Viewshed TX Site",
                        latitude = vTxLat,
                        longitude = vTxLon,
                        elevationMeters = viewshedState.groundElevationMeters?.plus(viewshedState.antennaHeightMeters)
                    )
                )
            }
            allPlottedGpsNodes.filter { !it.isSelf && it.hasValidGps }.take(24).forEach { node ->
                add(
                    AlignmentTarget(
                        id = "node_${node.stableKey}",
                        label = node.displayShortName,
                        subtitle = node.displayLongName,
                        latitude = node.latitude!!,
                        longitude = node.longitude!!,
                        elevationMeters = node.altitude?.toDouble()
                    )
                )
            }
        }
    }

    val openAlignForNode: (MeshNode) -> Unit = { node ->
        if (node.hasValidGps) {
            meshActions.onSelectNode(null)
            inspectedRfNode = null
            alignmentTarget = AlignmentTarget(
                id = "node_${node.stableKey}",
                label = node.displayShortName,
                subtitle = node.displayLongName,
                latitude = node.latitude!!,
                longitude = node.longitude!!,
                elevationMeters = node.altitude?.toDouble()
            )
            showAlignmentHud = true
        }
    }

    val openAlignForCandidate: (RelayCandidate) -> Unit = { cand ->
        alignmentTarget = AlignmentTarget(
            id = "relay_${cand.rank}",
            label = "Relay #${cand.rank}",
            subtitle = String.format(java.util.Locale.US, "%.0fm ASL • %+.1f dB", cand.groundElevationMeters, cand.worstLinkMarginDb),
            latitude = cand.latitude,
            longitude = cand.longitude,
            elevationMeters = cand.groundElevationMeters + bridgeState.relayAntennaHeightMeters
        )
        showAlignmentHud = true
    }

    val canHandleCustomBack = showAlignmentHud ||
        meshState.editingDraft != null ||
        (activeMode == WorkspaceMode.MESH && (
            meshState.selectedNode != null ||
                meshState.isAddNodeMode ||
                meshState.isLosMode ||
                meshState.isMeasureMode
            )) ||
        activeMode != WorkspaceMode.MESH

    BackHandler(enabled = canHandleCustomBack) {
        when {
            showAlignmentHud -> showAlignmentHud = false
            meshState.editingDraft != null -> meshActions.onDismissNodeEditor()
            activeMode == WorkspaceMode.MESH && meshState.selectedNode != null -> meshActions.onSelectNode(null)
            activeMode == WorkspaceMode.MESH && meshState.isAddNodeMode -> meshActions.onToggleAddNodeMode()
            activeMode == WorkspaceMode.MESH && meshState.isLosMode -> meshActions.onToggleLosMode()
            activeMode == WorkspaceMode.MESH && meshState.isMeasureMode -> meshActions.onToggleMeasureMode()
            activeMode != WorkspaceMode.MESH -> onSwitchMode(WorkspaceMode.MESH)
        }
    }

    val persistentMapCanvas = remember {
        movableContentOf<PersistentMapCanvasProps> { props ->
            val mode = props.mode
            val continuity = props.continuity
            val mState = props.meshState
            val vState = props.viewshedState
            val bState = props.bridgeState
            val vpState = props.viewportState
            val dropId = props.dropId
            val nodesForCanvas = if (mode == WorkspaceMode.MESH) mState.filteredMapNodes else props.allPlottedGpsNodes
            MeshRfMapCanvas(
                mode = mode,
                baseLayer = vpState.baseLayer,
                overlayContinuityMode = continuity,
                nodes = nodesForCanvas,
                selfNode = mState.selfNode,
                showRfSightlines = mState.showRfSightlines,
                isMeasureMode = mState.isMeasureMode,
                measureAnchorA = mState.measureAnchorA,
                measureAnchorB = mState.measureAnchorB,
                isLosMode = mState.isLosMode,
                isAddNodeMode = mState.isAddNodeMode,
                losPointA = mState.losPointA,
                losPointB = mState.losPointB,
                losResult = mState.losResult,
                sightlineFresnelResults = mState.sightlineFresnelResults,
                sightlineDiagnostics = mState.sightlineDiagnostics,
                aggregateCoverageResult = mState.aggregateCoverageResult,
                isAggregateCoverageEnabled = mState.isAggregateCoverageEnabled,
                savedViewshedLayers = vState.savedLayers,
                overlayOpacity = when (mode) {
                    WorkspaceMode.MESH -> vState.overlayOpacity
                    WorkspaceMode.VIEWSHED -> vState.overlayOpacity
                    WorkspaceMode.BRIDGE -> bState.overlayOpacity
                },
                viewshedTxLat = vState.txLat,
                viewshedTxLon = vState.txLon,
                viewshedRxLat = vState.rxLat,
                viewshedRxLon = vState.rxLon,
                viewshedRadiusKm = vState.radiusKm,
                viewshedIsTxTarget = vState.effectivePlacementTarget == ViewshedPlacementTarget.TX,
                viewshedResult = vState.currentResult,
                viewshedRxLinkResult = vState.rxLinkResult,
                viewshedPinnedSnapshot = vState.pinnedSnapshot,
                viewshedShowSnapshotOnMap = vState.showSnapshotOnMap,
                bridgeTxLat = bState.txLat,
                bridgeTxLon = bState.txLon,
                bridgeRxLat = bState.rxLat,
                bridgeRxLon = bState.rxLon,
                bridgeRadiusKm = bState.radiusKm,
                bridgeIsTxTarget = bState.effectivePlacementTarget == BridgePlacementTarget.TX,
                bridgeResult = bState.currentResult,
                bridgeSelectedCandidateIndex = bState.selectedCandidateIndex,
                bridgeObstructions = props.bridgeObstructions,
                bridgeSelectedObstructionId = bState.selectedObstructionId,
                bridgeObstructionFocusNonce = bState.obstructionFocusNonce,
                bridgeLiveDirectHop = bState.liveDirectHop,
                bridgeScrubbedProfileSample = bState.scrubbedProfileSample,
                dropCenterRequestId = dropId,
                fitBoundsRequestId = vpState.fitBoundsRequestId,
                centerFocusRequestId = vpState.centerFocusRequestId,
                centerFocusLat = vpState.centerFocusLat,
                centerFocusLon = vpState.centerFocusLon,
                centerFocusMinZoom = vpState.centerFocusMinZoom,
                startupTargetLat = vpState.startupTargetLat,
                startupTargetLon = vpState.startupTargetLon,
                hasStartedStartupZoom = vpState.hasStartedStartupZoom,
                savedCameraLat = vpState.cameraLat,
                savedCameraLon = vpState.cameraLon,
                savedCameraZoom = vpState.cameraZoom,
                savedCameraBearingDeg = vpState.cameraBearingDeg,
                onMeshNodeClick = meshActions.onMapNodeClick,
                onSightlineClick = meshActions.onSightlineClick,
                onRfNodeTap = { node -> inspectedRfNode = node },
                onMapCoordinateTap = { lat, lon ->
                    when (mode) {
                        WorkspaceMode.MESH -> meshActions.onMapCoordinateTap(lat, lon)
                        WorkspaceMode.VIEWSHED -> viewshedActions.onSetCustomCoordinate(lat, lon)
                        WorkspaceMode.BRIDGE -> bridgeActions.onMapTap(lat, lon)
                    }
                },
                onDropAtMapCenter = { lat, lon ->
                    when (mode) {
                        WorkspaceMode.VIEWSHED -> viewshedActions.onSetCustomCoordinate(lat, lon)
                        WorkspaceMode.BRIDGE -> bridgeActions.onMapTap(lat, lon)
                        WorkspaceMode.MESH -> Unit
                    }
                },
                onPinDragStart = onPinDragStart,
                onTxDragEnd = { lat, lon ->
                    if (mode == WorkspaceMode.VIEWSHED) viewshedActions.onTxDragEnd(lat, lon)
                    else if (mode == WorkspaceMode.BRIDGE) bridgeActions.onTxDragEnd(lat, lon)
                },
                onRxDragEnd = { lat, lon ->
                    if (mode == WorkspaceMode.VIEWSHED) viewshedActions.onRxDragEnd(lat, lon)
                    else if (mode == WorkspaceMode.BRIDGE) bridgeActions.onRxDragEnd(lat, lon)
                },
                onEndpointPinTap = { endpoint ->
                    if (mode == WorkspaceMode.VIEWSHED) {
                        viewshedActions.onSetPlacementTarget(
                            if (endpoint == RfPinEndpoint.TX) ViewshedPlacementTarget.TX else ViewshedPlacementTarget.RX
                        )
                    } else if (mode == WorkspaceMode.BRIDGE) {
                        bridgeActions.onSetPlacementTarget(
                            if (endpoint == RfPinEndpoint.TX) BridgePlacementTarget.TX else BridgePlacementTarget.RX
                        )
                    }
                },
                onCandidatePinTap = bridgeActions.onSelectCandidate,
                onObstructionPinTap = { obsId ->
                    bridgeActions.onSelectObstruction(obsId)
                    bridgeActions.onSelectDrawerTab(BridgeDrawerTab.OBSTRUCTIONS)
                    bridgeDrawerExpanded = true
                },
                onStartupZoomStarted = meshActions.onStartupZoomStarted,
                onSaveCameraState = meshActions.onSaveCameraState,
                onMapViewReady = { activeMapView = it },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = TacticalCanvas,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            WorkspaceNavigationBar(
                activeMode = activeMode,
                onSelectMode = { target ->
                    inspectedRfNode = null
                    onSwitchMode(target)
                }
            )
        }
    ) { outerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = outerPadding.calculateBottomPadding())
        ) {
            val isSplitPane = maxWidth >= 600.dp

            val mapStage: @Composable (Modifier) -> Unit = { stageModifier ->
                Box(modifier = stageModifier) {
                    persistentMapCanvas(
                        PersistentMapCanvasProps(
                            mode = activeMode,
                            continuity = viewportState.overlayContinuityMode,
                            meshState = meshState,
                            viewshedState = viewshedState,
                            bridgeState = bridgeState,
                            viewportState = viewportState,
                            dropId = dropCenterRequestId,
                            allPlottedGpsNodes = allPlottedGpsNodes,
                            bridgeObstructions = bridgeObstructions
                        )
                    )

                    WorkspaceTopFloatingOverlay(
                        activeMode = activeMode,
                        meshState = meshState,
                        viewshedState = viewshedState,
                        bridgeState = bridgeState,
                        activeFilterCount = activeFilterCount,
                        layersBadgeCount = layersBadgeCount,
                        hasFilteredSubset = hasFilteredSubset,
                        selectedBridgeObstruction = selectedBridgeObstruction,
                        activeBridgeCandidateRank = activeBridgeCandidateRank,
                        onSearchQueryChange = meshActions.onSearchQueryChange,
                        onOpenFilters = { showFiltersSheet = true },
                        onOpenLayers = { showSavedLayersSheet = true },
                        onLoadJsonClick = meshActions.onLoadJsonClick,
                        onToggleAddNodeMode = meshActions.onToggleAddNodeMode,
                        onResetAllFilters = meshActions.onResetAllFilters,
                        onCancelAggregateCompute = meshActions.onCancelAggregateCoverageCompute,
                        onDismissMeshError = meshActions.onDismissError,
                        onDismissAggregateError = meshActions.onDismissAggregateCoverageError,
                        onSetViewshedPlacementMode = viewshedActions.onSetPlacementMode,
                        onDropViewshedAtCenter = { dropCenterRequestId++ },
                        onSwapViewshedTxRx = viewshedActions.onSwapTxRx,
                        onDismissViewshedError = viewshedActions.onDismissError,
                        onSetBridgePlacementMode = bridgeActions.onSetPlacementMode,
                        onDropBridgeAtCenter = { dropCenterRequestId++ },
                        onSwapBridgeTxRx = bridgeActions.onSwapTxRx,
                        onDismissBridgeError = bridgeActions.onDismissError,
                        onDismissBridgeObstructionCallout = { bridgeActions.onSelectObstruction(null) },
                        offlineDemState = offlineDemState,
                        onOpenOfflineMaps = { showOfflineMapsDialog = true },
                        liveSyncStatus = liveSyncState.status,
                        onOpenLiveSync = { showLiveSyncSheet = true },
                        modifier = Modifier.align(Alignment.TopCenter)
                    )

                    if (activeMode == WorkspaceMode.MESH && meshState.isLoading) {
                        Surface(
                            modifier = Modifier.align(Alignment.Center),
                            color = TacticalSurface.copy(alpha = 0.94f),
                            shape = RoundedCornerShape(16.dp),
                            tonalElevation = 8.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(Modifier.size(22.dp), color = NodeDirectGreen, strokeWidth = 2.5.dp)
                                Spacer(Modifier.width(12.dp))
                                Text("Parsing NodeDB JSON…", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                            }
                        }
                    } else if (activeMode == WorkspaceMode.VIEWSHED && viewshedState.isCalculating) {
                        RfPlannerProgressOverlay(
                            note = viewshedState.statusNote ?: "Computing viewshed…",
                            borderColor = NodeMultiHopSky,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else if (activeMode == WorkspaceMode.BRIDGE && bridgeState.isCalculating) {
                        RfPlannerProgressOverlay(
                            note = bridgeState.statusNote ?: "Solving TX–RX bridge…",
                            borderColor = NodeDirectGreen,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }

                    val fabTestTag = when (activeMode) {
                        WorkspaceMode.MESH -> "map_tools_fab"
                        WorkspaceMode.VIEWSHED -> "viewshed_map_tools_fab"
                        WorkspaceMode.BRIDGE -> "bridge_map_tools_fab"
                    }
                    if (isSplitPane) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.End
                        ) {
                            FloatingCompassAlignButton(
                                isActive = showAlignmentHud,
                                onClick = {
                                    if (!showAlignmentHud && alignmentTarget == null) {
                                        alignmentTarget = availableAlignmentTargets.firstOrNull()
                                    }
                                    showAlignmentHud = !showAlignmentHud
                                }
                            )
                            FloatingMapToolsButton(
                                onClick = { showMapToolsSheet = true },
                                testTag = fabTestTag
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .widthIn(max = 720.dp)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp)
                                    .padding(bottom = if (activeMode == WorkspaceMode.MESH && !meshState.isAddNodeMode && !meshState.isLosMode && !meshState.isMeasureMode) 10.dp else 0.dp),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FloatingCompassAlignButton(
                                    isActive = showAlignmentHud,
                                    onClick = {
                                        if (!showAlignmentHud && alignmentTarget == null) {
                                            alignmentTarget = availableAlignmentTargets.firstOrNull()
                                        }
                                        showAlignmentHud = !showAlignmentHud
                                    }
                                )
                                Spacer(Modifier.width(8.dp))
                                FloatingMapToolsButton(
                                    onClick = { showMapToolsSheet = true },
                                    testTag = fabTestTag
                                )
                            }
                            when (activeMode) {
                                WorkspaceMode.MESH -> {
                                    val toolModifier = Modifier
                                        .padding(horizontal = 12.dp)
                                        .padding(bottom = 10.dp)
                                    when {
                                        meshState.isAddNodeMode -> AddNodeModeBanner(
                                            isPickingForExistingDraft = meshState.editingDraft?.isPickingCoordinateOnMap == true,
                                            onUseMapCenter = {
                                                val center = activeMapView?.mapCenter
                                                meshActions.onOpenNewNodeEditor(center?.latitude, center?.longitude)
                                            },
                                            onOpenManualForm = { meshActions.onOpenNewNodeEditor(null, null) },
                                            onDone = meshActions.onToggleAddNodeMode,
                                            modifier = toolModifier.widthIn(max = 580.dp)
                                        )
                                        meshState.isLosMode -> LosProfileDrawer(
                                            pointA = meshState.losPointA,
                                            pointB = meshState.losPointB,
                                            selfNode = meshState.selfNode,
                                            txHeightText = meshState.losTxHeightText,
                                            rxHeightText = meshState.losRxHeightText,
                                            frequencyText = meshState.losFrequencyText,
                                            result = meshState.losResult,
                                            isLoading = meshState.isLosLoading,
                                            errorMessage = meshState.losErrorMessage,
                                            isExpanded = meshState.isLosDrawerExpanded,
                                            onToggleExpanded = meshActions.onToggleLosDrawerExpanded,
                                            onTxHeightChange = meshActions.onLosTxHeightChange,
                                            onRxHeightChange = meshActions.onLosRxHeightChange,
                                            onFrequencyChange = meshActions.onLosFrequencyChange,
                                            onUseSelfAsPointA = meshActions.onUseSelfAsLosPointA,
                                            onSwapEndpoints = meshActions.onSwapLosEndpoints,
                                            onClearEndpoints = meshActions.onClearLosEndpoints,
                                            onRetryElevation = meshActions.onRetryLosElevation,
                                            onUseFlatFallback = meshActions.onUseFlatTerrainFallback,
                                            onDone = meshActions.onToggleLosMode,
                                            modifier = toolModifier.widthIn(max = 620.dp),
                                            txPowerText = meshState.losTxPowerText,
                                            txGainText = meshState.losTxGainText,
                                            rxGainText = meshState.losRxGainText,
                                            sensitivityText = meshState.losSensitivityText,
                                            onTxPowerChange = meshActions.onLosTxPowerChange,
                                            onTxGainChange = meshActions.onLosTxGainChange,
                                            onRxGainChange = meshActions.onLosRxGainChange,
                                            onSensitivityChange = meshActions.onLosSensitivityChange,
                                            onApplyMastFix = meshActions.onApplyLosMastFix,
                                            isOfflineTileMissing = meshState.isLosOfflineTileMissing,
                                            onOpenOfflineMaps = { showOfflineMapsDialog = true },
                                            observedDiagnostic = meshState.losObservedDiagnostic,
                                            fadeMarginPreset = meshState.fadeMarginPreset,
                                            onSelectFadeMarginPreset = onSelectFadeMarginPreset
                                        )
                                        meshState.isMeasureMode -> RfLinkRulerBar(
                                            anchorA = meshState.measureAnchorA,
                                            anchorB = meshState.measureAnchorB,
                                            selfNode = meshState.selfNode,
                                            onUseSelfAsAnchorA = meshActions.onUseSelfAsAnchorA,
                                            onInspectAnchorB = { node -> meshActions.onSelectNode(node) },
                                            onAnalyzeLos = {
                                                val a = meshState.measureAnchorA
                                                val b = meshState.measureAnchorB
                                                if (a != null && b != null) meshActions.onAnalyzeLosBetweenNodes(a, b)
                                            },
                                            onOpenDirectLinksSheet = meshActions.onOpenDirectLinksSheet,
                                            onClear = meshActions.onClearMeasureAnchors,
                                            onDone = meshActions.onToggleMeasureMode,
                                            modifier = toolModifier.widthIn(max = 580.dp)
                                        )
                                    }
                                }
                                WorkspaceMode.VIEWSHED -> CompactViewshedBottomDrawer(
                                    uiState = viewshedState,
                                    plottedNodes = plottedGpsNodes,
                                    controlsExpanded = viewshedDrawerExpanded,
                                    onToggleExpanded = { viewshedDrawerExpanded = !viewshedDrawerExpanded },
                                    onSelectTxNode = viewshedActions.onSelectExistingNode,
                                    onSelectRxNode = viewshedActions.onSelectRxNode,
                                    onAntennaHeightChange = viewshedActions.onAntennaHeightChange,
                                    onRxAntennaHeightChange = viewshedActions.onRxAntennaHeightChange,
                                    onRadiusKmChange = viewshedActions.onRadiusKmChange,
                                    onFrequencyChange = viewshedActions.onFrequencyChange,
                                    onTxPowerChange = viewshedActions.onTxPowerChange,
                                    onAntennaGainChange = viewshedActions.onAntennaGainChange,
                                    onSensitivityChange = viewshedActions.onSensitivityChange,
                                    onOpacityChange = viewshedActions.onOpacityChange,
                                    onCalculateCoverage = viewshedActions.onCalculateCoverage,
                                    onOpenSaveLayerDialog = { showSaveLayerDialog = true },
                                    onTogglePinSnapshot = viewshedActions.onTogglePinSnapshot,
                                    onToggleShowSnapshotOnMap = viewshedActions.onToggleShowSnapshotOnMap,
                                    onSendToBridge = viewshedActions.onSendToBridge,
                                    onApplyMastFix = viewshedActions.onApplyMastFix,
                                    onInspectObstructedNode = viewshedActions.onInspectObstructedNode,
                                    onDismissError = viewshedActions.onDismissError,
                                    onSelectFadeMarginPreset = onSelectFadeMarginPreset
                                )
                                WorkspaceMode.BRIDGE -> CompactBridgeBottomDrawer(
                                    uiState = bridgeState,
                                    plottedNodes = plottedGpsNodes,
                                    obstructions = bridgeObstructions,
                                    controlsExpanded = bridgeDrawerExpanded,
                                    onToggleExpanded = { bridgeDrawerExpanded = !bridgeDrawerExpanded },
                                    onSelectTxNode = bridgeActions.onSelectTxNode,
                                    onSelectRxNode = bridgeActions.onSelectRxNode,
                                    onTxHeightChange = bridgeActions.onTxHeightChange,
                                    onRxHeightChange = bridgeActions.onRxHeightChange,
                                    onRelayHeightChange = bridgeActions.onRelayHeightChange,
                                    onRadiusKmChange = bridgeActions.onRadiusKmChange,
                                    onFrequencyChange = bridgeActions.onFrequencyChange,
                                    onTxPowerChange = bridgeActions.onTxPowerChange,
                                    onTxAntennaGainChange = bridgeActions.onTxAntennaGainChange,
                                    onTxSensitivityChange = bridgeActions.onTxSensitivityChange,
                                    onRxPowerChange = bridgeActions.onRxPowerChange,
                                    onRxAntennaGainChange = bridgeActions.onRxAntennaGainChange,
                                    onRxSensitivityChange = bridgeActions.onRxSensitivityChange,
                                    onRelayPowerChange = bridgeActions.onRelayPowerChange,
                                    onRelayAntennaGainChange = bridgeActions.onRelayAntennaGainChange,
                                    onRelaySensitivityChange = bridgeActions.onRelaySensitivityChange,
                                    onOpacityChange = bridgeActions.onOpacityChange,
                                    onCalculateBridge = bridgeActions.onCalculateBridge,
                                    onSelectCandidate = bridgeActions.onSelectCandidate,
                                    onSelectDrawerTab = bridgeActions.onSelectDrawerTab,
                                    onSelectObstruction = bridgeActions.onSelectObstruction,
                                    onSelectProfileMode = bridgeActions.onSelectProfileMode,
                                    onScrubProfileSample = bridgeActions.onScrubProfileSample,
                                    onRetryLiveProfile = bridgeActions.onRetryLiveProfile,
                                    onDismissError = bridgeActions.onDismissError,
                                    onOpenOfflineMaps = { showOfflineMapsDialog = true },
                                    onSelectFadeMarginPreset = onSelectFadeMarginPreset,
                                    onAlignCandidate = openAlignForCandidate
                                )
                            }
                        }
                    }

                    WorkspaceModalsHost(
                        activeMode = activeMode,
                        isSplitPane = isSplitPane,
                        overlayContinuityMode = viewportState.overlayContinuityMode,
                        meshState = meshState,
                        viewshedState = viewshedState,
                        bridgeState = bridgeState,
                        activeMapView = activeMapView,
                        showFiltersSheet = showFiltersSheet,
                        showSavedLayersSheet = showSavedLayersSheet,
                        showMapToolsSheet = showMapToolsSheet,
                        showSaveLayerDialog = showSaveLayerDialog,
                        showExportDialog = showExportDialog,
                        inspectedRfNode = inspectedRfNode,
                        onDismissFilters = { showFiltersSheet = false },
                        onDismissSavedLayers = { showSavedLayersSheet = false },
                        onOpenSavedLayers = { showSavedLayersSheet = true },
                        onDismissMapTools = { showMapToolsSheet = false },
                        onDismissSaveLayerDialog = { showSaveLayerDialog = false },
                        onOpenExportDialog = { showExportDialog = true },
                        onDismissExportDialog = { showExportDialog = false },
                        onDismissRfNodeSheet = { inspectedRfNode = null },
                        onLoadJsonClick = meshActions.onLoadJsonClick,
                        onClearJsonClick = meshActions.onClearJsonClick,
                        onClearAllWithManualClick = meshActions.onClearAllWithManualClick,
                        onToggleShowMqtt = meshActions.onToggleShowMqtt,
                        onToggleRfSightlines = meshActions.onToggleRfSightlines,
                        onToggleFavoritesOnly = meshActions.onToggleFavoritesOnly,
                        onToggleHwModelFilter = meshActions.onToggleHwModelFilter,
                        onToggleRoleFilter = meshActions.onToggleRoleFilter,
                        onResetAllFilters = meshActions.onResetAllFilters,
                        onToggleLosMode = meshActions.onToggleLosMode,
                        onToggleMeasureMode = meshActions.onToggleMeasureMode,
                        onToggleAddNodeMode = meshActions.onToggleAddNodeMode,
                        onOpenDirectLinksSheet = meshActions.onOpenDirectLinksSheet,
                        onCloseDirectLinksSheet = meshActions.onCloseDirectLinksSheet,
                        onToggleDirectLinksSort = meshActions.onToggleDirectLinksSort,
                        onMeasureDirectLinkFromSelf = meshActions.onMeasureDirectLinkFromSelf,
                        onAnalyzeLosBetweenNodes = meshActions.onAnalyzeLosBetweenNodes,
                        onOpenNoGpsSheet = meshActions.onOpenNoGpsSheet,
                        onCloseNoGpsSheet = meshActions.onCloseNoGpsSheet,
                        onNoGpsSearchQueryChange = meshActions.onNoGpsSearchQueryChange,
                        onSelectNode = meshActions.onSelectNode,
                        onOpenEditNodeEditor = meshActions.onOpenEditNodeEditor,
                        onStartCoordinatePickForEditor = meshActions.onStartCoordinatePickForEditor,
                        onDismissNodeEditor = meshActions.onDismissNodeEditor,
                        onSaveNodeEditorDraft = meshActions.onSaveNodeEditorDraft,
                        onDeleteManualNode = meshActions.onDeleteManualNode,
                        onRevertNodeEdits = meshActions.onRevertNodeEdits,
                        onBuildNodeDbBackupJson = meshActions.onBuildNodeDbBackupJson,
                        onConfirmPendingImportMerge = meshActions.onConfirmPendingImportMerge,
                        onConfirmPendingImportReplace = meshActions.onConfirmPendingImportReplace,
                        onDismissPendingImport = meshActions.onDismissPendingImport,
                        onSelectBaseLayer = meshActions.onSelectBaseLayer,
                        onFitBounds = meshActions.onFitBounds,
                        onSelectOverlayContinuityMode = meshActions.onSelectOverlayContinuityMode,
                        onToggleAggregateCoverage = meshActions.onToggleAggregateCoverage,
                        onUpdateAggregateRadiusKm = meshActions.onUpdateAggregateRadiusKm,
                        onUpdateAggregateMastMeters = meshActions.onUpdateAggregateMastMeters,
                        onRefreshAggregateCoverage = meshActions.onRefreshAggregateCoverage,
                        onCancelAggregateCoverageCompute = meshActions.onCancelAggregateCoverageCompute,
                        onDismissAggregateCoverageError = meshActions.onDismissAggregateCoverageError,
                        onViewshedOpacityChange = viewshedActions.onOpacityChange,
                        onToggleSavedLayerEnabled = viewshedActions.onToggleSavedLayerEnabled,
                        onToggleSavedLayerRenderMode = viewshedActions.onToggleSavedLayerRenderMode,
                        onDeleteSavedLayer = viewshedActions.onDeleteSavedLayer,
                        suggestedLayerName = viewshedActions.suggestedLayerName,
                        suggestedLayerTint = viewshedActions.suggestedLayerTint,
                        onSaveLayer = viewshedActions.onSaveLayer,
                        onClearCurrentViewshed = viewshedActions.onClearCurrentViewshed,
                        onClearAllViewshed = viewshedActions.onClearAllAndStartFresh,
                        onClearBridgeOverlay = bridgeActions.onClearOverlay,
                        onResetBridgeAll = bridgeActions.onResetAll,
                        onAssignViewshedTx = if (activeMode == WorkspaceMode.MESH) meshActions.onAssignViewshedFromNode else viewshedActions.onSelectExistingNode,
                        onAssignViewshedRx = if (activeMode == WorkspaceMode.MESH) meshActions.onAssignViewshedRx else viewshedActions.onSelectRxNode,
                        onAssignBridgeTx = if (activeMode == WorkspaceMode.MESH) meshActions.onAssignBridgeA else bridgeActions.onSelectTxNode,
                        onAssignBridgeRx = if (activeMode == WorkspaceMode.MESH) meshActions.onAssignBridgeB else bridgeActions.onSelectRxNode,
                        onInspectFresnelSightline = meshActions.onInspectFresnelSightline,
                        showOfflineMapsDialog = showOfflineMapsDialog,
                        offlineDemState = offlineDemState,
                        viewportState = viewportState,
                        onDismissOfflineMaps = {
                            showOfflineMapsDialog = false
                            offlineActions.onDismissMessages()
                        },
                        onToggleOfflineOnly = offlineActions.onToggleOfflineOnly,
                        onDownloadPreset = offlineActions.onDownloadPreset,
                        onDownloadViewport = offlineActions.onDownloadViewport,
                        onCancelOfflineDownload = offlineActions.onCancelDownload,
                        onClearOfflineCache = offlineActions.onClearCache,
                        onAlignNode = openAlignForNode
                    )

                    if (showAlignmentHud) {
                        AntennaAlignmentHudOverlay(
                            target = alignmentTarget ?: availableAlignmentTargets.firstOrNull(),
                            availableTargets = availableAlignmentTargets,
                            fallbackOriginNode = meshState.selfNode,
                            onSelectTarget = { alignmentTarget = it },
                            onClose = { showAlignmentHud = false },
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 14.dp, vertical = 72.dp)
                                .widthIn(max = 420.dp)
                        )
                    }

                    if (showLiveSyncSheet) {
                        LiveSyncBottomSheet(
                            uiState = liveSyncState,
                            onUpdateConfig = liveSyncActions.onUpdateConfig,
                            onConnect = liveSyncActions.onConnect,
                            onDisconnect = liveSyncActions.onDisconnect,
                            onDismissError = liveSyncActions.onDismissError,
                            onDismiss = { showLiveSyncSheet = false }
                        )
                    }
                }
            }

            if (isSplitPane) {
                Row(modifier = Modifier.fillMaxSize()) {
                    mapStage(
                        Modifier
                            .weight(0.62f)
                            .fillMaxHeight()
                    )
                    VerticalDivider(color = TacticalOutline)
                    Surface(
                        color = TacticalSurface,
                        modifier = Modifier
                            .weight(0.38f)
                            .fillMaxHeight()
                            .testTag("workspace_side_panel")
                    ) {
                        when (activeMode) {
                            WorkspaceMode.MESH -> MeshModeSidePanel(
                                uiState = meshState,
                                activeMapView = activeMapView,
                                savedLayersBadgeCount = layersBadgeCount,
                                onLoadJsonClick = meshActions.onLoadJsonClick,
                                onSelectNode = meshActions.onSelectNode,
                                onOpenFilters = { showFiltersSheet = true },
                                onOpenSavedLayers = { showSavedLayersSheet = true },
                                onOpenDirectLinksSheet = meshActions.onOpenDirectLinksSheet,
                                onOpenNoGpsSheet = meshActions.onOpenNoGpsSheet,
                                onFitBounds = meshActions.onFitBounds,
                                onToggleAddNodeMode = meshActions.onToggleAddNodeMode,
                                onOpenNewNodeEditor = meshActions.onOpenNewNodeEditor,
                                onOpenEditNodeEditor = meshActions.onOpenEditNodeEditor,
                                onAssignViewshedTx = meshActions.onAssignViewshedFromNode,
                                onAssignViewshedRx = meshActions.onAssignViewshedRx,
                                onAssignBridgeTx = meshActions.onAssignBridgeA,
                                onAssignBridgeRx = meshActions.onAssignBridgeB,
                                onInspectFresnelSightline = meshActions.onInspectFresnelSightline,
                                onToggleMeasureMode = meshActions.onToggleMeasureMode,
                                onClearMeasureAnchors = meshActions.onClearMeasureAnchors,
                                onUseSelfAsAnchorA = meshActions.onUseSelfAsAnchorA,
                                onAnalyzeLosBetweenNodes = meshActions.onAnalyzeLosBetweenNodes,
                                onToggleLosMode = meshActions.onToggleLosMode,
                                onToggleLosDrawerExpanded = meshActions.onToggleLosDrawerExpanded,
                                onLosTxHeightChange = meshActions.onLosTxHeightChange,
                                onLosRxHeightChange = meshActions.onLosRxHeightChange,
                                onLosFrequencyChange = meshActions.onLosFrequencyChange,
                                onUseSelfAsLosPointA = meshActions.onUseSelfAsLosPointA,
                                onSwapLosEndpoints = meshActions.onSwapLosEndpoints,
                                onClearLosEndpoints = meshActions.onClearLosEndpoints,
                                onRetryLosElevation = meshActions.onRetryLosElevation,
                                onUseFlatTerrainFallback = meshActions.onUseFlatTerrainFallback,
                                onLosTxPowerChange = meshActions.onLosTxPowerChange,
                                onLosTxGainChange = meshActions.onLosTxGainChange,
                                onLosRxGainChange = meshActions.onLosRxGainChange,
                                onLosSensitivityChange = meshActions.onLosSensitivityChange,
                                onApplyLosMastFix = meshActions.onApplyLosMastFix,
                                onOpenOfflineMaps = { showOfflineMapsDialog = true },
                                onSelectFadeMarginPreset = onSelectFadeMarginPreset,
                                onAlignNode = openAlignForNode
                            )
                            WorkspaceMode.VIEWSHED -> ViewshedModeSidePanel(
                                uiState = viewshedState,
                                plottedNodes = plottedGpsNodes,
                                onSelectTxNode = viewshedActions.onSelectExistingNode,
                                onSelectRxNode = viewshedActions.onSelectRxNode,
                                onAntennaHeightChange = viewshedActions.onAntennaHeightChange,
                                onRxAntennaHeightChange = viewshedActions.onRxAntennaHeightChange,
                                onRadiusKmChange = viewshedActions.onRadiusKmChange,
                                onFrequencyChange = viewshedActions.onFrequencyChange,
                                onTxPowerChange = viewshedActions.onTxPowerChange,
                                onAntennaGainChange = viewshedActions.onAntennaGainChange,
                                onSensitivityChange = viewshedActions.onSensitivityChange,
                                onOpacityChange = viewshedActions.onOpacityChange,
                                onCalculateCoverage = viewshedActions.onCalculateCoverage,
                                onOpenSaveLayerDialog = { showSaveLayerDialog = true },
                                onTogglePinSnapshot = viewshedActions.onTogglePinSnapshot,
                                onToggleShowSnapshotOnMap = viewshedActions.onToggleShowSnapshotOnMap,
                                onSendToBridge = viewshedActions.onSendToBridge,
                                onApplyMastFix = viewshedActions.onApplyMastFix,
                                onInspectObstructedNode = viewshedActions.onInspectObstructedNode,
                                onDismissError = viewshedActions.onDismissError,
                                onSelectFadeMarginPreset = onSelectFadeMarginPreset
                            )
                            WorkspaceMode.BRIDGE -> BridgeModeSidePanel(
                                uiState = bridgeState,
                                plottedNodes = plottedGpsNodes,
                                obstructions = bridgeObstructions,
                                onSelectTxNode = bridgeActions.onSelectTxNode,
                                onSelectRxNode = bridgeActions.onSelectRxNode,
                                onTxHeightChange = bridgeActions.onTxHeightChange,
                                onRxHeightChange = bridgeActions.onRxHeightChange,
                                onRelayHeightChange = bridgeActions.onRelayHeightChange,
                                onRadiusKmChange = bridgeActions.onRadiusKmChange,
                                onFrequencyChange = bridgeActions.onFrequencyChange,
                                onTxPowerChange = bridgeActions.onTxPowerChange,
                                onTxAntennaGainChange = bridgeActions.onTxAntennaGainChange,
                                onTxSensitivityChange = bridgeActions.onTxSensitivityChange,
                                onRxPowerChange = bridgeActions.onRxPowerChange,
                                onRxAntennaGainChange = bridgeActions.onRxAntennaGainChange,
                                onRxSensitivityChange = bridgeActions.onRxSensitivityChange,
                                onRelayPowerChange = bridgeActions.onRelayPowerChange,
                                onRelayAntennaGainChange = bridgeActions.onRelayAntennaGainChange,
                                onRelaySensitivityChange = bridgeActions.onRelaySensitivityChange,
                                onOpacityChange = bridgeActions.onOpacityChange,
                                onCalculateBridge = bridgeActions.onCalculateBridge,
                                onSelectCandidate = bridgeActions.onSelectCandidate,
                                onSelectDrawerTab = bridgeActions.onSelectDrawerTab,
                                onSelectObstruction = bridgeActions.onSelectObstruction,
                                onSelectProfileMode = bridgeActions.onSelectProfileMode,
                                onScrubProfileSample = bridgeActions.onScrubProfileSample,
                                onRetryLiveProfile = bridgeActions.onRetryLiveProfile,
                                onDismissError = bridgeActions.onDismissError,
                                onOpenOfflineMaps = { showOfflineMapsDialog = true },
                                onSelectFadeMarginPreset = onSelectFadeMarginPreset,
                                onAlignCandidate = openAlignForCandidate
                            )
                        }
                    }
                }
            } else {
                mapStage(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun FloatingCompassAlignButton(
    isActive: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (isActive) NodeDirectGreen.copy(alpha = 0.24f) else TacticalSurface.copy(alpha = 0.94f),
        border = BorderStroke(
            width = 1.dp,
            color = if (isActive) NodeDirectGreen else TacticalOutline
        ),
        shadowElevation = 6.dp,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag("compass_align_fab")
    ) {
        Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Explore,
                contentDescription = stringResource(R.string.compass_align_fab_cd),
                tint = if (isActive) NodeDirectGreen else TextPrimary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun WorkspaceNavigationBar(
    activeMode: WorkspaceMode,
    onSelectMode: (WorkspaceMode) -> Unit
) {
    NavigationBar(
        containerColor = TacticalSurface,
        contentColor = TextPrimary
    ) {
        val navColors = NavigationBarItemDefaults.colors(
            selectedIconColor = NodeDirectGreen,
            selectedTextColor = NodeDirectGreen,
            indicatorColor = TacticalSurfaceBright,
            unselectedIconColor = TextSecondary,
            unselectedTextColor = TextSecondary
        )
        NavigationBarItem(
            selected = activeMode == WorkspaceMode.MESH,
            onClick = { onSelectMode(WorkspaceMode.MESH) },
            icon = {
                Icon(
                    imageVector = if (activeMode == WorkspaceMode.MESH) Icons.Filled.Map else Icons.Outlined.Map,
                    contentDescription = stringResource(R.string.nav_mesh_map)
                )
            },
            label = { Text(stringResource(R.string.nav_mesh_map)) },
            colors = navColors,
            modifier = Modifier.testTag("nav_tab_map")
        )
        NavigationBarItem(
            selected = activeMode == WorkspaceMode.VIEWSHED,
            onClick = { onSelectMode(WorkspaceMode.VIEWSHED) },
            icon = {
                Icon(
                    imageVector = if (activeMode == WorkspaceMode.VIEWSHED) Icons.Filled.CellTower else Icons.Outlined.CellTower,
                    contentDescription = stringResource(R.string.nav_viewshed)
                )
            },
            label = { Text(stringResource(R.string.nav_viewshed)) },
            colors = navColors,
            modifier = Modifier.testTag("nav_tab_viewshed")
        )
        NavigationBarItem(
            selected = activeMode == WorkspaceMode.BRIDGE,
            onClick = { onSelectMode(WorkspaceMode.BRIDGE) },
            icon = {
                Icon(
                    imageVector = if (activeMode == WorkspaceMode.BRIDGE) Icons.Filled.Hub else Icons.Outlined.Hub,
                    contentDescription = stringResource(R.string.nav_bridge_finder)
                )
            },
            label = { Text(stringResource(R.string.nav_bridge_finder)) },
            colors = navColors,
            modifier = Modifier.testTag("nav_tab_bridge")
        )
    }
}

private data class PersistentMapCanvasProps(
    val mode: WorkspaceMode,
    val continuity: OverlayContinuityMode,
    val meshState: NodeMapUiState,
    val viewshedState: ViewshedUiState,
    val bridgeState: BridgeUiState,
    val viewportState: MapViewportState,
    val dropId: Long,
    val allPlottedGpsNodes: List<MeshNode>,
    val bridgeObstructions: List<us.lemesh.nodedb.data.BridgeObstructionItem>
)
