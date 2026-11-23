package us.lemesh.nodedb

import android.Manifest
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.osmdroid.config.Configuration
import us.lemesh.nodedb.data.DeviceLocationResolver
import us.lemesh.nodedb.data.LiveMeshSyncRepository
import us.lemesh.nodedb.data.OfflineDemPackManager
import us.lemesh.nodedb.data.RfEnvironmentStateHolder
import us.lemesh.nodedb.ui.BridgeModeActions
import us.lemesh.nodedb.ui.BridgePlacementTarget
import us.lemesh.nodedb.ui.BridgeViewModel
import us.lemesh.nodedb.ui.LiveSyncActions
import us.lemesh.nodedb.ui.MeshModeActions
import us.lemesh.nodedb.ui.MeshRfWorkspaceScreen
import us.lemesh.nodedb.ui.NodeMapViewModel
import us.lemesh.nodedb.ui.OfflineMapsActions
import us.lemesh.nodedb.ui.ViewshedModeActions
import us.lemesh.nodedb.ui.ViewshedViewModel
import us.lemesh.nodedb.ui.state.MapViewportStateHolder
import us.lemesh.nodedb.ui.state.NodeInventoryStateHolder
import us.lemesh.nodedb.ui.state.RfWorkspaceCoordinator
import us.lemesh.nodedb.ui.state.WorkspaceMode
import us.lemesh.nodedb.ui.theme.LEMeshTheme
import java.io.File

class WorkspaceStateViewModel(application: Application) : AndroidViewModel(application) {
    val viewportHolder = MapViewportStateHolder()
    val inventoryHolder = NodeInventoryStateHolder()
    val coordinator = RfWorkspaceCoordinator()
    val environmentHolder = RfEnvironmentStateHolder()
    val liveSyncRepository = LiveMeshSyncRepository(application)

    override fun onCleared() {
        super.onCleared()
        liveSyncRepository.disconnect()
    }
}

class MainActivity : ComponentActivity() {

    private val workspaceStateViewModel: WorkspaceStateViewModel by viewModels()

    private val viewModel: NodeMapViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return NodeMapViewModel(
                    application = application,
                    viewportHolder = workspaceStateViewModel.viewportHolder,
                    inventoryHolder = workspaceStateViewModel.inventoryHolder,
                    coordinator = workspaceStateViewModel.coordinator,
                    environmentHolder = workspaceStateViewModel.environmentHolder,
                    liveSyncRepository = workspaceStateViewModel.liveSyncRepository
                ) as T
            }
        }
    }

    private val viewshedViewModel: ViewshedViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ViewshedViewModel(
                    application = application,
                    viewportHolder = workspaceStateViewModel.viewportHolder,
                    coordinator = workspaceStateViewModel.coordinator,
                    environmentHolder = workspaceStateViewModel.environmentHolder
                ) as T
            }
        }
    }

    private val bridgeViewModel: BridgeViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return BridgeViewModel(
                    application = application,
                    viewportHolder = workspaceStateViewModel.viewportHolder,
                    coordinator = workspaceStateViewModel.coordinator,
                    environmentHolder = workspaceStateViewModel.environmentHolder
                ) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }

        OfflineDemPackManager.initialize(applicationContext)
        handleIncomingIntent(intent)

        setContent {
            LEMeshTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val viewshedState by viewshedViewModel.uiState.collectAsStateWithLifecycle()
                val bridgeState by bridgeViewModel.uiState.collectAsStateWithLifecycle()
                val viewportState by workspaceStateViewModel.viewportHolder.state.collectAsStateWithLifecycle()
                val coordinatorState by workspaceStateViewModel.coordinator.state.collectAsStateWithLifecycle()
                val offlineDemState by OfflineDemPackManager.uiState.collectAsStateWithLifecycle()
                val liveSyncState by workspaceStateViewModel.liveSyncRepository.uiState.collectAsStateWithLifecycle()

                val context = LocalContext.current
                var hasRequestedLocationOnLaunch by rememberSaveable { mutableStateOf(false) }

                val locationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { grants ->
                    val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                        grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                    viewModel.resolveStartupZoomTarget(permissionGranted = granted)
                }

                LaunchedEffect(Unit) {
                    if (DeviceLocationResolver.hasAnyLocationPermission(context)) {
                        viewModel.resolveStartupZoomTarget(permissionGranted = true)
                    } else if (!hasRequestedLocationOnLaunch) {
                        hasRequestedLocationOnLaunch = true
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.ACCESS_FINE_LOCATION
                            )
                        )
                    } else {
                        viewModel.resolveStartupZoomTarget(permissionGranted = false)
                    }
                }

                LaunchedEffect(uiState.selfNode, uiState.allNodes) {
                    viewshedViewModel.syncDefaultSiteFromSelfNodeIfUnset(uiState.selfNode)
                    bridgeViewModel.syncDefaultsFromNodesIfUnset(uiState.selfNode, uiState.allNodes)
                }

                val openDocumentLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri: Uri? ->
                    if (uri != null) {
                        viewModel.loadFromUri(uri)
                    }
                }

                val triggerFilePicker = {
                    openDocumentLauncher.launch(
                        arrayOf(
                            "application/json",
                            "application/geo+json",
                            "text/plain",
                            "application/octet-stream"
                        )
                    )
                }

                MeshRfWorkspaceScreen(
                    activeMode = coordinatorState.activeMode,
                    viewportState = viewportState,
                    meshState = uiState,
                    viewshedState = viewshedState,
                    bridgeState = bridgeState,
                    onSwitchMode = workspaceStateViewModel.coordinator::switchMode,
                    onPinDragStart = workspaceStateViewModel.coordinator::onPinDragStarted,
                    meshActions = MeshModeActions(
                        onLoadJsonClick = triggerFilePicker,
                        onClearJsonClick = {
                            viewModel.clearLoadedJson()
                            viewshedViewModel.resetForFreshNodeDb()
                            bridgeViewModel.resetForFreshNodeDb()
                        },
                        onClearAllWithManualClick = {
                            viewModel.clearLoadedJson(clearManualNodesToo = true)
                            viewshedViewModel.resetForFreshNodeDb()
                            bridgeViewModel.resetForFreshNodeDb()
                        },
                        onSearchQueryChange = viewModel::updateSearchQuery,
                        onToggleShowMqtt = viewModel::toggleShowMqtt,
                        onToggleRfSightlines = viewModel::toggleRfSightlines,
                        onToggleFavoritesOnly = viewModel::toggleFavoritesOnly,
                        onToggleHwModelFilter = viewModel::toggleHwModelFilter,
                        onToggleRoleFilter = viewModel::toggleRoleFilter,
                        onResetAllFilters = viewModel::resetAllFilters,
                        onSelectBaseLayer = viewModel::selectBaseLayer,
                        onSelectOverlayContinuityMode = viewModel::setOverlayContinuityMode,
                        onOpenNoGpsSheet = { viewModel.setNoGpsSheetVisible(true) },
                        onCloseNoGpsSheet = { viewModel.setNoGpsSheetVisible(false) },
                        onNoGpsSearchQueryChange = viewModel::updateNoGpsSearchQuery,
                        onSelectNode = viewModel::selectNode,
                        onMapNodeClick = viewModel::onMapNodeClick,
                        onSightlineClick = viewModel::inspectSightlineFresnel,
                        onToggleMeasureMode = viewModel::toggleMeasureMode,
                        onClearMeasureAnchors = viewModel::clearMeasureAnchors,
                        onUseSelfAsAnchorA = viewModel::useSelfAsMeasureAnchorA,
                        onOpenDirectLinksSheet = { viewModel.setDirectLinksSheetVisible(true) },
                        onCloseDirectLinksSheet = { viewModel.setDirectLinksSheetVisible(false) },
                        onToggleDirectLinksSort = viewModel::toggleDirectLinksSort,
                        onMeasureDirectLinkFromSelf = viewModel::measureDirectLinkFromSelf,
                        onToggleLosMode = viewModel::toggleLosMode,
                        onMapCoordinateTap = viewModel::onMapCoordinateTap,
                        onToggleLosDrawerExpanded = viewModel::toggleLosDrawerExpanded,
                        onLosTxHeightChange = viewModel::updateLosTxHeight,
                        onLosRxHeightChange = viewModel::updateLosRxHeight,
                        onLosFrequencyChange = viewModel::updateLosFrequency,
                        onLosTxPowerChange = viewModel::updateLosTxPower,
                        onLosTxGainChange = viewModel::updateLosTxGain,
                        onLosRxGainChange = viewModel::updateLosRxGain,
                        onLosSensitivityChange = viewModel::updateLosSensitivity,
                        onApplyLosMastFix = viewModel::applyLosMastFix,
                        onUseSelfAsLosPointA = viewModel::useSelfAsLosPointA,
                        onSwapLosEndpoints = viewModel::swapLosEndpoints,
                        onClearLosEndpoints = viewModel::clearLosEndpoints,
                        onRetryLosElevation = viewModel::retryLosElevation,
                        onUseFlatTerrainFallback = viewModel::applyFlatTerrainFallback,
                        onAnalyzeLosBetweenNodes = viewModel::analyzeLosBetweenNodes,
                        onInspectFresnelSightline = viewModel::inspectSightlineFresnel,
                        onFitBounds = viewModel::requestFitBounds,
                        onDismissError = viewModel::dismissError,
                        onStartupZoomStarted = viewModel::markStartupZoomStarted,
                        onSaveCameraState = viewModel::saveMapCameraState,
                        onToggleAggregateCoverage = viewModel::toggleAggregateCoverage,
                        onUpdateAggregateRadiusKm = viewModel::updateAggregateRadiusKm,
                        onUpdateAggregateMastMeters = viewModel::updateAggregateMastMeters,
                        onRefreshAggregateCoverage = viewModel::refreshAggregateCoverage,
                        onCancelAggregateCoverageCompute = viewModel::cancelAggregateCoverageCompute,
                        onDismissAggregateCoverageError = viewModel::dismissAggregateCoverageError,
                        onToggleAddNodeMode = viewModel::toggleAddNodeMode,
                        onOpenNewNodeEditor = viewModel::openNewNodeEditor,
                        onOpenEditNodeEditor = viewModel::openEditNodeEditor,
                        onStartCoordinatePickForEditor = viewModel::startCoordinatePickForEditor,
                        onDismissNodeEditor = viewModel::dismissNodeEditor,
                        onSaveNodeEditorDraft = viewModel::saveNodeEditorDraft,
                        onDeleteManualNode = viewModel::deleteManualNode,
                        onRevertNodeEdits = viewModel::revertNodeEdits,
                        onBuildNodeDbBackupJson = viewModel::buildNodeDbBackupJson,
                        onConfirmPendingImportMerge = viewModel::confirmPendingImportMerge,
                        onConfirmPendingImportReplace = viewModel::confirmPendingImportReplace,
                        onDismissPendingImport = viewModel::dismissPendingImport,
                        onAssignViewshedFromNode = { node ->
                            viewshedViewModel.applyNodeHandoffAndCalculate(node, uiState.allNodes)
                            viewModel.selectNode(null)
                        },
                        onAssignViewshedRx = { node ->
                            viewshedViewModel.selectRxNode(node)
                            viewModel.selectNode(null)
                            workspaceStateViewModel.coordinator.recordHandoff(node, WorkspaceMode.VIEWSHED)
                        },
                        onAssignBridgeA = { node ->
                            bridgeViewModel.applyNodeHandoffForEndpoint(
                                target = BridgePlacementTarget.TX,
                                node = node,
                                autoSolveIfReady = true
                            )
                            viewModel.selectNode(null)
                        },
                        onAssignBridgeB = { node ->
                            bridgeViewModel.applyNodeHandoffForEndpoint(
                                target = BridgePlacementTarget.RX,
                                node = node,
                                autoSolveIfReady = true
                            )
                            viewModel.selectNode(null)
                        }
                    ),
                    viewshedActions = ViewshedModeActions(
                        onSetPlacementMode = viewshedViewModel::setPlacementMode,
                        onSetPlacementTarget = viewshedViewModel::setPlacementTarget,
                        onSetCustomCoordinate = viewshedViewModel::setCoordinateForActiveTarget,
                        onTxDragEnd = viewshedViewModel::setCustomSiteCoordinate,
                        onRxDragEnd = viewshedViewModel::setRxCustomCoordinate,
                        onSelectExistingNode = viewshedViewModel::selectExistingNode,
                        onSelectRxNode = viewshedViewModel::selectRxNode,
                        onSwapTxRx = viewshedViewModel::swapTxAndRx,
                        onAntennaHeightChange = viewshedViewModel::updateAntennaHeight,
                        onRxAntennaHeightChange = viewshedViewModel::updateRxAntennaHeight,
                        onRadiusKmChange = viewshedViewModel::updateRadiusKm,
                        onFrequencyChange = viewshedViewModel::updateFrequencyText,
                        onTxPowerChange = viewshedViewModel::updateTxPowerDbm,
                        onAntennaGainChange = viewshedViewModel::updateAntennaGainDbi,
                        onSensitivityChange = viewshedViewModel::updateSensitivityDbm,
                        onOpacityChange = viewshedViewModel::updateOverlayOpacity,
                        onCalculateCoverage = { viewshedViewModel.calculateCoverage(uiState.allNodes) },
                        onTogglePinSnapshot = viewshedViewModel::togglePinSnapshot,
                        onToggleShowSnapshotOnMap = viewshedViewModel::toggleShowSnapshotOnMap,
                        suggestedLayerName = viewshedViewModel.suggestDefaultLayerName(),
                        suggestedLayerTint = viewshedViewModel.suggestNextLayerTint(),
                        onSaveLayer = viewshedViewModel::saveCurrentViewshedAsLayer,
                        onToggleSavedLayerEnabled = viewshedViewModel::toggleSavedLayerEnabled,
                        onToggleSavedLayerRenderMode = viewshedViewModel::toggleSavedLayerRenderMode,
                        onDeleteSavedLayer = viewshedViewModel::deleteSavedLayer,
                        onClearCurrentViewshed = viewshedViewModel::clearCurrentViewshed,
                        onClearAllAndStartFresh = viewshedViewModel::clearAllAndStartFresh,
                        onSendToBridge = {
                            val latestViewshed = viewshedViewModel.uiState.value
                            if (bridgeViewModel.importFromViewshed(latestViewshed, autoSolve = true)) {
                                workspaceStateViewModel.coordinator.switchMode(WorkspaceMode.BRIDGE)
                            }
                        },
                        onApplyMastFix = viewshedViewModel::applyMastFix,
                        onInspectObstructedNode = viewshedViewModel::inspectObstructedNodeForFix,
                        onDismissError = viewshedViewModel::dismissError
                    ),
                    bridgeActions = BridgeModeActions(
                        onSetPlacementMode = bridgeViewModel::setPlacementMode,
                        onSetPlacementTarget = bridgeViewModel::setPlacementTarget,
                        onMapTap = bridgeViewModel::setCoordinateForActiveTarget,
                        onTxDragEnd = bridgeViewModel::setTxCustomCoordinate,
                        onRxDragEnd = bridgeViewModel::setRxCustomCoordinate,
                        onSelectTxNode = bridgeViewModel::selectTxNode,
                        onSelectRxNode = bridgeViewModel::selectRxNode,
                        onSwapTxRx = bridgeViewModel::swapTxAndRx,
                        onClearOverlay = bridgeViewModel::clearBridgeOverlay,
                        onResetAll = bridgeViewModel::resetBridgeAll,
                        onTxHeightChange = bridgeViewModel::updateTxAntennaHeight,
                        onRxHeightChange = bridgeViewModel::updateRxAntennaHeight,
                        onRelayHeightChange = bridgeViewModel::updateRelayAntennaHeight,
                        onRadiusKmChange = bridgeViewModel::updateRadiusKm,
                        onFrequencyChange = bridgeViewModel::updateFrequencyText,
                        onTxPowerChange = bridgeViewModel::updateTxPowerDbm,
                        onTxAntennaGainChange = bridgeViewModel::updateTxAntennaGainDbi,
                        onTxSensitivityChange = bridgeViewModel::updateTxSensitivityDbm,
                        onRxPowerChange = bridgeViewModel::updateRxPowerDbm,
                        onRxAntennaGainChange = bridgeViewModel::updateRxAntennaGainDbi,
                        onRxSensitivityChange = bridgeViewModel::updateRxSensitivityDbm,
                        onRelayPowerChange = bridgeViewModel::updateRelayPowerDbm,
                        onRelayAntennaGainChange = bridgeViewModel::updateRelayAntennaGainDbi,
                        onRelaySensitivityChange = bridgeViewModel::updateRelaySensitivityDbm,
                        onOpacityChange = bridgeViewModel::updateOverlayOpacity,
                        onCalculateBridge = bridgeViewModel::calculateBridge,
                        onSelectCandidate = bridgeViewModel::selectCandidateIndex,
                        onSelectDrawerTab = bridgeViewModel::selectDrawerTab,
                        onSelectObstruction = bridgeViewModel::selectObstruction,
                        onDismissError = bridgeViewModel::dismissError,
                        onSelectProfileMode = bridgeViewModel::selectProfileMode,
                        onScrubProfileSample = bridgeViewModel::updateScrubbedProfileSample,
                        onRetryLiveProfile = bridgeViewModel::retryLiveProfile
                    ),
                    offlineDemState = offlineDemState,
                    offlineActions = OfflineMapsActions(
                        onToggleOfflineOnly = { enabled ->
                            OfflineDemPackManager.setOfflineOnlyMode(applicationContext, enabled)
                        },
                        onDownloadPreset = { preset ->
                            OfflineDemPackManager.startPresetDownload(applicationContext, preset)
                        },
                        onDownloadViewport = { north, south, west, east ->
                            OfflineDemPackManager.startViewportDownload(
                                context = applicationContext,
                                northLat = north,
                                southLat = south,
                                westLon = west,
                                eastLon = east
                            )
                        },
                        onCancelDownload = OfflineDemPackManager::cancelDownload,
                        onClearCache = {
                            OfflineDemPackManager.clearDemCache(applicationContext)
                        },
                        onDismissMessages = OfflineDemPackManager::dismissMessages
                    ),
                    liveSyncState = liveSyncState,
                    liveSyncActions = LiveSyncActions(
                        onUpdateConfig = workspaceStateViewModel.liveSyncRepository::updateConfig,
                        onConnect = { cfg -> workspaceStateViewModel.liveSyncRepository.connect(cfg) },
                        onDisconnect = workspaceStateViewModel.liveSyncRepository::disconnect,
                        onDismissError = workspaceStateViewModel.liveSyncRepository::dismissError
                    ),
                    onSelectFadeMarginPreset = workspaceStateViewModel.environmentHolder::setFadeMarginPreset
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val clipUri = intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data ?: clipUri
            Intent.ACTION_SEND -> {
                val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                streamUri ?: clipUri ?: intent.data
            }
            else -> null
        }
        if (uri != null) {
            viewModel.loadFromUri(uri)
        }
    }

    companion object {
        const val ROUTE_MAP = "map"
        const val ROUTE_VIEWSHED = "viewshed"
        const val ROUTE_BRIDGE = "bridge"
    }
}
