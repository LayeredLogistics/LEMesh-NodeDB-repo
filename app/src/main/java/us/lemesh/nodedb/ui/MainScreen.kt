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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import us.lemesh.nodedb.R
import us.lemesh.nodedb.data.MapExportEngine
import us.lemesh.nodedb.data.MeshNode
import us.lemesh.nodedb.data.SavedViewshedLayer
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
import us.lemesh.nodedb.ui.theme.TacticalSurfaceBright
import us.lemesh.nodedb.ui.theme.TacticalSurfaceElevated
import us.lemesh.nodedb.ui.theme.TextMuted
import us.lemesh.nodedb.ui.theme.TextPrimary
import us.lemesh.nodedb.ui.theme.TextSecondary
import org.osmdroid.views.MapView

@Composable
fun MainScreen(
    uiState: NodeMapUiState,
    onLoadJsonClick: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onToggleShowMqtt: () -> Unit,
    onToggleRfSightlines: () -> Unit,
    onToggleFavoritesOnly: () -> Unit,
    onSelectBaseLayer: (MapBaseLayer) -> Unit,
    onOpenNoGpsSheet: () -> Unit,
    onCloseNoGpsSheet: () -> Unit,
    onNoGpsSearchQueryChange: (String) -> Unit,
    onSelectNode: (MeshNode?) -> Unit,
    onMapNodeClick: (MeshNode) -> Unit,
    onToggleMeasureMode: () -> Unit,
    onClearMeasureAnchors: () -> Unit,
    onUseSelfAsAnchorA: () -> Unit,
    onOpenDirectLinksSheet: () -> Unit,
    onCloseDirectLinksSheet: () -> Unit,
    onToggleDirectLinksSort: () -> Unit,
    onMeasureDirectLinkFromSelf: (MeshNode) -> Unit,
    onToggleLosMode: () -> Unit,
    onMapCoordinateTap: (Double, Double) -> Unit,
    onToggleLosDrawerExpanded: () -> Unit,
    onLosTxHeightChange: (String) -> Unit,
    onLosRxHeightChange: (String) -> Unit,
    onLosFrequencyChange: (String) -> Unit,
    onUseSelfAsLosPointA: () -> Unit,
    onSwapLosEndpoints: () -> Unit,
    onClearLosEndpoints: () -> Unit,
    onRetryLosElevation: () -> Unit,
    onUseFlatTerrainFallback: () -> Unit,
    onAnalyzeLosBetweenNodes: (MeshNode, MeshNode) -> Unit,
    onFitBounds: () -> Unit,
    onDismissError: () -> Unit,
    savedViewshedLayers: List<SavedViewshedLayer> = emptyList(),
    viewshedOverlayOpacity: Float = 0.68f,
    onViewshedOpacityChange: (Float) -> Unit = {},
    onToggleSavedLayerEnabled: (String) -> Unit = {},
    onToggleSavedLayerRenderMode: (String) -> Unit = {},
    onDeleteSavedLayer: (String) -> Unit = {},
    onClearJsonClick: () -> Unit = {},
    onAssignViewshedTx: (MeshNode) -> Unit = {},
    onAssignViewshedRx: (MeshNode) -> Unit = {},
    onAssignBridgeTx: (MeshNode) -> Unit = {},
    onAssignBridgeRx: (MeshNode) -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onSetTopHeaderDismissed: (Boolean) -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onToggleHudMinimized: () -> Unit = {},
    onStartupZoomStarted: () -> Unit = {},
    onSaveCameraState: (Double, Double, Double) -> Unit = { _, _, _ -> },
    onToggleAggregateCoverage: () -> Unit = {},
    onUpdateAggregateRadiusKm: (Int) -> Unit = {},
    onUpdateAggregateMastMeters: (Double) -> Unit = {},
    onRefreshAggregateCoverage: () -> Unit = {},
    onCancelAggregateCoverageCompute: () -> Unit = {},
    onDismissAggregateCoverageError: () -> Unit = {},
    onSightlineClick: (MeshNode) -> Unit = onMapNodeClick,
    onInspectFresnelSightline: (MeshNode) -> Unit = {},
    onLosTxPowerChange: (String) -> Unit = {},
    onLosTxGainChange: (String) -> Unit = {},
    onLosRxGainChange: (String) -> Unit = {},
    onLosSensitivityChange: (String) -> Unit = {},
    onApplyLosMastFix: () -> Unit = {},
    onToggleAddNodeMode: () -> Unit = {},
    onOpenNewNodeEditor: (Double?, Double?) -> Unit = { _, _ -> },
    onOpenEditNodeEditor: (MeshNode) -> Unit = {},
    onStartCoordinatePickForEditor: (NodeEditorDraft) -> Unit = {},
    onDismissNodeEditor: () -> Unit = {},
    onSaveNodeEditorDraft: (NodeEditorDraft) -> String? = { null },
    onDeleteManualNode: (MeshNode) -> Unit = {},
    onRevertNodeEdits: (MeshNode) -> Unit = {},
    onBuildNodeDbBackupJson: (() -> String)? = null,
    onClearAllWithManualClick: (() -> Unit)? = null,
    onConfirmPendingImportMerge: () -> Unit = {},
    onConfirmPendingImportReplace: () -> Unit = {},
    onDismissPendingImport: () -> Unit = {},
    onToggleHwModelFilter: (String) -> Unit = {},
    onToggleRoleFilter: (String) -> Unit = {},
    onResetAllFilters: (() -> Unit)? = null,
    onSelectOverlayContinuityMode: ((OverlayContinuityMode) -> Unit)? = null
) {
    var showSavedLayersSheet by remember { mutableStateOf(false) }
    var showFiltersSheet by remember { mutableStateOf(false) }
    var showMapToolsSheet by remember { mutableStateOf(false) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var alsoClearManualNodes by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var activeMapView by remember { mutableStateOf<MapView?>(null) }

    val activeFilterCount = (if (!uiState.showMqtt) 1 else 0) +
        (if (!uiState.showRfSightlines) 1 else 0) +
        (if (uiState.favoritesOnly) 1 else 0) +
        uiState.selectedHwModels.size +
        uiState.selectedRoles.size +
        (if (uiState.isLosMode) 1 else 0) +
        (if (uiState.isMeasureMode) 1 else 0) +
        (if (uiState.isAddNodeMode) 1 else 0)
    val savedLayersActiveCount = savedViewshedLayers.count { it.isEnabled }
    val layersBadgeCount = savedLayersActiveCount + (if (uiState.isAggregateCoverageEnabled) 1 else 0)
    val hasFilteredSubset = uiState.hasDatasetOrCustomNodes && (
        uiState.searchQuery.isNotBlank() ||
            !uiState.showMqtt ||
            uiState.favoritesOnly ||
            uiState.selectedHwModels.isNotEmpty() ||
            uiState.selectedRoles.isNotEmpty()
        )

    val resetVisibilityFilters = {
        if (onResetAllFilters != null) {
            onResetAllFilters()
        } else {
            if (uiState.searchQuery.isNotBlank()) onSearchQueryChange("")
            if (!uiState.showMqtt) onToggleShowMqtt()
            if (!uiState.showRfSightlines) onToggleRfSightlines()
            if (uiState.favoritesOnly) onToggleFavoritesOnly()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = TacticalCanvas,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            MeshRfMapCanvas(
                mode = WorkspaceMode.MESH,
                baseLayer = uiState.baseLayer,
                overlayContinuityMode = uiState.overlayContinuityMode,
                nodes = uiState.filteredMapNodes,
                selfNode = uiState.selfNode,
                showRfSightlines = uiState.showRfSightlines,
                isMeasureMode = uiState.isMeasureMode,
                measureAnchorA = uiState.measureAnchorA,
                measureAnchorB = uiState.measureAnchorB,
                isLosMode = uiState.isLosMode,
                isAddNodeMode = uiState.isAddNodeMode,
                losPointA = uiState.losPointA,
                losPointB = uiState.losPointB,
                losResult = uiState.losResult,
                sightlineFresnelResults = uiState.sightlineFresnelResults,
                aggregateCoverageResult = uiState.aggregateCoverageResult,
                isAggregateCoverageEnabled = uiState.isAggregateCoverageEnabled,
                savedViewshedLayers = savedViewshedLayers,
                overlayOpacity = viewshedOverlayOpacity,
                fitBoundsRequestId = uiState.fitBoundsRequestId,
                startupTargetLat = uiState.startupTargetLat,
                startupTargetLon = uiState.startupTargetLon,
                hasStartedStartupZoom = uiState.hasStartedStartupZoom,
                savedCameraLat = uiState.savedCameraLat,
                savedCameraLon = uiState.savedCameraLon,
                savedCameraZoom = uiState.savedCameraZoom,
                savedCameraBearingDeg = uiState.savedCameraBearingDeg,
                onMeshNodeClick = onMapNodeClick,
                onSightlineClick = onSightlineClick,
                onMapCoordinateTap = onMapCoordinateTap,
                onStartupZoomStarted = onStartupZoomStarted,
                onSaveCameraState = { lat, lon, zoom, _ -> onSaveCameraState(lat, lon, zoom) },
                onMapViewReady = { activeMapView = it },
                modifier = Modifier.fillMaxSize()
            )

            // Compact Search Bar + Filters & Layers triggers
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CompactNodeMapSearchBar(
                    searchQuery = uiState.searchQuery,
                    activeFilterCount = activeFilterCount,
                    savedLayersActiveCount = layersBadgeCount,
                    onSearchQueryChange = onSearchQueryChange,
                    onOpenFilters = { showFiltersSheet = true },
                    onOpenLayers = { showSavedLayersSheet = true }
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!uiState.hasLoadedFile && !uiState.isLoading) {
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
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.action_load_json),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (!uiState.isAddNodeMode) {
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
                                Icon(
                                    imageVector = Icons.Default.AddLocationAlt,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
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
                            onClick = resetVisibilityFilters,
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
                                    text = "${uiState.filteredMapNodes.size}/${uiState.plottedGpsCount} GPS",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NodeMultiHopSky
                                )
                                Text(
                                    text = "• Reset",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = uiState.isAggregateCoverageLoading) {
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
                            CircularProgressIndicator(
                                modifier = Modifier.size(15.dp),
                                color = NodeDirectGreen,
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = "${stringResource(R.string.aggregate_coverage_computing)} ${uiState.aggregateCoverageProgressDone}/${uiState.aggregateCoverageProgressTotal}",
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 11.sp,
                                color = TextPrimary
                            )
                            TextButton(
                                onClick = onCancelAggregateCoverageCompute,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("aggregate_progress_cancel_button")
                            ) {
                                Text(
                                    text = stringResource(R.string.action_cancel),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = uiState.isSightlinesSolving) {
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
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                color = NodeMultiHopSky,
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = stringResource(
                                    R.string.fresnel_sightlines_solving,
                                    uiState.sightlinesSolvedCount,
                                    uiState.sightlinesTotalCount
                                ),
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 11.sp,
                                color = TextPrimary
                            )
                        }
                    }
                }

                AnimatedVisibility(visible = uiState.errorMessage != null) {
                    ErrorBanner(
                        message = uiState.errorMessage.orEmpty(),
                        onDismiss = onDismissError
                    )
                }

                AnimatedVisibility(visible = uiState.aggregateCoverageError != null) {
                    ErrorBanner(
                        message = uiState.aggregateCoverageError.orEmpty(),
                        onDismiss = onDismissAggregateCoverageError
                    )
                }
            }

            if (uiState.isLoading) {
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
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = NodeDirectGreen,
                            strokeWidth = 2.5.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Parsing NodeDB JSON…",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                    }
                }
            }

            // Bottom Map Tools FAB + Active Field Tool Drawer (LoS or Ruler)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    FloatingMapToolsButton(
                        onClick = { showMapToolsSheet = true },
                        testTag = "map_tools_fab"
                    )
                }

                when {
                    uiState.isAddNodeMode -> {
                        AddNodeModeBanner(
                            isPickingForExistingDraft = uiState.editingDraft?.isPickingCoordinateOnMap == true,
                            onUseMapCenter = {
                                val center = activeMapView?.mapCenter
                                onOpenNewNodeEditor(center?.latitude, center?.longitude)
                            },
                            onOpenManualForm = { onOpenNewNodeEditor(null, null) },
                            onDone = onToggleAddNodeMode,
                            modifier = Modifier.widthIn(max = 580.dp)
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
                            modifier = Modifier.widthIn(max = 620.dp),
                            txPowerText = uiState.losTxPowerText,
                            txGainText = uiState.losTxGainText,
                            rxGainText = uiState.losRxGainText,
                            sensitivityText = uiState.losSensitivityText,
                            onTxPowerChange = onLosTxPowerChange,
                            onTxGainChange = onLosTxGainChange,
                            onRxGainChange = onLosRxGainChange,
                            onSensitivityChange = onLosSensitivityChange,
                            onApplyMastFix = onApplyLosMastFix
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
                                if (a != null && b != null) {
                                    onAnalyzeLosBetweenNodes(a, b)
                                }
                            },
                            onOpenDirectLinksSheet = onOpenDirectLinksSheet,
                            onClear = onClearMeasureAnchors,
                            onDone = onToggleMeasureMode,
                            modifier = Modifier.widthIn(max = 580.dp)
                        )
                    }
                }
            }
        }
    }

    if (showFiltersSheet) {
        NodeMapFiltersSheet(
            uiState = uiState,
            onToggleShowMqtt = onToggleShowMqtt,
            onToggleRfSightlines = onToggleRfSightlines,
            onToggleFavoritesOnly = onToggleFavoritesOnly,
            onToggleLosMode = onToggleLosMode,
            onToggleMeasureMode = onToggleMeasureMode,
            onOpenDirectLinksSheet = onOpenDirectLinksSheet,
            onOpenNoGpsSheet = onOpenNoGpsSheet,
            onLoadJsonClick = onLoadJsonClick,
            onClearJsonClick = {
                alsoClearManualNodes = !uiState.hasLoadedFile && uiState.manualNodeCount > 0
                showClearConfirmDialog = true
            },
            onResetFilters = resetVisibilityFilters,
            onToggleAggregateCoverage = onToggleAggregateCoverage,
            onToggleAddNodeMode = onToggleAddNodeMode,
            onToggleHwModelFilter = onToggleHwModelFilter,
            onToggleRoleFilter = onToggleRoleFilter,
            onDismiss = { showFiltersSheet = false }
        )
    }

    if (showMapToolsSheet) {
        val savedSummary = if (savedViewshedLayers.isNotEmpty() || uiState.isAggregateCoverageEnabled) {
            "${stringResource(R.string.filter_saved_layers)} ($layersBadgeCount)"
        } else {
            stringResource(R.string.filter_saved_layers)
        }
        val showNodeMapRssi = uiState.isAggregateCoverageEnabled ||
            savedViewshedLayers.any { it.isEnabled && it.renderMode == us.lemesh.nodedb.data.LayerRenderMode.RSSI }
        MapToolsBottomSheet(
            baseLayer = uiState.baseLayer,
            legendMode = MapToolsLegendMode.NODE_MAP,
            sensitivityDbm = uiState.aggregateCoverageResult?.sensitivityDbm ?: -130.0,
            testTagPrefix = "map",
            onSelectBaseLayer = onSelectBaseLayer,
            onExport = { showExportDialog = true },
            onFitBounds = if (uiState.plottedGpsCount > 0) onFitBounds else null,
            onLoadJson = onLoadJsonClick,
            onClearJson = if (uiState.hasDatasetOrCustomNodes) {
                {
                    alsoClearManualNodes = !uiState.hasLoadedFile && uiState.manualNodeCount > 0
                    showClearConfirmDialog = true
                }
            } else {
                null
            },
            savedLayersSummary = savedSummary,
            onOpenSavedLayers = { showSavedLayersSheet = true },
            showNodeMapRssiLegend = showNodeMapRssi,
            onAddNode = onToggleAddNodeMode,
            onDismiss = { showMapToolsSheet = false }
        )
    }

    if (uiState.showDirectLinksSheet) {
        DirectRfLinksBottomSheet(
            links = uiState.directRfLinksWithGps,
            selfNode = uiState.selfNode,
            sortDescending = uiState.directLinksSortDescending,
            onToggleSort = onToggleDirectLinksSort,
            onInspectNode = { node ->
                onCloseDirectLinksSheet()
                onSelectNode(node)
            },
            onMeasureOnMap = onMeasureDirectLinkFromSelf,
            onAnalyzeLos = { targetNode ->
                val self = uiState.selfNode
                if (self != null) {
                    onAnalyzeLosBetweenNodes(self, targetNode)
                }
            },
            onDismiss = onCloseDirectLinksSheet
        )
    }

    if (uiState.showNoGpsSheet) {
        NoGpsNodesBottomSheet(
            nodes = uiState.filteredNoGpsNodes,
            searchQuery = uiState.noGpsSearchQuery,
            onSearchQueryChange = onNoGpsSearchQueryChange,
            onNodeClick = { node ->
                onCloseNoGpsSheet()
                onSelectNode(node)
            },
            onDismiss = onCloseNoGpsSheet
        )
    }

    uiState.selectedNode?.let { selected ->
        NodeInspectorBottomSheet(
            node = selected,
            selfNode = uiState.selfNode,
            onDismiss = { onSelectNode(null) },
            onAssignViewshedTx = onAssignViewshedTx,
            onAssignViewshedRx = onAssignViewshedRx,
            onAssignBridgeTx = onAssignBridgeTx,
            onAssignBridgeRx = onAssignBridgeRx,
            onInspectFresnelSightline = if (uiState.selfNode?.hasValidGps == true && !selected.isSelf && selected.hasValidGps) {
                onInspectFresnelSightline
            } else {
                null
            },
            onEditNode = onOpenEditNodeEditor
        )
    }

    val activeEditorDraft = uiState.editingDraft
    if (activeEditorDraft != null && !activeEditorDraft.isPickingCoordinateOnMap) {
        val targetNode = if (!activeEditorDraft.isCreatingNew && activeEditorDraft.originalKey != null) {
            uiState.allNodes.firstOrNull { it.stableKey == activeEditorDraft.originalKey }
        } else {
            null
        }
        NodeEditorBottomSheet(
            initialDraft = activeEditorDraft,
            onSave = onSaveNodeEditorDraft,
            onPickOnMap = onStartCoordinatePickForEditor,
            onDeleteManual = if (!activeEditorDraft.isCreatingNew && activeEditorDraft.isManual && targetNode != null) {
                {
                    onDeleteManualNode(targetNode)
                    onDismissNodeEditor()
                }
            } else {
                null
            },
            onRevertEdits = if (!activeEditorDraft.isCreatingNew && !activeEditorDraft.isManual && activeEditorDraft.isEdited && targetNode != null) {
                {
                    onRevertNodeEdits(targetNode)
                    onDismissNodeEditor()
                }
            } else {
                null
            },
            onDismiss = onDismissNodeEditor
        )
    }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            containerColor = TacticalSurface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = {
                Text(
                    text = stringResource(R.string.clear_json_dialog_title),
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.clear_json_dialog_message),
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (uiState.manualNodeCount > 0 && uiState.hasLoadedFile && onClearAllWithManualClick != null) {
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
                                colors = CheckboxDefaults.colors(
                                    checkedColor = AlertErrorRed,
                                    uncheckedColor = TextSecondary
                                )
                            )
                            Text(
                                text = "Also delete ${uiState.manualNodeCount} manual node(s)",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirmDialog = false
                        if (alsoClearManualNodes && onClearAllWithManualClick != null) {
                            onClearAllWithManualClick()
                        } else {
                            onClearJsonClick()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AlertErrorRed,
                        contentColor = TextPrimary
                    ),
                    modifier = Modifier.testTag("confirm_clear_json_button")
                ) {
                    Text(
                        text = stringResource(R.string.action_clear_confirm),
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearConfirmDialog = false },
                    modifier = Modifier.testTag("cancel_clear_json_button")
                ) {
                    Text(
                        text = stringResource(R.string.action_cancel),
                        color = TextSecondary
                    )
                }
            }
        )
    }

    val pendingImport = uiState.pendingImport
    if (pendingImport != null) {
        AlertDialog(
            onDismissRequest = onDismissPendingImport,
            containerColor = TacticalSurface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = {
                Text(
                    text = stringResource(R.string.import_db_dialog_title),
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "${pendingImport.fileName} (${pendingImport.incomingResult.totalCount} nodes: ${pendingImport.matchedExistingCount} matching, ${pendingImport.newNodesCount} new).",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary
                    )
                    Text(
                        text = "Merge refreshes matching node coordinates and telemetry while keeping your custom colors, notes, edited specs, and manual nodes. Replace swaps the imported database.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.size(2.dp))
                    Button(
                        onClick = onConfirmPendingImportMerge,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NodeDirectGreen,
                            contentColor = TacticalCanvas
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("import_merge_button")
                    ) {
                        Text(
                            text = stringResource(R.string.import_db_merge_button),
                            fontWeight = FontWeight.Bold
                        )
                    }
                    OutlinedButton(
                        onClick = onConfirmPendingImportReplace,
                        border = BorderStroke(1.dp, NodeMultiHopSky.copy(alpha = 0.7f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = NodeMultiHopSky
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("import_replace_button")
                    ) {
                        Text(
                            text = stringResource(R.string.import_db_replace_button),
                            fontWeight = FontWeight.SemiBold
                        )
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
                    Text(
                        text = stringResource(R.string.action_cancel),
                        color = TextSecondary
                    )
                }
            }
        )
    }

    if (showSavedLayersSheet) {
        SavedViewshedLayersSheet(
            layers = savedViewshedLayers,
            overlayOpacity = viewshedOverlayOpacity,
            onOpacityChange = onViewshedOpacityChange,
            onToggleEnabled = onToggleSavedLayerEnabled,
            onToggleRenderMode = onToggleSavedLayerRenderMode,
            onDeleteLayer = onDeleteSavedLayer,
            onDismiss = { showSavedLayersSheet = false },
            overlayContinuityMode = uiState.overlayContinuityMode,
            onSelectOverlayContinuityMode = onSelectOverlayContinuityMode,
            aggregateConfig = AggregateCoverageSheetConfig(
                isEnabled = uiState.isAggregateCoverageEnabled,
                radiusKm = uiState.aggregateCoverageRadiusKm,
                mastHeightMeters = uiState.aggregateCoverageMastMeters,
                eligibleRfNodeCount = uiState.eligibleAggregateRfNodeCount,
                isLoading = uiState.isAggregateCoverageLoading,
                progressDone = uiState.aggregateCoverageProgressDone,
                progressTotal = uiState.aggregateCoverageProgressTotal,
                isStale = uiState.isAggregateCoverageStale,
                errorMessage = uiState.aggregateCoverageError,
                result = uiState.aggregateCoverageResult,
                onToggleEnabled = onToggleAggregateCoverage,
                onRadiusChange = onUpdateAggregateRadiusKm,
                onMastHeightChange = onUpdateAggregateMastMeters,
                onRefresh = onRefreshAggregateCoverage,
                onCancelCompute = onCancelAggregateCoverageCompute,
                onDismissError = onDismissAggregateCoverageError
            )
        )
    }

    MapExportHost(
        visible = showExportDialog,
        filePrefix = "lemesh_map",
        mapViewProvider = { activeMapView },
        buildGeoJson = {
            MapExportEngine.buildNodeMapGeoJson(
                uiState = uiState,
                savedLayers = savedViewshedLayers
            )
        },
        buildSummary = {
            MapExportEngine.buildNodeMapSummary(
                uiState = uiState,
                savedLayers = savedViewshedLayers
            )
        },
        buildKml = {
            MapExportEngine.buildNodeMapKml(
                uiState = uiState,
                savedLayers = savedViewshedLayers
            )
        },
        buildNodeDbBackupJson = onBuildNodeDbBackupJson,
        onDismiss = { showExportDialog = false }
    )
}

@Composable
internal fun CompactNodeMapSearchBar(
    searchQuery: String,
    activeFilterCount: Int,
    savedLayersActiveCount: Int,
    onSearchQueryChange: (String) -> Unit,
    onOpenFilters: () -> Unit,
    onOpenLayers: () -> Unit,
    isOfflineOnlyMode: Boolean = false,
    isOfflineDownloading: Boolean = false,
    onOpenOfflineMaps: (() -> Unit)? = null
) {
    Surface(
        color = TacticalSurface.copy(alpha = 0.93f),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, TacticalOutline, RoundedCornerShape(16.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(18.dp)
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (searchQuery.isEmpty()) {
                    Text(
                        text = stringResource(R.string.search_placeholder),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        color = TextPrimary
                    ),
                    cursorBrush = SolidColor(NodeDirectGreen),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("map_search_input")
                )
            }

            if (searchQuery.isNotEmpty()) {
                IconButton(
                    onClick = { onSearchQueryChange("") },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_clear_search),
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Filters Sheet Trigger
            Surface(
                onClick = onOpenFilters,
                color = if (activeFilterCount > 0) NodeMultiHopSky.copy(alpha = 0.16f) else TacticalSurfaceElevated,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(
                    1.dp,
                    if (activeFilterCount > 0) NodeMultiHopSky.copy(alpha = 0.7f) else TacticalOutline
                ),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("map_filters_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = stringResource(R.string.map_filters_tools_title),
                        tint = if (activeFilterCount > 0) NodeMultiHopSky else TextPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(R.string.map_filters_short),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (activeFilterCount > 0) NodeMultiHopSky else TextPrimary
                    )
                    if (activeFilterCount > 0) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(NodeMultiHopSky)
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = activeFilterCount.toString(),
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                color = TacticalCanvas
                            )
                        }
                    }
                }
            }

            // Saved Layers Sheet Trigger
            Surface(
                onClick = onOpenLayers,
                color = if (savedLayersActiveCount > 0) NodeDirectGreen.copy(alpha = 0.16f) else TacticalSurfaceElevated,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(
                    1.dp,
                    if (savedLayersActiveCount > 0) NodeDirectGreen.copy(alpha = 0.7f) else TacticalOutline
                ),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("chip_saved_layers")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = stringResource(R.string.saved_layers_title),
                        tint = if (savedLayersActiveCount > 0) NodeDirectGreen else TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    if (savedLayersActiveCount > 0) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(NodeDirectGreen)
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = savedLayersActiveCount.toString(),
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
                Surface(
                    onClick = onOpenOfflineMaps,
                    color = when {
                        isOfflineOnlyMode -> NodeSelfAmber.copy(alpha = 0.18f)
                        isOfflineDownloading -> NodeDirectGreen.copy(alpha = 0.18f)
                        else -> TacticalSurfaceElevated
                    },
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(
                        1.dp,
                        when {
                            isOfflineOnlyMode -> NodeSelfAmber.copy(alpha = 0.75f)
                            isOfflineDownloading -> NodeDirectGreen.copy(alpha = 0.75f)
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
                                isOfflineDownloading -> NodeDirectGreen
                                else -> TextSecondary
                            },
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    Surface(
        color = AlertErrorRed.copy(alpha = 0.92f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary
                )
                if (actionLabel != null && onActionClick != null) {
                    TextButton(
                        onClick = onActionClick,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = TacticalCanvas.copy(alpha = 0.35f),
                            contentColor = NodeSelfAmber
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("open_offline_maps_from_error")
                    ) {
                        Text(
                            text = actionLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss error",
                    tint = TextPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
