package us.lemesh.nodedb.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import us.lemesh.nodedb.R
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import us.lemesh.nodedb.data.FadeMarginPreset
import us.lemesh.nodedb.data.LinkDiagnosticSummary
import us.lemesh.nodedb.data.LinkDiagnosticTier
import us.lemesh.nodedb.data.LosAnalysisResult
import us.lemesh.nodedb.data.LosEndpoint
import us.lemesh.nodedb.data.LosStatus
import us.lemesh.nodedb.data.MeshNode
import us.lemesh.nodedb.data.RfViabilityTier
import us.lemesh.nodedb.ui.theme.AlertErrorRed
import us.lemesh.nodedb.ui.theme.JetBrainsMonoFontFamily
import us.lemesh.nodedb.ui.theme.NodeDirectGreen
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
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun LosProfileDrawer(
    pointA: LosEndpoint?,
    pointB: LosEndpoint?,
    selfNode: MeshNode?,
    txHeightText: String,
    rxHeightText: String,
    frequencyText: String,
    result: LosAnalysisResult?,
    isLoading: Boolean,
    errorMessage: String?,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onTxHeightChange: (String) -> Unit,
    onRxHeightChange: (String) -> Unit,
    onFrequencyChange: (String) -> Unit,
    onUseSelfAsPointA: () -> Unit,
    onSwapEndpoints: () -> Unit,
    onClearEndpoints: () -> Unit,
    onRetryElevation: () -> Unit,
    onUseFlatFallback: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    txPowerText: String = "22",
    txGainText: String = "3.0",
    rxGainText: String = "3.0",
    sensitivityText: String = "-130",
    onTxPowerChange: (String) -> Unit = {},
    onTxGainChange: (String) -> Unit = {},
    onRxGainChange: (String) -> Unit = {},
    onSensitivityChange: (String) -> Unit = {},
    onApplyMastFix: () -> Unit = {},
    isOfflineTileMissing: Boolean = false,
    onOpenOfflineMaps: (() -> Unit)? = null,
    observedDiagnostic: LinkDiagnosticSummary? = null,
    fadeMarginPreset: FadeMarginPreset = FadeMarginPreset.WINTER_CLEAR,
    onSelectFadeMarginPreset: ((FadeMarginPreset) -> Unit)? = null
) {
    val statusColor = result?.viabilityTier?.color ?: NodeMultiHopSky
    var rfBudgetExpanded by remember { mutableStateOf(false) }

    Surface(
        color = TacticalSurface.copy(alpha = 0.96f),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.5.dp, statusColor.copy(alpha = 0.7f), RoundedCornerShape(18.dp))
            .testTag("los_profile_drawer")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            // Collapsible Drawer Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Terrain,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = result?.viabilityTier?.label ?: "Fresnel & LoS Calculator",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (result != null) statusColor else TextPrimary
                            )
                            if (result != null) {
                                Text(
                                    text = String.format(Locale.US, "%.2f km", result.totalDistanceKm),
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(TacticalSurfaceBright.copy(alpha = 0.8f))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                        .testTag("los_elevation_source_pill")
                                ) {
                                    Text(
                                        text = result.elevationSource.label,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontSize = 9.sp,
                                        color = NodeMultiHopSky,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                        Text(
                            text = "${pointA?.shortLabel ?: "Tap Pt A"} → ${pointB?.shortLabel ?: "Tap Pt B"}",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 11.sp,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onToggleExpanded,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("los_collapse_toggle")
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                            contentDescription = if (isExpanded) "Collapse LoS drawer" else "Expand LoS drawer",
                            tint = TextSecondary
                        )
                    }
                    TextButton(
                        onClick = onDone,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("los_done_button")
                    ) {
                        Text(
                            text = "Done",
                            style = MaterialTheme.typography.labelLarge,
                            color = statusColor
                        )
                    }
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 430.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Selection helper & endpoint actions
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (selfNode?.hasValidGps == true && pointA?.node?.id != selfNode.id) {
                            OutlinedButton(
                                onClick = onUseSelfAsPointA,
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("los_from_self_button")
                            ) {
                                Text(
                                    text = "TX = Self",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = NodeSelfAmber
                                )
                            }
                        }
                        if (pointA != null && pointB != null) {
                            OutlinedButton(
                                onClick = onSwapEndpoints,
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("los_swap_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SwapHoriz,
                                    contentDescription = null,
                                    tint = NodeMultiHopSky,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Swap",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = NodeMultiHopSky
                                )
                            }
                        }
                        if (pointA != null || pointB != null) {
                            TextButton(
                                onClick = onClearEndpoints,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("los_clear_button")
                            ) {
                                Text(
                                    text = "Clear",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextSecondary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        if (pointA == null || pointB == null) {
                            Text(
                                text = if (pointA == null) "Tap map/node for A" else "Tap map/node for B",
                                style = MaterialTheme.typography.bodySmall,
                                color = NodeMultiHopSky
                            )
                        }
                    }

                    // Editable Antenna Heights & Frequency Inputs
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CompactNumericField(
                            label = "TX Ht (m)",
                            value = txHeightText,
                            onValueChange = onTxHeightChange,
                            testTag = "los_tx_height_input",
                            modifier = Modifier.weight(1f)
                        )
                        CompactNumericField(
                            label = "RX Ht (m)",
                            value = rxHeightText,
                            onValueChange = onRxHeightChange,
                            testTag = "los_rx_height_input",
                            modifier = Modifier.weight(1f)
                        )
                        CompactNumericField(
                            label = "Freq (MHz)",
                            value = frequencyText,
                            onValueChange = onFrequencyChange,
                            testTag = "los_freq_input",
                            modifier = Modifier.weight(1.25f)
                        )
                    }

                    // Expandable RF Radio Budget
                    Surface(
                        color = TacticalSurfaceElevated,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, TacticalOutline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .minimumInteractiveComponentSize()
                                    .clickable { rfBudgetExpanded = !rfBudgetExpanded }
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                                    .testTag("los_rf_budget_toggle"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = null,
                                        tint = NodeMultiHopSky,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "RF Radio Budget",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = TextPrimary
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "$txPowerText dBm • $txGainText/$rxGainText dBi • $sensitivityText",
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontSize = 10.sp,
                                        color = TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = if (rfBudgetExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        tint = TextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            AnimatedVisibility(visible = rfBudgetExpanded) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    CompactNumericField(
                                        label = "TX dBm",
                                        value = txPowerText,
                                        onValueChange = onTxPowerChange,
                                        testTag = "los_tx_power_input",
                                        modifier = Modifier.weight(1f)
                                    )
                                    CompactNumericField(
                                        label = "TX dBi",
                                        value = txGainText,
                                        onValueChange = onTxGainChange,
                                        testTag = "los_tx_gain_input",
                                        modifier = Modifier.weight(1f)
                                    )
                                    CompactNumericField(
                                        label = "RX dBi",
                                        value = rxGainText,
                                        onValueChange = onRxGainChange,
                                        testTag = "los_rx_gain_input",
                                        modifier = Modifier.weight(1f)
                                    )
                                    CompactNumericField(
                                        label = "Sens dBm",
                                        value = sensitivityText,
                                        onValueChange = onSensitivityChange,
                                        testTag = "los_sensitivity_input",
                                        allowNegative = true,
                                        modifier = Modifier.weight(1.1f)
                                    )
                                }
                            }
                        }
                    }

                    if (onSelectFadeMarginPreset != null) {
                        PropagationFadeMarginStrip(
                            selectedPreset = fadeMarginPreset,
                            onSelectPreset = onSelectFadeMarginPreset,
                            testTagPrefix = "los"
                        )
                    }

                    if (isLoading) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(TacticalSurfaceElevated)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Sampling Terrarium DEM / terrain profile…",
                                style = MaterialTheme.typography.bodySmall,
                                color = NodeMultiHopSky
                            )
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = NodeMultiHopSky,
                                trackColor = TacticalSurfaceBright
                            )
                        }
                    }

                    if (errorMessage != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(AlertErrorRed.copy(alpha = 0.16f))
                                .border(1.dp, AlertErrorRed.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary
                            )
                            @OptIn(ExperimentalLayoutApi::class)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onRetryElevation,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        tint = TextPrimary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Retry", style = MaterialTheme.typography.labelMedium, color = TextPrimary)
                                }
                                OutlinedButton(
                                    onClick = onUseFlatFallback,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Text(
                                        "Use Flat Fallback",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = NodeSelfAmber
                                    )
                                }
                                if (isOfflineTileMissing && onOpenOfflineMaps != null) {
                                    OutlinedButton(
                                        onClick = onOpenOfflineMaps,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, NodeMultiHopSky.copy(alpha = 0.7f)),
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("open_offline_maps_from_error")
                                    ) {
                                        Text(
                                            stringResource(R.string.offline_missing_cta),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = NodeMultiHopSky
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (result != null) {
                        // RF Viability & Link Margin Summary Row
                        val marginColor = when {
                            result.linkMarginDb < 0.0 -> AlertErrorRed
                            result.linkMarginDb < 6.0 -> NodeSelfAmber
                            else -> NodeDirectGreen
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            LosReadoutBadge(
                                label = "Predicted RSSI",
                                value = String.format(Locale.US, "%.1f dBm", result.predictedRssiDbm),
                                valueColor = result.viabilityTier.color,
                                modifier = Modifier.weight(1f)
                            )
                            LosReadoutBadge(
                                label = "Link Margin",
                                value = String.format(Locale.US, "%+.1f dB", result.linkMarginDb),
                                valueColor = marginColor,
                                modifier = Modifier.weight(1f)
                            )
                            LosReadoutBadge(
                                label = "Peak 100% F1",
                                value = String.format(Locale.US, "%.1f m", result.maxFresnel1RadiusMeters),
                                valueColor = NodeMultiHopSky,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Fresnel Clearance & Diffraction Path Loss Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val clearancePct = result.worstClearancePercentFresnel
                                ?.let { String.format(Locale.US, "%.0f%% F1", it) } ?: "N/A"
                            LosReadoutBadge(
                                label = "Worst Clearance",
                                value = String.format(
                                    Locale.US,
                                    "%+.1f m (%s)",
                                    result.worstClearanceMeters,
                                    clearancePct
                                ),
                                valueColor = result.status.color,
                                modifier = Modifier.weight(1.05f)
                            )
                            LosReadoutBadge(
                                label = if (result.fadeMarginDb > 0.0) "FSPL + Diff + Fade" else "FSPL + Diff Loss",
                                value = if (result.fadeMarginDb > 0.0) {
                                    String.format(
                                        Locale.US,
                                        "%.1f + %.1f + %.0f dB",
                                        result.fsplDb,
                                        result.diffractionLossDb,
                                        result.fadeMarginDb
                                    )
                                } else {
                                    String.format(
                                        Locale.US,
                                        "%.1f + %.1f dB",
                                        result.fsplDb,
                                        result.diffractionLossDb
                                    )
                                },
                                valueColor = if (result.diffractionLossDb > 3.0) AlertErrorRed else TextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (observedDiagnostic != null) {
                            val diagColor = when (observedDiagnostic.tier) {
                                LinkDiagnosticTier.NOMINAL -> NodeDirectGreen
                                LinkDiagnosticTier.CLUTTER_LOSS -> NodeSelfAmber
                                LinkDiagnosticTier.SEVERE_ANOMALY -> AlertErrorRed
                            }
                            Surface(
                                color = diagColor.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, diagColor.copy(alpha = 0.65f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("los_observed_vs_predicted_card")
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(R.string.diagnostics_observed_vs_predicted),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = diagColor,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = String.format(
                                                Locale.US,
                                                "ΔSNR %+.1f dB • %s",
                                                observedDiagnostic.deltaSnrDb,
                                                observedDiagnostic.tier.shortLabel
                                            ),
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp,
                                            color = diagColor
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        LosReadoutBadge(
                                            label = "Obs vs Pred SNR",
                                            value = String.format(
                                                Locale.US,
                                                "%.1f / %.1f dB",
                                                observedDiagnostic.observedSnrDb,
                                                observedDiagnostic.predictedSnrDb
                                            ),
                                            valueColor = diagColor,
                                            modifier = Modifier.weight(1f)
                                        )
                                        LosReadoutBadge(
                                            label = "Obs vs Pred RSSI",
                                            value = if (observedDiagnostic.observedRssiDbm != null) {
                                                String.format(
                                                    Locale.US,
                                                    "%d / %.0f dBm",
                                                    observedDiagnostic.observedRssiDbm,
                                                    observedDiagnostic.predictedRssiDbm
                                                )
                                            } else {
                                                String.format(
                                                    Locale.US,
                                                    "N/A / %.0f dBm",
                                                    observedDiagnostic.predictedRssiDbm
                                                )
                                            },
                                            valueColor = TextPrimary,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    Text(
                                        text = observedDiagnostic.diagnosticNote,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 10.sp,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }

                        // Mast Fix Recommendation Banner when 60% F1 zone is intruded
                        if (result.requiredEqualMastDeltaMeters > 0.05) {
                            val bannerColor = if (result.status == LosStatus.OBSTRUCTED) AlertErrorRed else NodeSelfAmber
                            Surface(
                                color = bannerColor.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, bannerColor.copy(alpha = 0.6f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("los_mast_fix_banner")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Build,
                                        contentDescription = null,
                                        tint = bannerColor,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Mast Fix for 60% F1 Clearance",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = bannerColor
                                        )
                                        Text(
                                            text = String.format(
                                                Locale.US,
                                                "Raise TX +%.1fm, RX +%.1fm, or Both +%.1fm",
                                                result.requiredTxMastDeltaMeters,
                                                result.requiredRxMastDeltaMeters,
                                                result.requiredEqualMastDeltaMeters
                                            ),
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontSize = 10.sp,
                                            color = TextPrimary
                                        )
                                    }
                                    Button(
                                        onClick = onApplyMastFix,
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = bannerColor,
                                            contentColor = TacticalCanvas
                                        ),
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("los_apply_mast_fix_button")
                                    ) {
                                        Text(
                                            text = "Apply Fix",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

                        // Responsive Elevation Profile Chart
                        LosElevationProfileChart(
                            result = result,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(195.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactNumericField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    allowNegative: Boolean = false
) {
    val pattern = remember(allowNegative) {
        if (allowNegative) Regex("^-?\\d*\\.?\\d*$") else Regex("^\\d*\\.?\\d*$")
    }
    OutlinedTextField(
        value = value,
        onValueChange = { incoming ->
            if (incoming.isEmpty() || incoming.matches(pattern)) {
                onValueChange(incoming)
            }
        },
        label = {
            Text(
                text = label,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        textStyle = MaterialTheme.typography.bodySmall.copy(
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary
        ),
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = TacticalSurfaceElevated,
            unfocusedContainerColor = TacticalSurfaceElevated,
            focusedBorderColor = NodeMultiHopSky,
            unfocusedBorderColor = TacticalOutline,
            focusedLabelColor = NodeMultiHopSky,
            unfocusedLabelColor = TextMuted
        ),
        modifier = modifier.testTag(testTag)
    )
}

@Composable
private fun LosReadoutBadge(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(TacticalSurfaceElevated)
            .border(1.dp, TacticalOutline, RoundedCornerShape(9.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = TextMuted,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(1.dp))
        Text(
            text = value,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun LosElevationProfileChart(
    result: LosAnalysisResult,
    modifier: Modifier = Modifier
) {
    val samples = result.samples
    if (samples.size < 2) return

    val textMeasurer = rememberTextMeasurer()
    var scrubbedIndex by remember(result) { mutableStateOf<Int?>(null) }

    val minYRaw = samples.minOf {
        min(it.adjustedTerrainMeters, min(it.losRayMeters, it.fresnel100LowerMeters))
    }
    val maxYRaw = samples.maxOf {
        max(it.adjustedTerrainMeters, max(it.losRayMeters, it.fresnel100UpperMeters))
    }
    val span = max(maxYRaw - minYRaw, 20.0)
    val yMin = floor((minYRaw - span * 0.08) / 5.0) * 5.0
    val yMax = ceil((maxYRaw + span * 0.12) / 5.0) * 5.0
    val yRange = max(yMax - yMin, 10.0)

    val axisTextStyle = TextStyle(
        fontFamily = JetBrainsMonoFontFamily,
        fontSize = 9.sp,
        color = TextMuted
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(TacticalCanvas)
            .border(1.dp, TacticalOutline, RoundedCornerShape(12.dp))
            .padding(8.dp)
    ) {
        // Chart Legend + Active Scrub Readout
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val activeSample = scrubbedIndex?.let { samples.getOrNull(it) }
            if (activeSample != null) {
                val pctStr = activeSample.clearancePercentFresnel?.let {
                    String.format(Locale.US, " (%.0f%% F1)", it)
                }.orEmpty()
                Text(
                    text = String.format(
                        Locale.US,
                        "%.2f km • Terr %.0fm • Clr %+.1fm%s • F1 %.1fm",
                        activeSample.distanceFromAKm,
                        activeSample.rawTerrainMeters,
                        activeSample.clearanceMeters,
                        pctStr,
                        activeSample.fresnelRadiusMeters
                    ),
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 10.sp,
                    color = NodeMultiHopSky,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChartLegendDot("Terrain (4/3 Re)", Color(0xFF64748B))
                    ChartLegendDot("LoS Ray", result.status.color)
                    ChartLegendDot("60% / 100% F1", NodeMultiHopSky)
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(samples) {
                    detectTapGestures { offset ->
                        val leftPad = 38.dp.toPx()
                        val rightPad = 8.dp.toPx()
                        val plotW = (size.width - leftPad - rightPad).coerceAtLeast(1f)
                        val frac = ((offset.x - leftPad) / plotW).coerceIn(0f, 1f)
                        scrubbedIndex = (frac * (samples.size - 1)).roundToInt()
                    }
                }
                .pointerInput(samples) {
                    detectHorizontalDragGestures(
                        onDragEnd = { scrubbedIndex = null },
                        onDragCancel = { scrubbedIndex = null }
                    ) { change, _ ->
                        change.consume()
                        val leftPad = 38.dp.toPx()
                        val rightPad = 8.dp.toPx()
                        val plotW = (size.width - leftPad - rightPad).coerceAtLeast(1f)
                        val frac = ((change.position.x - leftPad) / plotW).coerceIn(0f, 1f)
                        scrubbedIndex = (frac * (samples.size - 1)).roundToInt()
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val leftPad = 38.dp.toPx()
                val rightPad = 8.dp.toPx()
                val topPad = 6.dp.toPx()
                val bottomPad = 18.dp.toPx()
                val plotW = (size.width - leftPad - rightPad).coerceAtLeast(1f)
                val plotH = (size.height - topPad - bottomPad).coerceAtLeast(1f)

                fun xFor(index: Int): Float =
                    leftPad + (index.toFloat() / (samples.size - 1).toFloat()) * plotW

                fun yFor(elevMeters: Double): Float {
                    val norm = ((elevMeters - yMin) / yRange).toFloat().coerceIn(0f, 1f)
                    return topPad + plotH * (1f - norm)
                }

                // Horizontal grid lines + Y elevation labels
                val yTicks = 4
                for (t in 0..yTicks) {
                    val frac = t.toFloat() / yTicks.toFloat()
                    val elev = yMin + frac * yRange
                    val y = topPad + plotH * (1f - frac)
                    drawLine(
                        color = Color(0xFF1E293B),
                        start = Offset(leftPad, y),
                        end = Offset(leftPad + plotW, y),
                        strokeWidth = 1.dp.toPx()
                    )
                    val labelLayout = textMeasurer.measure("${elev.roundToInt()}m", axisTextStyle)
                    drawText(
                        textLayoutResult = labelLayout,
                        topLeft = Offset(0f, (y - labelLayout.size.height / 2f).coerceIn(0f, size.height - labelLayout.size.height))
                    )
                }

                // X distance labels (0, Mid, Total)
                val xLabels = listOf(
                    0 to "0",
                    (samples.size - 1) / 2 to String.format(Locale.US, "%.1f", result.totalDistanceKm / 2.0),
                    samples.size - 1 to String.format(Locale.US, "%.1f km", result.totalDistanceKm)
                )
                xLabels.forEach { (idx, label) ->
                    val x = xFor(idx)
                    val measured = textMeasurer.measure(label, axisTextStyle)
                    val drawX = (x - measured.size.width / 2f).coerceIn(leftPad, size.width - measured.size.width)
                    drawText(
                        textLayoutResult = measured,
                        topLeft = Offset(drawX, topPad + plotH + 3.dp.toPx())
                    )
                }

                // 100% Fresnel Zone outer boundary dashed curves
                val fresnel100UpperPath = Path()
                val fresnel100LowerPath = Path()
                samples.forEachIndexed { idx, s ->
                    val x = xFor(idx)
                    val yUp100 = yFor(s.fresnel100UpperMeters)
                    val yLo100 = yFor(s.fresnel100LowerMeters)
                    if (idx == 0) {
                        fresnel100UpperPath.moveTo(x, yUp100)
                        fresnel100LowerPath.moveTo(x, yLo100)
                    } else {
                        fresnel100UpperPath.lineTo(x, yUp100)
                        fresnel100LowerPath.lineTo(x, yLo100)
                    }
                }
                val outerDashEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f), 0f)
                drawPath(
                    path = fresnel100UpperPath,
                    color = NodeMultiHopSky.copy(alpha = 0.35f),
                    style = Stroke(width = 1.0.dp.toPx(), pathEffect = outerDashEffect)
                )
                drawPath(
                    path = fresnel100LowerPath,
                    color = NodeMultiHopSky.copy(alpha = 0.42f),
                    style = Stroke(width = 1.1.dp.toPx(), pathEffect = outerDashEffect)
                )

                // 60% Fresnel Zone envelope fill
                val fresnelEnvelope = Path().apply {
                    samples.forEachIndexed { idx, s ->
                        val x = xFor(idx)
                        val y = yFor(s.fresnel60UpperMeters)
                        if (idx == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    for (idx in samples.indices.reversed()) {
                        lineTo(xFor(idx), yFor(samples[idx].fresnel60LowerMeters))
                    }
                    close()
                }
                drawPath(
                    path = fresnelEnvelope,
                    color = NodeMultiHopSky.copy(alpha = 0.14f)
                )

                // 60% Fresnel upper and lower dashed curves
                val fresnelUpperPath = Path()
                val fresnelLowerPath = Path()
                samples.forEachIndexed { idx, s ->
                    val x = xFor(idx)
                    val yUp = yFor(s.fresnel60UpperMeters)
                    val yLo = yFor(s.fresnel60LowerMeters)
                    if (idx == 0) {
                        fresnelUpperPath.moveTo(x, yUp)
                        fresnelLowerPath.moveTo(x, yLo)
                    } else {
                        fresnelUpperPath.lineTo(x, yUp)
                        fresnelLowerPath.lineTo(x, yLo)
                    }
                }
                val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                drawPath(
                    path = fresnelUpperPath,
                    color = NodeMultiHopSky.copy(alpha = 0.7f),
                    style = Stroke(width = 1.3.dp.toPx(), pathEffect = dashEffect)
                )
                drawPath(
                    path = fresnelLowerPath,
                    color = NodeMultiHopSky.copy(alpha = 0.9f),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = dashEffect)
                )

                // Filled Terrain Contour (adjusted for k=4/3 Earth curvature)
                val terrainFillPath = Path().apply {
                    moveTo(xFor(0), topPad + plotH)
                    samples.forEachIndexed { idx, s ->
                        lineTo(xFor(idx), yFor(s.adjustedTerrainMeters))
                    }
                    lineTo(xFor(samples.lastIndex), topPad + plotH)
                    close()
                }
                drawPath(
                    path = terrainFillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF475569).copy(alpha = 0.75f),
                            Color(0xFF0F172A).copy(alpha = 0.92f)
                        ),
                        startY = topPad,
                        endY = topPad + plotH
                    )
                )

                val terrainLinePath = Path().apply {
                    samples.forEachIndexed { idx, s ->
                        val x = xFor(idx)
                        val y = yFor(s.adjustedTerrainMeters)
                        if (idx == 0) moveTo(x, y) else lineTo(x, y)
                    }
                }
                drawPath(
                    path = terrainLinePath,
                    color = Color(0xFF94A3B8),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )

                // Antenna Mast Vertical Stems (TX & RX)
                val txBaseY = yFor(samples.first().adjustedTerrainMeters)
                val txTipY = yFor(samples.first().losRayMeters)
                val rxBaseY = yFor(samples.last().adjustedTerrainMeters)
                val rxTipY = yFor(samples.last().losRayMeters)
                drawLine(
                    color = NodeSelfAmber,
                    start = Offset(xFor(0), txBaseY),
                    end = Offset(xFor(0), txTipY),
                    strokeWidth = 2.5.dp.toPx()
                )
                drawLine(
                    color = NodeSelfAmber,
                    start = Offset(xFor(samples.lastIndex), rxBaseY),
                    end = Offset(xFor(samples.lastIndex), rxTipY),
                    strokeWidth = 2.5.dp.toPx()
                )

                // Direct Geometric LoS Ray
                drawLine(
                    color = result.status.color,
                    start = Offset(xFor(0), txTipY),
                    end = Offset(xFor(samples.lastIndex), rxTipY),
                    strokeWidth = 2.2.dp.toPx()
                )

                // Worst Obstruction / Pinch Point Callout
                val worst = result.worstSample
                val wx = xFor(worst.index)
                val wyTerrain = yFor(worst.adjustedTerrainMeters)
                drawLine(
                    color = result.status.color.copy(alpha = 0.7f),
                    start = Offset(wx, topPad),
                    end = Offset(wx, topPad + plotH),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)
                )
                drawCircle(
                    color = TacticalCanvas,
                    radius = 5.dp.toPx(),
                    center = Offset(wx, wyTerrain)
                )
                drawCircle(
                    color = result.status.color,
                    radius = 3.5.dp.toPx(),
                    center = Offset(wx, wyTerrain)
                )

                // Scrubbed Crosshair
                scrubbedIndex?.let { sIdx ->
                    val sample = samples[sIdx]
                    val sx = xFor(sIdx)
                    drawLine(
                        color = TextPrimary.copy(alpha = 0.75f),
                        start = Offset(sx, topPad),
                        end = Offset(sx, topPad + plotH),
                        strokeWidth = 1.2.dp.toPx()
                    )
                    drawCircle(
                        color = NodeMultiHopSky,
                        radius = 4.dp.toPx(),
                        center = Offset(sx, yFor(sample.adjustedTerrainMeters))
                    )
                }
            }
        }
    }
}

@Composable
private fun ChartLegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
    }
}
