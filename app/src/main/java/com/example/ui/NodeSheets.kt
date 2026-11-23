package com.example.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.MeshGeoUtils
import com.example.data.MeshNode
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NodeDirectGreen
import com.example.ui.theme.NodeMqttSlate
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoGpsNodesBottomSheet(
    nodes: List<MeshNode>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onNodeClick: (MeshNode) -> Unit,
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
                        imageVector = Icons.Default.GpsOff,
                        contentDescription = null,
                        tint = NodeMultiHopSky,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "No-GPS Nodes (${nodes.size})",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("close_no_gps_sheet_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close No-GPS list",
                        tint = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("no_gps_search_input"),
                placeholder = {
                    Text(
                        text = stringResource(R.string.no_gps_search_placeholder),
                        color = TextMuted
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = TextSecondary
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.action_clear_search),
                                tint = TextSecondary
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = TacticalSurfaceElevated,
                    unfocusedContainerColor = TacticalSurfaceElevated,
                    focusedBorderColor = NodeMultiHopSky,
                    unfocusedBorderColor = TacticalOutline,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (nodes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No matching nodes without GPS coordinates.",
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
                    items(nodes, key = { "${it.id}_${it.num}" }) { node ->
                        NoGpsNodeRow(
                            node = node,
                            onClick = { onNodeClick(node) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NoGpsNodeRow(
    node: MeshNode,
    onClick: () -> Unit
) {
    val classification = node.linkClassification
    val accentColor = node.effectiveColor
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag("no_gps_node_${node.id}"),
        color = TacticalSurfaceElevated,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, TacticalOutline, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accentColor.copy(alpha = 0.16f))
                    .border(1.dp, accentColor.copy(alpha = 0.7f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = node.displayShortName.take(4),
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = accentColor,
                    maxLines = 1
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
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append(node.id.ifBlank { "No ID" })
                        if (!node.hwModel.isNullOrBlank()) {
                            append(" • ")
                            append(node.hwModel)
                        }
                    },
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 11.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val statusText = when {
                        node.isSelf -> "SELF"
                        node.viaMqtt -> "MQTT"
                        node.hopsAway != null -> "${node.hopsAway} hop${if (node.hopsAway == 1) "" else "s"} RF"
                        else -> "RF"
                    }
                    StatusBadge(
                        text = statusText,
                        color = if (node.viaMqtt) NodeMqttSlate else classification.color
                    )
                    Text(
                        text = "Heard ${MeshGeoUtils.formatRelativeTime(node.lastHeard)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeInspectorBottomSheet(
    node: MeshNode,
    selfNode: MeshNode?,
    onDismiss: () -> Unit,
    onAssignViewshedTx: (MeshNode) -> Unit = {},
    onAssignViewshedRx: (MeshNode) -> Unit = {},
    onAssignBridgeTx: (MeshNode) -> Unit = {},
    onAssignBridgeRx: (MeshNode) -> Unit = {},
    onInspectFresnelSightline: ((MeshNode) -> Unit)? = null,
    onEditNode: ((MeshNode) -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboardManager = LocalClipboardManager.current
    var rawJsonExpanded by remember(node.id, node.num) { mutableStateOf(false) }
    var copiedFeedback by remember(node.id, node.num) { mutableStateOf(false) }

    val classification = node.linkClassification
    val accentColor = node.effectiveColor
    val distanceKm: Double? = remember(node, selfNode) {
        if (selfNode != null &&
            !node.isSelf &&
            selfNode.latitude != null && selfNode.longitude != null &&
            node.latitude != null && node.longitude != null
        ) {
            MeshGeoUtils.haversineKm(
                selfNode.latitude,
                selfNode.longitude,
                node.latitude,
                node.longitude
            )
        } else null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = TacticalSurface,
        contentColor = TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(accentColor.copy(alpha = 0.16f))
                            .border(1.5.dp, accentColor, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = node.displayShortName.take(4),
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = accentColor
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StatusBadge(text = classification.label.uppercase(), color = accentColor)
                            if (node.isManual) {
                                StatusBadge(text = "MANUAL", color = NodeDirectGreen)
                            } else if (node.isEdited) {
                                StatusBadge(text = "EDITED", color = NodeMultiHopSky)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = node.displayLongName,
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary
                        )
                        Text(
                            text = node.id.ifBlank { "ID Unknown" },
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 13.sp,
                            color = TextSecondary
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("close_inspector_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close inspector",
                        tint = TextSecondary
                    )
                }
            }

            if (onEditNode != null) {
                OutlinedButton(
                    onClick = { onEditNode(node) },
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.75f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = accentColor.copy(alpha = 0.10f),
                        contentColor = accentColor
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("inspector_edit_node_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.node_inspector_edit),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            node.notes?.takeIf { it.isNotBlank() }?.let { note ->
                Surface(
                    color = TacticalSurfaceElevated,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, accentColor.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Operator Notes",
                            style = MaterialTheme.typography.labelMedium,
                            color = accentColor
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimary
                        )
                    }
                }
            }

            // Identity & Distance Row
            Surface(
                color = TacticalSurfaceElevated,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, TacticalOutline, RoundedCornerShape(12.dp))
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        MetricTile(
                            label = "Hardware",
                            value = node.hwModel ?: "N/A",
                            modifier = Modifier.weight(1f)
                        )
                        MetricTile(
                            label = "Role",
                            value = node.role ?: "N/A",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (distanceKm != null) {
                        val miles = MeshGeoUtils.kmToMiles(distanceKm)
                        HorizontalDivider(color = TacticalOutline.copy(alpha = 0.6f))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Straighten,
                                contentDescription = null,
                                tint = NodeDirectGreen,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Distance from Self: ",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "%.2f mi (%.2f km)",
                                    miles,
                                    distanceKm
                                ),
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = TextPrimary
                            )
                        }
                    }

                    if (node.hasValidGps) {
                        HorizontalDivider(color = TacticalOutline.copy(alpha = 0.6f))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = NodeMultiHopSky,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = buildString {
                                    append(String.format(Locale.US, "%.5f, %.5f", node.latitude, node.longitude))
                                    if (node.altitude != null) {
                                        append("  •  ${node.altitude} m")
                                    }
                                },
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            if (node.hasValidGps) {
                InspectorSectionCard(
                    title = stringResource(R.string.rf_assign_sites_title),
                    icon = Icons.Default.CellTower,
                    accentColor = NodeSelfAmber
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onAssignViewshedTx(node) },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NodeSelfAmber,
                                    contentColor = TacticalCanvas
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("inspector_viewshed_tx_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CellTower,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.rf_viewshed_tx),
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Button(
                                onClick = { onAssignViewshedRx(node) },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NodeMultiHopSky,
                                    contentColor = TacticalCanvas
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("inspector_viewshed_rx_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Radar,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.rf_viewshed_rx),
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onAssignBridgeTx(node) },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, NodeSelfAmber.copy(alpha = 0.7f)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = NodeSelfAmber
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("inspector_bridge_tx_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Hub,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.rf_bridge_tx),
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            OutlinedButton(
                                onClick = { onAssignBridgeRx(node) },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, NodeMultiHopSky.copy(alpha = 0.7f)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = NodeMultiHopSky
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("inspector_bridge_rx_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Hub,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.rf_bridge_rx),
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        if (!node.isSelf && selfNode?.hasValidGps == true && onInspectFresnelSightline != null) {
                            OutlinedButton(
                                onClick = { onInspectFresnelSightline(node) },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, NodeDirectGreen.copy(alpha = 0.75f)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = NodeDirectGreen
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("inspector_fresnel_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Terrain,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.fresnel_inspect_from_self),
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // RF Metrics Section
            InspectorSectionCard(
                title = "RF Link Metrics",
                icon = Icons.Default.Radar,
                accentColor = NodeMultiHopSky
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricTile(
                        label = "Hops Away",
                        value = node.hopsAway?.toString() ?: "N/A",
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        label = "Via MQTT",
                        value = if (node.viaMqtt) "Yes" else "No",
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        label = "SNR",
                        value = node.snr?.let { String.format(Locale.US, "%.1f dB", it) } ?: "N/A",
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        label = "RSSI",
                        value = node.rssi?.let { "$it dBm" } ?: "N/A",
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Last Heard: ${MeshGeoUtils.formatRelativeTime(node.lastHeard)} (${MeshGeoUtils.formatAbsoluteTime(node.lastHeard)})",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            // Device Metrics Section
            InspectorSectionCard(
                title = "Device Telemetry",
                icon = Icons.Default.BatteryChargingFull,
                accentColor = NodeDirectGreen
            ) {
                val dm = node.deviceMetrics
                if (dm == null) {
                    Text(
                        text = "No deviceMetrics recorded in NodeDB.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MetricTile(
                            label = "Battery",
                            value = dm.batteryLevel?.let { "$it%" } ?: "N/A",
                            modifier = Modifier.weight(1f)
                        )
                        MetricTile(
                            label = "Voltage",
                            value = dm.voltage?.let { String.format(Locale.US, "%.2f V", it) } ?: "N/A",
                            modifier = Modifier.weight(1f)
                        )
                        MetricTile(
                            label = "ChUtil",
                            value = dm.channelUtilization?.let { String.format(Locale.US, "%.1f%%", it) } ?: "N/A",
                            modifier = Modifier.weight(1f)
                        )
                        MetricTile(
                            label = "AirUtilTX",
                            value = dm.airUtilTx?.let { String.format(Locale.US, "%.1f%%", it) } ?: "N/A",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Environment Telemetry Section
            InspectorSectionCard(
                title = "Environment Telemetry",
                icon = Icons.Default.DeviceThermostat,
                accentColor = classification.color
            ) {
                val em = node.environmentMetrics
                if (em == null) {
                    Text(
                        text = "No environmentMetrics recorded in NodeDB.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                } else {
                    val tempValue = em.temperature?.let { c ->
                        val f = MeshGeoUtils.celsiusToFahrenheit(c)
                        String.format(Locale.US, "%.1f°C / %.1f°F", c, f)
                    } ?: "N/A"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MetricTile(
                            label = "Temperature",
                            value = tempValue,
                            modifier = Modifier.weight(1.4f)
                        )
                        MetricTile(
                            label = "Humidity",
                            value = em.relativeHumidity?.let { String.format(Locale.US, "%.1f%%", it) } ?: "N/A",
                            modifier = Modifier.weight(1f)
                        )
                        MetricTile(
                            label = "Pressure",
                            value = em.barometricPressure?.let { String.format(Locale.US, "%.1f hPa", it) } ?: "N/A",
                            modifier = Modifier.weight(1.1f)
                        )
                    }
                }
            }

            // View Raw JSON Expandable Card
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
                            .clickable { rawJsonExpanded = !rawJsonExpanded }
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                            .testTag("view_raw_json_toggle"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Code,
                                contentDescription = null,
                                tint = NodeMultiHopSky,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.raw_json_toggle),
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (rawJsonExpanded) {
                                IconButton(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(node.rawJson))
                                        copiedFeedback = true
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = stringResource(R.string.copy_json),
                                        tint = if (copiedFeedback) NodeDirectGreen else TextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Icon(
                                imageVector = if (rawJsonExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = TextSecondary
                            )
                        }
                    }

                    AnimatedVisibility(visible = rawJsonExpanded) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(TacticalCanvas)
                                .border(1.dp, TacticalOutline, RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                                .padding(12.dp)
                                .horizontalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = node.rawJson,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = Color(0xFFCBD5E1),
                                modifier = Modifier.testTag("raw_json_content")
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InspectorSectionCard(
    title: String,
    icon: ImageVector,
    accentColor: Color,
    content: @Composable () -> Unit
) {
    Surface(
        color = TacticalSurfaceElevated,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, TacticalOutline, RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(TacticalSurfaceBright.copy(alpha = 0.55f))
            .padding(horizontal = 8.dp, vertical = 7.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun StatusBadge(
    text: String,
    color: Color
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = text,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            color = color
        )
    }
}
