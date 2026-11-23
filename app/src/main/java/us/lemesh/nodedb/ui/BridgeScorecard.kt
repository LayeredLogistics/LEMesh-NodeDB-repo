package us.lemesh.nodedb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import us.lemesh.nodedb.data.BridgeHopEvaluation
import us.lemesh.nodedb.data.BridgeRankTier
import us.lemesh.nodedb.data.BridgeResult
import us.lemesh.nodedb.data.RelayCandidate
import us.lemesh.nodedb.data.ViewshedPointStatus
import us.lemesh.nodedb.ui.theme.AlertErrorRed
import us.lemesh.nodedb.ui.theme.JetBrainsMonoFontFamily
import us.lemesh.nodedb.ui.theme.NodeDirectGreen
import us.lemesh.nodedb.ui.theme.NodeMultiHopSky
import us.lemesh.nodedb.ui.theme.NodeSelfAmber
import us.lemesh.nodedb.ui.theme.TacticalCanvas
import us.lemesh.nodedb.ui.theme.TacticalOutline
import us.lemesh.nodedb.ui.theme.TacticalSurfaceBright
import us.lemesh.nodedb.ui.theme.TacticalSurfaceElevated
import us.lemesh.nodedb.ui.theme.TextMuted
import us.lemesh.nodedb.ui.theme.TextPrimary
import us.lemesh.nodedb.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun BridgeScorecardSection(
    result: BridgeResult,
    selectedCandidateIndex: Int,
    onSelectCandidate: (Int) -> Unit,
    modifier: Modifier = Modifier,
    isViewshedStale: Boolean = false,
    onAlignCandidate: ((RelayCandidate) -> Unit)? = null
) {
    val legendSensitivity = maxOf(
        result.params.txSensitivityDbm,
        result.params.rxSensitivityDbm,
        result.params.relaySensitivityDbm
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        DualViewshedLegend(sensitivityDbm = legendSensitivity)

        if (isViewshedStale) {
            Surface(
                color = NodeSelfAmber.copy(alpha = 0.14f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, NodeSelfAmber.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
                    .testTag("bridge_viewshed_stale_banner")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = NodeSelfAmber,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = androidx.compose.ui.res.stringResource(us.lemesh.nodedb.R.string.bridge_viewshed_stale_banner),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BridgeStatBox(
                label = "Mutual Overlap",
                value = String.format(Locale.US, "%.1f km²", result.mutualOverlapAreaKm2),
                accentColor = NodeDirectGreen,
                modifier = Modifier.weight(1f).testTag("bridge_overlap_area")
            )
            BridgeStatBox(
                label = "Direct TX ↔ RX",
                value = String.format(
                    Locale.US,
                    "%.1f km • %s",
                    result.directDistanceKm,
                    statusLabel(result.directBaseline.status)
                ),
                subValue = String.format(
                    Locale.US,
                    "TX→RX %+.1f • RX→TX %+.1f dB",
                    result.directBaseline.linkMarginDb,
                    result.directBaseline.reverseLinkMarginDb
                ),
                accentColor = if (result.directBaseline.status == ViewshedPointStatus.OBSTRUCTED || !result.directBaseline.isBidirectionalViable) {
                    if (result.directBaseline.status == ViewshedPointStatus.OBSTRUCTED) AlertErrorRed else NodeSelfAmber
                } else {
                    statusColor(result.directBaseline.status)
                },
                modifier = Modifier.weight(1.25f).testTag("bridge_direct_baseline")
            )
        }

        Text(
            text = String.format(
                Locale.US,
                "TX %.1f km² • RX %.1f km² • %d sites checked in %d ms (%d tiles)",
                result.txCoveredAreaKm2,
                result.rxCoveredAreaKm2,
                result.candidatesEvaluated,
                result.computeTimeMs,
                result.tileCountUsed
            ),
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 10.sp,
            color = TextMuted
        )

        val topTier = result.topCandidates.firstOrNull()?.tier
        if (topTier != null && topTier != BridgeRankTier.MUTUAL_CLEAR_LOS) {
            Surface(
                color = NodeSelfAmber.copy(alpha = 0.14f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, NodeSelfAmber.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = NodeSelfAmber,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (topTier == BridgeRankTier.GRAZING_BRIDGE) {
                            "No site cleared 60% Fresnel on both hops. Showing highest-margin Grazing candidates—try raising the Relay Mast."
                        } else {
                            "Terrain or bidirectional RF margin prevents a clear two-hop link. Try raising masts, antenna gain, or TX power."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.EmojiEvents,
                contentDescription = null,
                tint = NodeSelfAmber,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Top ${result.topCandidates.size} Bidirectional Relay Sites",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary
            )
        }

        result.topCandidates.forEachIndexed { idx, candidate ->
            RelayCandidateCard(
                candidate = candidate,
                isSelected = idx == selectedCandidateIndex,
                onClick = { onSelectCandidate(idx) },
                onAlign = onAlignCandidate?.let { cb -> { cb(candidate) } }
            )
        }
    }
}

@Composable
private fun RelayCandidateCard(
    candidate: RelayCandidate,
    isSelected: Boolean,
    onClick: () -> Unit,
    onAlign: (() -> Unit)? = null
) {
    val tierColor = when (candidate.tier) {
        BridgeRankTier.MUTUAL_CLEAR_LOS -> NodeDirectGreen
        BridgeRankTier.GRAZING_BRIDGE -> NodeSelfAmber
        BridgeRankTier.OBSTRUCTED_FALLBACK -> AlertErrorRed
    }
    val tierText = when (candidate.tier) {
        BridgeRankTier.MUTUAL_CLEAR_LOS -> "Clear LoS"
        BridgeRankTier.GRAZING_BRIDGE -> "Grazing"
        BridgeRankTier.OBSTRUCTED_FALLBACK -> "Blocked"
    }

    Surface(
        color = if (isSelected) TacticalSurfaceBright else TacticalSurfaceElevated,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) tierColor else TacticalOutline,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .testTag("relay_candidate_card_${candidate.rank}")
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
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(tierColor)
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (candidate.rank == 1) "#1 BEST" else "#${candidate.rank}",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = TacticalCanvas
                        )
                    }
                    Text(
                        text = tierText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = tierColor
                    )
                    if (candidate.inViewshedOverlap) {
                        Text(
                            text = "• Overlap",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 10.sp,
                            color = TextSecondary
                        )
                    }
                }

                Text(
                    text = String.format(Locale.US, "%+.1f dB 2-way", candidate.worstLinkMarginDb),
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = if (candidate.worstLinkMarginDb >= 0.0) tierColor else AlertErrorRed
                )
            }

            Text(
                text = String.format(
                    Locale.US,
                    "%.5f, %.5f  •  Ground %.0f m ASL",
                    candidate.latitude,
                    candidate.longitude,
                    candidate.groundElevationMeters
                ),
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 11.sp,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            BidirectionalHopSummaryBlock(
                forwardLabel = "TX → Relay",
                reverseLabel = "Relay → TX",
                hop = candidate.txHop
            )
            BidirectionalHopSummaryBlock(
                forwardLabel = "Relay → RX",
                reverseLabel = "RX → Relay",
                hop = candidate.rxHop
            )

            if (onAlign != null) {
                OutlinedButton(
                    onClick = onAlign,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    border = BorderStroke(1.dp, NodeDirectGreen.copy(alpha = 0.65f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("relay_candidate_align_${candidate.rank}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Explore,
                        contentDescription = null,
                        tint = NodeDirectGreen,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Align Antenna to Relay #${candidate.rank}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = NodeDirectGreen
                    )
                }
            }
        }
    }
}

@Composable
private fun BidirectionalHopSummaryBlock(
    forwardLabel: String,
    reverseLabel: String,
    hop: BridgeHopEvaluation
) {
    val fresnelPct = (hop.minFresnelRatio * 100.0).coerceIn(-99.0, 999.0)
    val forwardColor = if (!hop.forwardPasses) AlertErrorRed else statusColor(hop.status)
    val reverseColor = if (!hop.reversePasses) AlertErrorRed else statusColor(hop.status)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(TacticalCanvas.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = String.format(Locale.US, "%s • %.1f km • %.0f%% F1", forwardLabel, hop.distanceKm, fresnelPct),
                style = MaterialTheme.typography.bodySmall,
                fontSize = 10.sp,
                color = TextSecondary
            )
            Text(
                text = String.format(Locale.US, "%.1f dBm (%+.1f dB)", hop.predictedRssiDbm, hop.linkMarginDb),
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp,
                color = forwardColor
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = reverseLabel,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 10.sp,
                color = TextMuted
            )
            Text(
                text = String.format(Locale.US, "%.1f dBm (%+.1f dB)", hop.reverseRssiDbm, hop.reverseLinkMarginDb),
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp,
                color = reverseColor
            )
        }
    }
}

@Composable
fun DualViewshedLegend(sensitivityDbm: Double) {
    val floorDbm = sensitivityDbm.coerceAtMost(-90.0)
    val yellowDbm = kotlin.math.round(-60.0 + (floorDbm - (-60.0)) * 0.42).toInt()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(TacticalCanvas)
            .border(1.dp, TacticalOutline, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            LegendDot(color = NodeSelfAmber, label = "TX Only")
            LegendDot(color = NodeMultiHopSky, label = "RX Only")
            LegendDot(color = NodeDirectGreen, label = "Mutual Bridge RSSI")
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
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

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 10.sp,
            color = TextSecondary
        )
    }
}

@Composable
private fun BridgeStatBox(
    label: String,
    value: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    subValue: String? = null
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
            fontSize = 13.sp,
            color = accentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (subValue != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subValue,
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 9.sp,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun statusColor(status: ViewshedPointStatus): Color = when (status) {
    ViewshedPointStatus.CLEAR -> NodeDirectGreen
    ViewshedPointStatus.GRAZING -> NodeSelfAmber
    ViewshedPointStatus.OBSTRUCTED -> AlertErrorRed
}

private fun statusLabel(status: ViewshedPointStatus): String = when (status) {
    ViewshedPointStatus.CLEAR -> "Clear"
    ViewshedPointStatus.GRAZING -> "Grazing"
    ViewshedPointStatus.OBSTRUCTED -> "Blocked"
}
