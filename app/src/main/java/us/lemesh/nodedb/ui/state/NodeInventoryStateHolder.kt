package us.lemesh.nodedb.ui.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import us.lemesh.nodedb.data.DeviceMetrics
import us.lemesh.nodedb.data.DirectRfLink
import us.lemesh.nodedb.data.LinkDiagnosticSummary
import us.lemesh.nodedb.data.LiveTelemetryUpdate
import us.lemesh.nodedb.data.LosAnalysisResult
import us.lemesh.nodedb.data.LosAnalyzer
import us.lemesh.nodedb.data.MeshGeoUtils
import us.lemesh.nodedb.data.MeshNode
import us.lemesh.nodedb.data.NodeDbParseResult
import us.lemesh.nodedb.ui.NodeEditorDraft
import java.util.Locale

data class NodeInventoryState(
    val hasLoadedFile: Boolean = false,
    val loadedFileName: String? = null,
    val allNodes: List<MeshNode> = emptyList(),
    val selfNode: MeshNode? = null,
    val totalCount: Int = 0,
    val plottedGpsCount: Int = 0,
    val rfCount: Int = 0,
    val mqttCount: Int = 0,
    val noGpsCount: Int = 0,
    val manualNodeCount: Int = 0,
    val editedNodeCount: Int = 0,
    val searchQuery: String = "",
    val showMqtt: Boolean = true,
    val showRfSightlines: Boolean = true,
    val favoritesOnly: Boolean = false,
    val selectedHwModels: Set<String> = emptySet(),
    val selectedRoles: Set<String> = emptySet(),
    val selectedNode: MeshNode? = null,
    val editingDraft: NodeEditorDraft? = null,
    val showNoGpsSheet: Boolean = false,
    val noGpsSearchQuery: String = "",
    val showDirectLinksSheet: Boolean = false,
    val directLinksSortDescending: Boolean = true
) {
    val availableHwModels: List<String>
        get() = allNodes
            .mapNotNull { it.hwModel?.trim()?.takeIf { model -> model.isNotEmpty() } }
            .distinct()
            .sorted()

    val availableRoles: List<String>
        get() = allNodes
            .mapNotNull { it.role?.trim()?.takeIf { role -> role.isNotEmpty() } }
            .distinct()
            .sorted()

    val filteredMapNodes: List<MeshNode>
        get() = filterNodesForMap(
            allNodes = allNodes,
            showMqtt = showMqtt,
            favoritesOnly = favoritesOnly,
            searchQuery = searchQuery,
            selectedHwModels = selectedHwModels,
            selectedRoles = selectedRoles
        )

    val filteredNoGpsNodes: List<MeshNode>
        get() = filterNodesWithoutGps(
            allNodes = allNodes,
            showMqtt = showMqtt,
            favoritesOnly = favoritesOnly,
            noGpsSearchQuery = noGpsSearchQuery,
            selectedHwModels = selectedHwModels,
            selectedRoles = selectedRoles
        )

    val directRfLinksWithGps: List<DirectRfLink>
        get() = computeDirectRfLinks(
            allNodes = allNodes,
            selfNode = selfNode,
            sortDescending = directLinksSortDescending
        )

    companion object {
        fun filterNodesForMap(
            allNodes: List<MeshNode>,
            showMqtt: Boolean,
            favoritesOnly: Boolean,
            searchQuery: String,
            selectedHwModels: Set<String> = emptySet(),
            selectedRoles: Set<String> = emptySet()
        ): List<MeshNode> {
            return allNodes.filter { node ->
                node.hasValidGps &&
                    (showMqtt || !node.viaMqtt) &&
                    (!favoritesOnly || node.isFavorite) &&
                    (selectedHwModels.isEmpty() || (node.hwModel?.trim() in selectedHwModels)) &&
                    (selectedRoles.isEmpty() || (node.role?.trim() in selectedRoles)) &&
                    node.matchesQuery(searchQuery)
            }
        }

        fun filterNodesWithoutGps(
            allNodes: List<MeshNode>,
            showMqtt: Boolean,
            favoritesOnly: Boolean,
            noGpsSearchQuery: String,
            selectedHwModels: Set<String> = emptySet(),
            selectedRoles: Set<String> = emptySet()
        ): List<MeshNode> {
            return allNodes.filter { node ->
                !node.hasValidGps &&
                    (showMqtt || !node.viaMqtt) &&
                    (!favoritesOnly || node.isFavorite) &&
                    (selectedHwModels.isEmpty() || (node.hwModel?.trim() in selectedHwModels)) &&
                    (selectedRoles.isEmpty() || (node.role?.trim() in selectedRoles)) &&
                    node.matchesQuery(noGpsSearchQuery)
            }
        }

        fun computeDirectRfLinks(
            allNodes: List<MeshNode>,
            selfNode: MeshNode?,
            sortDescending: Boolean,
            sightlineFresnelResults: Map<String, LosAnalysisResult> = emptyMap(),
            sightlineDiagnostics: Map<String, LinkDiagnosticSummary> = emptyMap()
        ): List<DirectRfLink> {
            val origin = selfNode?.takeIf { it.hasValidGps } ?: return emptyList()
            val lat1 = origin.latitude!!
            val lon1 = origin.longitude!!
            val links = allNodes
                .filter { node ->
                    !node.isSelf &&
                        !node.viaMqtt &&
                        node.hopsAway == 0 &&
                        node.hasValidGps
                }
                .map { node ->
                    val lat2 = node.latitude!!
                    val lon2 = node.longitude!!
                    val km = MeshGeoUtils.haversineKm(lat1, lon1, lat2, lon2)
                    val key = LosAnalyzer.nodeSightlineKey(node)
                    val predicted = sightlineFresnelResults[key]
                    val diagnostic = sightlineDiagnostics[key] ?: predicted?.let { pred ->
                        val observedSnr = LosAnalyzer.resolveObservedSnr(origin, node)
                        LosAnalyzer.evaluateObservedVsPredicted(
                            observedSnrDb = observedSnr,
                            observedRssiDbm = node.rssi,
                            predicted = pred
                        )
                    }
                    DirectRfLink(
                        node = node,
                        distanceKm = km,
                        distanceMiles = MeshGeoUtils.kmToMiles(km),
                        bearingDegrees = MeshGeoUtils.bearingDegrees(lat1, lon1, lat2, lon2),
                        diagnostic = diagnostic,
                        predictedLos = predicted
                    )
                }
            return if (sortDescending) {
                links.sortedByDescending { it.distanceKm }
            } else {
                links.sortedBy { it.distanceKm }
            }
        }

        /**
         * Non-destructively merges incoming live MQTT/JSON telemetry packets into the base
         * NodeDB node list while preserving untouched fields on existing nodes.
         * Callers should subsequently run `CustomNodeStore.mergeNodes(...)` so custom user notes,
         * colors, manual nodes, and overrides remain intact.
         */
        fun mergeLiveTelemetryUpdates(
            existingBaseNodes: List<MeshNode>,
            updates: List<LiveTelemetryUpdate>
        ): List<MeshNode> {
            if (updates.isEmpty()) return existingBaseNodes
            val mutable = existingBaseNodes.toMutableList()
            val indexByKey = HashMap<String, Int>(mutable.size * 2)
            val indexByNum = HashMap<Long, Int>(mutable.size * 2)
            mutable.forEachIndexed { idx, node ->
                indexByKey[node.stableKey] = idx
                node.num?.let { indexByNum[it] = idx }
            }

            for (upd in updates) {
                val key = upd.stableKey ?: continue
                val existingIdx = indexByKey[key] ?: upd.nodeNum?.let { indexByNum[it] }
                if (existingIdx != null) {
                    val old = mutable[existingIdx]
                    val hasDeviceMetricUpdate = upd.batteryLevel != null ||
                        upd.voltage != null ||
                        upd.channelUtilization != null ||
                        upd.airUtilTx != null ||
                        upd.uptimeSeconds != null
                    val mergedMetrics = if (hasDeviceMetricUpdate) {
                        DeviceMetrics(
                            batteryLevel = upd.batteryLevel ?: old.deviceMetrics?.batteryLevel,
                            voltage = upd.voltage ?: old.deviceMetrics?.voltage,
                            channelUtilization = upd.channelUtilization ?: old.deviceMetrics?.channelUtilization,
                            airUtilTx = upd.airUtilTx ?: old.deviceMetrics?.airUtilTx,
                            uptimeSeconds = upd.uptimeSeconds ?: old.deviceMetrics?.uptimeSeconds
                        )
                    } else {
                        old.deviceMetrics
                    }
                    val mergedNeighbors = if (!upd.neighborLinks.isNullOrEmpty()) {
                        val byKey = LinkedHashMap<String, us.lemesh.nodedb.data.NeighborLinkTelemetry>()
                        old.neighborLinks.forEach { n ->
                            val nk = n.neighborId.ifBlank { n.neighborNum?.toString().orEmpty() }.lowercase(Locale.US)
                            if (nk.isNotEmpty()) byKey[nk] = n
                        }
                        upd.neighborLinks.forEach { n ->
                            val nk = n.neighborId.ifBlank { n.neighborNum?.toString().orEmpty() }.lowercase(Locale.US)
                            if (nk.isNotEmpty()) byKey[nk] = n
                        }
                        byKey.values.toList()
                    } else {
                        old.neighborLinks
                    }

                    val updated = old.copy(
                        num = old.num ?: upd.nodeNum,
                        id = old.id.ifBlank { upd.nodeId.ifBlank { key } },
                        longName = upd.longName?.takeIf { it.isNotBlank() } ?: old.longName,
                        shortName = upd.shortName?.takeIf { it.isNotBlank() } ?: old.shortName,
                        hwModel = upd.hwModel?.takeIf { it.isNotBlank() } ?: old.hwModel,
                        role = upd.role?.takeIf { it.isNotBlank() } ?: old.role,
                        latitude = upd.latitude ?: old.latitude,
                        longitude = upd.longitude ?: old.longitude,
                        altitude = upd.altitude ?: old.altitude,
                        snr = upd.snr ?: old.snr,
                        rssi = upd.rssi ?: old.rssi,
                        hopsAway = upd.hopsAway ?: old.hopsAway,
                        viaMqtt = upd.viaMqtt ?: old.viaMqtt,
                        lastHeard = maxOf(old.lastHeard ?: 0L, upd.lastHeard ?: 0L).takeIf { it > 0L },
                        deviceMetrics = mergedMetrics,
                        neighborLinks = mergedNeighbors
                    )
                    mutable[existingIdx] = updated
                    indexByKey[updated.stableKey] = existingIdx
                    updated.num?.let { indexByNum[it] = existingIdx }
                } else {
                    val cleanId = upd.nodeId.ifBlank {
                        upd.nodeNum?.let { LiveTelemetryUpdate.formatNodeNumAsHex(it) } ?: key
                    }
                    val suffix = cleanId.removePrefix("!").takeLast(4).uppercase(Locale.US)
                    val metrics = if (upd.batteryLevel != null ||
                        upd.voltage != null ||
                        upd.channelUtilization != null ||
                        upd.airUtilTx != null ||
                        upd.uptimeSeconds != null
                    ) {
                        DeviceMetrics(
                            batteryLevel = upd.batteryLevel,
                            voltage = upd.voltage,
                            channelUtilization = upd.channelUtilization,
                            airUtilTx = upd.airUtilTx,
                            uptimeSeconds = upd.uptimeSeconds
                        )
                    } else {
                        null
                    }
                    val created = MeshNode(
                        num = upd.nodeNum,
                        id = cleanId,
                        longName = upd.longName?.takeIf { it.isNotBlank() } ?: "Node $suffix",
                        shortName = upd.shortName?.takeIf { it.isNotBlank() } ?: suffix,
                        hwModel = upd.hwModel,
                        role = upd.role,
                        latitude = upd.latitude,
                        longitude = upd.longitude,
                        altitude = upd.altitude,
                        hopsAway = upd.hopsAway,
                        viaMqtt = upd.viaMqtt ?: true,
                        isFavorite = false,
                        isSelf = false,
                        snr = upd.snr,
                        rssi = upd.rssi,
                        lastHeard = upd.lastHeard,
                        deviceMetrics = metrics,
                        environmentMetrics = null,
                        rawJson = upd.rawJson ?: "{}",
                        neighborLinks = upd.neighborLinks.orEmpty()
                    )
                    val newIdx = mutable.size
                    mutable.add(created)
                    indexByKey[created.stableKey] = newIdx
                    created.num?.let { indexByNum[it] = newIdx }
                }
            }
            return mutable
        }
    }
}

class NodeInventoryStateHolder {
    private val _state = MutableStateFlow(NodeInventoryState())
    val state: StateFlow<NodeInventoryState> = _state.asStateFlow()

    fun applyDataset(
        result: NodeDbParseResult,
        fileName: String?,
        hasImportedFile: Boolean,
        selectedNode: MeshNode? = null
    ) {
        _state.update {
            it.copy(
                hasLoadedFile = hasImportedFile,
                loadedFileName = fileName,
                allNodes = result.nodes,
                selfNode = result.selfNode,
                totalCount = result.totalCount,
                plottedGpsCount = result.plottedGpsCount,
                rfCount = result.rfCount,
                mqttCount = result.mqttCount,
                noGpsCount = result.noGpsCount,
                manualNodeCount = result.nodes.count { n -> n.isManual },
                editedNodeCount = result.nodes.count { n -> n.isEdited },
                selectedNode = selectedNode,
                showNoGpsSheet = false,
                showDirectLinksSheet = false
            )
        }
    }

    fun updateSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    fun toggleShowMqtt() {
        _state.update { it.copy(showMqtt = !it.showMqtt) }
    }

    fun toggleRfSightlines() {
        _state.update { it.copy(showRfSightlines = !it.showRfSightlines) }
    }

    fun toggleFavoritesOnly() {
        _state.update { it.copy(favoritesOnly = !it.favoritesOnly) }
    }

    fun toggleHwModelFilter(hwModel: String) {
        val clean = hwModel.trim()
        if (clean.isEmpty()) return
        _state.update { state ->
            val next = if (clean in state.selectedHwModels) {
                state.selectedHwModels - clean
            } else {
                state.selectedHwModels + clean
            }
            state.copy(selectedHwModels = next)
        }
    }

    fun toggleRoleFilter(role: String) {
        val clean = role.trim()
        if (clean.isEmpty()) return
        _state.update { state ->
            val next = if (clean in state.selectedRoles) {
                state.selectedRoles - clean
            } else {
                state.selectedRoles + clean
            }
            state.copy(selectedRoles = next)
        }
    }

    fun resetFilters() {
        _state.update {
            it.copy(
                searchQuery = "",
                showMqtt = true,
                showRfSightlines = true,
                favoritesOnly = false,
                selectedHwModels = emptySet(),
                selectedRoles = emptySet()
            )
        }
    }

    fun selectNode(node: MeshNode?) {
        _state.update { it.copy(selectedNode = node) }
    }

    fun setEditingDraft(draft: NodeEditorDraft?) {
        _state.update { it.copy(editingDraft = draft) }
    }

    fun setNoGpsSheetVisible(visible: Boolean) {
        _state.update {
            it.copy(
                showNoGpsSheet = visible,
                noGpsSearchQuery = if (visible) it.noGpsSearchQuery else ""
            )
        }
    }

    fun updateNoGpsSearchQuery(query: String) {
        _state.update { it.copy(noGpsSearchQuery = query) }
    }

    fun setDirectLinksSheetVisible(visible: Boolean) {
        _state.update { it.copy(showDirectLinksSheet = visible) }
    }

    fun toggleDirectLinksSort() {
        _state.update { it.copy(directLinksSortDescending = !it.directLinksSortDescending) }
    }

    fun clearAll() {
        _state.update { NodeInventoryState() }
    }
}
