package us.lemesh.nodedb.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
import us.lemesh.nodedb.data.DiscoveredMeshtasticDevice
import us.lemesh.nodedb.data.MeshGeoUtils
import us.lemesh.nodedb.data.MeshNode
import us.lemesh.nodedb.data.MeshtasticBleRepository
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

data class BleSyncActions(
    val onStartScan: () -> Unit = {},
    val onStopScan: () -> Unit = {},
    val onConnectDevice: (DiscoveredMeshtasticDevice) -> Unit = {},
    val onReconnectSaved: () -> Unit = {},
    val onDisconnect: () -> Unit = {},
    val onForgetSaved: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    val onCenterOnMyRadio: () -> Unit = {}
)

private sealed interface PendingBlePermissionAction {
    data object Scan : PendingBlePermissionAction
    data object ReconnectSaved : PendingBlePermissionAction
    data class Connect(val device: DiscoveredMeshtasticDevice) : PendingBlePermissionAction
}

@Composable
internal fun BleRadioSyncSection(
    bleState: BleSyncUiState,
    myRadioNode: MeshNode?,
    actions: BleSyncActions,
    onCenterDone: () -> Unit
) {
    val context = LocalContext.current
    var pendingAction by remember { mutableStateOf<PendingBlePermissionAction?>(null) }
    var permissionDeniedNote by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.values.isNotEmpty() && grants.values.all { it }
        val queued = pendingAction
        pendingAction = null
        if (allGranted) {
            permissionDeniedNote = null
            when (queued) {
                PendingBlePermissionAction.Scan -> actions.onStartScan()
                PendingBlePermissionAction.ReconnectSaved -> actions.onReconnectSaved()
                is PendingBlePermissionAction.Connect -> actions.onConnectDevice(queued.device)
                null -> Unit
            }
        } else {
            permissionDeniedNote = "Bluetooth permissions were denied. Allow Nearby Devices in Android Settings to connect to Meshtastic radios."
        }
    }

    fun runWithBlePermission(action: PendingBlePermissionAction) {
        if (MeshtasticBleRepository.hasBlePermissions(context)) {
            permissionDeniedNote = null
            when (action) {
                PendingBlePermissionAction.Scan -> actions.onStartScan()
                PendingBlePermissionAction.ReconnectSaved -> actions.onReconnectSaved()
                is PendingBlePermissionAction.Connect -> actions.onConnectDevice(action.device)
            }
        } else {
            pendingAction = action
            permissionLauncher.launch(MeshtasticBleRepository.requiredBlePermissions())
        }
    }

    val statusAccent = when (bleState.status) {
        BleConnectionStatus.CONNECTED -> NodeDirectGreen
        BleConnectionStatus.SYNCING, BleConnectionStatus.CONNECTING, BleConnectionStatus.SCANNING -> NodeMultiHopSky
        BleConnectionStatus.RECONNECTING -> NodeSelfAmber
        BleConnectionStatus.OFFLINE -> TextSecondary
    }
    val statusIcon = when (bleState.status) {
        BleConnectionStatus.CONNECTED -> Icons.Default.BluetoothConnected
        BleConnectionStatus.SYNCING,
        BleConnectionStatus.CONNECTING,
        BleConnectionStatus.RECONNECTING,
        BleConnectionStatus.SCANNING -> Icons.Default.BluetoothSearching
        BleConnectionStatus.OFFLINE -> Icons.Default.Bluetooth
    }
    val activeShortName = bleState.connectedShortName
        ?.takeIf { it.isNotBlank() }
        ?: myRadioNode?.displayShortName?.takeIf { bleState.isActiveOrConnected && it.isNotBlank() }
    val statusHeadline = when (bleState.status) {
        BleConnectionStatus.CONNECTED -> if (!activeShortName.isNullOrBlank()) {
            "BLE: $activeShortName"
        } else {
            bleState.connectedDeviceName ?: "Connected"
        }
        BleConnectionStatus.SYNCING -> "Syncing NodeDB…"
        BleConnectionStatus.CONNECTING -> "Connecting to ${bleState.connectedDeviceName ?: "radio"}…"
        BleConnectionStatus.RECONNECTING -> if (bleState.reconnectAttempt > 0) {
            "Reconnecting (attempt ${bleState.reconnectAttempt}/${MeshtasticBleRepository.RECONNECT_BACKOFF_MS.size})…"
        } else {
            "Reconnecting to ${bleState.connectedDeviceName ?: "saved radio"}…"
        }
        BleConnectionStatus.SCANNING -> "Scanning for Meshtastic radios…"
        BleConnectionStatus.OFFLINE -> "No Bluetooth radio connected"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ble_sync_section"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Active BLE Status & Live Telemetry Counter Card
        Surface(
            color = TacticalSurfaceElevated,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, statusAccent.copy(alpha = 0.65f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(statusAccent.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = statusIcon,
                                contentDescription = null,
                                tint = statusAccent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = statusHeadline,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = if (bleState.status == BleConnectionStatus.OFFLINE) TextPrimary else statusAccent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = bleState.connectedAddress?.let { addr ->
                                    "${bleState.connectedDeviceName ?: "Meshtastic"} • $addr"
                                } ?: "Direct PhoneAPI GATT (MTU 512)",
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 10.sp,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (bleState.isActiveOrConnected) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = actions.onDisconnect,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AlertErrorRed,
                                contentColor = TacticalCanvas
                            ),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("ble_disconnect_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.LinkOff,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.live_sync_disconnect),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                // Live Telemetry Counter Strip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(TacticalSurface)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .testTag("ble_telemetry_counters"),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    BleTelemetryCounterCell(
                        label = "Nodes synced",
                        value = bleState.nodesSyncedCount.toString(),
                        accent = NodeDirectGreen,
                        modifier = Modifier.weight(1f)
                    )
                    BleTelemetryCounterCell(
                        label = "Packets received",
                        value = bleState.packetsReceivedCount.toString(),
                        accent = NodeMultiHopSky,
                        modifier = Modifier.weight(1f)
                    )
                    val ageText = bleState.lastPacketEpochSeconds?.let {
                        MeshGeoUtils.formatRelativeTime(it)
                    } ?: "—"
                    BleTelemetryCounterCell(
                        label = "Last packet age",
                        value = ageText,
                        accent = TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Center on My Radio action when connected
                if (bleState.status == BleConnectionStatus.CONNECTED || bleState.status == BleConnectionStatus.SYNCING) {
                    val hasGps = myRadioNode?.hasValidGps == true
                    OutlinedButton(
                        onClick = {
                            actions.onCenterOnMyRadio()
                            onCenterDone()
                        },
                        enabled = hasGps,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            1.dp,
                            if (hasGps) NodeDirectGreen.copy(alpha = 0.75f) else TacticalOutline
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (hasGps) NodeDirectGreen.copy(alpha = 0.12f) else TacticalSurface,
                            contentColor = if (hasGps) NodeDirectGreen else TextMuted,
                            disabledContentColor = TextMuted
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("ble_center_my_radio_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.MyLocation,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (hasGps) {
                                stringResource(R.string.ble_center_on_my_radio)
                            } else {
                                "Center on My Radio (awaiting GPS fix)"
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Error or permission warning banners
        val activeError = permissionDeniedNote ?: bleState.lastError
        if (activeError != null) {
            RfFloatingErrorBanner(
                message = activeError,
                onDismiss = {
                    permissionDeniedNote = null
                    actions.onDismissError()
                }
            )
        }

        // Saved Radio Quick-Connect Card (shown when offline / timed out after launch auto-connect)
        val savedRadio = bleState.savedRadio
        if (savedRadio != null && !bleState.isActiveOrConnected) {
            Surface(
                color = TacticalSurfaceElevated,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(
                    1.dp,
                    if (bleState.autoConnectTimedOut) NodeSelfAmber.copy(alpha = 0.65f) else TacticalOutline
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ble_saved_radio_card")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "REMEMBERED RADIO",
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = NodeMultiHopSky
                            )
                            Text(
                                text = buildString {
                                    append(savedRadio.name)
                                    savedRadio.shortName?.takeIf { it.isNotBlank() }?.let {
                                        append(" ($it)")
                                    }
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = savedRadio.address,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                        IconButton(
                            onClick = actions.onForgetSaved,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("ble_forget_saved_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = stringResource(R.string.ble_forget_saved),
                                tint = TextSecondary
                            )
                        }
                    }

                    if (bleState.autoConnectTimedOut) {
                        Text(
                            text = "Radio wasn't in range during launch auto-connect. Tap Reconnect once powered on nearby.",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color = NodeSelfAmber
                        )
                    }

                    Button(
                        onClick = { runWithBlePermission(PendingBlePermissionAction.ReconnectSaved) },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NodeDirectGreen,
                            contentColor = TacticalCanvas
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("ble_reconnect_saved_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.ble_reconnect_saved),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Scan for Nodes primary button
        Button(
            onClick = {
                if (bleState.isScanning) {
                    actions.onStopScan()
                } else {
                    runWithBlePermission(PendingBlePermissionAction.Scan)
                }
            },
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (bleState.isScanning) TacticalSurfaceElevated else NodeMultiHopSky,
                contentColor = if (bleState.isScanning) NodeMultiHopSky else TacticalCanvas
            ),
            border = if (bleState.isScanning) BorderStroke(1.dp, NodeMultiHopSky.copy(alpha = 0.7f)) else null,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("ble_scan_button")
        ) {
            if (bleState.isScanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = NodeMultiHopSky
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.ble_stop_scan),
                    fontWeight = FontWeight.Bold
                )
            } else {
                Icon(
                    imageVector = Icons.Default.BluetoothSearching,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.ble_scan_nodes),
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Discovered Meshtastic BLE radios list
        if (bleState.discoveredDevices.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ble_discovered_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "NEARBY MESHTASTIC RADIOS (${bleState.discoveredDevices.size})",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary
                )
                bleState.discoveredDevices.forEach { device ->
                    val isThisConnected = bleState.isActiveOrConnected && bleState.connectedAddress == device.address
                    BleDiscoveredRadioRow(
                        device = device,
                        isConnected = isThisConnected,
                        onConnectClick = {
                            if (isThisConnected) {
                                actions.onDisconnect()
                            } else {
                                runWithBlePermission(PendingBlePermissionAction.Connect(device))
                            }
                        }
                    )
                }
            }
        } else if (!bleState.isScanning && !bleState.isActiveOrConnected) {
            Text(
                text = "Tap \"Scan for Nodes\" to discover nearby Meshtastic radios advertising BLE service 6ba1b218.",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = TextMuted
            )
        }

        Text(
            text = "Non-destructive BLE sync: downloads your radio's NodeDB and streams live positions, SNR/RSSI, battery, and environment metrics while preserving custom site names, notes, AGL overrides, and manual sites.",
            style = MaterialTheme.typography.bodySmall,
            fontSize = 11.sp,
            color = TextMuted
        )
    }
}

@Composable
private fun BleTelemetryCounterCell(
    label: String,
    value: String,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = value,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BleDiscoveredRadioRow(
    device: DiscoveredMeshtasticDevice,
    isConnected: Boolean,
    onConnectClick: () -> Unit
) {
    Surface(
        color = TacticalSurfaceElevated,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            1.dp,
            if (isConnected) NodeDirectGreen.copy(alpha = 0.7f) else TacticalOutline
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ble_device_row_${device.address}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                BleSignalBarsIndicator(
                    bars = device.signalBars,
                    rssiDbm = device.rssiDbm
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${device.address} • ${device.rssiDbm} dBm",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 10.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onConnectClick,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isConnected) AlertErrorRed else NodeDirectGreen,
                    contentColor = TacticalCanvas
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("ble_connect_${device.address}")
            ) {
                Text(
                    text = if (isConnected) {
                        stringResource(R.string.live_sync_disconnect)
                    } else {
                        stringResource(R.string.live_sync_connect)
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
internal fun BleSignalBarsIndicator(
    bars: Int,
    rssiDbm: Int,
    modifier: Modifier = Modifier
) {
    val activeColor = when {
        bars >= 3 -> NodeDirectGreen
        bars == 2 -> NodeSelfAmber
        else -> AlertErrorRed
    }
    Row(
        modifier = modifier
            .semantics { contentDescription = "Bluetooth signal $bars of 4 bars, $rssiDbm dBm" },
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        for (level in 1..4) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height((5 + level * 4).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (level <= bars) activeColor else TacticalOutline.copy(alpha = 0.6f)
                    )
            )
        }
    }
}
