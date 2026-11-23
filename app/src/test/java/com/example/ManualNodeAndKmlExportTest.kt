package com.example

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.example.data.CustomNodeStore
import com.example.data.CustomNodeStoreData
import com.example.data.MapExportEngine
import com.example.data.MapKmlExportBuilder
import com.example.data.MeshNode
import com.example.data.NodeColorPalette
import com.example.data.NodeDbParser
import com.example.data.NodeOverride
import com.example.data.RelayBridgeEngine
import com.example.ui.BridgeUiState
import com.example.ui.MainScreen
import com.example.ui.MapExportHost
import com.example.ui.NodeEditorBottomSheet
import com.example.ui.NodeEditorDraft
import com.example.ui.NodeInspectorBottomSheet
import com.example.ui.NodeMapUiState
import com.example.ui.NodeMapViewModel
import com.example.ui.ViewshedUiState
import com.example.ui.theme.LEMeshTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManualNodeAndKmlExportTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `custom node store persists manual nodes edits and roundtrips via backup json`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storeFile = File(context.filesDir, "test_custom_nodes_store.json")
        if (storeFile.exists()) storeFile.delete()

        val imported = listOf(
            MeshNode(
                num = 1001L,
                id = "!000003e9",
                longName = "Hilltop Relay",
                shortName = "HILL",
                hwModel = "RAK4631",
                role = "ROUTER",
                latitude = 42.1100,
                longitude = -80.1200,
                altitude = 320,
                hopsAway = 0,
                viaMqtt = false,
                isFavorite = false,
                isSelf = true,
                snr = 8.5,
                rssi = -76,
                lastHeard = 1700000000L,
                deviceMetrics = null,
                environmentMetrics = null,
                rawJson = "{}"
            )
        )

        val manualNode = MeshNode(
            num = 0xF0001234L,
            id = "!f0001234",
            longName = "Ridge Portable",
            shortName = "RIDG",
            hwModel = "HELTEC_V3",
            role = "CLIENT",
            latitude = 42.1550,
            longitude = -80.0450,
            altitude = 285,
            hopsAway = 1,
            viaMqtt = false,
            isFavorite = true,
            isSelf = false,
            snr = 5.0,
            rssi = -92,
            lastHeard = 1700000500L,
            deviceMetrics = null,
            environmentMetrics = null,
            rawJson = "{}",
            customColorArgb = NodeColorPalette.PRESETS[4].argb, // Coral
            isManual = true,
            notes = "Solar pole 6m",
            isEdited = false
        )

        val indigoArgb = NodeColorPalette.PRESETS[6].argb
        val override = NodeOverride(
            targetKey = imported[0].stableKey,
            id = imported[0].id,
            num = imported[0].num,
            longName = "Hilltop Solar Mast",
            shortName = "HILLS",
            hwModel = "STATION_G2",
            role = "ROUTER",
            latitude = 42.1105,
            longitude = -80.1205,
            altitude = 335,
            hopsAway = 0,
            viaMqtt = false,
            isFavorite = true,
            isSelf = true,
            snr = 9.0,
            rssi = -74,
            notes = "Upgraded cavity filter",
            customColorArgb = indigoArgb
        )

        val store = CustomNodeStoreData(
            manualNodes = listOf(manualNode),
            overrides = mapOf(imported[0].stableKey to override)
        )
        CustomNodeStore.saveToFile(storeFile, store)
        val reloaded = CustomNodeStore.loadFromFile(storeFile)
        assertEquals(1, reloaded.manualNodes.size)
        assertEquals(1, reloaded.overrides.size)

        val merged = CustomNodeStore.mergeNodes(imported, reloaded)
        assertEquals(2, merged.totalCount)
        assertEquals(2, merged.plottedGpsCount)

        val editedImported = merged.nodes.first { it.id == "!000003e9" }
        assertEquals("Hilltop Solar Mast", editedImported.longName)
        assertEquals("STATION_G2", editedImported.hwModel)
        assertEquals(indigoArgb, editedImported.customColorArgb)
        assertEquals("Upgraded cavity filter", editedImported.notes)
        assertTrue(editedImported.isEdited)
        assertFalse(editedImported.isManual)

        val coralArgb = NodeColorPalette.PRESETS[4].argb
        val restoredManual = merged.nodes.first { it.id == "!f0001234" }
        assertEquals(coralArgb, restoredManual.customColorArgb)
        assertEquals("Solar pole 6m", restoredManual.notes)
        assertTrue(restoredManual.isManual)

        val backupJson = CustomNodeStore.buildNodeDbBackupJson(
            nodes = merged.nodes,
            selfNode = merged.selfNode,
            sourceFileName = "erie_mesh.json"
        )
        val reparsed = NodeDbParser.parse(backupJson).getOrThrow()
        assertEquals(2, reparsed.totalCount)
        assertEquals("!000003e9", reparsed.selfNode?.id)

        val reparsedManual = reparsed.nodes.first { it.id == "!f0001234" }
        assertTrue(reparsedManual.isManual)
        assertEquals(coralArgb, reparsedManual.customColorArgb)
        assertEquals("Solar pole 6m", reparsedManual.notes)
        assertEquals("Ridge Portable", reparsedManual.longName)

        val reparsedEdited = reparsed.nodes.first { it.id == "!000003e9" }
        assertTrue(reparsedEdited.isEdited)
        assertEquals(indigoArgb, reparsedEdited.customColorArgb)
        assertEquals("Upgraded cavity filter", reparsedEdited.notes)

        if (storeFile.exists()) storeFile.delete()
    }

    @Test
    fun `viewmodel saves manual nodes across restarts and supports editing and reverting`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val storeFile = File(app.filesDir, "custom_nodes_store.json")
        val cacheJsonFile = File(app.filesDir, "cached_nodedb.json")
        val cacheMetaFile = File(app.filesDir, "cached_nodedb_meta.json")
        if (storeFile.exists()) storeFile.delete()
        if (cacheJsonFile.exists()) cacheJsonFile.delete()
        if (cacheMetaFile.exists()) cacheMetaFile.delete()

        val vm1 = NodeMapViewModel(app)
        delay(200L)

        vm1.openNewNodeEditor(lat = 42.1292, lon = -80.0851)
        val draft = vm1.uiState.value.editingDraft!!
        val halfCoordErr = vm1.saveNodeEditorDraft(draft.copy(longitudeText = ""))
        assertNotNull(halfCoordErr)

        val roseArgb = NodeColorPalette.PRESETS[5].argb
        val tealArgb = NodeColorPalette.PRESETS[7].argb
        val err = vm1.saveNodeEditorDraft(
            draft.copy(
                longName = "East Pier Beacon",
                shortName = "PIER",
                hwModelText = "TBEAM",
                roleText = "ROUTER",
                altitudeText = "185",
                notesText = "Roof tripod mount",
                customColorArgb = roseArgb
            )
        )
        assertNull(err)
        delay(200L)

        assertEquals(1, vm1.uiState.value.totalCount)
        assertEquals(1, vm1.uiState.value.manualNodeCount)
        assertEquals(1, vm1.uiState.value.plottedGpsCount)
        assertEquals(roseArgb, vm1.uiState.value.allNodes.first().customColorArgb)

        val vm2 = NodeMapViewModel(app)
        delay(250L)
        assertEquals(1, vm2.uiState.value.manualNodeCount)
        val restored = vm2.uiState.value.allNodes.first()
        assertEquals("East Pier Beacon", restored.longName)
        assertEquals("Roof tripod mount", restored.notes)
        assertEquals(roseArgb, restored.customColorArgb)

        vm2.openEditNodeEditor(restored)
        val editDraft = vm2.uiState.value.editingDraft!!
        assertNull(
            vm2.saveNodeEditorDraft(
                editDraft.copy(
                    longName = "West Pier Beacon",
                    customColorArgb = tealArgb
                )
            )
        )
        delay(150L)
        val updated = vm2.uiState.value.allNodes.first()
        assertEquals("West Pier Beacon", updated.longName)
        assertEquals(tealArgb, updated.customColorArgb)

        vm2.updateSearchQuery("tripod")
        assertEquals(1, vm2.uiState.value.filteredMapNodes.size)
        vm2.updateSearchQuery("TBEAM")
        assertEquals(1, vm2.uiState.value.filteredMapNodes.size)
        vm2.updateSearchQuery("")

        vm2.deleteManualNode(updated)
        delay(150L)
        assertEquals(0, vm2.uiState.value.totalCount)
        assertEquals(0, vm2.uiState.value.manualNodeCount)

        if (storeFile.exists()) storeFile.delete()
    }

    @Test
    fun `kml exporter converts argb to kml aabbggrr and generates google maps earth valid kml`() {
        val coralArgb = NodeColorPalette.PRESETS[4].argb // 0xFFEF4444
        val amberArgb = NodeColorPalette.PRESETS[2].argb
        val purpleArgb = NodeColorPalette.PRESETS[3].argb
        assertEquals("ff5ec522", MapKmlExportBuilder.argbToKmlColor(0xFF22C55E.toInt()))
        assertEquals("ff4444ef", MapKmlExportBuilder.argbToKmlColor(coralArgb))

        val selfNode = MeshNode(
            num = 1L,
            id = "!00000001",
            longName = "Base & Command <HQ>",
            shortName = "BASE",
            hwModel = "STATION_G2",
            role = "ROUTER",
            latitude = 42.1292,
            longitude = -80.0851,
            altitude = 210,
            hopsAway = 0,
            viaMqtt = false,
            isFavorite = false,
            isSelf = true,
            snr = null,
            rssi = null,
            lastHeard = 1700000000L,
            deviceMetrics = null,
            environmentMetrics = null,
            rawJson = "{}",
            customColorArgb = amberArgb,
            isManual = false,
            notes = "Roof mast 12m"
        )
        val peerNode = MeshNode(
            num = 2L,
            id = "!00000002",
            longName = "Harbor Scout",
            shortName = "HARB",
            hwModel = "HELTEC_V3",
            role = "CLIENT",
            latitude = 42.1500,
            longitude = -80.0600,
            altitude = 180,
            hopsAway = 0,
            viaMqtt = false,
            isFavorite = true,
            isSelf = false,
            snr = 7.2,
            rssi = -84,
            lastHeard = 1700000100L,
            deviceMetrics = null,
            environmentMetrics = null,
            rawJson = "{}",
            customColorArgb = purpleArgb,
            isManual = true,
            notes = "Battery solar box"
        )

        val state = NodeMapUiState(
            hasLoadedFile = true,
            loadedFileName = "erie_backup.json",
            allNodes = listOf(selfNode, peerNode),
            selfNode = selfNode,
            totalCount = 2,
            plottedGpsCount = 2,
            rfCount = 2,
            showRfSightlines = true
        )

        val kml = MapExportEngine.buildNodeMapKml(uiState = state, exportedAtIso = "2026-11-23T12:00:00Z")
        assertTrue(kml.contains("""<kml xmlns="http://www.opengis.net/kml/2.2">"""))
        assertTrue(kml.contains("Base &amp; Command &lt;HQ&gt;"))
        assertTrue(kml.contains("<color>${MapKmlExportBuilder.argbToKmlColor(purpleArgb)}</color>"))
        assertTrue(kml.contains("<name>Mesh Nodes</name>"))
        assertTrue(kml.contains("<name>RF Sightlines</name>"))
        assertTrue(kml.contains("<ExtendedData>"))
        assertTrue(kml.contains("Battery solar box"))
        assertTrue(kml.contains("-80.060000,42.150000,180"))
        assertTrue(kml.contains("</kml>"))

        val viewshedKml = MapExportEngine.buildViewshedKml(
            uiState = ViewshedUiState(txLat = 42.12, txLon = -80.08, siteLabel = "Hill TX"),
            exportedAtIso = "2026-11-23T12:00:00Z"
        )
        assertTrue(viewshedKml.contains("<kml xmlns=\"http://www.opengis.net/kml/2.2\">"))
        assertTrue(viewshedKml.contains("Hill TX"))

        val bridgeKml = MapExportEngine.buildBridgeKml(
            uiState = BridgeUiState(txLat = 42.12, txLon = -80.08, rxLat = 42.18, rxLon = -80.02),
            exportedAtIso = "2026-11-23T12:00:00Z"
        )
        assertTrue(bridgeKml.contains("<kml xmlns=\"http://www.opengis.net/kml/2.2\">"))
        assertTrue(bridgeKml.contains("${RelayBridgeEngine.DEFAULT_TX_POWER_DBM.toInt()} dBm"))
    }

    @Test
    fun `node editor sheet allows selecting color swatch editing specs and saving`() {
        var savedDraft: NodeEditorDraft? = null
        val coralArgb = NodeColorPalette.PRESETS[4].argb

        val initial = NodeEditorDraft(
            originalKey = null,
            isCreatingNew = true,
            isManual = true,
            isEdited = false,
            id = "!deadbeef",
            num = 0xDEADBEEFL,
            longName = "Initial Name",
            shortName = "INIT",
            latitudeText = "42.129200",
            longitudeText = "-80.085100",
            altitudeText = "220",
            hwModelText = "HELTEC_V3",
            roleText = "CLIENT",
            hopsAwayText = "0",
            viaMqtt = false,
            isFavorite = false,
            isSelf = false,
            snrText = "",
            rssiText = "",
            notesText = "",
            customColorArgb = null
        )

        composeRule.setContent {
            LEMeshTheme {
                NodeEditorBottomSheet(
                    initialDraft = initial,
                    onSave = {
                        savedDraft = it
                        null
                    },
                    onPickOnMap = {},
                    onDismiss = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("color_swatch_coral").performScrollTo().performClick()
        composeRule.onNodeWithTag("editor_long_name_input").performScrollTo().performTextReplacement("Summit Relay")
        composeRule.onNodeWithTag("editor_notes_input").performScrollTo().performTextReplacement("10m fiberglass mast")
        composeRule.onNodeWithTag("save_node_button").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertNotNull(savedDraft)
        assertEquals("Summit Relay", savedDraft?.longName)
        assertEquals("10m fiberglass mast", savedDraft?.notesText)
        assertEquals(coralArgb, savedDraft?.customColorArgb)
    }

    @Test
    fun `node inspector shows manual badge notes and invokes edit callback`() {
        var editedClicked: MeshNode? = null
        val indigoArgb = NodeColorPalette.PRESETS[6].argb
        val node = MeshNode(
            num = 77L,
            id = "!0000004d",
            longName = "Tactical Beacon",
            shortName = "TACT",
            hwModel = "RAK4631",
            role = "REPEATER",
            latitude = 42.12,
            longitude = -80.08,
            altitude = 240,
            hopsAway = 0,
            viaMqtt = false,
            isFavorite = false,
            isSelf = false,
            snr = 6.5,
            rssi = -81,
            lastHeard = 1700000000L,
            deviceMetrics = null,
            environmentMetrics = null,
            rawJson = "{}",
            customColorArgb = indigoArgb,
            isManual = true,
            notes = "Check battery voltage monthly"
        )

        composeRule.setContent {
            LEMeshTheme {
                NodeInspectorBottomSheet(
                    node = node,
                    selfNode = null,
                    onDismiss = {},
                    onEditNode = { editedClicked = it }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("MANUAL").assertIsDisplayed()
        composeRule.onNodeWithText("Check battery voltage monthly").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("inspector_edit_node_button").performClick()
        composeRule.waitForIdle()

        assertEquals(node, editedClicked)
    }

    @Test
    fun `export dialog and main screen standby show add node kml and nodedb backup options`() {
        var addNodeToggled = false
        composeRule.setContent {
            LEMeshTheme {
                MainScreen(
                    uiState = NodeMapUiState(),
                    onLoadJsonClick = {},
                    onSearchQueryChange = {},
                    onToggleShowMqtt = {},
                    onToggleRfSightlines = {},
                    onToggleFavoritesOnly = {},
                    onSelectBaseLayer = {},
                    onOpenNoGpsSheet = {},
                    onCloseNoGpsSheet = {},
                    onNoGpsSearchQueryChange = {},
                    onSelectNode = {},
                    onMapNodeClick = {},
                    onToggleMeasureMode = {},
                    onClearMeasureAnchors = {},
                    onUseSelfAsAnchorA = {},
                    onOpenDirectLinksSheet = {},
                    onCloseDirectLinksSheet = {},
                    onToggleDirectLinksSort = {},
                    onMeasureDirectLinkFromSelf = {},
                    onToggleLosMode = {},
                    onMapCoordinateTap = { _, _ -> },
                    onToggleLosDrawerExpanded = {},
                    onLosTxHeightChange = {},
                    onLosRxHeightChange = {},
                    onLosFrequencyChange = {},
                    onUseSelfAsLosPointA = {},
                    onSwapLosEndpoints = {},
                    onClearLosEndpoints = {},
                    onRetryLosElevation = {},
                    onUseFlatTerrainFallback = {},
                    onAnalyzeLosBetweenNodes = { _, _ -> },
                    onFitBounds = {},
                    onDismissError = {},
                    onToggleAddNodeMode = { addNodeToggled = true }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("standby_add_node_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertTrue(addNodeToggled)
    }

    @Test
    fun `export dialog shows kml and nodedb json backup format cards`() {
        composeRule.setContent {
            LEMeshTheme {
                MapExportHost(
                    visible = true,
                    filePrefix = "lemesh_map",
                    mapViewProvider = { null },
                    buildGeoJson = { "{}" },
                    buildSummary = {
                        MapExportEngine.buildNodeMapSummary(NodeMapUiState(), emptyList())
                    },
                    buildKml = { "<kml/>" },
                    buildNodeDbBackupJson = { "{}" },
                    onDismiss = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("export_format_kml").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("export_format_nodedb_json").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("export_format_geojson").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("export_format_png").performScrollTo().assertIsDisplayed()
    }
}
