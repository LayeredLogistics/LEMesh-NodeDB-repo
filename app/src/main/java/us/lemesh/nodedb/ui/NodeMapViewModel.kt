package us.lemesh.nodedb.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import us.lemesh.nodedb.data.AggregateCoverageEngine
import us.lemesh.nodedb.data.AggregateCoverageParams
import us.lemesh.nodedb.data.AggregateCoverageResult
import us.lemesh.nodedb.data.CustomNodeStore
import us.lemesh.nodedb.data.CustomNodeStoreData
import us.lemesh.nodedb.data.DeviceLocationResolver
import us.lemesh.nodedb.data.DirectRfLink
import us.lemesh.nodedb.data.ElevationRepository
import us.lemesh.nodedb.data.FadeMarginPreset
import us.lemesh.nodedb.data.LinkDiagnosticSummary
import us.lemesh.nodedb.data.LiveMeshSyncRepository
import us.lemesh.nodedb.data.LiveTelemetryUpdate
import us.lemesh.nodedb.data.LosAnalysisResult
import us.lemesh.nodedb.data.LosAnalyzer
import us.lemesh.nodedb.data.LosElevationSource
import us.lemesh.nodedb.data.LosEndpoint
import us.lemesh.nodedb.data.MeshGeoUtils
import us.lemesh.nodedb.data.MeshNode
import us.lemesh.nodedb.data.MeshtasticBleRepository
import us.lemesh.nodedb.data.NodeColorPalette
import us.lemesh.nodedb.data.NodeDbParseResult
import us.lemesh.nodedb.data.NodeDbParser
import us.lemesh.nodedb.data.NodeOverride
import us.lemesh.nodedb.data.OfflineDemPackManager
import us.lemesh.nodedb.data.OfflineTileMissingException
import us.lemesh.nodedb.data.RelayBridgeEngine
import us.lemesh.nodedb.data.RfEnvironmentStateHolder
import us.lemesh.nodedb.data.TerrariumDemRepository
import us.lemesh.nodedb.data.TerrariumTileGrid
import us.lemesh.nodedb.ui.state.MapViewportStateHolder
import us.lemesh.nodedb.ui.state.NodeInventoryState
import us.lemesh.nodedb.ui.state.NodeInventoryStateHolder
import us.lemesh.nodedb.ui.state.OverlayContinuityMode
import us.lemesh.nodedb.ui.state.RfWorkspaceCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.Locale
import kotlin.math.abs

enum class MapBaseLayer(
    val displayName: String,
    val cartoStyleSlug: String? = null
) {
    DARK_MATTER("CARTO Dark Matter", "dark_all"),
    DARK_NO_LABELS("Dark (No Labels)", "dark_nolabels"),
    POSITRON("CARTO Positron", "light_all"),
    POSITRON_NO_LABELS("Positron (No Labels)", "light_nolabels"),
    VOYAGER("CARTO Voyager", "voyager"),
    VOYAGER_NO_LABELS("Voyager (No Labels)", "voyager_nolabels"),
    OSM_STANDARD("OSM Standard", null),
    OPEN_TOPO("OpenTopoMap", null);

    val requiresCartoKey: Boolean
        get() = cartoStyleSlug != null
}

data class NodeEditorDraft(
    val originalKey: String?,
    val isCreatingNew: Boolean,
    val isManual: Boolean,
    val isEdited: Boolean,
    val id: String,
    val num: Long?,
    val longName: String,
    val shortName: String,
    val latitudeText: String,
    val longitudeText: String,
    val altitudeText: String,
    val hwModelText: String,
    val roleText: String,
    val hopsAwayText: String,
    val viaMqtt: Boolean,
    val isFavorite: Boolean,
    val isSelf: Boolean,
    val snrText: String,
    val rssiText: String,
    val notesText: String,
    val customColorArgb: Int?,
    val maskLocationOnExport: Boolean = false,
    val isPickingCoordinateOnMap: Boolean = false
)

data class PendingNodeDbImport(
    val fileName: String,
    val jsonText: String,
    val incomingResult: NodeDbParseResult,
    val matchedExistingCount: Int,
    val newNodesCount: Int,
    val incomingRegularCount: Int,
    val incomingManualCount: Int
)

data class NodeMapUiState(
    val isLoading: Boolean = false,
    val hasLoadedFile: Boolean = false,
    val loadedFileName: String? = null,
    val errorMessage: String? = null,
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
    val baseLayer: MapBaseLayer = MapBaseLayer.DARK_MATTER,
    val selectedNode: MeshNode? = null,
    val showNoGpsSheet: Boolean = false,
    val noGpsSearchQuery: String = "",
    val fitBoundsRequestId: Long = 0L,
    val isAddNodeMode: Boolean = false,
    val editingDraft: NodeEditorDraft? = null,
    val isMeasureMode: Boolean = false,
    val measureAnchorA: MeshNode? = null,
    val measureAnchorB: MeshNode? = null,
    val showDirectLinksSheet: Boolean = false,
    val directLinksSortDescending: Boolean = true,
    val isLosMode: Boolean = false,
    val losPointA: LosEndpoint? = null,
    val losPointB: LosEndpoint? = null,
    val losTxHeightText: String = "2.0",
    val losRxHeightText: String = "2.0",
    val losFrequencyText: String = LosAnalyzer.DEFAULT_LORA_US_FREQ_MHZ.toString(),
    val losTxPowerText: String = "22",
    val losTxGainText: String = "3.0",
    val losRxGainText: String = "3.0",
    val losSensitivityText: String = "-130",
    val losCoordinates: List<Pair<Double, Double>> = emptyList(),
    val losElevations: List<Double> = emptyList(),
    val losElevationSource: LosElevationSource? = null,
    val losResult: LosAnalysisResult? = null,
    val losObservedDiagnostic: LinkDiagnosticSummary? = null,
    val isLosLoading: Boolean = false,
    val losErrorMessage: String? = null,
    val isLosOfflineTileMissing: Boolean = false,
    val isLosDrawerExpanded: Boolean = true,
    val sightlineFresnelResults: Map<String, LosAnalysisResult> = emptyMap(),
    val sightlineDiagnostics: Map<String, LinkDiagnosticSummary> = emptyMap(),
    val isSightlinesSolving: Boolean = false,
    val sightlinesSolvedCount: Int = 0,
    val sightlinesTotalCount: Int = 0,
    val isTopHeaderDismissed: Boolean = false,
    val isHudMinimized: Boolean = false,
    val isInitialCacheRestored: Boolean = false,
    val startupTargetLat: Double? = null,
    val startupTargetLon: Double? = null,
    val hasStartedStartupZoom: Boolean = false,
    val savedCameraLat: Double? = null,
    val savedCameraLon: Double? = null,
    val savedCameraZoom: Double? = null,
    val savedCameraBearingDeg: Float = 0f,
    val overlayContinuityMode: OverlayContinuityMode = OverlayContinuityMode.KEEP_NODEDB_WITH_RF,
    val fadeMarginPreset: FadeMarginPreset = FadeMarginPreset.WINTER_CLEAR,
    val isAggregateCoverageEnabled: Boolean = false,
    val aggregateCoverageRadiusKm: Int = 10,
    val aggregateCoverageMastMeters: Double = 10.0,
    val isAggregateCoverageLoading: Boolean = false,
    val aggregateCoverageProgressDone: Int = 0,
    val aggregateCoverageProgressTotal: Int = 0,
    val aggregateCoverageError: String? = null,
    val isAggregateOfflineTileMissing: Boolean = false,
    val aggregateCoverageResult: AggregateCoverageResult? = null,
    val pendingImport: PendingNodeDbImport? = null
) {
    val hasDatasetOrCustomNodes: Boolean
        get() = hasLoadedFile || allNodes.isNotEmpty()
    val currentAggregateParams: AggregateCoverageParams
        get() = AggregateCoverageParams(
            radiusKm = aggregateCoverageRadiusKm,
            antennaHeightMeters = aggregateCoverageMastMeters,
            fadeMarginDb = fadeMarginPreset.fadeMarginDb
        )

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
        get() = NodeInventoryState.filterNodesForMap(
            allNodes = allNodes,
            showMqtt = showMqtt,
            favoritesOnly = favoritesOnly,
            searchQuery = searchQuery,
            selectedHwModels = selectedHwModels,
            selectedRoles = selectedRoles
        )

    val eligibleAggregateRfNodeCount: Int
        get() = filteredMapNodes.count { !it.viaMqtt }

    val isAggregateCoverageStale: Boolean
        get() {
            val result = aggregateCoverageResult ?: return false
            return result.signature != AggregateCoverageEngine.buildSignature(
                nodes = filteredMapNodes,
                params = currentAggregateParams
            )
        }

    val allNoGpsNodes: List<MeshNode>
        get() = allNodes.filter { !it.hasValidGps }

    val filteredNoGpsNodes: List<MeshNode>
        get() = NodeInventoryState.filterNodesWithoutGps(
            allNodes = allNodes,
            showMqtt = showMqtt,
            favoritesOnly = favoritesOnly,
            noGpsSearchQuery = noGpsSearchQuery,
            selectedHwModels = selectedHwModels,
            selectedRoles = selectedRoles
        )

    val directRfLinksWithGps: List<DirectRfLink>
        get() = NodeInventoryState.computeDirectRfLinks(
            allNodes = allNodes,
            selfNode = selfNode,
            sortDescending = directLinksSortDescending,
            sightlineFresnelResults = sightlineFresnelResults,
            sightlineDiagnostics = sightlineDiagnostics
        )
}

class NodeMapViewModel @JvmOverloads constructor(
    application: Application,
    val viewportHolder: MapViewportStateHolder = MapViewportStateHolder(),
    val inventoryHolder: NodeInventoryStateHolder = NodeInventoryStateHolder(),
    val coordinator: RfWorkspaceCoordinator = RfWorkspaceCoordinator(),
    val environmentHolder: RfEnvironmentStateHolder = RfEnvironmentStateHolder(),
    private val liveSyncRepository: LiveMeshSyncRepository? = null,
    private val bleRepository: MeshtasticBleRepository? = null
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(NodeMapUiState())
    val uiState: StateFlow<NodeMapUiState> = _uiState.asStateFlow()

    private var losFetchJob: Job? = null
    private var sightlineSolveJob: Job? = null
    private var sightlineSearchDebounceJob: Job? = null
    private var aggregateCoverageJob: Job? = null
    private var aggregateSearchDebounceJob: Job? = null
    private var initialCacheRestoreJob: Job? = null
    private var startupResolutionJob: Job? = null

    private data class CachedSightlineTerrain(
        val selfLat: Double,
        val selfLon: Double,
        val targetLat: Double,
        val targetLon: Double,
        val coordinates: List<Pair<Double, Double>>,
        val elevations: List<Double>,
        val elevationSource: LosElevationSource
    )

    private data class LosParamsSnapshot(
        val txH: Double,
        val rxH: Double,
        val freq: Double,
        val txPower: Double,
        val txGain: Double,
        val rxGain: Double,
        val sens: Double,
        val fadeMarginDb: Double
    )

    private val sightlineTerrainCache = mutableMapOf<String, CachedSightlineTerrain>()

    internal var aggregateTileGridProvider: (suspend (
        northLat: Double,
        southLat: Double,
        westLon: Double,
        eastLon: Double,
        zoom: Int
    ) -> Result<TerrariumTileGrid>)? = null

    internal var sightlineTileGridProvider: (suspend (
        northLat: Double,
        southLat: Double,
        westLon: Double,
        eastLon: Double,
        zoom: Int
    ) -> Result<TerrariumTileGrid>)? = null

    internal var losOpenMeteoProvider: suspend (
        coordinates: List<Pair<Double, Double>>
    ) -> Result<List<Double>> = { coords ->
        ElevationRepository.fetchElevations(coords, getApplication())
    }

    private var importedBaseNodes: List<MeshNode> = emptyList()
    private var customStoreData: CustomNodeStoreData = CustomNodeStoreData()

    private val cacheJsonFile: File
        get() = File(getApplication<Application>().filesDir, CACHED_JSON_FILE)

    private val cacheMetaFile: File
        get() = File(getApplication<Application>().filesDir, CACHED_META_FILE)

    private val customStoreFile: File
        get() = File(getApplication<Application>().filesDir, CUSTOM_NODES_FILE)

    init {
        OfflineDemPackManager.initialize(getApplication())
        viewModelScope.launch {
            viewportHolder.state.collectLatest { vp ->
                _uiState.update { state ->
                    if (state.baseLayer == vp.baseLayer &&
                        state.savedCameraLat == vp.cameraLat &&
                        state.savedCameraLon == vp.cameraLon &&
                        state.savedCameraZoom == vp.cameraZoom &&
                        state.savedCameraBearingDeg == vp.cameraBearingDeg &&
                        state.overlayContinuityMode == vp.overlayContinuityMode &&
                        state.startupTargetLat == vp.startupTargetLat &&
                        state.startupTargetLon == vp.startupTargetLon &&
                        state.hasStartedStartupZoom == vp.hasStartedStartupZoom &&
                        state.fitBoundsRequestId == vp.fitBoundsRequestId
                    ) {
                        state
                    } else {
                        state.copy(
                            baseLayer = vp.baseLayer,
                            savedCameraLat = vp.cameraLat,
                            savedCameraLon = vp.cameraLon,
                            savedCameraZoom = vp.cameraZoom,
                            savedCameraBearingDeg = vp.cameraBearingDeg,
                            overlayContinuityMode = vp.overlayContinuityMode,
                            startupTargetLat = vp.startupTargetLat,
                            startupTargetLon = vp.startupTargetLon,
                            hasStartedStartupZoom = vp.hasStartedStartupZoom,
                            fitBoundsRequestId = vp.fitBoundsRequestId
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            environmentHolder.fadeMarginPreset.collectLatest { preset ->
                val changed = _uiState.value.fadeMarginPreset != preset
                if (changed) {
                    _uiState.update { it.copy(fadeMarginPreset = preset) }
                    recomputeLosWithCachedElevation()
                    recomputeSolvedSightlinesFromCache()
                    if (_uiState.value.isAggregateCoverageEnabled) {
                        refreshAggregateCoverage()
                    }
                }
            }
        }
        liveSyncRepository?.let { repo ->
            viewModelScope.launch {
                repo.incomingUpdates.collect { batch ->
                    applyLiveTelemetryUpdates(batch)
                }
            }
        }
        bleRepository?.let { repo ->
            viewModelScope.launch {
                repo.incomingUpdates.collect { batch ->
                    applyLiveTelemetryUpdates(batch)
                }
            }
        }
        initialCacheRestoreJob = restoreCachedNodeDb()
    }

    fun centerOnMyRadio(myNodeNum: Long? = null): Boolean {
        val state = _uiState.value
        val target = (if (myNodeNum != null) {
            state.allNodes.firstOrNull { it.num == myNodeNum && it.hasValidGps }
        } else {
            null
        }) ?: state.selfNode?.takeIf { it.hasValidGps } ?: return false
        viewportHolder.requestCenterFocus(
            lat = target.latitude!!,
            lon = target.longitude!!,
            minZoom = 15.0
        )
        return true
    }

    fun setFadeMarginPreset(preset: FadeMarginPreset) {
        environmentHolder.setFadeMarginPreset(preset)
    }

    fun applyLiveTelemetryUpdates(updates: List<LiveTelemetryUpdate>) {
        if (updates.isEmpty()) return
        importedBaseNodes = NodeInventoryState.mergeLiveTelemetryUpdates(importedBaseNodes, updates)
        val merged = CustomNodeStore.mergeNodes(importedBaseNodes, customStoreData)
        val currentState = _uiState.value
        val updatedSelected = currentState.selectedNode?.let { sel ->
            merged.nodes.firstOrNull { it.stableKey == sel.stableKey }
        }
        inventoryHolder.applyDataset(
            result = merged,
            fileName = currentState.loadedFileName,
            hasImportedFile = currentState.hasLoadedFile,
            selectedNode = updatedSelected
        )
        val recomputedDiagnostics = buildSightlineDiagnostics(
            selfNode = merged.selfNode,
            allNodes = merged.nodes,
            results = currentState.sightlineFresnelResults
        )
        _uiState.update { state ->
            state.copy(
                allNodes = merged.nodes,
                selfNode = merged.selfNode,
                totalCount = merged.totalCount,
                plottedGpsCount = merged.plottedGpsCount,
                rfCount = merged.rfCount,
                mqttCount = merged.mqttCount,
                noGpsCount = merged.noGpsCount,
                manualNodeCount = merged.nodes.count { it.isManual },
                editedNodeCount = merged.nodes.count { it.isEdited },
                selectedNode = updatedSelected,
                sightlineDiagnostics = recomputedDiagnostics
            )
        }
        if (currentState.losPointA != null && currentState.losPointB != null && currentState.losResult != null) {
            recomputeLosWithCachedElevation()
        }
        if (currentState.showRfSightlines && merged.selfNode?.hasValidGps == true) {
            refreshSightlineFresnelCalculations()
        }
    }

    private fun restoreCachedNodeDb(): Job {
        return viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) {
                val loadedStore = CustomNodeStore.loadFromFile(customStoreFile)
                val cachedPair = if (cacheJsonFile.exists()) {
                    val jsonText = runCatching { cacheJsonFile.readText(Charsets.UTF_8) }.getOrNull()
                    if (jsonText != null) {
                        val fileName = runCatching {
                            if (cacheMetaFile.exists()) {
                                JSONObject(cacheMetaFile.readText(Charsets.UTF_8))
                                    .optString("fileName", "cached_nodedb.json")
                            } else {
                                "cached_nodedb.json"
                            }
                        }.getOrDefault("cached_nodedb.json")
                        fileName to jsonText
                    } else null
                } else null
                loadedStore to cachedPair
            }

            customStoreData = restored.first
            val cached = restored.second
            if (cached == null) {
                if (customStoreData.manualNodes.isNotEmpty()) {
                    importedBaseNodes = emptyList()
                    val merged = CustomNodeStore.mergeNodes(emptyList(), customStoreData)
                    applyParsedResult(
                        result = merged,
                        fileName = null,
                        hasImportedFile = false,
                        triggerFitBounds = false
                    )
                } else {
                    _uiState.update { it.copy(isInitialCacheRestored = true) }
                }
                return@launch
            }

            val (fileName, jsonText) = cached
            val parseResult = withContext(Dispatchers.Default) {
                NodeDbParser.parse(jsonText)
            }
            parseResult.onSuccess { result ->
                importedBaseNodes = result.nodes.filterNot { it.isManual }
                val merged = CustomNodeStore.mergeNodes(importedBaseNodes, customStoreData)
                applyParsedResult(merged, fileName, hasImportedFile = true, triggerFitBounds = false)
            }.onFailure {
                if (customStoreData.manualNodes.isNotEmpty()) {
                    importedBaseNodes = emptyList()
                    val merged = CustomNodeStore.mergeNodes(emptyList(), customStoreData)
                    applyParsedResult(merged, fileName = null, hasImportedFile = false, triggerFitBounds = false)
                } else {
                    _uiState.update { it.copy(isInitialCacheRestored = true) }
                }
            }
        }
    }

    fun resolveStartupZoomTarget(permissionGranted: Boolean) {
        val snapshot = _uiState.value
        if (snapshot.hasStartedStartupZoom || snapshot.startupTargetLat != null || startupResolutionJob != null) {
            return
        }
        startupResolutionJob = viewModelScope.launch {
            val deviceLocation = if (permissionGranted) {
                DeviceLocationResolver.fetchQuickDeviceLocation(getApplication())
            } else {
                null
            }
            initialCacheRestoreJob?.join()
            val current = _uiState.value
            val (targetLat, targetLon) = DeviceLocationResolver.resolveStartupTarget(
                deviceLocation = deviceLocation,
                selfNode = current.selfNode,
                allNodes = current.allNodes
            )
            viewportHolder.setStartupTarget(targetLat, targetLon)
            _uiState.update {
                it.copy(
                    startupTargetLat = targetLat,
                    startupTargetLon = targetLon
                )
            }
        }
    }

    fun markStartupZoomStarted() {
        viewportHolder.markStartupZoomStarted()
        _uiState.update { it.copy(hasStartedStartupZoom = true) }
    }

    @JvmOverloads
    fun saveMapCameraState(
        lat: Double,
        lon: Double,
        zoom: Double,
        bearingDeg: Float = _uiState.value.savedCameraBearingDeg
    ) {
        viewportHolder.saveCameraState(lat, lon, zoom, bearingDeg)
        _uiState.update {
            it.copy(
                savedCameraLat = lat,
                savedCameraLon = lon,
                savedCameraZoom = zoom,
                savedCameraBearingDeg = bearingDeg
            )
        }
    }

    fun setOverlayContinuityMode(mode: OverlayContinuityMode) {
        viewportHolder.setOverlayContinuityMode(mode)
        _uiState.update { it.copy(overlayContinuityMode = mode) }
    }

    fun loadFromUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            val appContext = getApplication<Application>()
            val readResult = withContext(Dispatchers.IO) {
                runCatching {
                    runCatching {
                        appContext.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }

                    val fileName = resolveDisplayName(uri) ?: "nodedb.json"
                    val jsonText = appContext.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader(Charsets.UTF_8).readText()
                    } ?: throw IllegalStateException("Unable to open selected file stream.")

                    fileName to jsonText
                }
            }

            readResult.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = err.message ?: "Failed to read file."
                    )
                }
                return@launch
            }

            val (fileName, jsonText) = readResult.getOrThrow()
            processIncomingJsonText(fileName, jsonText)
        }
    }

    fun importJsonText(fileName: String, jsonText: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            processIncomingJsonText(fileName, jsonText)
        }
    }

    private suspend fun processIncomingJsonText(fileName: String, jsonText: String) {
        initialCacheRestoreJob?.join()
        val parseResult = withContext(Dispatchers.Default) {
            NodeDbParser.parse(jsonText)
        }

        parseResult.onSuccess { result ->
            val currentNodes = _uiState.value.allNodes
            if (currentNodes.isNotEmpty()) {
                val existingKeys = buildSet {
                    importedBaseNodes.forEach { add(it.stableKey) }
                    customStoreData.manualNodes.forEach { add(it.stableKey) }
                    currentNodes.forEach { add(it.stableKey) }
                }
                val matchedExistingCount = result.nodes.count { it.stableKey in existingKeys }
                val newNodesCount = (result.nodes.size - matchedExistingCount).coerceAtLeast(0)
                val incomingManualCount = result.nodes.count { it.isManual }
                val incomingRegularCount = result.nodes.size - incomingManualCount
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = null,
                        pendingImport = PendingNodeDbImport(
                            fileName = fileName,
                            jsonText = jsonText,
                            incomingResult = result,
                            matchedExistingCount = matchedExistingCount,
                            newNodesCount = newNodesCount,
                            incomingRegularCount = incomingRegularCount,
                            incomingManualCount = incomingManualCount
                        )
                    )
                }
            } else {
                applyImportReplace(fileName, jsonText, result)
            }
        }.onFailure { err ->
            _uiState.update {
                it.copy(
                    isLoading = false,
                    errorMessage = err.message ?: "Invalid Meshtastic NodeDB JSON."
                )
            }
        }
    }

    fun confirmPendingImportMerge() {
        val pending = _uiState.value.pendingImport ?: return
        viewModelScope.launch {
            val outcome = CustomNodeStore.mergeImportedNodeDb(
                existingBaseNodes = importedBaseNodes,
                incomingNodes = pending.incomingResult.nodes,
                storeData = customStoreData
            )
            importedBaseNodes = outcome.mergedBaseNodes
            customStoreData = outcome.updatedStoreData

            val backupJson = withContext(Dispatchers.Default) {
                CustomNodeStore.buildNodeDbBackupJson(
                    nodes = outcome.mergedBaseNodes,
                    selfNode = outcome.mergedBaseNodes.firstOrNull { it.isSelf },
                    sourceFileName = pending.fileName
                )
            }
            withContext(Dispatchers.IO) {
                runCatching {
                    CustomNodeStore.saveToFile(customStoreFile, customStoreData)
                    cacheJsonFile.writeText(backupJson, Charsets.UTF_8)
                    val meta = JSONObject().put("fileName", pending.fileName)
                    cacheMetaFile.writeText(meta.toString(), Charsets.UTF_8)
                }
            }
            val shouldFitBounds = _uiState.value.plottedGpsCount == 0
            _uiState.update { it.copy(pendingImport = null) }
            applyParsedResult(
                result = outcome.mergedParseResult,
                fileName = pending.fileName,
                hasImportedFile = true,
                triggerFitBounds = shouldFitBounds
            )
        }
    }

    fun confirmPendingImportReplace() {
        val pending = _uiState.value.pendingImport ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(pendingImport = null) }
            applyImportReplace(pending.fileName, pending.jsonText, pending.incomingResult)
        }
    }

    fun dismissPendingImport() {
        _uiState.update { it.copy(pendingImport = null, isLoading = false) }
    }

    private suspend fun applyImportReplace(
        fileName: String,
        jsonText: String,
        result: NodeDbParseResult
    ) {
        val importedManual = result.nodes.filter { it.isManual }
        val importedRegular = result.nodes.filterNot { it.isManual }
        if (importedManual.isNotEmpty()) {
            val existingManualKeys = customStoreData.manualNodes.mapTo(HashSet()) { it.stableKey }
            val newManual = importedManual.filter { it.stableKey !in existingManualKeys }
            if (newManual.isNotEmpty()) {
                customStoreData = customStoreData.copy(
                    manualNodes = customStoreData.manualNodes + newManual
                )
                withContext(Dispatchers.IO) {
                    runCatching { CustomNodeStore.saveToFile(customStoreFile, customStoreData) }
                }
            }
        }
        importedBaseNodes = importedRegular
        val merged = CustomNodeStore.mergeNodes(importedBaseNodes, customStoreData)
        withContext(Dispatchers.IO) {
            runCatching {
                cacheJsonFile.writeText(jsonText, Charsets.UTF_8)
                val meta = JSONObject().put("fileName", fileName)
                cacheMetaFile.writeText(meta.toString(), Charsets.UTF_8)
            }
        }
        applyParsedResult(merged, fileName, hasImportedFile = true, triggerFitBounds = true)
    }

    private fun applyParsedResult(
        result: NodeDbParseResult,
        fileName: String?,
        hasImportedFile: Boolean = true,
        triggerFitBounds: Boolean
    ) {
        sightlineSolveJob?.cancel()
        sightlineSearchDebounceJob?.cancel()
        sightlineTerrainCache.clear()
        aggregateCoverageJob?.cancel()
        aggregateSearchDebounceJob?.cancel()
        inventoryHolder.applyDataset(
            result = result,
            fileName = fileName,
            hasImportedFile = hasImportedFile,
            selectedNode = null
        )
        val nextFitId = if (triggerFitBounds) viewportHolder.requestFitBounds() else viewportHolder.state.value.fitBoundsRequestId
        _uiState.update { state ->
            state.copy(
                isLoading = false,
                hasLoadedFile = hasImportedFile,
                loadedFileName = fileName,
                errorMessage = null,
                allNodes = result.nodes,
                selfNode = result.selfNode,
                totalCount = result.totalCount,
                plottedGpsCount = result.plottedGpsCount,
                rfCount = result.rfCount,
                mqttCount = result.mqttCount,
                noGpsCount = result.noGpsCount,
                manualNodeCount = result.nodes.count { it.isManual },
                editedNodeCount = result.nodes.count { it.isEdited },
                selectedNode = null,
                showNoGpsSheet = false,
                showDirectLinksSheet = false,
                measureAnchorA = result.selfNode?.takeIf { it.hasValidGps },
                measureAnchorB = null,
                losPointA = null,
                losPointB = null,
                losCoordinates = emptyList(),
                losElevations = emptyList(),
                losElevationSource = null,
                losResult = null,
                losObservedDiagnostic = null,
                losErrorMessage = null,
                sightlineFresnelResults = emptyMap(),
                sightlineDiagnostics = emptyMap(),
                isSightlinesSolving = false,
                sightlinesSolvedCount = 0,
                sightlinesTotalCount = 0,
                isAggregateCoverageEnabled = false,
                isAggregateCoverageLoading = false,
                aggregateCoverageProgressDone = 0,
                aggregateCoverageProgressTotal = 0,
                aggregateCoverageError = null,
                aggregateCoverageResult = null,
                isInitialCacheRestored = true,
                fitBoundsRequestId = nextFitId
            )
        }
        if (_uiState.value.showRfSightlines) {
            refreshSightlineFresnelCalculations()
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        val resolver = getApplication<Application>().contentResolver
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    fun updateSearchQuery(query: String) {
        inventoryHolder.updateSearchQuery(query)
        _uiState.update { it.copy(searchQuery = query) }
        scheduleDebouncedFilterRecalc()
    }

    fun toggleShowMqtt() {
        inventoryHolder.toggleShowMqtt()
        _uiState.update { it.copy(showMqtt = !it.showMqtt) }
        if (_uiState.value.showRfSightlines) {
            refreshSightlineFresnelCalculations()
        }
        if (_uiState.value.isAggregateCoverageEnabled) {
            refreshAggregateCoverage()
        }
    }

    fun toggleRfSightlines() {
        inventoryHolder.toggleRfSightlines()
        _uiState.update { it.copy(showRfSightlines = !it.showRfSightlines) }
        refreshSightlineFresnelCalculations()
    }

    fun toggleFavoritesOnly() {
        inventoryHolder.toggleFavoritesOnly()
        _uiState.update { it.copy(favoritesOnly = !it.favoritesOnly) }
        if (_uiState.value.showRfSightlines) {
            refreshSightlineFresnelCalculations()
        }
        if (_uiState.value.isAggregateCoverageEnabled) {
            refreshAggregateCoverage()
        }
    }

    fun toggleHwModelFilter(hwModel: String) {
        val clean = hwModel.trim()
        if (clean.isEmpty()) return
        inventoryHolder.toggleHwModelFilter(clean)
        _uiState.update { state ->
            val next = if (clean in state.selectedHwModels) {
                state.selectedHwModels - clean
            } else {
                state.selectedHwModels + clean
            }
            state.copy(selectedHwModels = next)
        }
        refreshPostFilterCalculations()
    }

    fun toggleRoleFilter(role: String) {
        val clean = role.trim()
        if (clean.isEmpty()) return
        inventoryHolder.toggleRoleFilter(clean)
        _uiState.update { state ->
            val next = if (clean in state.selectedRoles) {
                state.selectedRoles - clean
            } else {
                state.selectedRoles + clean
            }
            state.copy(selectedRoles = next)
        }
        refreshPostFilterCalculations()
    }

    fun resetAllFilters() {
        inventoryHolder.resetFilters()
        _uiState.update {
            it.copy(
                searchQuery = "",
                showMqtt = true,
                showRfSightlines = true,
                favoritesOnly = false,
                selectedHwModels = emptySet(),
                selectedRoles = emptySet()
            )
        }
        refreshPostFilterCalculations()
    }

    private fun refreshPostFilterCalculations() {
        if (_uiState.value.showRfSightlines) {
            refreshSightlineFresnelCalculations()
        }
        if (_uiState.value.isAggregateCoverageEnabled) {
            refreshAggregateCoverage()
        }
    }

    private fun scheduleDebouncedFilterRecalc() {
        if (_uiState.value.showRfSightlines && _uiState.value.selfNode?.hasValidGps == true) {
            sightlineSearchDebounceJob?.cancel()
            val job = viewModelScope.launch {
                delay(400L)
                if (_uiState.value.showRfSightlines) {
                    refreshSightlineFresnelCalculations()
                }
            }
            sightlineSearchDebounceJob = job
            coordinator.registerJob("mesh_transient_sightline_debounce", job)
        }
        if (_uiState.value.isAggregateCoverageEnabled) {
            aggregateSearchDebounceJob?.cancel()
            val job = viewModelScope.launch {
                delay(450L)
                if (_uiState.value.isAggregateCoverageEnabled) {
                    refreshAggregateCoverage()
                }
            }
            aggregateSearchDebounceJob = job
            coordinator.registerJob("mesh_transient_agg_debounce", job)
        }
    }

    fun cycleBaseLayer() {
        val next = viewportHolder.cycleBaseLayer()
        _uiState.update { it.copy(baseLayer = next) }
    }

    fun selectBaseLayer(layer: MapBaseLayer) {
        viewportHolder.selectBaseLayer(layer)
        _uiState.update { it.copy(baseLayer = layer) }
    }

    fun selectNode(node: MeshNode?) {
        inventoryHolder.selectNode(node)
        _uiState.update { it.copy(selectedNode = node) }
    }

    fun setNoGpsSheetVisible(visible: Boolean) {
        inventoryHolder.setNoGpsSheetVisible(visible)
        _uiState.update {
            it.copy(
                showNoGpsSheet = visible,
                noGpsSearchQuery = if (visible) it.noGpsSearchQuery else ""
            )
        }
    }

    fun updateNoGpsSearchQuery(query: String) {
        inventoryHolder.updateNoGpsSearchQuery(query)
        _uiState.update { it.copy(noGpsSearchQuery = query) }
    }

    fun requestFitBounds() {
        val nextId = viewportHolder.requestFitBounds()
        _uiState.update { it.copy(fitBoundsRequestId = nextId) }
    }

    fun setTopHeaderDismissed(dismissed: Boolean) {
        _uiState.update { it.copy(isTopHeaderDismissed = dismissed) }
    }

    fun toggleHudMinimized() {
        _uiState.update { it.copy(isHudMinimized = !it.isHudMinimized) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun toggleAddNodeMode() {
        _uiState.update { state ->
            val entering = !state.isAddNodeMode
            if (entering) {
                losFetchJob?.cancel()
            }
            state.copy(
                isAddNodeMode = entering,
                isLosMode = if (entering) false else state.isLosMode,
                isLosLoading = if (entering) false else state.isLosLoading,
                isMeasureMode = if (entering) false else state.isMeasureMode,
                editingDraft = if (!entering && state.editingDraft?.isPickingCoordinateOnMap == true) {
                    null
                } else {
                    state.editingDraft
                }
            )
        }
    }

    fun openNewNodeEditor(lat: Double? = null, lon: Double? = null) {
        val existingNodes = _uiState.value.allNodes
        val (genNum, genId) = CustomNodeStore.generateUniqueManualId(existingNodes)
        val suffix = genId.removePrefix("!").takeLast(4).uppercase(Locale.US)
        val draft = NodeEditorDraft(
            originalKey = null,
            isCreatingNew = true,
            isManual = true,
            isEdited = false,
            id = genId,
            num = genNum,
            longName = "Node $suffix",
            shortName = suffix,
            latitudeText = lat?.let { String.format(Locale.US, "%.6f", it) } ?: "",
            longitudeText = lon?.let { String.format(Locale.US, "%.6f", it) } ?: "",
            altitudeText = "",
            hwModelText = "HELTEC_V3",
            roleText = "CLIENT",
            hopsAwayText = "0",
            viaMqtt = false,
            isFavorite = false,
            isSelf = existingNodes.none { it.isSelf },
            snrText = "",
            rssiText = "",
            notesText = "",
            customColorArgb = null,
            maskLocationOnExport = false,
            isPickingCoordinateOnMap = false
        )
        _uiState.update {
            it.copy(
                editingDraft = draft,
                selectedNode = null
            )
        }
    }

    fun openEditNodeEditor(node: MeshNode) {
        val draft = NodeEditorDraft(
            originalKey = node.stableKey,
            isCreatingNew = false,
            isManual = node.isManual,
            isEdited = node.isEdited,
            id = node.id,
            num = node.num,
            longName = node.longName,
            shortName = node.shortName,
            latitudeText = node.latitude?.let { String.format(Locale.US, "%.6f", it) } ?: "",
            longitudeText = node.longitude?.let { String.format(Locale.US, "%.6f", it) } ?: "",
            altitudeText = node.altitude?.toString() ?: "",
            hwModelText = node.hwModel.orEmpty(),
            roleText = node.role.orEmpty(),
            hopsAwayText = node.hopsAway?.toString() ?: "",
            viaMqtt = node.viaMqtt,
            isFavorite = node.isFavorite,
            isSelf = node.isSelf,
            snrText = node.snr?.let { String.format(Locale.US, "%.1f", it) } ?: "",
            rssiText = node.rssi?.toString() ?: "",
            notesText = node.notes.orEmpty(),
            customColorArgb = node.customColorArgb,
            maskLocationOnExport = node.isManual && node.maskLocationOnExport,
            isPickingCoordinateOnMap = false
        )
        _uiState.update { it.copy(editingDraft = draft) }
    }

    fun startCoordinatePickForEditor(currentDraft: NodeEditorDraft) {
        _uiState.update {
            it.copy(
                isAddNodeMode = true,
                isLosMode = false,
                isMeasureMode = false,
                editingDraft = currentDraft.copy(isPickingCoordinateOnMap = true)
            )
        }
    }

    fun dismissNodeEditor() {
        _uiState.update {
            it.copy(editingDraft = null)
        }
    }

    fun saveNodeEditorDraft(draft: NodeEditorDraft): String? {
        val cleanLongName = draft.longName.trim()
        val cleanShortName = draft.shortName.trim()
        var cleanId = draft.id.trim()
        if (cleanLongName.isEmpty() && cleanShortName.isEmpty() && cleanId.isEmpty()) {
            return "Enter at least a node name or ID."
        }
        if (cleanId.isEmpty()) {
            val (_, generatedId) = CustomNodeStore.generateUniqueManualId(_uiState.value.allNodes)
            cleanId = generatedId
        } else if (!cleanId.startsWith("!") && cleanId.toLongOrNull() != null) {
            cleanId = "!%08x".format(Locale.US, cleanId.toLong() and 0xFFFFFFFFL)
        }

        val parsedNum = draft.num ?: cleanId.removePrefix("!").toLongOrNull(16)
        val latRaw = draft.latitudeText.trim()
        val lonRaw = draft.longitudeText.trim()
        val (latVal, lonVal) = when {
            latRaw.isEmpty() && lonRaw.isEmpty() -> null to null
            latRaw.isEmpty() || lonRaw.isEmpty() -> {
                return "Enter both Latitude and Longitude, or leave both blank for No-GPS."
            }
            else -> {
                val lat = latRaw.toDoubleOrNull()
                val lon = lonRaw.toDoubleOrNull()
                if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite()) {
                    return "Invalid latitude or longitude number."
                }
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                    return "Latitude must be -90..90 and Longitude -180..180."
                }
                if (abs(lat) < 1e-7 && abs(lon) < 1e-7) {
                    return "Coordinates 0, 0 are treated as invalid GPS by Meshtastic."
                }
                lat to lon
            }
        }

        val altRaw = draft.altitudeText.trim()
        val altVal = if (altRaw.isEmpty()) null else altRaw.toIntOrNull()
            ?: return "Altitude must be a whole number in meters."

        val hopsRaw = draft.hopsAwayText.trim()
        val hopsVal = if (hopsRaw.isEmpty()) null else {
            val parsed = hopsRaw.toIntOrNull()
            if (parsed == null || parsed < 0) return "Hops Away must be 0 or greater."
            parsed
        }

        val snrRaw = draft.snrText.trim()
        val snrVal = if (snrRaw.isEmpty()) null else snrRaw.toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: return "SNR must be a valid number."

        val rssiRaw = draft.rssiText.trim()
        val rssiVal = if (rssiRaw.isEmpty()) null else rssiRaw.toIntOrNull()
            ?: return "RSSI must be a whole integer (e.g. -85)."

        val hwVal = draft.hwModelText.trim().ifEmpty { null }
        val roleVal = draft.roleText.trim().ifEmpty { null }
        val notesVal = draft.notesText.trim().ifEmpty { null }
        val finalShortName = cleanShortName.ifEmpty {
            cleanLongName.take(4).uppercase(Locale.US).ifEmpty {
                cleanId.removePrefix("!").takeLast(4).uppercase(Locale.US)
            }
        }
        val finalLongName = cleanLongName.ifEmpty { finalShortName }

        // Clear any competing self override if the user explicitly marks this node as Self
        var updatedStore = customStoreData
        if (draft.isSelf) {
            updatedStore = updatedStore.copy(
                manualNodes = updatedStore.manualNodes.map {
                    if (it.isSelf) it.copy(isSelf = false) else it
                },
                overrides = updatedStore.overrides.mapValues { (_, ov) ->
                    if (ov.isSelf) ov.copy(isSelf = false) else ov
                }
            )
        }

        val savedTargetKey: String
        if (draft.isCreatingNew || draft.isManual) {
            val existingIdx = if (!draft.isCreatingNew && draft.originalKey != null) {
                updatedStore.manualNodes.indexOfFirst { it.stableKey == draft.originalKey }
            } else -1

            val existingBase = existingIdx.takeIf { it >= 0 }?.let { updatedStore.manualNodes[it] }
            val newNodeBase = MeshNode(
                num = parsedNum,
                id = cleanId,
                longName = finalLongName,
                shortName = finalShortName,
                hwModel = hwVal,
                role = roleVal,
                latitude = latVal,
                longitude = lonVal,
                altitude = altVal,
                hopsAway = hopsVal,
                viaMqtt = draft.viaMqtt,
                isFavorite = draft.isFavorite,
                isSelf = draft.isSelf,
                snr = snrVal,
                rssi = rssiVal,
                lastHeard = existingBase?.lastHeard ?: Instant.now().epochSecond,
                deviceMetrics = existingBase?.deviceMetrics,
                environmentMetrics = existingBase?.environmentMetrics,
                rawJson = "",
                customColorArgb = draft.customColorArgb,
                isManual = true,
                notes = notesVal,
                isEdited = existingBase != null,
                maskLocationOnExport = draft.maskLocationOnExport
            )
            val newNode = newNodeBase.copy(
                rawJson = CustomNodeStore.serializeNodeToJsonObject(newNodeBase).toString(2)
            )
            savedTargetKey = newNode.stableKey
            val nextManualList = updatedStore.manualNodes.toMutableList()
            if (existingIdx >= 0) {
                nextManualList[existingIdx] = newNode
            } else {
                nextManualList += newNode
            }
            updatedStore = updatedStore.copy(manualNodes = nextManualList)
        } else {
            val originalKey = draft.originalKey ?: cleanId.lowercase(Locale.US)
            val prevOv = updatedStore.overrides[originalKey]
            val baseNode = importedBaseNodes.firstOrNull { it.stableKey == originalKey }

            fun coordsMatch(a: Double?, b: Double?): Boolean =
                (a == null && b == null) || (a != null && b != null && abs(a - b) <= 1e-5)

            fun snrMatch(a: Double?, b: Double?): Boolean =
                (a == null && b == null) || (a != null && b != null && abs(a - b) <= 0.06)

            val refLat = prevOv?.latitude ?: baseNode?.latitude
            val refLon = prevOv?.longitude ?: baseNode?.longitude
            val refAlt = prevOv?.altitude ?: baseNode?.altitude
            val overrideLocation = (prevOv?.overrideLocation == true) ||
                !coordsMatch(latVal, refLat) ||
                !coordsMatch(lonVal, refLon) ||
                altVal != refAlt

            val refHops = prevOv?.hopsAway ?: baseNode?.hopsAway
            val refSnr = prevOv?.snr ?: baseNode?.snr
            val refRssi = prevOv?.rssi ?: baseNode?.rssi
            val refMqtt = prevOv?.viaMqtt ?: baseNode?.viaMqtt ?: draft.viaMqtt
            val overrideTelemetry = (prevOv?.overrideTelemetry == true) ||
                hopsVal != refHops ||
                !snrMatch(snrVal, refSnr) ||
                rssiVal != refRssi ||
                draft.viaMqtt != refMqtt

            val override = NodeOverride(
                targetKey = originalKey,
                id = cleanId,
                num = parsedNum,
                longName = finalLongName,
                shortName = finalShortName,
                hwModel = hwVal,
                role = roleVal,
                latitude = latVal,
                longitude = lonVal,
                altitude = altVal,
                hopsAway = hopsVal,
                viaMqtt = draft.viaMqtt,
                isFavorite = draft.isFavorite,
                isSelf = draft.isSelf,
                snr = snrVal,
                rssi = rssiVal,
                notes = notesVal,
                customColorArgb = draft.customColorArgb,
                overrideLocation = overrideLocation,
                overrideTelemetry = overrideTelemetry
            )
            savedTargetKey = cleanId.lowercase(Locale.US).ifBlank { originalKey }
            val nextOverrides = updatedStore.overrides.toMutableMap().apply {
                this[originalKey] = override
            }
            updatedStore = updatedStore.copy(overrides = nextOverrides)
        }

        customStoreData = updatedStore
        persistCustomStoreAsync(updatedStore)
        recomputeMergedNodesAfterEdit(
            focusKey = savedTargetKey,
            keepInspectorOpen = !draft.isCreatingNew && _uiState.value.selectedNode != null
        )
        _uiState.update {
            it.copy(
                isAddNodeMode = false,
                editingDraft = null
            )
        }
        return null
    }

    fun deleteManualNode(node: MeshNode) {
        if (!node.isManual) return
        val key = node.stableKey
        val nextManual = customStoreData.manualNodes.filterNot { it.stableKey == key }
        val nextOverrides = customStoreData.overrides.filterKeys { it != key }
        val updatedStore = customStoreData.copy(manualNodes = nextManual, overrides = nextOverrides)
        customStoreData = updatedStore
        persistCustomStoreAsync(updatedStore)
        recomputeMergedNodesAfterEdit(focusKey = null, keepInspectorOpen = false)
    }

    fun revertNodeEdits(node: MeshNode) {
        if (node.isManual) return
        val matchKey = importedBaseNodes.firstOrNull { base ->
            val ov = customStoreData.overrides[base.stableKey]
            base.stableKey == node.stableKey ||
                (ov != null && ov.id.equals(node.id, ignoreCase = true) && ov.num == node.num)
        }?.stableKey ?: node.stableKey

        if (!customStoreData.overrides.containsKey(matchKey)) return
        val updatedStore = customStoreData.copy(
            overrides = customStoreData.overrides - matchKey
        )
        customStoreData = updatedStore
        persistCustomStoreAsync(updatedStore)
        recomputeMergedNodesAfterEdit(focusKey = matchKey, keepInspectorOpen = true)
    }

    fun buildNodeDbBackupJson(): String {
        val state = _uiState.value
        return CustomNodeStore.buildNodeDbBackupJson(
            nodes = state.allNodes,
            selfNode = state.selfNode,
            sourceFileName = state.loadedFileName
        )
    }

    private fun persistCustomStoreAsync(data: CustomNodeStoreData) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { CustomNodeStore.saveToFile(customStoreFile, data) }
        }
    }

    private fun recomputeMergedNodesAfterEdit(focusKey: String?, keepInspectorOpen: Boolean) {
        sightlineTerrainCache.clear()
        val merged = CustomNodeStore.mergeNodes(importedBaseNodes, customStoreData)
        val updatedSelected = if (keepInspectorOpen && focusKey != null) {
            merged.nodes.firstOrNull { it.stableKey == focusKey }
        } else null
        inventoryHolder.applyDataset(
            result = merged,
            fileName = _uiState.value.loadedFileName,
            hasImportedFile = _uiState.value.hasLoadedFile,
            selectedNode = updatedSelected
        )
        _uiState.update { state ->
            state.copy(
                allNodes = merged.nodes,
                selfNode = merged.selfNode,
                totalCount = merged.totalCount,
                plottedGpsCount = merged.plottedGpsCount,
                rfCount = merged.rfCount,
                mqttCount = merged.mqttCount,
                noGpsCount = merged.noGpsCount,
                manualNodeCount = merged.nodes.count { it.isManual },
                editedNodeCount = merged.nodes.count { it.isEdited },
                selectedNode = updatedSelected,
                sightlineFresnelResults = emptyMap(),
                sightlineDiagnostics = emptyMap()
            )
        }
        if (_uiState.value.showRfSightlines) {
            refreshSightlineFresnelCalculations()
        }
    }

    fun toggleMeasureMode() {
        _uiState.update { state ->
            val entering = !state.isMeasureMode
            state.copy(
                isMeasureMode = entering,
                isAddNodeMode = if (entering) false else state.isAddNodeMode,
                isLosMode = if (entering) false else state.isLosMode,
                showRfSightlines = if (entering) true else state.showRfSightlines,
                measureAnchorA = if (entering) {
                    state.measureAnchorA ?: state.selfNode?.takeIf { it.hasValidGps }
                } else null,
                measureAnchorB = if (entering) state.measureAnchorB else null
            )
        }
    }

    fun onMapNodeClick(node: MeshNode) {
        if (_uiState.value.isLosMode) {
            if (!node.hasValidGps) return
            val endpoint = LosEndpoint(
                latitude = node.latitude!!,
                longitude = node.longitude!!,
                label = node.displayLongName,
                shortLabel = node.displayShortName,
                node = node
            )
            assignLosEndpoint(endpoint)
            return
        }

        _uiState.update { state ->
            if (!state.isMeasureMode) {
                state.copy(selectedNode = node)
            } else {
                when {
                    state.measureAnchorA == null -> state.copy(measureAnchorA = node)
                    state.measureAnchorB == null -> {
                        if (node.id == state.measureAnchorA.id && node.num == state.measureAnchorA.num) {
                            state
                        } else {
                            state.copy(measureAnchorB = node)
                        }
                    }
                    else -> state.copy(measureAnchorA = node, measureAnchorB = null)
                }
            }
        }
    }

    fun toggleLosMode() {
        val current = _uiState.value
        val entering = !current.isLosMode
        if (!entering) {
            losFetchJob?.cancel()
            _uiState.update { it.copy(isLosMode = false, isLosLoading = false) }
            return
        }
        val prefillA = current.losPointA ?: current.selfNode?.takeIf { it.hasValidGps }?.let { self ->
            LosEndpoint(
                latitude = self.latitude!!,
                longitude = self.longitude!!,
                label = self.displayLongName,
                shortLabel = self.displayShortName,
                node = self
            )
        }
        _uiState.update {
            it.copy(
                isLosMode = true,
                isAddNodeMode = false,
                isMeasureMode = false,
                isLosDrawerExpanded = true,
                losPointA = prefillA
            )
        }
        val updated = _uiState.value
        if (updated.losPointA != null && updated.losPointB != null && updated.losResult == null) {
            fetchAndAnalyzeLos(updated.losPointA, updated.losPointB)
        }
    }

    fun onMapCoordinateTap(latitude: Double, longitude: Double) {
        val current = _uiState.value
        if (current.isAddNodeMode) {
            val activeDraft = current.editingDraft
            if (activeDraft != null && activeDraft.isPickingCoordinateOnMap) {
                _uiState.update {
                    it.copy(
                        editingDraft = activeDraft.copy(
                            latitudeText = String.format(Locale.US, "%.6f", latitude),
                            longitudeText = String.format(Locale.US, "%.6f", longitude),
                            isPickingCoordinateOnMap = false
                        )
                    )
                }
            } else {
                openNewNodeEditor(lat = latitude, lon = longitude)
            }
            return
        }
        if (!current.isLosMode) return
        val nextTag = if (current.losPointA == null || current.losPointB != null) "PT-A" else "PT-B"
        val endpoint = LosEndpoint(
            latitude = latitude,
            longitude = longitude,
            label = String.format(Locale.US, "%.4f, %.4f", latitude, longitude),
            shortLabel = nextTag,
            node = null
        )
        assignLosEndpoint(endpoint)
    }

    private fun assignLosEndpoint(endpoint: LosEndpoint) {
        val current = _uiState.value
        val newA: LosEndpoint
        val newB: LosEndpoint?

        when {
            current.losPointA == null -> {
                newA = endpoint
                newB = null
            }
            current.losPointB == null -> {
                newA = current.losPointA
                newB = endpoint
            }
            else -> {
                newA = endpoint
                newB = null
            }
        }

        _uiState.update {
            it.copy(
                losPointA = newA,
                losPointB = newB,
                losCoordinates = emptyList(),
                losElevations = emptyList(),
                losResult = null,
                losObservedDiagnostic = null,
                losErrorMessage = null,
                isLosDrawerExpanded = true
            )
        }

        if (newB != null) {
            fetchAndAnalyzeLos(newA, newB)
        }
    }

    fun useSelfAsLosPointA() {
        val self = _uiState.value.selfNode?.takeIf { it.hasValidGps } ?: return
        val selfEndpoint = LosEndpoint(
            latitude = self.latitude!!,
            longitude = self.longitude!!,
            label = self.displayLongName,
            shortLabel = self.displayShortName,
            node = self
        )
        val currentB = _uiState.value.losPointB
        _uiState.update {
            it.copy(
                losPointA = selfEndpoint,
                losCoordinates = emptyList(),
                losElevations = emptyList(),
                losResult = null,
                losErrorMessage = null
            )
        }
        if (currentB != null) {
            fetchAndAnalyzeLos(selfEndpoint, currentB)
        }
    }

    fun swapLosEndpoints() {
        val current = _uiState.value
        val a = current.losPointA ?: return
        val b = current.losPointB ?: return
        val reversedCoords = current.losCoordinates.reversed()
        val reversedElevs = current.losElevations.reversed()

        _uiState.update {
            it.copy(
                losPointA = b,
                losPointB = a,
                losCoordinates = reversedCoords,
                losElevations = reversedElevs,
                losErrorMessage = null
            )
        }
        if (reversedCoords.size >= 2 && reversedElevs.size == reversedCoords.size) {
            recomputeLosWithCachedElevation()
        } else {
            fetchAndAnalyzeLos(b, a)
        }
    }

    fun clearLosEndpoints() {
        losFetchJob?.cancel()
        _uiState.update {
            it.copy(
                losPointA = null,
                losPointB = null,
                losCoordinates = emptyList(),
                losElevations = emptyList(),
                losElevationSource = null,
                losResult = null,
                losObservedDiagnostic = null,
                isLosLoading = false,
                losErrorMessage = null
            )
        }
    }

    fun toggleLosDrawerExpanded() {
        _uiState.update { it.copy(isLosDrawerExpanded = !it.isLosDrawerExpanded) }
    }

    fun updateLosTxHeight(text: String) {
        _uiState.update { it.copy(losTxHeightText = text) }
        recomputeLosWithCachedElevation()
        recomputeSolvedSightlinesFromCache()
    }

    fun updateLosRxHeight(text: String) {
        _uiState.update { it.copy(losRxHeightText = text) }
        recomputeLosWithCachedElevation()
        recomputeSolvedSightlinesFromCache()
    }

    fun updateLosFrequency(text: String) {
        _uiState.update { it.copy(losFrequencyText = text) }
        recomputeLosWithCachedElevation()
        recomputeSolvedSightlinesFromCache()
    }

    fun updateLosTxPower(text: String) {
        _uiState.update { it.copy(losTxPowerText = text) }
        recomputeLosWithCachedElevation()
        recomputeSolvedSightlinesFromCache()
    }

    fun updateLosTxGain(text: String) {
        _uiState.update { it.copy(losTxGainText = text) }
        recomputeLosWithCachedElevation()
        recomputeSolvedSightlinesFromCache()
    }

    fun updateLosRxGain(text: String) {
        _uiState.update { it.copy(losRxGainText = text) }
        recomputeLosWithCachedElevation()
        recomputeSolvedSightlinesFromCache()
    }

    fun updateLosSensitivity(text: String) {
        _uiState.update { it.copy(losSensitivityText = text) }
        recomputeLosWithCachedElevation()
        recomputeSolvedSightlinesFromCache()
    }

    fun applyLosMastFix() {
        val state = _uiState.value
        val result = state.losResult ?: return
        val equalDelta = result.requiredEqualMastDeltaMeters
        if (equalDelta <= 0.05) return

        val curTx = state.losTxHeightText.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
        val curRx = state.losRxHeightText.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
        val newTx = String.format(Locale.US, "%.1f", curTx + equalDelta)
        val newRx = String.format(Locale.US, "%.1f", curRx + equalDelta)

        _uiState.update {
            it.copy(
                losTxHeightText = newTx,
                losRxHeightText = newRx
            )
        }
        recomputeLosWithCachedElevation()
        recomputeSolvedSightlinesFromCache()
    }

    fun retryLosElevation() {
        val state = _uiState.value
        val a = state.losPointA ?: return
        val b = state.losPointB ?: return
        fetchAndAnalyzeLos(a, b)
    }

    fun applyFlatTerrainFallback() {
        val state = _uiState.value
        val a = state.losPointA ?: return
        val b = state.losPointB ?: return
        val coords = state.losCoordinates.ifEmpty {
            LosAnalyzer.sampleGreatCirclePath(a, b, LosAnalyzer.DEFAULT_SAMPLE_COUNT)
        }
        val baseA = a.node?.altitude?.toDouble() ?: 0.0
        val baseB = b.node?.altitude?.toDouble() ?: baseA
        val elevs = List(coords.size) { idx ->
            val t = idx.toDouble() / (coords.size - 1).toDouble()
            baseA + t * (baseB - baseA)
        }
        _uiState.update {
            it.copy(
                losCoordinates = coords,
                losElevations = elevs,
                losElevationSource = LosElevationSource.FLAT_FALLBACK,
                losErrorMessage = null
            )
        }
        recomputeLosWithCachedElevation()
    }

    fun inspectSightlineFresnel(targetNode: MeshNode) {
        val state = _uiState.value
        val self = state.selfNode?.takeIf { it.hasValidGps }
        if (self == null || !targetNode.hasValidGps) return
        val key = LosAnalyzer.nodeSightlineKey(targetNode)
        val cached = sightlineTerrainCache[key]
        val selfLat = self.latitude!!
        val selfLon = self.longitude!!
        val tLat = targetNode.latitude!!
        val tLon = targetNode.longitude!!

        if (cached != null &&
            cached.selfLat == selfLat &&
            cached.selfLon == selfLon &&
            cached.targetLat == tLat &&
            cached.targetLon == tLon
        ) {
            losFetchJob?.cancel()
            val endpointA = LosEndpoint(selfLat, selfLon, self.displayLongName, self.displayShortName, self)
            val endpointB = LosEndpoint(tLat, tLon, targetNode.displayLongName, targetNode.displayShortName, targetNode)
            _uiState.update {
                it.copy(
                    isLosMode = true,
                    isMeasureMode = false,
                    showDirectLinksSheet = false,
                    selectedNode = null,
                    isLosDrawerExpanded = true,
                    isLosLoading = false,
                    losPointA = endpointA,
                    losPointB = endpointB,
                    losCoordinates = cached.coordinates,
                    losElevations = cached.elevations,
                    losElevationSource = cached.elevationSource,
                    losErrorMessage = null
                )
            }
            recomputeLosWithCachedElevation()
        } else {
            analyzeLosBetweenNodes(self, targetNode)
        }
    }

    fun analyzeLosBetweenNodes(nodeA: MeshNode, nodeB: MeshNode) {
        if (!nodeA.hasValidGps || !nodeB.hasValidGps) return
        val endpointA = LosEndpoint(
            latitude = nodeA.latitude!!,
            longitude = nodeA.longitude!!,
            label = nodeA.displayLongName,
            shortLabel = nodeA.displayShortName,
            node = nodeA
        )
        val endpointB = LosEndpoint(
            latitude = nodeB.latitude!!,
            longitude = nodeB.longitude!!,
            label = nodeB.displayLongName,
            shortLabel = nodeB.displayShortName,
            node = nodeB
        )
        _uiState.update {
            it.copy(
                isLosMode = true,
                isMeasureMode = false,
                showDirectLinksSheet = false,
                selectedNode = null,
                isLosDrawerExpanded = true,
                losPointA = endpointA,
                losPointB = endpointB,
                losCoordinates = emptyList(),
                losElevations = emptyList(),
                losElevationSource = null,
                losResult = null,
                losErrorMessage = null
            )
        }
        fetchAndAnalyzeLos(endpointA, endpointB)
    }

    fun refreshSightlineFresnelCalculations() {
        val state = _uiState.value
        val self = state.selfNode?.takeIf { it.hasValidGps }
        if (!state.showRfSightlines || self == null) {
            sightlineSolveJob?.cancel()
            _uiState.update {
                it.copy(
                    isSightlinesSolving = false,
                    sightlinesSolvedCount = 0,
                    sightlinesTotalCount = 0
                )
            }
            return
        }

        val selfLat = self.latitude!!
        val selfLon = self.longitude!!
        val targets = state.filteredMapNodes
            .asSequence()
            .filter { !it.isSelf && !it.viaMqtt && it.hasValidGps }
            .filter { target ->
                MeshGeoUtils.haversineKm(selfLat, selfLon, target.latitude!!, target.longitude!!) >= 0.005
            }
            .sortedWith(
                compareBy<MeshNode> { if (it.hopsAway == 0) 0 else 1 }
                    .thenBy { MeshGeoUtils.haversineKm(selfLat, selfLon, it.latitude!!, it.longitude!!) }
            )
            .take(MAX_AUTO_SIGHTLINES)
            .toList()

        if (targets.isEmpty()) {
            sightlineSolveJob?.cancel()
            _uiState.update {
                it.copy(
                    sightlineFresnelResults = emptyMap(),
                    isSightlinesSolving = false,
                    sightlinesSolvedCount = 0,
                    sightlinesTotalCount = 0
                )
            }
            return
        }

        sightlineSolveJob?.cancel()
        val solveJob = viewModelScope.launch {
            val params = parseLosParams(_uiState.value)
            val selfEndpoint = LosEndpoint(
                latitude = selfLat,
                longitude = selfLon,
                label = self.displayLongName,
                shortLabel = self.displayShortName,
                node = self
            )

            val results = mutableMapOf<String, LosAnalysisResult>()
            val pendingTargets = mutableListOf<MeshNode>()

            targets.forEach { target ->
                val key = LosAnalyzer.nodeSightlineKey(target)
                val tLat = target.latitude!!
                val tLon = target.longitude!!
                val cached = sightlineTerrainCache[key]
                if (cached != null &&
                    cached.selfLat == selfLat &&
                    cached.selfLon == selfLon &&
                    cached.targetLat == tLat &&
                    cached.targetLon == tLon
                ) {
                    val targetEndpoint = LosEndpoint(tLat, tLon, target.displayLongName, target.displayShortName, target)
                    results[key] = LosAnalyzer.analyze(
                        pointA = selfEndpoint,
                        pointB = targetEndpoint,
                        coordinates = cached.coordinates,
                        elevationsMeters = cached.elevations,
                        txHeightMeters = params.txH,
                        rxHeightMeters = params.rxH,
                        frequencyMhz = params.freq,
                        txPowerDbm = params.txPower,
                        txGainDbi = params.txGain,
                        rxGainDbi = params.rxGain,
                        sensitivityDbm = params.sens,
                        elevationSource = cached.elevationSource,
                        fadeMarginDb = params.fadeMarginDb
                    )
                } else {
                    pendingTargets += target
                }
            }

            if (pendingTargets.isEmpty()) {
                val diagnostics = buildSightlineDiagnostics(self, _uiState.value.allNodes, results)
                _uiState.update {
                    it.copy(
                        sightlineFresnelResults = results,
                        sightlineDiagnostics = diagnostics,
                        isSightlinesSolving = false,
                        sightlinesSolvedCount = targets.size,
                        sightlinesTotalCount = targets.size
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    sightlineFresnelResults = results.toMap(),
                    sightlineDiagnostics = buildSightlineDiagnostics(self, it.allNodes, results),
                    isSightlinesSolving = true,
                    sightlinesSolvedCount = results.size,
                    sightlinesTotalCount = targets.size
                )
            }

            val regionalTargets = pendingTargets.filter {
                MeshGeoUtils.haversineKm(selfLat, selfLon, it.latitude!!, it.longitude!!) <= 120.0
            }
            val batchGrid: TerrariumTileGrid? = if (regionalTargets.isNotEmpty()) {
                val lats = listOf(selfLat) + regionalTargets.mapNotNull { it.latitude }
                val lons = listOf(selfLon) + regionalTargets.mapNotNull { it.longitude }
                val nLat = (lats.max() + 0.02).coerceAtMost(85.0)
                val sLat = (lats.min() - 0.02).coerceAtLeast(-85.0)
                val eLon = (lons.max() + 0.02).coerceAtMost(180.0)
                val wLon = (lons.min() - 0.02).coerceAtLeast(-180.0)
                val zoom = TerrariumDemRepository.recommendedZoomForBounds(nLat, sLat, wLon, eLon, 25)
                val gridProvider = sightlineTileGridProvider ?: { n, s, w, e, z ->
                    TerrariumDemRepository.loadTileGridForBounds(getApplication(), n, s, w, e, z)
                }
                gridProvider(nLat, sLat, wLon, eLon, zoom).getOrNull()
            } else {
                null
            }

            var solvedCount = results.size
            for (target in pendingTargets) {
                val key = LosAnalyzer.nodeSightlineKey(target)
                val tLat = target.latitude!!
                val tLon = target.longitude!!
                val targetEndpoint = LosEndpoint(tLat, tLon, target.displayLongName, target.displayShortName, target)
                val coords = withContext(Dispatchers.Default) {
                    LosAnalyzer.sampleGreatCirclePath(selfEndpoint, targetEndpoint, SIGHTLINE_SAMPLE_COUNT)
                }
                val elevRes = fetchPathElevationsTerrariumFirst(coords, preferredGrid = batchGrid)
                elevRes.onSuccess { (elevations, source) ->
                    sightlineTerrainCache[key] = CachedSightlineTerrain(
                        selfLat = selfLat,
                        selfLon = selfLon,
                        targetLat = tLat,
                        targetLon = tLon,
                        coordinates = coords,
                        elevations = elevations,
                        elevationSource = source
                    )
                    val latestParams = parseLosParams(_uiState.value)
                    val analyzed = withContext(Dispatchers.Default) {
                        LosAnalyzer.analyze(
                            pointA = selfEndpoint,
                            pointB = targetEndpoint,
                            coordinates = coords,
                            elevationsMeters = elevations,
                            txHeightMeters = latestParams.txH,
                            rxHeightMeters = latestParams.rxH,
                            frequencyMhz = latestParams.freq,
                            txPowerDbm = latestParams.txPower,
                            txGainDbi = latestParams.txGain,
                            rxGainDbi = latestParams.rxGain,
                            sensitivityDbm = latestParams.sens,
                            elevationSource = source,
                            fadeMarginDb = latestParams.fadeMarginDb
                        )
                    }
                    results[key] = analyzed
                }
                solvedCount++
                _uiState.update {
                    it.copy(
                        sightlineFresnelResults = results.toMap(),
                        sightlineDiagnostics = buildSightlineDiagnostics(self, it.allNodes, results),
                        sightlinesSolvedCount = solvedCount
                    )
                }
            }

            _uiState.update {
                it.copy(
                    sightlineFresnelResults = results.toMap(),
                    sightlineDiagnostics = buildSightlineDiagnostics(self, it.allNodes, results),
                    isSightlinesSolving = false,
                    sightlinesSolvedCount = targets.size,
                    sightlinesTotalCount = targets.size
                )
            }
        }
        sightlineSolveJob = solveJob
        coordinator.registerJob("mesh_sightlines", solveJob)
    }

    private suspend fun fetchPathElevationsTerrariumFirst(
        coords: List<Pair<Double, Double>>,
        preferredGrid: TerrariumTileGrid? = null
    ): Result<Pair<List<Double>, LosElevationSource>> {
        if (preferredGrid != null) {
            val sampled = ArrayList<Double>(coords.size)
            var allValid = true
            for ((lat, lon) in coords) {
                val e = preferredGrid.sampleElevation(lat, lon)
                if (e == null) {
                    allValid = false
                    break
                }
                sampled.add(e)
            }
            if (allValid && sampled.size == coords.size) {
                return Result.success(sampled to LosElevationSource.TERRARIUM_DEM)
            }
        }

        val spanKm = MeshGeoUtils.haversineKm(
            coords.first().first,
            coords.first().second,
            coords.last().first,
            coords.last().second
        )
        if (sightlineTileGridProvider != null || spanKm <= 250.0) {
            val lats = coords.map { it.first }
            val lons = coords.map { it.second }
            val nLat = (lats.max() + 0.015).coerceAtMost(85.0)
            val sLat = (lats.min() - 0.015).coerceAtLeast(-85.0)
            val eLon = (lons.max() + 0.015).coerceAtMost(180.0)
            val wLon = (lons.min() - 0.015).coerceAtLeast(-180.0)
            val zoom = TerrariumDemRepository.recommendedZoomForBounds(nLat, sLat, wLon, eLon, 25)
            val gridProvider = sightlineTileGridProvider ?: { n, s, w, e, z ->
                TerrariumDemRepository.loadTileGridForBounds(getApplication(), n, s, w, e, z)
            }
            val gridResult = gridProvider(nLat, sLat, wLon, eLon, zoom)
            val failure = gridResult.exceptionOrNull()
            if (failure is OfflineTileMissingException) {
                return Result.failure(failure)
            }
            val grid = gridResult.getOrNull()
            if (grid != null) {
                OfflineDemPackManager.refreshCacheStats(getApplication())
                val sampled = ArrayList<Double>(coords.size)
                var allValid = true
                for ((lat, lon) in coords) {
                    val e = grid.sampleElevation(lat, lon)
                    if (e == null) {
                        allValid = false
                        break
                    }
                    sampled.add(e)
                }
                if (allValid && sampled.size == coords.size) {
                    return Result.success(sampled to LosElevationSource.TERRARIUM_DEM)
                }
            }
        }

        return losOpenMeteoProvider(coords).map { elevs ->
            elevs to LosElevationSource.OPEN_METEO
        }
    }

    private fun fetchAndAnalyzeLos(pointA: LosEndpoint, pointB: LosEndpoint) {
        losFetchJob?.cancel()
        val distanceKm = MeshGeoUtils.haversineKm(
            pointA.latitude,
            pointA.longitude,
            pointB.latitude,
            pointB.longitude
        )
        if (distanceKm < 0.005) {
            _uiState.update {
                it.copy(
                    isLosLoading = false,
                    losErrorMessage = "Select two distinct points at least 5 meters apart."
                )
            }
            return
        }

        val fetchJob = viewModelScope.launch {
            _uiState.update { it.copy(isLosLoading = true, losErrorMessage = null) }
            val coords = withContext(Dispatchers.Default) {
                LosAnalyzer.sampleGreatCirclePath(pointA, pointB, LosAnalyzer.DEFAULT_SAMPLE_COUNT)
            }
            val elevationResult = fetchPathElevationsTerrariumFirst(coords)
            elevationResult.onSuccess { (elevations, source) ->
                val nodeA = pointA.node
                val nodeB = pointB.node
                if (nodeA != null && nodeA.isSelf && nodeB != null && !nodeB.viaMqtt && nodeB.hasValidGps) {
                    sightlineTerrainCache[LosAnalyzer.nodeSightlineKey(nodeB)] = CachedSightlineTerrain(
                        selfLat = pointA.latitude,
                        selfLon = pointA.longitude,
                        targetLat = pointB.latitude,
                        targetLon = pointB.longitude,
                        coordinates = coords,
                        elevations = elevations,
                        elevationSource = source
                    )
                }
                _uiState.update {
                    it.copy(
                        isLosLoading = false,
                        losCoordinates = coords,
                        losElevations = elevations,
                        losElevationSource = source,
                        losErrorMessage = null,
                        isLosOfflineTileMissing = false
                    )
                }
                recomputeLosWithCachedElevation()
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLosLoading = false,
                        losCoordinates = coords,
                        losElevations = emptyList(),
                        losElevationSource = null,
                        losResult = null,
                        losErrorMessage = err.message ?: "Failed to fetch terrain elevation (Terrarium DEM & Open-Meteo unavailable).",
                        isLosOfflineTileMissing = err is OfflineTileMissingException
                    )
                }
            }
        }
        losFetchJob = fetchJob
        coordinator.registerJob("mesh_los_fetch", fetchJob)
    }

    private fun parseLosParams(state: NodeMapUiState): LosParamsSnapshot {
        val txH = state.losTxHeightText.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
        val rxH = state.losRxHeightText.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
        val freq = state.losFrequencyText.toDoubleOrNull()?.takeIf { it > 0.0 }
            ?: LosAnalyzer.DEFAULT_LORA_US_FREQ_MHZ
        val txPower = state.losTxPowerText.toDoubleOrNull() ?: RelayBridgeEngine.DEFAULT_TX_POWER_DBM
        val txGain = state.losTxGainText.toDoubleOrNull() ?: RelayBridgeEngine.DEFAULT_ANTENNA_GAIN_DBI
        val rxGain = state.losRxGainText.toDoubleOrNull() ?: RelayBridgeEngine.DEFAULT_ANTENNA_GAIN_DBI
        val sens = state.losSensitivityText.toDoubleOrNull() ?: RelayBridgeEngine.DEFAULT_SENSITIVITY_DBM
        return LosParamsSnapshot(
            txH = txH,
            rxH = rxH,
            freq = freq,
            txPower = txPower,
            txGain = txGain,
            rxGain = rxGain,
            sens = sens,
            fadeMarginDb = state.fadeMarginPreset.fadeMarginDb
        )
    }

    private fun buildSightlineDiagnostics(
        selfNode: MeshNode?,
        allNodes: List<MeshNode>,
        results: Map<String, LosAnalysisResult>
    ): Map<String, LinkDiagnosticSummary> {
        if (results.isEmpty()) return emptyMap()
        val nodesByKey = allNodes.associateBy { LosAnalyzer.nodeSightlineKey(it) }
        val map = LinkedHashMap<String, LinkDiagnosticSummary>(results.size)
        for ((key, predicted) in results) {
            val peer = nodesByKey[key] ?: predicted.pointB.node ?: continue
            val observedSnr = LosAnalyzer.resolveObservedSnr(selfNode, peer)
            val diagnostic = LosAnalyzer.evaluateObservedVsPredicted(
                observedSnrDb = observedSnr,
                observedRssiDbm = peer.rssi,
                predicted = predicted
            )
            if (diagnostic != null) {
                map[key] = diagnostic
            }
        }
        return map
    }

    private fun resolveLosObservedDiagnostic(
        state: NodeMapUiState,
        pointA: LosEndpoint,
        pointB: LosEndpoint,
        predicted: LosAnalysisResult
    ): LinkDiagnosticSummary? {
        val nodeA = pointA.node?.let { n -> state.allNodes.firstOrNull { it.stableKey == n.stableKey } ?: n }
        val nodeB = pointB.node?.let { n -> state.allNodes.firstOrNull { it.stableKey == n.stableKey } ?: n }
        if (nodeA == null || nodeB == null) return null
        val observedSnr = LosAnalyzer.resolveObservedSnr(nodeA, nodeB)
            ?: LosAnalyzer.resolveObservedSnr(nodeB, nodeA)
        val observedRssi = when {
            nodeA.isSelf && !nodeB.isSelf -> nodeB.rssi
            nodeB.isSelf && !nodeA.isSelf -> nodeA.rssi
            else -> nodeB.rssi ?: nodeA.rssi
        }
        return LosAnalyzer.evaluateObservedVsPredicted(
            observedSnrDb = observedSnr,
            observedRssiDbm = observedRssi,
            predicted = predicted
        )
    }

    private fun recomputeLosWithCachedElevation() {
        val state = _uiState.value
        val a = state.losPointA ?: return
        val b = state.losPointB ?: return
        if (state.losCoordinates.size < 2 || state.losElevations.size != state.losCoordinates.size) {
            return
        }
        val params = parseLosParams(state)
        val source = state.losElevationSource ?: LosElevationSource.OPEN_METEO

        val result = LosAnalyzer.analyze(
            pointA = a,
            pointB = b,
            coordinates = state.losCoordinates,
            elevationsMeters = state.losElevations,
            txHeightMeters = params.txH,
            rxHeightMeters = params.rxH,
            frequencyMhz = params.freq,
            txPowerDbm = params.txPower,
            txGainDbi = params.txGain,
            rxGainDbi = params.rxGain,
            sensitivityDbm = params.sens,
            elevationSource = source,
            fadeMarginDb = params.fadeMarginDb
        )
        val observedDiagnostic = resolveLosObservedDiagnostic(state, a, b, result)
        _uiState.update { it.copy(losResult = result, losObservedDiagnostic = observedDiagnostic) }
    }

    private fun recomputeSolvedSightlinesFromCache() {
        val state = _uiState.value
        val self = state.selfNode?.takeIf { it.hasValidGps } ?: return
        if (state.sightlineFresnelResults.isEmpty()) return
        val params = parseLosParams(state)
        val selfLat = self.latitude!!
        val selfLon = self.longitude!!
        val selfEndpoint = LosEndpoint(selfLat, selfLon, self.displayLongName, self.displayShortName, self)

        val updated = state.sightlineFresnelResults.mapValues { (key, existing) ->
            val cached = sightlineTerrainCache[key]
            if (cached != null) {
                LosAnalyzer.analyze(
                    pointA = selfEndpoint,
                    pointB = existing.pointB,
                    coordinates = cached.coordinates,
                    elevationsMeters = cached.elevations,
                    txHeightMeters = params.txH,
                    rxHeightMeters = params.rxH,
                    frequencyMhz = params.freq,
                    txPowerDbm = params.txPower,
                    txGainDbi = params.txGain,
                    rxGainDbi = params.rxGain,
                    sensitivityDbm = params.sens,
                    elevationSource = cached.elevationSource,
                    fadeMarginDb = params.fadeMarginDb
                )
            } else {
                existing
            }
        }
        val diagnostics = buildSightlineDiagnostics(self, state.allNodes, updated)
        _uiState.update {
            it.copy(
                sightlineFresnelResults = updated,
                sightlineDiagnostics = diagnostics
            )
        }
    }

    fun clearMeasureAnchors() {
        _uiState.update { it.copy(measureAnchorA = null, measureAnchorB = null) }
    }

    fun useSelfAsMeasureAnchorA() {
        _uiState.update { state ->
            val self = state.selfNode?.takeIf { it.hasValidGps } ?: return@update state
            val anchorB = state.measureAnchorB?.takeUnless { it.id == self.id && it.num == self.num }
            state.copy(measureAnchorA = self, measureAnchorB = anchorB)
        }
    }

    fun setDirectLinksSheetVisible(visible: Boolean) {
        _uiState.update { it.copy(showDirectLinksSheet = visible) }
    }

    fun toggleDirectLinksSort() {
        _uiState.update { it.copy(directLinksSortDescending = !it.directLinksSortDescending) }
    }

    fun measureDirectLinkFromSelf(targetNode: MeshNode) {
        _uiState.update { state ->
            val self = state.selfNode?.takeIf { it.hasValidGps }
            state.copy(
                showDirectLinksSheet = false,
                isMeasureMode = true,
                showRfSightlines = true,
                measureAnchorA = self ?: targetNode,
                measureAnchorB = if (self != null) targetNode else null
            )
        }
    }

    fun toggleAggregateCoverage() {
        val current = _uiState.value
        if (current.isAggregateCoverageEnabled) {
            aggregateCoverageJob?.cancel()
            aggregateSearchDebounceJob?.cancel()
            _uiState.update {
                it.copy(
                    isAggregateCoverageEnabled = false,
                    isAggregateCoverageLoading = false,
                    aggregateCoverageError = null
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    isAggregateCoverageEnabled = true,
                    aggregateCoverageError = null
                )
            }
            if (current.aggregateCoverageResult == null || _uiState.value.isAggregateCoverageStale) {
                refreshAggregateCoverage()
            }
        }
    }

    fun updateAggregateRadiusKm(radiusKm: Int) {
        val clamped = radiusKm.coerceIn(1, 50)
        if (_uiState.value.aggregateCoverageRadiusKm == clamped) return
        _uiState.update { it.copy(aggregateCoverageRadiusKm = clamped) }
        if (_uiState.value.isAggregateCoverageEnabled) {
            refreshAggregateCoverage()
        }
    }

    fun updateAggregateMastMeters(mastMeters: Double) {
        val clamped = mastMeters.coerceIn(1.0, 100.0)
        if (_uiState.value.aggregateCoverageMastMeters == clamped) return
        _uiState.update { it.copy(aggregateCoverageMastMeters = clamped) }
        if (_uiState.value.isAggregateCoverageEnabled) {
            refreshAggregateCoverage()
        }
    }

    fun refreshAggregateCoverage() {
        val snapshot = _uiState.value
        val filteredNodes = snapshot.filteredMapNodes
        val eligible = AggregateCoverageEngine.filterEligibleRfNodes(filteredNodes)
        if (eligible.selectedNodes.isEmpty()) {
            aggregateCoverageJob?.cancel()
            _uiState.update {
                it.copy(
                    isAggregateCoverageLoading = false,
                    aggregateCoverageProgressDone = 0,
                    aggregateCoverageProgressTotal = 0,
                    aggregateCoverageResult = null,
                    aggregateCoverageError = "No eligible non-MQTT RF nodes with GPS coordinates in the active map view."
                )
            }
            return
        }

        aggregateCoverageJob?.cancel()
        val params = snapshot.currentAggregateParams
        val totalToCompute = eligible.selectedNodes.size

        val aggJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isAggregateCoverageLoading = true,
                    aggregateCoverageProgressDone = 0,
                    aggregateCoverageProgressTotal = totalToCompute,
                    aggregateCoverageError = null
                )
            }
            val result = AggregateCoverageEngine.computeAggregateCoverage(
                context = getApplication(),
                nodes = filteredNodes,
                params = params,
                onProgress = { done, total ->
                    _uiState.update { st ->
                        st.copy(
                            aggregateCoverageProgressDone = done,
                            aggregateCoverageProgressTotal = total
                        )
                    }
                },
                tileGridProvider = aggregateTileGridProvider
            )
            result.onSuccess { coverage ->
                OfflineDemPackManager.refreshCacheStats(getApplication())
                _uiState.update {
                    it.copy(
                        isAggregateCoverageLoading = false,
                        aggregateCoverageProgressDone = coverage.contributingNodeCount,
                        aggregateCoverageProgressTotal = coverage.contributingNodeCount,
                        aggregateCoverageResult = coverage,
                        aggregateCoverageError = null,
                        isAggregateOfflineTileMissing = false
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isAggregateCoverageLoading = false,
                        aggregateCoverageError = err.message ?: "Failed to compute aggregate RF coverage.",
                        isAggregateOfflineTileMissing = err is OfflineTileMissingException
                    )
                }
            }
        }
        aggregateCoverageJob = aggJob
        coordinator.registerJob("mesh_aggregate", aggJob)
    }

    fun cancelAggregateCoverageCompute() {
        aggregateCoverageJob?.cancel()
        aggregateSearchDebounceJob?.cancel()
        _uiState.update { state ->
            state.copy(
                isAggregateCoverageLoading = false,
                isAggregateCoverageEnabled = if (state.aggregateCoverageResult == null) false else state.isAggregateCoverageEnabled
            )
        }
    }

    fun dismissAggregateCoverageError() {
        _uiState.update { it.copy(aggregateCoverageError = null, isAggregateOfflineTileMissing = false) }
    }

    fun clearLoadedJson(clearManualNodesToo: Boolean = false) {
        coordinator.cancelJobsWithPrefix("mesh_")
        losFetchJob?.cancel()
        sightlineSolveJob?.cancel()
        sightlineSearchDebounceJob?.cancel()
        sightlineTerrainCache.clear()
        aggregateCoverageJob?.cancel()
        aggregateSearchDebounceJob?.cancel()

        importedBaseNodes = emptyList()
        if (clearManualNodesToo) {
            customStoreData = CustomNodeStoreData()
        } else {
            // Drop overrides on imported nodes while preserving user's manual nodes
            customStoreData = customStoreData.copy(overrides = emptyMap())
        }
        val merged = CustomNodeStore.mergeNodes(emptyList(), customStoreData)
        inventoryHolder.applyDataset(
            result = merged,
            fileName = null,
            hasImportedFile = false,
            selectedNode = null
        )

        _uiState.update { state ->
            NodeMapUiState(
                baseLayer = state.baseLayer,
                isHudMinimized = state.isHudMinimized,
                isInitialCacheRestored = true,
                allNodes = merged.nodes,
                selfNode = merged.selfNode,
                totalCount = merged.totalCount,
                plottedGpsCount = merged.plottedGpsCount,
                rfCount = merged.rfCount,
                mqttCount = merged.mqttCount,
                noGpsCount = merged.noGpsCount,
                manualNodeCount = merged.nodes.count { it.isManual },
                editedNodeCount = merged.nodes.count { it.isEdited },
                startupTargetLat = state.startupTargetLat,
                startupTargetLon = state.startupTargetLon,
                hasStartedStartupZoom = state.hasStartedStartupZoom,
                savedCameraLat = state.savedCameraLat,
                savedCameraLon = state.savedCameraLon,
                savedCameraZoom = state.savedCameraZoom,
                savedCameraBearingDeg = state.savedCameraBearingDeg,
                overlayContinuityMode = state.overlayContinuityMode,
                fadeMarginPreset = state.fadeMarginPreset
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                if (cacheJsonFile.exists()) cacheJsonFile.delete()
                if (cacheMetaFile.exists()) cacheMetaFile.delete()
                CustomNodeStore.saveToFile(customStoreFile, customStoreData)
            }
        }
    }

    companion object {
        private const val CACHED_JSON_FILE = "cached_nodedb.json"
        private const val CACHED_META_FILE = "cached_nodedb_meta.json"
        private const val CUSTOM_NODES_FILE = "custom_nodes_store.json"
        private const val MAX_AUTO_SIGHTLINES = 40
        private const val SIGHTLINE_SAMPLE_COUNT = 64
    }
}
