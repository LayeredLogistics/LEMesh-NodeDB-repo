package us.lemesh.nodedb.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import us.lemesh.nodedb.R
import us.lemesh.nodedb.data.LinkClassification
import us.lemesh.nodedb.ui.theme.AlertErrorRed
import us.lemesh.nodedb.ui.theme.JetBrainsMonoFontFamily
import us.lemesh.nodedb.ui.theme.NodeDirectGreen
import us.lemesh.nodedb.ui.theme.NodeFavoritePurple
import us.lemesh.nodedb.ui.theme.NodeMqttSlate
import us.lemesh.nodedb.ui.theme.NodeMultiHopSky
import us.lemesh.nodedb.ui.theme.NodeSelfAmber
import us.lemesh.nodedb.ui.theme.TacticalCanvas
import us.lemesh.nodedb.ui.theme.TacticalOutline
import us.lemesh.nodedb.ui.theme.TacticalSurface
import us.lemesh.nodedb.ui.theme.TacticalSurfaceBright
import us.lemesh.nodedb.ui.theme.TacticalSurfaceElevated
import us.lemesh.nodedb.ui.theme.TextMuted
import us.lemesh.nodedb.ui.theme.TextPrimary
import us.lemesh.nodedb.ui.theme.TextSecondary

enum class MapToolsLegendMode {
    NODE_MAP,
    VIEWSHED,
    BRIDGE
}

@Composable
fun FloatingMapToolsButton(
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        color = TacticalSurface.copy(alpha = 0.94f),
        contentColor = TextPrimary,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, NodeMultiHopSky.copy(alpha = 0.55f)),
        modifier = modifier
            .heightIn(min = 48.dp)
            .minimumInteractiveComponentSize()
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Map,
                contentDescription = stringResource(R.string.map_tools_title),
                tint = NodeMultiHopSky,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = stringResource(R.string.map_tools_button),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MapToolsBottomSheet(
    baseLayer: MapBaseLayer,
    legendMode: MapToolsLegendMode,
    sensitivityDbm: Double = -130.0,
    testTagPrefix: String,
    onSelectBaseLayer: (MapBaseLayer) -> Unit,
    onExport: () -> Unit,
    onFitBounds: (() -> Unit)? = null,
    onLoadJson: (() -> Unit)? = null,
    onClearJson: (() -> Unit)? = null,
    savedLayersSummary: String? = null,
    onOpenSavedLayers: (() -> Unit)? = null,
    hasClearableOverlay: Boolean = false,
    onClearOverlay: (() -> Unit)? = null,
    hasResettablePins: Boolean = false,
    onResetPins: (() -> Unit)? = null,
    showNodeMapRssiLegend: Boolean = false,
    onAddNode: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Map,
                        contentDescription = null,
                        tint = NodeMultiHopSky,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.map_tools_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("${testTagPrefix}_map_tools_close")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close map tools",
                        tint = TextSecondary
                    )
                }
            }

            // Actions section
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.map_tools_actions),
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            onExport()
                        },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, NodeMultiHopSky.copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = NodeMultiHopSky.copy(alpha = 0.12f),
                            contentColor = NodeMultiHopSky
                        ),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("${testTagPrefix}_export_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.action_export),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (onAddNode != null) {
                        OutlinedButton(
                            onClick = {
                                onDismiss()
                                onAddNode()
                            },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, NodeDirectGreen.copy(alpha = 0.65f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = NodeDirectGreen.copy(alpha = 0.14f),
                                contentColor = NodeDirectGreen
                            ),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("${testTagPrefix}_add_node_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddLocationAlt,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.action_add_node),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    if (onFitBounds != null) {
                        OutlinedButton(
                            onClick = {
                                onFitBounds()
                                onDismiss()
                            },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, TacticalOutline),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = TacticalSurfaceElevated,
                                contentColor = NodeMultiHopSky
                            ),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("fit_bounds_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CenterFocusStrong,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.action_fit_bounds),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    if (onLoadJson != null) {
                        OutlinedButton(
                            onClick = {
                                onDismiss()
                                onLoadJson()
                            },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, NodeDirectGreen.copy(alpha = 0.6f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = NodeDirectGreen.copy(alpha = 0.12f),
                                contentColor = NodeDirectGreen
                            ),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("tools_load_json_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.action_load_json),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    if (onOpenSavedLayers != null) {
                        OutlinedButton(
                            onClick = {
                                onDismiss()
                                onOpenSavedLayers()
                            },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, TacticalOutline),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = TacticalSurfaceElevated,
                                contentColor = TextPrimary
                            ),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("${testTagPrefix}_tools_layers_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Layers,
                                contentDescription = null,
                                tint = NodeMultiHopSky,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = savedLayersSummary ?: stringResource(R.string.filter_saved_layers),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    if (onClearOverlay != null && hasClearableOverlay) {
                        OutlinedButton(
                            onClick = {
                                onClearOverlay()
                                onDismiss()
                            },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, NodeSelfAmber.copy(alpha = 0.6f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = TacticalSurfaceElevated,
                                contentColor = NodeSelfAmber
                            ),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("${testTagPrefix}_clear_overlay_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.LayersClear,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.bridge_clear_overlay),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    if (onResetPins != null && hasResettablePins) {
                        OutlinedButton(
                            onClick = {
                                onResetPins()
                                onDismiss()
                            },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, AlertErrorRed.copy(alpha = 0.6f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = TacticalSurfaceElevated,
                                contentColor = AlertErrorRed
                            ),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("${testTagPrefix}_reset_pins_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.RestartAlt,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.bridge_reset_pins),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    if (onClearJson != null) {
                        OutlinedButton(
                            onClick = {
                                onDismiss()
                                onClearJson()
                            },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, AlertErrorRed.copy(alpha = 0.6f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = TacticalSurfaceElevated,
                                contentColor = AlertErrorRed
                            ),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("map_clear_json_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.action_clear_json),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = TacticalOutline.copy(alpha = 0.6f))

            // Basemap selector section
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.map_tools_basemap),
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MapBaseLayer.entries.forEach { layer ->
                        val selected = layer == baseLayer
                        FilterChip(
                            selected = selected,
                            onClick = { onSelectBaseLayer(layer) },
                            label = {
                                Text(
                                    text = layer.displayName,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = TacticalSurfaceElevated,
                                labelColor = TextSecondary,
                                selectedContainerColor = NodeDirectGreen.copy(alpha = 0.22f),
                                selectedLabelColor = NodeDirectGreen
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selected,
                                borderColor = TacticalOutline,
                                selectedBorderColor = NodeDirectGreen
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

            HorizontalDivider(color = TacticalOutline.copy(alpha = 0.6f))

            // Contextual Legend section
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.map_tools_legend),
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary
                )
                when (legendMode) {
                    MapToolsLegendMode.NODE_MAP -> {
                        NodeMapLegendCard()
                        if (showNodeMapRssiLegend) {
                            RssiGradientLegend(sensitivityDbm = sensitivityDbm)
                        }
                    }
                    MapToolsLegendMode.VIEWSHED -> RssiGradientLegend(sensitivityDbm = sensitivityDbm)
                    MapToolsLegendMode.BRIDGE -> DualViewshedLegend(sensitivityDbm = sensitivityDbm)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NodeMapLegendCard(modifier: Modifier = Modifier) {
    Surface(
        color = TacticalCanvas,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, TacticalOutline, RoundedCornerShape(12.dp))
    ) {
        FlowRow(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NodeLegendDot("Self", LinkClassification.SELF.color)
            NodeLegendDot("Favorite", LinkClassification.FAVORITE.color)
            NodeLegendDot("Direct RF", LinkClassification.DIRECT_RF.color)
            NodeLegendDot("Multi-Hop", LinkClassification.MULTI_HOP_RF.color)
            NodeLegendDot("MQTT", LinkClassification.MQTT.color)
        }
    }
}

@Composable
private fun NodeLegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = TextPrimary
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NodeMapFiltersSheet(
    uiState: NodeMapUiState,
    onToggleShowMqtt: () -> Unit,
    onToggleRfSightlines: () -> Unit,
    onToggleFavoritesOnly: () -> Unit,
    onToggleLosMode: () -> Unit,
    onToggleMeasureMode: () -> Unit,
    onOpenDirectLinksSheet: () -> Unit,
    onOpenNoGpsSheet: () -> Unit,
    onLoadJsonClick: () -> Unit,
    onClearJsonClick: () -> Unit,
    onResetFilters: () -> Unit,
    onToggleAggregateCoverage: (() -> Unit)? = null,
    onToggleAddNodeMode: (() -> Unit)? = null,
    onToggleHwModelFilter: (String) -> Unit = {},
    onToggleRoleFilter: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val hasCustomFilters = !uiState.showMqtt ||
        !uiState.showRfSightlines ||
        uiState.favoritesOnly ||
        uiState.searchQuery.isNotBlank() ||
        uiState.selectedHwModels.isNotEmpty() ||
        uiState.selectedRoles.isNotEmpty()

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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = NodeDirectGreen,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.map_filters_tools_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (hasCustomFilters) {
                        OutlinedButton(
                            onClick = onResetFilters,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            border = BorderStroke(1.dp, NodeMultiHopSky.copy(alpha = 0.6f)),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("filters_reset_all_button")
                        ) {
                            Text(
                                text = stringResource(R.string.filters_reset_all),
                                style = MaterialTheme.typography.labelMedium,
                                color = NodeMultiHopSky
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close filters",
                            tint = TextSecondary
                        )
                    }
                }
            }

            // Dataset & Node Telemetry Summary
            Surface(
                color = TacticalSurfaceElevated,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, TacticalOutline, RoundedCornerShape(14.dp))
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.filters_dataset_section),
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary
                            )
                            Text(
                                text = uiState.loadedFileName ?: "Standby • No NodeDB file loaded",
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (uiState.loadedFileName != null) NodeDirectGreen else TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = {
                                    onDismiss()
                                    onLoadJsonClick()
                                },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NodeDirectGreen,
                                    contentColor = TacticalCanvas
                                ),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("sheet_load_json_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.action_load_json),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (uiState.hasLoadedFile) {
                                IconButton(
                                    onClick = {
                                        onDismiss()
                                        onClearJsonClick()
                                    },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(TacticalSurface)
                                        .border(1.dp, AlertErrorRed.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteSweep,
                                        contentDescription = stringResource(R.string.action_clear_json),
                                        tint = AlertErrorRed,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        DatasetStatBadge("Total", uiState.totalCount, TextPrimary)
                        DatasetStatBadge("GPS", uiState.plottedGpsCount, NodeDirectGreen)
                        DatasetStatBadge("RF", uiState.rfCount, NodeMultiHopSky)
                        DatasetStatBadge("MQTT", uiState.mqttCount, NodeMqttSlate)
                        DatasetStatBadge("No-GPS", uiState.noGpsCount, NodeSelfAmber)
                        if (uiState.manualNodeCount > 0) {
                            DatasetStatBadge("Manual", uiState.manualNodeCount, NodeFavoritePurple)
                        }
                    }
                }
            }

            // Visibility Toggles
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.filters_visibility_section),
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary
                )
                FilterSwitchRow(
                    icon = Icons.Default.Cloud,
                    title = stringResource(R.string.filter_show_mqtt),
                    checked = uiState.showMqtt,
                    accent = NodeMqttSlate,
                    testTag = "chip_show_mqtt",
                    onToggle = onToggleShowMqtt
                )
                FilterSwitchRow(
                    icon = Icons.Default.Timeline,
                    title = stringResource(R.string.filter_rf_sightlines),
                    checked = uiState.showRfSightlines,
                    accent = NodeDirectGreen,
                    testTag = "chip_rf_sightlines",
                    onToggle = onToggleRfSightlines
                )
                FilterSwitchRow(
                    icon = Icons.Default.Star,
                    title = stringResource(R.string.filter_favorites_only),
                    checked = uiState.favoritesOnly,
                    accent = NodeFavoritePurple,
                    testTag = "chip_favorites_only",
                    onToggle = onToggleFavoritesOnly
                )
                if (onToggleAggregateCoverage != null) {
                    FilterSwitchRow(
                        icon = Icons.Default.Waves,
                        title = stringResource(R.string.aggregate_coverage_title),
                        checked = uiState.isAggregateCoverageEnabled,
                        accent = NodeDirectGreen,
                        testTag = "chip_aggregate_coverage",
                        onToggle = onToggleAggregateCoverage
                    )
                }
            }

            // Hardware Model Filters
            if (uiState.availableHwModels.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.filters_hardware_section),
                        style = MaterialTheme.typography.labelLarge,
                        color = TextSecondary
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        uiState.availableHwModels.forEach { hwModel ->
                            val selected = hwModel in uiState.selectedHwModels
                            FilterChip(
                                selected = selected,
                                onClick = { onToggleHwModelFilter(hwModel) },
                                label = {
                                    Text(
                                        text = hwModel,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontSize = 11.sp
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = TacticalSurfaceElevated,
                                    labelColor = TextSecondary,
                                    selectedContainerColor = NodeMultiHopSky.copy(alpha = 0.22f),
                                    selectedLabelColor = NodeMultiHopSky
                                ),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("filter_hw_$hwModel")
                            )
                        }
                    }
                }
            }

            // Node Role Filters
            if (uiState.availableRoles.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.filters_role_section),
                        style = MaterialTheme.typography.labelLarge,
                        color = TextSecondary
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        uiState.availableRoles.forEach { role ->
                            val selected = role in uiState.selectedRoles
                            FilterChip(
                                selected = selected,
                                onClick = { onToggleRoleFilter(role) },
                                label = {
                                    Text(
                                        text = role,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontSize = 11.sp
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = TacticalSurfaceElevated,
                                    labelColor = TextSecondary,
                                    selectedContainerColor = NodeDirectGreen.copy(alpha = 0.22f),
                                    selectedLabelColor = NodeDirectGreen
                                ),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("filter_role_$role")
                            )
                        }
                    }
                }
            }

            // Field Tools
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.filters_field_tools_section),
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary
                )
                if (onToggleAddNodeMode != null) {
                    ActionSheetRow(
                        icon = Icons.Default.AddLocationAlt,
                        title = stringResource(R.string.action_add_node),
                        badgeText = if (uiState.isAddNodeMode) "ACTIVE" else null,
                        accent = NodeDirectGreen,
                        isActive = uiState.isAddNodeMode,
                        testTag = "chip_add_node",
                        onClick = {
                            onToggleAddNodeMode()
                            onDismiss()
                        }
                    )
                }
                ActionSheetRow(
                    icon = Icons.Default.Terrain,
                    title = stringResource(R.string.filter_los_profile),
                    badgeText = if (uiState.isLosMode) "ACTIVE" else null,
                    accent = NodeMultiHopSky,
                    isActive = uiState.isLosMode,
                    testTag = "chip_los_profile",
                    onClick = {
                        onToggleLosMode()
                        onDismiss()
                    }
                )
                ActionSheetRow(
                    icon = Icons.Default.Straighten,
                    title = stringResource(R.string.filter_measure_rf),
                    badgeText = if (uiState.isMeasureMode) "ACTIVE" else null,
                    accent = NodeSelfAmber,
                    isActive = uiState.isMeasureMode,
                    testTag = "chip_measure_rf",
                    onClick = {
                        onToggleMeasureMode()
                        onDismiss()
                    }
                )
            }

            // Node Lists
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.filters_lists_section),
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary
                )
                ActionSheetRow(
                    icon = Icons.Default.Straighten,
                    title = stringResource(R.string.filter_direct_links),
                    badgeText = uiState.directRfLinksWithGps.size.toString(),
                    accent = NodeDirectGreen,
                    isActive = false,
                    testTag = "chip_direct_links",
                    onClick = {
                        onDismiss()
                        onOpenDirectLinksSheet()
                    }
                )
                ActionSheetRow(
                    icon = Icons.Default.GpsOff,
                    title = stringResource(R.string.filter_no_gps_list),
                    badgeText = uiState.noGpsCount.toString(),
                    accent = NodeSelfAmber,
                    isActive = false,
                    testTag = "chip_no_gps_list",
                    onClick = {
                        onDismiss()
                        onOpenNoGpsSheet()
                    }
                )
            }
        }
    }
}

@Composable
private fun DatasetStatBadge(label: String, count: Int, dotColor: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(TacticalSurface)
            .border(1.dp, TacticalOutline.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = "$label ",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
        Text(
            text = count.toString(),
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            color = TextPrimary
        )
    }
}

@Composable
private fun FilterSwitchRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    accent: Color,
    testTag: String,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(TacticalSurfaceElevated)
            .border(
                width = 1.dp,
                color = if (checked) accent.copy(alpha = 0.5f) else TacticalOutline,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (checked) accent else TextMuted,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = TacticalCanvas,
                checkedTrackColor = accent,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = TacticalSurfaceBright,
                uncheckedBorderColor = TacticalOutline
            )
        )
    }
}

@Composable
private fun ActionSheetRow(
    icon: ImageVector,
    title: String,
    badgeText: String?,
    accent: Color,
    isActive: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isActive) accent.copy(alpha = 0.14f) else TacticalSurfaceElevated)
            .border(
                width = 1.dp,
                color = if (isActive) accent.copy(alpha = 0.7f) else TacticalOutline,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                color = TextPrimary
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (badgeText != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(accent.copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = badgeText,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = accent
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
