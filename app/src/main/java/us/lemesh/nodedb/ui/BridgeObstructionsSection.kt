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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import us.lemesh.nodedb.data.BridgeHopEvaluation
import us.lemesh.nodedb.data.BridgeLinkSegment
import us.lemesh.nodedb.data.BridgeObstructionItem
import us.lemesh.nodedb.data.BridgeProfileMode
import us.lemesh.nodedb.data.BridgeProfileSample
import us.lemesh.nodedb.data.BridgeResult
import us.lemesh.nodedb.data.RelayBridgeEngine
import us.lemesh.nodedb.data.ViewshedPointStatus
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
import kotlin.math.roundToInt

@Composable
fun BridgeObstructionsSection(
    result: BridgeResult?,
    selectedCandidateIndex: Int,
    selectedObstructionId: String?,
    txHeightMeters: Float,
    rxHeightMeters: Float,
    relayHeightMeters: Float,
    onTxHeightChange: (Float) -> Unit,
    onRxHeightChange: (Float) -> Unit,
    onRelayHeightChange: (Float) -> Unit,
    onSelectCandidate: (Int) -> Unit,
    onSelectObstruction: (String) -> Unit,
    modifier: Modifier = Modifier,
    liveDirectHop: BridgeHopEvaluation? = null,
    isLiveProfileLoading: Boolean = false,
    liveProfileError: String? = null,
    profileMode: BridgeProfileMode = BridgeProfileMode.DIRECT,
    scrubbedProfileSample: BridgeProfileSample? = null,
    onSelectProfileMode: (BridgeProfileMode) -> Unit = {},
    onScrubProfileSample: (BridgeProfileSample?) -> Unit = {},
    onRetryLiveProfile: () -> Unit = {},
    relayPowerDbm: Float = 22f,
    relayAntennaGainDbi: Float = 3.0f,
    relaySensitivityDbm: Float = -130f,
    onRelayPowerChange: (Float) -> Unit = {},
    onRelayAntennaGainChange: (Float) -> Unit = {},
    onRelaySensitivityChange: (Float) -> Unit = {},
    isLiveProfileOfflineMissing: Boolean = false,
    onOpenOfflineMaps: (() -> Unit)? = null
) {
    val activeCandidate = result?.topCandidates?.getOrNull(selectedCandidateIndex)
        ?: result?.topCandidates?.firstOrNull()
    val hasRelayCandidate = activeCandidate != null
    val effectiveProfileMode = if (profileMode == BridgeProfileMode.RELAY && hasRelayCandidate) {
        BridgeProfileMode.RELAY
    } else {
        BridgeProfileMode.DIRECT
    }
    val compositeProfile = remember(effectiveProfileMode, liveDirectHop, result, selectedCandidateIndex) {
        RelayBridgeEngine.buildCompositeProfile(
            mode = effectiveProfileMode,
            directHop = liveDirectHop,
            result = result,
            selectedCandidateIndex = selectedCandidateIndex
        )
    }
    val obstructions = remember(result, selectedCandidateIndex, liveDirectHop) {
        if (result != null) {
            RelayBridgeEngine.buildObstructionList(result, selectedCandidateIndex)
        } else {
            RelayBridgeEngine.buildDirectObstructionList(liveDirectHop)
        }
    }
    val blockedCount = obstructions.count { it.point.status == ViewshedPointStatus.OBSTRUCTED }
    val grazingCount = obstructions.count { it.point.status == ViewshedPointStatus.GRAZING }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BridgeTerrainProfileChart(
            profile = compositeProfile,
            selectedMode = effectiveProfileMode,
            hasRelayCandidate = hasRelayCandidate,
            isLoading = isLiveProfileLoading,
            errorMessage = liveProfileError,
            selectedObstructionId = selectedObstructionId,
            scrubbedSample = scrubbedProfileSample,
            onSelectMode = onSelectProfileMode,
            onSelectObstruction = onSelectObstruction,
            onScrubSample = onScrubProfileSample,
            onRetryLoading = onRetryLiveProfile,
            isOfflineTileMissing = isLiveProfileOfflineMissing,
            onOpenOfflineMaps = onOpenOfflineMaps
        )
        Surface(
            color = TacticalCanvas,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, TacticalOutline, RoundedCornerShape(10.dp))
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
                            imageVector = Icons.Default.Terrain,
                            contentDescription = null,
                            tint = if (blockedCount > 0) AlertErrorRed else if (grazingCount > 0) NodeSelfAmber else NodeDirectGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when {
                                blockedCount > 0 || grazingCount > 0 ->
                                    "$blockedCount Blocked • $grazingCount Grazing peaks"
                                else -> "Clear LoS — tightest terrain pinch points"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary
                        )
                    }
                }

                val activeHopForBanner = if (effectiveProfileMode == BridgeProfileMode.DIRECT) {
                    result?.directBaseline ?: liveDirectHop
                } else {
                    null
                }
                if (activeHopForBanner != null) {
                    Text(
                        text = String.format(
                            Locale.US,
                            "Direct 2-way • TX→RX %+.1f dB (%.1f dBm) • RX→TX %+.1f dB (%.1f dBm)",
                            activeHopForBanner.linkMarginDb,
                            activeHopForBanner.predictedRssiDbm,
                            activeHopForBanner.reverseLinkMarginDb,
                            activeHopForBanner.reverseRssiDbm
                        ),
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 10.sp,
                        color = if (activeHopForBanner.isBidirectionalViable) TextSecondary else AlertErrorRed
                    )
                }

                if ((result?.topCandidates?.size ?: 0) > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Relay:",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                        result!!.topCandidates.forEachIndexed { idx, cand ->
                            val isChosen = idx == selectedCandidateIndex
                            FilterChip(
                                selected = isChosen,
                                onClick = { onSelectCandidate(idx) },
                                label = {
                                    Text(
                                        text = if (cand.rank == 1) "#1 Best" else "#${cand.rank}",
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontSize = 10.sp
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = TacticalSurfaceElevated,
                                    labelColor = TextSecondary,
                                    selectedContainerColor = NodeDirectGreen.copy(alpha = 0.2f),
                                    selectedLabelColor = NodeDirectGreen
                                ),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("obstructions_candidate_chip_${cand.rank}")
                            )
                        }
                    }
                }
            }
        }

        Surface(
            color = TacticalSurfaceElevated,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, TacticalOutline, RoundedCornerShape(10.dp))
                .testTag("obstructions_live_mast_controls")
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Live Mast Heights (AGL)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        RfLabeledSlider(
                            label = "TX Mast",
                            valueText = "${txHeightMeters.roundToInt()} m",
                            value = txHeightMeters,
                            valueRange = 1f..60f,
                            accentColor = NodeSelfAmber,
                            onValueChange = onTxHeightChange
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        RfLabeledSlider(
                            label = "RX Mast",
                            valueText = "${rxHeightMeters.roundToInt()} m",
                            value = rxHeightMeters,
                            valueRange = 1f..60f,
                            accentColor = NodeMultiHopSky,
                            onValueChange = onRxHeightChange
                        )
                    }
                }
            }
        }

        if (result != null) {
            BridgeRelayRadioCard(
                relayHeightMeters = relayHeightMeters,
                relayPowerDbm = relayPowerDbm,
                relayAntennaGainDbi = relayAntennaGainDbi,
                relaySensitivityDbm = relaySensitivityDbm,
                tagPrefix = "profile_relay",
                onRelayHeightChange = onRelayHeightChange,
                onRelayPowerChange = onRelayPowerChange,
                onRelayAntennaGainChange = onRelayAntennaGainChange,
                onRelaySensitivityChange = onRelaySensitivityChange
            )
        }

        if (obstructions.isEmpty()) {
            Text(
                text = "No terrain obstructions detected along the evaluated paths.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )
        } else {
            obstructions.forEachIndexed { idx, item ->
                ObstructionPeakCard(
                    overallRank = idx + 1,
                    item = item,
                    relayRank = activeCandidate?.rank,
                    isSelected = item.id == selectedObstructionId,
                    onClick = { onSelectObstruction(item.id) }
                )
            }
        }
    }
}

@Composable
private fun ObstructionPeakCard(
    overallRank: Int,
    item: BridgeObstructionItem,
    relayRank: Int?,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val pt = item.point
    val statusColor = when (pt.status) {
        ViewshedPointStatus.OBSTRUCTED -> AlertErrorRed
        ViewshedPointStatus.GRAZING -> NodeSelfAmber
        ViewshedPointStatus.CLEAR -> NodeDirectGreen
    }
    val statusLabel = when (pt.status) {
        ViewshedPointStatus.OBSTRUCTED -> "BLOCKED"
        ViewshedPointStatus.GRAZING -> "GRAZING"
        ViewshedPointStatus.CLEAR -> "CLEAR PINCH"
    }
    val segmentLabel = when (item.segment) {
        BridgeLinkSegment.DIRECT -> "Direct TX–RX"
        BridgeLinkSegment.TX_TO_RELAY -> if (relayRank != null) "TX → Relay #$relayRank" else "TX → Relay"
        BridgeLinkSegment.RELAY_TO_RX -> if (relayRank != null) "Relay #$relayRank → RX" else "Relay → RX"
    }
    val originName = when (item.segment) {
        BridgeLinkSegment.DIRECT, BridgeLinkSegment.TX_TO_RELAY -> "TX"
        BridgeLinkSegment.RELAY_TO_RX -> "Relay"
    }
    val fresnelPct = (pt.fresnelRatio * 100.0).coerceIn(-999.0, 999.0)

    Surface(
        color = if (isSelected) TacticalSurfaceBright else TacticalSurfaceElevated,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) statusColor else TacticalOutline,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .minimumInteractiveComponentSize()
            .testTag("obstruction_card_${item.id}")
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(statusColor)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "#$overallRank $statusLabel",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = TacticalCanvas
                        )
                    }
                    Text(
                        text = segmentLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = String.format(Locale.US, "%+.1f m • %.0f%% F1", pt.clearanceMeters, fresnelPct),
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = statusColor
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = String.format(
                        Locale.US,
                        "%.5f, %.5f",
                        pt.latitude,
                        pt.longitude
                    ),
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = NodeMultiHopSky
                )
                Text(
                    text = String.format(
                        Locale.US,
                        "%.2f km from %s",
                        pt.distanceFromOriginKm,
                        originName
                    ),
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 10.sp,
                    color = TextSecondary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = String.format(
                        Locale.US,
                        "Terrain %.0f m ASL (+%.1f m bulge = %.0f m)",
                        pt.terrainElevationMeters,
                        pt.earthBulgeMeters,
                        pt.effectiveTerrainMeters
                    ),
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 10.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = String.format(Locale.US, "Ray %.0f m", pt.rayElevationMeters),
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 10.sp,
                    color = TextPrimary
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = null,
                    tint = if (isSelected) statusColor else TextMuted,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = if (isSelected) "Highlighted on Bridge map" else "Tap to pinpoint on Bridge map",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) statusColor else TextMuted
                )
            }
        }
    }
}

@Composable
fun BridgeSelectedObstructionCallout(
    item: BridgeObstructionItem,
    relayRank: Int?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pt = item.point
    val statusColor = when (pt.status) {
        ViewshedPointStatus.OBSTRUCTED -> AlertErrorRed
        ViewshedPointStatus.GRAZING -> NodeSelfAmber
        ViewshedPointStatus.CLEAR -> NodeDirectGreen
    }
    val segmentLabel = when (item.segment) {
        BridgeLinkSegment.DIRECT -> "Direct TX–RX"
        BridgeLinkSegment.TX_TO_RELAY -> if (relayRank != null) "TX → Relay #$relayRank" else "TX → Relay"
        BridgeLinkSegment.RELAY_TO_RX -> if (relayRank != null) "Relay #$relayRank → RX" else "Relay → RX"
    }

    Surface(
        color = TacticalSurface.copy(alpha = 0.96f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.5.dp, statusColor, RoundedCornerShape(12.dp))
            .testTag("bridge_selected_obstruction_callout")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Icon(
                imageVector = Icons.Default.WarningAmber,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = String.format(
                        Locale.US,
                        "%s • %+.1f m (%+.0f%% F1)",
                        segmentLabel,
                        pt.clearanceMeters,
                        pt.fresnelRatio * 100.0
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = String.format(
                        Locale.US,
                        "%.5f, %.5f • Terrain %.0f m / Ray %.0f m ASL",
                        pt.latitude,
                        pt.longitude,
                        pt.terrainElevationMeters,
                        pt.rayElevationMeters
                    ),
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 10.sp,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("dismiss_obstruction_callout")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Clear obstruction highlight",
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
