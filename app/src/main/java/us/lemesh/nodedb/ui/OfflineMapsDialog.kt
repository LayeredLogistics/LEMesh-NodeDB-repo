package us.lemesh.nodedb.ui

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.osmdroid.views.MapView
import us.lemesh.nodedb.R
import us.lemesh.nodedb.data.OfflineDemPreset
import us.lemesh.nodedb.data.OfflineDemUiState
import us.lemesh.nodedb.ui.state.MapViewportState
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

@Composable
internal fun OfflineMapsDialog(
    visible: Boolean,
    uiState: OfflineDemUiState,
    mapViewProvider: () -> MapView?,
    viewportState: MapViewportState,
    onToggleOfflineOnly: (Boolean) -> Unit,
    onDownloadPreset: (OfflineDemPreset) -> Unit,
    onDownloadViewport: (north: Double, south: Double, west: Double, east: Double) -> Unit,
    onCancelDownload: () -> Unit,
    onClearCache: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    val activeDownload = uiState.activeDownload

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TacticalSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        modifier = Modifier.widthIn(max = 560.dp),
        title = {
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
                        imageVector = if (uiState.isOfflineOnlyMode) Icons.Default.CloudOff else Icons.Default.Terrain,
                        contentDescription = null,
                        tint = if (uiState.isOfflineOnlyMode) NodeSelfAmber else NodeDirectGreen,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.offline_maps_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    color = if (uiState.isOfflineOnlyMode) {
                        NodeSelfAmber.copy(alpha = 0.18f)
                    } else {
                        NodeDirectGreen.copy(alpha = 0.16f)
                    },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(
                        1.dp,
                        if (uiState.isOfflineOnlyMode) NodeSelfAmber.copy(alpha = 0.65f) else NodeDirectGreen.copy(alpha = 0.6f)
                    )
                ) {
                    Text(
                        text = if (uiState.isOfflineOnlyMode) "OFFLINE-ONLY" else "ONLINE",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = if (uiState.isOfflineOnlyMode) NodeSelfAmber else NodeDirectGreen,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.offline_maps_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                // Offline-Only DEM Mode Toggle
                Surface(
                    color = TacticalSurfaceElevated,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        1.dp,
                        if (uiState.isOfflineOnlyMode) NodeSelfAmber.copy(alpha = 0.65f) else TacticalOutline
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.offline_only_mode_title),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Text(
                                text = stringResource(R.string.offline_only_mode_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = uiState.isOfflineOnlyMode,
                            onCheckedChange = onToggleOfflineOnly,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = TacticalCanvas,
                                checkedTrackColor = NodeSelfAmber,
                                uncheckedThumbColor = TextSecondary,
                                uncheckedTrackColor = TacticalSurfaceBright
                            ),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("offline_only_mode_switch")
                        )
                    }
                }

                // Cache Statistics & Clear Action
                Surface(
                    color = TacticalCanvas,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, TacticalOutline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(
                                R.string.offline_cache_summary,
                                uiState.cacheSizeMb,
                                uiState.cachedTileCount
                            ),
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = NodeMultiHopSky,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("offline_cache_summary_text")
                        )
                        OutlinedButton(
                            onClick = onClearCache,
                            enabled = uiState.cachedTileCount > 0 && activeDownload == null,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            border = BorderStroke(
                                1.dp,
                                if (uiState.cachedTileCount > 0) AlertErrorRed.copy(alpha = 0.65f) else TacticalOutline
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = AlertErrorRed,
                                disabledContentColor = TextMuted
                            ),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("offline_clear_cache_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.offline_clear_cache),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }

                // Active Download Progress
                if (activeDownload != null) {
                    Surface(
                        color = TacticalSurfaceElevated,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, NodeDirectGreen.copy(alpha = 0.7f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("offline_download_progress_card")
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
                                Text(
                                    text = activeDownload.packTitle,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "${activeDownload.downloadedTiles} / ${activeDownload.totalTiles} tiles",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = NodeDirectGreen
                                )
                            }
                            LinearProgressIndicator(
                                progress = { activeDownload.fraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(4.dp)),
                                color = NodeDirectGreen,
                                trackColor = TacticalSurfaceBright
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (activeDownload.failedTiles > 0) {
                                        "${activeDownload.failedTiles} failed tile(s)"
                                    } else if (activeDownload.isCancelling) {
                                        "Canceling…"
                                    } else {
                                        "Caching AWS Terrarium elevation tiles…"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (activeDownload.failedTiles > 0) AlertErrorRed else TextSecondary
                                )
                                OutlinedButton(
                                    onClick = onCancelDownload,
                                    enabled = !activeDownload.isCancelling,
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    border = BorderStroke(1.dp, AlertErrorRed.copy(alpha = 0.7f)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AlertErrorRed),
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("offline_cancel_download_button")
                                ) {
                                    Text(
                                        text = stringResource(R.string.offline_cancel_action),
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }
                }

                uiState.errorMessage?.let { err ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(AlertErrorRed.copy(alpha = 0.16f))
                            .border(1.dp, AlertErrorRed.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimary
                        )
                    }
                }

                uiState.statusMessage?.let { status ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(NodeMultiHopSky.copy(alpha = 0.14f))
                            .border(1.dp, NodeMultiHopSky.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimary
                        )
                    }
                }

                // Pack 1: Erie County & Presque Isle Core
                OfflinePackCard(
                    title = stringResource(R.string.offline_pack_erie_title),
                    subtitle = stringResource(R.string.offline_pack_erie_desc),
                    enabled = activeDownload == null,
                    buttonTestTag = "offline_download_erie_button",
                    onDownload = { onDownloadPreset(OfflineDemPreset.ERIE_PRESQUE_ISLE) }
                )

                // Pack 2: NW PA & I-90 Corridor
                OfflinePackCard(
                    title = stringResource(R.string.offline_pack_nwpa_title),
                    subtitle = stringResource(R.string.offline_pack_nwpa_desc),
                    enabled = activeDownload == null,
                    buttonTestTag = "offline_download_nwpa_button",
                    onDownload = { onDownloadPreset(OfflineDemPreset.NW_PA_CORRIDOR) }
                )

                // Pack 3: Current Map Viewport
                OfflinePackCard(
                    title = stringResource(R.string.offline_pack_viewport_title),
                    subtitle = stringResource(R.string.offline_pack_viewport_desc),
                    enabled = activeDownload == null,
                    icon = Icons.Default.Map,
                    buttonTestTag = "offline_download_viewport_button",
                    onDownload = {
                        val attachedBox = mapViewProvider()
                            ?.takeIf { it.width > 0 && it.height > 0 }
                            ?.boundingBox
                        if (attachedBox != null) {
                            onDownloadViewport(
                                attachedBox.latNorth.coerceIn(-85.0, 85.0),
                                attachedBox.latSouth.coerceIn(-85.0, 85.0),
                                attachedBox.lonWest.coerceIn(-180.0, 180.0),
                                attachedBox.lonEast.coerceIn(-180.0, 180.0)
                            )
                        } else {
                            val centerLat = viewportState.cameraLat ?: 42.1292
                            val centerLon = viewportState.cameraLon ?: -80.0851
                            onDownloadViewport(
                                (centerLat + 0.15).coerceIn(-85.0, 85.0),
                                (centerLat - 0.15).coerceIn(-85.0, 85.0),
                                (centerLon - 0.15).coerceIn(-180.0, 180.0),
                                (centerLon + 0.15).coerceIn(-180.0, 180.0)
                            )
                        }
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("offline_maps_close_button")
            ) {
                Text(
                    text = "Done",
                    color = NodeDirectGreen,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    )
}

@Composable
private fun OfflinePackCard(
    title: String,
    subtitle: String,
    enabled: Boolean,
    buttonTestTag: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.CloudDownload,
    onDownload: () -> Unit
) {
    Surface(
        color = TacticalSurfaceElevated,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, TacticalOutline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Text(
                    text = subtitle,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 10.sp,
                    color = TextSecondary
                )
            }
            Button(
                onClick = onDownload,
                enabled = enabled,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NodeDirectGreen,
                    contentColor = TacticalCanvas,
                    disabledContainerColor = TacticalSurfaceBright,
                    disabledContentColor = TextMuted
                ),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag(buttonTestTag)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = stringResource(R.string.offline_download_action),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
