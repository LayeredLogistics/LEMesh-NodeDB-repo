package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.MeshNode
import com.example.ui.theme.AlertErrorRed
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NodeDirectGreen
import com.example.ui.theme.NodeMultiHopSky
import com.example.ui.theme.NodeSelfAmber
import com.example.ui.theme.TacticalCanvas
import com.example.ui.theme.TacticalOutline
import com.example.ui.theme.TacticalSurface
import com.example.ui.theme.TacticalSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale
import kotlin.math.roundToInt

enum class RfPlacementChoice {
    AUTO,
    TX,
    RX
}

@Composable
fun RfBaseLayerSelectorChipRow(
    baseLayer: MapBaseLayer,
    testTagPrefix: String,
    onSelectBaseLayer: (MapBaseLayer) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MapBaseLayer.entries.forEach { layer ->
                FilterChip(
                    selected = layer == baseLayer,
                    onClick = { onSelectBaseLayer(layer) },
                    label = {
                        Text(
                            text = layer.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = TacticalSurfaceElevated,
                        labelColor = TextSecondary,
                        selectedContainerColor = NodeDirectGreen.copy(alpha = 0.22f),
                        selectedLabelColor = NodeDirectGreen
                    ),
                    modifier = Modifier.testTag("${testTagPrefix}_base_layer_${layer.name.lowercase()}")
                )
            }
        }
        if (baseLayer.requiresCartoKey && !isCartoKeyConfigured()) {
            Text(
                text = "Set CARTO_API_KEY in AI Studio Secrets for CARTO basemap tiles.",
                style = MaterialTheme.typography.labelSmall,
                color = NodeSelfAmber,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
fun RfQuickPlacementStrip(
    placementChoice: RfPlacementChoice,
    isTxTarget: Boolean,
    hasSwappableEndpoints: Boolean,
    testTagPrefix: String,
    onSelectMode: (RfPlacementChoice) -> Unit,
    onDropAtCenter: () -> Unit,
    onSwapTxRx: () -> Unit,
    trailingContent: (@Composable () -> Unit)? = null
) {
    val dropColor = if (isTxTarget) NodeSelfAmber else NodeMultiHopSky

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = placementChoice == RfPlacementChoice.AUTO,
            onClick = { onSelectMode(RfPlacementChoice.AUTO) },
            label = {
                Text(
                    text = stringResource(R.string.bridge_mode_auto),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            colors = FilterChipDefaults.filterChipColors(
                containerColor = TacticalSurfaceElevated,
                labelColor = TextSecondary,
                selectedContainerColor = NodeDirectGreen.copy(alpha = 0.22f),
                selectedLabelColor = NodeDirectGreen
            ),
            modifier = Modifier
                .weight(0.9f)
                .testTag("${testTagPrefix}_top_mode_auto")
        )
        FilterChip(
            selected = placementChoice == RfPlacementChoice.TX,
            onClick = { onSelectMode(RfPlacementChoice.TX) },
            border = if (placementChoice == RfPlacementChoice.AUTO && isTxTarget) {
                FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = false,
                    borderColor = NodeSelfAmber,
                    borderWidth = 1.5.dp
                )
            } else {
                FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = placementChoice == RfPlacementChoice.TX
                )
            },
            label = {
                Text(
                    text = "TX",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            },
            colors = FilterChipDefaults.filterChipColors(
                containerColor = TacticalSurfaceElevated,
                labelColor = if (isTxTarget) NodeSelfAmber else TextSecondary,
                selectedContainerColor = NodeSelfAmber.copy(alpha = 0.25f),
                selectedLabelColor = NodeSelfAmber
            ),
            modifier = Modifier
                .weight(0.75f)
                .testTag("${testTagPrefix}_top_mode_tx")
        )
        FilterChip(
            selected = placementChoice == RfPlacementChoice.RX,
            onClick = { onSelectMode(RfPlacementChoice.RX) },
            border = if (placementChoice == RfPlacementChoice.AUTO && !isTxTarget) {
                FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = false,
                    borderColor = NodeMultiHopSky,
                    borderWidth = 1.5.dp
                )
            } else {
                FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = placementChoice == RfPlacementChoice.RX
                )
            },
            label = {
                Text(
                    text = "RX",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            },
            colors = FilterChipDefaults.filterChipColors(
                containerColor = TacticalSurfaceElevated,
                labelColor = if (!isTxTarget) NodeMultiHopSky else TextSecondary,
                selectedContainerColor = NodeMultiHopSky.copy(alpha = 0.25f),
                selectedLabelColor = NodeMultiHopSky
            ),
            modifier = Modifier
                .weight(0.75f)
                .testTag("${testTagPrefix}_top_mode_rx")
        )
        Button(
            onClick = onDropAtCenter,
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = dropColor,
                contentColor = TacticalCanvas
            ),
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("${testTagPrefix}_drop_center_button")
        ) {
            Icon(
                imageVector = Icons.Default.MyLocation,
                contentDescription = if (isTxTarget) "Drop TX at map center" else "Drop RX at map center",
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (isTxTarget) {
                    stringResource(R.string.bridge_drop_tx)
                } else {
                    stringResource(R.string.bridge_drop_rx)
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(
            onClick = onSwapTxRx,
            enabled = hasSwappableEndpoints,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("${testTagPrefix}_top_swap_button")
        ) {
            Icon(
                imageVector = Icons.Default.SwapHoriz,
                contentDescription = "Swap TX and RX endpoints",
                tint = if (hasSwappableEndpoints) TextPrimary else TextMuted
            )
        }
        if (trailingContent != null) {
            trailingContent()
        }
    }
}

@Composable
fun RfEndpointCard(
    role: String,
    label: String,
    lat: Double?,
    lon: Double?,
    groundElevationMeters: Double?,
    isElevLoading: Boolean,
    accentColor: Color,
    plottedNodes: List<MeshNode>,
    pickNodeTestTag: String,
    onSelectNode: (MeshNode) -> Unit,
    extraContent: (@Composable ColumnScope.() -> Unit)? = null
) {
    var pickerExpanded by remember { mutableStateOf(false) }

    Surface(
        color = TacticalSurfaceElevated,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, accentColor.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "$role • $label",
                    style = MaterialTheme.typography.titleSmall,
                    color = accentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (plottedNodes.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { pickerExpanded = !pickerExpanded },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        border = BorderStroke(1.dp, if (pickerExpanded) accentColor else TacticalOutline),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag(pickNodeTestTag)
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationSearching,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (pickerExpanded) "Hide" else "Node",
                            style = MaterialTheme.typography.labelMedium,
                            color = accentColor
                        )
                    }
                }
            }

            if (lat != null && lon != null) {
                val elevString = when {
                    isElevLoading -> "DEM…"
                    groundElevationMeters != null -> String.format(Locale.US, "%.1f m ASL", groundElevationMeters)
                    else -> "N/A"
                }
                Text(
                    text = String.format(Locale.US, "%.5f, %.5f • Ground %s", lat, lon, elevString),
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 10.sp,
                    color = TextSecondary
                )
            } else {
                Text(
                    text = "Not placed • Tap map, Drop at center, or pick a node",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 10.sp,
                    color = TextMuted
                )
            }

            AnimatedVisibility(visible = pickerExpanded && plottedNodes.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .heightIn(max = 180.dp)
                        .background(TacticalSurface, RoundedCornerShape(8.dp))
                        .border(1.dp, TacticalOutline, RoundedCornerShape(8.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 2.dp)
                ) {
                    plottedNodes.forEach { node ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .clickable {
                                    pickerExpanded = false
                                    onSelectNode(node)
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = node.displayShortName,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = accentColor
                            )
                            Text(
                                text = node.displayLongName,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            if (extraContent != null) {
                extraContent()
            }
        }
    }
}

@Composable
fun RfRadioParamsSection(
    radiusLabel: String,
    radiusKm: Int,
    radiusTagPrefix: String,
    selectedRadiusColor: Color,
    frequencyText: String,
    txPowerDbm: Float,
    antennaGainDbi: Float,
    sensitivityDbm: Float,
    opacityLabel: String,
    overlayOpacity: Float,
    errorMessage: String?,
    onRadiusKmChange: (Int) -> Unit,
    onFrequencyChange: (String) -> Unit,
    onTxPowerChange: (Float) -> Unit,
    onAntennaGainChange: (Float) -> Unit,
    onSensitivityChange: (Float) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onDismissError: () -> Unit,
    showPerNodeRadioSliders: Boolean = true
) {
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = radiusLabel,
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(10, 25, 40, 50).forEach { rKm ->
                FilterChip(
                    selected = radiusKm == rKm,
                    onClick = { onRadiusKmChange(rKm) },
                    label = {
                        Text(
                            text = "${rKm}km",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 11.sp
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = TacticalSurfaceElevated,
                        labelColor = TextSecondary,
                        selectedContainerColor = selectedRadiusColor.copy(alpha = 0.22f),
                        selectedLabelColor = selectedRadiusColor
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("${radiusTagPrefix}$rKm")
                )
            }
        }
    }

    Surface(
        color = TacticalSurfaceElevated,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, TacticalOutline, RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { advancedExpanded = !advancedExpanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("${radiusTagPrefix}advanced_rf_toggle"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = NodeMultiHopSky,
                        modifier = Modifier.size(16.dp)
                    )
                    Column {
                        Text(
                            text = stringResource(
                                if (showPerNodeRadioSliders) R.string.rf_advanced_section else R.string.bridge_channel_overlay_section
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary
                        )
                        Text(
                            text = if (showPerNodeRadioSliders) {
                                String.format(
                                    Locale.US,
                                    "%s MHz • %d dBm • %.1f dBi • %d dBm • %d%%",
                                    frequencyText.ifBlank { "906.875" },
                                    txPowerDbm.roundToInt(),
                                    antennaGainDbi,
                                    sensitivityDbm.roundToInt(),
                                    (overlayOpacity * 100).roundToInt()
                                )
                            } else {
                                String.format(
                                    Locale.US,
                                    "%s MHz • %d%% opacity",
                                    frequencyText.ifBlank { "906.875" },
                                    (overlayOpacity * 100).roundToInt()
                                )
                            },
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 10.sp,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Icon(
                    imageVector = if (advancedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(R.string.rf_advanced_section),
                    tint = TextSecondary
                )
            }

            AnimatedVisibility(visible = advancedExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = frequencyText,
                            onValueChange = { incoming ->
                                if (incoming.isEmpty() || incoming.matches(Regex("^\\d*\\.?\\d*$"))) {
                                    onFrequencyChange(incoming)
                                }
                            },
                            label = { Text("Freq (MHz)", fontSize = 10.sp) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = JetBrainsMonoFontFamily,
                                color = TextPrimary
                            ),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = TacticalSurface,
                                unfocusedContainerColor = TacticalSurface,
                                focusedBorderColor = NodeMultiHopSky,
                                unfocusedBorderColor = TacticalOutline
                            ),
                            modifier = Modifier.weight(1f)
                        )

                        if (showPerNodeRadioSliders) {
                            Column(modifier = Modifier.weight(1.3f)) {
                                RfLabeledSlider(
                                    label = "TX Power",
                                    valueText = "${txPowerDbm.roundToInt()} dBm",
                                    value = txPowerDbm,
                                    valueRange = 10f..37f,
                                    accentColor = NodeDirectGreen,
                                    onValueChange = onTxPowerChange
                                )
                            }
                        }
                    }

                    if (showPerNodeRadioSliders) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                RfLabeledSlider(
                                    label = "Ant Gain",
                                    valueText = String.format(Locale.US, "%.1f dBi", antennaGainDbi),
                                    value = antennaGainDbi,
                                    valueRange = 0f..15f,
                                    accentColor = NodeDirectGreen,
                                    onValueChange = onAntennaGainChange
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                RfLabeledSlider(
                                    label = "RX Sens",
                                    valueText = "${sensitivityDbm.roundToInt()} dBm",
                                    value = sensitivityDbm,
                                    valueRange = -140f..-100f,
                                    accentColor = NodeDirectGreen,
                                    onValueChange = onSensitivityChange
                                )
                            }
                        }
                    }

                    RfLabeledSlider(
                        label = opacityLabel,
                        valueText = "${(overlayOpacity * 100).roundToInt()}%",
                        value = overlayOpacity,
                        valueRange = 0.1f..0.95f,
                        accentColor = NodeMultiHopSky,
                        onValueChange = onOpacityChange
                    )
                }
            }
        }
    }

    if (errorMessage != null) {
        RfFloatingErrorBanner(
            message = errorMessage,
            onDismiss = onDismissError
        )
    }
}

@Composable
fun RfFloatingErrorBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = AlertErrorRed.copy(alpha = 0.94f),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss error", tint = TextPrimary)
            }
        }
    }
}

@Composable
fun RfLabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    accentColor: Color = NodeDirectGreen,
    sliderTestTag: String? = null,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Text(
                text = valueText,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = TextPrimary
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor,
                inactiveTrackColor = TacticalOutline
            ),
            modifier = Modifier
                .height(30.dp)
                .let { if (sliderTestTag != null) it.testTag(sliderTestTag) else it }
        )
    }
}

@Composable
fun RfPlannerProgressOverlay(
    note: String,
    borderColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = TacticalSurface.copy(alpha = 0.94f),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.border(1.dp, borderColor, RoundedCornerShape(14.dp))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = borderColor,
                strokeWidth = 2.5.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RfNodeSiteActionSheet(
    node: MeshNode,
    isCurrentTx: Boolean,
    isCurrentRx: Boolean,
    onSetAsTx: () -> Unit,
    onSetAsRx: () -> Unit,
    onDismiss: () -> Unit,
    txButtonLabel: String = stringResource(R.string.rf_use_as_tx),
    rxButtonLabel: String = stringResource(R.string.rf_use_as_rx)
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val classification = node.linkClassification

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = TacticalSurface,
        contentColor = TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(classification.color.copy(alpha = 0.16f))
                            .border(1.5.dp, classification.color, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = node.displayShortName.take(4),
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = classification.color
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StatusBadge(text = classification.label.uppercase(), color = classification.color)
                            if (isCurrentTx) {
                                StatusBadge(text = "ACTIVE TX", color = NodeSelfAmber)
                            }
                            if (isCurrentRx) {
                                StatusBadge(text = "ACTIVE RX", color = NodeMultiHopSky)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = node.displayLongName,
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = node.id.ifBlank { "ID Unknown" },
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("rf_node_sheet_close")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close node selection",
                        tint = TextSecondary
                    )
                }
            }

            Surface(
                color = TacticalSurfaceElevated,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, TacticalOutline, RoundedCornerShape(12.dp))
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (node.latitude != null && node.longitude != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = NodeMultiHopSky,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = buildString {
                                    append(String.format(Locale.US, "%.5f, %.5f", node.latitude, node.longitude))
                                    if (node.altitude != null) {
                                        append("  •  ${node.altitude} m")
                                    }
                                },
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 12.sp,
                                color = TextPrimary
                            )
                        }
                    }
                    Text(
                        text = buildString {
                            append(node.hwModel ?: "Unknown HW")
                            append("  •  Hops: ${node.hopsAway ?: "N/A"}")
                            node.snr?.let { append(String.format(Locale.US, "  •  SNR: %.1f dB", it)) }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onSetAsTx,
                    enabled = node.hasValidGps,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NodeSelfAmber,
                        contentColor = TacticalCanvas
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("rf_node_set_tx_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.CellTower,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = txButtonLabel,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Button(
                    onClick = onSetAsRx,
                    enabled = node.hasValidGps,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NodeMultiHopSky,
                        contentColor = TacticalCanvas
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("rf_node_set_rx_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Radar,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = rxButtonLabel,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
