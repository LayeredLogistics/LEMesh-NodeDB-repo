package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.DirectRfLink
import com.example.data.MeshGeoUtils
import com.example.data.MeshNode
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NodeDirectGreen
import com.example.ui.theme.NodeMultiHopSky
import com.example.ui.theme.NodeSelfAmber
import com.example.ui.theme.TacticalOutline
import com.example.ui.theme.TacticalSurface
import com.example.ui.theme.TacticalSurfaceBright
import com.example.ui.theme.TacticalSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectRfLinksBottomSheet(
    links: List<DirectRfLink>,
    selfNode: MeshNode?,
    sortDescending: Boolean,
    onToggleSort: () -> Unit,
    onInspectNode: (MeshNode) -> Unit,
    onMeasureOnMap: (MeshNode) -> Unit,
    onAnalyzeLos: (MeshNode) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxKm = links.maxOfOrNull { it.distanceKm }
    val avgKm = if (links.isNotEmpty()) links.map { it.distanceKm }.average() else null

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
                .padding(bottom = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Straighten,
                        contentDescription = null,
                        tint = NodeDirectGreen,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Direct RF Link Distances (${links.size})",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary
                        )
                        Text(
                            text = if (selfNode != null) {
                                "0-hop RF from ${selfNode.displayShortName} (${selfNode.id})"
                            } else {
                                "Self node GPS required for automatic 0-hop range"
                            },
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("close_direct_links_sheet_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close direct RF links",
                        tint = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (links.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DirectSummaryStat(
                        label = "Max Reach",
                        value = maxKm?.let {
                            String.format(Locale.US, "%.2f mi / %.2f km", MeshGeoUtils.kmToMiles(it), it)
                        } ?: "N/A",
                        modifier = Modifier.weight(1f)
                    )
                    DirectSummaryStat(
                        label = "Avg Range",
                        value = avgKm?.let {
                            String.format(Locale.US, "%.2f mi / %.2f km", MeshGeoUtils.kmToMiles(it), it)
                        } ?: "N/A",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onToggleSort,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("toggle_direct_links_sort")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapVert,
                            contentDescription = null,
                            tint = NodeDirectGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (sortDescending) "Longest First" else "Shortest First",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            if (links.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (selfNode == null || !selfNode.hasValidGps) {
                            "Self node lacks valid GPS coordinates. Use Measure RF mode on the map to measure between any two plotted nodes."
                        } else {
                            "No 0-hop direct RF nodes with valid GPS coordinates found."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    items(links, key = { "${it.node.id}_${it.node.num}" }) { link ->
                        DirectRfLinkRow(
                            link = link,
                            onInspect = { onInspectNode(link.node) },
                            onMeasureOnMap = { onMeasureOnMap(link.node) },
                            onAnalyzeLos = { onAnalyzeLos(link.node) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectSummaryStat(
    label: String,
    value: String,
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
            fontSize = 12.sp,
            color = NodeDirectGreen,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DirectRfLinkRow(
    link: DirectRfLink,
    onInspect: () -> Unit,
    onMeasureOnMap: () -> Unit,
    onAnalyzeLos: () -> Unit
) {
    val node = link.node
    val classification = node.linkClassification
    val cardinal = MeshGeoUtils.formatCardinalDirection(link.bearingDegrees)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onInspect)
            .testTag("direct_link_row_${node.id}"),
        color = TacticalSurfaceElevated,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, TacticalOutline, RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(classification.color.copy(alpha = 0.16f))
                        .border(1.dp, classification.color.copy(alpha = 0.7f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = node.displayShortName.take(4),
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = classification.color
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = node.displayLongName,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append(node.id)
                            append(" • ")
                            append(String.format(Locale.US, "%.0f° %s", link.bearingDegrees, cardinal))
                        },
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = String.format(Locale.US, "%.2f mi", link.distanceMiles),
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = NodeDirectGreen
                    )
                    Text(
                        text = String.format(Locale.US, "%.2f km", link.distanceKm),
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val snrText = node.snr?.let { String.format(Locale.US, "SNR %.1f dB", it) } ?: "SNR N/A"
                    val rssiText = node.rssi?.let { "RSSI $it dBm" } ?: "RSSI N/A"
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(TacticalSurfaceBright.copy(alpha = 0.7f))
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "$snrText  •  $rssiText",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 10.sp,
                            color = NodeMultiHopSky
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = onAnalyzeLos,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.testTag("los_link_${node.id}")
                    ) {
                        Text(
                            text = "Fresnel LoS",
                            style = MaterialTheme.typography.labelMedium,
                            color = NodeMultiHopSky
                        )
                    }
                    TextButton(
                        onClick = onMeasureOnMap,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.testTag("highlight_link_${node.id}")
                    ) {
                        Text(
                            text = "Highlight",
                            style = MaterialTheme.typography.labelMedium,
                            color = NodeSelfAmber
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RfLinkRulerBar(
    anchorA: MeshNode?,
    anchorB: MeshNode?,
    selfNode: MeshNode?,
    onUseSelfAsAnchorA: () -> Unit,
    onInspectAnchorB: (MeshNode) -> Unit,
    onAnalyzeLos: () -> Unit,
    onOpenDirectLinksSheet: () -> Unit,
    onClear: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasBothCoords = anchorA?.hasValidGps == true && anchorB?.hasValidGps == true
    val distanceKm = if (hasBothCoords) {
        MeshGeoUtils.haversineKm(
            anchorA!!.latitude!!,
            anchorA.longitude!!,
            anchorB!!.latitude!!,
            anchorB.longitude!!
        )
    } else null
    val bearing = if (hasBothCoords) {
        MeshGeoUtils.bearingDegrees(
            anchorA!!.latitude!!,
            anchorA.longitude!!,
            anchorB!!.latitude!!,
            anchorB.longitude!!
        )
    } else null
    val elevDeltaM = if (hasBothCoords && anchorA?.altitude != null && anchorB?.altitude != null) {
        anchorB.altitude - anchorA.altitude
    } else null

    Surface(
        color = TacticalSurface.copy(alpha = 0.95f),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.5.dp, NodeSelfAmber.copy(alpha = 0.75f), RoundedCornerShape(16.dp))
            .testTag("rf_link_ruler_bar")
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
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Straighten,
                        contentDescription = null,
                        tint = NodeSelfAmber,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = buildString {
                            append(anchorA?.displayShortName ?: "Tap Node A")
                            append("  →  ")
                            append(anchorB?.displayShortName ?: "Tap Node B")
                        },
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                TextButton(
                    onClick = onDone,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.testTag("ruler_done_button")
                ) {
                    Text(
                        text = "Done",
                        style = MaterialTheme.typography.labelLarge,
                        color = NodeSelfAmber
                    )
                }
            }

            if (distanceKm != null && bearing != null) {
                val miles = MeshGeoUtils.kmToMiles(distanceKm)
                val cardinal = MeshGeoUtils.formatCardinalDirection(bearing)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(TacticalSurfaceElevated)
                            .border(1.dp, TacticalOutline, RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                    ) {
                        Column {
                            Text(
                                text = String.format(Locale.US, "%.2f mi  •  %.2f km", miles, distanceKm),
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = NodeDirectGreen
                            )
                            Text(
                                text = buildString {
                                    append(String.format(Locale.US, "Bearing %.0f° %s", bearing, cardinal))
                                    if (elevDeltaM != null) {
                                        val sign = if (elevDeltaM >= 0) "+" else ""
                                        append("  •  ΔElev ${sign}${elevDeltaM}m")
                                    }
                                    val snr = anchorB?.snr
                                    if (snr != null) {
                                        append(String.format(Locale.US, "  •  SNR %.1f dB", snr))
                                    }
                                },
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 11.sp,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = if (anchorA == null) {
                        "Tap any two plotted nodes on the map to measure distance and bearing."
                    } else {
                        "Anchor A set (${anchorA.displayShortName}). Tap a second node on the map to measure."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selfNode?.hasValidGps == true && anchorA?.id != selfNode.id) {
                    OutlinedButton(
                        onClick = onUseSelfAsAnchorA,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("ruler_from_self_button")
                    ) {
                        Text(
                            text = "From Self",
                            style = MaterialTheme.typography.labelMedium,
                            color = NodeSelfAmber
                        )
                    }
                }

                if (anchorB != null) {
                    OutlinedButton(
                        onClick = { onInspectAnchorB(anchorB) },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("ruler_inspect_b_button")
                    ) {
                        Text(
                            text = "Inspect ${anchorB.displayShortName}",
                            style = MaterialTheme.typography.labelMedium,
                            color = NodeMultiHopSky
                        )
                    }
                }

                if (anchorA != null && anchorB != null) {
                    OutlinedButton(
                        onClick = onAnalyzeLos,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("ruler_los_button")
                    ) {
                        Text(
                            text = "Fresnel",
                            style = MaterialTheme.typography.labelMedium,
                            color = NodeMultiHopSky
                        )
                    }
                }

                OutlinedButton(
                    onClick = onOpenDirectLinksSheet,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("ruler_direct_list_button")
                ) {
                    Text(
                        text = "Direct List",
                        style = MaterialTheme.typography.labelMedium,
                        color = NodeDirectGreen
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                if (anchorA != null || anchorB != null) {
                    TextButton(
                        onClick = onClear,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("ruler_clear_button")
                    ) {
                        Text(
                            text = "Clear",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}

