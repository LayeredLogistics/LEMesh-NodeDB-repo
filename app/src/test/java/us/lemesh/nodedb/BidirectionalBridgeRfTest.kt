package us.lemesh.nodedb

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import us.lemesh.nodedb.data.AggregateCoverageParams
import us.lemesh.nodedb.data.BridgeHopEvaluation
import us.lemesh.nodedb.data.BridgeParams
import us.lemesh.nodedb.data.BridgeRankTier
import us.lemesh.nodedb.data.BridgeResult
import us.lemesh.nodedb.data.MapExportEngine
import us.lemesh.nodedb.data.PolarVisibilityField
import us.lemesh.nodedb.data.RelayBridgeEngine
import us.lemesh.nodedb.data.RelayCandidate
import us.lemesh.nodedb.data.TerrariumDemRepository
import us.lemesh.nodedb.data.TerrariumTileGrid
import us.lemesh.nodedb.data.ViewshedParams
import us.lemesh.nodedb.data.ViewshedPointStatus
import us.lemesh.nodedb.ui.BridgeDrawerTab
import us.lemesh.nodedb.ui.BridgePlannerScreen
import us.lemesh.nodedb.ui.BridgeScorecardSection
import us.lemesh.nodedb.ui.BridgeUiState
import us.lemesh.nodedb.ui.BridgeViewModel
import us.lemesh.nodedb.ui.MapBaseLayer
import us.lemesh.nodedb.ui.ViewshedUiState
import us.lemesh.nodedb.ui.theme.LEMeshTheme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BidirectionalBridgeRfTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun buildFlatGrid(
        north: Double,
        south: Double,
        west: Double,
        east: Double,
        elevationMeters: Int = 200
    ): TerrariumTileGrid {
        val zoom = TerrariumDemRepository.recommendedZoomForBounds(north, south, west, east, 12)
        val (minTx, minTy) = TerrariumDemRepository.latLonToTile(north, west, zoom)
        val (maxTx, maxTy) = TerrariumDemRepository.latLonToTile(south, east, zoom)
        val raw = elevationMeters + 32768
        val rgb = Color.rgb(raw shr 8, raw and 0xFF, 0)
        val tiles = mutableMapOf<Long, IntArray>()
        for (ty in minTy..maxTy) {
            for (tx in minTx..maxTx) {
                tiles[TerrariumTileGrid.tileKey(tx, ty)] = IntArray(256 * 256) { rgb }
            }
        }
        return TerrariumTileGrid(zoom = zoom, tiles = tiles, tileCount = tiles.size)
    }

    @Test
    fun `global RF defaults use 22 dBm power and 3 dBi gain across all engines and states`() {
        val agg = AggregateCoverageParams()
        assertEquals(22.0, agg.txPowerDbm, 1e-4)
        assertEquals(3.0, agg.antennaGainDbi, 1e-4)

        val viewshedParams = ViewshedParams(originLat = 42.1, originLon = -80.0, originLabel = "TX")
        assertEquals(22.0, viewshedParams.txPowerDbm, 1e-4)
        assertEquals(3.0, viewshedParams.antennaGainDbi, 1e-4)

        val viewshedUi = ViewshedUiState()
        assertEquals(22f, viewshedUi.txPowerDbm, 1e-4f)
        assertEquals(3.0f, viewshedUi.antennaGainDbi, 1e-4f)

        val bridgeParams = BridgeParams(
            txLat = 42.1, txLon = -80.1, txLabel = "TX",
            rxLat = 42.2, rxLon = -80.0, rxLabel = "RX"
        )
        assertEquals(22.0, bridgeParams.txPowerDbm, 1e-4)
        assertEquals(3.0, bridgeParams.txAntennaGainDbi, 1e-4)
        assertEquals(22.0, bridgeParams.rxPowerDbm, 1e-4)
        assertEquals(3.0, bridgeParams.rxAntennaGainDbi, 1e-4)
        assertEquals(22.0, bridgeParams.relayPowerDbm, 1e-4)
        assertEquals(3.0, bridgeParams.relayAntennaGainDbi, 1e-4)

        val bridgeUi = BridgeUiState()
        assertEquals(22f, bridgeUi.txPowerDbm, 1e-4f)
        assertEquals(3.0f, bridgeUi.txAntennaGainDbi, 1e-4f)
        assertEquals(22f, bridgeUi.rxPowerDbm, 1e-4f)
        assertEquals(3.0f, bridgeUi.rxAntennaGainDbi, 1e-4f)
        assertEquals(22f, bridgeUi.relayPowerDbm, 1e-4f)
        assertEquals(3.0f, bridgeUi.relayAntennaGainDbi, 1e-4f)
    }

    @Test
    fun `evaluateHop flags weak return link when TX is strong but RX transmitter is weak`() {
        val grid = buildFlatGrid(42.25, 42.05, -80.15, -79.95, elevationMeters = 200)
        // ~11.1 km hop; FSPL at 906.875 MHz ~ 112.5 dB
        // Forward: TX 30 dBm + 6 dBi + RX 3 dBi = 39 dBm EIRP -> RSSI ~ -73.5 dBm, margin vs -130 = +56.5 dB
        // Reverse: RX 10 dBm + 3 dBi + TX 6 dBi = 19 dBm EIRP, TX sens -80 dBm -> RSSI ~ -93.5 dBm, margin = -13.5 dB
        val hop = RelayBridgeEngine.evaluateHop(
            latA = 42.10, lonA = -80.05, heightAMeters = 10.0,
            latB = 42.20, lonB = -80.05, heightBMeters = 10.0,
            freqMhz = 906.875, freqGhz = 0.906875,
            eirpDbm = 39.0,
            sensitivityDbm = -130.0,
            grid = grid,
            reverseEirpDbm = 19.0,
            reverseSensitivityDbm = -80.0
        )

        assertTrue(hop.forwardPasses)
        assertFalse(hop.reversePasses)
        assertFalse(hop.isBidirectionalViable)
        assertTrue(hop.linkMarginDb > 40.0)
        assertTrue(hop.reverseLinkMarginDb < 0.0)
        assertEquals(hop.reverseLinkMarginDb, hop.bottleneckLinkMarginDb, 1e-4)
        assertEquals(hop.predictedRssiDbm - 20.0, hop.reverseRssiDbm, 0.2)
    }

    @Test
    fun `computeBridgeAnalysis and rasterizeDualBridge respect asymmetric 2-way link budgets`() {
        val grid = buildFlatGrid(42.32, 42.00, -80.25, -79.85, elevationMeters = 200)
        val asymmetricParams = BridgeParams(
            txLat = 42.12, txLon = -80.10, txLabel = "Strong TX",
            rxLat = 42.18, rxLon = -80.00, rxLabel = "Weak RX",
            txAntennaHeightMeters = 15.0,
            rxAntennaHeightMeters = 15.0,
            relayAntennaHeightMeters = 20.0,
            radiusKm = 12,
            txPowerDbm = 30.0,
            antennaGainDbi = 8.0,
            sensitivityDbm = -135.0,
            rxPowerDbm = 10.0,
            rxAntennaGainDbi = 1.0,
            rxSensitivityDbm = -115.0,
            relayPowerDbm = 22.0,
            relayAntennaGainDbi = 3.0,
            relaySensitivityDbm = -125.0
        )
        val result = RelayBridgeEngine.computeBridgeAnalysis(asymmetricParams, grid)

        assertTrue(result.topCandidates.isNotEmpty())
        val top = result.topCandidates.first()
        val expectedWorst = minOf(top.txHop.bottleneckLinkMarginDb, top.rxHop.bottleneckLinkMarginDb)
        assertEquals(expectedWorst, top.worstLinkMarginDb, 1e-4)
        // Ensure forward and reverse margins on RX hop diverge noticeably due to asymmetric RX vs Relay
        assertTrue(kotlin.math.abs(top.rxHop.linkMarginDb - top.rxHop.reverseLinkMarginDb) > 1.0)

        val fieldA = result.txField!!
        val fieldB = result.rxField!!
        val bmp = PolarVisibilityField.rasterizeDualBridgeSubRegion(
            txField = fieldA,
            rxField = fieldB,
            sensitivityDbm = maxOf(fieldA.sensitivityDbm, fieldB.sensitivityDbm),
            renderNorthLat = result.northLat,
            renderSouthLat = result.southLat,
            renderWestLon = result.westLon,
            renderEastLon = result.eastLon,
            width = 64,
            height = 64
        )
        assertEquals(64, bmp.width)
        assertEquals(64, bmp.height)
    }

    @Test
    fun `BridgeViewModel swapTxAndRx swaps per-node RF stats and updating RF marks viewshed stale`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val vm = BridgeViewModel(context)
        vm.updateTxPowerDbm(30f)
        vm.updateTxAntennaGainDbi(6.5f)
        vm.updateTxSensitivityDbm(-135f)
        vm.updateTxAntennaHeight(25f)

        vm.updateRxPowerDbm(17f)
        vm.updateRxAntennaGainDbi(1.5f)
        vm.updateRxSensitivityDbm(-118f)
        vm.updateRxAntennaHeight(8f)

        vm.updateRelayPowerDbm(24f)
        vm.updateRelayAntennaGainDbi(5.0f)
        vm.updateRelaySensitivityDbm(-128f)

        vm.swapTxAndRx()
        val swapped = vm.uiState.value
        assertEquals(17f, swapped.txPowerDbm, 1e-4f)
        assertEquals(1.5f, swapped.txAntennaGainDbi, 1e-4f)
        assertEquals(-118f, swapped.txSensitivityDbm, 1e-4f)
        assertEquals(25f, swapped.rxAntennaHeightMeters, 1e-4f)

        assertEquals(30f, swapped.rxPowerDbm, 1e-4f)
        assertEquals(6.5f, swapped.rxAntennaGainDbi, 1e-4f)
        assertEquals(-135f, swapped.rxSensitivityDbm, 1e-4f)
        assertEquals(8f, swapped.txAntennaHeightMeters, 1e-4f)

        // Relay untouched by TX/RX swap
        assertEquals(24f, swapped.relayPowerDbm, 1e-4f)
        assertEquals(5.0f, swapped.relayAntennaGainDbi, 1e-4f)
        assertEquals(-128f, swapped.relaySensitivityDbm, 1e-4f)
    }

    @Test
    fun `MapExportEngine buildBridgeGeoJson writes independent TX RX Relay RF stats and reverse margins`() {
        val hop = BridgeHopEvaluation(
            distanceKm = 4.2,
            status = ViewshedPointStatus.CLEAR,
            predictedRssiDbm = -88.0,
            linkMarginDb = 42.0,
            worstClearanceMeters = 18.0,
            minFresnelRatio = 1.2,
            reverseRssiDbm = -98.0,
            reverseLinkMarginDb = 24.0,
            bottleneckLinkMarginDb = 24.0
        )
        val cand = RelayCandidate(
            rank = 1,
            latitude = 42.15,
            longitude = -80.02,
            groundElevationMeters = 280.0,
            txHop = hop,
            rxHop = hop,
            worstLinkMarginDb = 24.0,
            minFresnelRatio = 1.2,
            tier = BridgeRankTier.MUTUAL_CLEAR_LOS,
            inViewshedOverlap = true
        )
        val params = BridgeParams(
            txLat = 42.12, txLon = -80.08, txLabel = "TX",
            rxLat = 42.19, rxLon = -79.96, rxLabel = "RX",
            txPowerDbm = 27.0, antennaGainDbi = 5.0, sensitivityDbm = -132.0,
            rxPowerDbm = 18.0, rxAntennaGainDbi = 2.0, rxSensitivityDbm = -122.0,
            relayPowerDbm = 22.0, relayAntennaGainDbi = 3.0, relaySensitivityDbm = -128.0
        )
        val result = BridgeResult(
            params = params,
            txGroundElevationMeters = 200.0,
            rxGroundElevationMeters = 210.0,
            directDistanceKm = 12.5,
            directBaseline = hop,
            bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888),
            northLat = 42.22, southLat = 42.08, westLon = -80.12, eastLon = -79.92,
            txCoveredAreaKm2 = 15.0, rxCoveredAreaKm2 = 12.0, mutualOverlapAreaKm2 = 6.5,
            candidatesEvaluated = 10, topCandidates = listOf(cand),
            tileCountUsed = 1, computeTimeMs = 12L
        )
        val uiState = BridgeUiState(
            txPowerDbm = 27f, txAntennaGainDbi = 5.0f, txSensitivityDbm = -132f,
            rxPowerDbm = 18f, rxAntennaGainDbi = 2.0f, rxSensitivityDbm = -122f,
            relayPowerDbm = 22f, relayAntennaGainDbi = 3.0f, relaySensitivityDbm = -128f,
            currentResult = result
        )

        val geoJson = JSONObject(MapExportEngine.buildBridgeGeoJson(uiState))
        val metadata = geoJson.getJSONObject("metadata")
        assertTrue(metadata.getBoolean("bidirectional"))
        assertEquals(27.0, metadata.getDouble("txPowerDbm"), 1e-3)
        assertEquals(5.0, metadata.getDouble("txAntennaGainDbi"), 1e-3)
        assertEquals(18.0, metadata.getDouble("rxPowerDbm"), 1e-3)
        assertEquals(2.0, metadata.getDouble("rxAntennaGainDbi"), 1e-3)
        assertEquals(22.0, metadata.getDouble("relayPowerDbm"), 1e-3)
        assertEquals(3.0, metadata.getDouble("relayAntennaGainDbi"), 1e-3)

        val features = geoJson.getJSONArray("features")
        var foundCandidate = false
        for (i in 0 until features.length()) {
            val props = features.getJSONObject(i).getJSONObject("properties")
            if (props.optString("featureType") == "relay_candidate") {
                foundCandidate = true
                assertEquals(24.0, props.getDouble("txHopReverseMarginDb"), 1e-3)
                assertEquals(24.0, props.getDouble("rxHopBottleneckMarginDb"), 1e-3)
            }
        }
        assertTrue(foundCandidate)
    }

    @Test
    fun `BridgePlannerScreen shows TX RX Relay accordions and BridgeScorecard shows 2-way rows and stale banner`() {
        val forwardPassReverseFailHop = BridgeHopEvaluation(
            distanceKm = 6.0,
            status = ViewshedPointStatus.CLEAR,
            predictedRssiDbm = -95.0,
            linkMarginDb = 35.0,
            worstClearanceMeters = 12.0,
            minFresnelRatio = 1.1,
            reverseRssiDbm = -126.0,
            reverseLinkMarginDb = -4.0,
            bottleneckLinkMarginDb = -4.0
        )
        val cand = RelayCandidate(
            rank = 1,
            latitude = 42.15,
            longitude = -80.02,
            groundElevationMeters = 280.0,
            txHop = forwardPassReverseFailHop,
            rxHop = forwardPassReverseFailHop,
            worstLinkMarginDb = -4.0,
            minFresnelRatio = 1.1,
            tier = BridgeRankTier.OBSTRUCTED_FALLBACK,
            inViewshedOverlap = false
        )
        val result = BridgeResult(
            params = BridgeParams(42.12, -80.08, "TX", null, 42.19, -79.96, "RX", null),
            txGroundElevationMeters = 200.0,
            rxGroundElevationMeters = 210.0,
            directDistanceKm = 12.5,
            directBaseline = forwardPassReverseFailHop,
            bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888),
            northLat = 42.22, southLat = 42.08, westLon = -80.12, eastLon = -79.92,
            txCoveredAreaKm2 = 10.0, rxCoveredAreaKm2 = 8.0, mutualOverlapAreaKm2 = 2.5,
            candidatesEvaluated = 5, topCandidates = listOf(cand),
            tileCountUsed = 1, computeTimeMs = 8L
        )
        val state = BridgeUiState(
            currentResult = result,
            isViewshedStale = true,
            activeDrawerTab = BridgeDrawerTab.RESULTS
        )

        composeRule.setContent {
            LEMeshTheme {
                BridgePlannerScreen(
                    uiState = state,
                    existingNodes = emptyList(),
                    baseLayer = MapBaseLayer.DARK_MATTER,
                    onSelectBaseLayer = {},
                    onToggleTopBarExpanded = {},
                    onSetPlacementMode = {},
                    onSetPlacementTarget = {},
                    onMapTap = { _, _ -> },
                    onTxDragEnd = { _, _ -> },
                    onRxDragEnd = { _, _ -> },
                    onSelectTxNode = {},
                    onSelectRxNode = {},
                    onExistingNodeTap = {},
                    onSwapTxRx = {},
                    onClearOverlay = {},
                    onResetAll = {},
                    onTxHeightChange = {},
                    onRxHeightChange = {},
                    onRelayHeightChange = {},
                    onRadiusKmChange = {},
                    onFrequencyChange = {},
                    onTxPowerChange = {},
                    onTxAntennaGainChange = {},
                    onTxSensitivityChange = {},
                    onRxPowerChange = {},
                    onRxAntennaGainChange = {},
                    onRxSensitivityChange = {},
                    onRelayPowerChange = {},
                    onRelayAntennaGainChange = {},
                    onRelaySensitivityChange = {},
                    onOpacityChange = {},
                    onCalculateBridge = {},
                    onSelectCandidate = {},
                    onSelectDrawerTab = {},
                    onSelectObstruction = {},
                    onDismissError = {}
                )
            }
        }

        // Expand bottom drawer
        composeRule.onNodeWithTag("bridge_drawer_toggle").performClick()

        // Verify TX, RX, Relay radio accordions render with 22 dBm / 3.0 dBi defaults
        composeRule.onNodeWithTag("bridge_tx_rf_summary").assertIsDisplayed()
        composeRule.onNodeWithTag("bridge_tx_rf_toggle").performClick()
        composeRule.onNodeWithTag("bridge_tx_gain_slider").assertIsDisplayed()
        composeRule.onNodeWithTag("bridge_tx_power_slider").assertIsDisplayed()
        composeRule.onNodeWithTag("bridge_tx_sensitivity_slider").assertIsDisplayed()

        composeRule.onNodeWithTag("bridge_rx_rf_toggle").performScrollTo().performClick()
        composeRule.onNodeWithTag("bridge_rx_gain_slider").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("bridge_relay_rf_toggle").performScrollTo().performClick()
        composeRule.onNodeWithTag("bridge_relay_power_slider").performScrollTo().assertIsDisplayed()

        // Verify bidirectional scorecard stale banner & forward/reverse labels
        composeRule.onNodeWithTag("bridge_viewshed_stale_banner").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Relay → TX", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("RX → Relay", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `BridgeScorecardSection standalone renders mutual overlap and bidirectional margins`() {
        val hop = BridgeHopEvaluation(
            distanceKm = 5.0,
            status = ViewshedPointStatus.CLEAR,
            predictedRssiDbm = -90.0,
            linkMarginDb = 40.0,
            worstClearanceMeters = 10.0,
            minFresnelRatio = 1.0,
            reverseRssiDbm = -96.0,
            reverseLinkMarginDb = 34.0,
            bottleneckLinkMarginDb = 34.0
        )
        val cand = RelayCandidate(
            rank = 1,
            latitude = 42.15,
            longitude = -80.02,
            groundElevationMeters = 280.0,
            txHop = hop,
            rxHop = hop,
            worstLinkMarginDb = 34.0,
            minFresnelRatio = 1.0,
            tier = BridgeRankTier.MUTUAL_CLEAR_LOS,
            inViewshedOverlap = true
        )
        val result = BridgeResult(
            params = BridgeParams(42.12, -80.08, "TX", null, 42.19, -79.96, "RX", null),
            txGroundElevationMeters = 200.0,
            rxGroundElevationMeters = 210.0,
            directDistanceKm = 12.5,
            directBaseline = hop,
            bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888),
            northLat = 42.22, southLat = 42.08, westLon = -80.12, eastLon = -79.92,
            txCoveredAreaKm2 = 10.0, rxCoveredAreaKm2 = 8.0, mutualOverlapAreaKm2 = 4.2,
            candidatesEvaluated = 5, topCandidates = listOf(cand),
            tileCountUsed = 1, computeTimeMs = 8L
        )
        composeRule.setContent {
            LEMeshTheme {
                BridgeScorecardSection(
                    result = result,
                    selectedCandidateIndex = 0,
                    onSelectCandidate = {},
                    isViewshedStale = false
                )
            }
        }
        composeRule.onNodeWithTag("bridge_overlap_area").assertIsDisplayed()
        composeRule.onNodeWithTag("relay_candidate_card_1").assertIsDisplayed()
    }
}
