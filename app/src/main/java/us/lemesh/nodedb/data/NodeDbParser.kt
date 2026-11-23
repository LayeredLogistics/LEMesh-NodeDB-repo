package us.lemesh.nodedb.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

object NodeDbParser {

    fun parse(jsonText: String): Result<NodeDbParseResult> {
        return runCatching {
            val trimmed = jsonText.trim()
            if (trimmed.isEmpty()) {
                throw IllegalArgumentException("JSON file is empty.")
            }

            val rootObject = if (trimmed.startsWith("[")) {
                JSONObject().put("nodes", JSONArray(trimmed))
            } else {
                JSONObject(trimmed)
            }

            val isGeoJsonCollection = !rootObject.has("nodes") &&
                rootObject.optString("type", "").equals("FeatureCollection", ignoreCase = true) &&
                rootObject.has("features")

            val myNodeNum = extractMyNodeNum(rootObject)
            val myNodeId = rootObject.optNullableString("myNodeId")
                ?: rootObject.optJSONObject("myInfo")?.optNullableString("myNodeId")

            val initialNodes = if (isGeoJsonCollection) {
                parseGeoJsonFeatureCollection(rootObject)
            } else {
                val rawEntries = extractNodeEntries(rootObject)
                if (rawEntries.isEmpty()) {
                    throw IllegalArgumentException("No nodes found in JSON \"nodes\" field.")
                }
                rawEntries.map { (keyHint, nodeObj) ->
                    parseSingleNode(keyHint, nodeObj)
                }
            }

            // Identify Self Node:
            // 1) myInfo.myNodeNum (or myNodeNum at root)
            // 2) fallback: hopsAway == 0 && snr == null && !viaMqtt
            val selfIndex = findSelfNodeIndex(initialNodes, myNodeNum, myNodeId)

            val resolvedNodes = initialNodes.mapIndexed { index, node ->
                if (index == selfIndex) node.copy(isSelf = true) else node
            }

            val selfNode = if (selfIndex >= 0) resolvedNodes[selfIndex] else null
            val plottedGpsCount = resolvedNodes.count { it.hasValidGps }
            val mqttCount = resolvedNodes.count { it.viaMqtt }
            val rfCount = resolvedNodes.count { !it.viaMqtt }
            val noGpsCount = resolvedNodes.count { !it.hasValidGps }

            NodeDbParseResult(
                nodes = resolvedNodes,
                selfNode = selfNode,
                totalCount = resolvedNodes.size,
                plottedGpsCount = plottedGpsCount,
                rfCount = rfCount,
                mqttCount = mqttCount,
                noGpsCount = noGpsCount
            )
        }
    }

    private fun parseGeoJsonFeatureCollection(root: JSONObject): List<MeshNode> {
        val features = root.optJSONArray("features")
            ?: throw IllegalArgumentException("GeoJSON FeatureCollection is missing \"features\".")
        val nodes = mutableListOf<MeshNode>()
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val geometry = feature.optJSONObject("geometry") ?: continue
            if (!geometry.optString("type", "").equals("Point", ignoreCase = true)) continue
            val coords = geometry.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue

            val props = feature.optJSONObject("properties") ?: continue
            val featureType = props.optNullableString("featureType")
            if (featureType != null && !featureType.equals("mesh_node", ignoreCase = true)) {
                continue
            }
            if (featureType == null &&
                props.optNullableString("id") == null &&
                props.optNullableString("longName") == null &&
                props.optNullableString("shortName") == null
            ) {
                continue
            }

            val lon = coords.optDouble(0, Double.NaN).takeIf { it.isFinite() }
            val lat = coords.optDouble(1, Double.NaN).takeIf { it.isFinite() }
            val altFromCoord = if (coords.length() >= 3 && !coords.isNull(2)) {
                coords.optDouble(2, Double.NaN).takeIf { it.isFinite() }?.toInt()
            } else {
                null
            }
            val validPair = if (isValidCoordinatePair(lat, lon)) lat to lon else (null to null)

            val rawId = props.optNullableString("id") ?: ""
            val num = props.optNullableLong("num") ?: parseHexIdToLong(rawId)
            val normalizedId = if (rawId.isNotBlank() && !rawId.startsWith("!") && rawId.toLongOrNull() != null) {
                formatNodeNumAsHex(rawId.toLong())
            } else if (rawId.isBlank() && num != null) {
                formatNodeNumAsHex(num)
            } else {
                rawId
            }

            val longName = props.optNullableString("longName") ?: ""
            val shortName = props.optNullableString("shortName") ?: ""
            val hwModel = props.optNullableString("hwModel")
            val role = props.optNullableString("role")
            val altitude = props.optNullableInt("altitudeM")
                ?: props.optNullableInt("altitude")
                ?: altFromCoord
            val hopsAway = props.optNullableInt("hopsAway")
            val viaMqtt = props.optBoolean("viaMqtt", false)
            val isFavorite = props.optBoolean("isFavorite", false)
            val isSelf = props.optBoolean("isSelf", false)
            val isManual = props.optBoolean("isManual", false)
            val maskLocationOnExport = isManual && props.optBoolean("locationMasked", false)
            val snr = props.optNullableDouble("snr")
            val rssi = props.optNullableInt("rssi")
            val notes = props.optNullableString("notes")
            val hasCustomColor = props.optBoolean("hasCustomColor", false)
            val colorHex = props.optNullableString("lemeshColor")
                ?: if (hasCustomColor) props.optNullableString("colorHex") else null
            val customColorArgb = NodeColorPalette.parseColorHex(colorHex)

            nodes += MeshNode(
                num = num,
                id = normalizedId,
                longName = longName,
                shortName = shortName,
                hwModel = hwModel,
                role = role,
                latitude = validPair.first,
                longitude = validPair.second,
                altitude = altitude,
                hopsAway = hopsAway,
                viaMqtt = viaMqtt,
                isFavorite = isFavorite,
                isSelf = isSelf,
                snr = snr,
                rssi = rssi,
                lastHeard = props.optNullableLong("lastHeard"),
                deviceMetrics = null,
                environmentMetrics = null,
                rawJson = feature.toString(2),
                customColorArgb = customColorArgb,
                isManual = isManual,
                notes = notes,
                isEdited = false,
                maskLocationOnExport = maskLocationOnExport
            )
        }
        if (nodes.isEmpty()) {
            throw IllegalArgumentException("No mesh_node features found in GeoJSON file.")
        }
        return nodes
    }

    private fun extractMyNodeNum(root: JSONObject): Long? {
        val myInfo = root.optJSONObject("myInfo") ?: root.optJSONObject("my_info")
        return myInfo?.optNullableLong("myNodeNum")
            ?: myInfo?.optNullableLong("my_node_num")
            ?: root.optNullableLong("myNodeNum")
            ?: root.optNullableLong("my_node_num")
    }

    private fun extractNodeEntries(root: JSONObject): List<Pair<String?, JSONObject>> {
        val nodesAny = root.opt("nodes")
            ?: throw IllegalArgumentException("Missing \"nodes\" array or object in Meshtastic JSON.")

        return when (nodesAny) {
            is JSONArray -> {
                buildList {
                    for (i in 0 until nodesAny.length()) {
                        val obj = nodesAny.optJSONObject(i) ?: continue
                        add(null to obj)
                    }
                }
            }
            is JSONObject -> {
                buildList {
                    val keys = nodesAny.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val obj = nodesAny.optJSONObject(key) ?: continue
                        add(key to obj)
                    }
                }
            }
            else -> throw IllegalArgumentException("\"nodes\" must be a JSON array or object.")
        }
    }

    private fun parseSingleNode(keyHint: String?, nodeObj: JSONObject): MeshNode {
        val userObj = nodeObj.optJSONObject("user")

        val keyNumeric = keyHint?.toLongOrNull()
        val keyHex = keyHint?.takeIf { it.startsWith("!") }

        val num = nodeObj.optNullableLong("num")
            ?: keyNumeric
            ?: parseHexIdToLong(userObj?.optNullableString("id") ?: nodeObj.optNullableString("id") ?: keyHex)

        val rawId = userObj?.optNullableString("id")
            ?: nodeObj.optNullableString("id")
            ?: keyHex
            ?: num?.let { formatNodeNumAsHex(it) }
            ?: keyHint
            ?: ""

        val normalizedId = if (rawId.isNotBlank() && !rawId.startsWith("!") && rawId.toLongOrNull() != null) {
            formatNodeNumAsHex(rawId.toLong())
        } else {
            rawId
        }

        val longName = userObj?.optNullableString("longName")
            ?: userObj?.optNullableString("long_name")
            ?: nodeObj.optNullableString("longName")
            ?: nodeObj.optNullableString("long_name")
            ?: ""

        val shortName = userObj?.optNullableString("shortName")
            ?: userObj?.optNullableString("short_name")
            ?: nodeObj.optNullableString("shortName")
            ?: nodeObj.optNullableString("short_name")
            ?: ""

        val hwModel = userObj?.optNullableString("hwModel")
            ?: userObj?.optNullableString("hw_model")
            ?: nodeObj.optNullableString("hwModel")

        val role = userObj?.optNullableString("role")
            ?: nodeObj.optNullableString("role")

        val posObj = nodeObj.optJSONObject("position")
        val (lat, lon) = extractCoordinates(posObj)
        val altitude = posObj?.optNullableInt("altitude")

        val hopsAway = nodeObj.optNullableInt("hopsAway")
            ?: nodeObj.optNullableInt("hops_away")

        val viaMqtt = if (nodeObj.has("viaMqtt")) {
            nodeObj.optBoolean("viaMqtt", false)
        } else {
            nodeObj.optBoolean("via_mqtt", false)
        }

        val isFavorite = if (nodeObj.has("isFavorite")) {
            nodeObj.optBoolean("isFavorite", false)
        } else {
            nodeObj.optBoolean("is_favorite", false)
        }

        val snr = nodeObj.optNullableDouble("snr")
        val rssi = nodeObj.optNullableInt("rssi")
        val lastHeard = nodeObj.optNullableLong("lastHeard")
            ?: nodeObj.optNullableLong("last_heard")

        val deviceMetricsObj = nodeObj.optJSONObject("deviceMetrics")
            ?: nodeObj.optJSONObject("device_metrics")
        val deviceMetrics = deviceMetricsObj?.let { dm ->
            val battery = dm.optNullableInt("batteryLevel") ?: dm.optNullableInt("battery_level")
            val voltage = dm.optNullableDouble("voltage")
            val chUtil = dm.optNullableDouble("channelUtilization") ?: dm.optNullableDouble("channel_utilization")
            val airUtil = dm.optNullableDouble("airUtilTx") ?: dm.optNullableDouble("air_util_tx")
            val uptime = dm.optNullableLong("uptimeSeconds") ?: dm.optNullableLong("uptime_seconds")
            if (battery != null || voltage != null || chUtil != null || airUtil != null || uptime != null) {
                DeviceMetrics(
                    batteryLevel = battery,
                    voltage = voltage,
                    channelUtilization = chUtil,
                    airUtilTx = airUtil,
                    uptimeSeconds = uptime
                )
            } else null
        }

        val envMetricsObj = nodeObj.optJSONObject("environmentMetrics")
            ?: nodeObj.optJSONObject("environment_metrics")
        val environmentMetrics = envMetricsObj?.let { em ->
            val temp = em.optNullableDouble("temperature")
            val humidity = em.optNullableDouble("relativeHumidity") ?: em.optNullableDouble("relative_humidity")
            val pressure = em.optNullableDouble("barometricPressure") ?: em.optNullableDouble("barometric_pressure")
            if (temp != null || humidity != null || pressure != null) {
                EnvironmentMetrics(
                    temperature = temp,
                    relativeHumidity = humidity,
                    barometricPressure = pressure
                )
            } else null
        }

        val customColorArgb = nodeObj.optNullableString("lemeshColor")?.let { NodeColorPalette.parseColorHex(it) }
            ?: nodeObj.optNullableInt("lemeshColorArgb")
        val isManual = nodeObj.optBoolean("lemeshManual", false)
        val maskLocationOnExport = isManual && nodeObj.optBoolean("lemeshMaskLocation", false)
        val isEdited = nodeObj.optBoolean("lemeshEdited", false)
        val notes = nodeObj.optNullableString("lemeshNotes")
        val explicitIsSelf = if (nodeObj.has("isSelf")) {
            nodeObj.optBoolean("isSelf", false)
        } else {
            nodeObj.optBoolean("is_self", false)
        }

        val neighborLinks = extractNeighborLinks(nodeObj)

        return MeshNode(
            num = num,
            id = normalizedId,
            longName = longName,
            shortName = shortName,
            hwModel = hwModel,
            role = role,
            latitude = lat,
            longitude = lon,
            altitude = altitude,
            hopsAway = hopsAway,
            viaMqtt = viaMqtt,
            isFavorite = isFavorite,
            isSelf = explicitIsSelf,
            snr = snr,
            rssi = rssi,
            lastHeard = lastHeard,
            deviceMetrics = deviceMetrics,
            environmentMetrics = environmentMetrics,
            rawJson = nodeObj.toString(2),
            customColorArgb = customColorArgb,
            isManual = isManual,
            notes = notes,
            isEdited = isEdited,
            maskLocationOnExport = maskLocationOnExport,
            neighborLinks = neighborLinks
        )
    }

    private fun extractNeighborLinks(nodeObj: JSONObject): List<NeighborLinkTelemetry> {
        val neighborContainer = nodeObj.optJSONObject("neighborInfo")
            ?: nodeObj.optJSONObject("neighbor_info")
            ?: nodeObj.optJSONObject("neighborinfo")
        val neighborsArray = neighborContainer?.optJSONArray("neighbors")
            ?: nodeObj.optJSONArray("neighbors")
            ?: return emptyList()
        return buildList {
            for (i in 0 until neighborsArray.length()) {
                val item = neighborsArray.optJSONObject(i) ?: continue
                val snr = item.optNullableDouble("snr") ?: continue
                val neighborNum = item.optNullableLong("nodeId")
                    ?: item.optNullableLong("node_id")
                    ?: item.optNullableLong("num")
                val rawNeighborId = item.optNullableString("id")
                    ?: item.optNullableString("nodeIdHex")
                    ?: item.optNullableString("node_id_hex")
                    ?: neighborNum?.let { formatNodeNumAsHex(it) }
                    ?: continue
                val normalizedNeighborId = if (!rawNeighborId.startsWith("!") && rawNeighborId.toLongOrNull() != null) {
                    formatNodeNumAsHex(rawNeighborId.toLong())
                } else {
                    rawNeighborId
                }
                val lastRx = item.optNullableLong("lastRxTime") ?: item.optNullableLong("last_rx_time")
                add(
                    NeighborLinkTelemetry(
                        neighborNum = neighborNum ?: parseHexIdToLong(normalizedNeighborId),
                        neighborId = normalizedNeighborId,
                        snrDb = snr,
                        lastRxTime = lastRx
                    )
                )
            }
        }
    }

    private fun extractCoordinates(posObj: JSONObject?): Pair<Double?, Double?> {
        if (posObj == null) return null to null

        val directLat = posObj.optNullableDouble("latitude")
        val directLon = posObj.optNullableDouble("longitude")
        if (isValidCoordinatePair(directLat, directLon)) {
            return directLat to directLon
        }

        val latI = posObj.optNullableLong("latitudeI") ?: posObj.optNullableLong("latitude_i")
        val lonI = posObj.optNullableLong("longitudeI") ?: posObj.optNullableLong("longitude_i")
        val scaledLat = latI?.let { it.toDouble() * 1e-7 }
        val scaledLon = lonI?.let { it.toDouble() * 1e-7 }
        if (isValidCoordinatePair(scaledLat, scaledLon)) {
            return scaledLat to scaledLon
        }

        return null to null
    }

    private fun isValidCoordinatePair(lat: Double?, lon: Double?): Boolean {
        if (lat == null || lon == null) return false
        if (lat.isNaN() || lon.isNaN() || lat.isInfinite() || lon.isInfinite()) return false
        if (abs(lat) < 1e-7 && abs(lon) < 1e-7) return false
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return false
        return true
    }

    private fun findSelfNodeIndex(
        nodes: List<MeshNode>,
        myNodeNum: Long?,
        myNodeId: String?
    ): Int {
        val explicitIdx = nodes.indexOfFirst { it.isSelf }
        if (explicitIdx >= 0) return explicitIdx
        if (myNodeNum != null) {
            val expectedHex = formatNodeNumAsHex(myNodeNum)
            val idx = nodes.indexOfFirst { node ->
                node.num == myNodeNum || node.id.equals(expectedHex, ignoreCase = true)
            }
            if (idx >= 0) return idx
        }
        if (!myNodeId.isNullOrBlank()) {
            val idx = nodes.indexOfFirst { it.id.equals(myNodeId, ignoreCase = true) }
            if (idx >= 0) return idx
        }
        return nodes.indexOfFirst { it.hopsAway == 0 && it.snr == null && !it.viaMqtt }
    }

    private fun formatNodeNumAsHex(num: Long): String {
        val unsigned32 = num and 0xFFFFFFFFL
        return "!%08x".format(unsigned32)
    }

    private fun parseHexIdToLong(id: String?): Long? {
        if (id.isNullOrBlank() || !id.startsWith("!")) return null
        return id.removePrefix("!").toLongOrNull(16)
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = optString(key, "")
        return value.takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun JSONObject.optNullableInt(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        val raw = opt(key) ?: return null
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            else -> null
        }
    }

    private fun JSONObject.optNullableLong(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        val raw = opt(key) ?: return null
        return when (raw) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull()
            else -> null
        }
    }

    private fun JSONObject.optNullableDouble(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val raw = opt(key) ?: return null
        val value = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull()
            else -> null
        }
        return value?.takeIf { !it.isNaN() && !it.isInfinite() }
    }
}
