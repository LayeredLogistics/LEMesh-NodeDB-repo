package us.lemesh.nodedb

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import us.lemesh.nodedb.ui.MapBaseLayer
import us.lemesh.nodedb.ui.MapToolsBottomSheet
import us.lemesh.nodedb.ui.MapToolsLegendMode
import us.lemesh.nodedb.ui.NodeMapFiltersSheet
import us.lemesh.nodedb.ui.NodeMapUiState
import us.lemesh.nodedb.ui.RfRadioParamsSection
import us.lemesh.nodedb.ui.theme.LEMeshTheme
import us.lemesh.nodedb.ui.theme.NodeMultiHopSky
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StreamlinedUiComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `rf radio params starts with advanced section collapsed and expands on tap`() {
        composeRule.setContent {
            LEMeshTheme {
                RfRadioParamsSection(
                    radiusLabel = "Max Calculation Radius",
                    radiusKm = 25,
                    radiusTagPrefix = "radius_chip_",
                    selectedRadiusColor = NodeMultiHopSky,
                    frequencyText = "906.875",
                    txPowerDbm = 30f,
                    antennaGainDbi = 5.8f,
                    sensitivityDbm = -130f,
                    opacityLabel = "Heatmap Opacity",
                    overlayOpacity = 0.68f,
                    errorMessage = null,
                    onRadiusKmChange = {},
                    onFrequencyChange = {},
                    onTxPowerChange = {},
                    onAntennaGainChange = {},
                    onSensitivityChange = {},
                    onOpacityChange = {},
                    onDismissError = {}
                )
            }
        }

        // Radius chips are visible upfront
        composeRule.onNodeWithTag("radius_chip_25").assertIsDisplayed()
        // Advanced RF accordion starts collapsed
        composeRule.onNodeWithTag("radius_chip_advanced_rf_toggle").assertIsDisplayed()
        composeRule.onNodeWithText("Freq (MHz)").assertDoesNotExist()
        composeRule.onNodeWithText("TX Power").assertDoesNotExist()

        // Tapping toggle reveals Freq, TX Power, Ant Gain, RX Sens, Opacity
        composeRule.onNodeWithTag("radius_chip_advanced_rf_toggle").performClick()
        composeRule.onNodeWithText("Freq (MHz)").assertIsDisplayed()
        composeRule.onNodeWithText("TX Power").assertIsDisplayed()
    }

    @Test
    fun `map tools bottom sheet displays basemaps, contextual legend, and invokes actions`() {
        var selectedLayer: MapBaseLayer? = null
        var exported = false
        var clearedOverlay = false

        composeRule.setContent {
            LEMeshTheme {
                MapToolsBottomSheet(
                    baseLayer = MapBaseLayer.DARK_MATTER,
                    legendMode = MapToolsLegendMode.BRIDGE,
                    sensitivityDbm = -130.0,
                    testTagPrefix = "bridge",
                    onSelectBaseLayer = { selectedLayer = it },
                    onExport = { exported = true },
                    hasClearableOverlay = true,
                    onClearOverlay = { clearedOverlay = true },
                    hasResettablePins = true,
                    onResetPins = {},
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithTag("bridge_base_layer_osm_standard").performScrollTo().performClick()
        assertEquals(MapBaseLayer.OSM_STANDARD, selectedLayer)

        composeRule.onNodeWithText("Mutual Bridge RSSI").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("bridge_clear_overlay_button").performScrollTo().performClick()
        assertTrue(clearedOverlay)

        composeRule.onNodeWithTag("bridge_export_button").performScrollTo().performClick()
        assertTrue(exported)
    }

    @Test
    fun `node map filters sheet toggles visibility and triggers field tools`() {
        var mqttToggled = false
        var losToggled = false
        var directLinksOpened = false

        composeRule.setContent {
            LEMeshTheme {
                NodeMapFiltersSheet(
                    uiState = NodeMapUiState(
                        hasLoadedFile = true,
                        loadedFileName = "sample.json",
                        totalCount = 12,
                        plottedGpsCount = 9
                    ),
                    onToggleShowMqtt = { mqttToggled = true },
                    onToggleRfSightlines = {},
                    onToggleFavoritesOnly = {},
                    onToggleLosMode = { losToggled = true },
                    onToggleMeasureMode = {},
                    onOpenDirectLinksSheet = { directLinksOpened = true },
                    onOpenNoGpsSheet = {},
                    onLoadJsonClick = {},
                    onClearJsonClick = {},
                    onResetFilters = {},
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithTag("chip_show_mqtt").performScrollTo().performClick()
        assertTrue(mqttToggled)

        composeRule.onNodeWithTag("chip_los_profile").performScrollTo().performClick()
        assertTrue(losToggled)

        composeRule.onNodeWithTag("chip_direct_links").performScrollTo().performClick()
        assertTrue(directLinksOpened)
    }

    @Test
    fun `unified top card hides standby buttons when loaded and wraps viewshed and bridge with collapse toggle`() {
        composeRule.setContent {
            var activeMode by remember {
                mutableStateOf(us.lemesh.nodedb.ui.state.WorkspaceMode.MESH)
            }
            var loaded by remember { mutableStateOf(false) }

            LEMeshTheme {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Button(
                        onClick = { loaded = !loaded },
                        modifier = Modifier.testTag("test_toggle_loaded")
                    ) { androidx.compose.material3.Text("Loaded") }
                    androidx.compose.material3.Button(
                        onClick = { activeMode = us.lemesh.nodedb.ui.state.WorkspaceMode.VIEWSHED },
                        modifier = Modifier.testTag("test_mode_viewshed")
                    ) { androidx.compose.material3.Text("Viewshed") }
                    androidx.compose.material3.Button(
                        onClick = { activeMode = us.lemesh.nodedb.ui.state.WorkspaceMode.BRIDGE },
                        modifier = Modifier.testTag("test_mode_bridge")
                    ) { androidx.compose.material3.Text("Bridge") }

                    us.lemesh.nodedb.ui.WorkspaceTopFloatingOverlay(
                        activeMode = activeMode,
                        meshState = NodeMapUiState(hasLoadedFile = loaded, loadedFileName = if (loaded) "mesh.json" else null),
                        viewshedState = us.lemesh.nodedb.ui.ViewshedUiState(),
                        bridgeState = us.lemesh.nodedb.ui.BridgeUiState(),
                        activeFilterCount = 0,
                        layersBadgeCount = 0,
                        hasFilteredSubset = false,
                        selectedBridgeObstruction = null,
                        activeBridgeCandidateRank = null,
                        onSearchQueryChange = {},
                        onOpenFilters = {},
                        onOpenLayers = {},
                        onLoadJsonClick = {},
                        onToggleAddNodeMode = {},
                        onResetAllFilters = {},
                        onCancelAggregateCompute = {},
                        onDismissMeshError = {},
                        onDismissAggregateError = {},
                        onSetViewshedPlacementMode = {},
                        onDropViewshedAtCenter = {},
                        onSwapViewshedTxRx = {},
                        onDismissViewshedError = {},
                        onSetBridgePlacementMode = {},
                        onDropBridgeAtCenter = {},
                        onSwapBridgeTxRx = {},
                        onDismissBridgeError = {},
                        onDismissBridgeObstructionCallout = {},
                        onOpenOfflineMaps = {},
                        liveSyncStatus = us.lemesh.nodedb.data.LiveSyncStatus.CONNECTED,
                        onOpenLiveSync = {}
                    )
                }
            }
        }

        // Mesh standby shows Load JSON & Add Node
        composeRule.onNodeWithTag("load_json_button").assertIsDisplayed()
        composeRule.onNodeWithTag("standby_add_node_button").assertIsDisplayed()
        composeRule.onNodeWithTag("chip_saved_layers").assertIsDisplayed()
        composeRule.onNodeWithTag("merged_status_pill").assertIsDisplayed()

        // Loaded NodeDB hides standby Load JSON & Add Node row
        composeRule.onNodeWithTag("test_toggle_loaded").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("load_json_button").assertDoesNotExist()
        composeRule.onNodeWithTag("standby_add_node_button").assertDoesNotExist()
        composeRule.onNodeWithTag("map_search_input").assertIsDisplayed()

        // Switching to Viewshed keeps unified header, viewshed layers trigger & collapse toggle
        composeRule.onNodeWithTag("test_mode_viewshed").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("LEMesh Horizon").assertIsDisplayed()
        composeRule.onNodeWithTag("viewshed_layers_button").assertIsDisplayed()
        composeRule.onNodeWithTag("viewshed_top_mode_auto").assertIsDisplayed()

        composeRule.onNodeWithTag("top_card_collapse_toggle").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("viewshed_top_mode_auto").assertDoesNotExist()
        composeRule.onNodeWithTag("top_card_collapse_toggle").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("viewshed_top_mode_auto").assertIsDisplayed()

        // Switching to Bridge keeps unified header & bridge placement controls
        composeRule.onNodeWithTag("test_mode_bridge").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("LEMesh Horizon").assertIsDisplayed()
        composeRule.onNodeWithTag("bridge_top_mode_auto").assertIsDisplayed()
    }
}
