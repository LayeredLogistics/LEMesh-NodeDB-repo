package us.lemesh.nodedb.data

import org.json.JSONObject
import org.meshtastic.proto.DeviceMetrics as ProtoDeviceMetrics
import org.meshtastic.proto.EnvironmentMetrics as ProtoEnvironmentMetrics
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.NeighborInfo
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.RouteDiscovery
import org.meshtastic.proto.Routing
import org.meshtastic.proto.Telemetry
import org.meshtastic.proto.ToRadio
import org.meshtastic.proto.User
import java.util.Locale
import kotlin.math.abs

sealed class ParsedFromRadioEvent {
    data class MyNodeInfoEvent(
        val myNodeNum: Long,
        val nodeId: String
    ) : ParsedFromRadioEvent()

    data class ConfigCompleteEvent(
        val configId: Int
    ) : ParsedFromRadioEvent()

    data class TelemetryBatchEvent(
        val updates: List<LiveTelemetryUpdate>,
        val isNodeInfoRecord: Boolean = false,
        val selfShortName: String? = null
    ) : ParsedFromRadioEvent()

    data object Ignored : ParsedFromRadioEvent()
}

/**
 * Pure Kotlin parser/encoder for Meshtastic PhoneAPI BLE protobuf frames
 * (`org.meshtastic:protobufs` Wire KMP models).
 */
object MeshtasticBleParser {

    private const val COORDINATE_SCALE = 1e-7
    private const val UNKNOWN_TRACEROUTE_SNR = Int.MIN_VALUE

    fun unsignedNodeNum(rawNum: Int): Long = rawNum.toLong() and 0xFFFFFFFFL

    fun formatNodeId(nodeNum: Long): String = LiveTelemetryUpdate.formatNodeNumAsHex(nodeNum)

    fun buildWantConfigPacket(configId: Int): ByteArray {
        val safeId = if (configId > 0) configId else 1
        return ToRadio.ADAPTER.encode(ToRadio(want_config_id = safeId))
    }

    fun parseFromRadio(
        bytes: ByteArray,
        myNodeNum: Long? = null,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L
    ): ParsedFromRadioEvent {
        if (bytes.isEmpty()) return ParsedFromRadioEvent.Ignored
        val frame = runCatching { FromRadio.ADAPTER.decode(bytes) }.getOrNull()
            ?: return ParsedFromRadioEvent.Ignored

        frame.my_info?.let { myInfo ->
            val num = unsignedNodeNum(myInfo.my_node_num)
            if (num > 0L) {
                return ParsedFromRadioEvent.MyNodeInfoEvent(
                    myNodeNum = num,
                    nodeId = formatNodeId(num)
                )
            }
        }

        val configCompleteId = frame.config_complete_id
        if (configCompleteId != null && configCompleteId != 0) {
            return ParsedFromRadioEvent.ConfigCompleteEvent(configCompleteId)
        }

        frame.node_info?.let { nodeInfo ->
            val update = parseNodeInfo(nodeInfo, myNodeNum, nowEpochSeconds)
                ?: return ParsedFromRadioEvent.Ignored
            val selfShort = if (update.isSelf == true) {
                update.shortName?.takeIf { it.isNotBlank() }
            } else {
                null
            }
            return ParsedFromRadioEvent.TelemetryBatchEvent(
                updates = listOf(update),
                isNodeInfoRecord = true,
                selfShortName = selfShort
            )
        }

        frame.packet?.let { packet ->
            val updates = parseMeshPacket(packet, myNodeNum, nowEpochSeconds)
            if (updates.isNotEmpty()) {
                val selfShort = updates.firstOrNull { it.isSelf == true }
                    ?.shortName
                    ?.takeIf { it.isNotBlank() }
                return ParsedFromRadioEvent.TelemetryBatchEvent(
                    updates = updates,
                    isNodeInfoRecord = false,
                    selfShortName = selfShort
                )
            }
        }

        return ParsedFromRadioEvent.Ignored
    }

    internal fun parseNodeInfo(
        nodeInfo: NodeInfo,
        myNodeNum: Long?,
        nowEpochSeconds: Long
    ): LiveTelemetryUpdate? {
        val nodeNum = unsignedNodeNum(nodeInfo.num)
        if (nodeNum == 0L) return null
        val user = nodeInfo.user
        val nodeId = user?.id?.trim()?.takeIf { it.isNotEmpty() } ?: formatNodeId(nodeNum)
        val isSelf = myNodeNum != null && myNodeNum == nodeNum

        val (lat, lon, alt) = extractPosition(nodeInfo.position)
        val lastHeardRaw = unsignedNodeNum(nodeInfo.last_heard)
        val lastHeard = when {
            lastHeardRaw > 0L -> lastHeardRaw
            isSelf -> nowEpochSeconds
            else -> null
        }

        val dm = nodeInfo.device_metrics
        val hops = when {
            isSelf -> 0
            nodeInfo.hops_away != null -> nodeInfo.hops_away
            else -> null
        }
        val snr = nodeInfo.snr.toDouble().takeIf { it.isFinite() && nodeInfo.snr != 0f }

        return LiveTelemetryUpdate(
            nodeNum = nodeNum,
            nodeId = nodeId,
            longName = user?.long_name?.trim()?.takeIf { it.isNotEmpty() },
            shortName = user?.short_name?.trim()?.takeIf { it.isNotEmpty() },
            hwModel = user?.hw_model?.name?.takeIf { it != "UNSET" },
            role = user?.role?.name,
            latitude = lat,
            longitude = lon,
            altitude = alt,
            snr = snr,
            rssi = null,
            hopsAway = hops,
            viaMqtt = if (isSelf) false else nodeInfo.via_mqtt,
            lastHeard = lastHeard,
            batteryLevel = dm?.battery_level,
            voltage = dm?.voltage?.toDouble()?.takeIf { it.isFinite() && it > 0.0 },
            channelUtilization = dm?.channel_utilization?.toDouble()?.takeIf { it.isFinite() },
            airUtilTx = dm?.air_util_tx?.toDouble()?.takeIf { it.isFinite() },
            uptimeSeconds = dm?.uptime_seconds?.let { unsignedNodeNum(it) }?.takeIf { it > 0L },
            isSelf = if (isSelf) true else null,
            rawJson = buildBleSummaryJson(
                source = "ble_node_info",
                nodeId = nodeId,
                nodeNum = nodeNum,
                snr = snr,
                rssi = null,
                hopsAway = hops,
                viaMqtt = if (isSelf) false else nodeInfo.via_mqtt
            )
        )
    }

    internal fun parseMeshPacket(
        packet: MeshPacket,
        myNodeNum: Long?,
        nowEpochSeconds: Long
    ): List<LiveTelemetryUpdate> {
        val fromNum = unsignedNodeNum(packet.from)
        if (fromNum == 0L) return emptyList()
        val fromId = formatNodeId(fromNum)
        val isSelf = myNodeNum != null && myNodeNum == fromNum

        val rxTimeRaw = unsignedNodeNum(packet.rx_time)
        val lastHeard = if (rxTimeRaw > 0L) rxTimeRaw else nowEpochSeconds

        val rxRssi = packet.rx_rssi.takeIf { it != 0 }
        val rxSnr = packet.rx_snr.toDouble().takeIf {
            it.isFinite() && (packet.rx_snr != 0f || rxRssi != null)
        }
        val hopsAway = when {
            isSelf -> 0
            packet.hop_start > 0 && packet.hop_start >= packet.hop_limit -> packet.hop_start - packet.hop_limit
            else -> null
        }
        val viaMqtt = if (isSelf) false else packet.via_mqtt

        val decoded = packet.decoded ?: return if (rxSnr != null || rxRssi != null) {
            listOf(
                LiveTelemetryUpdate(
                    nodeNum = fromNum,
                    nodeId = fromId,
                    snr = rxSnr,
                    rssi = rxRssi,
                    hopsAway = hopsAway,
                    viaMqtt = viaMqtt,
                    lastHeard = lastHeard,
                    isSelf = if (isSelf) true else null,
                    rawJson = buildBleSummaryJson(
                        source = "ble_packet",
                        nodeId = fromId,
                        nodeNum = fromNum,
                        snr = rxSnr,
                        rssi = rxRssi,
                        hopsAway = hopsAway,
                        viaMqtt = viaMqtt
                    )
                )
            )
        } else {
            emptyList()
        }

        val payloadBytes = decoded.payload.toByteArray()
        val baseSummary = buildBleSummaryJson(
            source = "ble_${decoded.portnum.name.lowercase(Locale.US)}",
            nodeId = fromId,
            nodeNum = fromNum,
            snr = rxSnr,
            rssi = rxRssi,
            hopsAway = hopsAway,
            viaMqtt = viaMqtt
        )

        return when (decoded.portnum) {
            PortNum.POSITION_APP -> {
                val pos = runCatching { Position.ADAPTER.decode(payloadBytes) }.getOrNull()
                val (lat, lon, alt) = extractPosition(pos)
                if (lat == null && alt == null && rxSnr == null && rxRssi == null) return emptyList()
                listOf(
                    LiveTelemetryUpdate(
                        nodeNum = fromNum,
                        nodeId = fromId,
                        latitude = lat,
                        longitude = lon,
                        altitude = alt,
                        snr = rxSnr,
                        rssi = rxRssi,
                        hopsAway = hopsAway,
                        viaMqtt = viaMqtt,
                        lastHeard = lastHeard,
                        isSelf = if (isSelf) true else null,
                        rawJson = baseSummary
                    )
                )
            }

            PortNum.NODEINFO_APP -> {
                val user = runCatching { User.ADAPTER.decode(payloadBytes) }.getOrNull()
                val resolvedId = user?.id?.trim()?.takeIf { it.isNotEmpty() } ?: fromId
                listOf(
                    LiveTelemetryUpdate(
                        nodeNum = fromNum,
                        nodeId = resolvedId,
                        longName = user?.long_name?.trim()?.takeIf { it.isNotEmpty() },
                        shortName = user?.short_name?.trim()?.takeIf { it.isNotEmpty() },
                        hwModel = user?.hw_model?.name?.takeIf { it != "UNSET" },
                        role = user?.role?.name,
                        snr = rxSnr,
                        rssi = rxRssi,
                        hopsAway = hopsAway,
                        viaMqtt = viaMqtt,
                        lastHeard = lastHeard,
                        isSelf = if (isSelf) true else null,
                        rawJson = baseSummary
                    )
                )
            }

            PortNum.TELEMETRY_APP -> {
                val telemetry = runCatching { Telemetry.ADAPTER.decode(payloadBytes) }.getOrNull()
                val dm: ProtoDeviceMetrics? = telemetry?.device_metrics
                val em: ProtoEnvironmentMetrics? = telemetry?.environment_metrics
                if (dm == null && em == null && rxSnr == null && rxRssi == null) return emptyList()
                listOf(
                    LiveTelemetryUpdate(
                        nodeNum = fromNum,
                        nodeId = fromId,
                        snr = rxSnr,
                        rssi = rxRssi,
                        hopsAway = hopsAway,
                        viaMqtt = viaMqtt,
                        lastHeard = lastHeard,
                        batteryLevel = dm?.battery_level,
                        voltage = dm?.voltage?.toDouble()?.takeIf { it.isFinite() && it > 0.0 },
                        channelUtilization = dm?.channel_utilization?.toDouble()?.takeIf { it.isFinite() },
                        airUtilTx = dm?.air_util_tx?.toDouble()?.takeIf { it.isFinite() },
                        uptimeSeconds = dm?.uptime_seconds?.let { unsignedNodeNum(it) }?.takeIf { it > 0L },
                        temperature = em?.temperature?.toDouble()?.takeIf { it.isFinite() },
                        relativeHumidity = em?.relative_humidity?.toDouble()?.takeIf { it.isFinite() },
                        barometricPressure = em?.barometric_pressure?.toDouble()?.takeIf { it.isFinite() && it > 0.0 },
                        isSelf = if (isSelf) true else null,
                        rawJson = baseSummary
                    )
                )
            }

            PortNum.NEIGHBORINFO_APP -> {
                val neighborInfo = runCatching { NeighborInfo.ADAPTER.decode(payloadBytes) }.getOrNull()
                val reporterNum = neighborInfo?.node_id?.let { unsignedNodeNum(it) }?.takeIf { it > 0L } ?: fromNum
                val reporterId = formatNodeId(reporterNum)
                val links = neighborInfo?.neighbors.orEmpty().mapNotNull { neighbor ->
                    val nNum = unsignedNodeNum(neighbor.node_id)
                    if (nNum == 0L) return@mapNotNull null
                    val nSnr = neighbor.snr.toDouble()
                    if (!nSnr.isFinite()) return@mapNotNull null
                    NeighborLinkTelemetry(
                        neighborNum = nNum,
                        neighborId = formatNodeId(nNum),
                        snrDb = nSnr,
                        lastRxTime = unsignedNodeNum(neighbor.last_rx_time).takeIf { it > 0L } ?: lastHeard
                    )
                }
                if (links.isEmpty() && rxSnr == null && rxRssi == null) return emptyList()
                listOf(
                    LiveTelemetryUpdate(
                        nodeNum = reporterNum,
                        nodeId = reporterId,
                        snr = if (reporterNum == fromNum) rxSnr else null,
                        rssi = if (reporterNum == fromNum) rxRssi else null,
                        hopsAway = if (reporterNum == fromNum) hopsAway else null,
                        viaMqtt = viaMqtt,
                        lastHeard = lastHeard,
                        isSelf = if (myNodeNum != null && myNodeNum == reporterNum) true else null,
                        neighborLinks = links.takeIf { it.isNotEmpty() },
                        rawJson = baseSummary
                    )
                )
            }

            PortNum.TRACEROUTE_APP, PortNum.ROUTING_APP -> {
                val routeDiscovery = decodeRouteDiscovery(decoded.portnum, payloadBytes)
                val hopUpdates = if (routeDiscovery != null) {
                    buildTracerouteHopUpdates(
                        packetFromNum = fromNum,
                        packetToNum = unsignedNodeNum(packet.to),
                        myNodeNum = myNodeNum,
                        routeDiscovery = routeDiscovery,
                        lastHeard = lastHeard,
                        viaMqtt = viaMqtt
                    )
                } else {
                    emptyList()
                }
                val senderSignalUpdate = if (rxSnr != null || rxRssi != null) {
                    LiveTelemetryUpdate(
                        nodeNum = fromNum,
                        nodeId = fromId,
                        snr = rxSnr,
                        rssi = rxRssi,
                        hopsAway = hopsAway,
                        viaMqtt = viaMqtt,
                        lastHeard = lastHeard,
                        isSelf = if (isSelf) true else null,
                        rawJson = baseSummary
                    )
                } else {
                    null
                }
                buildList {
                    senderSignalUpdate?.let(::add)
                    addAll(hopUpdates)
                }
            }

            else -> {
                if (rxSnr == null && rxRssi == null) return emptyList()
                listOf(
                    LiveTelemetryUpdate(
                        nodeNum = fromNum,
                        nodeId = fromId,
                        snr = rxSnr,
                        rssi = rxRssi,
                        hopsAway = hopsAway,
                        viaMqtt = viaMqtt,
                        lastHeard = lastHeard,
                        isSelf = if (isSelf) true else null,
                        rawJson = baseSummary
                    )
                )
            }
        }
    }

    private fun decodeRouteDiscovery(portNum: PortNum, payloadBytes: ByteArray): RouteDiscovery? {
        if (payloadBytes.isEmpty()) return null
        if (portNum == PortNum.TRACEROUTE_APP) {
            return runCatching { RouteDiscovery.ADAPTER.decode(payloadBytes) }.getOrNull()
        }
        val routing = runCatching { Routing.ADAPTER.decode(payloadBytes) }.getOrNull()
        val nested = routing?.route_reply ?: routing?.route_request
        if (nested != null) return nested
        return runCatching { RouteDiscovery.ADAPTER.decode(payloadBytes) }.getOrNull()
            ?.takeIf {
                it.route.isNotEmpty() || it.snr_towards.isNotEmpty() ||
                    it.route_back.isNotEmpty() || it.snr_back.isNotEmpty()
            }
    }

    private fun buildTracerouteHopUpdates(
        packetFromNum: Long,
        packetToNum: Long,
        myNodeNum: Long?,
        routeDiscovery: RouteDiscovery,
        lastHeard: Long,
        viaMqtt: Boolean
    ): List<LiveTelemetryUpdate> {
        val linksByObserver = LinkedHashMap<Long, MutableList<NeighborLinkTelemetry>>()

        fun recordHop(observerNum: Long, heardNum: Long, rawSnrQuarterDb: Int) {
            if (observerNum == 0L || heardNum == 0L || observerNum == heardNum) return
            if (rawSnrQuarterDb == UNKNOWN_TRACEROUTE_SNR || abs(rawSnrQuarterDb) > 240) return
            val snrDb = rawSnrQuarterDb / 4.0
            linksByObserver.getOrPut(observerNum) { mutableListOf() }.add(
                NeighborLinkTelemetry(
                    neighborNum = heardNum,
                    neighborId = formatNodeId(heardNum),
                    snrDb = snrDb,
                    lastRxTime = lastHeard
                )
            )
        }

        val requesterNum = packetToNum.takeIf { it != 0L && it != 0xFFFFFFFFL } ?: myNodeNum ?: 0L
        val responderNum = packetFromNum

        // Forward path: requester -> route[0] -> ... -> responder
        val forwardChain = buildList {
            if (requesterNum != 0L) add(requesterNum)
            routeDiscovery.route.forEach { raw ->
                val num = unsignedNodeNum(raw)
                if (num != 0L && num != 0xFFFFFFFFL) add(num)
            }
            if (responderNum != 0L) add(responderNum)
        }
        if (forwardChain.size >= 2) {
            val pairCount = minOf(forwardChain.size - 1, routeDiscovery.snr_towards.size)
            for (i in 0 until pairCount) {
                recordHop(
                    observerNum = forwardChain[i + 1],
                    heardNum = forwardChain[i],
                    rawSnrQuarterDb = routeDiscovery.snr_towards[i]
                )
            }
        }

        // Return path: responder -> route_back[0] -> ... -> requester
        val returnChain = buildList {
            if (responderNum != 0L) add(responderNum)
            routeDiscovery.route_back.forEach { raw ->
                val num = unsignedNodeNum(raw)
                if (num != 0L && num != 0xFFFFFFFFL) add(num)
            }
            if (requesterNum != 0L) add(requesterNum)
        }
        if (returnChain.size >= 2) {
            val pairCount = minOf(returnChain.size - 1, routeDiscovery.snr_back.size)
            for (i in 0 until pairCount) {
                recordHop(
                    observerNum = returnChain[i + 1],
                    heardNum = returnChain[i],
                    rawSnrQuarterDb = routeDiscovery.snr_back[i]
                )
            }
        }

        return linksByObserver.map { (observerNum, links) ->
            LiveTelemetryUpdate(
                nodeNum = observerNum,
                nodeId = formatNodeId(observerNum),
                viaMqtt = if (myNodeNum != null && observerNum == myNodeNum) false else viaMqtt,
                lastHeard = lastHeard,
                isSelf = if (myNodeNum != null && observerNum == myNodeNum) true else null,
                neighborLinks = links,
                rawJson = buildBleSummaryJson(
                    source = "ble_traceroute",
                    nodeId = formatNodeId(observerNum),
                    nodeNum = observerNum,
                    snr = null,
                    rssi = null,
                    hopsAway = null,
                    viaMqtt = viaMqtt
                )
            )
        }
    }

    private fun extractPosition(pos: Position?): Triple<Double?, Double?, Int?> {
        if (pos == null) return Triple(null, null, null)
        val latI = pos.latitude_i
        val lonI = pos.longitude_i
        var lat: Double? = null
        var lon: Double? = null
        if (latI != null && lonI != null && (latI != 0 || lonI != 0)) {
            val candLat = latI.toDouble() * COORDINATE_SCALE
            val candLon = lonI.toDouble() * COORDINATE_SCALE
            if (candLat in -85.0..85.0 && candLon in -180.0..180.0 &&
                (abs(candLat) > 1e-6 || abs(candLon) > 1e-6)
            ) {
                lat = candLat
                lon = candLon
            }
        }
        val alt = pos.altitude?.takeIf { it != 0 || lat != null }
        return Triple(lat, lon, alt)
    }

    private fun buildBleSummaryJson(
        source: String,
        nodeId: String,
        nodeNum: Long,
        snr: Double?,
        rssi: Int?,
        hopsAway: Int?,
        viaMqtt: Boolean
    ): String {
        return JSONObject().apply {
            put("source", source)
            put("id", nodeId)
            put("num", nodeNum)
            if (snr != null) put("snr", snr)
            if (rssi != null) put("rssi", rssi)
            if (hopsAway != null) put("hopsAway", hopsAway)
            put("viaMqtt", viaMqtt)
        }.toString(2)
    }
}
