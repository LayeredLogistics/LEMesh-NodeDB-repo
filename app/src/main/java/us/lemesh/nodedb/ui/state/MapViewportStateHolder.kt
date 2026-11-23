package us.lemesh.nodedb.ui.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import us.lemesh.nodedb.ui.MapBaseLayer

enum class OverlayContinuityMode(
    val label: String,
    val subtitle: String
) {
    KEEP_NODEDB_WITH_RF(
        label = "NodeDB + Active RF",
        subtitle = "Keep NodeDB pins visible alongside Viewshed & Bridge overlays"
    ),
    ACTIVE_MODE_ONLY(
        label = "Active Mode Only",
        subtitle = "Show only overlays belonging to the active workspace"
    )
}

data class MapViewportState(
    val cameraLat: Double? = null,
    val cameraLon: Double? = null,
    val cameraZoom: Double? = null,
    val cameraBearingDeg: Float = 0f,
    val baseLayer: MapBaseLayer = MapBaseLayer.DARK_MATTER,
    val overlayOpacity: Float = 0.68f,
    val overlayContinuityMode: OverlayContinuityMode = OverlayContinuityMode.KEEP_NODEDB_WITH_RF,
    val startupTargetLat: Double? = null,
    val startupTargetLon: Double? = null,
    val hasStartedStartupZoom: Boolean = false,
    val fitBoundsRequestId: Long = 0L,
    val centerFocusLat: Double? = null,
    val centerFocusLon: Double? = null,
    val centerFocusMinZoom: Double? = null,
    val centerFocusRequestId: Long = 0L
)

class MapViewportStateHolder {
    private val _state = MutableStateFlow(MapViewportState())
    val state: StateFlow<MapViewportState> = _state.asStateFlow()

    fun saveCameraState(
        lat: Double,
        lon: Double,
        zoom: Double,
        bearingDeg: Float = _state.value.cameraBearingDeg
    ) {
        val current = _state.value
        if (current.cameraLat == lat &&
            current.cameraLon == lon &&
            current.cameraZoom == zoom &&
            current.cameraBearingDeg == bearingDeg
        ) {
            return
        }
        _state.update {
            it.copy(
                cameraLat = lat,
                cameraLon = lon,
                cameraZoom = zoom,
                cameraBearingDeg = bearingDeg
            )
        }
    }

    fun selectBaseLayer(layer: MapBaseLayer) {
        _state.update { it.copy(baseLayer = layer) }
    }

    fun cycleBaseLayer(): MapBaseLayer {
        var nextLayer = _state.value.baseLayer
        _state.update { state ->
            val layers = MapBaseLayer.entries
            nextLayer = layers[(layers.indexOf(state.baseLayer) + 1) % layers.size]
            state.copy(baseLayer = nextLayer)
        }
        return nextLayer
    }

    fun updateOverlayOpacity(opacity: Float) {
        val clamped = opacity.coerceIn(0.05f, 1.0f)
        _state.update { it.copy(overlayOpacity = clamped) }
    }

    fun setOverlayContinuityMode(mode: OverlayContinuityMode) {
        _state.update { it.copy(overlayContinuityMode = mode) }
    }

    fun setStartupTarget(lat: Double, lon: Double) {
        _state.update { it.copy(startupTargetLat = lat, startupTargetLon = lon) }
    }

    fun markStartupZoomStarted() {
        _state.update { it.copy(hasStartedStartupZoom = true) }
    }

    fun requestFitBounds(): Long {
        var nextId = 0L
        _state.update {
            nextId = it.fitBoundsRequestId + 1L
            it.copy(fitBoundsRequestId = nextId)
        }
        return nextId
    }

    fun requestCenterFocus(lat: Double, lon: Double, minZoom: Double? = null) {
        _state.update {
            it.copy(
                centerFocusLat = lat,
                centerFocusLon = lon,
                centerFocusMinZoom = minZoom,
                centerFocusRequestId = it.centerFocusRequestId + 1L
            )
        }
    }
}
