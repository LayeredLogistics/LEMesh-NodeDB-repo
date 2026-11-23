package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
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
import com.example.data.CoveredNetworkNode
import com.example.data.MeshGeoUtils
import com.example.data.ViewshedFixStrategy
import com.example.data.ViewshedLinkFixOption
import com.example.data.ViewshedObstructedNode
import com.example.data.ViewshedPointStatus
import com.example.data.ViewshedResult
import com.example.ui.theme.AlertErrorRed
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NodeDirectGreen
import com.example.ui.theme.NodeMultiHopSky
import com.example.ui.theme.NodeSelfAmber
import com.example.ui.theme.TacticalCanvas
import com.example.ui.theme.TacticalOutline
import com.example.ui.theme.TacticalSurface
import com.example.ui.theme.TacticalSurfaceBright
import com.example.ui.theme.TacticalSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun RxLinkCheckCard(
    rxLink: ViewshedRxLinkResult?,
    rxLabel: String,
    isRxLinkLoading: Boolean,
    isRefreshingCoverage: Boolean,
    txHeightMeters: Float,
    rxHeightMeters: Float,
    onAcceptMastFix: (ViewshedLinkFixOption) -> Unit,
    onSendToBridge: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusColor = when (rxLink?.hop?.status) {
        ViewshedPointStatus.CLEAR -> NodeDirectGreen
        ViewshedPointStatus.GRAZING -> NodeSelfAmber
        ViewshedPointStatus.OBSTRUCTED -> AlertErrorRed
        null -> NodeMultiHopSky
    }
    val statusLabel = when (rxLink?.hop?.status) {
        ViewshedPointStatus.CLEAR -> "Clear LoS"
        ViewshedPointStatus.GRAZING -> "Marginal / Grazing"
        ViewshedPointStatus.OBSTRUCTED -> "Obstructed"
        null -> if (isRxLinkLoading) "Sampling…" else "Pending"
    }

    Surface(
        color = TacticalSurfaceElevated,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, statusColor.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
            .testTag("viewshed_rx_link_card")
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
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
                        imageVector = Icons.Default.Radar,
                        contentDescription = null,
                        tint = NodeMultiHopSky,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = "TX → RX Link Check",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        Text(
                            text = "Target: $rxLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isRxLinkLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            color = NodeMultiHopSky,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(statusColor.copy(alpha = 0.2f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = statusLabel,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = statusColor
                        )
                    }
                }
            }

            if (rxLink != null) {
                val hop = rxLink.hop
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ScorecardStatBox(
                        label = "Predicted RSSI",
                        value = String.format(Locale.US, "%.1f dBm", hop.predictedRssiDbm),
                        accentColor = if (hop.linkMarginDb >= 0.0) NodeDirectGreen else AlertErrorRed,
                        modifier = Modifier.weight(1f)
                    )
                    ScorecardStatBox(
                        label = "Link Margin",
                        value = String.format(Locale.US, "%+.1f dB", hop.linkMarginDb),
                        accentColor = statusColor,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    text = String.format(
                        Locale.US,
                        "%.2f km (%s) • Worst clear %.1f m (%.0f%% F1) • TX %dm / RX %dm",
                        hop.distanceKm,
                        MeshGeoUtils.formatCardinalDirection(rxLink.bearingDegrees),
                        hop.worstClearanceMeters,
                        hop.minFresnelRatio * 100.0,
                        txHeightMeters.roundToInt(),
                        rxHeightMeters.roundToInt()
                    ),
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 10.sp,
                    color = TextSecondary
                )

                val fixPlan = rxLink.fixPlan
                AnimatedVisibility(visible = fixPlan.options.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(TacticalCanvas)
                            .border(1.dp, statusColor.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                            .padding(8.dp)
                            .testTag("viewshed_suggested_fixes_panel"),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Build,
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = stringResource(R.string.viewshed_suggested_fixes),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = statusColor
                            )
                        }

                        fixPlan.diagnosticNote?.let { note ->
                            Text(
                                text = note,
                                style = MaterialTheme.typography.bodySmall,
                                color = NodeSelfAmber
                            )
                        }

                        fixPlan.options.forEach { option ->
                            SuggestedFixOptionRow(
                                option = option,
                                busy = isRxLinkLoading || isRefreshingCoverage,
                                onAcceptMastFix = { onAcceptMastFix(option) },
                                onSendToBridge = onSendToBridge
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = "Evaluating terrain profile and Fresnel clearance between TX and RX…",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
        }
    }
}

@Composable
private fun SuggestedFixOptionRow(
    option: ViewshedLinkFixOption,
    busy: Boolean,
    onAcceptMastFix: () -> Unit,
    onSendToBridge: () -> Unit
) {
    val isBridge = option.strategy == ViewshedFixStrategy.BRIDGE_RELAY_FALLBACK
    val accent = when {
        isBridge -> NodeMultiHopSky
        option.isRecommended -> NodeDirectGreen
        else -> NodeSelfAmber
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(TacticalSurface)
            .border(
                width = 1.dp,
                color = if (option.isRecommended) accent.copy(alpha = 0.65f) else TacticalOutline,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = option.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (option.isRecommended) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(accent.copy(alpha = 0.2f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.viewshed_recommended_badge),
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            color = accent
                        )
                    }
                }
            }
            Text(
                text = option.subtitle,
                fontFamily = if (isBridge) MaterialTheme.typography.bodySmall.fontFamily else JetBrainsMonoFontFamily,
                fontSize = 10.sp,
                color = TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            option.resultingHop?.let { verified ->
                Text(
                    text = String.format(
                        Locale.US,
                        "Verified: Clear LoS • %+.1f dB margin • %.0f%% F1",
                        verified.linkMarginDb,
                        verified.minFresnelRatio * 100.0
                    ),
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 9.sp,
                    color = NodeDirectGreen
                )
            }
        }

        if (isBridge) {
            OutlinedButton(
                onClick = onSendToBridge,
                enabled = !busy,
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                border = BorderStroke(1.dp, NodeMultiHopSky.copy(alpha = 0.8f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = NodeMultiHopSky.copy(alpha = 0.14f),
                    contentColor = NodeMultiHopSky
                ),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("accept_fix_bridge_fallback")
            ) {
                Icon(
                    imageVector = Icons.Default.Hub,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.viewshed_send_to_bridge_fix),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            Button(
                onClick = onAcceptMastFix,
                enabled = !busy,
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent,
                    contentColor = TacticalCanvas
                ),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("accept_fix_${option.strategy.name.lowercase()}")
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.viewshed_accept_fix),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun ViewshedScorecardSection(
    result: ViewshedResult,
    pinnedSnapshot: ViewshedResult?,
    onInspectObstructedNode: (ViewshedObstructedNode) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // RSSI Color Gradient Legend
        RssiGradientLegend(sensitivityDbm = result.params.sensitivityDbm)

        // Core Scorecard Metrics
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ScorecardStatBox(
                label = "Covered Area",
                value = String.format(Locale.US, "%.1f km²", result.coveredAreaKm2),
                accentColor = NodeDirectGreen,
                modifier = Modifier.weight(1f).testTag("scorecard_area")
            )
            ScorecardStatBox(
                label = "Max Clear Range",
                value = String.format(Locale.US, "%.2f km", result.maxUnobstructedRangeKm),
                accentColor = NodeMultiHopSky,
                modifier = Modifier.weight(1f).testTag("scorecard_range")
            )
        }

        Text(
            text = "Computed ${result.computeTimeMs} ms • ${result.tileCountUsed} DEM tiles • Ground ${String.format(Locale.US, "%.0f m ASL", result.groundElevationMeters)}",
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 10.sp,
            color = TextMuted
        )

        // Side-by-Side Snapshot Comparison Card
        if (pinnedSnapshot != null && pinnedSnapshot != result) {
            SnapshotComparisonCard(
                baseline = pinnedSnapshot,
                current = result
            )
        }

        // Covered Existing Network Nodes inside Clear LoS Footprint
        Surface(
            color = TacticalSurfaceElevated,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, TacticalOutline, RoundedCornerShape(12.dp))
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Sensors,
                            contentDescription = null,
                            tint = NodeDirectGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Clear LoS Network Nodes (${result.coveredClearNodes.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                    }
                }

                if (result.coveredClearNodes.isEmpty()) {
                    Text(
                        text = "No loaded network nodes fall within the unobstructed Clear LoS footprint at these settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                } else {
                    result.coveredClearNodes.forEachIndexed { idx, covered ->
                        if (idx > 0) {
                            HorizontalDivider(color = TacticalOutline.copy(alpha = 0.5f))
                        }
                        CoveredNodeItem(covered = covered)
                    }
                }
            }
        }

        // Obstructed & Partial Network Nodes inside coverage radius
        Surface(
            color = TacticalSurfaceElevated,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, TacticalOutline, RoundedCornerShape(12.dp))
                .testTag("viewshed_obstructed_nodes_card")
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = NodeSelfAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(
                                R.string.viewshed_obstructed_nodes_title,
                                result.obstructedNodes.size
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                    }
                }

                if (result.obstructedNodes.isEmpty()) {
                    Text(
                        text = stringResource(R.string.viewshed_obstructed_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                } else {
                    result.obstructedNodes.forEachIndexed { idx, obstructed ->
                        if (idx > 0) {
                            HorizontalDivider(color = TacticalOutline.copy(alpha = 0.5f))
                        }
                        ObstructedNodeItem(
                            obstructed = obstructed,
                            onInspect = { onInspectObstructedNode(obstructed) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ObstructedNodeItem(
    obstructed: ViewshedObstructedNode,
    onInspect: () -> Unit
) {
    val isPartial = obstructed.status == ViewshedPointStatus.GRAZING
    val statusColor = if (isPartial) NodeSelfAmber else AlertErrorRed
    val statusBadgeText = stringResource(
        if (isPartial) R.string.viewshed_status_partial else R.string.viewshed_status_obstructed
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(statusColor)
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "${obstructed.node.displayShortName} • ${obstructed.node.displayLongName}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(statusColor.copy(alpha = 0.2f))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = statusBadgeText,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        color = statusColor
                    )
                }
            }
            val rssiLabel = obstructed.predictedRssiDbm?.let {
                String.format(Locale.US, " • %.1f dBm", it)
            }.orEmpty()
            Text(
                text = String.format(
                    Locale.US,
                    "%.2f km (%.2f mi)%s",
                    obstructed.distanceKm,
                    obstructed.distanceMiles,
                    rssiLabel
                ),
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 10.sp,
                color = TextSecondary
            )
            Text(
                text = obstructed.quickFixHint,
                style = MaterialTheme.typography.labelSmall,
                color = if (obstructed.recommendedTxHeightMeters != null) NodeDirectGreen else NodeMultiHopSky,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        OutlinedButton(
            onClick = onInspect,
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.7f)),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = statusColor.copy(alpha = 0.12f),
                contentColor = statusColor
            ),
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("obstructed_node_fix_button_${obstructed.node.id}")
        ) {
            Text(
                text = stringResource(R.string.viewshed_fix_options),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun SnapshotComparisonCard(
    baseline: ViewshedResult,
    current: ViewshedResult
) {
    val areaDelta = current.coveredAreaKm2 - baseline.coveredAreaKm2
    val rangeDelta = current.maxUnobstructedRangeKm - baseline.maxUnobstructedRangeKm
    val nodesDelta = current.coveredClearNodes.size - baseline.coveredClearNodes.size

    Surface(
        color = TacticalSurfaceElevated,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, NodeSelfAmber.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
            .testTag("snapshot_comparison_card")
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.CompareArrows,
                    contentDescription = null,
                    tint = NodeSelfAmber,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Snapshot vs Current Comparison",
                    style = MaterialTheme.typography.titleSmall,
                    color = NodeSelfAmber
                )
            }

            ComparisonRow(
                metric = "Mast Height",
                snapshotValue = "${baseline.params.antennaHeightMeters.toInt()}m AGL",
                currentValue = "${current.params.antennaHeightMeters.toInt()}m AGL",
                deltaText = "${(current.params.antennaHeightMeters - baseline.params.antennaHeightMeters).toInt().let { if (it >= 0) "+$it" else "$it" }}m",
                deltaPositive = current.params.antennaHeightMeters >= baseline.params.antennaHeightMeters
            )
            ComparisonRow(
                metric = "Covered Area",
                snapshotValue = String.format(Locale.US, "%.1f km²", baseline.coveredAreaKm2),
                currentValue = String.format(Locale.US, "%.1f km²", current.coveredAreaKm2),
                deltaText = String.format(Locale.US, "%+.1f km²", areaDelta),
                deltaPositive = areaDelta >= 0.0
            )
            ComparisonRow(
                metric = "Max Clear Range",
                snapshotValue = String.format(Locale.US, "%.2f km", baseline.maxUnobstructedRangeKm),
                currentValue = String.format(Locale.US, "%.2f km", current.maxUnobstructedRangeKm),
                deltaText = String.format(Locale.US, "%+.2f km", rangeDelta),
                deltaPositive = rangeDelta >= 0.0
            )
            ComparisonRow(
                metric = "Clear Nodes",
                snapshotValue = baseline.coveredClearNodes.size.toString(),
                currentValue = current.coveredClearNodes.size.toString(),
                deltaText = if (nodesDelta >= 0) "+$nodesDelta" else "$nodesDelta",
                deltaPositive = nodesDelta >= 0
            )
        }
    }
}

@Composable
private fun ComparisonRow(
    metric: String,
    snapshotValue: String,
    currentValue: String,
    deltaText: String,
    deltaPositive: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = metric,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$snapshotValue → $currentValue",
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 11.sp,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = deltaText,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            color = if (deltaPositive) NodeDirectGreen else AlertErrorRed
        )
    }
}

@Composable
private fun CoveredNodeItem(covered: CoveredNetworkNode) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(NodeDirectGreen)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${covered.node.displayShortName} • ${covered.node.displayLongName}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = String.format(
                    Locale.US,
                    "%.2f km (%.2f mi)",
                    covered.distanceKm,
                    covered.distanceMiles
                ),
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 10.sp,
                color = TextSecondary
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(TacticalSurfaceBright)
                .padding(horizontal = 7.dp, vertical = 3.dp)
        ) {
            Text(
                text = String.format(Locale.US, "%.1f dBm", covered.predictedRssiDbm),
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = when {
                    covered.predictedRssiDbm >= -100.0 -> NodeDirectGreen
                    covered.predictedRssiDbm >= -115.0 -> NodeMultiHopSky
                    else -> NodeSelfAmber
                }
            )
        }
    }
}

@Composable
private fun ScorecardStatBox(
    label: String,
    value: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(TacticalSurfaceElevated)
            .border(1.dp, TacticalOutline, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = TextMuted
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = accentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun RssiGradientLegend(sensitivityDbm: Double) {
    val floorDbm = sensitivityDbm.coerceAtMost(-90.0)
    val yellowDbm = kotlin.math.round(-60.0 + (floorDbm - (-60.0)) * 0.42).toInt()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(TacticalCanvas)
            .border(1.dp, TacticalOutline, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "Predicted RSSI Signal Footprint (FSPL + Diffraction)",
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    Brush.horizontalGradient(
                        0.0f to Color(0xFF22C55E),
                        0.42f to Color(0xFFFACC15),
                        0.72f to Color(0xFFF97316),
                        1.0f to Color(0xFFEF4444)
                    )
                )
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("> -60 dBm", fontFamily = JetBrainsMonoFontFamily, fontSize = 9.sp, color = NodeDirectGreen)
            Text("$yellowDbm dBm", fontFamily = JetBrainsMonoFontFamily, fontSize = 9.sp, color = Color(0xFFFACC15))
            Text("${sensitivityDbm.toInt()} dBm", fontFamily = JetBrainsMonoFontFamily, fontSize = 9.sp, color = Color(0xFFEF4444))
        }
    }
}
