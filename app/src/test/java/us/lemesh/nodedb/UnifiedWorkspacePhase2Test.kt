package us.lemesh.nodedb

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import us.lemesh.nodedb.data.MeshNode
import us.lemesh.nodedb.ui.BridgePlacementTarget
import us.lemesh.nodedb.ui.BridgeUiState
import us.lemesh.nodedb.ui.BridgeViewModel
import us.lemesh.nodedb.ui.MeshRfWorkspaceScreen
import us.lemesh.nodedb.ui.NodeEditorDraft
import us.lemesh.nodedb.ui.NodeInspectorBottomSheet
import us.lemesh.nodedb.ui.NodeMapFiltersSheet
import us.lemesh.nodedb.ui.NodeMapUiState
import us.lemesh.nodedb.ui.NodeMapViewModel
import us.lemesh.nodedb.ui.SavedViewshedLayersSheet
import us.lemesh.nodedb.ui.ViewshedUiState
import us.lemesh.nodedb.ui.ViewshedViewModel
import us.lemesh.nodedb.ui.state.MapViewportState
import us.lemesh.nodedb.ui.state.MapViewportStateHolder
import us.lemesh.nodedb.ui.state.NodeInventoryStateHolder
import us.lemesh.nodedb.ui.state.OverlayContinuityMode
import us.lemesh.nodedb.ui.state.RfWorkspaceCoordinator
import us.lemesh.nodedb.ui.state.WorkspaceMode
import us.lemesh.nodedb.ui.theme.LEMeshTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UnifiedWorkspacePhase2Test {

    @get:Rule
    val composeRule = createComposeRule()

    private fun sampleNode(
        num: Long,
        id: String,
        longName: String,
        shortName: String,
        hwModel: String?,
        role: String?,
        lat: Double,
        lon: Double,
        isSelf: Boolean = false
    ): MeshNode = MeshNode(
        num = num,
        id = id,
        longName = longName,
        shortName = shortName,
        hwModel = hwModel,
        role = role,
        latitude = lat,
        longitude = lon,
        altitude = 240,
        hopsAway = 0,
        viaMqtt = false,
        isFavorite = false,
        isSelf = isSelf,
        snr = 6.5,
        rssi = -82,
        lastHeard = 1_700_000_000L,
        deviceMetrics = null,
        environmentMetrics = null,
        rawJson = "{}"
    )

    @Test
    fun `shared viewport holder preserves camera position zoom and bearing across mode switches`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewportHolder = MapViewportStateHolder()
        val inventoryHolder = NodeInventoryStateHolder()
        val coordinator = RfWorkspaceCoordinator()
        val mapVm = NodeMapViewModel(app, viewportHolder, inventoryHolder, coordinator)

        mapVm.saveMapCameraState(
            lat = 42.1500,
            lon = -80.0200,
            zoom = 12.75,
            bearingDeg = 42.5f
        )
        delay(60L)

        coordinator.switchMode(WorkspaceMode.VIEWSHED)
        assertEquals(WorkspaceMode.VIEWSHED, coordinator.state.value.activeMode)
        coordinator.switchMode(WorkspaceMode.BRIDGE)
        assertEquals(WorkspaceMode.BRIDGE, coordinator.state.value.activeMode)
        coordinator.switchMode(WorkspaceMode.MESH)
        assertEquals(WorkspaceMode.MESH, coordinator.state.value.activeMode)

        val vp = viewportHolder.state.value
        assertEquals(42.1500, vp.cameraLat!!, 1e-6)
        assertEquals(-80.0200, vp.cameraLon!!, 1e-6)
        assertEquals(12.75, vp.cameraZoom!!, 1e-6)
        assertEquals(42.5f, vp.cameraBearingDeg, 1e-3f)

        val ui = mapVm.uiState.value
        assertEquals(42.1500, ui.savedCameraLat!!, 1e-6)
        assertEquals(-80.0200, ui.savedCameraLon!!, 1e-6)
        assertEquals(12.75, ui.savedCameraZoom!!, 1e-6)
        assertEquals(42.5f, ui.savedCameraBearingDeg, 1e-3f)
    }

    @Test
    fun `overlay continuity mode updates viewport state and toggles from SavedViewshedLayersSheet`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewportHolder = MapViewportStateHolder()
        val inventoryHolder = NodeInventoryStateHolder()
        val coordinator = RfWorkspaceCoordinator()
        val mapVm = NodeMapViewModel(app, viewportHolder, inventoryHolder, coordinator)

        assertEquals(OverlayContinuityMode.KEEP_NODEDB_WITH_RF, viewportHolder.state.value.overlayContinuityMode)
        mapVm.setOverlayContinuityMode(OverlayContinuityMode.ACTIVE_MODE_ONLY)
        assertEquals(OverlayContinuityMode.ACTIVE_MODE_ONLY, viewportHolder.state.value.overlayContinuityMode)
        assertEquals(OverlayContinuityMode.ACTIVE_MODE_ONLY, mapVm.uiState.value.overlayContinuityMode)

        var selectedMode = OverlayContinuityMode.KEEP_NODEDB_WITH_RF
        composeRule.setContent {
            LEMeshTheme {
                SavedViewshedLayersSheet(
                    layers = emptyList(),
                    overlayOpacity = 0.68f,
                    onOpacityChange = {},
                    onToggleEnabled = {},
                    onToggleRenderMode = {},
                    onDeleteLayer = {},
                    onDismiss = {},
                    overlayContinuityMode = selectedMode,
                    onSelectOverlayContinuityMode = { selectedMode = it }
                )
            }
        }

        composeRule.onNodeWithTag("overlay_continuity_card").assertIsDisplayed()
        composeRule.onNodeWithTag("continuity_active_only").performClick()
        composeRule.waitForIdle()
        assertEquals(OverlayContinuityMode.ACTIVE_MODE_ONLY, selectedMode)

        composeRule.onNodeWithTag("continuity_keep_nodedb").performClick()
        composeRule.waitForIdle()
        assertEquals(OverlayContinuityMode.KEEP_NODEDB_WITH_RF, selectedMode)
    }

    @Test
    fun `hardware model and role filters filter nodes and reset cleanly`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewportHolder = MapViewportStateHolder()
        val inventoryHolder = NodeInventoryStateHolder()
        val coordinator = RfWorkspaceCoordinator()
        val mapVm = NodeMapViewModel(app, viewportHolder, inventoryHolder, coordinator)

        mapVm.saveNodeEditorDraft(
            NodeEditorDraft(
                originalKey = null,
                isCreatingNew = true,
                isManual = true,
                isEdited = false,
                id = "!a0000001",
                num = 0xA0000001L,
                longName = "Tower North",
                shortName = "TWRN",
                latitudeText = "42.1400",
                longitudeText = "-80.0800",
                altitudeText = "240",
                hwModelText = "STATION_G2",
                roleText = "ROUTER",
                hopsAwayText = "0",
                viaMqtt = false,
                isFavorite = false,
                isSelf = true,
                snrText = "",
                rssiText = "",
                notesText = "",
                customColorArgb = null
            )
        )
        mapVm.saveNodeEditorDraft(
            NodeEditorDraft(
                originalKey = null,
                isCreatingNew = true,
                isManual = true,
                isEdited = false,
                id = "!a0000002",
                num = 0xA0000002L,
                longName = "Pocket Unit",
                shortName = "PKT2",
                latitudeText = "42.1600",
                longitudeText = "-80.0500",
                altitudeText = "200",
                hwModelText = "HELTEC_V3",
                roleText = "CLIENT",
                hopsAwayText = "1",
                viaMqtt = false,
                isFavorite = false,
                isSelf = false,
                snrText = "",
                rssiText = "",
                notesText = "",
                customColorArgb = null
            )
        )
        delay(120L)

        assertEquals(listOf("HELTEC_V3", "STATION_G2"), mapVm.uiState.value.availableHwModels)
        assertEquals(listOf("CLIENT", "ROUTER"), mapVm.uiState.value.availableRoles)
        assertEquals(2, mapVm.uiState.value.filteredMapNodes.size)

        mapVm.toggleHwModelFilter("STATION_G2")
        assertEquals(listOf("Tower North"), mapVm.uiState.value.filteredMapNodes.map { it.longName })

        mapVm.toggleRoleFilter("CLIENT")
        assertTrue(mapVm.uiState.value.filteredMapNodes.isEmpty())

        mapVm.resetAllFilters()
        assertEquals(2, mapVm.uiState.value.filteredMapNodes.size)

        var toggledHw: String? = null
        var toggledRole: String? = null
        var resetTriggered = false
        val sheetState = mapVm.uiState.value.copy(selectedHwModels = setOf("STATION_G2"))

        composeRule.setContent {
            LEMeshTheme {
                NodeMapFiltersSheet(
                    uiState = sheetState,
                    onToggleShowMqtt = {},
                    onToggleRfSightlines = {},
                    onToggleFavoritesOnly = {},
                    onToggleHwModelFilter = { toggledHw = it },
                    onToggleRoleFilter = { toggledRole = it },
                    onToggleLosMode = {},
                    onToggleMeasureMode = {},
                    onOpenDirectLinksSheet = {},
                    onOpenNoGpsSheet = {},
                    onLoadJsonClick = {},
                    onClearJsonClick = {},
                    onResetFilters = { resetTriggered = true },
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithTag("filter_hw_STATION_G2").performScrollTo().performClick()
        assertEquals("STATION_G2", toggledHw)

        composeRule.onNodeWithTag("filter_role_ROUTER").performScrollTo().performClick()
        assertEquals("ROUTER", toggledRole)

        composeRule.onNodeWithTag("filters_reset_all_button").performScrollTo().performClick()
        assertTrue(resetTriggered)
    }

    @Test
    fun `viewshed node handoff derives rf preset switches mode and auto starts coverage`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewportHolder = MapViewportStateHolder()
        val coordinator = RfWorkspaceCoordinator()
        val viewshedVm = ViewshedViewModel(app, viewportHolder, coordinator)

        val routerNode = sampleNode(
            num = 101L,
            id = "!00000065",
            longName = "Ridge Station G2",
            shortName = "RDG2",
            hwModel = "STATION_G2",
            role = "ROUTER",
            lat = 42.1425,
            lon = -80.0615
        )
        viewshedVm.selectRxNode(routerNode)

        val accepted = viewshedVm.applyNodeHandoffAndCalculate(routerNode, listOf(routerNode))
        assertTrue(accepted)

        val ui = viewshedVm.uiState.value
        assertEquals(WorkspaceMode.VIEWSHED, coordinator.state.value.activeMode)
        assertEquals(routerNode.stableKey, coordinator.state.value.lastHandoffSourceNodeKey)
        assertEquals(42.1425, ui.txLat!!, 1e-6)
        assertEquals(-80.0615, ui.txLon!!, 1e-6)
        assertEquals(15f, ui.antennaHeightMeters, 1e-3f)
        assertEquals(30f, ui.txPowerDbm, 1e-3f)
        assertEquals(5.8f, ui.antennaGainDbi, 1e-3f)
        assertNull(ui.rxLat)
        assertNull(ui.rxNode)
        assertTrue(ui.isCalculating || coordinator.hasActiveJob("viewshed_calculate"))

        viewshedVm.cancelActiveCalculations()
    }

    @Test
    fun `bridge node handoff clears unpositioned opposite endpoint then auto solves when both nodes set`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewportHolder = MapViewportStateHolder()
        val coordinator = RfWorkspaceCoordinator()
        val bridgeVm = BridgeViewModel(app, viewportHolder, coordinator)

        val nodeA = sampleNode(
            num = 201L,
            id = "!000000c9",
            longName = "West Repeater",
            shortName = "WRPT",
            hwModel = "STATION_G2",
            role = "ROUTER",
            lat = 42.1100,
            lon = -80.1400
        )
        val nodeB = sampleNode(
            num = 202L,
            id = "!000000ca",
            longName = "East Field",
            shortName = "EFLD",
            hwModel = "HELTEC_V3",
            role = "CLIENT",
            lat = 42.2000,
            lon = -79.9600
        )

        val assignedA = bridgeVm.applyNodeHandoffForEndpoint(BridgePlacementTarget.TX, nodeA, autoSolveIfReady = true)
        assertTrue(assignedA)
        val afterA = bridgeVm.uiState.value
        assertEquals(WorkspaceMode.BRIDGE, coordinator.state.value.activeMode)
        assertEquals(42.1100, afterA.txLat!!, 1e-6)
        assertEquals(15f, afterA.txAntennaHeightMeters, 1e-3f)
        assertEquals(30f, afterA.txPowerDbm, 1e-3f)
        assertNull(afterA.rxLat)
        assertNull(afterA.rxLon)
        assertFalse(afterA.isCalculating)

        val assignedB = bridgeVm.applyNodeHandoffForEndpoint(BridgePlacementTarget.RX, nodeB, autoSolveIfReady = true)
        assertTrue(assignedB)
        val afterB = bridgeVm.uiState.value
        assertEquals(42.2000, afterB.rxLat!!, 1e-6)
        assertEquals(-79.9600, afterB.rxLon!!, 1e-6)
        assertEquals(10f, afterB.rxAntennaHeightMeters, 1e-3f)
        assertEquals(22f, afterB.rxPowerDbm, 1e-3f)
        assertTrue(afterB.isCalculating || coordinator.hasActiveJob("bridge_calculate"))

        bridgeVm.cancelActiveCalculations()
    }

    @Test
    fun `coordinator cancels scoped background jobs when pin drag starts`() {
        val coordinator = RfWorkspaceCoordinator()
        val scope = CoroutineScope(Dispatchers.Default)
        val viewshedJob = scope.launch { delay(5_000L) }
        val bridgeJob = scope.launch { delay(5_000L) }
        val meshLosJob = scope.launch { delay(5_000L) }

        coordinator.registerJob("viewshed_calculate", viewshedJob)
        coordinator.registerJob("bridge_calculate", bridgeJob)
        coordinator.registerJob("mesh_los_probe", meshLosJob)

        coordinator.onPinDragStarted(WorkspaceMode.VIEWSHED)
        assertTrue(viewshedJob.isCancelled)
        assertFalse(bridgeJob.isCancelled)
        assertFalse(meshLosJob.isCancelled)

        coordinator.onPinDragStarted(WorkspaceMode.BRIDGE)
        assertTrue(bridgeJob.isCancelled)
        assertFalse(meshLosJob.isCancelled)

        coordinator.onPinDragStarted(WorkspaceMode.MESH)
        assertTrue(meshLosJob.isCancelled)
    }

    @Test
    fun `node inspector displays unified phase 2 handoff labels`() {
        val node = sampleNode(
            num = 301L,
            id = "!0000012d",
            longName = "Harbor Tower",
            shortName = "HRBR",
            hwModel = "RAK4631",
            role = "ROUTER",
            lat = 42.1350,
            lon = -80.0800
        )

        var viewshedTapped = false
        var bridgeATapped = false
        var bridgeBTapped = false

        composeRule.setContent {
            LEMeshTheme {
                NodeInspectorBottomSheet(
                    node = node,
                    selfNode = null,
                    onDismiss = {},
                    onAssignViewshedTx = { viewshedTapped = true },
                    onAssignBridgeTx = { bridgeATapped = true },
                    onAssignBridgeRx = { bridgeBTapped = true }
                )
            }
        }

        composeRule.onNodeWithText("Viewshed from Node").assertIsDisplayed()
        composeRule.onNodeWithText("Set Bridge A").assertIsDisplayed()
        composeRule.onNodeWithText("Set Bridge B").assertIsDisplayed()

        composeRule.onNodeWithTag("inspector_viewshed_tx_button").performScrollTo().performClick()
        assertTrue(viewshedTapped)
        composeRule.onNodeWithTag("inspector_bridge_tx_button").performScrollTo().performClick()
        assertTrue(bridgeATapped)
        composeRule.onNodeWithTag("inspector_bridge_rx_button").performScrollTo().performClick()
        assertTrue(bridgeBTapped)
    }

    @Test
    fun `unified workspace adapts between compact bottom navigation and expanded split pane`() {
        var compactMode = WorkspaceMode.MESH
        composeRule.setContent {
            LEMeshTheme {
                Box(modifier = Modifier.size(380.dp, 800.dp)) {
                    MeshRfWorkspaceScreen(
                        activeMode = compactMode,
                        viewportState = MapViewportState(),
                        meshState = NodeMapUiState(),
                        viewshedState = ViewshedUiState(),
                        bridgeState = BridgeUiState(),
                        onSwitchMode = { compactMode = it }
                    )
                }
            }
        }

        composeRule.onNodeWithTag("nav_tab_map").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_tab_viewshed").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_tab_bridge").assertIsDisplayed()
        composeRule.onNodeWithTag("workspace_side_panel").assertDoesNotExist()

        composeRule.onNodeWithTag("nav_tab_viewshed").performClick()
        composeRule.waitForIdle()
        assertEquals(WorkspaceMode.VIEWSHED, compactMode)

        composeRule.onNodeWithTag("nav_tab_bridge").performClick()
        composeRule.waitForIdle()
        assertEquals(WorkspaceMode.BRIDGE, compactMode)
    }

    @Test
    fun `expanded workspace renders split pane side panel per active mode`() {
        composeRule.setContent {
            LEMeshTheme {
                Box(modifier = Modifier.size(720.dp, 800.dp)) {
                    MeshRfWorkspaceScreen(
                        activeMode = WorkspaceMode.VIEWSHED,
                        viewportState = MapViewportState(),
                        meshState = NodeMapUiState(),
                        viewshedState = ViewshedUiState(),
                        bridgeState = BridgeUiState()
                    )
                }
            }
        }

        composeRule.onNodeWithTag("workspace_side_panel").assertIsDisplayed()
        composeRule.onNodeWithTag("viewshed_side_panel").assertIsDisplayed()
    }
}
