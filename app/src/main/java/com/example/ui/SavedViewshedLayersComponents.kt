package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.AggregateCoverageResult
import com.example.data.LAYER_TINT_PALETTE
import com.example.data.LayerRenderMode
import com.example.data.SavedViewshedLayer
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

@Composable
fun SaveViewshedLayerDialog(
    initialName: String,
    initialTintArgb: Int,
    onDismiss: () -> Unit,
    onConfirmSave: (name: String, tintColorArgb: Int, renderMode: LayerRenderMode) -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var selectedTint by remember(initialTintArgb) { mutableIntStateOf(initialTintArgb) }
    var selectedMode by remember { mutableStateOf(LayerRenderMode.TINT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TacticalSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Layers,
                    contentDescription = null,
                    tint = NodeDirectGreen,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.save_layer_dialog_title),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.save_layer_name_label)) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = TacticalSurfaceElevated,
                        unfocusedContainerColor = TacticalSurfaceElevated,
                        focusedBorderColor = NodeDirectGreen,
                        unfocusedBorderColor = TacticalOutline
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("save_layer_name_input")
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.save_layer_tint_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        LAYER_TINT_PALETTE.forEachIndexed { idx, argb ->
                            val isSelected = selectedTint == argb
                            val swatchColor = Color(argb)
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .clickable { selectedTint = argb }
                                    .padding(6.dp)
                                    .clip(CircleShape)
                                    .background(swatchColor)
                                    .border(
                                        width = if (isSelected) 2.5.dp else 1.dp,
                                        color = if (isSelected) TextPrimary else TacticalOutline,
                                        shape = CircleShape
                                    )
                                    .testTag("layer_tint_swatch_$idx"),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected color",
                                        tint = TacticalCanvas,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.save_layer_mode_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedMode == LayerRenderMode.TINT,
                            onClick = { selectedMode = LayerRenderMode.TINT },
                            label = {
                                Text(
                                    text = stringResource(R.string.mode_distinct_tint),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(Color(selectedTint))
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = TacticalSurfaceElevated,
                                labelColor = TextSecondary,
                                selectedContainerColor = Color(selectedTint).copy(alpha = 0.22f),
                                selectedLabelColor = TextPrimary
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("save_layer_mode_tint")
                        )
                        FilterChip(
                            selected = selectedMode == LayerRenderMode.RSSI,
                            onClick = { selectedMode = LayerRenderMode.RSSI },
                            label = {
                                Text(
                                    text = stringResource(R.string.mode_rssi_heatmap),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = TacticalSurfaceElevated,
                                labelColor = TextSecondary,
                                selectedContainerColor = NodeMultiHopSky.copy(alpha = 0.22f),
                                selectedLabelColor = NodeMultiHopSky
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("save_layer_mode_rssi")
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmSave(name.trim(), selectedTint, selectedMode) },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NodeDirectGreen,
                    contentColor = TacticalCanvas
                ),
                modifier = Modifier.testTag("confirm_save_layer_button")
            ) {
                Text(
                    text = stringResource(R.string.action_save),
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("cancel_save_layer_button")
            ) {
                Text(
                    text = stringResource(R.string.action_cancel),
                    color = TextSecondary
                )
            }
        }
    )
}

data class AggregateCoverageSheetConfig(
    val isEnabled: Boolean,
    val radiusKm: Int,
    val mastHeightMeters: Double,
    val eligibleRfNodeCount: Int,
    val isLoading: Boolean,
    val progressDone: Int,
    val progressTotal: Int,
    val isStale: Boolean,
    val errorMessage: String?,
    val result: AggregateCoverageResult?,
    val onToggleEnabled: () -> Unit,
    val onRadiusChange: (Int) -> Unit,
    val onMastHeightChange: (Double) -> Unit,
    val onRefresh: () -> Unit,
    val onCancelCompute: () -> Unit,
    val onDismissError: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedViewshedLayersSheet(
    layers: List<SavedViewshedLayer>,
    overlayOpacity: Float,
    onOpacityChange: (Float) -> Unit,
    onToggleEnabled: (String) -> Unit,
    onToggleRenderMode: (String) -> Unit,
    onDeleteLayer: (String) -> Unit,
    onDismiss: () -> Unit,
    aggregateConfig: AggregateCoverageSheetConfig? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pendingDeleteLayer by remember { mutableStateOf<SavedViewshedLayer?>(null) }
    val enabledCount = layers.count { it.isEnabled }
    val showOpacitySlider = layers.isNotEmpty() || aggregateConfig != null

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
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = null,
                        tint = NodeMultiHopSky,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.saved_layers_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = "$enabledCount of ${layers.size} active on map",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 11.sp,
                            color = if (enabledCount > 0) NodeDirectGreen else TextSecondary
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("close_saved_layers_sheet")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close layers sheet",
                        tint = TextSecondary
                    )
                }
            }

            if (aggregateConfig != null) {
                AggregateCoverageControlCard(config = aggregateConfig)
            }

            if (showOpacitySlider) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Overlay Opacity",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                        Text(
                            text = "${(overlayOpacity * 100).roundToInt()}%",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = TextPrimary
                        )
                    }
                    Slider(
                        value = overlayOpacity.coerceIn(0.1f, 0.95f),
                        onValueChange = onOpacityChange,
                        valueRange = 0.1f..0.95f,
                        colors = SliderDefaults.colors(
                            thumbColor = NodeDirectGreen,
                            activeTrackColor = NodeDirectGreen,
                            inactiveTrackColor = TacticalOutline
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("node_map_layers_opacity_slider")
                    )
                }
            }

            if (layers.isEmpty()) {
                SavedLayersEmptyState(compact = aggregateConfig != null)
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    items(layers, key = { it.id }) { layer ->
                        SavedLayerCard(
                            layer = layer,
                            onToggleEnabled = { onToggleEnabled(layer.id) },
                            onToggleRenderMode = { onToggleRenderMode(layer.id) },
                            onRequestDelete = { pendingDeleteLayer = layer }
                        )
                    }
                }
            }
        }
    }

    pendingDeleteLayer?.let { target ->
        DeleteLayerConfirmationDialog(
            layerName = target.name,
            onConfirm = {
                pendingDeleteLayer = null
                onDeleteLayer(target.id)
            },
            onDismiss = { pendingDeleteLayer = null }
        )
    }
}

@Composable
private fun AggregateCoverageControlCard(
    config: AggregateCoverageSheetConfig
) {
    val radiusOptions = listOf(5, 10, 15, 25)
    val mastOptions = listOf(2.0, 10.0, 20.0)
    val isActive = config.isEnabled

    Surface(
        color = TacticalSurfaceElevated,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (isActive) NodeDirectGreen.copy(alpha = 0.65f) else TacticalOutline,
                shape = RoundedCornerShape(12.dp)
            )
            .testTag("aggregate_coverage_card")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Waves,
                        contentDescription = null,
                        tint = if (isActive) NodeDirectGreen else NodeMultiHopSky,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.aggregate_coverage_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        Text(
                            text = if (config.eligibleRfNodeCount > 0) {
                                "${config.eligibleRfNodeCount} active RF GPS nodes"
                            } else {
                                stringResource(R.string.aggregate_coverage_empty_hint)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Switch(
                    checked = isActive,
                    onCheckedChange = { config.onToggleEnabled() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TacticalCanvas,
                        checkedTrackColor = NodeDirectGreen,
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = TacticalSurface
                    ),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("aggregate_coverage_switch")
                )
            }

            if (config.isLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = NodeDirectGreen,
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "${stringResource(R.string.aggregate_coverage_computing)} ${config.progressDone}/${config.progressTotal}",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 11.sp,
                            color = NodeDirectGreen
                        )
                    }
                    TextButton(
                        onClick = config.onCancelCompute,
                        modifier = Modifier.testTag("aggregate_coverage_cancel")
                    ) {
                        Text(stringResource(R.string.action_cancel), color = TextSecondary)
                    }
                }
            }

            if (config.errorMessage != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(AlertErrorRed.copy(alpha = 0.16f))
                        .border(1.dp, AlertErrorRed.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = config.errorMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = config.onDismissError,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss error",
                            tint = TextPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            config.result?.takeIf { isActive }?.let { res ->
                val capNote = if (res.skippedCapCount > 0) " (top ${res.contributingNodeCount})" else ""
                Text(
                    text = String.format(
                        Locale.US,
                        "%d nodes%s • %.1f km² • peak %.0f dBm • %d ms",
                        res.contributingNodeCount,
                        capNote,
                        res.estimatedCoveredAreaKm2,
                        res.peakRssiDbm,
                        res.computeTimeMs
                    ),
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 11.sp,
                    color = if (config.isStale) NodeSelfAmber else NodeDirectGreen
                )
            }

            if (isActive && config.isStale && !config.isLoading) {
                Text(
                    text = stringResource(R.string.aggregate_coverage_stale_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = NodeSelfAmber
                )
            }

            // Radius Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.aggregate_coverage_radius_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                radiusOptions.forEach { km ->
                    val selected = config.radiusKm == km
                    FilterChip(
                        selected = selected,
                        onClick = { config.onRadiusChange(km) },
                        label = {
                            Text(
                                text = "${km}km",
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 11.sp
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = TacticalSurface,
                            labelColor = TextSecondary,
                            selectedContainerColor = NodeMultiHopSky.copy(alpha = 0.22f),
                            selectedLabelColor = NodeMultiHopSky
                        ),
                        modifier = Modifier.testTag("aggregate_radius_chip_$km")
                    )
                }
            }

            // Mast Height Chips + Refresh Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.aggregate_coverage_mast_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    mastOptions.forEach { mastM ->
                        val selected = kotlin.math.abs(config.mastHeightMeters - mastM) < 0.2
                        val labelInt = mastM.roundToInt()
                        FilterChip(
                            selected = selected,
                            onClick = { config.onMastHeightChange(mastM) },
                            label = {
                                Text(
                                    text = "${labelInt}m",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 11.sp
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = TacticalSurface,
                                labelColor = TextSecondary,
                                selectedContainerColor = NodeDirectGreen.copy(alpha = 0.22f),
                                selectedLabelColor = NodeDirectGreen
                            ),
                            modifier = Modifier.testTag("aggregate_mast_chip_$labelInt")
                        )
                    }
                }

                if (isActive) {
                    Spacer(modifier = Modifier.width(6.dp))
                    OutlinedButton(
                        onClick = config.onRefresh,
                        enabled = !config.isLoading && config.eligibleRfNodeCount > 0,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("aggregate_coverage_refresh")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.aggregate_coverage_refresh),
                            tint = NodeMultiHopSky,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.aggregate_coverage_refresh),
                            style = MaterialTheme.typography.labelSmall,
                            color = NodeMultiHopSky
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedLayerCard(
    layer: SavedViewshedLayer,
    onToggleEnabled: () -> Unit,
    onToggleRenderMode: () -> Unit,
    onRequestDelete: () -> Unit
) {
    val tintColor = Color(layer.tintColorArgb)
    val isTintMode = layer.renderMode == LayerRenderMode.TINT

    Surface(
        color = TacticalSurface,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (layer.isEnabled) tintColor.copy(alpha = 0.55f) else TacticalOutline,
                shape = RoundedCornerShape(10.dp)
            )
            .testTag("saved_layer_card_${layer.id}")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .then(
                            if (isTintMode) {
                                Modifier.background(tintColor)
                            } else {
                                Modifier.background(
                                    Brush.linearGradient(
                                        listOf(
                                            Color(0xFF22C55E),
                                            Color(0xFF06B6D4),
                                            Color(0xFFF97316)
                                        )
                                    )
                                )
                            }
                        )
                        .border(1.dp, TextPrimary.copy(alpha = 0.4f), CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = layer.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (layer.isEnabled) TextPrimary else TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = String.format(
                            Locale.US,
                            "%dm AGL • %dkm • %.1f km² • %.1fkm clear",
                            layer.antennaHeightMeters.roundToInt(),
                            layer.radiusKm,
                            layer.coveredAreaKm2,
                            layer.maxUnobstructedRangeKm
                        ),
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 10.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Switch(
                    checked = layer.isEnabled,
                    onCheckedChange = { onToggleEnabled() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TacticalCanvas,
                        checkedTrackColor = NodeDirectGreen,
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = TacticalSurfaceElevated
                    ),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("layer_enable_switch_${layer.id}")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                FilterChip(
                    selected = isTintMode,
                    onClick = onToggleRenderMode,
                    label = {
                        Text(
                            text = if (isTintMode) "Mode: Tint" else "Mode: RSSI",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 11.sp
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = TacticalSurfaceElevated,
                        labelColor = NodeMultiHopSky,
                        iconColor = NodeMultiHopSky,
                        selectedContainerColor = tintColor.copy(alpha = 0.2f),
                        selectedLabelColor = tintColor,
                        selectedLeadingIconColor = tintColor
                    ),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("layer_mode_toggle_${layer.id}")
                )

                IconButton(
                    onClick = onRequestDelete,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("layer_delete_button_${layer.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete layer ${layer.name}",
                        tint = AlertErrorRed
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedLayersEmptyState(compact: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(TacticalSurface.copy(alpha = 0.7f))
            .border(1.dp, TacticalOutline.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .padding(if (compact) 10.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Radar,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(if (compact) 20.dp else 28.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = stringResource(R.string.saved_layers_empty_title),
                style = MaterialTheme.typography.labelLarge,
                color = TextPrimary
            )
            Text(
                text = stringResource(R.string.saved_layers_empty_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun DeleteLayerConfirmationDialog(
    layerName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TacticalSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = {
            Text(
                text = stringResource(R.string.delete_layer_confirm_title),
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Text(
                text = "\"$layerName\" — ${stringResource(R.string.delete_layer_confirm_message)}",
                style = MaterialTheme.typography.bodySmall
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AlertErrorRed,
                    contentColor = TextPrimary
                ),
                modifier = Modifier.testTag("confirm_delete_layer_button")
            ) {
                Text(stringResource(R.string.action_delete), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), color = TextSecondary)
            }
        }
    )
}
