package us.lemesh.nodedb

import android.app.Application
import android.content.Context
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import us.lemesh.nodedb.data.LosAnalyzer
import us.lemesh.nodedb.data.LosElevationSource
import us.lemesh.nodedb.data.LosEndpoint
import us.lemesh.nodedb.data.LosStatus
import us.lemesh.nodedb.data.NodeDbParser
import us.lemesh.nodedb.data.RelayBridgeEngine
import us.lemesh.nodedb.data.RfViabilityTier
import us.lemesh.nodedb.data.TerrariumDemRepository
import us.lemesh.nodedb.data.TerrariumTileGrid
import us.lemesh.nodedb.ui.LosProfileDrawer
import us.lemesh.nodedb.ui.NodeMapUiState
import us.lemesh.nodedb.ui.NodeMapViewModel
import us.lemesh.nodedb.ui.theme.LEMeshTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FresnelSightlineTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `fresnel analyzer computes 100pct f1 diffraction loss rssi margin and exact mast fix`() {
        val pointA = LosEndpoint(42.1000, -80.1000, "Base TX", "TX")
        val pointB = LosEndpoint(42.1900, -80.1000, "Hill RX", "RX")
        val coords = LosAnalyzer.sampleGreatCirclePath(pointA, pointB, 100)

        // Flat 200m terrain with 30m masts -> Clear LoS (~10 km @ 906.875 MHz)
        val clearElevs = List(100) { 200.0 }
        val clear = LosAnalyzer.analyze(
            pointA = pointA,
            pointB = pointB,
            coordinates = coords,
            elevationsMeters = clearElevs,
            txHeightMeters = 30.0,
            rxHeightMeters = 30.0,
            frequencyMhz = 906.875
        )
        assertEquals(LosStatus.CLEAR_LOS, clear.status)
        assertEquals(RfViabilityTier.VIABLE_CLEAR, clear.viabilityTier)
        assertEquals(RelayBridgeEngine.DEFAULT_TX_POWER_DBM, clear.txPowerDbm, 1e-4)
        assertEquals(RelayBridgeEngine.DEFAULT_ANTENNA_GAIN_DBI, clear.txGainDbi, 1e-4)
        assertEquals(RelayBridgeEngine.DEFAULT_ANTENNA_GAIN_DBI, clear.rxGainDbi, 1e-4)
        assertEquals(RelayBridgeEngine.DEFAULT_SENSITIVITY_DBM, clear.sensitivityDbm, 1e-4)
        assertEquals(0.0, clear.diffractionLossDb, 1e-4)
        assertEquals(28.76, clear.maxFresnel1RadiusMeters, 0.5)
        // EIRP+RX = 22 + 3 + 3 = 28 dBm, FSPL ~111.59 dB -> RSSI ~ -83.59 dBm, Margin ~ +46.4 dB
        assertTrue(clear.predictedRssiDbm in -85.0..-82.0)
        assertTrue(clear.linkMarginDb > 44.0)
        assertEquals(0.0, clear.requiredEqualMastDeltaMeters, 1e-4)

        // 100% F1 bounds match +/- fresnelRadiusMeters around ray
        val mid = clear.samples[50]
        assertEquals(mid.losRayMeters + mid.fresnelRadiusMeters, mid.fresnel100UpperMeters, 1e-4)
        assertEquals(mid.losRayMeters - mid.fresnelRadiusMeters, mid.fresnel100LowerMeters, 1e-4)

        // Peak at midpoint = 235m (5m above 230m ray, ~6.47m including earth bulge) -> Obstructed + knife-edge loss
        val blockedElevs = MutableList(100) { 200.0 }.apply { this[50] = 235.0 }
        val blocked = LosAnalyzer.analyze(
            pointA = pointA,
            pointB = pointB,
            coordinates = coords,
            elevationsMeters = blockedElevs,
            txHeightMeters = 30.0,
            rxHeightMeters = 30.0,
            frequencyMhz = 906.875,
            elevationSource = LosElevationSource.TERRARIUM_DEM
        )
        assertEquals(LosStatus.OBSTRUCTED, blocked.status)
        assertEquals(RfViabilityTier.OBSTRUCTED, blocked.viabilityTier)
        assertEquals(LosElevationSource.TERRARIUM_DEM, blocked.elevationSource)
        assertTrue("Expected knife-edge diffraction loss > 6 dB, got ${blocked.diffractionLossDb}", blocked.diffractionLossDb > 6.0)
        assertEquals(blocked.fsplDb + blocked.diffractionLossDb, blocked.totalPathLossDb, 1e-4)
        assertTrue(blocked.predictedRssiDbm < clear.predictedRssiDbm - 6.0)
        assertTrue(blocked.requiredEqualMastDeltaMeters > 20.0)
        assertTrue(blocked.requiredTxMastDeltaMeters > blocked.requiredEqualMastDeltaMeters)

        // Applying equal mast delta clears 60% F1 completely
        val fixed = LosAnalyzer.analyze(
            pointA = pointA,
            pointB = pointB,
            coordinates = coords,
            elevationsMeters = blockedElevs,
            txHeightMeters = 30.0 + blocked.requiredEqualMastDeltaMeters,
            rxHeightMeters = 30.0 + blocked.requiredEqualMastDeltaMeters,
            frequencyMhz = 906.875
        )
        assertEquals(LosStatus.CLEAR_LOS, fixed.status)
        assertEquals(RfViabilityTier.VIABLE_CLEAR, fixed.viabilityTier)
        assertTrue((fixed.worstClearancePercentFresnel ?: 0.0) >= 60.0)

        // Weak link margin case: clear LoS but low TX power + low sensitivity floor
        val weakMargin = LosAnalyzer.analyze(
            pointA = pointA,
            pointB = pointB,
            coordinates = coords,
            elevationsMeters = clearElevs,
            txHeightMeters = 30.0,
            rxHeightMeters = 30.0,
            frequencyMhz = 906.875,
            txPowerDbm = 0.0,
            txGainDbi = 0.0,
            rxGainDbi = 0.0,
            sensitivityDbm = -100.0
        )
        assertEquals(LosStatus.CLEAR_LOS, weakMargin.status)
        assertEquals(RfViabilityTier.WEAK_MARGIN, weakMargin.viabilityTier)
        assertTrue(weakMargin.linkMarginDb < 0.0)
    }

    @Test
    fun `viewmodel auto solves visible rf sightlines via terrarium first and falls back to open meteo`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>() as Application
        val sampleJson = """
            {
              "myInfo": { "myNodeNum": 100 },
              "nodes": [
                {
                  "num": 100,
                  "user": { "id": "!00000064", "longName": "Self Base", "shortName": "SELF" },
                  "position": { "latitude": 42.1000, "longitude": -80.1000, "altitude": 200 },
                  "hopsAway": 0,
                  "viaMqtt": false
                },
                {
                  "num": 101,
                  "user": { "id": "!00000065", "longName": "North Direct", "shortName": "NRTH" },
                  "position": { "latitude": 42.1450, "longitude": -80.1000, "altitude": 200 },
                  "hopsAway": 0,
                  "viaMqtt": false
                },
                {
                  "num": 102,
                  "user": { "id": "!00000066", "longName": "MQTT Peer", "shortName": "MQTT" },
                  "position": { "latitude": 42.1300, "longitude": -80.0800, "altitude": 200 },
                  "hopsAway": 2,
                  "viaMqtt": true
                }
              ]
            }
        """.trimIndent()
        val parsed = NodeDbParser.parse(sampleJson).getOrThrow()

        // 1. Terrarium DEM primary path succeeds -> Open-Meteo must NOT be called
        val vmTerrarium = NodeMapViewModel(context)
        var openMeteoCalls = 0
        var terrariumCalls = 0
        vmTerrarium.losOpenMeteoProvider = { coords ->
            openMeteoCalls++
            Result.success(List(coords.size) { 200.0 })
        }
        vmTerrarium.sightlineTileGridProvider = { nLat, sLat, wLon, eLon, zoom ->
            terrariumCalls++
            Result.success(buildFlatGrid(nLat, sLat, wLon, eLon, zoom, elevationMeters = 200))
        }

        val stateField = NodeMapViewModel::class.java.getDeclaredField("_uiState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(vmTerrarium) as kotlinx.coroutines.flow.MutableStateFlow<NodeMapUiState>
        stateFlow.value = stateFlow.value.copy(
            hasLoadedFile = true,
            allNodes = parsed.nodes,
            selfNode = parsed.selfNode,
            showRfSightlines = true
        )

        vmTerrarium.refreshSightlineFresnelCalculations()
        delay(250L)

        val directNode = parsed.nodes.first { it.id == "!00000065" }
        val mqttNode = parsed.nodes.first { it.id == "!00000066" }
        val solvedMap = vmTerrarium.uiState.value.sightlineFresnelResults
        val directKey = LosAnalyzer.nodeSightlineKey(directNode)
        assertTrue("Expected direct node key $directKey in sightlineFresnelResults", solvedMap.containsKey(directKey))
        assertFalse("MQTT nodes must not be solved as RF sightlines", solvedMap.containsKey(LosAnalyzer.nodeSightlineKey(mqttNode)))
        val directRes = solvedMap.getValue(directKey)
        assertEquals(LosElevationSource.TERRARIUM_DEM, directRes.elevationSource)
        assertTrue("Terrarium DEM provider should have been called", terrariumCalls >= 1)
        assertEquals("Open-Meteo should not be called when Terrarium DEM succeeds", 0, openMeteoCalls)

        // Inspecting solved sightline opens Fresnel calculator immediately without network/tile fetch
        val callsBeforeInspect = terrariumCalls
        vmTerrarium.inspectSightlineFresnel(directNode)
        assertTrue(vmTerrarium.uiState.value.isLosMode)
        assertNotNull(vmTerrarium.uiState.value.losResult)
        assertEquals(LosElevationSource.TERRARIUM_DEM, vmTerrarium.uiState.value.losResult?.elevationSource)
        assertEquals(callsBeforeInspect, terrariumCalls)

        // Raising TX/RX mast heights fixes the grazing 2.0m mast over 5 km (~10m F1 -> 60% = 6.1m)
        assertEquals(LosStatus.MARGINAL_GRAZING, vmTerrarium.uiState.value.losResult?.status)
        vmTerrarium.applyLosMastFix()
        assertEquals(LosStatus.CLEAR_LOS, vmTerrarium.uiState.value.losResult?.status)
        assertEquals(RfViabilityTier.VIABLE_CLEAR, vmTerrarium.uiState.value.losResult?.viabilityTier)

        // 2. Terrarium failure triggers Open-Meteo fallback cleanly
        val vmFallback = NodeMapViewModel(context)
        var fallbackOpenMeteoCalls = 0
        vmFallback.sightlineTileGridProvider = { _, _, _, _, _ ->
            Result.failure(IllegalStateException("Tile offline"))
        }
        vmFallback.losOpenMeteoProvider = { coords ->
            fallbackOpenMeteoCalls++
            Result.success(List(coords.size) { 215.0 })
        }
        @Suppress("UNCHECKED_CAST")
        val fallbackFlow = stateField.get(vmFallback) as kotlinx.coroutines.flow.MutableStateFlow<NodeMapUiState>
        fallbackFlow.value = fallbackFlow.value.copy(
            hasLoadedFile = true,
            allNodes = parsed.nodes,
            selfNode = parsed.selfNode,
            showRfSightlines = true
        )

        vmFallback.refreshSightlineFresnelCalculations()
        delay(250L)

        val fallbackRes = vmFallback.uiState.value.sightlineFresnelResults[directKey]
        assertNotNull(fallbackRes)
        assertEquals(LosElevationSource.OPEN_METEO, fallbackRes?.elevationSource)
        assertTrue(fallbackOpenMeteoCalls >= 1)
    }

    @Test
    fun `los profile drawer displays fresnel viability metrics expandable rf budget and mast fix action`() {
        val pointA = LosEndpoint(42.1000, -80.1000, "Self Base", "SELF")
        val pointB = LosEndpoint(42.1900, -80.1000, "Hill Relay", "HILL")
        val coords = LosAnalyzer.sampleGreatCirclePath(pointA, pointB, 64)
        val blockedElevs = MutableList(64) { 200.0 }.apply { this[32] = 225.0 }
        val result = LosAnalyzer.analyze(
            pointA = pointA,
            pointB = pointB,
            coordinates = coords,
            elevationsMeters = blockedElevs,
            txHeightMeters = 2.0,
            rxHeightMeters = 2.0,
            frequencyMhz = 906.875,
            elevationSource = LosElevationSource.TERRARIUM_DEM
        )

        var mastFixApplied = false
        composeRule.setContent {
            LEMeshTheme {
                LosProfileDrawer(
                    pointA = pointA,
                    pointB = pointB,
                    selfNode = null,
                    txHeightText = "2.0",
                    rxHeightText = "2.0",
                    frequencyText = "906.875",
                    result = result,
                    isLoading = false,
                    errorMessage = null,
                    isExpanded = true,
                    onToggleExpanded = {},
                    onTxHeightChange = {},
                    onRxHeightChange = {},
                    onFrequencyChange = {},
                    onUseSelfAsPointA = {},
                    onSwapEndpoints = {},
                    onClearEndpoints = {},
                    onRetryElevation = {},
                    onUseFlatFallback = {},
                    onDone = {},
                    onApplyMastFix = { mastFixApplied = true }
                )
            }
        }

        composeRule.onNodeWithTag("los_elevation_source_pill").assertIsDisplayed()
        composeRule.onNodeWithText("Terrarium DEM").assertIsDisplayed()
        composeRule.onNodeWithText("Predicted RSSI").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Peak 100% F1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("FSPL + Diff Loss").performScrollTo().assertIsDisplayed()

        // Expand RF Radio Budget accordion -> reveals 22 dBm / 3.0 dBi / -130 dBm fields
        composeRule.onNodeWithTag("los_rf_budget_toggle").performScrollTo().performClick()
        composeRule.onNodeWithTag("los_tx_power_input").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("los_tx_gain_input").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("los_rx_gain_input").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("los_sensitivity_input").performScrollTo().assertIsDisplayed()

        // Mast Fix banner & Apply Fix action button
        composeRule.onNodeWithTag("los_mast_fix_banner").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("los_apply_mast_fix_button").performScrollTo().performClick()
        assertTrue(mastFixApplied)
    }

    private fun buildFlatGrid(
        northLat: Double,
        southLat: Double,
        westLon: Double,
        eastLon: Double,
        zoom: Int,
        elevationMeters: Int
    ): TerrariumTileGrid {
        val (minTx, minTy) = TerrariumDemRepository.latLonToTile(northLat, westLon, zoom)
        val (maxTx, maxTy) = TerrariumDemRepository.latLonToTile(southLat, eastLon, zoom)
        val raw = elevationMeters + 32768
        val r = (raw shr 8) and 0xFF
        val g = raw and 0xFF
        val rgb = Color.rgb(r, g, 0)
        val tiles = mutableMapOf<Long, IntArray>()
        for (ty in minTy..maxTy) {
            for (tx in minTx..maxTx) {
                tiles[TerrariumTileGrid.tileKey(tx, ty)] = IntArray(256 * 256) { rgb }
            }
        }
        return TerrariumTileGrid(zoom = zoom, tiles = tiles, tileCount = tiles.size)
    }
}
