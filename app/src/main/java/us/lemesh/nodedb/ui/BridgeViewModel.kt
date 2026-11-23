package us.lemesh.nodedb.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import us.lemesh.nodedb.data.BridgeHopEvaluation
import us.lemesh.nodedb.data.BridgeParams
import us.lemesh.nodedb.data.BridgeProfileMode
import us.lemesh.nodedb.data.BridgeProfileSample
import us.lemesh.nodedb.data.BridgeResult
import us.lemesh.nodedb.data.FadeMarginPreset
import us.lemesh.nodedb.data.LosAnalyzer
import us.lemesh.nodedb.data.MeshGeoUtils
import us.lemesh.nodedb.data.MeshNode
import us.lemesh.nodedb.data.OfflineDemPackManager
import us.lemesh.nodedb.data.OfflineTileMissingException
import us.lemesh.nodedb.data.RelayBridgeEngine
import us.lemesh.nodedb.data.RfEnvironmentStateHolder
import us.lemesh.nodedb.data.TerrariumDemRepository
import us.lemesh.nodedb.data.TerrariumTileGrid
import us.lemesh.nodedb.data.ViewshedEngine
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

enum class BridgePlacementTarget(val label: String) {
    TX("TX Site"),
    RX("RX Site")
}

enum class BridgePlacementMode(val label: String) {
    AUTO("Auto"),
    TX("TX"),
    RX("RX")
}

enum class BridgeDrawerTab(val label: String) {
    RESULTS("Results"),
    OBSTRUCTIONS("Obstructions")
}

data class BridgeUiState(
    val txLat: Double? = 42.1292,
    val txLon: Double? = -80.0851,
    val txLabel: String = "TX Site (Erie, PA)",
    val txNode: MeshNode? = null,
    val txGroundElevationMeters: Double? = null,
    val isTxElevLoading: Boolean = false,
    val rxLat: Double? = 42.1950,
    val rxLon: Double? = -79.9650,
    val rxLabel: String = "RX Site (Northeast)",
    val rxNode: MeshNode? = null,
    val rxGroundElevationMeters: Double? = null,
    val isRxElevLoading: Boolean = false,
    val placementMode: BridgePlacementMode = BridgePlacementMode.AUTO,
    val nextAutoTarget: BridgePlacementTarget = BridgePlacementTarget.TX,
    val txAntennaHeightMeters: Float = 10f,
    val rxAntennaHeightMeters: Float = 10f,
    val relayAntennaHeightMeters: Float = 15f,
    val radiusKm: Int = 25,
    val frequencyText: String = LosAnalyzer.DEFAULT_LORA_US_FREQ_MHZ.toString(),
    val txPowerDbm: Float = RelayBridgeEngine.DEFAULT_TX_POWER_DBM.toFloat(),
    val txAntennaGainDbi: Float = RelayBridgeEngine.DEFAULT_ANTENNA_GAIN_DBI.toFloat(),
    val txSensitivityDbm: Float = RelayBridgeEngine.DEFAULT_SENSITIVITY_DBM.toFloat(),
    val rxPowerDbm: Float = RelayBridgeEngine.DEFAULT_TX_POWER_DBM.toFloat(),
    val rxAntennaGainDbi: Float = RelayBridgeEngine.DEFAULT_ANTENNA_GAIN_DBI.toFloat(),
    val rxSensitivityDbm: Float = RelayBridgeEngine.DEFAULT_SENSITIVITY_DBM.toFloat(),
    val relayPowerDbm: Float = RelayBridgeEngine.DEFAULT_TX_POWER_DBM.toFloat(),
    val relayAntennaGainDbi: Float = RelayBridgeEngine.DEFAULT_ANTENNA_GAIN_DBI.toFloat(),
    val relaySensitivityDbm: Float = RelayBridgeEngine.DEFAULT_SENSITIVITY_DBM.toFloat(),
    val fadeMarginPreset: FadeMarginPreset = FadeMarginPreset.WINTER_CLEAR,
    val overlayOpacity: Float = 0.72f,
    val isCalculating: Boolean = false,
    val statusNote: String? = null,
    val errorMessage: String? = null,
    val isOfflineTileMissingError: Boolean = false,
    val currentResult: BridgeResult? = null,
    val isViewshedStale: Boolean = false,
    val selectedCandidateIndex: Int = 0,
    val activeDrawerTab: BridgeDrawerTab = BridgeDrawerTab.RESULTS,
    val selectedObstructionId: String? = null,
    val obstructionFocusNonce: Long = 0L,
    val liveDirectHop: BridgeHopEvaluation? = null,
    val isLiveProfileLoading: Boolean = false,
    val liveProfileError: String? = null,
    val isLiveProfileOfflineMissingError: Boolean = false,
    val profileMode: BridgeProfileMode = BridgeProfileMode.DIRECT,
    val scrubbedProfileSample: BridgeProfileSample? = null,
    val hasUserPositionedTx: Boolean = false,
    val hasUserPositionedRx: Boolean = false,
    val isTopBarExpanded: Boolean = true
) {
    val antennaGainDbi: Float
        get() = txAntennaGainDbi

    val sensitivityDbm: Float
        get() = txSensitivityDbm

    val hasTxSet: Boolean
        get() = txLat != null && txLon != null

    val hasRxSet: Boolean
        get() = rxLat != null && rxLon != null

    val areBothEndpointsSet: Boolean
        get() = hasTxSet && hasRxSet

    val hasAnyOverlayOrPins: Boolean
        get() = currentResult != null || hasTxSet || hasRxSet

    val effectivePlacementTarget: BridgePlacementTarget
        get() = when (placementMode) {
            BridgePlacementMode.AUTO -> nextAutoTarget
            BridgePlacementMode.TX -> BridgePlacementTarget.TX
            BridgePlacementMode.RX -> BridgePlacementTarget.RX
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
}

class BridgeViewModel @JvmOverloads constructor(
    application: Application,
    val viewportHolder: MapViewportStateHolder = MapViewportStateHolder(),
    val coordinator: RfWorkspaceCoordinator = RfWorkspaceCoordinator(),
    val environmentHolder: RfEnvironmentStateHolder = RfEnvironmentStateHolder()
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(BridgeUiState())
    val uiState: StateFlow<BridgeUiState> = _uiState.asStateFlow()

    private var txElevJob: Job? = null
    private var rxElevJob: Job? = null
    private var calculateJob: Job? = null
    private var liveResampleJob: Job? = null
    private var liveDirectProfileJob: Job? = null
    private var cachedGrid: TerrariumTileGrid? = null
    private var cachedCorridorGrid: TerrariumTileGrid? = null

    internal var corridorTileGridProvider: (suspend (Double, Double, Double, Double, Int) -> Result<TerrariumTileGrid>)? = null

    init {
        OfflineDemPackManager.initialize(getApplication())
        sampleTxElevation(42.1292, -80.0851)
        sampleRxElevation(42.1950, -79.9650)
        scheduleLiveDirectProfile(delayMs = 260L)
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
                    _uiState.update {
                        it.copy(
                            fadeMarginPreset = preset,
                            isViewshedStale = it.currentResult != null
                        )
                    }
                    scheduleLiveHopResample()
                }
            }
        }
    }

    fun setFadeMarginPreset(preset: FadeMarginPreset) {
        environmentHolder.setFadeMarginPreset(preset)
    }

    fun syncDefaultsFromNodesIfUnset(selfNode: MeshNode?, allNodes: List<MeshNode>) {
        val gpsNodes = allNodes.filter { it.hasValidGps }
        if (gpsNodes.isEmpty()) return

        val state = _uiState.value
        if (!state.hasUserPositionedTx) {
            val defaultTx = selfNode?.takeIf { it.hasValidGps } ?: gpsNodes.first()
            setTxSite(
                lat = defaultTx.latitude!!,
                lon = defaultTx.longitude!!,
                label = "${defaultTx.displayShortName} (${defaultTx.displayLongName})",
                node = defaultTx,
                markUserPositioned = false
            )
        }

        if (!state.hasUserPositionedRx) {
            val chosenTx = selfNode?.takeIf { it.hasValidGps } ?: gpsNodes.first()
            val candidatesForRx = gpsNodes.filter { it.id != chosenTx.id || it.num != chosenTx.num }
            val defaultRx = candidatesForRx.maxByOrNull { node ->
                MeshGeoUtils.haversineKm(
                    chosenTx.latitude!!,
                    chosenTx.longitude!!,
                    node.latitude!!,
                    node.longitude!!
                )
            }
            if (defaultRx != null) {
                setRxSite(
                    lat = defaultRx.latitude!!,
                    lon = defaultRx.longitude!!,
                    label = "${defaultRx.displayShortName} (${defaultRx.displayLongName})",
                    node = defaultRx,
                    markUserPositioned = false
                )
            }
        }
    }

    fun setPlacementMode(mode: BridgePlacementMode) {
        _uiState.update { state ->
            val nextAuto = when (mode) {
                BridgePlacementMode.AUTO -> when {
                    !state.hasTxSet -> BridgePlacementTarget.TX
                    !state.hasRxSet -> BridgePlacementTarget.RX
                    else -> state.nextAutoTarget
                }
                BridgePlacementMode.TX -> BridgePlacementTarget.TX
                BridgePlacementMode.RX -> BridgePlacementTarget.RX
            }
            state.copy(placementMode = mode, nextAutoTarget = nextAuto)
        }
    }

    fun setPlacementTarget(target: BridgePlacementTarget) {
        _uiState.update {
            it.copy(
                placementMode = if (target == BridgePlacementTarget.TX) {
                    BridgePlacementMode.TX
                } else {
                    BridgePlacementMode.RX
                },
                nextAutoTarget = target
            )
        }
    }

    fun selectNodeForActiveTarget(node: MeshNode) {
        if (!node.hasValidGps) return
        val state = _uiState.value
        when (state.effectivePlacementTarget) {
            BridgePlacementTarget.TX -> {
                selectTxNode(node)
                advanceAutoTargetAfter(BridgePlacementTarget.TX)
            }
            BridgePlacementTarget.RX -> {
                selectRxNode(node)
                advanceAutoTargetAfter(BridgePlacementTarget.RX)
            }
        }
    }

    fun setCoordinateForActiveTarget(lat: Double, lon: Double) {
        val state = _uiState.value
        when (state.effectivePlacementTarget) {
            BridgePlacementTarget.TX -> {
                setTxCustomCoordinate(lat, lon)
                advanceAutoTargetAfter(BridgePlacementTarget.TX)
            }
            BridgePlacementTarget.RX -> {
                setRxCustomCoordinate(lat, lon)
                advanceAutoTargetAfter(BridgePlacementTarget.RX)
            }
        }
    }

    private fun advanceAutoTargetAfter(placed: BridgePlacementTarget) {
        _uiState.update { state ->
            if (state.placementMode == BridgePlacementMode.AUTO) {
                val next = if (placed == BridgePlacementTarget.TX) {
                    BridgePlacementTarget.RX
                } else {
                    BridgePlacementTarget.TX
                }
                state.copy(nextAutoTarget = next)
            } else {
                state
            }
        }
    }

    fun selectTxNode(node: MeshNode) {
        if (!node.hasValidGps) return
        setTxSite(
            lat = node.latitude!!,
            lon = node.longitude!!,
            label = "${node.displayShortName} (${node.displayLongName})",
            node = node,
            markUserPositioned = true
        )
    }

    fun selectRxNode(node: MeshNode) {
        if (!node.hasValidGps) return
        setRxSite(
            lat = node.latitude!!,
            lon = node.longitude!!,
            label = "${node.displayShortName} (${node.displayLongName})",
            node = node,
            markUserPositioned = true
        )
    }

    fun setTxCustomCoordinate(lat: Double, lon: Double) {
        setTxSite(lat, lon, "TX Pin", null, markUserPositioned = true)
    }

    fun setRxCustomCoordinate(lat: Double, lon: Double) {
        setRxSite(lat, lon, "RX Pin", null, markUserPositioned = true)
    }

    fun swapTxAndRx() {
        val s = _uiState.value
        if (!s.hasTxSet && !s.hasRxSet) return
        _uiState.update {
            it.copy(
                txLat = s.rxLat,
                txLon = s.rxLon,
                txLabel = if (s.hasRxSet) s.rxLabel else "Tap or drop TX",
                txNode = s.rxNode,
                txGroundElevationMeters = s.rxGroundElevationMeters,
                txAntennaHeightMeters = s.rxAntennaHeightMeters,
                txPowerDbm = s.rxPowerDbm,
                txAntennaGainDbi = s.rxAntennaGainDbi,
                txSensitivityDbm = s.rxSensitivityDbm,
                rxLat = s.txLat,
                rxLon = s.txLon,
                rxLabel = if (s.hasTxSet) s.txLabel else "Tap or drop RX",
                rxNode = s.txNode,
                rxGroundElevationMeters = s.txGroundElevationMeters,
                rxAntennaHeightMeters = s.txAntennaHeightMeters,
                rxPowerDbm = s.txPowerDbm,
                rxAntennaGainDbi = s.txAntennaGainDbi,
                rxSensitivityDbm = s.txSensitivityDbm,
                isViewshedStale = s.currentResult != null,
                hasUserPositionedTx = true,
                hasUserPositionedRx = true,
                scrubbedProfileSample = null,
                errorMessage = null
            )
        }
        scheduleLiveDirectProfile()
    }

    fun importFromViewshed(
        viewshedState: ViewshedUiState,
        autoSolve: Boolean = true
    ): Boolean {
        val tLat = viewshedState.txLat
        val tLon = viewshedState.txLon
        val rLat = viewshedState.rxLat
        val rLon = viewshedState.rxLon
        if (tLat == null || tLon == null || rLat == null || rLon == null) {
            return false
        }

        calculateJob?.cancel()
        liveResampleJob?.cancel()
        liveDirectProfileJob?.cancel()
        txElevJob?.cancel()
        rxElevJob?.cancel()
        cachedGrid = null
        cachedCorridorGrid = null

        val txLabel = viewshedState.siteLabel.takeUnless { it.startsWith("Tap or drop") } ?: "TX Site"
        val rxLabel = viewshedState.rxLabel.takeUnless { it.startsWith("Tap or drop") } ?: "RX Site"

        _uiState.update { state ->
            state.copy(
                txLat = tLat,
                txLon = tLon,
                txLabel = txLabel,
                txNode = viewshedState.sourceNode,
                txGroundElevationMeters = viewshedState.groundElevationMeters,
                isTxElevLoading = false,
                rxLat = rLat,
                rxLon = rLon,
                rxLabel = rxLabel,
                rxNode = viewshedState.rxNode,
                rxGroundElevationMeters = viewshedState.rxGroundElevationMeters,
                isRxElevLoading = false,
                placementMode = BridgePlacementMode.AUTO,
                nextAutoTarget = BridgePlacementTarget.TX,
                txAntennaHeightMeters = viewshedState.antennaHeightMeters.coerceIn(1f, 60f),
                rxAntennaHeightMeters = viewshedState.rxAntennaHeightMeters.coerceIn(1f, 60f),
                radiusKm = viewshedState.radiusKm.coerceIn(1, ViewshedEngine.MAX_SUPPORTED_RADIUS_KM),
                frequencyText = viewshedState.frequencyText,
                txPowerDbm = viewshedState.txPowerDbm.coerceIn(10f, 37f),
                txAntennaGainDbi = viewshedState.antennaGainDbi.coerceIn(0f, 15f),
                txSensitivityDbm = viewshedState.sensitivityDbm.coerceIn(-140f, -100f),
                overlayOpacity = viewshedState.overlayOpacity.coerceIn(0.05f, 1.0f),
                isCalculating = false,
                statusNote = null,
                errorMessage = null,
                currentResult = null,
                isViewshedStale = false,
                selectedCandidateIndex = 0,
                activeDrawerTab = BridgeDrawerTab.RESULTS,
                selectedObstructionId = null,
                liveDirectHop = null,
                isLiveProfileLoading = false,
                liveProfileError = null,
                profileMode = BridgeProfileMode.DIRECT,
                scrubbedProfileSample = null,
                hasUserPositionedTx = true,
                hasUserPositionedRx = true
            )
        }

        if (viewshedState.groundElevationMeters == null) {
            sampleTxElevation(tLat, tLon)
        }
        if (viewshedState.rxGroundElevationMeters == null) {
            sampleRxElevation(rLat, rLon)
        }

        if (autoSolve) {
            calculateBridge()
        } else {
            scheduleLiveDirectProfile(delayMs = 0L)
        }
        return true
    }

    fun clearBridgeOverlay() {
        calculateJob?.cancel()
        liveResampleJob?.cancel()
        cachedGrid = null
        _uiState.update {
            it.copy(
                isCalculating = false,
                statusNote = null,
                errorMessage = null,
                currentResult = null,
                isViewshedStale = false,
                selectedCandidateIndex = 0,
                selectedObstructionId = null,
                profileMode = BridgeProfileMode.DIRECT,
                scrubbedProfileSample = null
            )
        }
        if (_uiState.value.areBothEndpointsSet && _uiState.value.liveDirectHop == null) {
            scheduleLiveDirectProfile(delayMs = 0L)
        }
    }

    fun resetBridgeAll() {
        calculateJob?.cancel()
        liveResampleJob?.cancel()
        liveDirectProfileJob?.cancel()
        txElevJob?.cancel()
        rxElevJob?.cancel()
        cachedGrid = null
        cachedCorridorGrid = null
        _uiState.update {
            it.copy(
                txLat = null,
                txLon = null,
                txLabel = "Tap or drop TX",
                txNode = null,
                txGroundElevationMeters = null,
                isTxElevLoading = false,
                rxLat = null,
                rxLon = null,
                rxLabel = "Tap or drop RX",
                rxNode = null,
                rxGroundElevationMeters = null,
                isRxElevLoading = false,
                placementMode = BridgePlacementMode.AUTO,
                nextAutoTarget = BridgePlacementTarget.TX,
                isCalculating = false,
                statusNote = null,
                errorMessage = null,
                currentResult = null,
                isViewshedStale = false,
                selectedCandidateIndex = 0,
                activeDrawerTab = BridgeDrawerTab.RESULTS,
                selectedObstructionId = null,
                liveDirectHop = null,
                isLiveProfileLoading = false,
                liveProfileError = null,
                profileMode = BridgeProfileMode.DIRECT,
                scrubbedProfileSample = null,
                hasUserPositionedTx = true,
                hasUserPositionedRx = true
            )
        }
    }

    fun resetForFreshNodeDb() {
        resetBridgeAll()
        _uiState.update {
            it.copy(
                hasUserPositionedTx = false,
                hasUserPositionedRx = false
            )
        }
    }

    private fun setTxSite(
        lat: Double,
        lon: Double,
        label: String,
        node: MeshNode?,
        markUserPositioned: Boolean
    ) {
        _uiState.update {
            it.copy(
                txLat = lat,
                txLon = lon,
                txLabel = label,
                txNode = node,
                txGroundElevationMeters = null,
                scrubbedProfileSample = null,
                errorMessage = null,
                hasUserPositionedTx = it.hasUserPositionedTx || markUserPositioned
            )
        }
        sampleTxElevation(lat, lon)
        scheduleLiveDirectProfile()
    }

    private fun setRxSite(
        lat: Double,
        lon: Double,
        label: String,
        node: MeshNode?,
        markUserPositioned: Boolean
    ) {
        _uiState.update {
            it.copy(
                rxLat = lat,
                rxLon = lon,
                rxLabel = label,
                rxNode = node,
                rxGroundElevationMeters = null,
                scrubbedProfileSample = null,
                errorMessage = null,
                hasUserPositionedRx = it.hasUserPositionedRx || markUserPositioned
            )
        }
        sampleRxElevation(lat, lon)
        scheduleLiveDirectProfile()
    }

    private fun sampleTxElevation(lat: Double, lon: Double) {
        txElevJob?.cancel()
        val job = viewModelScope.launch {
            try {
                _uiState.update { it.copy(isTxElevLoading = true) }
                TerrariumDemRepository.samplePointElevation(getApplication(), lat, lon, zoom = 12)
                    .onSuccess { elev ->
                        _uiState.update { it.copy(txGroundElevationMeters = elev, isTxElevLoading = false) }
                    }
                    .onFailure {
                        _uiState.update { it.copy(isTxElevLoading = false) }
                    }
            } catch (ce: CancellationException) {
                _uiState.update { it.copy(isTxElevLoading = false) }
                throw ce
            }
        }
        txElevJob = job
        coordinator.registerJob(JOB_TX_ELEV, job)
    }

    private fun sampleRxElevation(lat: Double, lon: Double) {
        rxElevJob?.cancel()
        val job = viewModelScope.launch {
            try {
                _uiState.update { it.copy(isRxElevLoading = true) }
                TerrariumDemRepository.samplePointElevation(getApplication(), lat, lon, zoom = 12)
                    .onSuccess { elev ->
                        _uiState.update { it.copy(rxGroundElevationMeters = elev, isRxElevLoading = false) }
                    }
                    .onFailure {
                        _uiState.update { it.copy(isRxElevLoading = false) }
                    }
            } catch (ce: CancellationException) {
                _uiState.update { it.copy(isRxElevLoading = false) }
                throw ce
            }
        }
        rxElevJob = job
        coordinator.registerJob(JOB_RX_ELEV, job)
    }

    fun updateTxAntennaHeight(heightM: Float) {
        val clamped = heightM.coerceIn(1f, 60f)
        if (_uiState.value.txAntennaHeightMeters == clamped) return
        _uiState.update { it.copy(txAntennaHeightMeters = clamped, isViewshedStale = it.currentResult != null) }
        scheduleLiveHopResample()
    }

    fun updateRxAntennaHeight(heightM: Float) {
        val clamped = heightM.coerceIn(1f, 60f)
        if (_uiState.value.rxAntennaHeightMeters == clamped) return
        _uiState.update { it.copy(rxAntennaHeightMeters = clamped, isViewshedStale = it.currentResult != null) }
        scheduleLiveHopResample()
    }

    fun updateRelayAntennaHeight(heightM: Float) {
        val clamped = heightM.coerceIn(1f, 60f)
        if (_uiState.value.relayAntennaHeightMeters == clamped) return
        _uiState.update { it.copy(relayAntennaHeightMeters = clamped, isViewshedStale = it.currentResult != null) }
        scheduleLiveHopResample()
    }

    fun updateRadiusKm(radiusKm: Int) {
        val clamped = radiusKm.coerceIn(1, ViewshedEngine.MAX_SUPPORTED_RADIUS_KM)
        val previous = _uiState.value
        if (previous.radiusKm == clamped) return
        _uiState.update { it.copy(radiusKm = clamped) }
        if (previous.currentResult != null && previous.areBothEndpointsSet) {
            calculateBridge()
        }
    }

    fun updateFrequencyText(text: String) {
        if (_uiState.value.frequencyText == text) return
        _uiState.update { it.copy(frequencyText = text, isViewshedStale = it.currentResult != null) }
        scheduleLiveHopResample()
    }

    fun updateTxPowerDbm(powerDbm: Float) {
        val clamped = powerDbm.coerceIn(10f, 37f)
        if (_uiState.value.txPowerDbm == clamped) return
        _uiState.update { it.copy(txPowerDbm = clamped, isViewshedStale = it.currentResult != null) }
        scheduleLiveHopResample()
    }

    fun updateTxAntennaGainDbi(gainDbi: Float) {
        val clamped = gainDbi.coerceIn(0f, 15f)
        if (_uiState.value.txAntennaGainDbi == clamped) return
        _uiState.update { it.copy(txAntennaGainDbi = clamped, isViewshedStale = it.currentResult != null) }
        scheduleLiveHopResample()
    }

    fun updateTxSensitivityDbm(sensitivityDbm: Float) {
        val clamped = sensitivityDbm.coerceIn(-140f, -100f)
        if (_uiState.value.txSensitivityDbm == clamped) return
        _uiState.update { it.copy(txSensitivityDbm = clamped, isViewshedStale = it.currentResult != null) }
        scheduleLiveHopResample()
    }

    fun updateRxPowerDbm(powerDbm: Float) {
        val clamped = powerDbm.coerceIn(10f, 37f)
        if (_uiState.value.rxPowerDbm == clamped) return
        _uiState.update { it.copy(rxPowerDbm = clamped, isViewshedStale = it.currentResult != null) }
        scheduleLiveHopResample()
    }

    fun updateRxAntennaGainDbi(gainDbi: Float) {
        val clamped = gainDbi.coerceIn(0f, 15f)
        if (_uiState.value.rxAntennaGainDbi == clamped) return
        _uiState.update { it.copy(rxAntennaGainDbi = clamped, isViewshedStale = it.currentResult != null) }
        scheduleLiveHopResample()
    }

    fun updateRxSensitivityDbm(sensitivityDbm: Float) {
        val clamped = sensitivityDbm.coerceIn(-140f, -100f)
        if (_uiState.value.rxSensitivityDbm == clamped) return
        _uiState.update { it.copy(rxSensitivityDbm = clamped, isViewshedStale = it.currentResult != null) }
        scheduleLiveHopResample()
    }

    fun updateRelayPowerDbm(powerDbm: Float) {
        val clamped = powerDbm.coerceIn(10f, 37f)
        if (_uiState.value.relayPowerDbm == clamped) return
        _uiState.update { it.copy(relayPowerDbm = clamped, isViewshedStale = it.currentResult != null) }
        scheduleLiveHopResample()
    }

    fun updateRelayAntennaGainDbi(gainDbi: Float) {
        val clamped = gainDbi.coerceIn(0f, 15f)
        if (_uiState.value.relayAntennaGainDbi == clamped) return
        _uiState.update { it.copy(relayAntennaGainDbi = clamped, isViewshedStale = it.currentResult != null) }
        scheduleLiveHopResample()
    }

    fun updateRelaySensitivityDbm(sensitivityDbm: Float) {
        val clamped = sensitivityDbm.coerceIn(-140f, -100f)
        if (_uiState.value.relaySensitivityDbm == clamped) return
        _uiState.update { it.copy(relaySensitivityDbm = clamped, isViewshedStale = it.currentResult != null) }
        scheduleLiveHopResample()
    }

    fun updateAntennaGainDbi(gainDbi: Float) = updateTxAntennaGainDbi(gainDbi)

    fun updateSensitivityDbm(sensitivityDbm: Float) = updateTxSensitivityDbm(sensitivityDbm)

    internal fun setCachedGridForTest(grid: TerrariumTileGrid) {
        cachedGrid = grid
    }

    internal fun setCorridorGridForTest(grid: TerrariumTileGrid) {
        cachedCorridorGrid = grid
    }

    fun retryLiveProfile() {
        scheduleLiveDirectProfile(delayMs = 0L)
    }

    private fun gridCoversCorridor(
        grid: TerrariumTileGrid,
        txLat: Double,
        txLon: Double,
        rxLat: Double,
        rxLon: Double
    ): Boolean {
        val midLat = (txLat + rxLat) * 0.5
        val midLon = (txLon + rxLon) * 0.5
        return grid.sampleElevation(txLat, txLon) != null &&
            grid.sampleElevation(rxLat, rxLon) != null &&
            grid.sampleElevation(midLat, midLon) != null
    }

    private fun scheduleLiveDirectProfile(delayMs: Long = 260L) {
        liveDirectProfileJob?.cancel()
        val snapshot = _uiState.value
        val tLat = snapshot.txLat
        val tLon = snapshot.txLon
        val rLat = snapshot.rxLat
        val rLon = snapshot.rxLon
        if (tLat == null || tLon == null || rLat == null || rLon == null) {
            _uiState.update {
                it.copy(
                    liveDirectHop = null,
                    isLiveProfileLoading = false,
                    liveProfileError = null,
                    scrubbedProfileSample = null
                )
            }
            return
        }

        val job = viewModelScope.launch {
            try {
                if (delayMs > 0L) delay(delayMs)
                val current = _uiState.value
                val curTxLat = current.txLat ?: return@launch
                val curTxLon = current.txLon ?: return@launch
                val curRxLat = current.rxLat ?: return@launch
                val curRxLon = current.rxLon ?: return@launch

                val existingGrid = cachedGrid?.takeIf {
                    gridCoversCorridor(it, curTxLat, curTxLon, curRxLat, curRxLon)
                } ?: cachedCorridorGrid?.takeIf {
                    gridCoversCorridor(it, curTxLat, curTxLon, curRxLat, curRxLon)
                }

                val gridToUse = if (existingGrid != null) {
                    existingGrid
                } else {
                    val pad = 0.015
                    val north = (kotlin.math.max(curTxLat, curRxLat) + pad).coerceAtMost(85.0)
                    val south = (kotlin.math.min(curTxLat, curRxLat) - pad).coerceAtLeast(-85.0)
                    val west = kotlin.math.min(curTxLon, curRxLon) - pad
                    val east = kotlin.math.max(curTxLon, curRxLon) + pad
                    val zoom = TerrariumDemRepository.recommendedZoomForBounds(
                        northLat = north,
                        southLat = south,
                        westLon = west,
                        eastLon = east,
                        preferredRadiusKm = 12
                    )
                    _uiState.update {
                        it.copy(
                            isLiveProfileLoading = true,
                            liveProfileError = null,
                            isLiveProfileOfflineMissingError = false
                        )
                    }
                    val fetchResult = corridorTileGridProvider?.invoke(north, south, west, east, zoom)
                        ?: TerrariumDemRepository.loadTileGridForBounds(
                            context = getApplication(),
                            northLat = north,
                            southLat = south,
                            westLon = west,
                            eastLon = east,
                            zoom = zoom
                        )
                    val loaded = fetchResult.getOrElse { err ->
                        _uiState.update {
                            it.copy(
                                isLiveProfileLoading = false,
                                liveProfileError = err.message ?: "Unable to fetch corridor elevation profile.",
                                isLiveProfileOfflineMissingError = err is OfflineTileMissingException
                            )
                        }
                        return@launch
                    }
                    OfflineDemPackManager.refreshCacheStats(getApplication())
                    cachedCorridorGrid = loaded
                    loaded
                }

                val paramsSnapshot = _uiState.value
                val freqMhz = (paramsSnapshot.frequencyText.toDoubleOrNull()?.takeIf { it > 0.0 }
                    ?: LosAnalyzer.DEFAULT_LORA_US_FREQ_MHZ)
                val freqGhz = freqMhz / 1000.0
                val fwdEirpDbm = (paramsSnapshot.txPowerDbm + paramsSnapshot.txAntennaGainDbi + paramsSnapshot.rxAntennaGainDbi).toDouble()
                val revEirpDbm = (paramsSnapshot.rxPowerDbm + paramsSnapshot.rxAntennaGainDbi + paramsSnapshot.txAntennaGainDbi).toDouble()
                val txH = paramsSnapshot.txAntennaHeightMeters.toDouble()
                val rxH = paramsSnapshot.rxAntennaHeightMeters.toDouble()
                val rxSens = paramsSnapshot.rxSensitivityDbm.toDouble()
                val txSens = paramsSnapshot.txSensitivityDbm.toDouble()

                val fadeMarginDb = paramsSnapshot.fadeMarginPreset.fadeMarginDb

                val hop = withContext(Dispatchers.Default) {
                    RelayBridgeEngine.evaluateHop(
                        latA = curTxLat,
                        lonA = curTxLon,
                        heightAMeters = txH,
                        latB = curRxLat,
                        lonB = curRxLon,
                        heightBMeters = rxH,
                        freqMhz = freqMhz,
                        freqGhz = freqGhz,
                        eirpDbm = fwdEirpDbm,
                        sensitivityDbm = rxSens,
                        grid = gridToUse,
                        collectObstructionPeaks = true,
                        reverseEirpDbm = revEirpDbm,
                        reverseSensitivityDbm = txSens,
                        fadeMarginDb = fadeMarginDb
                    )
                }

                _uiState.update { latest ->
                    if (latest.txLat != curTxLat || latest.txLon != curTxLon ||
                        latest.rxLat != curRxLat || latest.rxLon != curRxLon
                    ) {
                        return@update latest
                    }
                    val directObs = RelayBridgeEngine.buildDirectObstructionList(hop)
                    val keepObsId = latest.selectedObstructionId?.takeIf { selId ->
                        val solvedIds = latest.currentResult?.let {
                            RelayBridgeEngine.buildObstructionList(it, latest.selectedCandidateIndex).map { o -> o.id }
                        }.orEmpty()
                        selId in solvedIds || directObs.any { it.id == selId }
                    }
                    latest.copy(
                        liveDirectHop = hop,
                        isLiveProfileLoading = false,
                        liveProfileError = null,
                        txGroundElevationMeters = latest.txGroundElevationMeters ?: hop.profileSamples.firstOrNull()?.terrainElevationMeters,
                        rxGroundElevationMeters = latest.rxGroundElevationMeters ?: hop.profileSamples.lastOrNull()?.terrainElevationMeters,
                        selectedObstructionId = keepObsId
                    )
                }
            } catch (ce: CancellationException) {
                _uiState.update { it.copy(isLiveProfileLoading = false) }
                throw ce
            }
        }
        liveDirectProfileJob = job
        coordinator.registerJob(JOB_LIVE_PROFILE, job)
    }

    private fun scheduleLiveHopResample() {
        val state = _uiState.value
        val existing = state.currentResult
        val tLat = state.txLat
        val tLon = state.txLon
        val rLat = state.rxLat
        val rLon = state.rxLon
        if (tLat == null || tLon == null || rLat == null || rLon == null) return
        if (state.isCalculating) return

        val freq = state.frequencyText.toDoubleOrNull()?.takeIf { it > 0.0 }
            ?: LosAnalyzer.DEFAULT_LORA_US_FREQ_MHZ

        val matchingSolvedResult = existing?.takeIf {
            it.params.txLat == tLat &&
                it.params.txLon == tLon &&
                it.params.rxLat == rLat &&
                it.params.rxLon == rLon
        }

        if (matchingSolvedResult != null) {
            val grid = cachedGrid ?: cachedCorridorGrid ?: return
            val updatedParams = matchingSolvedResult.params.copy(
                txAntennaHeightMeters = state.txAntennaHeightMeters.toDouble(),
                rxAntennaHeightMeters = state.rxAntennaHeightMeters.toDouble(),
                relayAntennaHeightMeters = state.relayAntennaHeightMeters.toDouble(),
                frequencyMhz = freq,
                txPowerDbm = state.txPowerDbm.toDouble(),
                antennaGainDbi = state.txAntennaGainDbi.toDouble(),
                sensitivityDbm = state.txSensitivityDbm.toDouble(),
                rxPowerDbm = state.rxPowerDbm.toDouble(),
                rxAntennaGainDbi = state.rxAntennaGainDbi.toDouble(),
                rxSensitivityDbm = state.rxSensitivityDbm.toDouble(),
                relayPowerDbm = state.relayPowerDbm.toDouble(),
                relayAntennaGainDbi = state.relayAntennaGainDbi.toDouble(),
                relaySensitivityDbm = state.relaySensitivityDbm.toDouble(),
                fadeMarginDb = state.fadeMarginPreset.fadeMarginDb
            )

            liveResampleJob?.cancel()
            val job = viewModelScope.launch {
                val updatedResult = withContext(Dispatchers.Default) {
                    RelayBridgeEngine.recalculateHopsOnGrid(matchingSolvedResult, updatedParams, grid)
                }
                _uiState.update { latest ->
                    if (latest.currentResult == null || latest.isCalculating) return@update latest
                    val stillSelectedObsId = latest.selectedObstructionId?.takeIf { selId ->
                        RelayBridgeEngine.buildObstructionList(
                            updatedResult,
                            latest.selectedCandidateIndex
                        ).any { it.id == selId }
                    }
                    latest.copy(
                        currentResult = updatedResult,
                        liveDirectHop = updatedResult.directBaseline,
                        selectedObstructionId = stillSelectedObsId,
                        scrubbedProfileSample = null
                    )
                }
            }
            liveResampleJob = job
            coordinator.registerJob(JOB_RESAMPLE, job)
            return
        }

        val grid = (cachedCorridorGrid ?: cachedGrid)?.takeIf {
            gridCoversCorridor(it, tLat, tLon, rLat, rLon)
        } ?: return
        val txH = state.txAntennaHeightMeters.toDouble()
        val rxH = state.rxAntennaHeightMeters.toDouble()
        val fwdEirp = (state.txPowerDbm + state.txAntennaGainDbi + state.rxAntennaGainDbi).toDouble()
        val revEirp = (state.rxPowerDbm + state.rxAntennaGainDbi + state.txAntennaGainDbi).toDouble()
        val rxSens = state.rxSensitivityDbm.toDouble()
        val txSens = state.txSensitivityDbm.toDouble()

        liveResampleJob?.cancel()
        val directJob = viewModelScope.launch {
            val updatedDirect = withContext(Dispatchers.Default) {
                RelayBridgeEngine.evaluateHop(
                    latA = tLat,
                    lonA = tLon,
                    heightAMeters = txH,
                    latB = rLat,
                    lonB = rLon,
                    heightBMeters = rxH,
                    freqMhz = freq,
                    freqGhz = freq / 1000.0,
                    eirpDbm = fwdEirp,
                    sensitivityDbm = rxSens,
                    grid = grid,
                    collectObstructionPeaks = true,
                    reverseEirpDbm = revEirp,
                    reverseSensitivityDbm = txSens,
                    fadeMarginDb = state.fadeMarginPreset.fadeMarginDb
                )
            }
            _uiState.update { latest ->
                if (latest.txLat != tLat || latest.txLon != tLon ||
                    latest.rxLat != rLat || latest.rxLon != rLon
                ) {
                    return@update latest
                }
                val stillSelectedObsId = latest.selectedObstructionId?.takeIf { selId ->
                    RelayBridgeEngine.buildDirectObstructionList(updatedDirect).any { it.id == selId }
                }
                latest.copy(
                    liveDirectHop = updatedDirect,
                    selectedObstructionId = stillSelectedObsId,
                    scrubbedProfileSample = null
                )
            }
        }
        liveResampleJob = directJob
        coordinator.registerJob(JOB_RESAMPLE, directJob)
    }

    fun updateOverlayOpacity(opacity: Float) {
        val clamped = opacity.coerceIn(0.05f, 1.0f)
        viewportHolder.updateOverlayOpacity(clamped)
        _uiState.update { it.copy(overlayOpacity = clamped) }
    }

    fun selectCandidateIndex(index: Int) {
        _uiState.update {
            it.copy(
                selectedCandidateIndex = index.coerceAtLeast(0),
                selectedObstructionId = null,
                scrubbedProfileSample = null
            )
        }
    }

    fun selectDrawerTab(tab: BridgeDrawerTab) {
        _uiState.update { it.copy(activeDrawerTab = tab) }
    }

    fun selectProfileMode(mode: BridgeProfileMode) {
        _uiState.update { state ->
            val effectiveMode = if (mode == BridgeProfileMode.RELAY &&
                state.currentResult?.topCandidates?.isNotEmpty() != true
            ) {
                BridgeProfileMode.DIRECT
            } else {
                mode
            }
            state.copy(
                profileMode = effectiveMode,
                scrubbedProfileSample = null
            )
        }
    }

    fun updateScrubbedProfileSample(sample: BridgeProfileSample?) {
        _uiState.update { it.copy(scrubbedProfileSample = sample) }
    }

    fun selectObstruction(obstructionId: String?) {
        _uiState.update { state ->
            val autoMode = when {
                obstructionId == null -> state.profileMode
                obstructionId.startsWith("DIRECT_") -> BridgeProfileMode.DIRECT
                (obstructionId.startsWith("TX_TO_RELAY_") || obstructionId.startsWith("RELAY_TO_RX_")) &&
                    state.currentResult?.topCandidates?.isNotEmpty() == true -> BridgeProfileMode.RELAY
                else -> state.profileMode
            }
            state.copy(
                selectedObstructionId = obstructionId,
                profileMode = autoMode,
                obstructionFocusNonce = if (obstructionId != null) state.obstructionFocusNonce + 1L else state.obstructionFocusNonce
            )
        }
    }

    fun calculateBridge() {
        val state = _uiState.value
        val tLat = state.txLat
        val tLon = state.txLon
        val rLat = state.rxLat
        val rLon = state.rxLon
        if (tLat == null || tLon == null || rLat == null || rLon == null) {
            _uiState.update {
                it.copy(errorMessage = "Place both TX and RX sites on the map before solving a bridge.")
            }
            return
        }
        val distKm = state.directDistanceKm ?: 0.0
        if (distKm < 0.25) {
            _uiState.update {
                it.copy(errorMessage = "TX and RX must be separated by at least 250 m to solve a relay bridge.")
            }
            return
        }

        val freq = state.frequencyText.toDoubleOrNull()?.takeIf { it > 0.0 }
            ?: LosAnalyzer.DEFAULT_LORA_US_FREQ_MHZ

        val params = BridgeParams(
            txLat = tLat,
            txLon = tLon,
            txLabel = state.txLabel,
            txNode = state.txNode,
            rxLat = rLat,
            rxLon = rLon,
            rxLabel = state.rxLabel,
            rxNode = state.rxNode,
            txAntennaHeightMeters = state.txAntennaHeightMeters.toDouble(),
            rxAntennaHeightMeters = state.rxAntennaHeightMeters.toDouble(),
            relayAntennaHeightMeters = state.relayAntennaHeightMeters.toDouble(),
            radiusKm = state.radiusKm.coerceIn(1, ViewshedEngine.MAX_SUPPORTED_RADIUS_KM),
            frequencyMhz = freq,
            txPowerDbm = state.txPowerDbm.toDouble(),
            antennaGainDbi = state.txAntennaGainDbi.toDouble(),
            sensitivityDbm = state.txSensitivityDbm.toDouble(),
            rxPowerDbm = state.rxPowerDbm.toDouble(),
            rxAntennaGainDbi = state.rxAntennaGainDbi.toDouble(),
            rxSensitivityDbm = state.rxSensitivityDbm.toDouble(),
            relayPowerDbm = state.relayPowerDbm.toDouble(),
            relayAntennaGainDbi = state.relayAntennaGainDbi.toDouble(),
            relaySensitivityDbm = state.relaySensitivityDbm.toDouble(),
            fadeMarginDb = state.fadeMarginPreset.fadeMarginDb
        )

        calculateJob?.cancel()
        val job = viewModelScope.launch {
            try {
                val bounds = RelayBridgeEngine.computeBridgeBounds(params)
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
                cachedGrid = grid
                liveResampleJob?.cancel()
                _uiState.update {
                    it.copy(statusNote = "Computing bidirectional TX/RX viewsheds & ranking bridge sites (${grid.tileCount} tiles)…")
                }

                val result = withContext(Dispatchers.Default) {
                    RelayBridgeEngine.computeBridgeAnalysisSuspend(params, grid)
                }

                _uiState.update {
                    it.copy(
                        isCalculating = false,
                        statusNote = null,
                        errorMessage = null,
                        txGroundElevationMeters = result.txGroundElevationMeters,
                        rxGroundElevationMeters = result.rxGroundElevationMeters,
                        currentResult = result,
                        isViewshedStale = false,
                        liveDirectHop = result.directBaseline,
                        isLiveProfileLoading = false,
                        liveProfileError = null,
                        profileMode = if (result.topCandidates.isNotEmpty()) {
                            BridgeProfileMode.RELAY
                        } else {
                            BridgeProfileMode.DIRECT
                        },
                        scrubbedProfileSample = null,
                        selectedCandidateIndex = 0,
                        selectedObstructionId = null
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

    fun applyNodeHandoffForEndpoint(
        target: BridgePlacementTarget,
        node: MeshNode,
        autoSolveIfReady: Boolean = true
    ): Boolean {
        val lat = node.latitude ?: return false
        val lon = node.longitude ?: return false
        if (!node.hasValidGps) return false

        val preset = RfWorkspaceCoordinator.deriveNodeRfPreset(node)
        val label = "${node.displayShortName} (${node.displayLongName})"

        calculateJob?.cancel()
        liveResampleJob?.cancel()
        liveDirectProfileJob?.cancel()

        _uiState.update { state ->
            when (target) {
                BridgePlacementTarget.TX -> {
                    val keepRx = (state.hasUserPositionedRx || state.rxNode != null) &&
                        state.rxNode?.stableKey != node.stableKey
                    state.copy(
                        txLat = lat,
                        txLon = lon,
                        txLabel = label,
                        txNode = node,
                        txGroundElevationMeters = null,
                        txAntennaHeightMeters = preset.mastHeightMeters,
                        txPowerDbm = preset.txPowerDbm,
                        txAntennaGainDbi = preset.antennaGainDbi,
                        txSensitivityDbm = preset.sensitivityDbm,
                        hasUserPositionedTx = true,
                        rxLat = if (keepRx) state.rxLat else null,
                        rxLon = if (keepRx) state.rxLon else null,
                        rxLabel = if (keepRx) state.rxLabel else "Tap or drop RX",
                        rxNode = if (keepRx) state.rxNode else null,
                        rxGroundElevationMeters = if (keepRx) state.rxGroundElevationMeters else null,
                        hasUserPositionedRx = if (keepRx) state.hasUserPositionedRx else false,
                        nextAutoTarget = if (keepRx) BridgePlacementTarget.TX else BridgePlacementTarget.RX,
                        currentResult = null,
                        isViewshedStale = false,
                        liveDirectHop = null,
                        selectedObstructionId = null,
                        scrubbedProfileSample = null,
                        errorMessage = null
                    )
                }
                BridgePlacementTarget.RX -> {
                    val keepTx = (state.hasUserPositionedTx || state.txNode != null) &&
                        state.txNode?.stableKey != node.stableKey
                    state.copy(
                        rxLat = lat,
                        rxLon = lon,
                        rxLabel = label,
                        rxNode = node,
                        rxGroundElevationMeters = null,
                        rxAntennaHeightMeters = preset.mastHeightMeters,
                        rxPowerDbm = preset.txPowerDbm,
                        rxAntennaGainDbi = preset.antennaGainDbi,
                        rxSensitivityDbm = preset.sensitivityDbm,
                        hasUserPositionedRx = true,
                        txLat = if (keepTx) state.txLat else null,
                        txLon = if (keepTx) state.txLon else null,
                        txLabel = if (keepTx) state.txLabel else "Tap or drop TX",
                        txNode = if (keepTx) state.txNode else null,
                        txGroundElevationMeters = if (keepTx) state.txGroundElevationMeters else null,
                        hasUserPositionedTx = if (keepTx) state.hasUserPositionedTx else false,
                        nextAutoTarget = if (keepTx) BridgePlacementTarget.RX else BridgePlacementTarget.TX,
                        currentResult = null,
                        isViewshedStale = false,
                        liveDirectHop = null,
                        selectedObstructionId = null,
                        scrubbedProfileSample = null,
                        errorMessage = null
                    )
                }
            }
        }

        coordinator.recordHandoff(node, WorkspaceMode.BRIDGE)
        when (target) {
            BridgePlacementTarget.TX -> sampleTxElevation(lat, lon)
            BridgePlacementTarget.RX -> sampleRxElevation(lat, lon)
        }

        val updated = _uiState.value
        val shouldSolve = autoSolveIfReady &&
            updated.areBothEndpointsSet &&
            (updated.directDistanceKm ?: 0.0) >= 0.25

        if (shouldSolve) {
            calculateBridge()
        } else {
            viewportHolder.requestCenterFocus(lat, lon, minZoom = 11.0)
            if (updated.areBothEndpointsSet) {
                scheduleLiveDirectProfile(delayMs = 0L)
            }
        }
        return true
    }

    fun cancelActiveCalculations() {
        calculateJob?.cancel()
        liveResampleJob?.cancel()
        liveDirectProfileJob?.cancel()
        txElevJob?.cancel()
        rxElevJob?.cancel()
        coordinator.cancelJobsWithPrefix("bridge_")
        _uiState.update {
            it.copy(
                isCalculating = false,
                isLiveProfileLoading = false,
                isTxElevLoading = false,
                isRxElevLoading = false,
                statusNote = null
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
        const val JOB_TX_ELEV = "bridge_transient_tx_elev"
        const val JOB_RX_ELEV = "bridge_transient_rx_elev"
        const val JOB_LIVE_PROFILE = "bridge_transient_live_profile"
        const val JOB_RESAMPLE = "bridge_transient_resample"
        const val JOB_CALCULATE = "bridge_calculate"
    }
}
