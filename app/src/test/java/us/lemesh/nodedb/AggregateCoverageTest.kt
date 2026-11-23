package us.lemesh.nodedb

import android.app.Application
import android.content.Context
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import us.lemesh.nodedb.data.AggregateCoverageEngine
import us.lemesh.nodedb.data.AggregateCoverageParams
import us.lemesh.nodedb.data.AggregateCoverageResult
import us.lemesh.nodedb.data.MapExportEngine
import us.lemesh.nodedb.data.MeshNode
import us.lemesh.nodedb.data.PolarVisibilityField
import us.lemesh.nodedb.data.TerrariumDemRepository
import us.lemesh.nodedb.data.TerrariumTileGrid
import us.lemesh.nodedb.data.ViewshedEngine
import us.lemesh.nodedb.ui.AggregateCoverageSheetConfig
import us.lemesh.nodedb.ui.NodeMapFiltersSheet
import us.lemesh.nodedb.ui.NodeMapUiState
import us.lemesh.nodedb.ui.NodeMapViewModel
import us.lemesh.nodedb.ui.SavedViewshedLayersSheet
import us.lemesh.nodedb.ui.theme.LEMeshTheme
import kotlinx.coroutines.Dispatchers
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
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AggregateCoverageTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun makeNode(
        id: String,
        lat: Double? = 42.10,
        lon: Double? = -80.10,
        viaMqtt: Boolean = false,
        isSelf: Boolean = false,
        isFavorite: Boolean = false,
        role: String? = "CLIENT",
        hopsAway: Int? = 0,
        snr: Double? = 5.0
    ): MeshNode = MeshNode(
        num = id.hashCode().toLong(),
        id = id,
        longName = "Node $id",
        shortName = id.takeLast(4),
        hwModel = "TBEAM",
        role = role,
        latitude = lat,
        longitude = lon,
        altitude = 210,
        hopsAway = hopsAway,
        viaMqtt = viaMqtt,
        isFavorite = isFavorite,
        isSelf = isSelf,
        snr = snr,
        rssi = -85,
        lastHeard = null,
        deviceMetrics = null,
        environmentMetrics = null,
        rawJson = "{}"
    )

    private fun buildSyntheticGrid(
        northLat: Double,
        southLat: Double,
        westLon: Double,
        eastLon: Double,
        zoom: Int,
        ridgeMinLat: Double? = null
    ): TerrariumTileGrid {
        val (minTx, minTy) = TerrariumDemRepository.latLonToTile(northLat, westLon, zoom)
        val (maxTx, maxTy) = TerrariumDemRepository.latLonToTile(southLat, eastLon, zoom)
        val flatRgb = Color.rgb(128, 200, 0)  // 200m
        val ridgeRgb = Color.rgb(129, 164, 0) // 420m

        fun latToGlobalPy(lat: Double): Double {
            val latRad = Math.toRadians(lat)
            val n = (1 shl zoom).toDouble()
            return (1.0 - kotlin.math.ln(kotlin.math.tan(latRad) + 1.0 / kotlin.math.cos(latRad)) / Math.PI) / 2.0 * n * 256.0
        }

        val ridgeSouthPy = ridgeMinLat?.let { latToGlobalPy(it) }
        val ridgeNorthPy = ridgeMinLat?.let { latToGlobalPy(it + 0.004) }

        val tiles = mutableMapOf<Long, IntArray>()
        for (ty in minTy..maxTy) {
            for (tx in minTx..maxTx) {
                val arr = IntArray(256 * 256)
                for (py in 0 until 256) {
                    val globalPy = ty * 256 + py
                    val isRidge = ridgeNorthPy != null && ridgeSouthPy != null &&
                        globalPy >= ridgeNorthPy && globalPy <= ridgeSouthPy
                    val c = if (isRidge) ridgeRgb else flatRgb
                    val rowStart = py * 256
                    for (px in 0 until 256) arr[rowStart + px] = c
                }
                tiles[TerrariumTileGrid.tileKey(tx, ty)] = arr
            }
        }
        return TerrariumTileGrid(zoom = zoom, tiles = tiles, tileCount = tiles.size)
    }

    @Test
    fun `filterEligibleRfNodes excludes mqtt and no-gps and prioritizes critical nodes`() {
        val nodes = mutableListOf<MeshNode>()
        nodes += makeNode("!mqtt1", viaMqtt = true)
        nodes += makeNode("!nogps", lat = null, lon = null)
        for (i in 1..40) {
            nodes += makeNode("!client$i", hopsAway = 2, snr = i.toDouble())
        }
        nodes += makeNode("!router1", role = "ROUTER", hopsAway = 1, snr = 1.0)
        nodes += makeNode("!fav1", isFavorite = true, hopsAway = 3, snr = -5.0)
        nodes += makeNode("!self", isSelf = true, hopsAway = null, snr = null)

        val result = AggregateCoverageEngine.filterEligibleRfNodes(nodes, maxNodes = 36)
        assertEquals(43, result.totalEligibleCount)
        assertEquals(36, result.selectedNodes.size)
        assertEquals(7, result.skippedCapCount)
        assertFalse(result.selectedNodes.any { it.viaMqtt })
        assertTrue(result.selectedNodes.all { it.hasValidGps })
        assertEquals("!self", result.selectedNodes[0].id)
        assertEquals("!fav1", result.selectedNodes[1].id)
        assertEquals("!router1", result.selectedNodes[2].id)
    }

    @Test
    fun `overlapping dual nodes sum RF power while terrain ridge blocks shadowed regions`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ridgeLat = 42.1350
        val singleNode = listOf(makeNode("!a", lat = 42.10, lon = -80.10))
        val twoNearbyNodes = listOf(
            makeNode("!a", lat = 42.10, lon = -80.10),
            makeNode("!b", lat = 42.1005, lon = -80.1005)
        )

        val singleRes = AggregateCoverageEngine.computeAggregateCoverage(
            context = context,
            nodes = singleNode,
            params = AggregateCoverageParams(radiusKm = 8, antennaHeightMeters = 12.0),
            tileGridProvider = { n, s, w, e, z ->
                Result.success(buildSyntheticGrid(n, s, w, e, z, ridgeMinLat = ridgeLat))
            }
        ).getOrThrow()

        val dualRes = AggregateCoverageEngine.computeAggregateCoverage(
            context = context,
            nodes = twoNearbyNodes,
            params = AggregateCoverageParams(radiusKm = 8, antennaHeightMeters = 12.0),
            tileGridProvider = { n, s, w, e, z ->
                Result.success(buildSyntheticGrid(n, s, w, e, z, ridgeMinLat = ridgeLat))
            }
        ).getOrThrow()

        assertEquals(1, singleRes.contributingNodeCount)
        assertEquals(2, dualRes.contributingNodeCount)
        assertTrue("Dual overlapping nodes should yield higher peak RSSI (+~3dB): ${dualRes.peakRssiDbm} vs ${singleRes.peakRssiDbm}", dualRes.peakRssiDbm > singleRes.peakRssiDbm + 2.0)

        // Sub-region north of ridge (42.145..42.160) should be blocked from 42.10 origin
        val shadowBitmap = PolarVisibilityField.rasterizeAggregateSubRegion(
            fields = dualRes.fields,
            sensitivityDbm = dualRes.sensitivityDbm,
            renderNorthLat = 42.160,
            renderSouthLat = 42.148,
            renderWestLon = -80.11,
            renderEastLon = -80.09,
            width = 32,
            height = 32
        )
        var nonTransparentShadowPixels = 0
        for (y in 0 until 32) {
            for (x in 0 until 32) {
                if (Color.alpha(shadowBitmap.getPixel(x, y)) > 0) nonTransparentShadowPixels++
            }
        }
        assertEquals(0, nonTransparentShadowPixels)

        // Adding a 3rd node north of the ridge illuminates that previously blocked pocket
        val threeWithRelay = twoNearbyNodes + makeNode("!north", lat = 42.152, lon = -80.10)
        val relayRes = AggregateCoverageEngine.computeAggregateCoverage(
            context = context,
            nodes = threeWithRelay,
            params = AggregateCoverageParams(radiusKm = 8, antennaHeightMeters = 12.0),
            tileGridProvider = { n, s, w, e, z ->
                Result.success(buildSyntheticGrid(n, s, w, e, z, ridgeMinLat = ridgeLat))
            }
        ).getOrThrow()
        assertTrue("Node north of ridge should expand aggregate covered area", relayRes.estimatedCoveredAreaKm2 > dualRes.estimatedCoveredAreaKm2 + 20.0)
    }

    @Test
    fun `distant nodes are partitioned into separate spatial DEM clusters`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val distantNodes = listOf(
            makeNode("!erie", lat = 42.1292, lon = -80.0851),
            makeNode("!pittsburgh", lat = 40.4406, lon = -79.9959)
        )
        var gridRequests = 0
        val result = AggregateCoverageEngine.computeAggregateCoverage(
            context = context,
            nodes = distantNodes,
            params = AggregateCoverageParams(radiusKm = 5),
            tileGridProvider = { n, s, w, e, z ->
                gridRequests++
                assertEquals(12, z) // Stays high-res z12 per cluster instead of collapsing across 190 km
                Result.success(buildSyntheticGrid(n, s, w, e, z))
            }
        ).getOrThrow()

        assertEquals(2, gridRequests)
        assertEquals(2, result.contributingNodeCount)
        assertTrue(result.estimatedCoveredAreaKm2 > 100.0)
    }

    @Test
    fun `viewmodel toggle, parameter changes, export summary, and clear reset state cleanly`() = runBlocking(Dispatchers.Main.immediate) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = NodeMapViewModel(app)
        vm.aggregateTileGridProvider = { n, s, w, e, z ->
            Result.success(buildSyntheticGrid(n, s, w, e, z))
        }

        // Load two RF nodes + one MQTT node via reflection or public method? Use NodeDbParser JSON via temp file URI or loadFromUri?
        // Actually we can inspect NodeMapUiState via MapExportEngine and trigger toggle when JSON is loaded via a temp cache file or Intent!
        val tempJson = app.cacheDir.resolve("test_nodedb.json").apply {
            writeText(
                """
                {
                  "myNodeNum": 1,
                  "nodes": [
                    {
                      "num": 1,
                      "user": { "id": "!self0001", "longName": "Self Tower", "shortName": "SELF", "role": "ROUTER" },
                      "position": { "latitudeI": 421292000, "longitudeI": -800851000, "altitude": 220 },
                      "hopsAway": 0,
                      "viaMqtt": false,
                      "isFavorite": true
                    },
                    {
                      "num": 2,
                      "user": { "id": "!rf000002", "longName": "Relay Two", "shortName": "RLY2", "role": "CLIENT" },
                      "position": { "latitudeI": 421400000, "longitudeI": -800700000, "altitude": 210 },
                      "hopsAway": 0,
                      "viaMqtt": false,
                      "snr": 7.5
                    },
                    {
                      "num": 3,
                      "user": { "id": "!mqtt0003", "longName": "MQTT Only", "shortName": "MQTT", "role": "CLIENT" },
                      "position": { "latitudeI": 421500000, "longitudeI": -800600000, "altitude": 200 },
                      "viaMqtt": true
                    }
                  ]
                }
                """.trimIndent()
            )
        }
        vm.loadFromUri(android.net.Uri.fromFile(tempJson))

        // Wait until loaded & computed
        var attempts = 0
        while (!vm.uiState.value.hasLoadedFile && attempts < 40) {
            kotlinx.coroutines.delay(25)
            attempts++
        }
        assertTrue(vm.uiState.value.hasLoadedFile)
        assertEquals(2, vm.uiState.value.eligibleAggregateRfNodeCount)

        vm.toggleAggregateCoverage()
        attempts = 0
        while ((vm.uiState.value.isAggregateCoverageLoading || vm.uiState.value.aggregateCoverageResult == null) && attempts < 80) {
            kotlinx.coroutines.delay(25)
            attempts++
        }
        val activeState = vm.uiState.value
        assertTrue(activeState.isAggregateCoverageEnabled)
        assertNotNull(activeState.aggregateCoverageResult)
        assertEquals(2, activeState.aggregateCoverageResult!!.contributingNodeCount)
        assertFalse(activeState.isAggregateCoverageStale)

        // Export summary includes Aggregate RF metrics & RSSI legend
        val summary = MapExportEngine.buildNodeMapSummary(activeState, emptyList())
        assertTrue(summary.showRssiGradient)
        assertTrue(summary.metricLines.any { it.contains("Aggregate RF: 2 nodes") })

        // Favorites filter reduces eligible nodes to 1 (!self0001) and recalculates
        vm.toggleFavoritesOnly()
        attempts = 0
        while ((vm.uiState.value.isAggregateCoverageLoading || vm.uiState.value.aggregateCoverageResult?.contributingNodeCount != 1) && attempts < 80) {
            kotlinx.coroutines.delay(25)
            attempts++
        }
        assertEquals(1, vm.uiState.value.aggregateCoverageResult?.contributingNodeCount)

        // Clear JSON resets aggregate state
        vm.clearLoadedJson()
        assertFalse(vm.uiState.value.isAggregateCoverageEnabled)
        assertNull(vm.uiState.value.aggregateCoverageResult)
    }

    @Test
    fun `layers sheet shows aggregate controls and opacity slider even without saved layers`() {
        var toggled = false
        var selectedRadius = 10
        var selectedMast = 10.0

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
                    aggregateConfig = AggregateCoverageSheetConfig(
                        isEnabled = true,
                        radiusKm = selectedRadius,
                        mastHeightMeters = selectedMast,
                        eligibleRfNodeCount = 6,
                        isLoading = false,
                        progressDone = 6,
                        progressTotal = 6,
                        isStale = false,
                        errorMessage = null,
                        result = AggregateCoverageResult(
                            fields = emptyList(),
                            contributingNodeCount = 6,
                            totalEligibleCount = 6,
                            skippedCapCount = 0,
                            northLat = 42.2,
                            southLat = 42.0,
                            westLon = -80.2,
                            eastLon = -80.0,
                            estimatedCoveredAreaKm2 = 142.5,
                            peakRssiDbm = -58.0,
                            sensitivityDbm = -130.0,
                            signature = "sig",
                            computeTimeMs = 120L
                        ),
                        onToggleEnabled = { toggled = true },
                        onRadiusChange = { selectedRadius = it },
                        onMastHeightChange = { selectedMast = it },
                        onRefresh = {},
                        onCancelCompute = {},
                        onDismissError = {}
                    )
                )
            }
        }

        composeRule.onNodeWithTag("aggregate_coverage_card").assertIsDisplayed()
        composeRule.onNodeWithTag("node_map_layers_opacity_slider").assertIsDisplayed()
        composeRule.onNodeWithTag("aggregate_coverage_switch").performClick()
        assertTrue(toggled)
        composeRule.onNodeWithTag("aggregate_radius_chip_15").performClick()
        assertEquals(15, selectedRadius)
        composeRule.onNodeWithTag("aggregate_mast_chip_20").performClick()
        assertEquals(20.0, selectedMast, 1e-4)
    }

    @Test
    fun `filters sheet displays aggregate coverage toggle when callback provided`() {
        var toggled = false
        composeRule.setContent {
            LEMeshTheme {
                NodeMapFiltersSheet(
                    uiState = NodeMapUiState(hasLoadedFile = true, loadedFileName = "sample.json"),
                    onToggleShowMqtt = {},
                    onToggleRfSightlines = {},
                    onToggleFavoritesOnly = {},
                    onToggleLosMode = {},
                    onToggleMeasureMode = {},
                    onOpenDirectLinksSheet = {},
                    onOpenNoGpsSheet = {},
                    onLoadJsonClick = {},
                    onClearJsonClick = {},
                    onResetFilters = {},
                    onToggleAggregateCoverage = { toggled = true },
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithTag("chip_aggregate_coverage").performScrollTo().performClick()
        assertTrue(toggled)
    }
}
