package com.example

import android.Manifest
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.outlined.CellTower
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.DeviceLocationResolver
import com.example.ui.BridgePlannerScreen
import com.example.ui.BridgeViewModel
import com.example.ui.MainScreen
import com.example.ui.NodeMapViewModel
import com.example.ui.ViewshedPlannerScreen
import com.example.ui.ViewshedViewModel
import com.example.ui.theme.LEMeshTheme
import com.example.ui.theme.NodeDirectGreen
import com.example.ui.theme.TacticalCanvas
import com.example.ui.theme.TacticalSurface
import com.example.ui.theme.TacticalSurfaceBright
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import org.osmdroid.config.Configuration
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: NodeMapViewModel by viewModels()
    private val viewshedViewModel: ViewshedViewModel by viewModels()
    private val bridgeViewModel: BridgeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize osmdroid user agent and cache paths before inflating MapView
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }

        handleIncomingIntent(intent)

        setContent {
            LEMeshTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val viewshedState by viewshedViewModel.uiState.collectAsStateWithLifecycle()
                val bridgeState by bridgeViewModel.uiState.collectAsStateWithLifecycle()
                val navController = rememberNavController()
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = backStackEntry?.destination?.route ?: ROUTE_MAP

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
                            "text/plain",
                            "application/octet-stream"
                        )
                    )
                }

                val navigateToTab: (String) -> Unit = { route ->
                    navController.navigate(route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = TacticalCanvas,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        NavigationBar(
                            containerColor = TacticalSurface,
                            contentColor = TextPrimary
                        ) {
                            val isMapSelected = currentRoute == ROUTE_MAP
                            val isViewshedSelected = currentRoute == ROUTE_VIEWSHED
                            val isBridgeSelected = currentRoute == ROUTE_BRIDGE

                            NavigationBarItem(
                                selected = isMapSelected,
                                onClick = {
                                    navController.navigate(ROUTE_MAP) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (isMapSelected) Icons.Filled.Map else Icons.Outlined.Map,
                                        contentDescription = "Node Map"
                                    )
                                },
                                label = { Text("Node Map") },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = NodeDirectGreen,
                                    selectedTextColor = NodeDirectGreen,
                                    indicatorColor = TacticalSurfaceBright,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                ),
                                modifier = Modifier.testTag("nav_tab_map")
                            )

                            NavigationBarItem(
                                selected = isViewshedSelected,
                                onClick = {
                                    navController.navigate(ROUTE_VIEWSHED) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (isViewshedSelected) Icons.Filled.CellTower else Icons.Outlined.CellTower,
                                        contentDescription = "Viewshed Planner"
                                    )
                                },
                                label = { Text("Viewshed") },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = NodeDirectGreen,
                                    selectedTextColor = NodeDirectGreen,
                                    indicatorColor = TacticalSurfaceBright,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                ),
                                modifier = Modifier.testTag("nav_tab_viewshed")
                            )

                            NavigationBarItem(
                                selected = isBridgeSelected,
                                onClick = {
                                    navController.navigate(ROUTE_BRIDGE) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (isBridgeSelected) Icons.Filled.Hub else Icons.Outlined.Hub,
                                        contentDescription = "TX-RX Bridge"
                                    )
                                },
                                label = { Text("Bridge") },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = NodeDirectGreen,
                                    selectedTextColor = NodeDirectGreen,
                                    indicatorColor = TacticalSurfaceBright,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                ),
                                modifier = Modifier.testTag("nav_tab_bridge")
                            )
                        }
                    }
                ) { outerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = ROUTE_MAP,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = outerPadding.calculateBottomPadding())
                    ) {
                        composable(ROUTE_MAP) {
                            MainScreen(
                                uiState = uiState,
                                onLoadJsonClick = triggerFilePicker,
                                onSearchQueryChange = viewModel::updateSearchQuery,
                                onToggleShowMqtt = viewModel::toggleShowMqtt,
                                onToggleRfSightlines = viewModel::toggleRfSightlines,
                                onToggleFavoritesOnly = viewModel::toggleFavoritesOnly,
                                onSelectBaseLayer = viewModel::selectBaseLayer,
                                onOpenNoGpsSheet = { viewModel.setNoGpsSheetVisible(true) },
                                onCloseNoGpsSheet = { viewModel.setNoGpsSheetVisible(false) },
                                onNoGpsSearchQueryChange = viewModel::updateNoGpsSearchQuery,
                                onSelectNode = viewModel::selectNode,
                                onMapNodeClick = viewModel::onMapNodeClick,
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
                                onUseSelfAsLosPointA = viewModel::useSelfAsLosPointA,
                                onSwapLosEndpoints = viewModel::swapLosEndpoints,
                                onClearLosEndpoints = viewModel::clearLosEndpoints,
                                onRetryLosElevation = viewModel::retryLosElevation,
                                onUseFlatTerrainFallback = viewModel::applyFlatTerrainFallback,
                                onAnalyzeLosBetweenNodes = viewModel::analyzeLosBetweenNodes,
                                onFitBounds = viewModel::requestFitBounds,
                                onDismissError = viewModel::dismissError,
                                savedViewshedLayers = viewshedState.savedLayers,
                                viewshedOverlayOpacity = viewshedState.overlayOpacity,
                                onViewshedOpacityChange = viewshedViewModel::updateOverlayOpacity,
                                onToggleSavedLayerEnabled = viewshedViewModel::toggleSavedLayerEnabled,
                                onToggleSavedLayerRenderMode = viewshedViewModel::toggleSavedLayerRenderMode,
                                onDeleteSavedLayer = viewshedViewModel::deleteSavedLayer,
                                onClearJsonClick = {
                                    viewModel.clearLoadedJson()
                                    viewshedViewModel.resetForFreshNodeDb()
                                    bridgeViewModel.resetForFreshNodeDb()
                                },
                                onAssignViewshedTx = { node ->
                                    viewshedViewModel.selectExistingNode(node)
                                    viewModel.selectNode(null)
                                    navigateToTab(ROUTE_VIEWSHED)
                                },
                                onAssignViewshedRx = { node ->
                                    viewshedViewModel.selectRxNode(node)
                                    viewModel.selectNode(null)
                                    navigateToTab(ROUTE_VIEWSHED)
                                },
                                onAssignBridgeTx = { node ->
                                    bridgeViewModel.selectTxNode(node)
                                    viewModel.selectNode(null)
                                    navigateToTab(ROUTE_BRIDGE)
                                },
                                onAssignBridgeRx = { node ->
                                    bridgeViewModel.selectRxNode(node)
                                    viewModel.selectNode(null)
                                    navigateToTab(ROUTE_BRIDGE)
                                },
                                onSetTopHeaderDismissed = viewModel::setTopHeaderDismissed,
                                onToggleHudMinimized = viewModel::toggleHudMinimized,
                                onStartupZoomStarted = viewModel::markStartupZoomStarted,
                                onSaveCameraState = viewModel::saveMapCameraState,
                                onToggleAggregateCoverage = viewModel::toggleAggregateCoverage,
                                onUpdateAggregateRadiusKm = viewModel::updateAggregateRadiusKm,
                                onUpdateAggregateMastMeters = viewModel::updateAggregateMastMeters,
                                onRefreshAggregateCoverage = viewModel::refreshAggregateCoverage,
                                onCancelAggregateCoverageCompute = viewModel::cancelAggregateCoverageCompute,
                                onDismissAggregateCoverageError = viewModel::dismissAggregateCoverageError,
                                onSightlineClick = viewModel::inspectSightlineFresnel,
                                onInspectFresnelSightline = viewModel::inspectSightlineFresnel,
                                onLosTxPowerChange = viewModel::updateLosTxPower,
                                onLosTxGainChange = viewModel::updateLosTxGain,
                                onLosRxGainChange = viewModel::updateLosRxGain,
                                onLosSensitivityChange = viewModel::updateLosSensitivity,
                                onApplyLosMastFix = viewModel::applyLosMastFix,
                                onToggleAddNodeMode = viewModel::toggleAddNodeMode,
                                onOpenNewNodeEditor = viewModel::openNewNodeEditor,
                                onOpenEditNodeEditor = viewModel::openEditNodeEditor,
                                onStartCoordinatePickForEditor = viewModel::startCoordinatePickForEditor,
                                onDismissNodeEditor = viewModel::dismissNodeEditor,
                                onSaveNodeEditorDraft = viewModel::saveNodeEditorDraft,
                                onDeleteManualNode = viewModel::deleteManualNode,
                                onRevertNodeEdits = viewModel::revertNodeEdits,
                                onBuildNodeDbBackupJson = viewModel::buildNodeDbBackupJson,
                                onClearAllWithManualClick = {
                                    viewModel.clearLoadedJson(clearManualNodesToo = true)
                                    viewshedViewModel.resetForFreshNodeDb()
                                    bridgeViewModel.resetForFreshNodeDb()
                                }
                            )
                        }

                        composable(ROUTE_VIEWSHED) {
                            ViewshedPlannerScreen(
                                uiState = viewshedState,
                                existingNodes = uiState.allNodes,
                                baseLayer = uiState.baseLayer,
                                onSelectBaseLayer = viewModel::selectBaseLayer,
                                onToggleTopBarExpanded = viewshedViewModel::toggleTopBarExpanded,
                                onSetPlacementMode = viewshedViewModel::setPlacementMode,
                                onSetPlacementTarget = viewshedViewModel::setPlacementTarget,
                                onSetCustomCoordinate = viewshedViewModel::setCoordinateForActiveTarget,
                                onTxDragEnd = viewshedViewModel::setCustomSiteCoordinate,
                                onRxDragEnd = viewshedViewModel::setRxCustomCoordinate,
                                onSelectExistingNode = viewshedViewModel::selectExistingNode,
                                onSelectRxNode = viewshedViewModel::selectRxNode,
                                onExistingNodeTap = viewshedViewModel::selectNodeForActiveTarget,
                                onSwapTxRx = viewshedViewModel::swapTxAndRx,
                                onAntennaHeightChange = viewshedViewModel::updateAntennaHeight,
                                onRxAntennaHeightChange = viewshedViewModel::updateRxAntennaHeight,
                                onRadiusKmChange = viewshedViewModel::updateRadiusKm,
                                onFrequencyChange = viewshedViewModel::updateFrequencyText,
                                onTxPowerChange = viewshedViewModel::updateTxPowerDbm,
                                onAntennaGainChange = viewshedViewModel::updateAntennaGainDbi,
                                onSensitivityChange = viewshedViewModel::updateSensitivityDbm,
                                onOpacityChange = viewshedViewModel::updateOverlayOpacity,
                                onCalculateCoverage = {
                                    viewshedViewModel.calculateCoverage(uiState.allNodes)
                                },
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
                                        navigateToTab(ROUTE_BRIDGE)
                                    }
                                },
                                onApplyMastFix = viewshedViewModel::applyMastFix,
                                onInspectObstructedNode = viewshedViewModel::inspectObstructedNodeForFix,
                                onDismissError = viewshedViewModel::dismissError
                            )
                        }

                        composable(ROUTE_BRIDGE) {
                            BridgePlannerScreen(
                                uiState = bridgeState,
                                existingNodes = uiState.allNodes,
                                baseLayer = uiState.baseLayer,
                                onSelectBaseLayer = viewModel::selectBaseLayer,
                                onToggleTopBarExpanded = bridgeViewModel::toggleTopBarExpanded,
                                onSetPlacementMode = bridgeViewModel::setPlacementMode,
                                onSetPlacementTarget = bridgeViewModel::setPlacementTarget,
                                onMapTap = bridgeViewModel::setCoordinateForActiveTarget,
                                onTxDragEnd = bridgeViewModel::setTxCustomCoordinate,
                                onRxDragEnd = bridgeViewModel::setRxCustomCoordinate,
                                onSelectTxNode = bridgeViewModel::selectTxNode,
                                onSelectRxNode = bridgeViewModel::selectRxNode,
                                onExistingNodeTap = bridgeViewModel::selectNodeForActiveTarget,
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
                            )
                        }
                    }
                }
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
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                } ?: intent.data
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
