package us.lemesh.nodedb

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import us.lemesh.nodedb.data.CustomNodeStore
import us.lemesh.nodedb.data.CustomNodeStoreData
import us.lemesh.nodedb.data.DeviceMetrics
import us.lemesh.nodedb.data.MapExportEngine
import us.lemesh.nodedb.data.MeshNode
import us.lemesh.nodedb.data.NodeColorPalette
import us.lemesh.nodedb.data.NodeDbParser
import us.lemesh.nodedb.data.NodeOverride
import us.lemesh.nodedb.ui.MainScreen
import us.lemesh.nodedb.ui.NodeMapUiState
import us.lemesh.nodedb.ui.NodeMapViewModel
import us.lemesh.nodedb.ui.PendingNodeDbImport
import us.lemesh.nodedb.ui.theme.LEMeshTheme
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NodeDbMergeImportTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Context>() as Application
        clearNodeDbCacheFiles()
    }

    @After
    fun tearDown() {
        clearNodeDbCacheFiles()
    }

    private fun clearNodeDbCacheFiles() {
        listOf("cached_nodedb.json", "cached_nodedb_meta.json", "custom_nodes_store.json").forEach { name ->
            val file = File(app.filesDir, name)
            if (file.exists()) file.delete()
        }
    }

    private fun waitUntil(timeoutMs: Long = 3000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(15L)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Timed out waiting for async ViewModel state transition.", predicate())
    }

    @Test
    fun `mergeImportedNodeDb preserves CustomNodeStore metadata while updating telemetry and coordinates`() {
        val existingBase = listOf(
            MeshNode(
                num = 0x1001L,
                id = "!00001001",
                longName = "Original Lake Relay",
                shortName = "ORIG",
                hwModel = "HELTEC_V3",
                role = "CLIENT",
                latitude = 42.1000,
                longitude = -80.0000,
                altitude = 200,
                hopsAway = 1,
                viaMqtt = false,
                isFavorite = false,
                isSelf = true,
                snr = 4.0,
                rssi = -95,
                lastHeard = 1734000000L,
                deviceMetrics = DeviceMetrics(batteryLevel = 62, voltage = 3.85, channelUtilization = 11.0, airUtilTx = 1.2, uptimeSeconds = 5000L),
                environmentMetrics = null,
                rawJson = "{}"
            )
        )

        val amberArgb = NodeColorPalette.PRESETS[2].argb
        val override = NodeOverride(
            targetKey = existingBase[0].stableKey,
            id = "!00001001",
            num = 0x1001L,
            longName = "Hilltop Mast Site",
            shortName = "HILL",
            hwModel = "STATION_G2",
            role = "ROUTER",
            latitude = 42.1000,
            longitude = -80.0000,
            altitude = 200,
            hopsAway = 1,
            viaMqtt = false,
            isFavorite = true,
            isSelf = true,
            snr = 4.0,
            rssi = -95,
            notes = "12m fiberglass mast on roof",
            customColorArgb = amberArgb,
            overrideLocation = false,
            overrideTelemetry = false
        )

        val manualNode = MeshNode(
            num = 0xF0001111L,
            id = "!f0001111",
            longName = "Portable Field Box",
            shortName = "PORT",
            hwModel = "TBEAM",
            role = "CLIENT",
            latitude = 42.1400,
            longitude = -80.0600,
            altitude = 240,
            hopsAway = 0,
            viaMqtt = false,
            isFavorite = false,
            isSelf = false,
            snr = null,
            rssi = null,
            lastHeard = 1734000100L,
            deviceMetrics = null,
            environmentMetrics = null,
            rawJson = "{}",
            customColorArgb = NodeColorPalette.PRESETS[4].argb,
            isManual = true,
            notes = "Battery box 2"
        )

        val storeData = CustomNodeStoreData(
            manualNodes = listOf(manualNode),
            overrides = mapOf(override.targetKey to override)
        )

        val freshImported = listOf(
            MeshNode(
                num = 0x1001L,
                id = "!00001001",
                longName = "Original Lake Relay",
                shortName = "ORIG",
                hwModel = "HELTEC_V3",
                role = "CLIENT",
                latitude = 42.1550,
                longitude = -80.0450,
                altitude = 225,
                hopsAway = 0,
                viaMqtt = false,
                isFavorite = false,
                isSelf = true,
                snr = 9.5,
                rssi = -72,
                lastHeard = 1735000999L,
                deviceMetrics = DeviceMetrics(batteryLevel = 88, voltage = 4.12, channelUtilization = 14.5, airUtilTx = 2.0, uptimeSeconds = 12345L),
                environmentMetrics = null,
                rawJson = "{}"
            ),
            MeshNode(
                num = 0x2002L,
                id = "!00002002",
                longName = "West Beacon",
                shortName = "WEST",
                hwModel = "RAK4631",
                role = "ROUTER_LATE",
                latitude = 42.1800,
                longitude = -80.1200,
                altitude = 260,
                hopsAway = 1,
                viaMqtt = false,
                isFavorite = false,
                isSelf = false,
                snr = 6.25,
                rssi = -84,
                lastHeard = 1735000888L,
                deviceMetrics = null,
                environmentMetrics = null,
                rawJson = "{}"
            )
        )

        val outcome = CustomNodeStore.mergeImportedNodeDb(existingBase, freshImported, storeData)

        assertEquals(1, outcome.updatedCount)
        assertEquals(1, outcome.addedCount)
        assertEquals(3, outcome.mergedParseResult.totalCount)

        val updatedSite = outcome.mergedParseResult.nodes.first { it.id == "!00001001" }
        // Custom metadata preserved
        assertEquals("Hilltop Mast Site", updatedSite.longName)
        assertEquals("HILL", updatedSite.shortName)
        assertEquals("STATION_G2", updatedSite.hwModel)
        assertEquals("ROUTER", updatedSite.role)
        assertEquals("12m fiberglass mast on roof", updatedSite.notes)
        assertEquals(amberArgb, updatedSite.customColorArgb)
        assertTrue(updatedSite.isFavorite)
        assertTrue(updatedSite.isEdited)
        // Fresh coordinates and telemetry merged
        assertEquals(42.1550, updatedSite.latitude!!, 1e-6)
        assertEquals(-80.0450, updatedSite.longitude!!, 1e-6)
        assertEquals(225, updatedSite.altitude)
        assertEquals(9.5, updatedSite.snr!!, 1e-4)
        assertEquals(-72, updatedSite.rssi)
        assertEquals(0, updatedSite.hopsAway)
        assertEquals(1735000999L, updatedSite.lastHeard)
        assertEquals(88, updatedSite.deviceMetrics?.batteryLevel)

        // New node added and manual node preserved
        val addedBeacon = outcome.mergedParseResult.nodes.firstOrNull { it.id == "!00002002" }
        assertNotNull(addedBeacon)
        assertEquals("West Beacon", addedBeacon?.longName)

        val keptManual = outcome.mergedParseResult.nodes.firstOrNull { it.id == "!f0001111" }
        assertNotNull(keptManual)
        assertTrue(keptManual!!.isManual)
        assertEquals("Portable Field Box", keptManual.longName)
    }

    @Test
    fun `mergeImportedNodeDb respects explicit location override while refreshing telemetry`() {
        val existingBase = listOf(
            MeshNode(
                num = 0x1005L,
                id = "!00001005",
                longName = "Base Node",
                shortName = "BASE",
                hwModel = "HELTEC_V3",
                role = "CLIENT",
                latitude = 42.1000,
                longitude = -80.0000,
                altitude = 200,
                hopsAway = 2,
                viaMqtt = false,
                isFavorite = false,
                isSelf = false,
                snr = 1.0,
                rssi = -102,
                lastHeard = 1734000000L,
                deviceMetrics = null,
                environmentMetrics = null,
                rawJson = "{}"
            )
        )
        val override = NodeOverride(
            targetKey = existingBase[0].stableKey,
            id = "!00001005",
            num = 0x1005L,
            longName = "Surveyed Tower",
            shortName = "SURV",
            hwModel = "HELTEC_V3",
            role = "ROUTER",
            latitude = 42.3333,
            longitude = -80.4444,
            altitude = 310,
            hopsAway = 2,
            viaMqtt = false,
            isFavorite = false,
            isSelf = false,
            snr = 1.0,
            rssi = -102,
            notes = "Surveyed GPS pin",
            customColorArgb = null,
            overrideLocation = true,
            overrideTelemetry = false
        )
        val incoming = listOf(
            existingBase[0].copy(
                latitude = 42.1001,
                longitude = -80.0001,
                altitude = 202,
                hopsAway = 0,
                snr = 7.75,
                rssi = -78
            )
        )

        val outcome = CustomNodeStore.mergeImportedNodeDb(
            existingBaseNodes = existingBase,
            incomingNodes = incoming,
            storeData = CustomNodeStoreData(overrides = mapOf(override.targetKey to override))
        )

        val merged = outcome.mergedParseResult.nodes.single()
        assertEquals(42.3333, merged.latitude!!, 1e-6)
        assertEquals(-80.4444, merged.longitude!!, 1e-6)
        assertEquals(310, merged.altitude)
        assertEquals(0, merged.hopsAway)
        assertEquals(7.75, merged.snr!!, 1e-4)
        assertEquals(-78, merged.rssi)
    }

    @Test
    fun `viewmodel importJsonText prompts Merge vs Replace when DB has nodes and preserves custom edits on Merge`() {
        val initialJson = """
            {
              "myInfo": { "myNodeNum": 1001 },
              "nodes": [
                {
                  "num": 1001,
                  "user": { "id": "!000003e9", "longName": "Lake Base", "shortName": "LAKE", "hwModel": "HELTEC_V3", "role": "CLIENT" },
                  "position": { "latitude": 42.1292, "longitude": -80.0851, "altitude": 210 },
                  "hopsAway": 0,
                  "viaMqtt": false
                },
                {
                  "num": 1002,
                  "user": { "id": "!000003ea", "longName": "Harbor Repeater", "shortName": "HARB", "hwModel": "TBEAM", "role": "CLIENT" },
                  "position": { "latitude": 42.1450, "longitude": -80.0950, "altitude": 205 },
                  "hopsAway": 1,
                  "viaMqtt": false,
                  "snr": 3.5,
                  "rssi": -96
                }
              ]
            }
        """.trimIndent()

        val updateJson = """
            {
              "myInfo": { "myNodeNum": 1001 },
              "nodes": [
                {
                  "num": 1002,
                  "user": { "id": "!000003ea", "longName": "Harbor Repeater", "shortName": "HARB", "hwModel": "TBEAM", "role": "CLIENT" },
                  "position": { "latitude": 42.1600, "longitude": -80.0700, "altitude": 222 },
                  "hopsAway": 0,
                  "viaMqtt": false,
                  "snr": 10.25,
                  "rssi": -69,
                  "lastHeard": 1735001234
                },
                {
                  "num": 1003,
                  "user": { "id": "!000003eb", "longName": "Presque Isle Buoy", "shortName": "BUOY", "hwModel": "RAK4631", "role": "SENSOR" },
                  "position": { "latitude": 42.1700, "longitude": -80.1100, "altitude": 180 },
                  "hopsAway": 1,
                  "viaMqtt": false,
                  "snr": 6.0,
                  "rssi": -88
                }
              ]
            }
        """.trimIndent()

        val replaceJson = """
            {
              "myInfo": { "myNodeNum": 2001 },
              "nodes": [
                {
                  "num": 2001,
                  "user": { "id": "!000007d1", "longName": "Cleveland Base", "shortName": "CLEV", "hwModel": "STATION_G2", "role": "ROUTER" },
                  "position": { "latitude": 41.4993, "longitude": -81.6944, "altitude": 199 },
                  "hopsAway": 0,
                  "viaMqtt": false
                }
              ]
            }
        """.trimIndent()

        val vm = NodeMapViewModel(app)
        waitUntil { vm.uiState.value.isInitialCacheRestored }

        // 1. Empty DB auto-loads immediately without pendingImport dialog
        vm.importJsonText("initial.json", initialJson)
        waitUntil { vm.uiState.value.allNodes.size == 2 }
        assertNull(vm.uiState.value.pendingImport)
        assertEquals("initial.json", vm.uiState.value.loadedFileName)

        // 2. Customize Harbor Repeater specs/color WITHOUT moving GPS or changing telemetry
        val harbor = vm.uiState.value.allNodes.first { it.id == "!000003ea" }
        vm.openEditNodeEditor(harbor)
        val draft = vm.uiState.value.editingDraft!!
        val customPurple = NodeColorPalette.PRESETS[5].argb
        val saveErr = vm.saveNodeEditorDraft(
            draft.copy(
                longName = "Harbor High Mast",
                roleText = "ROUTER",
                notesText = "Waterfront pole 15m",
                customColorArgb = customPurple
            )
        )
        assertNull(saveErr)

        // 3. Importing updateJson when DB already has nodes surfaces pendingImport with counts
        vm.importJsonText("update.json", updateJson)
        waitUntil { vm.uiState.value.pendingImport != null }
        val pending = vm.uiState.value.pendingImport!!
        assertEquals("update.json", pending.fileName)
        assertEquals(1, pending.matchedExistingCount)
        assertEquals(1, pending.newNodesCount)

        // 4. Confirm Merge -> Harbor keeps custom specs/color while coords/SNR/RSSI refresh, plus 1001 kept & 1003 added
        vm.confirmPendingImportMerge()
        waitUntil { vm.uiState.value.pendingImport == null && vm.uiState.value.allNodes.size == 3 }
        val mergedHarbor = vm.uiState.value.allNodes.first { it.id == "!000003ea" }
        assertEquals("Harbor High Mast", mergedHarbor.longName)
        assertEquals("ROUTER", mergedHarbor.role)
        assertEquals("Waterfront pole 15m", mergedHarbor.notes)
        assertEquals(customPurple, mergedHarbor.customColorArgb)
        assertEquals(42.1600, mergedHarbor.latitude!!, 1e-4)
        assertEquals(-80.0700, mergedHarbor.longitude!!, 1e-4)
        assertEquals(222, mergedHarbor.altitude)
        assertEquals(10.25, mergedHarbor.snr!!, 1e-3)
        assertEquals(-69, mergedHarbor.rssi)
        assertEquals(0, mergedHarbor.hopsAway)
        assertNotNull(vm.uiState.value.allNodes.firstOrNull { it.id == "!000003e9" })
        assertNotNull(vm.uiState.value.allNodes.firstOrNull { it.id == "!000003eb" })

        // 5. Importing replaceJson -> Confirm Replace swaps base nodes cleanly
        vm.importJsonText("cleveland.json", replaceJson)
        waitUntil { vm.uiState.value.pendingImport != null }
        vm.confirmPendingImportReplace()
        waitUntil {
            vm.uiState.value.pendingImport == null &&
                vm.uiState.value.loadedFileName == "cleveland.json"
        }
        assertEquals(1, vm.uiState.value.allNodes.size)
        assertEquals("!000007d1", vm.uiState.value.allNodes.single().id)
        assertFalse(vm.uiState.value.allNodes.any { it.id == "!000003ea" })
    }

    @Test
    fun `NodeDbParser parses GeoJSON FeatureCollection mesh_node export`() {
        val customCyan = NodeColorPalette.PRESETS[1].argb
        val node = MeshNode(
            num = 0x4455L,
            id = "!00004455",
            longName = "Geo Relay",
            shortName = "GEOR",
            hwModel = "RAK4631",
            role = "ROUTER",
            latitude = 42.1500,
            longitude = -80.0500,
            altitude = 275,
            hopsAway = 0,
            viaMqtt = false,
            isFavorite = true,
            isSelf = false,
            snr = 7.5,
            rssi = -81,
            lastHeard = null,
            deviceMetrics = null,
            environmentMetrics = null,
            rawJson = "{}",
            customColorArgb = customCyan,
            isManual = true,
            notes = "Water tower east"
        )
        val geoJson = MapExportEngine.buildNodeMapGeoJson(
            NodeMapUiState(allNodes = listOf(node))
        )

        val parsed = NodeDbParser.parse(geoJson).getOrThrow()
        assertEquals(1, parsed.totalCount)
        val restored = parsed.nodes.single()
        assertEquals("!00004455", restored.id)
        assertEquals("Geo Relay", restored.longName)
        assertEquals("GEOR", restored.shortName)
        assertEquals(42.1500, restored.latitude!!, 1e-5)
        assertEquals(-80.0500, restored.longitude!!, 1e-5)
        assertEquals(275, restored.altitude)
        assertEquals(customCyan, restored.customColorArgb)
        assertTrue(restored.isManual)
        assertEquals("Water tower east", restored.notes)
    }

    @Test
    fun `MainScreen renders Merge vs Replace import dialog and invokes callbacks`() {
        val sampleNode = MeshNode(
            num = 1L,
            id = "!00000001",
            longName = "Alpha",
            shortName = "ALPH",
            hwModel = null,
            role = null,
            latitude = 42.1,
            longitude = -80.0,
            altitude = 200,
            hopsAway = 0,
            viaMqtt = false,
            isFavorite = false,
            isSelf = true,
            snr = null,
            rssi = null,
            lastHeard = null,
            deviceMetrics = null,
            environmentMetrics = null,
            rawJson = "{}"
        )
        val parsed = NodeDbParser.parse(
            """{"nodes":[{"num":1,"user":{"id":"!00000001","longName":"Alpha","shortName":"ALPH"},"position":{"latitude":42.1,"longitude":-80.0}}]}"""
        ).getOrThrow()

        var mergeClicks = 0
        var replaceClicks = 0
        var cancelClicks = 0

        composeRule.setContent {
            LEMeshTheme {
                MainScreen(
                    uiState = NodeMapUiState(
                        hasLoadedFile = true,
                        loadedFileName = "current.json",
                        allNodes = listOf(sampleNode),
                        selfNode = sampleNode,
                        totalCount = 1,
                        plottedGpsCount = 1,
                        pendingImport = PendingNodeDbImport(
                            fileName = "shared_nodedb.json",
                            jsonText = "{}",
                            incomingResult = parsed,
                            matchedExistingCount = 1,
                            newNodesCount = 2,
                            incomingRegularCount = 3,
                            incomingManualCount = 0
                        )
                    ),
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
                    onConfirmPendingImportMerge = { mergeClicks++ },
                    onConfirmPendingImportReplace = { replaceClicks++ },
                    onDismissPendingImport = { cancelClicks++ }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("import_merge_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(1, mergeClicks)

        composeRule.onNodeWithTag("import_replace_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(1, replaceClicks)

        composeRule.onNodeWithTag("import_cancel_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(1, cancelClicks)
    }
}
