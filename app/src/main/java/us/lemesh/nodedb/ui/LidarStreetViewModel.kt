package us.lemesh.nodedb.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.lemesh.nodedb.data.FadeMarginPreset
import us.lemesh.nodedb.data.LidarDataSource
import us.lemesh.nodedb.data.LidarDemRepository
import us.lemesh.nodedb.data.LidarElevationPatch
import us.lemesh.nodedb.data.LidarViewshedEngine
import us.lemesh.nodedb.data.LidarViewshedParams
import us.lemesh.nodedb.data.LidarViewshedResult
import us.lemesh.nodedb.data.LosAnalyzer
import us.lemesh.nodedb.data.MeshNode
import us.lemesh.nodedb.data.OfflineDemPackManager
import us.lemesh.nodedb.data.RfEnvironmentStateHolder
import us.lemesh.nodedb.data.TerrariumDemRepository
import us.lemesh.nodedb.ui.state.MapViewportStateHolder
import us.lemesh.nodedb.ui.state.RfWorkspaceCoordinator
import us.lemesh.nodedb.ui.state.WorkspaceMode
import java.util.Locale
import kotlin.math.roundToInt

data class LidarStreetUiState(
    val txLat: Double? = 42.1292,
    val txLon: Double? = -80.0851,
    val siteLabel: String = "Street Survey (Erie, PA)",
    val sourceNode: MeshNode? = null,
    val groundElevationMeters: Double? = null,
    val isGroundElevLoading: Boolean = false,
    val txHeightFt: Float = LidarViewshedEngine.DEFAULT_TX_HEIGHT_FT,
    val rxHeightFt: Float = LidarViewshedEngine.DEFAULT_RX_HEIGHT_FT,
    val txHeightText: String = "10",
    val rxHeightText: String = "5",
    val radiusMeters: Int = LidarViewshedEngine.DEFAULT_RADIUS_METERS,
    val radiusText: String = "500",
    val frequencyText: String = LosAnalyzer.DEFAULT_LORA_US_FREQ_MHZ.toString(),
    val txPowerDbm: Float = 22f,
    val antennaGainDbi: Float = 3.0f,
    val cableLossDb: Float = LidarViewshedEngine.DEFAULT_CABLE_LOSS_DB,
    val overlayOpacity: Float = 0.72f,
    val fadeMarginPreset: FadeMarginPreset = FadeMarginPreset.WINTER_CLEAR,
    val activeDataSource: LidarDataSource = LidarDataSource.USGS_3DEP_1M,
    val activeSourceLabel: String = LidarDataSource.USGS_3DEP_1M.badgeLabel,
    val activeShortSourceLabel: String = LidarDataSource.USGS_3DEP_1M.shortLabel,
    val importedLocalPatch: LidarElevationPatch? = null,
    val importedFileName: String? = null,
    val activePatch: LidarElevationPatch? = null,
    val isCalculating: Boolean = false,
    val isExportingGeoTiff: Boolean = false,
    val statusNote: String? = null,
    val exportStatusMessage: String? = null,
    val errorMessage: String? = null,
    val currentResult: LidarViewshedResult? = null,
    val hasUserPositionedSite: Boolean = false
) {
    val hasTxSet: Boolean
        get() = txLat != null && txLon != null

    val hasAnyOverlayOrPins: Boolean
        get() = currentResult != null || hasTxSet
}

class LidarStreetViewModel @JvmOverloads constructor(
    application: Application,
    val viewportHolder: MapViewportStateHolder = MapViewportStateHolder(),
    val coordinator: RfWorkspaceCoordinator = RfWorkspaceCoordinator(),
    val environmentHolder: RfEnvironmentStateHolder = RfEnvironmentStateHolder()
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(LidarStreetUiState())
    val uiState: StateFlow<LidarStreetUiState> = _uiState.asStateFlow()

    private var groundSampleJob: Job? = null
    private var calculateJob: Job? = null

    init {
        OfflineDemPackManager.initialize(getApplication())
        sampleGroundElevation(42.1292, -80.0851)
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
                    if (prev.currentResult != null && prev.hasTxSet && !prev.isCalculating) {
                        calculateViewshed()
                    }
                }
            }
        }
    }

    fun setFadeMarginPreset(preset: FadeMarginPreset) {
        environmentHolder.setFadeMarginPreset(preset)
    }

    fun syncDefaultSiteFromSelfNodeIfUnset(selfNode: MeshNode?) {
        if (selfNode == null || !selfNode.hasValidGps) return
        val current = _uiState.value
        if (current.hasUserPositionedSite) return
        setTxCoordinate(
            lat = selfNode.latitude!!,
            lon = selfNode.longitude!!,
            label = "${selfNode.displayLongName} (${selfNode.displayShortName})",
            sourceNode = selfNode,
            markUserPositioned = false
        )
    }

    fun setTxMapTapCoordinate(lat: Double, lon: Double) {
        setTxCoordinate(
            lat = lat,
            lon = lon,
            label = String.format(Locale.US, "TX (%.5f, %.5f)", lat, lon),
            sourceNode = null,
            markUserPositioned = true
        )
    }

    fun setTxDragCoordinate(lat: Double, lon: Double) {
        setTxCoordinate(
            lat = lat,
            lon = lon,
            label = String.format(Locale.US, "TX (%.5f, %.5f)", lat, lon),
            sourceNode = null,
            markUserPositioned = true
        )
    }

    fun selectTxNode(node: MeshNode) {
        val lat = node.latitude ?: return
        val lon = node.longitude ?: return
        val preset = RfWorkspaceCoordinator.deriveNodeRfPreset(node)
        val clippedTxFt = (preset.mastHeightMeters * LidarViewshedEngine.METERS_TO_FEET)
            .roundToInt()
            .coerceIn(
                LidarViewshedEngine.MIN_TX_HEIGHT_FT.roundToInt(),
                LidarViewshedEngine.MAX_TX_HEIGHT_FT.roundToInt()
            )
            .toFloat()
        _uiState.update {
            it.copy(
                txHeightFt = clippedTxFt,
                txHeightText = clippedTxFt.roundToInt().toString(),
                txPowerDbm = preset.txPowerDbm.coerceIn(1f, 30f),
                antennaGainDbi = preset.antennaGainDbi.coerceIn(-3f, 12f)
            )
        }
        setTxCoordinate(
            lat = lat,
            lon = lon,
            label = "${node.displayLongName} (${node.displayShortName})",
            sourceNode = node,
            markUserPositioned = true
        )
        viewportHolder.requestCenterFocus(lat, lon, minZoom = 16.0)
    }

    fun applyNodeHandoffAndCalculate(node: MeshNode) {
        if (!node.hasValidGps) return
        selectTxNode(node)
        coordinator.recordHandoff(node, WorkspaceMode.LIDAR_STREET)
        calculateViewshed()
    }

    private fun setTxCoordinate(
        lat: Double,
        lon: Double,
        label: String,
        sourceNode: MeshNode?,
        markUserPositioned: Boolean
    ) {
        _uiState.update { current ->
            val currentPatch = current.activePatch
            val keepPatch = currentPatch != null && currentPatch.containsCoordinate(lat, lon)
            current.copy(
                txLat = lat,
                txLon = lon,
                siteLabel = label,
                sourceNode = sourceNode,
                groundElevationMeters = currentPatch?.takeIf { keepPatch }?.sampleElevation(lat, lon),
                errorMessage = null,
                hasUserPositionedSite = current.hasUserPositionedSite || markUserPositioned
            )
        }
        sampleGroundElevation(lat, lon)
    }

    fun updateTxHeightFromSlider(heightFt: Float) {
        val rounded = heightFt.roundToInt().coerceIn(
            LidarViewshedEngine.MIN_TX_HEIGHT_FT.roundToInt(),
            LidarViewshedEngine.MAX_TX_HEIGHT_FT.roundToInt()
        )
        _uiState.update {
            it.copy(
                txHeightFt = rounded.toFloat(),
                txHeightText = rounded.toString()
            )
        }
    }

    fun updateTxHeightFromText(text: String) {
        val filtered = text.filter { it.isDigit() || it == '.' }.take(5)
        val parsed = filtered.toFloatOrNull()
        _uiState.update { current ->
            current.copy(
                txHeightText = filtered,
                txHeightFt = parsed?.coerceIn(
                    LidarViewshedEngine.MIN_TX_HEIGHT_FT,
                    LidarViewshedEngine.MAX_TX_HEIGHT_FT
                ) ?: current.txHeightFt
            )
        }
    }

    fun updateRxHeightFromSlider(heightFt: Float) {
        val rounded = heightFt.roundToInt().coerceIn(
            LidarViewshedEngine.MIN_RX_HEIGHT_FT.roundToInt(),
            LidarViewshedEngine.MAX_RX_HEIGHT_FT.roundToInt()
        )
        _uiState.update {
            it.copy(
                rxHeightFt = rounded.toFloat(),
                rxHeightText = rounded.toString()
            )
        }
    }

    fun updateRxHeightFromText(text: String) {
        val filtered = text.filter { it.isDigit() || it == '.' }.take(5)
        val parsed = filtered.toFloatOrNull()
        _uiState.update { current ->
            current.copy(
                rxHeightText = filtered,
                rxHeightFt = parsed?.coerceIn(
                    LidarViewshedEngine.MIN_RX_HEIGHT_FT,
                    LidarViewshedEngine.MAX_RX_HEIGHT_FT
                ) ?: current.rxHeightFt
            )
        }
    }

    fun updateRadiusFromSlider(meters: Int) {
        val snapped = ((meters + 25) / 50) * 50
        val clamped = snapped.coerceIn(LidarViewshedEngine.MIN_RADIUS_METERS, LidarViewshedEngine.MAX_RADIUS_METERS)
        _uiState.update {
            it.copy(
                radiusMeters = clamped,
                radiusText = clamped.toString()
            )
        }
    }

    fun updateRadiusFromText(text: String) {
        val digits = text.filter { it.isDigit() }.take(4)
        val parsed = digits.toIntOrNull()
        _uiState.update { current ->
            current.copy(
                radiusText = digits,
                radiusMeters = parsed?.coerceIn(
                    LidarViewshedEngine.MIN_RADIUS_METERS,
                    LidarViewshedEngine.MAX_RADIUS_METERS
                ) ?: current.radiusMeters
            )
        }
    }

    fun updateFrequencyText(text: String) {
        _uiState.update { it.copy(frequencyText = text) }
    }

    fun updateTxPowerDbm(dbm: Float) {
        _uiState.update { it.copy(txPowerDbm = dbm.coerceIn(1f, 30f)) }
    }

    fun updateAntennaGainDbi(dbi: Float) {
        _uiState.update { it.copy(antennaGainDbi = dbi.coerceIn(-3f, 12f)) }
    }

    fun updateCableLossDb(lossDb: Float) {
        val rounded = (lossDb * 2f).roundToInt() / 2f
        _uiState.update { it.copy(cableLossDb = rounded.coerceIn(0f, 6f)) }
    }

    fun updateOverlayOpacity(opacity: Float) {
        val clamped = opacity.coerceIn(0.15f, 0.95f)
        viewportHolder.updateOverlayOpacity(clamped)
        _uiState.update { it.copy(overlayOpacity = clamped) }
    }

    fun clearImportedLocalPatch() {
        _uiState.update {
            it.copy(
                importedLocalPatch = null,
                importedFileName = null,
                activeDataSource = LidarDataSource.USGS_3DEP_1M,
                activeSourceLabel = LidarDataSource.USGS_3DEP_1M.badgeLabel,
                activeShortSourceLabel = LidarDataSource.USGS_3DEP_1M.shortLabel
            )
        }
    }

    fun clearOverlayOnly() {
        calculateJob?.cancel()
        _uiState.update {
            it.copy(
                isCalculating = false,
                statusNote = null,
                currentResult = null
            )
        }
    }

    fun resetPinsAndOverlay() {
        calculateJob?.cancel()
        _uiState.update {
            it.copy(
                isCalculating = false,
                statusNote = null,
                errorMessage = null,
                currentResult = null
            )
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun dismissExportStatus() {
        _uiState.update { it.copy(exportStatusMessage = null) }
    }

    fun suggestedGeoTiffFileName(): String {
        val state = _uiState.value
        val lat = state.txLat
        val lon = state.txLon
        val resTag = state.activePatch?.let { patch ->
            when {
                patch.resolutionMeters <= 0.65 -> "0.5m"
                patch.resolutionMeters <= 1.35 -> "1m"
                else -> String.format(Locale.US, "%.0fm", patch.resolutionMeters)
            }
        } ?: "lidar"
        return if (lat != null && lon != null) {
            String.format(
                Locale.US,
                "lemesh_lidar_%s_%.4f_%.4f_%dm.tif",
                resTag,
                lat,
                lon,
                state.radiusMeters
            )
        } else {
            "lemesh_lidar_${resTag}_${state.radiusMeters}m.tif"
        }
    }

    fun exportActivePatchToGeoTiff(uri: Uri) {
        val state = _uiState.value
        val txLat = state.txLat
        val txLon = state.txLon
        if (state.activePatch == null && (txLat == null || txLon == null)) {
            _uiState.update {
                it.copy(errorMessage = "Drop or select a TX coordinate before exporting a GeoTIFF patch.")
            }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isExportingGeoTiff = true,
                    statusNote = "Preparing 32-bit GeoTIFF elevation patch…",
                    exportStatusMessage = null,
                    errorMessage = null
                )
            }
            val exportResult = withContext(Dispatchers.IO) {
                runCatching {
                    val radiusM = state.radiusMeters.coerceIn(
                        LidarViewshedEngine.MIN_RADIUS_METERS,
                        LidarViewshedEngine.MAX_RADIUS_METERS
                    )
                    val patch = if (state.importedLocalPatch != null &&
                        txLat != null && txLon != null &&
                        state.importedLocalPatch.containsCoordinate(txLat, txLon)
                    ) {
                        state.importedLocalPatch
                    } else {
                        val existing = state.activePatch
                        val coversRadius = if (existing != null && txLat != null && txLon != null) {
                            val req = LidarDemRepository.computeQueryBounds(txLat, txLon, radiusM)
                            val northCheck = txLat + (req.northLat - txLat) * 0.85
                            val southCheck = txLat - (txLat - req.southLat) * 0.85
                            val eastCheck = txLon + (req.eastLon - txLon) * 0.85
                            val westCheck = txLon - (txLon - req.westLon) * 0.85
                            existing.containsCoordinate(northCheck, eastCheck) &&
                                existing.containsCoordinate(southCheck, westCheck)
                        } else {
                            existing != null
                        }
                        if (existing != null && coversRadius) {
                            existing
                        } else {
                            val fetch = LidarDemRepository.fetchPatchForViewshed(
                                context = getApplication(),
                                centerLat = txLat!!,
                                centerLon = txLon!!,
                                radiusMeters = radiusM,
                                importedLocalPatch = state.importedLocalPatch
                            )
                            fetch.patch
                        }
                    }

                    val resolver = getApplication<Application>().contentResolver
                    val stream = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
                        ?: resolver.openOutputStream(uri)
                        ?: throw IllegalStateException("Unable to open destination file for GeoTIFF export.")
                    stream.use { out ->
                        LidarDemRepository.writeGeoTiffStream(patch, out)
                    }

                    val savedName = resolveDisplayName(uri) ?: suggestedGeoTiffFileName()
                    patch to savedName
                }
            }
            exportResult.onSuccess { (patch, savedName) ->
                _uiState.update {
                    it.copy(
                        activePatch = patch,
                        activeDataSource = patch.source,
                        activeSourceLabel = patch.sourceLabel,
                        activeShortSourceLabel = patch.shortBadgeLabel,
                        isExportingGeoTiff = false,
                        statusNote = null,
                        exportStatusMessage = String.format(
                            Locale.US,
                            "Saved %s (%d×%d px • %.2fm grid) for offline import.",
                            savedName,
                            patch.cols,
                            patch.rows,
                            patch.resolutionMeters
                        )
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isExportingGeoTiff = false,
                        statusNote = null,
                        errorMessage = err.message ?: "Failed to export GeoTIFF patch."
                    )
                }
            }
        }
    }

    fun importLocalLidarUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isCalculating = true,
                    statusNote = "Parsing local LiDAR grid…",
                    exportStatusMessage = null,
                    errorMessage = null
                )
            }
            val fileName = resolveDisplayName(uri) ?: "local_lidar_grid"
            val parsedResult = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openInputStream(uri)?.use { stream ->
                        LidarDemRepository.parseLocalLidarStream(fileName, stream)
                    } ?: throw IllegalStateException("Unable to open selected LiDAR file.")
                }
            }
            parsedResult.onSuccess { patch ->
                val centerLat = (patch.northLat + patch.southLat) * 0.5
                val centerLon = (patch.westLon + patch.eastLon) * 0.5
                val current = _uiState.value
                val shouldRecenterTx = current.txLat == null || current.txLon == null ||
                    !patch.containsCoordinate(current.txLat, current.txLon)
                val targetTxLat = if (shouldRecenterTx) centerLat else current.txLat!!
                val targetTxLon = if (shouldRecenterTx) centerLon else current.txLon!!
                val ground = patch.sampleElevation(targetTxLat, targetTxLon)
                _uiState.update {
                    it.copy(
                        importedLocalPatch = patch,
                        importedFileName = fileName,
                        activePatch = patch,
                        activeDataSource = patch.source,
                        activeSourceLabel = patch.sourceLabel,
                        activeShortSourceLabel = patch.shortBadgeLabel,
                        txLat = targetTxLat,
                        txLon = targetTxLon,
                        siteLabel = if (shouldRecenterTx) "Survey ($fileName)" else it.siteLabel,
                        groundElevationMeters = ground,
                        isCalculating = false,
                        statusNote = null
                    )
                }
                if (shouldRecenterTx) {
                    viewportHolder.requestCenterFocus(targetTxLat, targetTxLon, minZoom = 16.0)
                }
                calculateViewshed()
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isCalculating = false,
                        statusNote = null,
                        errorMessage = err.message ?: "Failed to parse local GeoTIFF / ASC grid."
                    )
                }
            }
        }
    }

    fun calculateViewshed() {
        val state = _uiState.value
        val txLat = state.txLat ?: return
        val txLon = state.txLon ?: return

        calculateJob?.cancel()
        val job = viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(
                        isCalculating = true,
                        statusNote = if (it.importedLocalPatch != null &&
                            it.importedLocalPatch.containsCoordinate(txLat, txLon)
                        ) {
                            "Computing 1.0 m viewshed from ${it.importedLocalPatch.sourceLabel}…"
                        } else {
                            "Fetching finest 0.5m–1m USGS 3DEP LiDAR…"
                        },
                        errorMessage = null
                    )
                }

                val freqMhz = state.frequencyText.toDoubleOrNull()?.coerceIn(100.0, 6000.0)
                    ?: LosAnalyzer.DEFAULT_LORA_US_FREQ_MHZ
                val radiusM = state.radiusMeters.coerceIn(
                    LidarViewshedEngine.MIN_RADIUS_METERS,
                    LidarViewshedEngine.MAX_RADIUS_METERS
                )
                val params = LidarViewshedParams(
                    originLat = txLat,
                    originLon = txLon,
                    originLabel = state.siteLabel,
                    sourceNode = state.sourceNode,
                    txHeightFt = state.txHeightFt.toDouble().coerceIn(
                        LidarViewshedEngine.MIN_TX_HEIGHT_FT.toDouble(),
                        LidarViewshedEngine.MAX_TX_HEIGHT_FT.toDouble()
                    ),
                    rxHeightFt = state.rxHeightFt.toDouble().coerceIn(
                        LidarViewshedEngine.MIN_RX_HEIGHT_FT.toDouble(),
                        LidarViewshedEngine.MAX_RX_HEIGHT_FT.toDouble()
                    ),
                    radiusMeters = radiusM,
                    frequencyMhz = freqMhz,
                    txPowerDbm = state.txPowerDbm.toDouble().coerceIn(1.0, 30.0),
                    antennaGainDbi = state.antennaGainDbi.toDouble().coerceIn(-3.0, 12.0),
                    cableLossDb = state.cableLossDb.toDouble().coerceIn(0.0, 6.0),
                    sensitivityDbm = LidarViewshedEngine.SX1262_LONGFAST_SENSITIVITY_DBM,
                    fadeMarginDb = state.fadeMarginPreset.fadeMarginDb
                )

                val fetchResult = LidarDemRepository.fetchPatchForViewshed(
                    context = getApplication(),
                    centerLat = txLat,
                    centerLon = txLon,
                    radiusMeters = radiusM,
                    importedLocalPatch = state.importedLocalPatch
                )

                _uiState.update {
                    it.copy(
                        activePatch = fetchResult.patch,
                        activeDataSource = fetchResult.patch.source,
                        activeSourceLabel = fetchResult.patch.sourceLabel,
                        activeShortSourceLabel = fetchResult.patch.shortBadgeLabel,
                        statusNote = "Raymarching ${fetchResult.patch.shortBadgeLabel} @ 1.0 m step…"
                    )
                }

                val result = LidarViewshedEngine.computeViewshed(
                    params = params,
                    patch = fetchResult.patch
                )

                _uiState.update {
                    it.copy(
                        isCalculating = false,
                        statusNote = null,
                        currentResult = result,
                        groundElevationMeters = result.originElevationMeters
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isCalculating = false,
                        statusNote = null,
                        errorMessage = e.message ?: "LiDAR street viewshed failed."
                    )
                }
            }
        }
        calculateJob = job
        coordinator.registerJob("lidar_viewshed", job)
    }

    private fun sampleGroundElevation(lat: Double, lon: Double) {
        groundSampleJob?.cancel()
        val cachedPatch = _uiState.value.activePatch?.takeIf { it.containsCoordinate(lat, lon) }
        if (cachedPatch != null) {
            _uiState.update {
                it.copy(
                    groundElevationMeters = cachedPatch.sampleElevation(lat, lon),
                    activeDataSource = cachedPatch.source,
                    activeSourceLabel = cachedPatch.sourceLabel,
                    activeShortSourceLabel = cachedPatch.shortBadgeLabel,
                    isGroundElevLoading = false
                )
            }
            return
        }
        groundSampleJob = viewModelScope.launch {
            _uiState.update { it.copy(isGroundElevLoading = true) }
            val elev: Double? = runCatching {
                val res = LidarDemRepository.fetchPatchForViewshed(
                    context = getApplication(),
                    centerLat = lat,
                    centerLon = lon,
                    radiusMeters = _uiState.value.radiusMeters,
                    importedLocalPatch = _uiState.value.importedLocalPatch
                )
                _uiState.update {
                    it.copy(
                        activePatch = res.patch,
                        activeDataSource = res.patch.source,
                        activeSourceLabel = res.patch.sourceLabel,
                        activeShortSourceLabel = res.patch.shortBadgeLabel
                    )
                }
                res.patch.sampleElevation(lat, lon)
            }.getOrNull() ?: TerrariumDemRepository.samplePointElevation(
                context = getApplication(),
                lat = lat,
                lon = lon,
                zoom = 14
            ).getOrNull()
            _uiState.update {
                it.copy(
                    groundElevationMeters = elev,
                    isGroundElevLoading = false
                )
            }
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return runCatching {
            getApplication<Application>().contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        }.getOrNull()
    }
}
