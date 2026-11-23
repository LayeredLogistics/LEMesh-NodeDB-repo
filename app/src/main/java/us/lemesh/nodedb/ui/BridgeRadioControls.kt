package us.lemesh.nodedb.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import us.lemesh.nodedb.R
import us.lemesh.nodedb.ui.theme.JetBrainsMonoFontFamily
import us.lemesh.nodedb.ui.theme.NodeDirectGreen
import us.lemesh.nodedb.ui.theme.TacticalOutline
import us.lemesh.nodedb.ui.theme.TacticalSurface
import us.lemesh.nodedb.ui.theme.TacticalSurfaceElevated
import us.lemesh.nodedb.ui.theme.TextPrimary
import us.lemesh.nodedb.ui.theme.TextSecondary
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Inline expandable RF radio hardware section embedded directly inside Bridge TX / RX site cards.
 */
@Composable
fun BridgeEndpointRadioAccordion(
    roleLabel: String,
    accentColor: Color,
    powerDbm: Float,
    antennaGainDbi: Float,
    sensitivityDbm: Float,
    tagPrefix: String,
    onPowerChange: (Float) -> Unit,
    onAntennaGainChange: (Float) -> Unit,
    onSensitivityChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.SettingsInputAntenna,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = String.format(
                        Locale.US,
                        "%d dBm • %.1f dBi • %d dBm",
                        powerDbm.roundToInt(),
                        antennaGainDbi,
                        sensitivityDbm.roundToInt()
                    ),
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 10.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("${tagPrefix}_rf_summary")
                )
            }

            OutlinedButton(
                onClick = { expanded = !expanded },
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                border = BorderStroke(1.dp, if (expanded) accentColor else TacticalOutline),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("${tagPrefix}_rf_toggle")
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = "$roleLabel radio parameters",
                    tint = accentColor,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (expanded) "Hide" else stringResource(R.string.bridge_radio_button),
                    style = MaterialTheme.typography.labelMedium,
                    color = accentColor
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TacticalSurface, RoundedCornerShape(8.dp))
                    .border(1.dp, accentColor.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                RfLabeledSlider(
                    label = "$roleLabel Ant Gain",
                    valueText = String.format(Locale.US, "%.1f dBi", antennaGainDbi),
                    value = antennaGainDbi,
                    valueRange = 0f..15f,
                    accentColor = accentColor,
                    sliderTestTag = "${tagPrefix}_gain_slider",
                    onValueChange = onAntennaGainChange
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        RfLabeledSlider(
                            label = "$roleLabel Power",
                            valueText = "${powerDbm.roundToInt()} dBm",
                            value = powerDbm,
                            valueRange = 10f..37f,
                            accentColor = accentColor,
                            sliderTestTag = "${tagPrefix}_power_slider",
                            onValueChange = onPowerChange
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        RfLabeledSlider(
                            label = "$roleLabel Sens",
                            valueText = "${sensitivityDbm.roundToInt()} dBm",
                            value = sensitivityDbm,
                            valueRange = -140f..-100f,
                            accentColor = accentColor,
                            sliderTestTag = "${tagPrefix}_sensitivity_slider",
                            onValueChange = onSensitivityChange
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dedicated Relay Node Mast & RF Radio card placed in the Bridge drawer and Profile tab.
 */
@Composable
fun BridgeRelayRadioCard(
    relayHeightMeters: Float,
    relayPowerDbm: Float,
    relayAntennaGainDbi: Float,
    relaySensitivityDbm: Float,
    tagPrefix: String = "bridge_relay",
    onRelayHeightChange: (Float) -> Unit,
    onRelayPowerChange: (Float) -> Unit,
    onRelayAntennaGainChange: (Float) -> Unit,
    onRelaySensitivityChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var rfExpanded by rememberSaveable { mutableStateOf(false) }

    Surface(
        color = TacticalSurfaceElevated,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, NodeDirectGreen.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { rfExpanded = !rfExpanded }
                    .testTag("${tagPrefix}_rf_toggle"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.SettingsInputAntenna,
                        contentDescription = null,
                        tint = NodeDirectGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.bridge_relay_rf_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = NodeDirectGreen,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = String.format(
                                Locale.US,
                                "%dm AGL • %d dBm • %.1f dBi • %d dBm",
                                relayHeightMeters.roundToInt(),
                                relayPowerDbm.roundToInt(),
                                relayAntennaGainDbi,
                                relaySensitivityDbm.roundToInt()
                            ),
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 10.sp,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("${tagPrefix}_rf_summary")
                        )
                    }
                }
                Icon(
                    imageVector = if (rfExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Toggle relay radio parameters",
                    tint = TextSecondary
                )
            }

            RfLabeledSlider(
                label = "Relay Mast AGL",
                valueText = "${relayHeightMeters.roundToInt()} m",
                value = relayHeightMeters,
                valueRange = 1f..60f,
                accentColor = NodeDirectGreen,
                sliderTestTag = "${tagPrefix}_mast_slider",
                onValueChange = onRelayHeightChange
            )

            AnimatedVisibility(visible = rfExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TacticalSurface, RoundedCornerShape(8.dp))
                        .border(1.dp, NodeDirectGreen.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    RfLabeledSlider(
                        label = "Relay Ant Gain",
                        valueText = String.format(Locale.US, "%.1f dBi", relayAntennaGainDbi),
                        value = relayAntennaGainDbi,
                        valueRange = 0f..15f,
                        accentColor = NodeDirectGreen,
                        sliderTestTag = "${tagPrefix}_gain_slider",
                        onValueChange = onRelayAntennaGainChange
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            RfLabeledSlider(
                                label = "Relay Power",
                                valueText = "${relayPowerDbm.roundToInt()} dBm",
                                value = relayPowerDbm,
                                valueRange = 10f..37f,
                                accentColor = NodeDirectGreen,
                                sliderTestTag = "${tagPrefix}_power_slider",
                                onValueChange = onRelayPowerChange
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            RfLabeledSlider(
                                label = "Relay Sens",
                                valueText = "${relaySensitivityDbm.roundToInt()} dBm",
                                value = relaySensitivityDbm,
                                valueRange = -140f..-100f,
                                accentColor = NodeDirectGreen,
                                sliderTestTag = "${tagPrefix}_sensitivity_slider",
                                onValueChange = onRelaySensitivityChange
                            )
                        }
                    }
                }
            }
        }
    }
}
