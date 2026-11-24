package us.lemesh.nodedb

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.meshtastic.proto.Config
import org.meshtastic.proto.Data
import org.meshtastic.proto.DeviceMetrics as ProtoDeviceMetrics
import org.meshtastic.proto.EnvironmentMetrics as ProtoEnvironmentMetrics
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.HardwareModel
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.Neighbor
import org.meshtastic.proto.NeighborInfo
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.RouteDiscovery
import org.meshtastic.proto.Routing
import org.meshtastic.proto.Telemetry
import org.meshtastic.proto.ToRadio
import org.meshtastic.proto.User
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig
import us.lemesh.nodedb.data.BleConnectionStatus
import us.lemesh.nodedb.data.BleSyncUiState
import us.lemesh.nodedb.data.CustomNodeStore
import us.lemesh.nodedb.data.CustomNodeStoreData
import us.lemesh.nodedb.data.DiscoveredMeshtasticDevice
import us.lemesh.nodedb.data.LiveSyncStatus
import us.lemesh.nodedb.data.LiveSyncTransport
import us.lemesh.nodedb.data.LiveSyncUiState
import us.lemesh.nodedb.data.LosAnalyzer
import us.lemesh.nodedb.data.MeshNode
import us.lemesh.nodedb.data.MeshtasticBleParser
import us.lemesh.nodedb.data.NodeOverride
import us.lemesh.nodedb.data.ParsedFromRadioEvent
import us.lemesh.nodedb.data.SavedBleRadio
import us.lemesh.nodedb.ui.BleSyncActions
import us.lemesh.nodedb.ui.LiveSyncBottomSheet
import us.lemesh.nodedb.ui.SyncSheetTab
import us.lemesh.nodedb.ui.UnifiedHorizonTopCard
import us.lemesh.nodedb.ui.state.NodeInventoryState
import us.lemesh.nodedb.ui.theme.LEMeshTheme

@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [35])
class MeshtasticBleParserTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun wantConfigPacket_encodesValidToRadioProtobuf() {
        val bytes = MeshtasticBleParser.buildWantConfigPacket(424242)
        val decoded = ToRadio.ADAPTER.decode(bytes)
        assertEquals(424242, decoded.want_config_id)
    }

    @Test
    fun parseFromRadio_decodesMyInfoConfigCompleteAndNodeInfoAccurately() {
        val myNumInt = 0x1A2B3C4D
        val myNumLong = myNumInt.toLong() and 0xFFFFFFFFL

        val myInfoFrame = FromRadio(
            my_info = MyNodeInfo(my_node_num = myNumInt)
        )
        val parsedMyInfo = MeshtasticBleParser.parseFromRadio(FromRadio.ADAPTER.encode(myInfoFrame))
        assertTrue(parsedMyInfo is ParsedFromRadioEvent.MyNodeInfoEvent)
        val myEvt = parsedMyInfo as ParsedFromRadioEvent.MyNodeInfoEvent
        assertEquals(myNumLong, myEvt.myNodeNum)
        assertEquals("!1a2b3c4d", myEvt.nodeId)

        val cfgFrame = FromRadio(config_complete_id = 777)
        val parsedCfg = MeshtasticBleParser.parseFromRadio(FromRadio.ADAPTER.encode(cfgFrame))
        assertEquals(ParsedFromRadioEvent.ConfigCompleteEvent(777), parsedCfg)

        val remoteNumInt = 0x99887766.toInt()
        val remoteNumLong = remoteNumInt.toLong() and 0xFFFFFFFFL
        val nodeInfoFrame = FromRadio(
            node_info = NodeInfo(
                num = remoteNumInt,
                user = User(
                    id = "!99887766",
                    long_name = "Hamburg Water Tower",
                    short_name = "HWT1",
                    hw_model = HardwareModel.RAK4631,
                    role = Config.DeviceConfig.Role.ROUTER
                ),
                position = Position(
                    latitude_i = 427159000,
                    longitude_i = -788294000,
                    altitude = 258
                ),
                snr = 6.25f,
                last_heard = 1730000500,
                device_metrics = ProtoDeviceMetrics(
                    battery_level = 94,
                    voltage = 4.18f,
                    channel_utilization = 14.5f,
                    air_util_tx = 2.1f,
                    uptime_seconds = 86400
                ),
                hops_away = 0,
                via_mqtt = false
            )
        )

        val parsedNode = MeshtasticBleParser.parseFromRadio(
            bytes = FromRadio.ADAPTER.encode(nodeInfoFrame),
            myNodeNum = myNumLong
        ) as ParsedFromRadioEvent.TelemetryBatchEvent

        assertTrue(parsedNode.isNodeInfoRecord)
        assertEquals(1, parsedNode.updates.size)
        val upd = parsedNode.updates.first()
        assertEquals(remoteNumLong, upd.nodeNum)
        assertEquals("!99887766", upd.nodeId)
        assertEquals("Hamburg Water Tower", upd.longName)
        assertEquals("HWT1", upd.shortName)
        assertEquals("RAK4631", upd.hwModel)
        assertEquals("ROUTER", upd.role)
        assertEquals(42.7159, upd.latitude!!, 1e-5)
        assertEquals(-78.8294, upd.longitude!!, 1e-5)
        assertEquals(258, upd.altitude)
        assertEquals(6.25, upd.snr!!, 1e-3)
        assertEquals(0, upd.hopsAway)
        assertFalse(upd.viaMqtt!!)
        assertEquals(1730000500L, upd.lastHeard)
        assertEquals(94, upd.batteryLevel)
        assertEquals(4.18, upd.voltage!!, 1e-2)
        assertEquals(14.5, upd.channelUtilization!!, 1e-2)
        assertEquals(2.1, upd.airUtilTx!!, 1e-2)
        assertEquals(86400L, upd.uptimeSeconds)
    }

    @Test
    fun parseFromRadio_decodesMeshPacketsPositionTelemetryNeighborAndTraceroute() {
        val myNum = 0x00000100L
        val peerNumInt = 0x00000200
        val relayNumInt = 0x00000300

        // 1. POSITION_APP packet with rx_snr & rx_rssi
        val posBytes = Position.ADAPTER.encode(
            Position(latitude_i = 428864000, longitude_i = -788784000, altitude = 195)
        )
        val posFrame = FromRadio(
            packet = MeshPacket(
                from = peerNumInt,
                to = myNum.toInt(),
                decoded = Data(portnum = PortNum.POSITION_APP, payload = posBytes.toByteString()),
                rx_time = 1730001000,
                rx_snr = 8.5f,
                rx_rssi = -84,
                hop_start = 3,
                hop_limit = 3,
                via_mqtt = false
            )
        )
        val posEvent = MeshtasticBleParser.parseFromRadio(
            FromRadio.ADAPTER.encode(posFrame),
            myNodeNum = myNum
        ) as ParsedFromRadioEvent.TelemetryBatchEvent
        val posUpd = posEvent.updates.single()
        assertEquals("!00000200", posUpd.nodeId)
        assertEquals(42.8864, posUpd.latitude!!, 1e-5)
        assertEquals(-78.8784, posUpd.longitude!!, 1e-5)
        assertEquals(195, posUpd.altitude)
        assertEquals(8.5, posUpd.snr!!, 1e-3)
        assertEquals(-84, posUpd.rssi)
        assertEquals(0, posUpd.hopsAway)

        // 2. TELEMETRY_APP with DeviceMetrics + EnvironmentMetrics
        val telemetryBytes = Telemetry.ADAPTER.encode(
            Telemetry(
                device_metrics = ProtoDeviceMetrics(
                    battery_level = 82,
                    voltage = 4.04f,
                    channel_utilization = 9.2f,
                    air_util_tx = 1.4f
                ),
                environment_metrics = ProtoEnvironmentMetrics(
                    temperature = 18.6f,
                    relative_humidity = 61.5f,
                    barometric_pressure = 1014.2f
                )
            )
        )
        val telFrame = FromRadio(
            packet = MeshPacket(
                from = peerNumInt,
                to = myNum.toInt(),
                decoded = Data(portnum = PortNum.TELEMETRY_APP, payload = telemetryBytes.toByteString()),
                rx_time = 1730001100,
                rx_snr = 7.0f,
                rx_rssi = -89,
                hop_start = 3,
                hop_limit = 3
            )
        )
        val telEvent = MeshtasticBleParser.parseFromRadio(
            FromRadio.ADAPTER.encode(telFrame),
            myNodeNum = myNum
        ) as ParsedFromRadioEvent.TelemetryBatchEvent
        val telUpd = telEvent.updates.single()
        assertEquals(82, telUpd.batteryLevel)
        assertEquals(4.04, telUpd.voltage!!, 1e-2)
        assertEquals(18.6, telUpd.temperature!!, 1e-2)
        assertEquals(61.5, telUpd.relativeHumidity!!, 1e-2)
        assertEquals(1014.2, telUpd.barometricPressure!!, 1e-1)
        assertEquals(7.0, telUpd.snr!!, 1e-3)
        assertEquals(-89, telUpd.rssi)

        // 3. NEIGHBORINFO_APP
        val neighborBytes = NeighborInfo.ADAPTER.encode(
            NeighborInfo(
                node_id = myNum.toInt(),
                neighbors = listOf(
                    Neighbor(node_id = peerNumInt, snr = 9.25f, last_rx_time = 1730001200)
                )
            )
        )
        val neighborFrame = FromRadio(
            packet = MeshPacket(
                from = myNum.toInt(),
                decoded = Data(portnum = PortNum.NEIGHBORINFO_APP, payload = neighborBytes.toByteString()),
                rx_time = 1730001200
            )
        )
        val neighborEvent = MeshtasticBleParser.parseFromRadio(
            FromRadio.ADAPTER.encode(neighborFrame),
            myNodeNum = myNum
        ) as ParsedFromRadioEvent.TelemetryBatchEvent
        val neighborUpd = neighborEvent.updates.single()
        assertEquals(true, neighborUpd.isSelf)
        assertEquals(1, neighborUpd.neighborLinks!!.size)
        assertEquals("!00000200", neighborUpd.neighborLinks!!.first().neighborId)
        assertEquals(9.25, neighborUpd.neighborLinks!!.first().snrDb, 1e-3)

        // 4. ROUTING_APP traceroute hop SNRs (quarter-dB scaled)
        val routeBytes = Routing.ADAPTER.encode(
            Routing(
                route_reply = RouteDiscovery(
                    route = listOf(relayNumInt),
                    snr_towards = listOf(28, -12), // +7.0 dB, -3.0 dB
                    route_back = listOf(relayNumInt),
                    snr_back = listOf(16, 20) // +4.0 dB, +5.0 dB
                )
            )
        )
        val routeFrame = FromRadio(
            packet = MeshPacket(
                from = peerNumInt,
                to = myNum.toInt(),
                decoded = Data(portnum = PortNum.ROUTING_APP, payload = routeBytes.toByteString()),
                rx_time = 1730001300,
                rx_snr = 5.0f,
                rx_rssi = -94
            )
        )
        val routeEvent = MeshtasticBleParser.parseFromRadio(
            FromRadio.ADAPTER.encode(routeFrame),
            myNodeNum = myNum
        ) as ParsedFromRadioEvent.TelemetryBatchEvent
        val selfTraceUpdate = routeEvent.updates.firstOrNull { it.nodeNum == myNum }
        assertNotNull(selfTraceUpdate)
        assertEquals(5.0, selfTraceUpdate!!.neighborLinks!!.first { it.neighborNum == relayNumInt.toLong() }.snrDb, 1e-3)
    }

    @Test
    fun mergeBleUpdates_preservesCustomNodeStoreOverridesAndUpdatesLiveDiagnostics() {
        val myNum = 0x00000100L
        val peerNum = 0x00000200L

        val existingSelf = MeshNode(
            num = myNum,
            id = "!00000100",
            longName = "Buffalo Rooftop Base",
            shortName = "BUFB",
            hwModel = "HELTEC_V3",
            role = "CLIENT",
            latitude = 42.8864,
            longitude = -78.8784,
            altitude = 200,
            hopsAway = 0,
            viaMqtt = false,
            isFavorite = false,
            isSelf = true,
            snr = null,
            rssi = null,
            lastHeard = 1700000000L,
            deviceMetrics = null,
            environmentMetrics = null,
            rawJson = "{}"
        )
        val existingPeer = MeshNode(
            num = peerNum,
            id = "!00000200",
            longName = "Old Peer Name",
            shortName = "OLDP",
            hwModel = "RAK4631",
            role = "ROUTER",
            latitude = 42.9000,
            longitude = -78.8700,
            altitude = 210,
            hopsAway = 1,
            viaMqtt = false,
            isFavorite = true,
            isSelf = false,
            snr = 1.0,
            rssi = -108,
            lastHeard = 1700000000L,
            deviceMetrics = null,
            environmentMetrics = null,
            rawJson = "{}"
        )

        // User customized the peer's site name, AGL mast note, and color in CustomNodeStore
        val store = CustomNodeStoreData(
            overrides = mapOf(
                existingPeer.stableKey to NodeOverride(
                    targetKey = existingPeer.stableKey,
                    id = existingPeer.id,
                    num = existingPeer.num,
                    longName = "Custom North Tower (45m AGL)",
                    shortName = "NTWR",
                    hwModel = existingPeer.hwModel,
                    role = existingPeer.role,
                    latitude = existingPeer.latitude,
                    longitude = existingPeer.longitude,
                    altitude = existingPeer.altitude,
                    hopsAway = existingPeer.hopsAway,
                    viaMqtt = false,
                    isFavorite = true,
                    isSelf = false,
                    snr = existingPeer.snr,
                    rssi = existingPeer.rssi,
                    notes = "5.8 dBi Fiberglass @ 45m AGL",
                    customColorArgb = 0xFF22C55E.toInt(),
                    overrideLocation = false,
                    overrideTelemetry = false
                )
            )
        )

        val posBytes = Position.ADAPTER.encode(
            Position(latitude_i = 429150000, longitude_i = -788650000, altitude = 245)
        )
        val telBytes = Telemetry.ADAPTER.encode(
            Telemetry(
                device_metrics = ProtoDeviceMetrics(battery_level = 91, voltage = 4.12f),
                environment_metrics = ProtoEnvironmentMetrics(temperature = 14.5f, relative_humidity = 55.0f, barometric_pressure = 1018.4f)
            )
        )

        val posEvt = MeshtasticBleParser.parseFromRadio(
            FromRadio.ADAPTER.encode(
                FromRadio(
                    packet = MeshPacket(
                        from = peerNum.toInt(),
                        to = myNum.toInt(),
                        decoded = Data(portnum = PortNum.POSITION_APP, payload = posBytes.toByteString()),
                        rx_time = 1730005000,
                        rx_snr = 10.5f,
                        rx_rssi = -79,
                        hop_start = 3,
                        hop_limit = 3,
                        via_mqtt = false
                    )
                )
            ),
            myNodeNum = myNum
        ) as ParsedFromRadioEvent.TelemetryBatchEvent

        val telEvt = MeshtasticBleParser.parseFromRadio(
            FromRadio.ADAPTER.encode(
                FromRadio(
                    packet = MeshPacket(
                        from = peerNum.toInt(),
                        to = myNum.toInt(),
                        decoded = Data(portnum = PortNum.TELEMETRY_APP, payload = telBytes.toByteString()),
                        rx_time = 1730005060,
                        rx_snr = 10.5f,
                        rx_rssi = -79,
                        hop_start = 3,
                        hop_limit = 3,
                        via_mqtt = false
                    )
                )
            ),
            myNodeNum = myNum
        ) as ParsedFromRadioEvent.TelemetryBatchEvent

        val mergedBase = NodeInventoryState.mergeLiveTelemetryUpdates(
            listOf(existingSelf, existingPeer),
            posEvt.updates + telEvt.updates
        )
        val finalResult = CustomNodeStore.mergeNodes(mergedBase, store)
        val mergedPeer = finalResult.nodes.first { it.id == "!00000200" }

        // CustomNodeStore overrides preserved
        assertEquals("Custom North Tower (45m AGL)", mergedPeer.longName)
        assertEquals("NTWR", mergedPeer.shortName)
        assertEquals("5.8 dBi Fiberglass @ 45m AGL", mergedPeer.notes)
        assertEquals(0xFF22C55E.toInt(), mergedPeer.customColorArgb)

        // Live BLE telemetry & coordinates updated
        assertEquals(42.9150, mergedPeer.latitude!!, 1e-4)
        assertEquals(-78.8650, mergedPeer.longitude!!, 1e-4)
        assertEquals(245, mergedPeer.altitude)
        assertEquals(10.5, mergedPeer.snr!!, 1e-3)
        assertEquals(-79, mergedPeer.rssi)
        assertEquals(0, mergedPeer.hopsAway)
        assertEquals(91, mergedPeer.deviceMetrics?.batteryLevel)
        assertEquals(14.5, mergedPeer.environmentMetrics?.temperature!!, 1e-2)
        assertEquals(1018.4, mergedPeer.environmentMetrics?.barometricPressure!!, 1e-1)

        // Feeds directly into Phase 4 Direct RF links & observed SNR resolution
        val directLinks = NodeInventoryState.computeDirectRfLinks(
            allNodes = finalResult.nodes,
            selfNode = finalResult.selfNode,
            sortDescending = true
        )
        assertEquals(1, directLinks.size)
        assertEquals("!00000200", directLinks.first().node.id)
        assertEquals(10.5, LosAnalyzer.resolveObservedSnr(finalResult.selfNode!!, mergedPeer)!!, 1e-3)
    }

    @Test
    fun topCardStatusPill_showsBleShortNameAndSyncSheetBluetoothTabWorks() {
        var centerClicked = false
        var reconnectClicked = false
        val connectedRadioNode = MeshNode(
            num = 0x1A2B3C4DL,
            id = "!1a2b3c4d",
            longName = "Buffalo Rooftop",
            shortName = "BUF1",
            hwModel = "HELTEC_V3",
            role = "CLIENT",
            latitude = 42.8864,
            longitude = -78.8784,
            altitude = 200,
            hopsAway = 0,
            viaMqtt = false,
            isFavorite = false,
            isSelf = true,
            snr = null,
            rssi = null,
            lastHeard = 1730005000L,
            deviceMetrics = null,
            environmentMetrics = null,
            rawJson = "{}"
        )
        val bleState = BleSyncUiState(
            status = BleConnectionStatus.CONNECTED,
            connectedAddress = "AA:BB:CC:DD:EE:01",
            connectedDeviceName = "Meshtastic_3c4d",
            connectedShortName = "BUF1",
            myNodeNum = 0x1A2B3C4DL,
            nodesSyncedCount = 18,
            packetsReceivedCount = 42,
            lastPacketEpochSeconds = System.currentTimeMillis() / 1000L - 5L,
            discoveredDevices = listOf(
                DiscoveredMeshtasticDevice("AA:BB:CC:DD:EE:01", "Meshtastic_3c4d", -58)
            )
        )

        composeRule.setContent {
            LEMeshTheme {
                UnifiedHorizonTopCard(
                    expanded = false,
                    onToggleExpanded = {},
                    liveSyncStatus = LiveSyncStatus.OFFLINE,
                    liveSyncTransport = LiveSyncTransport.MQTT,
                    bleSyncState = bleState,
                    onOpenLiveSync = {}
                ) {}
            }
        }

        composeRule.onNodeWithTag("live_sync_toolbar_badge").assertIsDisplayed()
        composeRule.onNodeWithText("BLE: BUF1").assertIsDisplayed()

        // Verify Bluetooth tab inside LiveSyncBottomSheet
        composeRule.setContent {
            LEMeshTheme {
                LiveSyncBottomSheet(
                    uiState = LiveSyncUiState(),
                    onUpdateConfig = {},
                    onConnect = {},
                    onDisconnect = {},
                    onDismissError = {},
                    onDismiss = {},
                    bleState = bleState,
                    bleActions = BleSyncActions(
                        onCenterOnMyRadio = { centerClicked = true },
                        onReconnectSaved = { reconnectClicked = true }
                    ),
                    myRadioNode = connectedRadioNode,
                    initialTab = SyncSheetTab.BLUETOOTH
                )
            }
        }

        composeRule.onNodeWithTag("live_sync_transport_bluetooth").assertIsDisplayed()
        composeRule.onNodeWithTag("ble_sync_section").assertIsDisplayed()
        composeRule.onNodeWithTag("ble_telemetry_counters").assertIsDisplayed()
        composeRule.onNodeWithText("18").assertIsDisplayed()
        composeRule.onNodeWithText("42").assertIsDisplayed()
        composeRule.onNodeWithTag("ble_scan_button").assertIsDisplayed()
        composeRule.onNodeWithTag("ble_center_my_radio_button").assertIsDisplayed().performClick()
        assertTrue(centerClicked)

        // Verify Remembered Radio quick-connect card when offline after auto-connect timeout
        composeRule.setContent {
            LEMeshTheme {
                LiveSyncBottomSheet(
                    uiState = LiveSyncUiState(),
                    onUpdateConfig = {},
                    onConnect = {},
                    onDisconnect = {},
                    onDismissError = {},
                    onDismiss = {},
                    bleState = BleSyncUiState(
                        status = BleConnectionStatus.OFFLINE,
                        savedRadio = SavedBleRadio("AA:BB:CC:DD:EE:01", "Meshtastic_3c4d", "BUF1"),
                        autoConnectTimedOut = true
                    ),
                    bleActions = BleSyncActions(
                        onReconnectSaved = { reconnectClicked = true }
                    ),
                    myRadioNode = null,
                    initialTab = SyncSheetTab.BLUETOOTH
                )
            }
        }

        composeRule.onNodeWithTag("ble_saved_radio_card").assertIsDisplayed()
        composeRule.onNodeWithTag("ble_reconnect_saved_button").assertIsDisplayed()
        composeRule.onNodeWithTag("ble_forget_saved_button").assertIsDisplayed()
    }
}
