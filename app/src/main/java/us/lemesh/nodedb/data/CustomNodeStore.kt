package us.lemesh.nodedb.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.roundToLong
import kotlin.random.Random

data class NodeOverride(
    val targetKey: String,
    val id: String,
    val num: Long?,
    val longName: String,
    val shortName: String,
    val hwModel: String?,
    val role: String?,
    val latitude: Double?,
    val longitude: Double?,
    val altitude: Int?,
    val hopsAway: Int?,
    val viaMqtt: Boolean,
    val isFavorite: Boolean,
    val isSelf: Boolean,
    val snr: Double?,
    val rssi: Int?,
    val notes: String?,
    val customColorArgb: Int?,
    val overrideLocation: Boolean = false,
    val overrideTelemetry: Boolean = false
) {
    fun applyTo(base: MeshNode): MeshNode {
        val merged = base.copy(
            id = id,
            num = num,
            longName = longName,
            shortName = shortName,
            hwModel = hwModel,
            role = role,
            latitude = if (overrideLocation) latitude else (base.latitude ?: latitude),
            longitude = if (overrideLocation) longitude else (base.longitude ?: longitude),
            altitude = if (overrideLocation) altitude else (base.altitude ?: altitude),
            hopsAway = if (overrideTelemetry) hopsAway else (base.hopsAway ?: hopsAway),
            viaMqtt = if (overrideTelemetry) viaMqtt else base.viaMqtt,
            isFavorite = isFavorite,
            isSelf = isSelf,
            snr = if (overrideTelemetry) snr else (base.snr ?: snr),
            rssi = if (overrideTelemetry) rssi else (base.rssi ?: rssi),
            notes = notes,
            customColorArgb = customColorArgb,
            isEdited = true
        )
        return merged.copy(rawJson = CustomNodeStore.serializeNodeToJsonObject(merged).toString(2))
    }

    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("targetKey", targetKey)
            put("id", id)
            putOpt("num", num)
            put("longName", longName)
            put("shortName", shortName)
            putOpt("hwModel", hwModel)
            putOpt("role", role)
            putOpt("latitude", latitude)
            putOpt("longitude", longitude)
            putOpt("altitude", altitude)
            putOpt("hopsAway", hopsAway)
            put("viaMqtt", viaMqtt)
            put("isFavorite", isFavorite)
            put("isSelf", isSelf)
            putOpt("snr", snr)
            putOpt("rssi", rssi)
            putOpt("notes", notes)
            putOpt("customColorHex", customColorArgb?.let { String.format(Locale.US, "#%08X", it) })
            put("overrideLocation", overrideLocation)
            put("overrideTelemetry", overrideTelemetry)
        }
    }

    companion object {
        fun fromNode(
            targetKey: String,
            node: MeshNode,
            overrideLocation: Boolean = false,
            overrideTelemetry: Boolean = false
        ): NodeOverride {
            return NodeOverride(
                targetKey = targetKey,
                id = node.id,
                num = node.num,
                longName = node.longName,
                shortName = node.shortName,
                hwModel = node.hwModel,
                role = node.role,
                latitude = node.latitude,
                longitude = node.longitude,
                altitude = node.altitude,
                hopsAway = node.hopsAway,
                viaMqtt = node.viaMqtt,
                isFavorite = node.isFavorite,
                isSelf = node.isSelf,
                snr = node.snr,
                rssi = node.rssi,
                notes = node.notes,
                customColorArgb = node.customColorArgb,
                overrideLocation = overrideLocation,
                overrideTelemetry = overrideTelemetry
            )
        }

        fun fromJsonObject(obj: JSONObject): NodeOverride? {
            val targetKey = obj.optString("targetKey", "").trim()
            if (targetKey.isEmpty()) return null
            val colorHex = obj.optString("customColorHex", "").takeIf { it.isNotBlank() && it != "null" }
            return NodeOverride(
                targetKey = targetKey,
                id = obj.optString("id", targetKey),
                num = if (obj.has("num") && !obj.isNull("num")) obj.optLong("num") else null,
                longName = obj.optString("longName", ""),
                shortName = obj.optString("shortName", ""),
                hwModel = obj.optString("hwModel", "").takeIf { it.isNotBlank() && it != "null" },
                role = obj.optString("role", "").takeIf { it.isNotBlank() && it != "null" },
                latitude = if (obj.has("latitude") && !obj.isNull("latitude")) obj.optDouble("latitude") else null,
                longitude = if (obj.has("longitude") && !obj.isNull("longitude")) obj.optDouble("longitude") else null,
                altitude = if (obj.has("altitude") && !obj.isNull("altitude")) obj.optInt("altitude") else null,
                hopsAway = if (obj.has("hopsAway") && !obj.isNull("hopsAway")) obj.optInt("hopsAway") else null,
                viaMqtt = obj.optBoolean("viaMqtt", false),
                isFavorite = obj.optBoolean("isFavorite", false),
                isSelf = obj.optBoolean("isSelf", false),
                snr = if (obj.has("snr") && !obj.isNull("snr")) obj.optDouble("snr") else null,
                rssi = if (obj.has("rssi") && !obj.isNull("rssi")) obj.optInt("rssi") else null,
                notes = obj.optString("notes", "").takeIf { it.isNotBlank() && it != "null" },
                customColorArgb = NodeColorPalette.parseColorHex(colorHex),
                overrideLocation = obj.optBoolean("overrideLocation", false),
                overrideTelemetry = obj.optBoolean("overrideTelemetry", false)
            )
        }
    }
}

data class CustomNodeStoreData(
    val manualNodes: List<MeshNode> = emptyList(),
    val overrides: Map<String, NodeOverride> = emptyMap()
)

data class MergedImportOutcome(
    val mergedBaseNodes: List<MeshNode>,
    val updatedStoreData: CustomNodeStoreData,
    val mergedParseResult: NodeDbParseResult,
    val updatedCount: Int,
    val addedCount: Int
)

object CustomNodeStore {

    fun loadFromFile(file: File): CustomNodeStoreData {
        if (!file.exists()) return CustomNodeStoreData()
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return CustomNodeStoreData()
        return fromJsonString(text)
    }

    fun saveToFile(file: File, data: CustomNodeStoreData) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(toJsonString(data), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
            tmp.delete()
        }
    }

    fun toJsonString(data: CustomNodeStoreData): String {
        val root = JSONObject()
        root.put("version", 1)

        val manualArr = JSONArray()
        data.manualNodes.forEach { node ->
            manualArr.put(serializeNodeToJsonObject(node.copy(isManual = true)))
        }
        root.put("manualNodes", manualArr)

        val overridesArr = JSONArray()
        data.overrides.values.forEach { ov ->
            overridesArr.put(ov.toJsonObject())
        }
        root.put("overrides", overridesArr)
        return root.toString(2)
    }

    fun fromJsonString(json: String): CustomNodeStoreData {
        return runCatching {
            val root = JSONObject(json)
            val manualArr = root.optJSONArray("manualNodes")
            val manualNodes = if (manualArr != null && manualArr.length() > 0) {
                val wrapped = JSONObject().put("nodes", manualArr).toString()
                NodeDbParser.parse(wrapped).getOrNull()?.nodes?.map { it.copy(isManual = true) }.orEmpty()
            } else {
                emptyList()
            }

            val overridesMap = linkedMapOf<String, NodeOverride>()
            val overridesArr = root.optJSONArray("overrides")
            if (overridesArr != null) {
                for (i in 0 until overridesArr.length()) {
                    val obj = overridesArr.optJSONObject(i) ?: continue
                    val ov = NodeOverride.fromJsonObject(obj) ?: continue
                    overridesMap[ov.targetKey] = ov
                }
            }

            CustomNodeStoreData(
                manualNodes = manualNodes,
                overrides = overridesMap
            )
        }.getOrElse { CustomNodeStoreData() }
    }

    fun mergeImportedNodeDb(
        existingBaseNodes: List<MeshNode>,
        incomingNodes: List<MeshNode>,
        storeData: CustomNodeStoreData
    ): MergedImportOutcome {
        val incomingRegular = incomingNodes.filterNot { it.isManual }
        val incomingManual = incomingNodes.filter { it.isManual }

        val incomingRegularByKey = LinkedHashMap<String, MeshNode>(incomingRegular.size)
        incomingRegular.forEach { node ->
            incomingRegularByKey[node.stableKey] = node
        }

        val existingKeys = LinkedHashSet<String>(existingBaseNodes.size)
        val mergedBase = ArrayList<MeshNode>(existingBaseNodes.size + incomingRegularByKey.size)
        var updatedCount = 0
        existingBaseNodes.forEach { existing ->
            val key = existing.stableKey
            existingKeys += key
            val incomingMatch = incomingRegularByKey[key]
            if (incomingMatch != null) {
                mergedBase += incomingMatch
                updatedCount++
            } else {
                mergedBase += existing
            }
        }

        var addedCount = 0
        incomingRegularByKey.forEach { (key, incoming) ->
            if (key !in existingKeys) {
                mergedBase += incoming
                addedCount++
            }
        }

        // Refresh non-overridden location & telemetry fields on matching overrides while
        // preserving custom site names, specs, notes, colors, and explicit overrides.
        val refreshedOverrides = LinkedHashMap<String, NodeOverride>(storeData.overrides.size)
        storeData.overrides.forEach { (key, ov) ->
            val incoming = incomingRegularByKey[key]
            refreshedOverrides[key] = if (incoming != null) {
                ov.copy(
                    latitude = if (ov.overrideLocation) ov.latitude else (incoming.latitude ?: ov.latitude),
                    longitude = if (ov.overrideLocation) ov.longitude else (incoming.longitude ?: ov.longitude),
                    altitude = if (ov.overrideLocation) ov.altitude else (incoming.altitude ?: ov.altitude),
                    hopsAway = if (ov.overrideTelemetry) ov.hopsAway else (incoming.hopsAway ?: ov.hopsAway),
                    viaMqtt = if (ov.overrideTelemetry) ov.viaMqtt else incoming.viaMqtt,
                    snr = if (ov.overrideTelemetry) ov.snr else (incoming.snr ?: ov.snr),
                    rssi = if (ov.overrideTelemetry) ov.rssi else (incoming.rssi ?: ov.rssi)
                )
            } else {
                ov
            }
        }

        val existingManualKeys = storeData.manualNodes.mapTo(HashSet()) { it.stableKey }
        val newManualNodes = incomingManual.filter { it.stableKey !in existingManualKeys }
        val mergedManualNodes = storeData.manualNodes + newManualNodes
        addedCount += newManualNodes.size

        val updatedStoreData = storeData.copy(
            manualNodes = mergedManualNodes,
            overrides = refreshedOverrides
        )
        val mergedResult = mergeNodes(mergedBase, updatedStoreData)
        return MergedImportOutcome(
            mergedBaseNodes = mergedBase,
            updatedStoreData = updatedStoreData,
            mergedParseResult = mergedResult,
            updatedCount = updatedCount,
            addedCount = addedCount
        )
    }

    fun mergeNodes(
        importedNodes: List<MeshNode>,
        storeData: CustomNodeStoreData
    ): NodeDbParseResult {
        val hasExplicitSelfOverride = storeData.manualNodes.any { it.isSelf } ||
            storeData.overrides.values.any { it.isSelf }

        val mergedImported = importedNodes.map { node ->
            val ov = storeData.overrides[node.stableKey]
            val applied = ov?.applyTo(node) ?: node
            if (hasExplicitSelfOverride && ov?.isSelf != true && applied.isSelf) {
                applied.copy(isSelf = false)
            } else {
                applied
            }
        }

        val existingKeys = mergedImported.mapTo(HashSet()) { it.stableKey }
        val filteredManual = storeData.manualNodes
            .filter { it.stableKey !in existingKeys }
            .map { node ->
                node.copy(
                    isManual = true,
                    rawJson = serializeNodeToJsonObject(node.copy(isManual = true)).toString(2)
                )
            }

        val combined = mergedImported + filteredManual
        val selfNode = combined.firstOrNull { it.isSelf }
        val plottedGpsCount = combined.count { it.hasValidGps }
        val mqttCount = combined.count { it.viaMqtt }
        val rfCount = combined.count { !it.viaMqtt }
        val noGpsCount = combined.count { !it.hasValidGps }

        return NodeDbParseResult(
            nodes = combined,
            selfNode = selfNode,
            totalCount = combined.size,
            plottedGpsCount = plottedGpsCount,
            rfCount = rfCount,
            mqttCount = mqttCount,
            noGpsCount = noGpsCount
        )
    }

    fun serializeNodeToJsonObject(node: MeshNode): JSONObject {
        val obj = JSONObject()
        node.num?.let { obj.put("num", it) }
        obj.put("id", node.id)

        val user = JSONObject().apply {
            put("id", node.id)
            put("longName", node.longName)
            put("shortName", node.shortName)
            node.hwModel?.let { put("hwModel", it) }
            node.role?.let { put("role", it) }
        }
        obj.put("user", user)

        if (node.latitude != null && node.longitude != null) {
            val pos = JSONObject().apply {
                put("latitude", node.latitude)
                put("longitude", node.longitude)
                put("latitudeI", (node.latitude * 1e7).roundToLong())
                put("longitudeI", (node.longitude * 1e7).roundToLong())
                node.altitude?.let { put("altitude", it) }
            }
            obj.put("position", pos)
        } else if (node.altitude != null) {
            obj.put("position", JSONObject().put("altitude", node.altitude))
        }

        node.hopsAway?.let { obj.put("hopsAway", it) }
        obj.put("viaMqtt", node.viaMqtt)
        obj.put("isFavorite", node.isFavorite)
        if (node.isSelf) obj.put("isSelf", true)
        node.snr?.let { obj.put("snr", it) }
        node.rssi?.let { obj.put("rssi", it) }
        node.lastHeard?.let { obj.put("lastHeard", it) }

        node.deviceMetrics?.let { dm ->
            val dmObj = JSONObject().apply {
                dm.batteryLevel?.let { put("batteryLevel", it) }
                dm.voltage?.let { put("voltage", it) }
                dm.channelUtilization?.let { put("channelUtilization", it) }
                dm.airUtilTx?.let { put("airUtilTx", it) }
                dm.uptimeSeconds?.let { put("uptimeSeconds", it) }
            }
            if (dmObj.length() > 0) obj.put("deviceMetrics", dmObj)
        }

        node.environmentMetrics?.let { em ->
            val emObj = JSONObject().apply {
                em.temperature?.let { put("temperature", it) }
                em.relativeHumidity?.let { put("relativeHumidity", it) }
                em.barometricPressure?.let { put("barometricPressure", it) }
            }
            if (emObj.length() > 0) obj.put("environmentMetrics", emObj)
        }

        node.customColorArgb?.let {
            obj.put("lemeshColor", String.format(Locale.US, "#%08X", it))
            obj.put("lemeshColorArgb", it)
        }
        if (node.isManual) obj.put("lemeshManual", true)
        if (node.isManual && node.maskLocationOnExport) obj.put("lemeshMaskLocation", true)
        if (node.isEdited) obj.put("lemeshEdited", true)
        node.notes?.takeIf { it.isNotBlank() }?.let { obj.put("lemeshNotes", it) }

        return obj
    }

    fun buildNodeDbBackupJson(
        nodes: List<MeshNode>,
        selfNode: MeshNode?,
        sourceFileName: String? = null
    ): String {
        val root = JSONObject()
        val resolvedSelf = selfNode ?: nodes.firstOrNull { it.isSelf }
        resolvedSelf?.let { self ->
            val myInfo = JSONObject().apply {
                self.num?.let { put("myNodeNum", it) }
                if (self.id.isNotBlank()) put("myNodeId", self.id)
            }
            root.put("myInfo", myInfo)
            self.num?.let { root.put("myNodeNum", it) }
            if (self.id.isNotBlank()) root.put("myNodeId", self.id)
        }
        sourceFileName?.let { root.put("lemeshSourceFile", it) }
        root.put("lemeshBackupVersion", 1)

        val nodesArray = JSONArray()
        nodes.forEach { node ->
            nodesArray.put(serializeNodeToJsonObject(node))
        }
        root.put("nodes", nodesArray)
        return root.toString(2)
    }

    fun generateUniqueManualId(existingNodes: List<MeshNode>): Pair<Long, String> {
        val existingIds = existingNodes.mapTo(HashSet()) { it.id.lowercase(Locale.US) }
        val existingNums = existingNodes.mapNotNullTo(HashSet()) { it.num }
        repeat(100) {
            val rawNum = (0xF0000000L + (Random.nextInt(0x0FFFFFFF).toLong() and 0x0FFFFFFFL))
            val hexId = String.format(Locale.US, "!%08x", rawNum and 0xFFFFFFFFL)
            if (hexId !in existingIds && rawNum !in existingNums) {
                return rawNum to hexId
            }
        }
        val fallbackNum = 0xF0000001L + existingNodes.size
        return fallbackNum to String.format(Locale.US, "!%08x", fallbackNum and 0xFFFFFFFFL)
    }
}
