package us.lemesh.nodedb.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import us.lemesh.nodedb.R
import us.lemesh.nodedb.data.AlignmentTarget
import us.lemesh.nodedb.data.AntennaAlignmentMath
import us.lemesh.nodedb.data.CompassSensorTracker
import us.lemesh.nodedb.data.DeviceLocationResolver
import us.lemesh.nodedb.data.DeviceLocationResolver.DeviceLocationFix
import us.lemesh.nodedb.data.MeshGeoUtils
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
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Real-time True-North Antenna Alignment Compass HUD for rooftop Yagi/sector antenna aiming.
 * Uses Android TYPE_ROTATION_VECTOR + GeomagneticField magnetic declination and live GPS fix
 * (gracefully falling back to selfNode coordinates when GPS permission/fix is unavailable).
 */
@Composable
fun AntennaAlignmentHudOverlay(
    target: AlignmentTarget?,
    availableTargets: List<AlignmentTarget>,
    fallbackOriginNode: MeshNode?,
    onSelectTarget: (AlignmentTarget) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var magneticHeadingDeg by remember { mutableStateOf<Double?>(null) }
    var sensorAccuracyLow by remember { mutableStateOf(false) }
    var hasCompassSensor by remember { mutableStateOf(true) }
    var deviceFix by remember { mutableStateOf<DeviceLocationFix?>(null) }
    var declinationDeg by remember { mutableDoubleStateOf(0.0) }

    DisposableEffect(context) {
        val tracker = CompassSensorTracker(context) { heading, accLow ->
            magneticHeadingDeg = heading
            sensorAccuracyLow = accLow
        }
        hasCompassSensor = tracker.hasSensor
        tracker.start()
        onDispose {
            tracker.stop()
        }
    }

    LaunchedEffect(context) {
        while (true) {
            if (DeviceLocationResolver.hasAnyLocationPermission(context)) {
                val fix = DeviceLocationResolver.fetchDeviceLocationWithAltitude(context)
                if (fix != null) {
                    deviceFix = fix
                }
            }
            delay(5_000L)
        }
    }

    val originLat = deviceFix?.latitude ?: fallbackOriginNode?.latitude
    val originLon = deviceFix?.longitude ?: fallbackOriginNode?.longitude
    val originElevM = deviceFix?.altitudeMeters ?: fallbackOriginNode?.altitude?.toDouble()
    val usingGpsFix = deviceFix != null

    LaunchedEffect(originLat, originLon, originElevM) {
        if (originLat != null && originLon != null) {
            declinationDeg = CompassSensorTracker.magneticDeclinationDegrees(
                lat = originLat,
                lon = originLon,
                altitudeMeters = originElevM ?: 200.0
            )
        }
    }

    val trueHeadingDeg = magneticHeadingDeg?.let {
        AntennaAlignmentMath.applyMagneticDeclination(it, declinationDeg)
    }

    val targetAzimuthDeg = if (originLat != null && originLon != null && target != null) {
        AntennaAlignmentMath.computeTargetAzimuthDeg(originLat, originLon, target.latitude, target.longitude)
    } else null

    val distanceKm = if (originLat != null && originLon != null && target != null) {
        MeshGeoUtils.haversineKm(originLat, originLon, target.latitude, target.longitude)
    } else null

    val relativeTurnDeg = if (trueHeadingDeg != null && targetAzimuthDeg != null) {
        AntennaAlignmentMath.computeRelativeTurnDeg(trueHeadingDeg, targetAzimuthDeg)
    } else null

    val verticalTiltDeg = if (distanceKm != null && originElevM != null && target?.elevationMeters != null) {
        AntennaAlignmentMath.computeVerticalElevationAngleDeg(
            distanceKm = distanceKm,
            originElevationMeters = originElevM,
            targetElevationMeters = target.elevationMeters
        )
    } else null

    val isOnTarget = relativeTurnDeg != null && abs(relativeTurnDeg) <= 5.0
    val aimAccent = when {
        target == null -> NodeMultiHopSky
        isOnTarget -> NodeDirectGreen
        relativeTurnDeg != null && abs(relativeTurnDeg) <= 20.0 -> NodeSelfAmber
        else -> NodeMultiHopSky
    }

    Surface(
        color = TacticalSurface.copy(alpha = 0.96f),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.5.dp, aimAccent.copy(alpha = 0.78f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("antenna_alignment_hud")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Explore,
                        contentDescription = null,
                        tint = aimAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.align_hud_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        Text(
                            text = target?.let { "${it.label} • ${it.subtitle}" }
                                ?: stringResource(R.string.align_hud_no_target),
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 10.sp,
                            color = if (target != null) aimAccent else TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("antenna_alignment_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close antenna alignment HUD",
                        tint = TextSecondary
                    )
                }
            }

            // Target selector strip
            if (availableTargets.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    availableTargets.forEach { candidate ->
                        val selected = target?.id == candidate.id
                        FilterChip(
                            selected = selected,
                            onClick = { onSelectTarget(candidate) },
                            label = {
                                Text(
                                    text = candidate.label,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 10.sp,
                                    maxLines = 1
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = TacticalSurfaceElevated,
                                labelColor = TextSecondary,
                                selectedContainerColor = aimAccent.copy(alpha = 0.22f),
                                selectedLabelColor = aimAccent
                            ),
                            modifier = Modifier.testTag("align_target_chip_${candidate.id}")
                        )
                    }
                }
            }

            // Compass dial + telemetry readouts
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(136.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(TacticalCanvas)
                        .border(1.dp, TacticalOutline, RoundedCornerShape(14.dp))
                        .padding(6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CompassDialCanvas(
                        trueHeadingDeg = trueHeadingDeg,
                        targetAzimuthDeg = targetAzimuthDeg,
                        accentColor = aimAccent,
                        modifier = Modifier.fillMaxSize()
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = trueHeadingDeg?.let {
                                String.format(Locale.US, "%03.0f°", it)
                            } ?: "---°",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = TextPrimary
                        )
                        Text(
                            text = "TRUE",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 8.sp,
                            color = TextMuted
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Turn cue banner
                    val cueText = when {
                        target == null -> "Pick a target above"
                        originLat == null -> "Waiting for GPS/Self fix"
                        !hasCompassSensor -> "Compass sensor unavailable"
                        relativeTurnDeg == null -> "Move phone in figure-8"
                        isOnTarget -> "ON TARGET ✓"
                        relativeTurnDeg > 0 -> String.format(Locale.US, "TURN RIGHT %.0f°", relativeTurnDeg)
                        else -> String.format(Locale.US, "TURN LEFT %.0f°", abs(relativeTurnDeg))
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(aimAccent.copy(alpha = 0.16f))
                            .border(1.dp, aimAccent.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("antenna_alignment_turn_cue")
                    ) {
                        Text(
                            text = cueText,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = aimAccent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AlignmentMetricTile(
                            label = "Target Azimuth",
                            value = targetAzimuthDeg?.let {
                                String.format(
                                    Locale.US,
                                    "%03.0f° %s",
                                    it,
                                    MeshGeoUtils.formatCardinalDirection(it)
                                )
                            } ?: "--",
                            valueColor = aimAccent,
                            modifier = Modifier.weight(1f)
                        )
                        AlignmentMetricTile(
                            label = "Elevation Tilt",
                            value = verticalTiltDeg?.let {
                                String.format(Locale.US, "%+.1f°", it)
                            } ?: "N/A",
                            valueColor = NodeMultiHopSky,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AlignmentMetricTile(
                            label = "Distance",
                            value = distanceKm?.let {
                                String.format(Locale.US, "%.2f mi / %.1f km", MeshGeoUtils.kmToMiles(it), it)
                            } ?: "--",
                            valueColor = TextPrimary,
                            modifier = Modifier.weight(1.25f)
                        )
                        AlignmentMetricTile(
                            label = "Declination",
                            value = String.format(Locale.US, "%+.1f°", declinationDeg),
                            valueColor = TextSecondary,
                            modifier = Modifier.weight(0.75f)
                        )
                    }
                }
            }

            // Sensor/GPS provenance footer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (sensorAccuracyLow || !hasCompassSensor) Icons.Default.Warning else Icons.Default.GpsFixed,
                        contentDescription = null,
                        tint = if (sensorAccuracyLow || !hasCompassSensor) NodeSelfAmber else NodeDirectGreen,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = buildString {
                            append(if (usingGpsFix) "Origin: Live GPS" else if (originLat != null) "Origin: Self Node" else "Origin: No GPS")
                            if (sensorAccuracyLow) append(" • Low mag accuracy (calibrate)")
                        },
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 10.sp,
                        color = if (sensorAccuracyLow) NodeSelfAmber else TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun AlignmentMetricTile(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(TacticalSurfaceElevated)
            .border(1.dp, TacticalOutline, RoundedCornerShape(8.dp))
            .padding(horizontal = 7.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            color = TextMuted,
            maxLines = 1
        )
        Text(
            text = value,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CompassDialCanvas(
    trueHeadingDeg: Double?,
    targetAzimuthDeg: Double?,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val radius = min(size.width, size.height) * 0.45f
        val center = Offset(size.width / 2f, size.height / 2f)

        // Outer compass bezel ring
        drawCircle(
            color = Color(0xFF334155),
            radius = radius,
            center = center,
            style = Stroke(width = 2.dp.toPx())
        )

        val headingOffset = trueHeadingDeg ?: 0.0

        // Tick marks rotated relative to device True Heading (heading-up display)
        for (deg in 0 until 360 step 15) {
            val relRad = Math.toRadians(deg - headingOffset - 90.0)
            val isCardinal = deg % 90 == 0
            val innerFrac = if (isCardinal) 0.80f else 0.88f
            val start = Offset(
                x = center.x + (radius * innerFrac) * cos(relRad).toFloat(),
                y = center.y + (radius * innerFrac) * sin(relRad).toFloat()
            )
            val end = Offset(
                x = center.x + radius * cos(relRad).toFloat(),
                y = center.y + radius * sin(relRad).toFloat()
            )
            drawLine(
                color = if (deg == 0) AlertErrorRed else if (isCardinal) Color(0xFFE2E8F0) else Color(0xFF475569),
                start = start,
                end = end,
                strokeWidth = if (deg == 0) 2.5.dp.toPx() else if (isCardinal) 1.8.dp.toPx() else 1.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        // Fixed top lubber triangle (phone pointing direction)
        val lubberPath = Path().apply {
            moveTo(center.x, center.y - radius - 4.dp.toPx())
            lineTo(center.x - 5.dp.toPx(), center.y - radius + 5.dp.toPx())
            lineTo(center.x + 5.dp.toPx(), center.y - radius + 5.dp.toPx())
            close()
        }
        drawPath(lubberPath, color = Color(0xFFF8FAFC))

        // Target bearing vector arrow
        if (targetAzimuthDeg != null) {
            val relTargetRad = Math.toRadians(targetAzimuthDeg - headingOffset - 90.0)
            val tip = Offset(
                x = center.x + (radius * 0.90f) * cos(relTargetRad).toFloat(),
                y = center.y + (radius * 0.90f) * sin(relTargetRad).toFloat()
            )
            val innerTail = Offset(
                x = center.x + (radius * 0.56f) * cos(relTargetRad).toFloat(),
                y = center.y + (radius * 0.56f) * sin(relTargetRad).toFloat()
            )
            drawLine(
                color = accentColor,
                start = innerTail,
                end = tip,
                strokeWidth = 3.5.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawCircle(
                color = accentColor,
                radius = 4.5.dp.toPx(),
                center = tip
            )
        }
    }
}
