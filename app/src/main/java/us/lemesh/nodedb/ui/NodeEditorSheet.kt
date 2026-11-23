package us.lemesh.nodedb.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Restore
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import us.lemesh.nodedb.R
import us.lemesh.nodedb.data.NodeColorPalette
import us.lemesh.nodedb.ui.theme.AlertErrorRed
import us.lemesh.nodedb.ui.theme.JetBrainsMonoFontFamily
import us.lemesh.nodedb.ui.theme.NodeDirectGreen
import us.lemesh.nodedb.ui.theme.NodeFavoritePurple
import us.lemesh.nodedb.ui.theme.NodeMqttSlate
import us.lemesh.nodedb.ui.theme.NodeMultiHopSky
import us.lemesh.nodedb.ui.theme.NodeSelfAmber
import us.lemesh.nodedb.ui.theme.TacticalCanvas
import us.lemesh.nodedb.ui.theme.TacticalOutline
import us.lemesh.nodedb.ui.theme.TacticalSurface
import us.lemesh.nodedb.ui.theme.TacticalSurfaceElevated
import us.lemesh.nodedb.ui.theme.TextMuted
import us.lemesh.nodedb.ui.theme.TextPrimary
import us.lemesh.nodedb.ui.theme.TextSecondary
import java.util.Locale

private val HW_PRESETS = listOf("HELTEC_V3", "TBEAM", "RAK4631", "STATION_G2", "T_ECHO")
private val ROLE_PRESETS = listOf("CLIENT", "ROUTER", "REPEATER", "TRACKER")

@Composable
fun AddNodeModeBanner(
    isPickingForExistingDraft: Boolean,
    onUseMapCenter: () -> Unit,
    onOpenManualForm: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = TacticalSurface.copy(alpha = 0.96f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.2.dp, NodeDirectGreen.copy(alpha = 0.75f)),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .testTag("add_node_mode_banner")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
                        imageVector = Icons.Default.AddLocationAlt,
                        contentDescription = null,
                        tint = NodeDirectGreen,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.add_node_banner_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = NodeDirectGreen
                        )
                        Text(
                            text = if (isPickingForExistingDraft) {
                                stringResource(R.string.add_node_pick_hint)
                            } else {
                                stringResource(R.string.add_node_banner_hint)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
                OutlinedButton(
                    onClick = onDone,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    border = BorderStroke(1.dp, TacticalOutline),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("add_node_done_button")
                ) {
                    Text(
                        text = "Done",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextPrimary
                    )
                }
            }

            if (!isPickingForExistingDraft) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onUseMapCenter,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NodeDirectGreen,
                            contentColor = TacticalCanvas
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("add_node_use_center_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.MyLocation,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.add_node_use_center),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    OutlinedButton(
                        onClick = onOpenManualForm,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        border = BorderStroke(1.dp, NodeMultiHopSky.copy(alpha = 0.7f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = NodeMultiHopSky
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("add_node_manual_form_button")
                    ) {
                        Text(
                            text = stringResource(R.string.add_node_enter_manual),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NodeEditorBottomSheet(
    initialDraft: NodeEditorDraft,
    onSave: (NodeEditorDraft) -> String?,
    onPickOnMap: (NodeEditorDraft) -> Unit,
    onDeleteManual: (() -> Unit)? = null,
    onRevertEdits: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember(initialDraft.originalKey, initialDraft.isCreatingNew, initialDraft.latitudeText, initialDraft.longitudeText) {
        mutableStateOf(initialDraft)
    }
    var validationError by remember(initialDraft.originalKey, initialDraft.isCreatingNew) {
        mutableStateOf<String?>(null)
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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AddLocationAlt,
                        contentDescription = null,
                        tint = NodeDirectGreen,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = if (draft.isCreatingNew) {
                            stringResource(R.string.node_editor_add_title)
                        } else {
                            stringResource(R.string.node_editor_edit_title)
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("node_editor_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_cancel),
                        tint = TextSecondary
                    )
                }
            }

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
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Palette,
                                contentDescription = null,
                                tint = draft.customColorArgb?.let { Color(it) } ?: NodeMultiHopSky,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = stringResource(R.string.node_editor_color_label),
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary
                            )
                        }
                        Text(
                            text = NodeColorPalette.colorName(draft.customColorArgb) ?: "Auto (By Link Type)",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 11.sp,
                            color = draft.customColorArgb?.let { Color(it) } ?: TextSecondary
                        )
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = draft.customColorArgb == null,
                            onClick = { draft = draft.copy(customColorArgb = null) },
                            label = {
                                Text(
                                    text = stringResource(R.string.node_editor_color_auto),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = TacticalCanvas,
                                labelColor = TextSecondary,
                                selectedContainerColor = NodeMultiHopSky.copy(alpha = 0.22f),
                                selectedLabelColor = NodeMultiHopSky
                            ),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("color_swatch_auto")
                        )

                        NodeColorPalette.PRESETS.forEach { option ->
                            val isSelected = draft.customColorArgb != null &&
                                (draft.customColorArgb!! and 0xFFFFFF) == (option.argb and 0xFFFFFF)
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .clickable { draft = draft.copy(customColorArgb = option.argb) }
                                    .testTag("color_swatch_${option.name.lowercase(Locale.US)}")
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(option.color)
                                        .border(
                                            width = if (isSelected) 2.5.dp else 1.dp,
                                            color = if (isSelected) TextPrimary else TacticalOutline,
                                            shape = CircleShape
                                        )
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = option.name,
                                            tint = TacticalCanvas,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EditorTextField(
                    value = draft.longName,
                    onValueChange = { draft = draft.copy(longName = it) },
                    label = "Long Name",
                    modifier = Modifier
                        .weight(1.5f)
                        .testTag("editor_long_name_input")
                )
                EditorTextField(
                    value = draft.shortName,
                    onValueChange = { draft = draft.copy(shortName = it.take(5)) },
                    label = "Short",
                    modifier = Modifier
                        .weight(0.75f)
                        .testTag("editor_short_name_input")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EditorTextField(
                    value = draft.id,
                    onValueChange = { draft = draft.copy(id = it) },
                    label = "Node ID (!hex)",
                    modifier = Modifier
                        .weight(1.2f)
                        .testTag("editor_id_input")
                )
                EditorTextField(
                    value = draft.altitudeText,
                    onValueChange = { draft = draft.copy(altitudeText = it) },
                    label = "Alt (m)",
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier
                        .weight(0.8f)
                        .testTag("editor_altitude_input")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                EditorTextField(
                    value = draft.latitudeText,
                    onValueChange = { draft = draft.copy(latitudeText = it) },
                    label = "Latitude",
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("editor_lat_input")
                )
                EditorTextField(
                    value = draft.longitudeText,
                    onValueChange = { draft = draft.copy(longitudeText = it) },
                    label = "Longitude",
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("editor_lon_input")
                )
                OutlinedButton(
                    onClick = { onPickOnMap(draft) },
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                    border = BorderStroke(1.dp, NodeMultiHopSky.copy(alpha = 0.7f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NodeMultiHopSky),
                    modifier = Modifier
                        .heightIn(min = 52.dp)
                        .testTag("editor_pick_map_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.AddLocationAlt,
                        contentDescription = stringResource(R.string.node_editor_pick_on_map),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            EditorTextField(
                value = draft.hwModelText,
                onValueChange = { draft = draft.copy(hwModelText = it) },
                label = "Hardware Model",
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("editor_hw_model_input")
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                HW_PRESETS.forEach { preset ->
                    FilterChip(
                        selected = draft.hwModelText.equals(preset, ignoreCase = true),
                        onClick = { draft = draft.copy(hwModelText = preset) },
                        label = {
                            Text(
                                text = preset,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 11.sp
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = TacticalSurfaceElevated,
                            labelColor = TextSecondary,
                            selectedContainerColor = NodeDirectGreen.copy(alpha = 0.2f),
                            selectedLabelColor = NodeDirectGreen
                        ),
                        modifier = Modifier.heightIn(min = 36.dp)
                    )
                }
            }

            EditorTextField(
                value = draft.roleText,
                onValueChange = { draft = draft.copy(roleText = it) },
                label = "Node Role",
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("editor_role_input")
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ROLE_PRESETS.forEach { role ->
                    FilterChip(
                        selected = draft.roleText.equals(role, ignoreCase = true),
                        onClick = { draft = draft.copy(roleText = role) },
                        label = {
                            Text(
                                text = role,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 11.sp
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = TacticalSurfaceElevated,
                            labelColor = TextSecondary,
                            selectedContainerColor = NodeMultiHopSky.copy(alpha = 0.2f),
                            selectedLabelColor = NodeMultiHopSky
                        ),
                        modifier = Modifier.heightIn(min = 36.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EditorTextField(
                    value = draft.hopsAwayText,
                    onValueChange = { draft = draft.copy(hopsAwayText = it) },
                    label = "Hops",
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("editor_hops_input")
                )
                EditorTextField(
                    value = draft.snrText,
                    onValueChange = { draft = draft.copy(snrText = it) },
                    label = "SNR (dB)",
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("editor_snr_input")
                )
                EditorTextField(
                    value = draft.rssiText,
                    onValueChange = { draft = draft.copy(rssiText = it) },
                    label = "RSSI (dBm)",
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("editor_rssi_input")
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = draft.isSelf,
                    onClick = { draft = draft.copy(isSelf = !draft.isSelf) },
                    label = { Text("Self Node") },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = TacticalSurfaceElevated,
                        labelColor = TextSecondary,
                        selectedContainerColor = NodeSelfAmber.copy(alpha = 0.24f),
                        selectedLabelColor = NodeSelfAmber
                    ),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("editor_toggle_self")
                )
                FilterChip(
                    selected = draft.isFavorite,
                    onClick = { draft = draft.copy(isFavorite = !draft.isFavorite) },
                    label = { Text("Favorite") },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = TacticalSurfaceElevated,
                        labelColor = TextSecondary,
                        selectedContainerColor = NodeFavoritePurple.copy(alpha = 0.24f),
                        selectedLabelColor = NodeFavoritePurple
                    ),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("editor_toggle_favorite")
                )
                FilterChip(
                    selected = draft.viaMqtt,
                    onClick = { draft = draft.copy(viaMqtt = !draft.viaMqtt) },
                    label = { Text("Via MQTT") },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = TacticalSurfaceElevated,
                        labelColor = TextSecondary,
                        selectedContainerColor = NodeMqttSlate.copy(alpha = 0.28f),
                        selectedLabelColor = TextPrimary
                    ),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("editor_toggle_mqtt")
                )
            }

            if (draft.isCreatingNew || draft.isManual) {
                Surface(
                    color = TacticalSurfaceElevated,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        1.dp,
                        if (draft.maskLocationOnExport) NodeSelfAmber.copy(alpha = 0.65f) else TacticalOutline
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
                                text = stringResource(R.string.node_editor_mask_location_title),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Text(
                                text = stringResource(R.string.node_editor_mask_location_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = draft.maskLocationOnExport,
                            onCheckedChange = { draft = draft.copy(maskLocationOnExport = it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = TacticalCanvas,
                                checkedTrackColor = NodeSelfAmber,
                                uncheckedThumbColor = TextSecondary,
                                uncheckedTrackColor = TacticalSurface
                            ),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("node_editor_mask_location_switch")
                        )
                    }
                }
            }

            EditorTextField(
                value = draft.notesText,
                onValueChange = { draft = draft.copy(notesText = it) },
                label = stringResource(R.string.node_editor_notes_label),
                singleLine = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("editor_notes_input")
            )

            validationError?.let { err ->
                Surface(
                    color = AlertErrorRed.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, AlertErrorRed.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = AlertErrorRed,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (onDeleteManual != null) {
                    OutlinedButton(
                        onClick = onDeleteManual,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, AlertErrorRed.copy(alpha = 0.7f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AlertErrorRed),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("delete_manual_node_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.node_editor_delete))
                    }
                } else if (onRevertEdits != null) {
                    OutlinedButton(
                        onClick = onRevertEdits,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, NodeSelfAmber.copy(alpha = 0.7f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NodeSelfAmber),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("revert_node_edits_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Restore,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.node_editor_revert))
                    }
                }

                Button(
                    onClick = {
                        val err = onSave(draft)
                        validationError = err
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NodeDirectGreen,
                        contentColor = TacticalCanvas
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("save_node_button")
                ) {
                    Text(
                        text = stringResource(R.string.node_editor_save),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
        },
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else 3,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = JetBrainsMonoFontFamily,
            color = TextPrimary
        ),
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = TacticalSurfaceElevated,
            unfocusedContainerColor = TacticalSurfaceElevated,
            focusedBorderColor = NodeDirectGreen,
            unfocusedBorderColor = TacticalOutline,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary
        ),
        modifier = modifier
    )
}
