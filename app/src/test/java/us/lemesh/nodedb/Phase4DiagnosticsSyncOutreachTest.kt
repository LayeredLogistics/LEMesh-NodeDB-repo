package us.lemesh.nodedb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
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
import us.lemesh.nodedb.data.AlignmentTarget
import us.lemesh.nodedb.data.AntennaAlignmentMath
import us.lemesh.nodedb.data.BridgeParams
import us.lemesh.nodedb.data.CustomNodeStore
import us.lemesh.nodedb.data.CustomNodeStoreData
import us.lemesh.nodedb.data.FadeMarginPreset
import us.lemesh.nodedb.data.GeoJsonReportExporter
import us.lemesh.nodedb.data.LinkDiagnosticTier
import us.lemesh.nodedb.data.LiveMeshSyncRepository
import us.lemesh.nodedb.data.LiveSyncConfig
import us.lemesh.nodedb.data.LiveSyncStatus
import us.lemesh.nodedb.data.LiveSyncTransport
import us.lemesh.nodedb.data.LiveTelemetryPayloadParser
import us.lemesh.nodedb.data.LosAnalyzer
import us.lemesh.nodedb.data.LosEndpoint
import us.lemesh.nodedb.data.MapExportEngine
import us.lemesh.nodedb.data.MapExportSummary
import us.lemesh.nodedb.data.MeshNode
import us.lemesh.nodedb.data.MqttV311WireCodec
import us.lemesh.nodedb.data.NeighborLinkTelemetry
import us.lemesh.nodedb.data.NodeDbParser
import us.lemesh.nodedb.data.NodeOverride
import us.lemesh.nodedb.data.RelayBridgeEngine
import us.lemesh.nodedb.data.RfEnvironmentStateHolder
import us.lemesh.nodedb.data.TerrariumDemRepository
import us.lemesh.nodedb.data.TerrariumTileGrid
import us.lemesh.nodedb.data.ViewshedEngine
import us.lemesh.nodedb.data.ViewshedParams
import us.lemesh.nodedb.ui.AntennaAlignmentHudOverlay
import us.lemesh.nodedb.ui.CompactNodeMapSearchBar
import us.lemesh.nodedb.ui.LiveSyncToolbarBadge
import us.lemesh.nodedb.ui.PropagationFadeMarginStrip
import us.lemesh.nodedb.ui.state.NodeInventoryState
import us.lemesh.nodedb.ui.theme.LEMeshTheme
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Phase4DiagnosticsSyncOutreachTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun flatGridFor(lat: Double, lon: Double, elevMeters: Float = 200f): TerrariumTileGrid {
        val zoom = 12
        val (minTx, minTy) = TerrariumDemRepository.latLonToTile(lat + 0.15, lon - 0.15, zoom)
        val (maxTx, maxTy) = TerrariumDemRepository.latLonToTile(lat - 0.15, lon + 0.15, zoom)
        val raw = elevMeters.toInt() + 32768
        val rgb = Color.rgb((raw shr 8) and 0xFF, raw and 0xFF, 0)
        val tiles = mutableMapOf<Long, IntArray>()
        for (tx in minTx..maxTx) {
            for (ty in minTy..maxTy) {
                tiles[TerrariumTileGrid.tileKey(tx, ty)] = IntArray(256 * 256) { rgb }
            }
        }
        return TerrariumTileGrid(
            zoom = zoom,
            tiles = tiles,
            tileCount = tiles.size
        )
    }

    private fun makeNode(
        id: String,
        num: Long? = null,
        longName: String,
        shortName: String,
        role: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        altitude: Int? = null,
        hopsAway: Int? = 0,
        viaMqtt: Boolean = false,
        isFavorite: Boolean = false,
        isSelf: Boolean = false,
        snr: Double? = null,
        rssi: Int? = null,
        isManual: Boolean = false,
        notes: String? = null
    ) = MeshNode(
        num = num,
        id = id,
        longName = longName,
        shortName = shortName,
        hwModel = null,
        role = role,
        latitude = latitude,
        longitude = longitude,
        altitude = altitude,
        hopsAway = hopsAway,
        viaMqtt = viaMqtt,
        isFavorite = isFavorite,
        isSelf = isSelf,
        snr = snr,
        rssi = rssi,
        lastHeard = null,
        deviceMetrics = null,
        environmentMetrics = null,
        rawJson = "{}",
        isManual = isManual,
        notes = notes
    )

    // ---------------------------------------------------------------------
    // 1. Observed vs. Predicted RF Link Diagnostics & Neighbor Parsing
    // ---------------------------------------------------------------------

    @Test
    fun observedVsPredictedDiagnostics_classifyNominalClutterAndSevereTiers() {
        val pointA = LosEndpoint(42.8864, -78.8784, "Self", "SELF")
        val pointB = LosEndpoint(42.9364, -78.8784, "Remote", "REM")
        val samples = List(25) { 200.0 }
        val coords = LosAnalyzer.sampleGreatCirclePath(pointA, pointB, count = samples.size)
        val los = LosAnalyzer.analyze(
            pointA = pointA,
            pointB = pointB,
            coordinates = coords,
            elevationsMeters = samples,
            txHeightMeters = 10.0,
            rxHeightMeters = 10.0,
            frequencyMhz = 906.875,
            fadeMarginDb = 0.0
        )

        val predSnr = los.predictedSnrDb
        assertEquals(LosAnalyzer.estimatePredictedSnrDb(los.predictedRssiDbm), predSnr, 1e-6)

        val nominal = LosAnalyzer.evaluateObservedVsPredicted(observedSnrDb = predSnr - 3.0, observedRssiDbm = -92, predicted = los)
        assertNotNull(nominal)
        assertEquals(LinkDiagnosticTier.NOMINAL, nominal!!.tier)
        assertEquals(-3.0, nominal.deltaSnrDb, 1e-4)

        val clutter = LosAnalyzer.evaluateObservedVsPredicted(observedSnrDb = predSnr - 9.0, observedRssiDbm = -102, predicted = los)
        assertNotNull(clutter)
        assertEquals(LinkDiagnosticTier.CLUTTER_LOSS, clutter!!.tier)
        assertEquals(-9.0, clutter.deltaSnrDb, 1e-4)

        val severe = LosAnalyzer.evaluateObservedVsPredicted(observedSnrDb = predSnr - 15.5, observedRssiDbm = -114, predicted = los)
        assertNotNull(severe)
        assertEquals(LinkDiagnosticTier.SEVERE_ANOMALY, severe!!.tier)
        assertEquals(-15.5, severe.deltaSnrDb, 1e-4)
    }

    @Test
    fun nodeDbParser_parsesNeighborInfoLinksAndResolvesObservedSnr() {
        val json = """
            {
              "myNodeNum": 100,
              "nodes": {
                "!00000064": {
                  "num": 100,
                  "user": { "id": "!00000064", "longName": "Buffalo Roof", "shortName": "BUF", "role": "ROUTER" },
                  "position": { "latitudeI": 428864000, "longitudeI": -788784000, "altitude": 200 },
                  "neighborInfo": {
                    "neighbors": [
                      { "nodeId": 200, "snr": -4.5, "lastRxTime": 1700000100 }
                    ]
                  }
                },
                "!000000c8": {
                  "num": 200,
                  "user": { "id": "!000000c8", "longName": "Niagara Tower", "shortName": "NIA", "role": "REPEATER" },
                  "position": { "latitudeI": 430962000, "longitudeI": -790377000, "altitude": 210 },
                  "hopsAway": 0,
                  "snr": 2.0
                }
              }
            }
        """.trimIndent()

        val parsed = NodeDbParser.parse(json).getOrThrow()
        val selfNode = parsed.nodes.first { it.num == 100L }
        val remoteNode = parsed.nodes.first { it.num == 200L }

        assertEquals(1, selfNode.neighborLinks.size)
        assertEquals(200L, selfNode.neighborLinks.first().neighborNum)
        assertEquals(-4.5, selfNode.neighborLinks.first().snrDb, 1e-4)

        // Neighbor link SNR takes priority when present between selfNode and remoteNode
        assertEquals(-4.5, LosAnalyzer.resolveObservedSnr(selfNode, remoteNode)!!, 1e-4)

        // Falls back to peer.snr if no explicit neighborLinks record exists
        val selfNoNeighbors = selfNode.copy(neighborLinks = emptyList())
        assertEquals(2.0, LosAnalyzer.resolveObservedSnr(selfNoNeighbors, remoteNode)!!, 1e-4)
    }

    // ---------------------------------------------------------------------
    // 2. Global Fade Margin Presets Across LoS, Viewshed & Bridge Engines
    // ---------------------------------------------------------------------

    @Test
    fun fadeMarginPresets_shiftPredictedRssiAndLinkMarginConsistentlyAcrossEngines() {
        val envHolder = RfEnvironmentStateHolder()
        assertEquals(FadeMarginPreset.WINTER_CLEAR, envHolder.fadeMarginPreset.value)
        envHolder.setFadeMarginPreset(FadeMarginPreset.LAKE_EFFECT_SQUALL)
        assertEquals(12.0, envHolder.currentFadeMarginDb, 1e-6)

        // LoS engine
        val a = LosEndpoint(42.8864, -78.8784, "A", "A")
        val b = LosEndpoint(42.9364, -78.8784, "B", "B")
        val flatSamples = List(21) { 200.0 }
        val flatCoords = LosAnalyzer.sampleGreatCirclePath(a, b, count = flatSamples.size)
        val losWinter = LosAnalyzer.analyze(a, b, flatCoords, flatSamples, 10.0, 10.0, 906.875, fadeMarginDb = 0.0)
        val losSummer = LosAnalyzer.analyze(a, b, flatCoords, flatSamples, 10.0, 10.0, 906.875, fadeMarginDb = 6.0)
        val losSquall = LosAnalyzer.analyze(a, b, flatCoords, flatSamples, 10.0, 10.0, 906.875, fadeMarginDb = 12.0)

        assertEquals(losWinter.predictedRssiDbm - 6.0, losSummer.predictedRssiDbm, 1e-6)
        assertEquals(losWinter.predictedRssiDbm - 12.0, losSquall.predictedRssiDbm, 1e-6)
        assertEquals(losWinter.linkMarginDb - 12.0, losSquall.linkMarginDb, 1e-6)

        // Bridge engine hop evaluation
        val grid = flatGridFor(42.8864, -78.8784, 200f)
        val bridgeWinter = RelayBridgeEngine.evaluateHop(
            latA = 42.8864, lonA = -78.8784, heightAMeters = 10.0,
            latB = 42.9364, lonB = -78.8784, heightBMeters = 10.0,
            freqMhz = 906.875, freqGhz = 0.906875,
            eirpDbm = 23.0, sensitivityDbm = -130.0,
            grid = grid,
            fadeMarginDb = 0.0
        )
        val bridgeSquall = RelayBridgeEngine.evaluateHop(
            latA = 42.8864, lonA = -78.8784, heightAMeters = 10.0,
            latB = 42.9364, lonB = -78.8784, heightBMeters = 10.0,
            freqMhz = 906.875, freqGhz = 0.906875,
            eirpDbm = 23.0, sensitivityDbm = -130.0,
            grid = grid,
            fadeMarginDb = 12.0
        )
        assertEquals(bridgeWinter.predictedRssiDbm - 12.0, bridgeSquall.predictedRssiDbm, 1e-5)
        assertEquals(bridgeWinter.bottleneckLinkMarginDb - 12.0, bridgeSquall.bottleneckLinkMarginDb, 1e-5)

        // Viewshed engine polar field
        val vsWinter = ViewshedEngine.computePolarField(
            originLat = 42.8864, originLon = -78.8784,
            antennaHeightMeters = 10.0, radiusKm = 10.0,
            frequencyMhz = 906.875, txPowerDbm = 20.0, antennaGainDbi = 3.0, sensitivityDbm = -130.0,
            grid = grid,
            rayCount = 36,
            stepMeters = 500.0,
            fadeMarginDb = 0.0
        )
        val vsSquall = ViewshedEngine.computePolarField(
            originLat = 42.8864, originLon = -78.8784,
            antennaHeightMeters = 10.0, radiusKm = 10.0,
            frequencyMhz = 906.875, txPowerDbm = 20.0, antennaGainDbi = 3.0, sensitivityDbm = -130.0,
            grid = grid,
            rayCount = 36,
            stepMeters = 500.0,
            fadeMarginDb = 12.0
        )
        val idx = 10
        assertEquals(vsWinter.field.rssiGrid[idx] - 12f, vsSquall.field.rssiGrid[idx], 1e-3f)
    }

    // ---------------------------------------------------------------------
    // 3. Host Outreach Card Privacy Filtering & Bitmap Composition
    // ---------------------------------------------------------------------

    @Test
    fun hostOutreachCard_omitsPersonalNodesMasksPortableSitesAndNeverIncludesPhotos() {
        val personalHandheld = makeNode(
            id = "!client01", num = 1, longName = "Alice Handheld", shortName = "ALI",
            role = "CLIENT", latitude = 42.8901, longitude = -78.8701, isManual = false
        )
        val portableManual = makeNode(
            id = "manual_portable_1", num = null, longName = "My Back Porch Test", shortName = "PRCH",
            role = "CLIENT", latitude = 42.91234, longitude = -78.85432, isManual = true
        )
        val fixedRouter = makeNode(
            id = "!router99", num = 99, longName = "Erie County Tower", shortName = "ERIE",
            role = "ROUTER", latitude = 42.9500, longitude = -78.8200, isManual = false
        )

        val spec = GeoJsonReportExporter.buildHostOutreachSpec(
            nodes = listOf(personalHandheld, portableManual, fixedRouter),
            candidateSiteTitle = "Hamburg Water Tower"
        )

        assertFalse("Host Outreach Card must never embed property photos", spec.includesPropertyPhotos)
        assertEquals(1, spec.omittedPersonalNodeCount)
        assertEquals(1, spec.maskedPortableSiteCount)
        assertEquals(2, spec.includedSites.size)

        // Personal handheld omitted
        assertTrue(spec.includedSites.none { it.id == "!client01" })

        // Portable manual site masked & anonymized
        val portableSite = spec.includedSites.first { it.id == "manual_portable_1" }
        assertTrue(portableSite.isMaskedPortable)
        assertEquals("Community Relay Sector", portableSite.label)
        assertTrue(
            kotlin.math.abs(portableSite.latitude - 42.91234) > 1e-5 ||
                kotlin.math.abs(portableSite.longitude - (-78.85432)) > 1e-5
        )

        // Fixed infrastructure retained
        val routerSite = spec.includedSites.first { it.id == "!router99" }
        assertFalse(routerSite.isMaskedPortable)
        assertEquals("Erie County Tower", routerSite.label)

        // Hardware summary badges present
        assertTrue(spec.hardwareBadgeLines.any { it.contains("Solar", ignoreCase = true) })
        assertTrue(spec.hardwareBadgeLines.any { it.contains("Zero Utility Power", ignoreCase = true) })
        assertTrue(spec.hardwareBadgeLines.any { it.contains("Non-Penetrating", ignoreCase = true) })

        // Render Host Outreach Card PNG bitmap cleanly
        val dummyMap = Bitmap.createBitmap(400, 260, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.DKGRAY) }
        val cardBitmap = MapExportEngine.composeHostOutreachCardBitmap(dummyMap, spec)
        assertTrue(cardBitmap.width >= 840)
        assertTrue(cardBitmap.height > dummyMap.height)
        dummyMap.recycle()
        cardBitmap.recycle()
    }

    // ---------------------------------------------------------------------
    // 4. MQTT v3.1.1 Codec, Payload Parsing, Non-Destructive Merge & Redaction
    // ---------------------------------------------------------------------

    @Test
    fun mqttCodecAndPayloadParser_roundTripAndPreserveCustomNotesAndRedaction() {
        // MQTT CONNECT / SUBSCRIBE / PUBLISH wire frame verification
        val connectPkt = MqttV311WireCodec.encodeConnectPacket("lemesh-test", "opUser", "superSecretPass!")
        val connectIn = DataInputStream(ByteArrayInputStream(connectPkt))
        val (connHeader, connBody) = MqttV311WireCodec.readNextPacket(connectIn)
        assertEquals(0x10, connHeader and 0xF0)
        assertTrue(String(connBody, StandardCharsets.UTF_8).contains("MQTT"))

        val subPkt = MqttV311WireCodec.encodeSubscribePacket(1, "msh/US/2/json/#")
        val (subHeader, subBody) = MqttV311WireCodec.readNextPacket(DataInputStream(ByteArrayInputStream(subPkt)))
        assertEquals(0x80, subHeader and 0xF0)
        assertTrue(String(subBody, StandardCharsets.UTF_8).contains("msh/US/2/json/#"))

        val payloadJson = """
            {
              "from": 305419896,
              "type": "position",
              "rssi": -88,
              "snr": 7.5,
              "hopStart": 3,
              "hopsAway": 0,
              "payload": {
                "latitude_i": 428864000,
                "longitude_i": -788784000,
                "altitude": 215
              }
            }
        """.trimIndent()
        val pubPkt = MqttV311WireCodec.encodePublishPacketForTest(
            "msh/US/2/json/LongFast/!12345678",
            payloadJson.toByteArray(StandardCharsets.UTF_8)
        )
        val (pubHeader, pubBody) = MqttV311WireCodec.readNextPacket(DataInputStream(ByteArrayInputStream(pubPkt)))
        assertTrue(MqttV311WireCodec.isPublish(pubHeader))
        val decoded = MqttV311WireCodec.decodePublishBody(pubHeader and 0x0F, pubBody)
        assertNotNull(decoded)
        assertEquals("msh/US/2/json/LongFast/!12345678", decoded!!.topic)

        val updates = LiveTelemetryPayloadParser.parsePayload(String(decoded.payload, StandardCharsets.UTF_8))
        assertEquals(1, updates.size)
        assertEquals("!12345678", updates.first().nodeId)
        assertEquals(42.8864, updates.first().latitude!!, 1e-4)
        assertEquals(-78.8784, updates.first().longitude!!, 1e-4)
        assertEquals(7.5, updates.first().snr!!, 1e-4)
        assertEquals(-88, updates.first().rssi)

        // Non-destructive merge preserves manual nodes and custom notes/color overrides
        val existingNode = makeNode(
            id = "!12345678", num = 305419896L, longName = "Existing Tower", shortName = "EXTW",
            latitude = 42.80, longitude = -78.80, snr = 1.0, rssi = -105
        )
        val manualNode = makeNode(
            id = "manual_site_1", num = null, longName = "Candidate Barn", shortName = "BARN",
            latitude = 42.91, longitude = -78.82, isManual = true, notes = "Owner approved solar mount"
        )
        val store = CustomNodeStoreData(
            manualNodes = listOf(manualNode),
            overrides = mapOf(
                existingNode.stableKey to NodeOverride(
                    targetKey = existingNode.stableKey,
                    id = existingNode.id,
                    num = existingNode.num,
                    longName = existingNode.longName,
                    shortName = existingNode.shortName,
                    hwModel = existingNode.hwModel,
                    role = existingNode.role,
                    latitude = existingNode.latitude,
                    longitude = existingNode.longitude,
                    altitude = existingNode.altitude,
                    hopsAway = existingNode.hopsAway,
                    viaMqtt = existingNode.viaMqtt,
                    isFavorite = existingNode.isFavorite,
                    isSelf = existingNode.isSelf,
                    snr = existingNode.snr,
                    rssi = existingNode.rssi,
                    notes = "Tower mast 35m AGL",
                    customColorArgb = 0xFF22C55E.toInt(),
                    overrideLocation = false,
                    overrideTelemetry = false
                )
            )
        )
        val mergedBase = NodeInventoryState.mergeLiveTelemetryUpdates(listOf(existingNode), updates)
        val finalNodes = CustomNodeStore.mergeNodes(mergedBase, store).nodes

        val updatedExisting = finalNodes.first { it.id == "!12345678" }
        assertEquals(42.8864, updatedExisting.latitude!!, 1e-4)
        assertEquals(7.5, updatedExisting.snr!!, 1e-4)
        assertEquals("Tower mast 35m AGL", updatedExisting.notes)
        assertEquals(0xFF22C55E.toInt(), updatedExisting.customColorArgb)
        assertTrue(finalNodes.any { it.id == "manual_site_1" && it.isManual })

        // Credential redaction check
        val cfg = LiveSyncConfig(
            transport = LiveSyncTransport.MQTT,
            mqttHost = "mqtt.lemesh.us",
            mqttPort = 8883,
            mqttUseTls = true,
            mqttUsername = "operator_alice",
            mqttPassword = "TopSecretPassword99!"
        )
        val summary = cfg.toRedactedEndpointSummary()
        assertFalse(summary.contains("TopSecretPassword99!"))
        assertFalse(summary.contains("operator_alice"))

        val redactedErr = LiveMeshSyncRepository.redactErrorMessage(
            "Auth failed for operator_alice with pass TopSecretPassword99! at mqtt.lemesh.us",
            cfg
        )
        assertFalse(redactedErr.contains("TopSecretPassword99!"))
        assertFalse(redactedErr.contains("operator_alice"))
    }

    // ---------------------------------------------------------------------
    // 5. Antenna Alignment Compass Math
    // ---------------------------------------------------------------------

    @Test
    fun antennaAlignmentMath_handlesDeclinationWrapAroundAndVerticalTilt() {
        // Magnetic declination conversion
        assertEquals(350.0, AntennaAlignmentMath.applyMagneticDeclination(0.0, -10.0), 1e-6)
        assertEquals(5.0, AntennaAlignmentMath.applyMagneticDeclination(355.0, 10.0), 1e-6)

        // Shortest turn across 0°/360° boundary
        assertEquals(20.0, AntennaAlignmentMath.computeRelativeTurnDeg(350.0, 10.0), 1e-6)
        assertEquals(-20.0, AntennaAlignmentMath.computeRelativeTurnDeg(10.0, 350.0), 1e-6)
        assertEquals(0.0, AntennaAlignmentMath.computeRelativeTurnDeg(90.0, 90.0), 1e-6)

        // Due North azimuth ~0°, due East ~90°
        val azNorth = AntennaAlignmentMath.computeTargetAzimuthDeg(42.8864, -78.8784, 43.0864, -78.8784)
        assertTrue(azNorth < 1.0 || azNorth > 359.0)

        val azEast = AntennaAlignmentMath.computeTargetAzimuthDeg(42.8864, -78.8784, 42.8864, -78.6784)
        assertEquals(90.0, azEast, 1.0)

        // Elevation angle: higher target at 1 km -> positive tilt; equal height at 20 km -> slightly negative due to Earth curvature
        val upTilt = AntennaAlignmentMath.computeVerticalElevationAngleDeg(
            distanceKm = 1.0,
            originElevationMeters = 200.0,
            targetElevationMeters = 300.0
        )
        assertTrue("Expected upward tilt > 5 deg, got $upTilt", upTilt > 5.0)
        val levelLongTilt = AntennaAlignmentMath.computeVerticalElevationAngleDeg(
            distanceKm = 20.0,
            originElevationMeters = 200.0,
            targetElevationMeters = 200.0
        )
        assertTrue("Expected slight downward tilt over 20 km due to curvature, got $levelLongTilt", levelLongTilt < 0.0)
        val nearZeroTilt = AntennaAlignmentMath.computeVerticalElevationAngleDeg(
            distanceKm = 0.0001,
            originElevationMeters = 200.0,
            targetElevationMeters = 200.0
        )
        assertEquals(0.0, nearZeroTilt, 1e-4)
    }

    // ---------------------------------------------------------------------
    // 6. Compose UI Rendering Verification for Phase 4 Controls
    // ---------------------------------------------------------------------

    @Test
    fun phase4UiComponents_renderAndRespondToClicks() {
        var selectedPreset = FadeMarginPreset.WINTER_CLEAR
        var liveSyncClicked = false
        var hudClosed = false

        val target = AlignmentTarget(
            id = "relay_1",
            label = "Relay #1",
            subtitle = "312m ASL • +14.2 dB",
            latitude = 42.95,
            longitude = -78.80,
            elevationMeters = 320.0
        )
        val fallbackSelf = makeNode(
            id = "!self", num = 1, longName = "Self Base", shortName = "SELF",
            latitude = 42.8864, longitude = -78.8784, altitude = 200, isSelf = true
        )

        composeRule.setContent {
            LEMeshTheme {
                androidx.compose.foundation.layout.Column {
                    LiveSyncToolbarBadge(
                        status = LiveSyncStatus.CONNECTED,
                        onClick = { liveSyncClicked = true }
                    )
                    PropagationFadeMarginStrip(
                        selectedPreset = selectedPreset,
                        onSelectPreset = { selectedPreset = it },
                        testTagPrefix = "test"
                    )
                    AntennaAlignmentHudOverlay(
                        target = target,
                        availableTargets = listOf(target),
                        fallbackOriginNode = fallbackSelf,
                        onSelectTarget = {},
                        onClose = { hudClosed = true }
                    )
                }
            }
        }

        composeRule.onNodeWithTag("live_sync_toolbar_badge").assertIsDisplayed().performClick()
        assertTrue(liveSyncClicked)

        composeRule.onNodeWithTag("test_fade_preset_lake_effect_squall").assertIsDisplayed().performClick()
        assertEquals(FadeMarginPreset.LAKE_EFFECT_SQUALL, selectedPreset)

        composeRule.onNodeWithTag("antenna_alignment_hud").assertIsDisplayed()
        composeRule.onNodeWithTag("antenna_alignment_close_button").assertIsDisplayed().performClick()
        assertTrue(hudClosed)
    }

    @Test
    fun lemeshBrandEmblem_decodesRendersInToolbarAndExportsCleanly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val emblem = MapExportEngine.decodeBrandEmblemBitmap(context)
        assertNotNull("Expected lemesh_brand_emblem drawable to decode", emblem)
        assertTrue(emblem!!.width > 0 && emblem.height > 0)

        val dummyMap = Bitmap.createBitmap(640, 420, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.DKGRAY) }
        val summary = MapExportSummary(
            screenTitle = "Node Map Snapshot",
            subtitle = "Lake Effect Mesh • lemesh.us",
            baseLayerName = "Dark Tactical",
            timestampLabel = "2026-11-23 12:40",
            metricLines = listOf("12 GPS nodes • 906.875 MHz"),
            legendItems = emptyList(),
            showRssiGradient = false
        )
        val snapshot = MapExportEngine.composeAnnotatedExportBitmap(dummyMap, summary, emblem)
        assertTrue(snapshot.width >= 640)
        assertTrue(snapshot.height > dummyMap.height)

        val spec = GeoJsonReportExporter.buildHostOutreachSpec(
            nodes = emptyList(),
            candidateSiteTitle = "Presque Isle Relay"
        )
        val outreach = MapExportEngine.composeHostOutreachCardBitmap(dummyMap, spec, emblem)
        assertTrue(outreach.width >= 840)
        assertTrue(outreach.height > dummyMap.height)

        snapshot.recycle()
        outreach.recycle()
        dummyMap.recycle()
        emblem.recycle()

        composeRule.setContent {
            LEMeshTheme {
                CompactNodeMapSearchBar(
                    searchQuery = "",
                    activeFilterCount = 0,
                    savedLayersActiveCount = 0,
                    onSearchQueryChange = {},
                    onOpenFilters = {},
                    onOpenLayers = {}
                )
            }
        }
        composeRule.onNodeWithTag("toolbar_lemesh_emblem").assertIsDisplayed()
    }
}
