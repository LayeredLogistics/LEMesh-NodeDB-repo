package us.lemesh.nodedb.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Seasonal foliage clutter & lake-effect precipitation fade margin presets for 915 MHz LoRa RF
 * planning around Lake Erie / Great Lakes terrain.
 */
enum class FadeMarginPreset(
    val fadeMarginDb: Double,
    val label: String,
    val shortLabel: String,
    val description: String
) {
    WINTER_CLEAR(
        fadeMarginDb = 0.0,
        label = "Winter Leaf-Off / Clear",
        shortLabel = "0 dB Winter",
        description = "Baseline clear-air propagation with deciduous leaf-off."
    ),
    SUMMER_CANOPY(
        fadeMarginDb = 6.0,
        label = "Summer Leaf-On Canopy",
        shortLabel = "+6 dB Canopy",
        description = "Adds 6 dB attenuation margin for dense summer foliage & light clutter."
    ),
    LAKE_EFFECT_SQUALL(
        fadeMarginDb = 12.0,
        label = "Lake-Effect Squall Margin",
        shortLabel = "+12 dB Squall",
        description = "Adds 12 dB worst-case fade margin for wet snow squalls, ice glaze, and heavy canopy."
    );

    companion object {
        fun fromFadeMarginDb(db: Double): FadeMarginPreset =
            entries.minByOrNull { kotlin.math.abs(it.fadeMarginDb - db) } ?: WINTER_CLEAR
    }
}

/**
 * Global workspace-scoped holder for environmental propagation settings synced across
 * Mesh Map (LoS & Aggregate Coverage), Viewshed Planner, and Bridge Finder.
 */
class RfEnvironmentStateHolder(
    initialPreset: FadeMarginPreset = FadeMarginPreset.WINTER_CLEAR
) {
    private val _fadeMarginPreset = MutableStateFlow(initialPreset)
    val fadeMarginPreset: StateFlow<FadeMarginPreset> = _fadeMarginPreset.asStateFlow()

    val currentFadeMarginDb: Double
        get() = _fadeMarginPreset.value.fadeMarginDb

    fun setFadeMarginPreset(preset: FadeMarginPreset) {
        _fadeMarginPreset.value = preset
    }
}
