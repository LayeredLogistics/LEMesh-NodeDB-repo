package us.lemesh.nodedb.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import us.lemesh.nodedb.data.BridgeHopEvaluation
import us.lemesh.nodedb.data.FadeMarginPreset
import us.lemesh.nodedb.data.LAYER_TINT_PALETTE
import us.lemesh.nodedb.data.LayerRenderMode
import us.lemesh.nodedb.data.LosAnalyzer
import us.lemesh.nodedb.data.MeshGeoUtils
import us.lemesh.nodedb.data.MeshNode
import us.lemesh.nodedb.data.OfflineDemPackManager
import us.lemesh.nodedb.data.OfflineTileMissingException
import us.lemesh.nodedb.data.RelayBridgeEngine
import us.lemesh.nodedb.data.RfEnvironmentStateHolder
import us.lemesh.nodedb.data.SavedViewshedLayer
import us.lemesh.nodedb.data.TerrariumDemRepository
import us.lemesh.nodedb.data.TerrariumTileGrid
import us.lemesh.nodedb.data.ViewshedEngine
import us.lemesh.nodedb.data.ViewshedFixAdvisor
import us.lemesh.nodedb.data.ViewshedFixPlan
import us.lemesh.nodedb.data.ViewshedLayerRepository
import us.lemesh.nodedb.data.ViewshedParams
import us.lemesh.nodedb.data.ViewshedResult
import us.lemesh.nodedb.data.createTintedLayerBitmap
import us.lemesh.nodedb.ui.state.MapViewportStateHolder
import us.lemesh.nodedb.ui.state.RfWorkspaceCoordinator
import us.lemesh.nodedb.ui.state.WorkspaceMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ViewshedPlacementTarget(val label: String) {
    TX("TX Site"),
    RX("RX Test")
}

enum class ViewshedPlacementMode(val label: String) {
    AUTO("Auto"),
    TX("TX"),
    RX("RX")
}

data class ViewshedRxLinkResult(
    val txLat: Double,
    val txLon: Double,
    val rxLat: Double,
    val rxLon: Double,
    val bearingDegrees: Double,
    val hop: BridgeHopEvaluation,
    val txGroundElevationMeters: Double,
    val rxGroundElevationMeters: Double,
    val fixPlan: ViewshedFixPlan = ViewshedFixPlan(emptyList())
)

data class ViewshedUiState(
    val txLat: Double? = 42.1292,
    val txLon: Double? = -80.0851,
    val siteLabel: String = "TX Site (Erie, PA)",
    val sourceNode: MeshNode? = null,
    val groundElevationMeters: Double? = null,
    val isGroundElevLoading: Boolean = false,
    val rxLat: Double? = null,
    val rxLon: Double? = null,
    val rxLabel: String = "Tap or drop RX",
    val rxNode: MeshNode? = null,
    val rxGroundElevationMeters: Double? = null,
    val isRxElevLoading: Boolean = false,
    val placementMode: ViewshedPlacementMode = ViewshedPlacementMode.AUTO,
    val nextAutoTarget: ViewshedPlacementTarget = ViewshedPlacementTarget.RX,
    val antennaHeightMeters: Float = 10f,
    val rxAntennaHeightMeters: Float = 10f,
    val radiusKm: Int = 25,
    val frequencyText: String = LosAnalyzer.DEFAULT_LORA_US_FREQ_MHZ.toString(),
    val txPowerDbm: Float = 22f,
    val antennaGainDbi: Float = 3.0f,
    val sensitivityDbm: Float = -130f,
    val fadeMarginPreset: FadeMarginPreset = FadeMarginPreset.WINTER_CLEAR,
    val overlayOpacity: Float = 0.68f,
    val isCalculating: Boolean = false,
    val isRxLinkLoading: Boolean = false,
    val statusNote: String? = null,
    val errorMessage: String? = null,
    val isOfflineTileMissingError: Boolean = false,
    val currentResult: ViewshedResult? = null,
    val rxLinkResult: ViewshedRxLinkResult? = null,
    val pinnedSnapshot: ViewshedResult? = null,
    val isSnapshotPinned: Boolean = false,
    val showSnapshotOnMap: Boolean = false,
    val hasUserPositionedSite: Boolean = false,
    val hasUserPositionedRx: Boolean = false,
    val savedLayers: List<SavedViewshedLayer> = emptyList(),
    val isTopBarExpanded: Boolean = true
) {
    val hasTxSet: Boolean
        get() = txLat != null && txLon != null

    val hasRxSet: Boolean
        get() = rxLat != null && rxLon != null

    val areBothEndpointsSet: Boolean
        get() = hasTxSet && hasRxSet

    val effectivePlacementTarget: ViewshedPlacementTarget
        get() = when (placementMode) {
            ViewshedPlacementMode.AUTO -> nextAutoTarget
            ViewshedPlacementMode.TX -> ViewshedPlacementTarget.TX
            ViewshedPlacementMode.RX -> ViewshedPlacementTarget.RX
        }

    val directDistanceKm: Double?
        get() {
            val tLat = txLat ?: return null
            val tLon = txLon ?: return null
            val rLat = rxLat ?: return null
            val rLon = rxLon ?: return null
            return MeshGeoUtils.haversineKm(tLat, tLon, rLat, rLon)
        }

    val directBearingDeg: Double?
        get() {
            val tLat = txLat ?: return null
            val tLon = txLon ?: return null
            val rLat = rxLat ?: return null
            val rLon = rxLon ?: return null
            return MeshGeoUtils.bearingDegrees(tLat, tLon, rLat, rLon)
        }

    val hasAnyVisibleMapOverlay: Boolean
        get() = currentResult != null ||
            pinnedSnapshot != null ||
            rxLinkResult != null ||
            savedLayers.any { it.isEnabled }

    val hasAnyOverlayOrPins: Boolean
        get() = hasAnyVisibleMapOverlay || hasTxSet || hasRxSet
}

class ViewshedViewModel @JvmOverloads constructor(
    application: Application,
    val viewportHolder: MapViewportStateHolder = MapViewportStateHolder(),
    val coordinator: RfWorkspaceCoordinator = RfWorkspaceCoordinator(),
    val environmentHolder: RfEnvironmentStateHolder = RfEnvironmentStateHolder()
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ViewshedUiState())
    val uiState: StateFlow<ViewshedUiState> = _uiState.asStateFlow()

    private var groundSampleJob: Job? = null
    private var rxGroundSampleJob: Job? = null
    private var rxLinkJob: Job? = null
    private var calculateJob: Job? = null
    private var latestExistingNodes: List<MeshNode> = emptyList()

    init {
        OfflineDemPackManager.initialize(getApplication())
        sampleGroundElevation(42.1292, -80.0851)
        loadPersistedLayers()
        viewModelScope.launch {
            viewportHolder.state
                .map { it.overlayOpacity }
                .distinctUntilChanged()
                .collectLatest { opacity ->
                    _uiState.update { state ->
                        if (state.overlayOpacity == opacity) state else state.copy(overlayOpacity = opacity)
                    }
                }
        }
        viewModelScope.launch {
            environmentHolder.fadeMarginPreset.collectLatest { preset ->
                val prev = _uiState.value
                if (prev.fadeMarginPreset != preset) {
                    _uiState.update { it.copy(fadeMarginPreset = preset) }
                    refreshRxLinkCheck(debounceMs = 0L)
                    if (prev.currentResult != null && prev.hasTxSet && !prev.isCalculating) {
                        calculateCoverage(latestExistingNodes)
                    }
                }
            }
        }
    }

    fun setFadeMarginPreset(preset: FadeMarginPreset) {
        environmentHolder.setFadeMarginPreset(preset)
    }

    private fun loadPersistedLayers() {
        viewModelScope.launch {
            val loaded = ViewshedLayerRepository.loadAllLayers(getApplication())
            _uiState.update { it.copy(savedLayers = loaded) }
        }
    }

    fun syncDefaultSiteFromSelfNodeIfUnset(selfNode: MeshNode?) {
        if (selfNode == null || !selfNode.hasValidGps) return
        val current = _uiState.value
        if (current.hasUserPositionedSite) return
        setProposedSite(
            lat = selfNode.latitude!!,
            lon = selfNode.longitude!!,
            label = "${selfNode.displayLongName} (${selfNode.displayShortName})",
            sourceNode = selfNode,
            markUserPositioned = false
        )
    }

    fun setPlacementMode(mode: ViewshedPlacementMode) {
        _uiState.update { state ->
            val nextAuto = when (mode) {
                ViewshedPlacementMode.AUTO -> when {
                    !state.hasTxSet -> ViewshedPlacementTarget.TX
                    !state.hasRxSet -> ViewshedPlacementTarget.RX
                    else -> state.nextAutoTarget
                }
                ViewshedPlacementMode.TX -> ViewshedPlacementTarget.TX
                ViewshedPlacementMode.RX -> ViewshedPlacementTarget.RX
            }
            state.copy(placementMode = mode, nextAutoTarget = nextAuto)
        }
    }

    fun setPlacementTarget(target: ViewshedPlacementTarget) {
        _uiState.update {
            it.copy(
                placementMode = if (target == ViewshedPlacementTarget.TX) {
                    ViewshedPlacementMode.TX
                } else {
                    ViewshedPlacementMode.RX
                },
                nextAutoTarget = target
            )
        }
    }

    fun selectNodeForActiveTarget(node: MeshNode) {
        if (!node.hasValidGps) return
        val target = _uiState.value.effectivePlacementTarget
        when (target) {
            ViewshedPlacementTarget.TX -> {
                selectExistingNode(node)
                advanceAutoTargetAfter(ViewshedPlacementTarget.TX)
            }
            ViewshedPlacementTarget.RX -> {
                selectRxNode(node)
                advanceAutoTargetAfter(ViewshedPlacementTarget.RX)
            }
        }
    }

    fun setCoordinateForActiveTarget(lat: Double, lon: Double) {
        val target = _uiState.value.effectivePlacementTarget
        when (target) {
            ViewshedPlacementTarget.TX -> {
                setCustomSiteCoordinate(lat, lon)
                advanceAutoTargetAfter(ViewshedPlacementTarget.TX)
            }
            ViewshedPlacementTarget.RX -> {
                setRxCustomCoordinate(lat, lon)
                advanceAutoTargetAfter(ViewshedPlacementTarget.RX)
            }
        }
    }

    private fun advanceAutoTargetAfter(placed: ViewshedPlacementTarget) {
        _uiState.update { state ->
            if (state.placementMode == ViewshedPlacementMode.AUTO) {
                val next = if (placed == ViewshedPlacementTarget.TX) {
                    ViewshedPlacementTarget.RX
                } else {
                    ViewshedPlacementTarget.TX
                }
                state.copy(nextAutoTarget = next)
            } else {
                state
            }
        }
    }

    fun selectExistingNode(node: MeshNode) {
        if (!node.hasValidGps) return
        setProposedSite(
            lat = node.latitude!!,
            lon = node.longitude!!,
            label = "${node.displayLongName} (${node.displayShortName})",
            sourceNode = node,
            markUserPositioned = true
        )
    }

    fun selectRxNode(node: MeshNode) {
        if (!node.hasValidGps) return
        setRxSite(
            lat = node.latitude!!,
            lon = node.longitude!!,
            label = "${node.displayShortName} (${node.displayLongName})",
            rxNode = node,
            markUserPositioned = true
        )
    }

    fun inspectObstructedNodeForFix(node: MeshNode) {
        if (!node.hasValidGps) return
        selectRxNode(node)
    }

    fun setCustomSiteCoordinate(lat: Double, lon: Double) {
        setProposedSite(
            lat = lat,
            lon = lon,
            label = "TX Site",
            sourceNode = null,
            markUserPositioned = true
        )
    }

    fun setRxCustomCoordinate(lat: Double, lon: Double) {
        setRxSite(
            lat = lat,
            lon = lon,
            label = "RX Test Pin",
            rxNode = null,
            markUserPositioned = true
        )
    }

    fun swapTxAndRx() {
        val s = _uiState.value
        if (!s.hasTxSet && !s.hasRxSet) return
        _uiState.update {
            it.copy(
                txLat = s.rxLat,
                txLon = s.rxLon,
                siteLabel = if (s.hasRxSet) s.rxLabel else "Tap or drop TX",
                sourceNode = s.rxNode,
                groundElevationMeters = s.rxGroundElevationMeters,
                rxLat = s.txLat,
                rxLon = s.txLon,
                rxLabel = if (s.hasTxSet) s.siteLabel else "Tap or drop RX",
                rxNode = s.sourceNode,
                rxGroundElevationMeters = s.groundElevationMeters,
                hasUserPositionedSite = true,
                hasUserPositionedRx = true,
                errorMessage = null
            )
        }
        refreshRxLinkCheck(debounceMs = 0L)
    }

    private fun setProposedSite(
        lat: Double,
        lon: Double,
        label: String,
        sourceNode: MeshNode?,
        markUserPositioned: Boolean
    ) {
        _uiState.update {
            it.copy(
                txLat = lat,
                txLon = lon,
                siteLabel = label,
                sourceNode = sourceNode,
                groundElevationMeters = null,
                errorMessage = null,
                isOfflineTileMissingError = false,
                hasUserPositionedSite = it.hasUserPositionedSite || markUserPositioned
            )
        }
        sampleGroundElevation(lat, lon)
        refreshRxLinkCheck(debounceMs = 0L)
    }

    private fun setRxSite(
        lat: Double,
        lon: Double,
        label: String,
        rxNode: MeshNode?,
        markUserPositioned: Boolean
    ) {
        _uiState.update {
            it.copy(
                rxLat = lat,
                rxLon = lon,
                rxLabel = label,
                rxNode = rxNode,
                rxGroundElevationMeters = null,
                errorMessage = null,
                isOfflineTileMissingError = false,
                hasUserPositionedRx = it.hasUserPositionedRx || markUserPositioned
            )
        }
        sampleRxGroundElevation(lat, lon)
        refreshRxLinkCheck(debounceMs = 0L)
    }

    private fun sampleGroundElevation(lat: Double, lon: Double) {
        groundSampleJob?.cancel()
        val job = viewModelScope.launch {
            try {
                _uiState.update { it.copy(isGroundElevLoading = true) }
                val result = TerrariumDemRepository.samplePointElevation(
                    context = getApplication(),
                    lat = lat,
                    lon = lon,
                    zoom = 12
                )
                result.onSuccess { elev ->
                    _uiState.update {
                        it.copy(
                            groundElevationMeters = elev,
                            isGroundElevLoading = false
                        )
                    }
                }.onFailure {
                    _uiState.update { it.copy(isGroundElevLoading = false) }
                }
            } catch (ce: CancellationException) {
                _uiState.update { it.copy(isGroundElevLoading = false) }
                throw ce
            }
        }
        groundSampleJob = job
        coordinator.registerJob(JOB_TX_ELEV, job)
    }

    private fun sampleRxGroundElevation(lat: Double, lon: Double) {
        rxGroundSampleJob?.cancel()
        val job = viewModelScope.launch {
            try {
                _uiState.update { it.copy(isRxElevLoading = true) }
                val result = TerrariumDemRepository.samplePointElevation(
                    context = getApplication(),
                    lat = lat,
                    lon = lon,
                    zoom = 12
                )
                result.onSuccess { elev ->
                    _uiState.update {
                        it.copy(
                            rxGroundElevationMeters = elev,
                            isRxElevLoading = false
                        )
                    }
                }.onFailure {
                    _uiState.update { it.copy(isRxElevLoading = false) }
                }
            } catch (ce: CancellationException) {
                _uiState.update { it.copy(isRxElevLoading = false) }
                throw ce
            }
        }
        rxGroundSampleJob = job
        coordinator.registerJob(JOB_RX_ELEV, job)
    }

    fun updateAntennaHeight(heightM: Float) {
        _uiState.update { it.copy(antennaHeightMeters = heightM.coerceIn(1f, 60f)) }
        refreshRxLinkCheck(debounceMs = 150L)
    }

    fun updateRxAntennaHeight(heightM: Float) {
        _uiState.update { it.copy(rxAntennaHeightMeters = heightM.coerceIn(1f, 60f)) }
        refreshRxLinkCheck(debounceMs = 150L)
    }

    fun applyMastFix(targetTxMeters: Float, targetRxMeters: Float) {
        val clampedTx = targetTxMeters.coerceIn(
            ViewshedFixAdvisor.MIN_MAST_HEIGHT_METERS.toFloat(),
            ViewshedFixAdvisor.MAX_MAST_HEIGHT_METERS.toFloat()
        )
        val clampedRx = targetRxMeters.coerceIn(
            ViewshedFixAdvisor.MIN_MAST_HEIGHT_METERS.toFloat(),
            ViewshedFixAdvisor.MAX_MAST_HEIGHT_METERS.toFloat()
        )
        val hadActiveViewshed = _uiState.value.currentResult != null && _uiState.value.hasTxSet
        _uiState.update {
            it.copy(
                antennaHeightMeters = clampedTx,
                rxAntennaHeightMeters = clampedRx,
                errorMessage = null
            )
        }
        refreshRxLinkCheck(debounceMs = 0L)
        if (hadActiveViewshed) {
            calculateCoverage(latestExistingNodes)
        }
    }

    fun updateRadiusKm(radiusKm: Int) {
        val clamped = radiusKm.coerceIn(1, ViewshedEngine.MAX_SUPPORTED_RADIUS_KM)
        val previous = _uiState.value
        if (previous.radiusKm == clamped) return
        _uiState.update { it.copy(radiusKm = clamped) }
        if (previous.currentResult != null && previous.hasTxSet) {
            calculateCoverage(latestExistingNodes)
        }
    }

    fun updateFrequencyText(text: String) {
        _uiState.update { it.copy(frequencyText = text) }
        refreshRxLinkCheck(debounceMs = 200L)
    }

    fun updateTxPowerDbm(powerDbm: Float) {
        _uiState.update { it.copy(txPowerDbm = powerDbm) }
        refreshRxLinkCheck(debounceMs = 150L)
    }

    fun updateAntennaGainDbi(gainDbi: Float) {
        _uiState.update { it.copy(antennaGainDbi = gainDbi) }
        refreshRxLinkCheck(debounceMs = 150L)
    }

    fun updateSensitivityDbm(sensitivityDbm: Float) {
        _uiState.update { it.copy(sensitivityDbm = sensitivityDbm) }
        refreshRxLinkCheck(debounceMs = 150L)
    }

    fun updateOverlayOpacity(opacity: Float) {
        val clamped = opacity.coerceIn(0.05f, 1.0f)
        viewportHolder.updateOverlayOpacity(clamped)
        _uiState.update { it.copy(overlayOpacity = clamped) }
    }

    fun togglePinSnapshot() {
        _uiState.update { state ->
            if (!state.isSnapshotPinned) {
                val active = state.currentResult ?: return@update state
                state.copy(
                    isSnapshotPinned = true,
                    pinnedSnapshot = active
                )
            } else {
                state.copy(
                    isSnapshotPinned = false,
                    pinnedSnapshot = null,
                    showSnapshotOnMap = false
                )
            }
        }
    }

    fun toggleShowSnapshotOnMap() {
        _uiState.update { state ->
            if (state.pinnedSnapshot == null) state
            else state.copy(showSnapshotOnMap = !state.showSnapshotOnMap)
        }
    }

    private fun refreshRxLinkCheck(debounceMs: Long) {
        rxLinkJob?.cancel()
        val snapshot = _uiState.value
        val tLat = snapshot.txLat
        val tLon = snapshot.txLon
        val rLat = snapshot.rxLat
        val rLon = snapshot.rxLon
        if (tLat == null || tLon == null || rLat == null || rLon == null) {
            _uiState.update { it.copy(rxLinkResult = null, isRxLinkLoading = false) }
            return
        }

        val job = viewModelScope.launch {
            try {
                if (debounceMs > 0L) delay(debounceMs)
                val state = _uiState.value
                val curTLat = state.txLat ?: return@launch
                val curTLon = state.txLon ?: return@launch
                val curRLat = state.rxLat ?: return@launch
                val curRLon = state.rxLon ?: return@launch

                _uiState.update { it.copy(isRxLinkLoading = true) }

                val north = max(curTLat, curRLat) + 0.01
                val south = min(curTLat, curRLat) - 0.01
                val west = min(curTLon, curRLon) - 0.01
                val east = max(curTLon, curRLon) + 0.01
                val zoom = TerrariumDemRepository.recommendedZoomForBounds(
                    northLat = north,
                    southLat = south,
                    westLon = west,
                    eastLon = east,
                    preferredRadiusKm = state.radiusKm
                )

                val gridResult = TerrariumDemRepository.loadTileGridForBounds(
                    context = getApplication(),
                    northLat = north,
                    southLat = south,
                    westLon = west,
                    eastLon = east,
                    zoom = zoom
                )

                gridResult.onSuccess { grid ->
                    OfflineDemPackManager.refreshCacheStats(getApplication())
                    val link = withContext(Dispatchers.Default) {
                        evaluateRxLinkOnGrid(state, grid)
                    }
                    _uiState.update {
                        it.copy(
                            isRxLinkLoading = false,
                            rxLinkResult = link,
                            groundElevationMeters = link?.txGroundElevationMeters ?: it.groundElevationMeters,
                            rxGroundElevationMeters = link?.rxGroundElevationMeters ?: it.rxGroundElevationMeters,
                            isOfflineTileMissingError = false
                        )
                    }
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isRxLinkLoading = false,
                            errorMessage = if (err is OfflineTileMissingException) err.message else it.errorMessage,
                            isOfflineTileMissingError = it.isOfflineTileMissingError || err is OfflineTileMissingException
                        )
                    }
                }
            } catch (ce: CancellationException) {
                _uiState.update { it.copy(isRxLinkLoading = false) }
                throw ce
            }
        }
        rxLinkJob = job
        coordinator.registerJob(JOB_RX_LINK, job)
    }

    internal fun evaluateRxLinkOnGrid(
        state: ViewshedUiState,
        grid: TerrariumTileGrid
    ): ViewshedRxLinkResult? {
        val tLat = state.txLat ?: return null
        val tLon = state.txLon ?: return null
        val rLat = state.rxLat ?: return null
        val rLon = state.rxLon ?: return null

        val freqMhz = state.frequencyText.toDoubleOrNull()?.takeIf { it > 0.0 }
            ?: LosAnalyzer.DEFAULT_LORA_US_FREQ_MHZ
        val freqGhz = freqMhz / 1000.0
        val eirpDbm = state.txPowerDbm.toDouble() + state.antennaGainDbi.toDouble()
        val sensitivity = state.sensitivityDbm.toDouble()

        val fadeMarginDb = state.fadeMarginPreset.fadeMarginDb
        val hop = RelayBridgeEngine.evaluateHop(
            latA = tLat,
            lonA = tLon,
            heightAMeters = state.antennaHeightMeters.toDouble(),
            latB = rLat,
            lonB = rLon,
            heightBMeters = state.rxAntennaHeightMeters.toDouble(),
            freqMhz = freqMhz,
            freqGhz = freqGhz,
            eirpDbm = eirpDbm,
            sensitivityDbm = sensitivity,
            grid = grid,
            fadeMarginDb = fadeMarginDb
        )
        val txElev = grid.sampleElevation(tLat, tLon) ?: 0.0
        val rxElev = grid.sampleElevation(rLat, rLon) ?: 0.0
        val bearing = MeshGeoUtils.bearingDegrees(tLat, tLon, rLat, rLon)
        val fixPlan = ViewshedFixAdvisor.analyzeHopFixes(
            txLat = tLat,
            txLon = tLon,
            currentTxMeters = state.antennaHeightMeters.toDouble(),
            rxLat = rLat,
            rxLon = rLon,
            currentRxMeters = state.rxAntennaHeightMeters.toDouble(),
            freqMhz = freqMhz,
            eirpDbm = eirpDbm - fadeMarginDb,
            sensitivityDbm = sensitivity,
            grid = grid,
            currentHop = hop
        )

        return ViewshedRxLinkResult(
            txLat = tLat,
            txLon = tLon,
            rxLat = rLat,
            rxLon = rLon,
            bearingDegrees = bearing,
            hop = hop,
            txGroundElevationMeters = txElev,
            rxGroundElevationMeters = rxElev,
            fixPlan = fixPlan
        )
    }

    fun calculateCoverage(existingNodes: List<MeshNode>) {
        latestExistingNodes = existingNodes
        val state = _uiState.value
        val tLat = state.txLat
        val tLon = state.txLon
        if (tLat == null || tLon == null) {
            _uiState.update {
                it.copy(errorMessage = "Place a TX site on the map before calculating coverage.")
            }
            return
        }

        val freq = state.frequencyText.toDoubleOrNull()?.takeIf { it > 0.0 }
            ?: LosAnalyzer.DEFAULT_LORA_US_FREQ_MHZ

        val params = ViewshedParams(
            originLat = tLat,
            originLon = tLon,
            originLabel = state.siteLabel,
            sourceNode = state.sourceNode,
            antennaHeightMeters = state.antennaHeightMeters.toDouble(),
            radiusKm = state.radiusKm.coerceIn(1, ViewshedEngine.MAX_SUPPORTED_RADIUS_KM),
            frequencyMhz = freq,
            txPowerDbm = state.txPowerDbm.toDouble(),
            antennaGainDbi = state.antennaGainDbi.toDouble(),
            sensitivityDbm = state.sensitivityDbm.toDouble(),
            fadeMarginDb = state.fadeMarginPreset.fadeMarginDb
        )

        calculateJob?.cancel()
        val job = viewModelScope.launch {
            try {
                val bounds = TerrariumDemRepository.paddedBoundsForRadius(
                    originLat = params.originLat,
                    originLon = params.originLon,
                    radiusKm = params.radiusKm.toDouble()
                )
                val zoom = TerrariumDemRepository.recommendedZoomForBounds(
                    northLat = bounds[0],
                    southLat = bounds[1],
                    westLon = bounds[2],
                    eastLon = bounds[3],
                    preferredRadiusKm = params.radiusKm
                )
                _uiState.update {
                    it.copy(
                        isCalculating = true,
                        errorMessage = null,
                        isOfflineTileMissingError = false,
                        statusNote = "Fetching AWS Terrarium DEM tiles (z$zoom)…"
                    )
                }

                val gridResult = TerrariumDemRepository.loadTileGridForBounds(
                    context = getApplication(),
                    northLat = bounds[0],
                    southLat = bounds[1],
                    westLon = bounds[2],
                    eastLon = bounds[3],
                    zoom = zoom
                )

                gridResult.onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isCalculating = false,
                            statusNote = null,
                            errorMessage = err.message ?: "Failed to fetch Terrarium DEM tiles.",
                            isOfflineTileMissingError = err is OfflineTileMissingException
                        )
                    }
                    return@launch
                }

                val grid = gridResult.getOrThrow()
                OfflineDemPackManager.refreshCacheStats(getApplication())
                _uiState.update {
                    it.copy(statusNote = "Raycasting 720 radials across ${grid.tileCount} DEM tiles…")
                }

                val result = withContext(Dispatchers.Default) {
                    ViewshedEngine.computeViewshed(
                        params = params,
                        grid = grid,
                        existingNodes = existingNodes
                    )
                }

                // Compute TX->RX link check using the loaded grid when RX falls inside radius, or fetch path bounds otherwise
                val rxLat = state.rxLat
                val rxLon = state.rxLon
                val linkResult: ViewshedRxLinkResult? = if (rxLat != null && rxLon != null) {
                    val distKm = MeshGeoUtils.haversineKm(tLat, tLon, rxLat, rxLon)
                    val linkGrid = if (distKm <= state.radiusKm) {
                        grid
                    } else {
                        val bZoom = TerrariumDemRepository.recommendedZoomForBounds(
                            northLat = max(tLat, rxLat) + 0.01,
                            southLat = min(tLat, rxLat) - 0.01,
                            westLon = min(tLon, rxLon) - 0.01,
                            eastLon = max(tLon, rxLon) + 0.01,
                            preferredRadiusKm = state.radiusKm
                        )
                        TerrariumDemRepository.loadTileGridForBounds(
                            context = getApplication(),
                            northLat = max(tLat, rxLat) + 0.01,
                            southLat = min(tLat, rxLat) - 0.01,
                            westLon = min(tLon, rxLon) - 0.01,
                            eastLon = max(tLon, rxLon) + 0.01,
                            zoom = bZoom
                        ).getOrNull() ?: grid
                    }
                    withContext(Dispatchers.Default) {
                        evaluateRxLinkOnGrid(state, linkGrid)
                    }
                } else {
                    null
                }

                _uiState.update {
                    it.copy(
                        isCalculating = false,
                        statusNote = null,
                        errorMessage = null,
                        groundElevationMeters = result.groundElevationMeters,
                        currentResult = result,
                        rxLinkResult = linkResult ?: it.rxLinkResult,
                        rxGroundElevationMeters = linkResult?.rxGroundElevationMeters ?: it.rxGroundElevationMeters
                    )
                }
            } catch (ce: CancellationException) {
                _uiState.update { it.copy(isCalculating = false, statusNote = null) }
                throw ce
            }
        }
        calculateJob = job
        coordinator.registerJob(JOB_CALCULATE, job)
    }

    fun applyNodeHandoffAndCalculate(
        node: MeshNode,
        existingNodes: List<MeshNode>
    ): Boolean {
        val lat = node.latitude ?: return false
        val lon = node.longitude ?: return false
        if (!node.hasValidGps) return false

        latestExistingNodes = existingNodes
        val preset = RfWorkspaceCoordinator.deriveNodeRfPreset(node)
        val label = "${node.displayLongName} (${node.displayShortName})"

        calculateJob?.cancel()
        rxLinkJob?.cancel()

        _uiState.update { state ->
            val clearRxIfSame = state.rxNode?.stableKey == node.stableKey
            state.copy(
                txLat = lat,
                txLon = lon,
                siteLabel = label,
                sourceNode = node,
                groundElevationMeters = null,
                antennaHeightMeters = preset.mastHeightMeters,
                txPowerDbm = preset.txPowerDbm,
                antennaGainDbi = preset.antennaGainDbi,
                sensitivityDbm = preset.sensitivityDbm,
                rxLat = if (clearRxIfSame) null else state.rxLat,
                rxLon = if (clearRxIfSame) null else state.rxLon,
                rxLabel = if (clearRxIfSame) "Tap or drop RX" else state.rxLabel,
                rxNode = if (clearRxIfSame) null else state.rxNode,
                rxGroundElevationMeters = if (clearRxIfSame) null else state.rxGroundElevationMeters,
                rxLinkResult = if (clearRxIfSame) null else state.rxLinkResult,
                hasUserPositionedSite = true,
                nextAutoTarget = ViewshedPlacementTarget.RX,
                errorMessage = null
            )
        }

        coordinator.recordHandoff(node, WorkspaceMode.VIEWSHED)
        sampleGroundElevation(lat, lon)
        viewportHolder.requestCenterFocus(lat, lon, minZoom = 11.5)
        calculateCoverage(existingNodes)
        return true
    }

    fun cancelActiveCalculations() {
        calculateJob?.cancel()
        rxLinkJob?.cancel()
        groundSampleJob?.cancel()
        rxGroundSampleJob?.cancel()
        coordinator.cancelJobsWithPrefix("viewshed_")
        _uiState.update {
            it.copy(
                isCalculating = false,
                isRxLinkLoading = false,
                isGroundElevLoading = false,
                isRxElevLoading = false,
                statusNote = null
            )
        }
    }

    fun suggestDefaultLayerName(): String {
        val state = _uiState.value
        val result = state.currentResult
        val label = (result?.params?.originLabel ?: state.siteLabel).take(22)
        val heightM = (result?.params?.antennaHeightMeters ?: state.antennaHeightMeters.toDouble()).roundToInt()
        val radiusKm = result?.params?.radiusKm ?: state.radiusKm
        return "$label • ${heightM}m • ${radiusKm}km"
    }

    fun suggestNextLayerTint(): Int {
        val used = _uiState.value.savedLayers.map { it.tintColorArgb }.toSet()
        return LAYER_TINT_PALETTE.firstOrNull { it !in used }
            ?: LAYER_TINT_PALETTE[_uiState.value.savedLayers.size % LAYER_TINT_PALETTE.size]
    }

    fun saveCurrentViewshedAsLayer(
        name: String,
        tintColorArgb: Int,
        renderMode: LayerRenderMode
    ) {
        val result = _uiState.value.currentResult ?: return
        viewModelScope.launch {
            val saved = ViewshedLayerRepository.saveNewLayer(
                context = getApplication(),
                name = name,
                result = result,
                tintColorArgb = tintColorArgb,
                renderMode = renderMode,
                existingLayers = _uiState.value.savedLayers
            )
            _uiState.update { state ->
                state.copy(savedLayers = listOf(saved) + state.savedLayers)
            }
        }
    }

    fun toggleSavedLayerEnabled(layerId: String) {
        val updated = _uiState.value.savedLayers.map { layer ->
            if (layer.id == layerId) layer.copy(isEnabled = !layer.isEnabled) else layer
        }
        _uiState.update { it.copy(savedLayers = updated) }
        viewModelScope.launch {
            ViewshedLayerRepository.persistMetadata(getApplication(), updated)
        }
    }

    fun toggleSavedLayerRenderMode(layerId: String) {
        val current = _uiState.value.savedLayers
        val target = current.firstOrNull { it.id == layerId } ?: return
        val nextMode = if (target.renderMode == LayerRenderMode.TINT) {
            LayerRenderMode.RSSI
        } else {
            LayerRenderMode.TINT
        }

        viewModelScope.launch {
            val tinted = if (nextMode == LayerRenderMode.TINT && target.tintedBitmap == null) {
                withContext(Dispatchers.Default) {
                    createTintedLayerBitmap(target.rssiBitmap, target.tintColorArgb)
                }
            } else {
                target.tintedBitmap
            }
            val updated = _uiState.value.savedLayers.map { layer ->
                if (layer.id == layerId) {
                    layer.copy(renderMode = nextMode, tintedBitmap = tinted)
                } else {
                    layer
                }
            }
            _uiState.update { it.copy(savedLayers = updated) }
            ViewshedLayerRepository.persistMetadata(getApplication(), updated)
        }
    }

    fun deleteSavedLayer(layerId: String) {
        val remaining = _uiState.value.savedLayers.filterNot { it.id == layerId }
        _uiState.update { it.copy(savedLayers = remaining) }
        viewModelScope.launch {
            ViewshedLayerRepository.deleteLayer(getApplication(), layerId, remaining)
        }
    }

    fun clearCurrentViewshed() {
        calculateJob?.cancel()
        rxLinkJob?.cancel()
        _uiState.update {
            it.copy(
                isCalculating = false,
                isRxLinkLoading = false,
                statusNote = null,
                errorMessage = null,
                currentResult = null,
                rxLinkResult = null
            )
        }
    }

    fun clearAllAndStartFresh() {
        calculateJob?.cancel()
        rxLinkJob?.cancel()
        groundSampleJob?.cancel()
        rxGroundSampleJob?.cancel()
        val hiddenLayers = _uiState.value.savedLayers.map { layer ->
            if (layer.isEnabled) layer.copy(isEnabled = false) else layer
        }
        _uiState.update {
            it.copy(
                txLat = null,
                txLon = null,
                siteLabel = "Tap or drop TX",
                sourceNode = null,
                groundElevationMeters = null,
                isGroundElevLoading = false,
                rxLat = null,
                rxLon = null,
                rxLabel = "Tap or drop RX",
                rxNode = null,
                rxGroundElevationMeters = null,
                isRxElevLoading = false,
                placementMode = ViewshedPlacementMode.AUTO,
                nextAutoTarget = ViewshedPlacementTarget.TX,
                isCalculating = false,
                isRxLinkLoading = false,
                statusNote = null,
                errorMessage = null,
                currentResult = null,
                rxLinkResult = null,
                pinnedSnapshot = null,
                isSnapshotPinned = false,
                showSnapshotOnMap = false,
                hasUserPositionedSite = true,
                hasUserPositionedRx = true,
                savedLayers = hiddenLayers
            )
        }
        viewModelScope.launch {
            ViewshedLayerRepository.persistMetadata(getApplication(), hiddenLayers)
        }
    }

    fun resetForFreshNodeDb() {
        clearAllAndStartFresh()
        _uiState.update {
            it.copy(
                hasUserPositionedSite = false,
                hasUserPositionedRx = false
            )
        }
    }

    fun toggleTopBarExpanded() {
        _uiState.update { it.copy(isTopBarExpanded = !it.isTopBarExpanded) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null, isOfflineTileMissingError = false) }
    }

    private companion object {
        const val JOB_TX_ELEV = "viewshed_transient_tx_elev"
        const val JOB_RX_ELEV = "viewshed_transient_rx_elev"
        const val JOB_RX_LINK = "viewshed_transient_rx_link"
        const val JOB_CALCULATE = "viewshed_calculate"
    }
}
