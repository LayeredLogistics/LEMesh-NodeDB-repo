package us.lemesh.nodedb.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.UUID
import javax.net.ssl.SSLSocketFactory
import kotlin.math.abs

enum class LiveSyncTransport(val label: String) {
    MQTT("MQTT Broker"),
    LOCAL_JSON("Local JSON Feed")
}

enum class LiveSyncStatus(val label: String) {
    OFFLINE("Offline"),
    RECONNECTING("Reconnecting"),
    CONNECTED("Connected")
}

data class LiveSyncConfig(
    val transport: LiveSyncTransport = LiveSyncTransport.MQTT,
    val mqttHost: String = "mqtt.meshtastic.org",
    val mqttPort: Int = 1883,
    val mqttUseTls: Boolean = false,
    val mqttUsername: String = "",
    val mqttPassword: String = "",
    val mqttTopicFilter: String = "msh/US/2/json/#",
    val localJsonUrl: String = "",
    val pollIntervalSeconds: Int = 15
) {
    /**
     * Produces a safe log/UI summary guaranteed never to contain credentials.
     */
    fun toRedactedEndpointSummary(): String = when (transport) {
        LiveSyncTransport.MQTT -> {
            val scheme = if (mqttUseTls) "mqtts" else "mqtt"
            val host = mqttHost.trim().ifBlank { "unconfigured" }
            "$scheme://$host:$mqttPort (${mqttTopicFilter.trim().ifBlank { "msh/#" }})"
        }
        LiveSyncTransport.LOCAL_JSON -> {
            localJsonUrl.trim().substringBefore("?").ifBlank { "Local JSON URL unset" }
        }
    }
}

data class LiveTelemetryUpdate(
    val nodeNum: Long? = null,
    val nodeId: String = "",
    val longName: String? = null,
    val shortName: String? = null,
    val hwModel: String? = null,
    val role: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Int? = null,
    val snr: Double? = null,
    val rssi: Int? = null,
    val hopsAway: Int? = null,
    val viaMqtt: Boolean? = null,
    val lastHeard: Long? = null,
    val batteryLevel: Int? = null,
    val voltage: Double? = null,
    val channelUtilization: Double? = null,
    val airUtilTx: Double? = null,
    val uptimeSeconds: Long? = null,
    val neighborLinks: List<NeighborLinkTelemetry>? = null,
    val rawJson: String? = null
) {
    val stableKey: String?
        get() {
            val cleanId = nodeId.trim().lowercase(java.util.Locale.US)
            if (cleanId.isNotEmpty()) return cleanId
            if (nodeNum != null) return formatNodeNumAsHex(nodeNum)
            return null
        }

    companion object {
        fun formatNodeNumAsHex(num: Long): String {
            val unsigned32 = num and 0xFFFFFFFFL
            return "!%08x".format(unsigned32)
        }
    }
}

data class LiveSyncUiState(
    val status: LiveSyncStatus = LiveSyncStatus.OFFLINE,
    val config: LiveSyncConfig = LiveSyncConfig(),
    val packetsReceived: Int = 0,
    val nodesUpdatedCount: Int = 0,
    val lastPacketEpochSeconds: Long? = null,
    val lastError: String? = null
)

/**
 * Pure Kotlin MQTT v3.1.1 wire-format codec (CONNECT, CONNACK, SUBSCRIBE, PUBLISH, PINGREQ, DISCONNECT)
 * avoiding heavy legacy third-party MQTT SDK dependencies while staying 100% deterministic and unit-testable.
 */
object MqttV311WireCodec {
    private const val PACKET_CONNECT = 0x10
    private const val PACKET_CONNACK = 0x20
    private const val PACKET_PUBLISH = 0x30
    private const val PACKET_SUBSCRIBE = 0x82
    private const val PACKET_PINGREQ = 0xC0
    private const val PACKET_PINGRESP = 0xD0
    private const val PACKET_DISCONNECT = 0xE0

    data class DecodedPublish(
        val topic: String,
        val payload: ByteArray
    )

    fun encodeConnectPacket(
        clientId: String,
        username: String = "",
        password: String = "",
        keepAliveSeconds: Int = 45
    ): ByteArray {
        val body = ByteArrayOutputStream()
        // Protocol Name "MQTT" + Level 4 (3.1.1)
        writeMqttString(body, "MQTT")
        body.write(0x04)

        val hasUser = username.isNotEmpty()
        val hasPass = hasUser && password.isNotEmpty()
        var connectFlags = 0x02 // Clean Session
        if (hasUser) connectFlags = connectFlags or 0x80
        if (hasPass) connectFlags = connectFlags or 0x40
        body.write(connectFlags)

        val clampedKeepAlive = keepAliveSeconds.coerceIn(10, 600)
        body.write((clampedKeepAlive ushr 8) and 0xFF)
        body.write(clampedKeepAlive and 0xFF)

        writeMqttString(body, clientId.take(23).ifBlank { "lemesh-client" })
        if (hasUser) writeMqttString(body, username)
        if (hasPass) writeMqttString(body, password)

        return framePacket(PACKET_CONNECT, body.toByteArray())
    }

    fun encodeSubscribePacket(packetId: Int, topicFilter: String): ByteArray {
        val body = ByteArrayOutputStream()
        body.write((packetId ushr 8) and 0xFF)
        body.write(packetId and 0xFF)
        writeMqttString(body, topicFilter.ifBlank { "msh/#" })
        body.write(0x00) // QoS 0
        return framePacket(PACKET_SUBSCRIBE, body.toByteArray())
    }

    fun encodePingReqPacket(): ByteArray = byteArrayOf(PACKET_PINGREQ.toByte(), 0x00)

    fun encodeDisconnectPacket(): ByteArray = byteArrayOf(PACKET_DISCONNECT.toByte(), 0x00)

    fun encodePublishPacketForTest(topic: String, payload: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        writeMqttString(body, topic)
        body.write(payload)
        return framePacket(PACKET_PUBLISH, body.toByteArray())
    }

    fun decodePublishBody(flags: Int, body: ByteArray): DecodedPublish? {
        if (body.size < 2) return null
        val topicLen = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        var offset = 2
        if (offset + topicLen > body.size) return null
        val topic = String(body, offset, topicLen, Charsets.UTF_8)
        offset += topicLen
        val qos = (flags ushr 1) and 0x03
        if (qos > 0) {
            if (offset + 2 > body.size) return null
            offset += 2 // skip Packet Identifier
        }
        val payload = if (offset < body.size) body.copyOfRange(offset, body.size) else ByteArray(0)
        return DecodedPublish(topic = topic, payload = payload)
    }

    fun readNextPacket(input: DataInputStream): Pair<Int, ByteArray> {
        val header = input.readUnsignedByte()
        var multiplier = 1
        var remainingLength = 0
        var digit: Int
        var bytesRead = 0
        do {
            digit = input.readUnsignedByte()
            remainingLength += (digit and 0x7F) * multiplier
            multiplier *= 128
            bytesRead++
            if (bytesRead > 4) throw IllegalStateException("Malformed MQTT remaining length")
        } while ((digit and 0x80) != 0)

        if (remainingLength > 1_048_576) {
            throw IllegalStateException("MQTT packet exceeds safety size limit")
        }
        val body = ByteArray(remainingLength)
        if (remainingLength > 0) {
            input.readFully(body)
        }
        return header to body
    }

    fun isConnAckAccepted(header: Int, body: ByteArray): Boolean =
        (header and 0xF0) == PACKET_CONNACK && body.size >= 2 && body[1].toInt() == 0

    fun connAckReturnCode(body: ByteArray): Int =
        if (body.size >= 2) body[1].toInt() and 0xFF else -1

    fun isPublish(header: Int): Boolean = (header and 0xF0) == PACKET_PUBLISH
    fun isPingResp(header: Int): Boolean = (header and 0xF0) == PACKET_PINGRESP

    private fun framePacket(headerByte: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(body.size + 5)
        out.write(headerByte)
        var x = body.size
        do {
            var encoded = x % 128
            x /= 128
            if (x > 0) encoded = encoded or 0x80
            out.write(encoded)
        } while (x > 0)
        out.write(body)
        return out.toByteArray()
    }

    private fun writeMqttString(out: OutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.write((bytes.size ushr 8) and 0xFF)
        out.write(bytes.size and 0xFF)
        out.write(bytes)
    }
}

/**
 * Parses incoming live telemetry JSON payloads from Meshtastic MQTT JSON topics, MeshMonitor,
 * or local NodeDB bridge endpoints into normalized [LiveTelemetryUpdate] batches.
 */
object LiveTelemetryPayloadParser {

    fun parsePayload(jsonText: String): List<LiveTelemetryUpdate> {
        val trimmed = jsonText.trim()
        if (trimmed.isEmpty()) return emptyList()

        return runCatching {
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        parseObject(obj)?.let(::add)
                    }
                }
            } else {
                val root = JSONObject(trimmed)
                // Case 1: Full NodeDB snapshot / local bridge endpoint
                if (root.has("nodes")) {
                    val parsed = NodeDbParser.parse(trimmed).getOrNull()
                    if (parsed != null) {
                        return@runCatching parsed.nodes.map { node ->
                            LiveTelemetryUpdate(
                                nodeNum = node.num,
                                nodeId = node.id,
                                longName = node.longName.takeIf { it.isNotBlank() },
                                shortName = node.shortName.takeIf { it.isNotBlank() },
                                hwModel = node.hwModel,
                                role = node.role,
                                latitude = node.latitude,
                                longitude = node.longitude,
                                altitude = node.altitude,
                                snr = node.snr,
                                rssi = node.rssi,
                                hopsAway = node.hopsAway,
                                viaMqtt = node.viaMqtt,
                                lastHeard = node.lastHeard,
                                batteryLevel = node.deviceMetrics?.batteryLevel,
                                voltage = node.deviceMetrics?.voltage,
                                channelUtilization = node.deviceMetrics?.channelUtilization,
                                airUtilTx = node.deviceMetrics?.airUtilTx,
                                uptimeSeconds = node.deviceMetrics?.uptimeSeconds,
                                neighborLinks = node.neighborLinks.takeIf { it.isNotEmpty() },
                                rawJson = node.rawJson
                            )
                        }
                    }
                }
                // Case 2: Meshtastic MQTT JSON packet (position, nodeinfo, telemetry, neighborinfo)
                parseObject(root)?.let { listOf(it) } ?: emptyList()
            }
        }.getOrDefault(emptyList())
    }

    private fun parseObject(obj: JSONObject): LiveTelemetryUpdate? {
        val fromNum = obj.optNullableLong("from")
            ?: obj.optNullableLong("num")
            ?: obj.optNullableLong("nodeNum")
            ?: obj.optNullableLong("node_num")
        val payloadObj = obj.optJSONObject("payload")
        val rawId = obj.optNullableString("sender")
            ?: obj.optNullableString("id")
            ?: obj.optNullableString("nodeId")
            ?: payloadObj?.optNullableString("id")
            ?: fromNum?.let { LiveTelemetryUpdate.formatNodeNumAsHex(it) }
            ?: return null

        val normalizedId = if (!rawId.startsWith("!") && rawId.toLongOrNull() != null) {
            LiveTelemetryUpdate.formatNodeNumAsHex(rawId.toLong())
        } else {
            rawId
        }
        val resolvedNum = fromNum ?: normalizedId.removePrefix("!").toLongOrNull(16)

        val packetType = obj.optNullableString("type")?.lowercase(java.util.Locale.US).orEmpty()
        val timestamp = obj.optNullableLong("timestamp")
            ?: obj.optNullableLong("rxTime")
            ?: obj.optNullableLong("lastHeard")
            ?: (System.currentTimeMillis() / 1000L)

        val snr = obj.optNullableDouble("rxSnr") ?: obj.optNullableDouble("snr")
        val rssi = obj.optNullableInt("rxRssi") ?: obj.optNullableInt("rssi")
        val hopsStart = obj.optNullableInt("hopStart") ?: obj.optNullableInt("hop_start")
        val hopsLimit = obj.optNullableInt("hopLimit") ?: obj.optNullableInt("hop_limit")
        val hopsAway = obj.optNullableInt("hopsAway")
            ?: obj.optNullableInt("hops_away")
            ?: if (hopsStart != null && hopsLimit != null && hopsStart >= hopsLimit) hopsStart - hopsLimit else null

        var longName: String? = null
        var shortName: String? = null
        var hwModel: String? = null
        var role: String? = null
        var lat: Double? = null
        var lon: Double? = null
        var alt: Int? = null
        var battery: Int? = null
        var voltage: Double? = null
        var chUtil: Double? = null
        var airUtil: Double? = null
        var uptime: Long? = null
        var neighbors: List<NeighborLinkTelemetry>? = null

        val source = payloadObj ?: obj

        // Position fields
        val directLat = source.optNullableDouble("latitude")
        val directLon = source.optNullableDouble("longitude")
        val latI = source.optNullableLong("latitude_i") ?: source.optNullableLong("latitudeI")
        val lonI = source.optNullableLong("longitude_i") ?: source.optNullableLong("longitudeI")
        val candLat = directLat ?: latI?.let { it.toDouble() * 1e-7 }
        val candLon = directLon ?: lonI?.let { it.toDouble() * 1e-7 }
        if (candLat != null && candLon != null &&
            candLat in -85.0..85.0 && candLon in -180.0..180.0 &&
            (abs(candLat) > 1e-6 || abs(candLon) > 1e-6)
        ) {
            lat = candLat
            lon = candLon
        }
        alt = source.optNullableInt("altitude")

        // NodeInfo fields
        if (packetType == "nodeinfo" || source.has("longname") || source.has("longName") || source.has("shortname") || source.has("shortName")) {
            longName = source.optNullableString("longname") ?: source.optNullableString("longName")
            shortName = source.optNullableString("shortname") ?: source.optNullableString("shortName")
            hwModel = source.optNullableString("hardware") ?: source.optNullableString("hwModel") ?: source.optNullableString("hw_model")
            role = source.optNullableString("role")
        }

        // Device telemetry fields
        val dm = source.optJSONObject("deviceMetrics") ?: source.optJSONObject("device_metrics") ?: source
        battery = dm.optNullableInt("battery_level") ?: dm.optNullableInt("batteryLevel")
        voltage = dm.optNullableDouble("voltage")
        chUtil = dm.optNullableDouble("channel_utilization") ?: dm.optNullableDouble("channelUtilization")
        airUtil = dm.optNullableDouble("air_util_tx") ?: dm.optNullableDouble("airUtilTx")
        uptime = dm.optNullableLong("uptime_seconds") ?: dm.optNullableLong("uptimeSeconds")

        // NeighborInfo fields
        val neighborsArr = source.optJSONArray("neighbors")
        if (neighborsArr != null && neighborsArr.length() > 0) {
            neighbors = buildList {
                for (i in 0 until neighborsArr.length()) {
                    val item = neighborsArr.optJSONObject(i) ?: continue
                    val nSnr = item.optNullableDouble("snr") ?: continue
                    val nNum = item.optNullableLong("node_id") ?: item.optNullableLong("nodeId") ?: item.optNullableLong("num")
                    val nIdRaw = item.optNullableString("id") ?: nNum?.let { LiveTelemetryUpdate.formatNodeNumAsHex(it) } ?: continue
                    val nId = if (!nIdRaw.startsWith("!") && nIdRaw.toLongOrNull() != null) {
                        LiveTelemetryUpdate.formatNodeNumAsHex(nIdRaw.toLong())
                    } else nIdRaw
                    add(
                        NeighborLinkTelemetry(
                            neighborNum = nNum ?: nId.removePrefix("!").toLongOrNull(16),
                            neighborId = nId,
                            snrDb = nSnr,
                            lastRxTime = item.optNullableLong("last_rx_time") ?: item.optNullableLong("lastRxTime")
                        )
                    )
                }
            }.takeIf { it.isNotEmpty() }
        }

        val hasAnyPayload = longName != null || shortName != null || lat != null ||
            snr != null || rssi != null || battery != null || voltage != null ||
            chUtil != null || airUtil != null || !neighbors.isNullOrEmpty()
        if (!hasAnyPayload) return null

        return LiveTelemetryUpdate(
            nodeNum = resolvedNum,
            nodeId = normalizedId,
            longName = longName,
            shortName = shortName,
            hwModel = hwModel,
            role = role,
            latitude = lat,
            longitude = lon,
            altitude = alt,
            snr = snr,
            rssi = rssi,
            hopsAway = hopsAway,
            viaMqtt = if (obj.has("viaMqtt")) obj.optBoolean("viaMqtt", true) else true,
            lastHeard = timestamp,
            batteryLevel = battery,
            voltage = voltage,
            channelUtilization = chUtil,
            airUtilTx = airUtil,
            uptimeSeconds = uptime,
            neighborLinks = neighbors,
            rawJson = obj.toString(2)
        )
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = optString(key, "")
        return value.takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun JSONObject.optNullableInt(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return when (val raw = opt(key)) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            else -> null
        }
    }

    private fun JSONObject.optNullableLong(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return when (val raw = opt(key)) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull()
            else -> null
        }
    }

    private fun JSONObject.optNullableDouble(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val v = when (val raw = opt(key)) {
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull()
            else -> null
        }
        return v?.takeIf { it.isFinite() }
    }
}

/**
 * Optional Live MQTT & Local JSON Endpoint Sync repository.
 * Streams live position, nodeinfo, battery/voltage/channel utilization, and neighbor SNR updates
 * with automatic reconnect backoff while keeping credentials strictly private.
 */
class LiveMeshSyncRepository(
    context: Context? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val prefs = context?.applicationContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        LiveSyncUiState(
            status = LiveSyncStatus.OFFLINE,
            config = loadSavedConfig()
        )
    )
    val uiState: StateFlow<LiveSyncUiState> = _uiState.asStateFlow()

    private val _incomingUpdates = MutableSharedFlow<List<LiveTelemetryUpdate>>(extraBufferCapacity = 32)
    val incomingUpdates: SharedFlow<List<LiveTelemetryUpdate>> = _incomingUpdates.asSharedFlow()

    @Volatile
    private var activeSocket: Socket? = null
    private var syncJob: Job? = null

    fun updateConfig(config: LiveSyncConfig) {
        val sanitized = config.copy(
            mqttHost = config.mqttHost.trim(),
            mqttPort = config.mqttPort.coerceIn(1, 65535),
            mqttTopicFilter = config.mqttTopicFilter.trim().ifBlank { "msh/US/2/json/#" },
            localJsonUrl = config.localJsonUrl.trim(),
            pollIntervalSeconds = config.pollIntervalSeconds.coerceIn(5, 300)
        )
        persistConfig(sanitized)
        _uiState.update { it.copy(config = sanitized, lastError = null) }
    }

    fun connect(configOverride: LiveSyncConfig? = null) {
        val activeConfig = configOverride?.also { updateConfig(it) } ?: _uiState.value.config
        disconnectInternal(keepError = false)

        val validationError = validateConfig(activeConfig)
        if (validationError != null) {
            _uiState.update {
                it.copy(
                    status = LiveSyncStatus.OFFLINE,
                    lastError = validationError
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                status = LiveSyncStatus.RECONNECTING,
                lastError = null
            )
        }

        syncJob = scope.launch {
            when (activeConfig.transport) {
                LiveSyncTransport.MQTT -> runMqttLoop(activeConfig)
                LiveSyncTransport.LOCAL_JSON -> runLocalJsonPollLoop(activeConfig)
            }
        }
    }

    fun disconnect() {
        disconnectInternal(keepError = false)
        _uiState.update { it.copy(status = LiveSyncStatus.OFFLINE, lastError = null) }
    }

    fun toggleConnection() {
        if (_uiState.value.status == LiveSyncStatus.OFFLINE) {
            connect()
        } else {
            disconnect()
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(lastError = null) }
    }

    private fun disconnectInternal(keepError: Boolean) {
        syncJob?.cancel()
        syncJob = null
        val sock = activeSocket
        activeSocket = null
        if (sock != null) {
            runCatching {
                sock.getOutputStream()?.write(MqttV311WireCodec.encodeDisconnectPacket())
                sock.close()
            }
        }
        if (!keepError) {
            _uiState.update { it.copy(lastError = null) }
        }
    }

    private fun validateConfig(config: LiveSyncConfig): String? = when (config.transport) {
        LiveSyncTransport.MQTT -> {
            if (config.mqttHost.isBlank()) "Enter an MQTT broker host (e.g. mqtt.meshtastic.org)."
            else null
        }
        LiveSyncTransport.LOCAL_JSON -> {
            val url = config.localJsonUrl
            if (url.isBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
                "Enter a valid http:// or https:// local JSON endpoint URL."
            } else null
        }
    }

    private suspend fun runMqttLoop(config: LiveSyncConfig) {
        var backoffMs = 2_000L
        while (scope.isActive) {
            try {
                _uiState.update { it.copy(status = LiveSyncStatus.RECONNECTING) }
                val socket = openSocket(config.mqttHost, config.mqttPort, config.mqttUseTls)
                activeSocket = socket
                socket.soTimeout = 50_000

                val out = socket.getOutputStream()
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                val clientId = "lemesh_${UUID.randomUUID().toString().take(8)}"

                out.write(
                    MqttV311WireCodec.encodeConnectPacket(
                        clientId = clientId,
                        username = config.mqttUsername,
                        password = config.mqttPassword,
                        keepAliveSeconds = 45
                    )
                )
                out.flush()

                val (ackHeader, ackBody) = MqttV311WireCodec.readNextPacket(input)
                if (!MqttV311WireCodec.isConnAckAccepted(ackHeader, ackBody)) {
                    val code = MqttV311WireCodec.connAckReturnCode(ackBody)
                    throw IllegalStateException("Broker rejected connection (CONNACK code $code).")
                }

                out.write(MqttV311WireCodec.encodeSubscribePacket(1, config.mqttTopicFilter))
                out.flush()

                backoffMs = 2_000L
                _uiState.update { it.copy(status = LiveSyncStatus.CONNECTED, lastError = null) }

                var lastPingMs = System.currentTimeMillis()
                while (scope.isActive && !socket.isClosed) {
                    val now = System.currentTimeMillis()
                    if (now - lastPingMs >= 30_000L) {
                        out.write(MqttV311WireCodec.encodePingReqPacket())
                        out.flush()
                        lastPingMs = now
                    }
                    val (header, body) = try {
                        MqttV311WireCodec.readNextPacket(input)
                    } catch (_: java.net.SocketTimeoutException) {
                        out.write(MqttV311WireCodec.encodePingReqPacket())
                        out.flush()
                        lastPingMs = System.currentTimeMillis()
                        continue
                    }

                    if (MqttV311WireCodec.isPublish(header)) {
                        val decoded = MqttV311WireCodec.decodePublishBody(header and 0x0F, body)
                        if (decoded != null && decoded.payload.isNotEmpty()) {
                            val text = runCatching { String(decoded.payload, Charsets.UTF_8) }.getOrNull()
                            if (!text.isNullOrBlank() && (text.trimStart().startsWith("{") || text.trimStart().startsWith("["))) {
                                val updates = LiveTelemetryPayloadParser.parsePayload(text)
                                if (updates.isNotEmpty()) {
                                    emitUpdates(updates)
                                }
                            }
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: EOFException) {
                _uiState.update {
                    it.copy(
                        status = LiveSyncStatus.RECONNECTING,
                        lastError = "Broker closed connection. Retrying…"
                    )
                }
            } catch (t: Throwable) {
                val safeMsg = redactErrorMessage(t.message ?: t.javaClass.simpleName, config)
                _uiState.update {
                    it.copy(
                        status = LiveSyncStatus.RECONNECTING,
                        lastError = safeMsg
                    )
                }
            } finally {
                runCatching { activeSocket?.close() }
                activeSocket = null
            }

            delay(backoffMs)
            backoffMs = (backoffMs * 2L).coerceAtMost(30_000L)
        }
    }

    private suspend fun runLocalJsonPollLoop(config: LiveSyncConfig) {
        var backoffMs = 3_000L
        while (scope.isActive) {
            try {
                val jsonBody = withContext(Dispatchers.IO) { fetchHttpJson(config.localJsonUrl) }
                val updates = LiveTelemetryPayloadParser.parsePayload(jsonBody)
                backoffMs = 3_000L
                _uiState.update {
                    it.copy(
                        status = LiveSyncStatus.CONNECTED,
                        lastError = null
                    )
                }
                if (updates.isNotEmpty()) {
                    emitUpdates(updates)
                }
                delay(config.pollIntervalSeconds * 1000L)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val safeMsg = redactErrorMessage(t.message ?: "Local JSON endpoint unreachable.", config)
                _uiState.update {
                    it.copy(
                        status = LiveSyncStatus.RECONNECTING,
                        lastError = safeMsg
                    )
                }
                delay(backoffMs)
                backoffMs = (backoffMs * 2L).coerceAtMost(30_000L)
            }
        }
    }

    private suspend fun emitUpdates(updates: List<LiveTelemetryUpdate>) {
        _incomingUpdates.emit(updates)
        _uiState.update { state ->
            state.copy(
                packetsReceived = state.packetsReceived + 1,
                nodesUpdatedCount = state.nodesUpdatedCount + updates.size,
                lastPacketEpochSeconds = System.currentTimeMillis() / 1000L
            )
        }
    }

    private fun openSocket(host: String, port: Int, useTls: Boolean): Socket {
        return if (useTls) {
            val sslSocket = SSLSocketFactory.getDefault().createSocket()
            sslSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            sslSocket
        } else {
            Socket().apply {
                connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            }
        }
    }

    private fun fetchHttpJson(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "LEMesh-NodeDB/Android")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code from local JSON endpoint")
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun persistConfig(config: LiveSyncConfig) {
        prefs?.edit()?.apply {
            putString(KEY_TRANSPORT, config.transport.name)
            putString(KEY_MQTT_HOST, config.mqttHost)
            putInt(KEY_MQTT_PORT, config.mqttPort)
            putBoolean(KEY_MQTT_TLS, config.mqttUseTls)
            putString(KEY_MQTT_USER, config.mqttUsername)
            putString(KEY_MQTT_PASS, config.mqttPassword)
            putString(KEY_MQTT_TOPIC, config.mqttTopicFilter)
            putString(KEY_LOCAL_URL, config.localJsonUrl)
            putInt(KEY_POLL_SEC, config.pollIntervalSeconds)
            apply()
        }
    }

    private fun loadSavedConfig(): LiveSyncConfig {
        val p = prefs ?: return LiveSyncConfig()
        val transport = runCatching {
            LiveSyncTransport.valueOf(p.getString(KEY_TRANSPORT, LiveSyncTransport.MQTT.name) ?: LiveSyncTransport.MQTT.name)
        }.getOrDefault(LiveSyncTransport.MQTT)
        return LiveSyncConfig(
            transport = transport,
            mqttHost = p.getString(KEY_MQTT_HOST, "mqtt.meshtastic.org") ?: "mqtt.meshtastic.org",
            mqttPort = p.getInt(KEY_MQTT_PORT, 1883),
            mqttUseTls = p.getBoolean(KEY_MQTT_TLS, false),
            mqttUsername = p.getString(KEY_MQTT_USER, "") ?: "",
            mqttPassword = p.getString(KEY_MQTT_PASS, "") ?: "",
            mqttTopicFilter = p.getString(KEY_MQTT_TOPIC, "msh/US/2/json/#") ?: "msh/US/2/json/#",
            localJsonUrl = p.getString(KEY_LOCAL_URL, "") ?: "",
            pollIntervalSeconds = p.getInt(KEY_POLL_SEC, 15)
        )
    }

    companion object {
        private const val PREFS_NAME = "lemesh_live_sync_prefs"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val KEY_TRANSPORT = "transport"
        private const val KEY_MQTT_HOST = "mqtt_host"
        private const val KEY_MQTT_PORT = "mqtt_port"
        private const val KEY_MQTT_TLS = "mqtt_tls"
        private const val KEY_MQTT_USER = "mqtt_user"
        private const val KEY_MQTT_PASS = "mqtt_pass"
        private const val KEY_MQTT_TOPIC = "mqtt_topic"
        private const val KEY_LOCAL_URL = "local_url"
        private const val KEY_POLL_SEC = "poll_sec"

        internal fun redactErrorMessage(raw: String, config: LiveSyncConfig): String {
            var sanitized = raw
            if (config.mqttPassword.isNotBlank()) {
                sanitized = sanitized.replace(config.mqttPassword, "••••")
            }
            if (config.mqttUsername.isNotBlank()) {
                sanitized = sanitized.replace(config.mqttUsername, "[user]")
            }
            return sanitized.take(180)
        }
    }
}
