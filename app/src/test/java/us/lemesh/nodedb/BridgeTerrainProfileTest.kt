package us.lemesh.nodedb

import android.app.Application
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import us.lemesh.nodedb.data.BridgeLinkSegment
import us.lemesh.nodedb.data.BridgeParams
import us.lemesh.nodedb.data.BridgeProfileMode
import us.lemesh.nodedb.data.BridgeProfileSample
import us.lemesh.nodedb.data.BridgeRankTier
import us.lemesh.nodedb.data.BridgeResult
import us.lemesh.nodedb.data.LosAnalyzer
import us.lemesh.nodedb.data.RelayBridgeEngine
import us.lemesh.nodedb.data.RelayCandidate
import us.lemesh.nodedb.data.TerrariumDemRepository
import us.lemesh.nodedb.data.TerrariumTileGrid
import us.lemesh.nodedb.data.ViewshedPointStatus
import us.lemesh.nodedb.ui.BridgeObstructionsSection
import us.lemesh.nodedb.ui.BridgeViewModel
import us.lemesh.nodedb.ui.computeProfileYBounds
import us.lemesh.nodedb.ui.distanceAlongCompositeProfileKm
import us.lemesh.nodedb.ui.findNearestPeakHitId
import us.lemesh.nodedb.ui.findNearestProfileSample
import us.lemesh.nodedb.ui.theme.LEMeshTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BridgeTerrainProfileTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun buildRidgeCorridorGrid(
        txLat: Double,
        txLon: Double,
        rxLat: Double,
        rxLon: Double,
        ridgeLat: Double,
        ridgeElevMeters: Int,
        baseElevMeters: Int = 200
    ): TerrariumTileGrid {
        val north = maxOf(txLat, rxLat) + 0.03
        val south = minOf(txLat, rxLat) - 0.03
        val west = minOf(txLon, rxLon) - 0.03
        val east = maxOf(txLon, rxLon) + 0.03
        val zoom = TerrariumDemRepository.recommendedZoomForBounds(north, south, west, east, 12)
        val (minTx, minTy) = TerrariumDemRepository.latLonToTile(north, west, zoom)
        val (maxTx, maxTy) = TerrariumDemRepository.latLonToTile(south, east, zoom)

        val baseRaw = baseElevMeters + 32768
        val baseRgb = Color.rgb(baseRaw shr 8, baseRaw and 0xFF, 0)
        val tiles = mutableMapOf<Long, IntArray>()
        for (ty in minTy..maxTy) {
            for (tx in minTx..maxTx) {
                tiles[TerrariumTileGrid.tileKey(tx, ty)] = IntArray(256 * 256) { baseRgb }
            }
        }

        val n = (1 shl zoom).toDouble()
        val latRad = Math.toRadians(ridgeLat)
        val worldY = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n
        val ridgeTy = floor(worldY).toInt()
        val ridgePy = ((worldY - ridgeTy) * 256.0).toInt().coerceIn(5, 250)
        val ridgeRaw = ridgeElevMeters + 32768
        val ridgeRgb = Color.rgb(ridgeRaw shr 8, ridgeRaw and 0xFF, 0)
        for (tx in minTx..maxTx) {
            val arr = tiles[TerrariumTileGrid.tileKey(tx, ridgeTy)] ?: continue
            for (y in (ridgePy - 5)..(ridgePy + 5)) {
                for (px in 0 until 256) {
                    arr[y * 256 + px] = ridgeRgb
                }
            }
        }
        return TerrariumTileGrid(zoom = zoom, tiles = tiles, tileCount = tiles.size)
    }

    @Test
    fun `evaluateHop populates dense profile samples and detects ridge intrusion for Direct TX-RX`() {
        val txLat = 42.1000
        val txLon = -80.0500
        val rxLat = 42.2000
        val rxLon = -80.0500
        val grid = buildRidgeCorridorGrid(
            txLat = txLat,
            txLon = txLon,
            rxLat = rxLat,
            rxLon = rxLon,
            ridgeLat = 42.1500,
            ridgeElevMeters = 330
        )

        val hop = RelayBridgeEngine.evaluateHop(
            latA = txLat,
            lonA = txLon,
            heightAMeters = 10.0,
            latB = rxLat,
            lonB = rxLon,
            heightBMeters = 10.0,
            freqMhz = LosAnalyzer.DEFAULT_LORA_US_FREQ_MHZ,
            freqGhz = LosAnalyzer.DEFAULT_LORA_US_FREQ_MHZ / 1000.0,
            eirpDbm = 35.8,
            sensitivityDbm = -130.0,
            grid = grid,
            collectObstructionPeaks = true
        )

        assertEquals(ViewshedPointStatus.OBSTRUCTED, hop.status)
        assertTrue("Expected >= 96 samples, got ${hop.profileSamples.size}", hop.profileSamples.size >= 96)
        assertEquals(0.0, hop.profileSamples.first().distanceFromStartKm, 1e-4)
        assertEquals(hop.distanceKm, hop.profileSamples.last().distanceFromStartKm, 1e-4)
        assertTrue(hop.profileSamples.any { it.clearanceMeters < 0.0 })
        assertNotNull(hop.worstObstruction)
        val directObs = RelayBridgeEngine.buildDirectObstructionList(hop)
        assertTrue(directObs.isNotEmpty())
        assertEquals("DIRECT_1", directObs.first().id)
    }

    @Test
    fun `buildCompositeProfile switches between obstructed Direct path and cleared two-hop Relay path`() {
        val txLat = 42.1000
        val txLon = -80.0500
        val rxLat = 42.2000
        val rxLon = -80.0500
        val relayLat = 42.1500
        val relayLon = -80.0500

        val grid = buildRidgeCorridorGrid(
            txLat = txLat,
            txLon = txLon,
            rxLat = rxLat,
            rxLon = rxLon,
            ridgeLat = relayLat,
            ridgeElevMeters = 320
        )

        val freq = LosAnalyzer.DEFAULT_LORA_US_FREQ_MHZ
        val directHop = RelayBridgeEngine.evaluateHop(
            latA = txLat, lonA = txLon, heightAMeters = 10.0,
            latB = rxLat, lonB = rxLon, heightBMeters = 10.0,
            freqMhz = freq, freqGhz = freq / 1000.0, eirpDbm = 35.8, sensitivityDbm = -130.0,
            grid = grid, collectObstructionPeaks = true
        )
        val txHop = RelayBridgeEngine.evaluateHop(
            latA = txLat, lonA = txLon, heightAMeters = 10.0,
            latB = relayLat, lonB = relayLon, heightBMeters = 15.0,
            freqMhz = freq, freqGhz = freq / 1000.0, eirpDbm = 35.8, sensitivityDbm = -130.0,
            grid = grid, collectObstructionPeaks = true
        )
        val rxHop = RelayBridgeEngine.evaluateHop(
            latA = relayLat, lonA = relayLon, heightAMeters = 15.0,
            latB = rxLat, lonB = rxLon, heightBMeters = 10.0,
            freqMhz = freq, freqGhz = freq / 1000.0, eirpDbm = 35.8, sensitivityDbm = -130.0,
            grid = grid, collectObstructionPeaks = true
        )

        val candidate = RelayCandidate(
            rank = 1,
            latitude = relayLat,
            longitude = relayLon,
            groundElevationMeters = 320.0,
            txHop = txHop,
            rxHop = rxHop,
            worstLinkMarginDb = minOf(txHop.linkMarginDb, rxHop.linkMarginDb),
            minFresnelRatio = minOf(txHop.minFresnelRatio, rxHop.minFresnelRatio),
            tier = BridgeRankTier.MUTUAL_CLEAR_LOS,
            inViewshedOverlap = true
        )
        val dummyBitmap = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        val result = BridgeResult(
            params = BridgeParams(txLat, txLon, "TX", null, rxLat, rxLon, "RX", null),
            txGroundElevationMeters = 200.0,
            rxGroundElevationMeters = 200.0,
            directDistanceKm = directHop.distanceKm,
            directBaseline = directHop,
            bitmap = dummyBitmap,
            northLat = 42.22, southLat = 42.08, westLon = -80.10, eastLon = -80.00,
            txCoveredAreaKm2 = 10.0, rxCoveredAreaKm2 = 10.0, mutualOverlapAreaKm2 = 4.0,
            candidatesEvaluated = 1, topCandidates = listOf(candidate),
            tileCountUsed = grid.tileCount, computeTimeMs = 10L
        )

        val directProfile = RelayBridgeEngine.buildCompositeProfile(
            mode = BridgeProfileMode.DIRECT,
            directHop = directHop,
            result = result,
            selectedCandidateIndex = 0
        )!!
        assertEquals(BridgeProfileMode.DIRECT, directProfile.mode)
        assertNull(directProfile.relayHingeIndex)
        assertTrue(directProfile.visibleObstructions.any { it.segment == BridgeLinkSegment.DIRECT })

        val relayProfile = RelayBridgeEngine.buildCompositeProfile(
            mode = BridgeProfileMode.RELAY,
            directHop = directHop,
            result = result,
            selectedCandidateIndex = 0
        )!!
        assertEquals(BridgeProfileMode.RELAY, relayProfile.mode)
        assertNotNull(relayProfile.relayHingeIndex)
        assertEquals(txHop.distanceKm, relayProfile.relayHingeDistanceKm!!, 1e-4)
        assertEquals(txHop.distanceKm + rxHop.distanceKm, relayProfile.totalDistanceKm, 1e-4)
        assertTrue(relayProfile.samples.any { it.segment == BridgeLinkSegment.TX_TO_RELAY })
        assertTrue(relayProfile.samples.any { it.segment == BridgeLinkSegment.RELAY_TO_RX })
        assertTrue(relayProfile.visibleObstructions.none { it.segment == BridgeLinkSegment.DIRECT })
    }

    @Test
    fun `findNearestPeakHitId and findNearestProfileSample map chart coordinates accurately`() {
        val grid = buildRidgeCorridorGrid(
            txLat = 42.1000, txLon = -80.0500,
            rxLat = 42.2000, rxLon = -80.0500,
            ridgeLat = 42.1500, ridgeElevMeters = 330
        )
        val hop = RelayBridgeEngine.evaluateHop(
            latA = 42.1000, lonA = -80.0500, heightAMeters = 10.0,
            latB = 42.2000, lonB = -80.0500, heightBMeters = 10.0,
            freqMhz = 906.875, freqGhz = 0.906875, eirpDbm = 35.8, sensitivityDbm = -130.0,
            grid = grid, collectObstructionPeaks = true
        )
        val profile = RelayBridgeEngine.buildCompositeProfile(
            mode = BridgeProfileMode.DIRECT,
            directHop = hop,
            result = null,
            selectedCandidateIndex = 0
        )!!
        val obs = profile.visibleObstructions.first()
        val bounds = computeProfileYBounds(profile.samples)

        val leftPad = 38f
        val topPad = 10f
        val plotW = 300f
        val plotH = 150f

        val peakDist = distanceAlongCompositeProfileKm(profile, obs)
        val peakX = leftPad + ((peakDist / profile.totalDistanceKm).toFloat() * plotW)
        val normY = ((obs.point.effectiveTerrainMeters - bounds.yMin) / bounds.yRange).toFloat().coerceIn(0f, 1f)
        val peakY = topPad + plotH * (1f - normY)

        val hit = findNearestPeakHitId(
            tapX = peakX + 4f,
            tapY = peakY - 3f,
            leftPadPx = leftPad,
            topPadPx = topPad,
            plotWidthPx = plotW,
            plotHeightPx = plotH,
            profile = profile,
            selectedObstructionId = null,
            hitRadiusPx = 28f
        )
        assertEquals(obs.id, hit)

        val miss = findNearestPeakHitId(
            tapX = leftPad + 5f,
            tapY = topPad + plotH - 5f,
            leftPadPx = leftPad,
            topPadPx = topPad,
            plotWidthPx = plotW,
            plotHeightPx = plotH,
            profile = profile,
            selectedObstructionId = null,
            hitRadiusPx = 20f
        )
        assertNull(miss)

        val midScrub = findNearestProfileSample(
            xPx = leftPad + plotW * 0.5f,
            leftPadPx = leftPad,
            plotWidthPx = plotW,
            profile = profile
        )
        assertNotNull(midScrub)
        assertEquals(profile.totalDistanceKm * 0.5, midScrub!!.distanceFromStartKm, 0.35)
    }

    @Test
    fun `BridgeViewModel computes live direct profile pre-solve and focuses map when peak selected`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val grid = buildRidgeCorridorGrid(
            txLat = 42.1000, txLon = -80.0500,
            rxLat = 42.2000, rxLon = -80.0500,
            ridgeLat = 42.1500, ridgeElevMeters = 330
        )

        val vm = BridgeViewModel(context)
        vm.corridorTileGridProvider = { _, _, _, _, _ -> Result.success(grid) }
        vm.setTxCustomCoordinate(42.1000, -80.0500)
        vm.setRxCustomCoordinate(42.2000, -80.0500)

        delay(420L)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val state = vm.uiState.value
        assertNull(state.currentResult)
        assertNotNull("Expected liveDirectHop before solve", state.liveDirectHop)
        assertEquals(ViewshedPointStatus.OBSTRUCTED, state.liveDirectHop!!.status)
        assertTrue(state.liveDirectHop!!.profileSamples.size >= 96)

        val directObs = RelayBridgeEngine.buildDirectObstructionList(state.liveDirectHop)
        assertTrue(directObs.isNotEmpty())
        val firstId = directObs.first().id
        val initialNonce = state.obstructionFocusNonce

        vm.selectObstruction(firstId)
        assertEquals(firstId, vm.uiState.value.selectedObstructionId)
        assertEquals(initialNonce + 1L, vm.uiState.value.obstructionFocusNonce)
        assertEquals(BridgeProfileMode.DIRECT, vm.uiState.value.profileMode)

        val probeSample = state.liveDirectHop!!.profileSamples[state.liveDirectHop!!.profileSamples.size / 3]
        vm.updateScrubbedProfileSample(probeSample)
        assertEquals(probeSample, vm.uiState.value.scrubbedProfileSample)
    }

    @Test
    fun `BridgeObstructionsSection renders live side profile pre-solve and triggers obstruction selection`() {
        val grid = buildRidgeCorridorGrid(
            txLat = 42.1000, txLon = -80.0500,
            rxLat = 42.2000, rxLon = -80.0500,
            ridgeLat = 42.1500, ridgeElevMeters = 330
        )
        val hop = RelayBridgeEngine.evaluateHop(
            latA = 42.1000, lonA = -80.0500, heightAMeters = 10.0,
            latB = 42.2000, lonB = -80.0500, heightBMeters = 10.0,
            freqMhz = 906.875, freqGhz = 0.906875, eirpDbm = 35.8, sensitivityDbm = -130.0,
            grid = grid, collectObstructionPeaks = true
        )
        val directObs = RelayBridgeEngine.buildDirectObstructionList(hop)
        assertTrue(directObs.isNotEmpty())

        var selectedObsId: String? = null
        var selectedMode: BridgeProfileMode? = null
        var clearedScrub = false

        val initialScrub: BridgeProfileSample = hop.profileSamples[hop.profileSamples.size / 2]

        composeRule.setContent {
            LEMeshTheme {
                BridgeObstructionsSection(
                    result = null,
                    selectedCandidateIndex = 0,
                    selectedObstructionId = null,
                    txHeightMeters = 10f,
                    rxHeightMeters = 10f,
                    relayHeightMeters = 15f,
                    onTxHeightChange = {},
                    onRxHeightChange = {},
                    onRelayHeightChange = {},
                    onSelectCandidate = {},
                    onSelectObstruction = { selectedObsId = it },
                    liveDirectHop = hop,
                    isLiveProfileLoading = false,
                    liveProfileError = null,
                    profileMode = BridgeProfileMode.DIRECT,
                    scrubbedProfileSample = initialScrub,
                    onSelectProfileMode = { selectedMode = it },
                    onScrubProfileSample = { if (it == null) clearedScrub = true },
                    onRetryLiveProfile = {}
                )
            }
        }

        composeRule.onNodeWithTag("bridge_terrain_profile_chart").assertIsDisplayed()
        composeRule.onNodeWithTag("bridge_profile_mode_direct").assertIsDisplayed()
        composeRule.onNodeWithTag("bridge_profile_mode_relay").assertIsNotEnabled()
        composeRule.onNodeWithTag("bridge_profile_scrub_readout").assertIsDisplayed()
        composeRule.onNodeWithTag("bridge_profile_clear_scrub").performClick()
        assertTrue(clearedScrub)

        composeRule.onNodeWithTag("bridge_profile_mode_direct").performClick()
        assertEquals(BridgeProfileMode.DIRECT, selectedMode)

        composeRule.onNodeWithTag("obstruction_card_${directObs.first().id}")
            .performScrollTo()
            .performClick()
        assertEquals(directObs.first().id, selectedObsId)
    }
}
