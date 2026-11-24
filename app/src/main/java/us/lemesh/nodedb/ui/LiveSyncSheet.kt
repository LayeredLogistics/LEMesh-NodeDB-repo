package us.lemesh.nodedb.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import us.lemesh.nodedb.R
import us.lemesh.nodedb.data.BleSyncUiState
import us.lemesh.nodedb.data.LiveSyncConfig
import us.lemesh.nodedb.data.LiveSyncStatus
import us.lemesh.nodedb.data.LiveSyncTransport
import us.lemesh.nodedb.data.LiveSyncUiState
import us.lemesh.nodedb.data.MeshNode
import us.lemesh.nodedb.ui.theme.AlertErrorRed
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
 * Compact top-toolbar status badge visible across Mesh Map, Viewshed, and Bridge Finder.
 * Tapping opens [LiveSyncBottomSheet] for one-tap Connect/Disconnect and broker/endpoint config.
 */
@Composable
fun LiveSyncToolbarBadge(
    status: LiveSyncStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = when (status) {
        LiveSyncStatus.CONNECTED -> NodeDirectGreen
        LiveSyncStatus.RECONNECTING -> NodeSelfAmber
        LiveSyncStatus.OFFLINE -> TextSecondary
    }
    val icon = when (status) {
        LiveSyncStatus.CONNECTED -> Icons.Default.CloudDone
        LiveSyncStatus.RECONNECTING -> Icons.Default.CloudSync
        LiveSyncStatus.OFFLINE -> Icons.Default.CloudOff
    }
    val label = when (status) {
        LiveSyncStatus.CONNECTED -> stringResource(R.string.live_sync_status_connected)
        LiveSyncStatus.RECONNECTING -> stringResource(R.string.live_sync_status_reconnecting)
        LiveSyncStatus.OFFLINE -> stringResource(R.string.live_sync_status_offline)
    }

    Surface(
        color = if (status == LiveSyncStatus.OFFLINE) {
            TacticalSurfaceElevated.copy(alpha = 0.94f)
        } else {
            accent.copy(alpha = 0.18f)
        },
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (status == LiveSyncStatus.OFFLINE) TacticalOutline else accent.copy(alpha = 0.75f)
        ),
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag("live_sync_toolbar_badge")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Icon(
                imageVector = icon,
                contentDescription = "Live telemetry sync: $label",
                tint = accent,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (status == LiveSyncStatus.OFFLINE) TextPrimary else accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

enum class SyncSheetTab(val label: String) {
    BLUETOOTH("Bluetooth"),
    MQTT("MQTT"),
    LOCAL_JSON("Local JSON")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveSyncBottomSheet(
    uiState: LiveSyncUiState,
    onUpdateConfig: (LiveSyncConfig) -> Unit,
    onConnect: (LiveSyncConfig) -> Unit,
    onDisconnect: () -> Unit,
    onDismissError: () -> Unit,
    onDismiss: () -> Unit,
    bleState: BleSyncUiState = BleSyncUiState(),
    bleActions: BleSyncActions = BleSyncActions(),
    myRadioNode: MeshNode? = null,
    initialTab: SyncSheetTab = if (uiState.status != LiveSyncStatus.OFFLINE && !bleState.isActiveOrConnected) {
        if (uiState.config.transport == LiveSyncTransport.LOCAL_JSON) SyncSheetTab.LOCAL_JSON else SyncSheetTab.MQTT
    } else {
        SyncSheetTab.BLUETOOTH
    }
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var selectedTab by remember { mutableStateOf(initialTab) }
    var transport by remember(uiState.config) { mutableStateOf(uiState.config.transport) }
    var mqttHost by remember(uiState.config) { mutableStateOf(uiState.config.mqttHost) }
    var mqttPortText by remember(uiState.config) { mutableStateOf(uiState.config.mqttPort.toString()) }
    var mqttUseTls by remember(uiState.config) { mutableStateOf(uiState.config.mqttUseTls) }
    var mqttTopic by remember(uiState.config) { mutableStateOf(uiState.config.mqttTopicFilter) }
    var mqttUsername by remember(uiState.config) { mutableStateOf(uiState.config.mqttUsername) }
    var mqttPassword by remember(uiState.config) { mutableStateOf(uiState.config.mqttPassword) }
    var localJsonUrl by remember(uiState.config) { mutableStateOf(uiState.config.localJsonUrl) }
    var pollIntervalText by remember(uiState.config) { mutableStateOf(uiState.config.pollIntervalSeconds.toString()) }

    fun buildConfig(): LiveSyncConfig = LiveSyncConfig(
        transport = transport,
        mqttHost = mqttHost.trim(),
        mqttPort = mqttPortText.toIntOrNull()?.coerceIn(1, 65535)
            ?: if (mqttUseTls) 8883 else 1883,
        mqttUseTls = mqttUseTls,
        mqttUsername = mqttUsername.trim(),
        mqttPassword = mqttPassword,
        mqttTopicFilter = mqttTopic.trim().ifBlank { "msh/US/2/json/#" },
        localJsonUrl = localJsonUrl.trim(),
        pollIntervalSeconds = pollIntervalText.toIntOrNull()?.coerceIn(5, 300) ?: 15
    )

    val statusAccent = when (uiState.status) {
        LiveSyncStatus.CONNECTED -> NodeDirectGreen
        LiveSyncStatus.RECONNECTING -> NodeSelfAmber
        LiveSyncStatus.OFFLINE -> TextSecondary
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
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.live_sync_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = stringResource(R.string.live_sync_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("live_sync_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close live telemetry sync",
                        tint = TextSecondary
                    )
                }
            }

            // 3-tab transport selector: Bluetooth Radio | MQTT | Local JSON
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SyncSheetTab.entries.forEach { tab ->
                    val selected = selectedTab == tab
                    val tabAccent = if (tab == SyncSheetTab.BLUETOOTH) NodeDirectGreen else NodeMultiHopSky
                    FilterChip(
                        selected = selected,
                        onClick = {
                            selectedTab = tab
                            when (tab) {
                                SyncSheetTab.MQTT -> transport = LiveSyncTransport.MQTT
                                SyncSheetTab.LOCAL_JSON -> transport = LiveSyncTransport.LOCAL_JSON
                                SyncSheetTab.BLUETOOTH -> Unit
                            }
                        },
                        label = {
                            Text(
                                text = tab.label,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 12.sp
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = TacticalSurfaceElevated,
                            labelColor = TextSecondary,
                            selectedContainerColor = tabAccent.copy(alpha = 0.22f),
                            selectedLabelColor = tabAccent
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag(
                                when (tab) {
                                    SyncSheetTab.BLUETOOTH -> "live_sync_transport_bluetooth"
                                    SyncSheetTab.MQTT -> "live_sync_transport_mqtt"
                                    SyncSheetTab.LOCAL_JSON -> "live_sync_transport_local_json"
                                }
                            )
                    )
                }
            }

            if (selectedTab == SyncSheetTab.BLUETOOTH) {
                BleRadioSyncSection(
                    bleState = bleState,
                    myRadioNode = myRadioNode,
                    actions = bleActions,
                    onCenterDone = onDismiss
                )
            } else {
                // Status summary & one-tap connect toggle for MQTT / Local JSON
                Surface(
                    color = TacticalSurfaceElevated,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, statusAccent.copy(alpha = 0.65f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(9.dp)
                                        .clip(CircleShape)
                                        .background(statusAccent)
                                )
                                Text(
                                    text = uiState.status.label.uppercase(),
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = statusAccent
                                )
                            }
                            Text(
                                text = uiState.config.toRedactedEndpointSummary(),
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 10.sp,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${uiState.packetsReceived} packets • ${uiState.nodesUpdatedCount} node updates",
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 10.sp,
                                color = TextMuted
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (uiState.status == LiveSyncStatus.OFFLINE) {
                                    onConnect(buildConfig())
                                } else {
                                    onDisconnect()
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (uiState.status == LiveSyncStatus.OFFLINE) NodeDirectGreen else AlertErrorRed,
                                contentColor = TacticalCanvas
                            ),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("live_sync_toggle_button")
                        ) {
                            Icon(
                                imageVector = if (uiState.status == LiveSyncStatus.OFFLINE) Icons.Default.Link else Icons.Default.LinkOff,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (uiState.status == LiveSyncStatus.OFFLINE) {
                                    stringResource(R.string.live_sync_connect)
                                } else {
                                    stringResource(R.string.live_sync_disconnect)
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                if (uiState.lastError != null) {
                    RfFloatingErrorBanner(
                        message = uiState.lastError,
                        onDismiss = onDismissError
                    )
                }

            val fieldColors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = TacticalSurfaceElevated,
                unfocusedContainerColor = TacticalSurfaceElevated,
                focusedBorderColor = NodeMultiHopSky,
                unfocusedBorderColor = TacticalOutline,
                focusedLabelColor = NodeMultiHopSky,
                unfocusedLabelColor = TextMuted
            )

            if (transport == LiveSyncTransport.MQTT) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = mqttHost,
                        onValueChange = { mqttHost = it },
                        label = { Text("Broker Host") },
                        singleLine = true,
                        colors = fieldColors,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1.7f)
                            .testTag("live_sync_mqtt_host_input")
                    )
                    OutlinedTextField(
                        value = mqttPortText,
                        onValueChange = { if (it.all(Char::isDigit)) mqttPortText = it.take(5) },
                        label = { Text("Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = fieldColors,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(0.8f)
                            .testTag("live_sync_mqtt_port_input")
                    )
                }

                OutlinedTextField(
                    value = mqttTopic,
                    onValueChange = { mqttTopic = it },
                    label = { Text("Topic Filter (e.g. msh/US/2/json/#)") },
                    singleLine = true,
                    colors = fieldColors,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("live_sync_mqtt_topic_input")
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(TacticalSurfaceElevated)
                        .border(1.dp, TacticalOutline, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = if (mqttUseTls) NodeDirectGreen else TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Use TLS Encryption (MQTTS)",
                                style = MaterialTheme.typography.labelLarge,
                                color = TextPrimary
                            )
                            Text(
                                text = "Port 8883 standard for encrypted brokers",
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                    }
                    Switch(
                        checked = mqttUseTls,
                        onCheckedChange = { enabled ->
                            mqttUseTls = enabled
                            if (enabled && mqttPortText == "1883") mqttPortText = "8883"
                            if (!enabled && mqttPortText == "8883") mqttPortText = "1883"
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TacticalCanvas,
                            checkedTrackColor = NodeDirectGreen
                        ),
                        modifier = Modifier.testTag("live_sync_mqtt_tls_switch")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = mqttUsername,
                        onValueChange = { mqttUsername = it },
                        label = { Text("Username (optional)") },
                        singleLine = true,
                        colors = fieldColors,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("live_sync_mqtt_user_input")
                    )
                    OutlinedTextField(
                        value = mqttPassword,
                        onValueChange = { mqttPassword = it },
                        label = { Text("Password (private)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = fieldColors,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("live_sync_mqtt_pass_input")
                    )
                }
            } else {
                OutlinedTextField(
                    value = localJsonUrl,
                    onValueChange = { localJsonUrl = it },
                    label = { Text("Local NodeDB JSON URL (http/https)") },
                    placeholder = { Text("http://192.168.1.50:8080/nodedb.json") },
                    singleLine = true,
                    colors = fieldColors,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("live_sync_json_url_input")
                )
                OutlinedTextField(
                    value = pollIntervalText,
                    onValueChange = { if (it.all(Char::isDigit)) pollIntervalText = it.take(3) },
                    label = { Text("Poll Interval (seconds, 5–300)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = fieldColors,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("live_sync_poll_interval_input")
                )
            }

            Text(
                text = "Non-destructive merge: live updates refresh positions, battery, and neighbor SNR while preserving your custom notes, colors, and manual sites. Credentials stay private on-device.",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = TextMuted
            )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { onUpdateConfig(buildConfig()) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("live_sync_save_button")
                    ) {
                        Text(
                            text = stringResource(R.string.live_sync_save_apply),
                            color = TextPrimary
                        )
                    }
                    Button(
                        onClick = { onConnect(buildConfig()) },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NodeMultiHopSky,
                            contentColor = TacticalCanvas
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("live_sync_save_and_connect_button")
                    ) {
                        Text(
                            text = "Save & Connect",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
