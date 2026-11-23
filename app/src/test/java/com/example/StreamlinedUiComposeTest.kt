package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.ui.MapBaseLayer
import com.example.ui.MapToolsBottomSheet
import com.example.ui.MapToolsLegendMode
import com.example.ui.NodeMapFiltersSheet
import com.example.ui.NodeMapUiState
import com.example.ui.RfRadioParamsSection
import com.example.ui.theme.LEMeshTheme
import com.example.ui.theme.NodeMultiHopSky
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StreamlinedUiComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `rf radio params starts with advanced section collapsed and expands on tap`() {
        composeRule.setContent {
            LEMeshTheme {
                RfRadioParamsSection(
                    radiusLabel = "Max Calculation Radius",
                    radiusKm = 25,
                    radiusTagPrefix = "radius_chip_",
                    selectedRadiusColor = NodeMultiHopSky,
                    frequencyText = "906.875",
                    txPowerDbm = 30f,
                    antennaGainDbi = 5.8f,
                    sensitivityDbm = -130f,
                    opacityLabel = "Heatmap Opacity",
                    overlayOpacity = 0.68f,
                    errorMessage = null,
                    onRadiusKmChange = {},
                    onFrequencyChange = {},
                    onTxPowerChange = {},
                    onAntennaGainChange = {},
                    onSensitivityChange = {},
                    onOpacityChange = {},
                    onDismissError = {}
                )
            }
        }

        // Radius chips are visible upfront
        composeRule.onNodeWithTag("radius_chip_25").assertIsDisplayed()
        // Advanced RF accordion starts collapsed
        composeRule.onNodeWithTag("radius_chip_advanced_rf_toggle").assertIsDisplayed()
        composeRule.onNodeWithText("Freq (MHz)").assertDoesNotExist()
        composeRule.onNodeWithText("TX Power").assertDoesNotExist()

        // Tapping toggle reveals Freq, TX Power, Ant Gain, RX Sens, Opacity
        composeRule.onNodeWithTag("radius_chip_advanced_rf_toggle").performClick()
        composeRule.onNodeWithText("Freq (MHz)").assertIsDisplayed()
        composeRule.onNodeWithText("TX Power").assertIsDisplayed()
    }

    @Test
    fun `map tools bottom sheet displays basemaps, contextual legend, and invokes actions`() {
        var selectedLayer: MapBaseLayer? = null
        var exported = false
        var clearedOverlay = false

        composeRule.setContent {
            LEMeshTheme {
                MapToolsBottomSheet(
                    baseLayer = MapBaseLayer.DARK_MATTER,
                    legendMode = MapToolsLegendMode.BRIDGE,
                    sensitivityDbm = -130.0,
                    testTagPrefix = "bridge",
                    onSelectBaseLayer = { selectedLayer = it },
                    onExport = { exported = true },
                    hasClearableOverlay = true,
                    onClearOverlay = { clearedOverlay = true },
                    hasResettablePins = true,
                    onResetPins = {},
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithTag("bridge_base_layer_osm_standard").performScrollTo().performClick()
        assertEquals(MapBaseLayer.OSM_STANDARD, selectedLayer)

        composeRule.onNodeWithText("Mutual Bridge RSSI").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("bridge_clear_overlay_button").performScrollTo().performClick()
        assertTrue(clearedOverlay)

        composeRule.onNodeWithTag("bridge_export_button").performScrollTo().performClick()
        assertTrue(exported)
    }

    @Test
    fun `node map filters sheet toggles visibility and triggers field tools`() {
        var mqttToggled = false
        var losToggled = false
        var directLinksOpened = false

        composeRule.setContent {
            LEMeshTheme {
                NodeMapFiltersSheet(
                    uiState = NodeMapUiState(
                        hasLoadedFile = true,
                        loadedFileName = "sample.json",
                        totalCount = 12,
                        plottedGpsCount = 9
                    ),
                    onToggleShowMqtt = { mqttToggled = true },
                    onToggleRfSightlines = {},
                    onToggleFavoritesOnly = {},
                    onToggleLosMode = { losToggled = true },
                    onToggleMeasureMode = {},
                    onOpenDirectLinksSheet = { directLinksOpened = true },
                    onOpenNoGpsSheet = {},
                    onLoadJsonClick = {},
                    onClearJsonClick = {},
                    onResetFilters = {},
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithTag("chip_show_mqtt").performScrollTo().performClick()
        assertTrue(mqttToggled)

        composeRule.onNodeWithTag("chip_los_profile").performScrollTo().performClick()
        assertTrue(losToggled)

        composeRule.onNodeWithTag("chip_direct_links").performScrollTo().performClick()
        assertTrue(directLinksOpened)
    }
}
