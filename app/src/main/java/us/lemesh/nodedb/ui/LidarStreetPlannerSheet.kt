package us.lemesh.nodedb.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import us.lemesh.nodedb.R
import us.lemesh.nodedb.data.FadeMarginPreset
import us.lemesh.nodedb.data.LidarDataSource
import us.lemesh.nodedb.data.LidarViewshedEngine
import us.lemesh.nodedb.data.LidarViewshedResult
import us.lemesh.nodedb.data.MeshNode
import us.lemesh.nodedb.ui.theme.JetBrainsMonoFontFamily
import us.lemesh.nodedb.ui.theme.NodeDirectGreen
import us.lemesh.nodedb.ui.theme.NodeMultiHopSky
import us.lemesh.nodedb.ui.theme.NodeSelfAmber
import us.lemesh.nodedb.ui.theme.TacticalCanvas
import us.lemesh.nodedb.ui.theme.TacticalOutline
import us.lemesh.nodedb.ui.theme.TacticalSurface
import us.lemesh.nodedb.ui.theme.TacticalSurfaceElevated
import us.lemesh.nodedb.ui.theme.TextMuted
import us.lemesh.nodedb.ui.theme.TextPrimary
import us.lemesh.nodedb.ui.theme.TextSecondary
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Row 2 controls rendered inside [UnifiedHorizonTopCard] when [WorkspaceMode.LIDAR_STREET] is active:
 * Single TX site summary, Drop TX at map center, and finest-resolution elevation source badge.
 */
@Composable
internal fun ColumnScope.UnifiedLidarTopCardBody(
    uiState: LidarStreetUiState,
    onDropAtCenter: () -> Unit
) {
    val sourceAccent = lidarDataSourceColor(uiState.activeDataSource)
    val elevStr = uiState.groundElevationMeters?.let {
        String.format(Locale.US, "%.1fm", it)
    } ?: if (uiState.isGroundElevLoading) "LiDAR…" else "—"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (uiState.hasTxSet) {
                        "TX: ${uiState.siteLabel}"
                    } else {
                        "Tap map or Drop TX to begin survey"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = NodeSelfAmber,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (uiState.hasTxSet) {
                        String.format(
                            Locale.US,
                            "%.5f, %.5f • %s • TX %dft • RX %dft",
                            uiState.txLat,
                            uiState.txLon,
                            elevStr,
                            uiState.txHeightFt.roundToInt(),
                            uiState.rxHeightFt.roundToInt()
                        )
                    } else {
                        "RX ${uiState.rxHeightFt.roundToInt()} ft AGL • 1.0 m viewshed"
                    },
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 10.5.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Button(
                onClick = onDropAtCenter,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NodeSelfAmber,
                    contentColor = TacticalCanvas
                ),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("lidar_drop_pin_button")
            ) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = "Drop TX pin at map center",
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.lidar_drop_pin),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Tap map or drag TX • SX1262 LongFast -134 dBm RX",
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 10.sp,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            LidarSourceBadge(
                source = uiState.activeDataSource,
                label = uiState.activeShortSourceLabel,
                accentColor = sourceAccent
            )
        }
    }
}

@Composable
internal fun CompactLidarStreetBottomDrawer(
    uiState: LidarStreetUiState,
    plottedNodes: List<MeshNode>,
    controlsExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSelectTxNode: (MeshNode) -> Unit,
    onTxHeightSliderChange: (Float) -> Unit,
    onTxHeightTextChange: (String) -> Unit,
    onRxHeightSliderChange: (Float) -> Unit,
    onRxHeightTextChange: (String) -> Unit,
    onRadiusSliderChange: (Int) -> Unit,
    onRadiusTextChange: (String) -> Unit,
    onFrequencyChange: (String) -> Unit,
    onTxPowerChange: (Float) -> Unit,
    onAntennaGainChange: (Float) -> Unit,
    onCableLossChange: (Float) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onCalculateViewshed: () -> Unit,
    onImportLocalLidar: () -> Unit,
    onExportGeoTiff: () -> Unit = {},
    onClearImportedLidar: () -> Unit,
    onDismissExportStatus: () -> Unit = {},
    onDismissError: () -> Unit = {},
    onSelectFadeMarginPreset: ((FadeMarginPreset) -> Unit)? = null
) {
    Surface(
        color = TacticalSurface.copy(alpha = 0.96f),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, TacticalOutline, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            LidarDrawerHeader(
                uiState = uiState,
                controlsExpanded = controlsExpanded,
                onCalculateViewshed = onCalculateViewshed,
                onToggleExpanded = onToggleExpanded
            )
            AnimatedVisibility(visible = controlsExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 390.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LidarStreetControlsContent(
                        uiState = uiState,
                        plottedNodes = plottedNodes,
                        onSelectTxNode = onSelectTxNode,
                        onTxHeightSliderChange = onTxHeightSliderChange,
                        onTxHeightTextChange = onTxHeightTextChange,
                        onRxHeightSliderChange = onRxHeightSliderChange,
                        onRxHeightTextChange = onRxHeightTextChange,
                        onRadiusSliderChange = onRadiusSliderChange,
                        onRadiusTextChange = onRadiusTextChange,
                        onFrequencyChange = onFrequencyChange,
                        onTxPowerChange = onTxPowerChange,
                        onAntennaGainChange = onAntennaGainChange,
                        onCableLossChange = onCableLossChange,
                        onOpacityChange = onOpacityChange,
                        onCalculateViewshed = onCalculateViewshed,
                        onImportLocalLidar = onImportLocalLidar,
                        onExportGeoTiff = onExportGeoTiff,
                        onClearImportedLidar = onClearImportedLidar,
                        onDismissExportStatus = onDismissExportStatus,
                        onDismissError = onDismissError,
                        onSelectFadeMarginPreset = onSelectFadeMarginPreset
                    )
                }
            }
        }
    }
}

@Composable
internal fun LidarStreetModeSidePanel(
    uiState: LidarStreetUiState,
    plottedNodes: List<MeshNode>,
    onSelectTxNode: (MeshNode) -> Unit,
    onTxHeightSliderChange: (Float) -> Unit,
    onTxHeightTextChange: (String) -> Unit,
    onRxHeightSliderChange: (Float) -> Unit,
    onRxHeightTextChange: (String) -> Unit,
    onRadiusSliderChange: (Int) -> Unit,
    onRadiusTextChange: (String) -> Unit,
    onFrequencyChange: (String) -> Unit,
    onTxPowerChange: (Float) -> Unit,
    onAntennaGainChange: (Float) -> Unit,
    onCableLossChange: (Float) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onCalculateViewshed: () -> Unit,
    onImportLocalLidar: () -> Unit,
    onExportGeoTiff: () -> Unit = {},
    onClearImportedLidar: () -> Unit,
    onDismissExportStatus: () -> Unit = {},
    onDismissError: () -> Unit = {},
    onSelectFadeMarginPreset: ((FadeMarginPreset) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(14.dp)
            .testTag("lidar_side_panel"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        LidarDrawerHeader(
            uiState = uiState,
            controlsExpanded = true,
            onCalculateViewshed = onCalculateViewshed,
            onToggleExpanded = {}
        )
        HorizontalDivider(color = TacticalOutline.copy(alpha = 0.6f))
        LidarStreetControlsContent(
            uiState = uiState,
            plottedNodes = plottedNodes,
            onSelectTxNode = onSelectTxNode,
            onTxHeightSliderChange = onTxHeightSliderChange,
            onTxHeightTextChange = onTxHeightTextChange,
            onRxHeightSliderChange = onRxHeightSliderChange,
            onRxHeightTextChange = onRxHeightTextChange,
            onRadiusSliderChange = onRadiusSliderChange,
            onRadiusTextChange = onRadiusTextChange,
            onFrequencyChange = onFrequencyChange,
            onTxPowerChange = onTxPowerChange,
            onAntennaGainChange = onAntennaGainChange,
            onCableLossChange = onCableLossChange,
            onOpacityChange = onOpacityChange,
            onCalculateViewshed = onCalculateViewshed,
            onImportLocalLidar = onImportLocalLidar,
            onExportGeoTiff = onExportGeoTiff,
            onClearImportedLidar = onClearImportedLidar,
            onDismissExportStatus = onDismissExportStatus,
            onDismissError = onDismissError,
            onSelectFadeMarginPreset = onSelectFadeMarginPreset
        )
    }
}

@Composable
private fun LidarDrawerHeader(
    uiState: LidarStreetUiState,
    controlsExpanded: Boolean,
    onCalculateViewshed: () -> Unit,
    onToggleExpanded: () -> Unit
) {
    val result = uiState.currentResult
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onToggleExpanded)
                .padding(vertical = 2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Terrain,
                    contentDescription = null,
                    tint = NodeDirectGreen,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(R.string.lidar_street_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val summary = if (result != null) {
                String.format(
                    Locale.US,
                    "%.2f ac • %.0fm max • %.0f%% F1 • %s",
                    result.coveredAreaAcres,
                    result.maxClearRangeMeters,
                    result.corridorClearancePct,
                    result.shortSourceLabel
                )
            } else {
                val txFt = uiState.txHeightFt.roundToInt()
                val rxFt = uiState.rxHeightFt.roundToInt()
                "TX ${txFt}ft • RX ${rxFt}ft • ${uiState.radiusMeters}m • ${uiState.activeShortSourceLabel}"
            }
            Text(
                text = summary,
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 11.sp,
                color = if (result != null) NodeDirectGreen else TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Button(
                onClick = onCalculateViewshed,
                enabled = uiState.hasTxSet && !uiState.isCalculating,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NodeDirectGreen,
                    contentColor = TacticalCanvas
                ),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("lidar_quick_solve_button")
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(3.dp))
                Text("Scan", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }

            IconButton(
                onClick = onToggleExpanded,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("lidar_drawer_toggle")
            ) {
                Icon(
                    imageVector = if (controlsExpanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                    contentDescription = if (controlsExpanded) "Collapse LiDAR drawer" else "Expand LiDAR drawer",
                    tint = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun LidarStreetControlsContent(
    uiState: LidarStreetUiState,
    plottedNodes: List<MeshNode>,
    onSelectTxNode: (MeshNode) -> Unit,
    onTxHeightSliderChange: (Float) -> Unit,
    onTxHeightTextChange: (String) -> Unit,
    onRxHeightSliderChange: (Float) -> Unit,
    onRxHeightTextChange: (String) -> Unit,
    onRadiusSliderChange: (Int) -> Unit,
    onRadiusTextChange: (String) -> Unit,
    onFrequencyChange: (String) -> Unit,
    onTxPowerChange: (Float) -> Unit,
    onAntennaGainChange: (Float) -> Unit,
    onCableLossChange: (Float) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onCalculateViewshed: () -> Unit,
    onImportLocalLidar: () -> Unit,
    onExportGeoTiff: () -> Unit = {},
    onClearImportedLidar: () -> Unit,
    onDismissExportStatus: () -> Unit = {},
    onDismissError: () -> Unit = {},
    onSelectFadeMarginPreset: ((FadeMarginPreset) -> Unit)? = null
) {
    uiState.currentResult?.let { result ->
        LidarCoverageScorecard(result = result)
    }

    RfEndpointCard(
        role = "TX",
        label = uiState.siteLabel,
        lat = uiState.txLat,
        lon = uiState.txLon,
        groundElevationMeters = uiState.groundElevationMeters,
        isElevLoading = uiState.isGroundElevLoading,
        accentColor = NodeSelfAmber,
        plottedNodes = plottedNodes,
        pickNodeTestTag = "select_lidar_tx_node_button",
        onSelectNode = onSelectTxNode
    )

    // TX Height AGL (0–50 ft)
    LidarHybridNumericSliderRow(
        label = "TX Height AGL (0–50 ft)",
        dualSubLabel = String.format(
            Locale.US,
            "%.1f m AGL",
            uiState.txHeightFt * LidarViewshedEngine.FEET_TO_METERS
        ),
        value = uiState.txHeightFt,
        valueRange = LidarViewshedEngine.MIN_TX_HEIGHT_FT..LidarViewshedEngine.MAX_TX_HEIGHT_FT,
        steps = 49,
        textValue = uiState.txHeightText,
        unitSuffix = "ft",
        accentColor = NodeSelfAmber,
        sliderTag = "lidar_tx_height_slider",
        inputTag = "lidar_tx_height_input",
        onSliderChange = onTxHeightSliderChange,
        onTextChange = onTxHeightTextChange
    )

    // RX Target Height AGL (0–10 ft)
    LidarHybridNumericSliderRow(
        label = "RX Target Height AGL (0–10 ft)",
        dualSubLabel = String.format(
            Locale.US,
            "%.1f m AGL • Viewshed target slice",
            uiState.rxHeightFt * LidarViewshedEngine.FEET_TO_METERS
        ),
        value = uiState.rxHeightFt,
        valueRange = LidarViewshedEngine.MIN_RX_HEIGHT_FT..LidarViewshedEngine.MAX_RX_HEIGHT_FT,
        steps = 9,
        textValue = uiState.rxHeightText,
        unitSuffix = "ft",
        accentColor = NodeMultiHopSky,
        sliderTag = "lidar_rx_height_slider",
        inputTag = "lidar_rx_height_input",
        onSliderChange = onRxHeightSliderChange,
        onTextChange = onRxHeightTextChange
    )

    LidarHybridNumericSliderRow(
        label = "Street Scan Radius (100–1,500 m)",
        dualSubLabel = String.format(
            Locale.US,
            "%.0f ft • %.2f mi • 1.0 m ray step",
            uiState.radiusMeters * LidarViewshedEngine.METERS_TO_FEET,
            uiState.radiusMeters / 1609.344
        ),
        value = uiState.radiusMeters.toFloat(),
        valueRange = LidarViewshedEngine.MIN_RADIUS_METERS.toFloat()..LidarViewshedEngine.MAX_RADIUS_METERS.toFloat(),
        steps = 27,
        textValue = uiState.radiusText,
        unitSuffix = "m",
        accentColor = NodeDirectGreen,
        sliderTag = "lidar_radius_slider",
        inputTag = "lidar_radius_input",
        onSliderChange = { onRadiusSliderChange(it.roundToInt()) },
        onTextChange = onRadiusTextChange
    )

    // Read-only Semtech SX1262 LongFast RX baseline pill
    LidarSx1262SpecPill()

    LidarRadioAccordion(
        uiState = uiState,
        onFrequencyChange = onFrequencyChange,
        onTxPowerChange = onTxPowerChange,
        onAntennaGainChange = onAntennaGainChange,
        onCableLossChange = onCableLossChange,
        onOpacityChange = onOpacityChange
    )

    if (onSelectFadeMarginPreset != null) {
        PropagationFadeMarginStrip(
            selectedPreset = uiState.fadeMarginPreset,
            onSelectPreset = onSelectFadeMarginPreset,
            testTagPrefix = "lidar"
        )
    }

    Button(
        onClick = onCalculateViewshed,
        enabled = uiState.hasTxSet && !uiState.isCalculating && !uiState.isExportingGeoTiff,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = NodeDirectGreen,
            contentColor = TacticalCanvas
        ),
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .testTag("lidar_calculate_button")
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.lidar_calculate_viewshed),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = onImportLocalLidar,
            enabled = !uiState.isCalculating && !uiState.isExportingGeoTiff,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, NodeMultiHopSky.copy(alpha = 0.7f)),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = TacticalSurfaceElevated,
                contentColor = NodeMultiHopSky
            ),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            modifier = Modifier
                .weight(1f)
                .minimumInteractiveComponentSize()
                .testTag("lidar_import_file_button")
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = stringResource(R.string.lidar_import_local_grid),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        val canExportGeoTiff = (uiState.hasTxSet || uiState.activePatch != null) &&
            !uiState.isCalculating &&
            !uiState.isExportingGeoTiff
        OutlinedButton(
            onClick = onExportGeoTiff,
            enabled = canExportGeoTiff,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(
                1.dp,
                if (canExportGeoTiff) NodeSelfAmber.copy(alpha = 0.75f) else TacticalOutline
            ),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = TacticalSurfaceElevated,
                contentColor = NodeSelfAmber
            ),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            modifier = Modifier
                .weight(1f)
                .minimumInteractiveComponentSize()
                .testTag("lidar_export_geotiff_button")
        ) {
            if (uiState.isExportingGeoTiff) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    color = NodeSelfAmber,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = if (uiState.isExportingGeoTiff) "Saving…" else stringResource(R.string.lidar_export_geotiff),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    if (uiState.exportStatusMessage != null) {
        Surface(
            color = NodeDirectGreen.copy(alpha = 0.12f),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, NodeDirectGreen.copy(alpha = 0.55f)),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("lidar_export_status_banner")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = uiState.exportStatusMessage,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 11.sp,
                    color = NodeDirectGreen,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onDismissExportStatus,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("lidar_dismiss_export_status_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss GeoTIFF export status",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }

    if (uiState.errorMessage != null) {
        val errorColor = MaterialTheme.colorScheme.error
        Surface(
            color = errorColor.copy(alpha = 0.12f),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, errorColor.copy(alpha = 0.55f)),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("lidar_error_banner")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = uiState.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = errorColor,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onDismissError,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("lidar_dismiss_error_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss LiDAR error",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }

    if (uiState.importedFileName != null) {
        Surface(
            color = NodeDirectGreen.copy(alpha = 0.12f),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, NodeDirectGreen.copy(alpha = 0.55f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Local DSM/DTM: ${uiState.importedFileName}",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 11.sp,
                    color = NodeDirectGreen,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onClearImportedLidar,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("lidar_clear_imported_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear imported local LiDAR grid",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LidarSx1262SpecPill() {
    Surface(
        color = NodeMultiHopSky.copy(alpha = 0.10f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, NodeMultiHopSky.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("lidar_sx1262_spec_pill")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Sensors,
                contentDescription = null,
                tint = NodeMultiHopSky,
                modifier = Modifier.size(16.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Semtech SX1262 RX Baseline (Fixed)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = NodeMultiHopSky,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.lidar_sx1262_rx_summary),
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 10.sp,
                    color = TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun LidarCoverageScorecard(result: LidarViewshedResult) {
    val accent = lidarDataSourceColor(result.dataSource)
    Surface(
        color = TacticalSurfaceElevated,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, accent.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
            .testTag("lidar_scorecard")
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                LidarSourceBadge(
                    source = result.dataSource,
                    label = result.sourceLabel,
                    accentColor = accent
                )
                Text(
                    text = "${result.computeTimeMs} ms • 1.0 m step",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 10.sp,
                    color = TextSecondary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LidarMetricTile(
                    label = "Covered Area",
                    primary = String.format(Locale.US, "%.2f ac", result.coveredAreaAcres),
                    secondary = String.format(Locale.US, "%,.0f m²", result.coveredAreaSqMeters),
                    accentColor = NodeDirectGreen,
                    modifier = Modifier.weight(1f)
                )
                LidarMetricTile(
                    label = "Max Clear Range",
                    primary = String.format(Locale.US, "%.0f m", result.maxClearRangeMeters),
                    secondary = String.format(
                        Locale.US,
                        "%.0f ft",
                        result.maxClearRangeMeters * LidarViewshedEngine.METERS_TO_FEET
                    ),
                    accentColor = NodeMultiHopSky,
                    modifier = Modifier.weight(1f)
                )
                LidarMetricTile(
                    label = "Corridor ≥60% F1",
                    primary = String.format(Locale.US, "%.1f%%", result.corridorClearancePct),
                    secondary = String.format(
                        Locale.US,
                        "TX %.0fft / RX %.0fft",
                        result.params.txHeightFt,
                        result.params.rxHeightFt
                    ),
                    accentColor = NodeSelfAmber,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun LidarMetricTile(
    label: String,
    primary: String,
    secondary: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(TacticalCanvas.copy(alpha = 0.72f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = primary,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = accentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = secondary,
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 10.sp,
            color = TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LidarHybridNumericSliderRow(
    label: String,
    dualSubLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    textValue: String,
    unitSuffix: String,
    accentColor: Color,
    sliderTag: String,
    inputTag: String,
    onSliderChange: (Float) -> Unit,
    onTextChange: (String) -> Unit
) {
    Surface(
        color = TacticalSurfaceElevated,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, TacticalOutline.copy(alpha = 0.75f), RoundedCornerShape(10.dp))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = dualSubLabel,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 10.sp,
                        color = TextSecondary
                    )
                }
                OutlinedTextField(
                    value = textValue,
                    onValueChange = onTextChange,
                    singleLine = true,
                    suffix = {
                        Text(
                            text = unitSuffix,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 10.sp,
                            color = TextSecondary
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    ),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = TacticalCanvas,
                        unfocusedContainerColor = TacticalCanvas,
                        focusedBorderColor = accentColor,
                        unfocusedBorderColor = TacticalOutline
                    ),
                    modifier = Modifier
                        .width(96.dp)
                        .testTag(inputTag)
                )
            }
            Slider(
                value = value.coerceIn(valueRange.start, valueRange.endInclusive),
                onValueChange = onSliderChange,
                valueRange = valueRange,
                steps = steps,
                colors = SliderDefaults.colors(
                    thumbColor = accentColor,
                    activeTrackColor = accentColor,
                    inactiveTrackColor = TacticalOutline
                ),
                modifier = Modifier
                    .height(28.dp)
                    .testTag(sliderTag)
            )
        }
    }
}

@Composable
private fun LidarRadioAccordion(
    uiState: LidarStreetUiState,
    onFrequencyChange: (String) -> Unit,
    onTxPowerChange: (Float) -> Unit,
    onAntennaGainChange: (Float) -> Unit,
    onCableLossChange: (Float) -> Unit,
    onOpacityChange: (Float) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

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
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("lidar_advanced_rf_toggle"),
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
                            text = stringResource(R.string.lidar_tx_rf_params_section),
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary
                        )
                        Text(
                            text = String.format(
                                Locale.US,
                                "%s MHz • %d dBm • %.1f dBi • -%.1f dB loss",
                                uiState.frequencyText.ifBlank { "906.875" },
                                uiState.txPowerDbm.roundToInt(),
                                uiState.antennaGainDbi,
                                uiState.cableLossDb
                            ),
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 10.sp,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Toggle TX RF parameters",
                    tint = TextSecondary
                )
            }

            AnimatedVisibility(visible = expanded) {
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
                            value = uiState.frequencyText,
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
                        Column(modifier = Modifier.weight(1.3f)) {
                            RfLabeledSlider(
                                label = "TX Power",
                                valueText = "${uiState.txPowerDbm.roundToInt()} dBm",
                                value = uiState.txPowerDbm,
                                valueRange = 1f..30f,
                                accentColor = NodeDirectGreen,
                                sliderTestTag = "lidar_tx_power_slider",
                                onValueChange = onTxPowerChange
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            RfLabeledSlider(
                                label = "Ant Gain",
                                valueText = String.format(Locale.US, "%.1f dBi", uiState.antennaGainDbi),
                                value = uiState.antennaGainDbi,
                                valueRange = -3f..12f,
                                accentColor = NodeDirectGreen,
                                sliderTestTag = "lidar_gain_slider",
                                onValueChange = onAntennaGainChange
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            RfLabeledSlider(
                                label = "Cable Loss",
                                valueText = String.format(Locale.US, "%.1f dB", uiState.cableLossDb),
                                value = uiState.cableLossDb,
                                valueRange = 0f..6f,
                                accentColor = NodeSelfAmber,
                                sliderTestTag = "lidar_cable_loss_slider",
                                onValueChange = onCableLossChange
                            )
                        }
                    }

                    RfLabeledSlider(
                        label = "Overlay Opacity",
                        valueText = "${(uiState.overlayOpacity * 100).roundToInt()}%",
                        value = uiState.overlayOpacity,
                        valueRange = 0.15f..0.95f,
                        accentColor = NodeMultiHopSky,
                        sliderTestTag = "lidar_opacity_slider",
                        onValueChange = onOpacityChange
                    )
                }
            }
        }
    }
}

@Composable
internal fun LidarSourceBadge(
    source: LidarDataSource,
    label: String,
    accentColor: Color = lidarDataSourceColor(source)
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(accentColor.copy(alpha = 0.16f))
            .border(1.dp, accentColor.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .testTag("lidar_source_badge"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(accentColor)
        )
        Text(
            text = label,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            color = accentColor,
            maxLines = 1
        )
    }
}

internal fun lidarDataSourceColor(source: LidarDataSource): Color = when (source) {
    LidarDataSource.USGS_3DEP_1M,
    LidarDataSource.LOCAL_GEOTIFF,
    LidarDataSource.LOCAL_ASCII_GRID -> NodeDirectGreen
    LidarDataSource.CACHED_3DEP_1M -> NodeMultiHopSky
    LidarDataSource.TERRARIUM_10M_FALLBACK -> NodeSelfAmber
}
