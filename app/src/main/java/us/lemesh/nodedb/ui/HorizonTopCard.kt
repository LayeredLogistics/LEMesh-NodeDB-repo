package us.lemesh.nodedb.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import us.lemesh.nodedb.R
import us.lemesh.nodedb.data.BleConnectionStatus
import us.lemesh.nodedb.data.BleSyncUiState
import us.lemesh.nodedb.data.LiveSyncStatus
import us.lemesh.nodedb.data.LiveSyncTransport
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

/**
 * Unified collapsible top workspace card used across Mesh, Viewshed, and Bridge modes.
 * Houses Row 1 (Brand emblem/title, merged Offline DEM + Live Sync pill, Layers trigger,
 * and collapse/expand chevron) plus collapsible mode-specific rows separated by subtle 1.dp dividers.
 */
@Composable
internal fun UnifiedHorizonTopCard(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
    savedLayersActiveCount: Int = 0,
    layersTestTag: String = "chip_saved_layers",
    layersIconTintActive: Boolean = savedLayersActiveCount > 0,
    onOpenLayers: (() -> Unit)? = null,
    isOfflineOnlyMode: Boolean = false,
    isOfflineDownloading: Boolean = false,
    onOpenOfflineMaps: (() -> Unit)? = null,
    liveSyncStatus: LiveSyncStatus = LiveSyncStatus.OFFLINE,
    liveSyncTransport: LiveSyncTransport = LiveSyncTransport.MQTT,
    bleSyncState: BleSyncUiState = BleSyncUiState(),
    onOpenLiveSync: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = TacticalSurface.copy(alpha = 0.94f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, TacticalOutline),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
        modifier = modifier
            .fillMaxWidth()
            .testTag("unified_horizon_top_card")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Row 1: Brand & Status Header (Always visible)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("horizon_brand_pill"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.lemesh_brand_emblem),
                        contentDescription = stringResource(R.string.lemesh_brand_emblem_cd),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .border(1.dp, NodeDirectGreen.copy(alpha = 0.65f), CircleShape)
                            .testTag("toolbar_lemesh_emblem")
                    )
                    Text(
                        text = stringResource(R.string.app_full_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (onOpenOfflineMaps != null || onOpenLiveSync != null) {
                        MergedHorizonStatusPill(
                            isOfflineOnlyMode = isOfflineOnlyMode,
                            isOfflineDownloading = isOfflineDownloading,
                            onOpenOfflineMaps = onOpenOfflineMaps,
                            liveSyncStatus = liveSyncStatus,
                            liveSyncTransport = liveSyncTransport,
                            bleSyncState = bleSyncState,
                            onOpenLiveSync = onOpenLiveSync
                        )
                    }

                    if (onOpenLayers != null) {
                        Surface(
                            onClick = onOpenLayers,
                            color = if (savedLayersActiveCount > 0) NodeDirectGreen.copy(alpha = 0.16f) else TacticalSurfaceElevated,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(
                                1.dp,
                                if (savedLayersActiveCount > 0) NodeDirectGreen.copy(alpha = 0.7f) else TacticalOutline
                            ),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag(layersTestTag)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Layers,
                                    contentDescription = stringResource(R.string.saved_layers_title),
                                    tint = if (layersIconTintActive) NodeDirectGreen else TextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                                if (savedLayersActiveCount > 0) {
                                    Box(
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(NodeDirectGreen)
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            text = savedLayersActiveCount.toString(),
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp,
                                            color = TacticalCanvas
                                        )
                                    }
                                }
                            }
                        }
                    }

                    IconButton(
                        onClick = onToggleExpanded,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("top_card_collapse_toggle")
                    ) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = stringResource(
                                if (expanded) R.string.action_collapse_top_bar else R.string.action_expand_top_bar
                            ),
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = TacticalOutline.copy(alpha = 0.65f)
                    )
                    content()
                }
            }
        }
    }
}

@Composable
internal fun MergedHorizonStatusPill(
    isOfflineOnlyMode: Boolean,
    isOfflineDownloading: Boolean,
    onOpenOfflineMaps: (() -> Unit)?,
    liveSyncStatus: LiveSyncStatus,
    onOpenLiveSync: (() -> Unit)?,
    modifier: Modifier = Modifier,
    liveSyncTransport: LiveSyncTransport = LiveSyncTransport.MQTT,
    bleSyncState: BleSyncUiState = BleSyncUiState()
) {
    val bleIsConnected = bleSyncState.status == BleConnectionStatus.CONNECTED
    val bleIsSyncing = bleSyncState.status == BleConnectionStatus.SYNCING ||
        bleSyncState.status == BleConnectionStatus.CONNECTING
    val bleIsReconnecting = bleSyncState.status == BleConnectionStatus.RECONNECTING
    val bleHighlightActive = bleIsConnected || bleIsSyncing || bleIsReconnecting

    val syncAccent = when {
        bleIsConnected -> NodeDirectGreen
        bleIsSyncing -> NodeMultiHopSky
        bleIsReconnecting -> NodeSelfAmber
        liveSyncStatus == LiveSyncStatus.CONNECTED -> NodeDirectGreen
        liveSyncStatus == LiveSyncStatus.RECONNECTING -> NodeSelfAmber
        else -> TextSecondary
    }
    val syncLabel = when {
        bleIsConnected -> {
            val shortTag = bleSyncState.connectedShortName
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: bleSyncState.connectedDeviceName?.trim()?.take(8)?.takeIf { it.isNotEmpty() }
                ?: "Radio"
            "BLE: $shortTag"
        }
        bleIsSyncing -> stringResource(R.string.ble_status_syncing)
        bleIsReconnecting -> stringResource(R.string.ble_status_reconnecting)
        liveSyncStatus == LiveSyncStatus.CONNECTED -> {
            if (liveSyncTransport == LiveSyncTransport.MQTT) "MQTT"
            else stringResource(R.string.live_sync_status_connected)
        }
        liveSyncStatus == LiveSyncStatus.RECONNECTING -> stringResource(R.string.live_sync_status_reconnecting)
        else -> stringResource(R.string.live_sync_status_offline)
    }
    val isSyncHighlighted = bleHighlightActive || liveSyncStatus != LiveSyncStatus.OFFLINE
    val offlineTint = when {
        isOfflineOnlyMode -> NodeSelfAmber
        isOfflineDownloading -> NodeDirectGreen
        else -> TextSecondary
    }
    val borderAccent = when {
        bleIsConnected || liveSyncStatus == LiveSyncStatus.CONNECTED -> NodeDirectGreen.copy(alpha = 0.65f)
        bleIsSyncing -> NodeMultiHopSky.copy(alpha = 0.7f)
        isOfflineOnlyMode || bleIsReconnecting || liveSyncStatus == LiveSyncStatus.RECONNECTING -> NodeSelfAmber.copy(alpha = 0.7f)
        isOfflineDownloading -> NodeDirectGreen.copy(alpha = 0.65f)
        else -> TacticalOutline
    }

    Surface(
        color = TacticalSurfaceElevated.copy(alpha = 0.94f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, borderAccent),
        modifier = modifier
            .heightIn(min = 38.dp)
            .clip(RoundedCornerShape(10.dp))
            .testTag("merged_status_pill")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onOpenOfflineMaps != null) {
                Box(
                    modifier = Modifier
                        .widthIn(min = 34.dp)
                        .heightIn(min = 38.dp)
                        .background(
                            when {
                                isOfflineOnlyMode -> NodeSelfAmber.copy(alpha = 0.16f)
                                isOfflineDownloading -> NodeDirectGreen.copy(alpha = 0.16f)
                                else -> TacticalSurfaceElevated.copy(alpha = 0f)
                            }
                        )
                        .clickable(onClick = onOpenOfflineMaps)
                        .padding(horizontal = 8.dp, vertical = 7.dp)
                        .testTag("offline_maps_toolbar_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isOfflineOnlyMode) Icons.Default.CloudOff else Icons.Default.CloudDownload,
                        contentDescription = stringResource(R.string.action_offline_maps),
                        tint = offlineTint,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }

            if (onOpenOfflineMaps != null && onOpenLiveSync != null) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(20.dp)
                        .background(TacticalOutline.copy(alpha = 0.85f))
                )
            }

            if (onOpenLiveSync != null) {
                Row(
                    modifier = Modifier
                        .heightIn(min = 38.dp)
                        .background(
                            if (!isSyncHighlighted) {
                                TacticalSurfaceElevated.copy(alpha = 0f)
                            } else {
                                syncAccent.copy(alpha = 0.16f)
                            }
                        )
                        .clickable(onClick = onOpenLiveSync)
                        .padding(horizontal = 8.dp, vertical = 7.dp)
                        .semantics {
                            contentDescription = "Radio and telemetry sync: $syncLabel"
                        }
                        .testTag("live_sync_toolbar_badge"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (bleHighlightActive) {
                        Icon(
                            imageVector = if (bleIsConnected) Icons.Default.BluetoothConnected else Icons.Default.BluetoothSearching,
                            contentDescription = null,
                            tint = syncAccent,
                            modifier = Modifier.size(13.dp)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(syncAccent)
                        )
                    }
                    Text(
                        text = syncLabel,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (!isSyncHighlighted) TextPrimary else syncAccent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Full-width MESH mode search + Filters middle row and standby/filter bottom row inside [UnifiedHorizonTopCard].
 */
@Composable
internal fun ColumnScope.UnifiedMeshTopCardBody(
    searchQuery: String,
    activeFilterCount: Int,
    onSearchQueryChange: (String) -> Unit,
    onOpenFilters: () -> Unit,
    showStandbyActions: Boolean,
    showAddNodeButton: Boolean,
    onLoadJsonClick: () -> Unit,
    onToggleAddNodeMode: () -> Unit,
    hasFilteredSubset: Boolean,
    filteredCount: Int,
    plottedGpsCount: Int,
    onResetFilters: () -> Unit
) {
    // Middle Row: Full-width search bar + docked Filters trigger
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(18.dp)
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 40.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (searchQuery.isEmpty()) {
                Text(
                    text = stringResource(R.string.search_placeholder),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = JetBrainsMonoFontFamily,
                    color = TextPrimary
                ),
                cursorBrush = SolidColor(NodeDirectGreen),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("map_search_input")
            )
        }

        if (searchQuery.isNotEmpty()) {
            IconButton(
                onClick = { onSearchQueryChange("") },
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_clear_search),
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Surface(
            onClick = onOpenFilters,
            color = if (activeFilterCount > 0) NodeMultiHopSky.copy(alpha = 0.16f) else TacticalSurfaceElevated,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(
                1.dp,
                if (activeFilterCount > 0) NodeMultiHopSky.copy(alpha = 0.7f) else TacticalOutline
            ),
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("map_filters_button")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = stringResource(R.string.map_filters_tools_title),
                    tint = if (activeFilterCount > 0) NodeMultiHopSky else TextPrimary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = stringResource(R.string.map_filters_short),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (activeFilterCount > 0) NodeMultiHopSky else TextPrimary
                )
                if (activeFilterCount > 0) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(NodeMultiHopSky)
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = activeFilterCount.toString(),
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = TacticalCanvas
                        )
                    }
                }
            }
        }
    }

    // Bottom Row (Standby only): Load JSON & Add Node side-by-side equal width
    if (showStandbyActions) {
        HorizontalDivider(
            thickness = 1.dp,
            color = TacticalOutline.copy(alpha = 0.65f)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onLoadJsonClick,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NodeDirectGreen,
                    contentColor = TacticalCanvas
                ),
                modifier = Modifier
                    .weight(1f)
                    .minimumInteractiveComponentSize()
                    .testTag("load_json_button")
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.action_load_json),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (showAddNodeButton) {
                OutlinedButton(
                    onClick = onToggleAddNodeMode,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    border = BorderStroke(1.dp, NodeMultiHopSky.copy(alpha = 0.7f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = TacticalSurfaceElevated.copy(alpha = 0.85f),
                        contentColor = NodeMultiHopSky
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("standby_add_node_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.AddLocationAlt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.action_add_node),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    // Filtered subset quick reset row docked inside the same card
    if (hasFilteredSubset) {
        HorizontalDivider(
            thickness = 1.dp,
            color = TacticalOutline.copy(alpha = 0.65f)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onResetFilters)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("filtered_subset_reset_row"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "$filteredCount/$plottedGpsCount GPS visible",
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = NodeMultiHopSky
            )
            Text(
                text = stringResource(R.string.filters_reset_all),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
