package us.lemesh.nodedb.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import us.lemesh.nodedb.R
import us.lemesh.nodedb.data.BridgeCompositeProfile
import us.lemesh.nodedb.data.BridgeLinkSegment
import us.lemesh.nodedb.data.BridgeObstructionItem
import us.lemesh.nodedb.data.BridgeProfileMode
import us.lemesh.nodedb.data.BridgeProfileSample
import us.lemesh.nodedb.data.ViewshedPointStatus
import us.lemesh.nodedb.ui.theme.AlertErrorRed
import us.lemesh.nodedb.ui.theme.JetBrainsMonoFontFamily
import us.lemesh.nodedb.ui.theme.NodeDirectGreen
import us.lemesh.nodedb.ui.theme.NodeMultiHopSky
import us.lemesh.nodedb.ui.theme.NodeSelfAmber
import us.lemesh.nodedb.ui.theme.TacticalCanvas
import us.lemesh.nodedb.ui.theme.TacticalOutline
import us.lemesh.nodedb.ui.theme.TacticalSurfaceBright
import us.lemesh.nodedb.ui.theme.TacticalSurfaceElevated
import us.lemesh.nodedb.ui.theme.TextMuted
import us.lemesh.nodedb.ui.theme.TextPrimary
import us.lemesh.nodedb.ui.theme.TextSecondary
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class ProfileYBounds(
    val yMin: Double,
    val yMax: Double
) {
    val yRange: Double get() = max(yMax - yMin, 10.0)
}

internal fun computeProfileYBounds(samples: List<BridgeProfileSample>): ProfileYBounds {
    val minYRaw = samples.minOf {
        min(it.effectiveTerrainMeters, min(it.rayElevationMeters, it.fresnel60LowerMeters))
    }
    val maxYRaw = samples.maxOf {
        max(it.effectiveTerrainMeters, max(it.rayElevationMeters, it.fresnel60UpperMeters))
    }
    val span = max(maxYRaw - minYRaw, 20.0)
    val yMin = floor((minYRaw - span * 0.08) / 5.0) * 5.0
    val yMax = ceil((maxYRaw + span * 0.14) / 5.0) * 5.0
    return ProfileYBounds(yMin = yMin, yMax = max(yMax, yMin + 10.0))
}

internal fun distanceAlongCompositeProfileKm(
    profile: BridgeCompositeProfile,
    obs: BridgeObstructionItem
): Double = when (obs.segment) {
    BridgeLinkSegment.RELAY_TO_RX -> (profile.relayHingeDistanceKm ?: 0.0) + obs.point.distanceFromOriginKm
    else -> obs.point.distanceFromOriginKm
}

internal fun findNearestProfileSample(
    xPx: Float,
    leftPadPx: Float,
    plotWidthPx: Float,
    profile: BridgeCompositeProfile
): BridgeProfileSample? {
    val samples = profile.samples
    if (samples.isEmpty()) return null
    val totalDist = profile.totalDistanceKm.coerceAtLeast(0.01)
    val frac = ((xPx - leftPadPx) / plotWidthPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
    val targetDistKm = frac * totalDist
    return samples.minByOrNull { abs(it.distanceFromStartKm - targetDistKm) }
}

internal fun findNearestPeakHitId(
    tapX: Float,
    tapY: Float,
    leftPadPx: Float,
    topPadPx: Float,
    plotWidthPx: Float,
    plotHeightPx: Float,
    profile: BridgeCompositeProfile,
    selectedObstructionId: String?,
    hitRadiusPx: Float
): String? {
    val samples = profile.samples
    if (samples.size < 2 || plotWidthPx <= 1f || plotHeightPx <= 1f) return null
    val bounds = computeProfileYBounds(samples)
    val totalDist = profile.totalDistanceKm.coerceAtLeast(0.01)

    val interactivePeaks = profile.visibleObstructions.filter {
        it.point.status != ViewshedPointStatus.CLEAR || it.id == selectedObstructionId
    }
    var bestId: String? = null
    var bestDist = hitRadiusPx
    for (obs in interactivePeaks) {
        val distKm = distanceAlongCompositeProfileKm(profile, obs).coerceIn(0.0, totalDist)
        val px = leftPadPx + ((distKm / totalDist).toFloat() * plotWidthPx)
        val normY = ((obs.point.effectiveTerrainMeters - bounds.yMin) / bounds.yRange).toFloat().coerceIn(0f, 1f)
        val py = topPadPx + plotHeightPx * (1f - normY)
        val d = hypot(tapX - px, tapY - py)
        if (d <= bestDist) {
            bestDist = d
            bestId = obs.id
        }
    }
    return bestId
}

@Composable
fun BridgeTerrainProfileChart(
    profile: BridgeCompositeProfile?,
    selectedMode: BridgeProfileMode,
    hasRelayCandidate: Boolean,
    isLoading: Boolean,
    errorMessage: String?,
    selectedObstructionId: String?,
    scrubbedSample: BridgeProfileSample?,
    onSelectMode: (BridgeProfileMode) -> Unit,
    onSelectObstruction: (String) -> Unit,
    onScrubSample: (BridgeProfileSample?) -> Unit,
    onRetryLoading: () -> Unit,
    modifier: Modifier = Modifier,
    isOfflineTileMissing: Boolean = false,
    onOpenOfflineMaps: (() -> Unit)? = null
) {
    Surface(
        color = TacticalCanvas,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, TacticalOutline, RoundedCornerShape(12.dp))
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.bridge_profile_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ProfileModeChip(
                        label = stringResource(R.string.bridge_profile_mode_direct),
                        selected = selectedMode == BridgeProfileMode.DIRECT || !hasRelayCandidate,
                        enabled = true,
                        accentColor = NodeSelfAmber,
                        testTag = "bridge_profile_mode_direct",
                        onClick = { onSelectMode(BridgeProfileMode.DIRECT) }
                    )
                    ProfileModeChip(
                        label = stringResource(R.string.bridge_profile_mode_relay),
                        selected = selectedMode == BridgeProfileMode.RELAY && hasRelayCandidate,
                        enabled = hasRelayCandidate,
                        accentColor = NodeDirectGreen,
                        testTag = "bridge_profile_mode_relay",
                        onClick = { onSelectMode(BridgeProfileMode.RELAY) }
                    )
                }
            }

            if (isLoading && profile == null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(TacticalSurfaceElevated)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.bridge_profile_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = NodeMultiHopSky
                    )
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = NodeMultiHopSky,
                        trackColor = TacticalSurfaceBright
                    )
                }
            } else if (errorMessage != null && profile == null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(AlertErrorRed.copy(alpha = 0.14f))
                        .border(1.dp, AlertErrorRed.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onRetryLoading,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Text("Retry", style = MaterialTheme.typography.labelMedium)
                        }
                        if (isOfflineTileMissing && onOpenOfflineMaps != null) {
                            OutlinedButton(
                                onClick = onOpenOfflineMaps,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("open_offline_maps_from_error")
                            ) {
                                Text(
                                    text = stringResource(R.string.offline_missing_cta),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = NodeMultiHopSky
                                )
                            }
                        }
                    }
                }
            } else if (profile != null && profile.samples.size >= 2) {
                val activeObs = profile.visibleObstructions.firstOrNull { it.id == selectedObstructionId }

                // Telemetry / Legend Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (scrubbedSample != null) {
                        val scrubColor = when (scrubbedSample.status) {
                            ViewshedPointStatus.OBSTRUCTED -> AlertErrorRed
                            ViewshedPointStatus.GRAZING -> NodeSelfAmber
                            ViewshedPointStatus.CLEAR -> NodeMultiHopSky
                        }
                        Text(
                            text = String.format(
                                Locale.US,
                                "%.2f km • Terr %.0fm (+%.1fm) • Clr %+.1fm (%.0f%% F1)",
                                scrubbedSample.distanceFromStartKm,
                                scrubbedSample.terrainElevationMeters,
                                scrubbedSample.earthBulgeMeters,
                                scrubbedSample.clearanceMeters,
                                (scrubbedSample.fresnelRatio * 100.0).coerceIn(-999.0, 999.0)
                            ),
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = scrubColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("bridge_profile_scrub_readout")
                        )
                        IconButton(
                            onClick = { onScrubSample(null) },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("bridge_profile_clear_scrub")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear terrain probe",
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else if (activeObs != null) {
                        val obsDist = distanceAlongCompositeProfileKm(profile, activeObs)
                        val obsColor = when (activeObs.point.status) {
                            ViewshedPointStatus.OBSTRUCTED -> AlertErrorRed
                            ViewshedPointStatus.GRAZING -> NodeSelfAmber
                            ViewshedPointStatus.CLEAR -> NodeDirectGreen
                        }
                        Text(
                            text = String.format(
                                Locale.US,
                                "Peak @ %.2f km • Terr %.0fm • Clr %+.1fm • %.5f, %.5f",
                                obsDist,
                                activeObs.point.terrainElevationMeters,
                                activeObs.point.clearanceMeters,
                                activeObs.point.latitude,
                                activeObs.point.longitude
                            ),
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = obsColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ProfileLegendDot("Terrain (4/3 Re)", Color(0xFF94A3B8))
                            ProfileLegendDot("Blocked", AlertErrorRed)
                            ProfileLegendDot("60% F1", NodeMultiHopSky)
                            if (profile.mode == BridgeProfileMode.RELAY) {
                                ProfileLegendDot("Relay", NodeDirectGreen)
                            }
                        }
                    }
                }

                BridgeProfileCanvasBox(
                    profile = profile,
                    selectedObstructionId = selectedObstructionId,
                    scrubbedSample = scrubbedSample,
                    onSelectObstruction = onSelectObstruction,
                    onScrubSample = onScrubSample
                )

                Text(
                    text = stringResource(R.string.bridge_profile_tap_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
            }
        }
    }
}

@Composable
private fun BridgeProfileCanvasBox(
    profile: BridgeCompositeProfile,
    selectedObstructionId: String?,
    scrubbedSample: BridgeProfileSample?,
    onSelectObstruction: (String) -> Unit,
    onScrubSample: (BridgeProfileSample?) -> Unit
) {
    val samples = profile.samples
    val bounds = remember(profile) { computeProfileYBounds(samples) }
    val textMeasurer = rememberTextMeasurer()

    val currentProfile by rememberUpdatedState(profile)
    val currentSelectedId by rememberUpdatedState(selectedObstructionId)
    val currentOnSelectObstruction by rememberUpdatedState(onSelectObstruction)
    val currentOnScrubSample by rememberUpdatedState(onScrubSample)

    val axisTextStyle = TextStyle(
        fontFamily = JetBrainsMonoFontFamily,
        fontSize = 9.sp,
        color = TextMuted
    )
    val peakNumberStyle = TextStyle(
        fontFamily = JetBrainsMonoFontFamily,
        fontSize = 8.5.sp,
        fontWeight = FontWeight.Bold,
        color = TacticalCanvas
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(195.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF070D19))
            .pointerInput(profile) {
                detectTapGestures { offset ->
                    val leftPad = 38.dp.toPx()
                    val rightPad = 10.dp.toPx()
                    val topPad = 10.dp.toPx()
                    val bottomPad = 20.dp.toPx()
                    val plotW = (size.width - leftPad - rightPad).coerceAtLeast(1f)
                    val plotH = (size.height - topPad - bottomPad).coerceAtLeast(1f)
                    val hitId = findNearestPeakHitId(
                        tapX = offset.x,
                        tapY = offset.y,
                        leftPadPx = leftPad,
                        topPadPx = topPad,
                        plotWidthPx = plotW,
                        plotHeightPx = plotH,
                        profile = currentProfile,
                        selectedObstructionId = currentSelectedId,
                        hitRadiusPx = 28.dp.toPx()
                    )
                    if (hitId != null) {
                        currentOnSelectObstruction(hitId)
                    } else {
                        val sample = findNearestProfileSample(offset.x, leftPad, plotW, currentProfile)
                        currentOnScrubSample(sample)
                    }
                }
            }
            .pointerInput(profile) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    val leftPad = 38.dp.toPx()
                    val rightPad = 10.dp.toPx()
                    val plotW = (size.width - leftPad - rightPad).coerceAtLeast(1f)
                    val sample = findNearestProfileSample(change.position.x, leftPad, plotW, currentProfile)
                    currentOnScrubSample(sample)
                }
            }
            .testTag("bridge_terrain_profile_chart")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val leftPad = 38.dp.toPx()
            val rightPad = 10.dp.toPx()
            val topPad = 10.dp.toPx()
            val bottomPad = 20.dp.toPx()
            val plotW = (size.width - leftPad - rightPad).coerceAtLeast(1f)
            val plotH = (size.height - topPad - bottomPad).coerceAtLeast(1f)
            val totalDist = profile.totalDistanceKm.coerceAtLeast(0.01)

            fun xForDist(distKm: Double): Float {
                val frac = (distKm / totalDist).toFloat().coerceIn(0f, 1f)
                return leftPad + frac * plotW
            }

            fun yForElev(elevMeters: Double): Float {
                val norm = ((elevMeters - bounds.yMin) / bounds.yRange).toFloat().coerceIn(0f, 1f)
                return topPad + plotH * (1f - norm)
            }

            // Grid lines + elevation labels
            val yTicks = 4
            for (t in 0..yTicks) {
                val frac = t.toFloat() / yTicks.toFloat()
                val elev = bounds.yMin + frac * bounds.yRange
                val y = topPad + plotH * (1f - frac)
                drawLine(
                    color = Color(0xFF1E293B),
                    start = Offset(leftPad, y),
                    end = Offset(leftPad + plotW, y),
                    strokeWidth = 1.dp.toPx()
                )
                val labelLayout = textMeasurer.measure("${elev.roundToInt()}m", axisTextStyle)
                drawText(
                    textLayoutResult = labelLayout,
                    topLeft = Offset(
                        2.dp.toPx(),
                        (y - labelLayout.size.height / 2f).coerceIn(0f, size.height - labelLayout.size.height)
                    )
                )
            }

            // X distance labels
            val xLabelEntries = buildList {
                add(0.0 to "TX")
                val hingeDist = profile.relayHingeDistanceKm
                if (hingeDist != null) {
                    add(hingeDist to String.format(Locale.US, "Relay %.1f", hingeDist))
                } else {
                    add((totalDist * 0.5) to String.format(Locale.US, "%.1f", totalDist * 0.5))
                }
                add(totalDist to String.format(Locale.US, "%.1f km", totalDist))
            }
            xLabelEntries.forEach { (dist, label) ->
                val x = xForDist(dist)
                val measured = textMeasurer.measure(label, axisTextStyle)
                val drawX = (x - measured.size.width / 2f).coerceIn(leftPad, size.width - measured.size.width)
                drawText(
                    textLayoutResult = measured,
                    topLeft = Offset(drawX, topPad + plotH + 3.dp.toPx())
                )
            }

            // 60% Fresnel Zone envelope fill + dashed bounds
            val fresnelEnvelope = Path().apply {
                samples.forEachIndexed { idx, s ->
                    val x = xForDist(s.distanceFromStartKm)
                    val y = yForElev(s.fresnel60UpperMeters)
                    if (idx == 0) moveTo(x, y) else lineTo(x, y)
                }
                for (idx in samples.indices.reversed()) {
                    lineTo(xForDist(samples[idx].distanceFromStartKm), yForElev(samples[idx].fresnel60LowerMeters))
                }
                close()
            }
            drawPath(
                path = fresnelEnvelope,
                color = NodeMultiHopSky.copy(alpha = 0.14f)
            )

            val fresnelUpper = Path()
            val fresnelLower = Path()
            samples.forEachIndexed { idx, s ->
                val x = xForDist(s.distanceFromStartKm)
                val yUp = yForElev(s.fresnel60UpperMeters)
                val yLo = yForElev(s.fresnel60LowerMeters)
                if (idx == 0) {
                    fresnelUpper.moveTo(x, yUp)
                    fresnelLower.moveTo(x, yLo)
                } else {
                    fresnelUpper.lineTo(x, yUp)
                    fresnelLower.lineTo(x, yLo)
                }
            }
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
            drawPath(
                path = fresnelUpper,
                color = NodeMultiHopSky.copy(alpha = 0.6f),
                style = Stroke(width = 1.1.dp.toPx(), pathEffect = dashEffect)
            )
            drawPath(
                path = fresnelLower,
                color = NodeMultiHopSky.copy(alpha = 0.85f),
                style = Stroke(width = 1.3.dp.toPx(), pathEffect = dashEffect)
            )

            // Filled terrain relief
            val terrainFill = Path().apply {
                moveTo(xForDist(samples.first().distanceFromStartKm), topPad + plotH)
                samples.forEach { s ->
                    lineTo(xForDist(s.distanceFromStartKm), yForElev(s.effectiveTerrainMeters))
                }
                lineTo(xForDist(samples.last().distanceFromStartKm), topPad + plotH)
                close()
            }
            drawPath(
                path = terrainFill,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF475569).copy(alpha = 0.78f),
                        Color(0xFF0F172A).copy(alpha = 0.94f)
                    ),
                    startY = topPad,
                    endY = topPad + plotH
                )
            )

            // Red terrain intrusion shading (where terrain blocks ray) & amber grazing intrusion
            for (i in 0 until samples.lastIndex) {
                val a = samples[i]
                val b = samples[i + 1]
                val ax = xForDist(a.distanceFromStartKm)
                val bx = xForDist(b.distanceFromStartKm)

                if (a.effectiveTerrainMeters > a.rayElevationMeters || b.effectiveTerrainMeters > b.rayElevationMeters) {
                    val clipTopA = max(a.effectiveTerrainMeters, a.rayElevationMeters)
                    val clipTopB = max(b.effectiveTerrainMeters, b.rayElevationMeters)
                    val blockedPatch = Path().apply {
                        moveTo(ax, yForElev(a.rayElevationMeters))
                        lineTo(ax, yForElev(clipTopA))
                        lineTo(bx, yForElev(clipTopB))
                        lineTo(bx, yForElev(b.rayElevationMeters))
                        close()
                    }
                    drawPath(path = blockedPatch, color = AlertErrorRed.copy(alpha = 0.34f))
                } else if (a.effectiveTerrainMeters > a.fresnel60LowerMeters || b.effectiveTerrainMeters > b.fresnel60LowerMeters) {
                    val clipTopA = max(a.effectiveTerrainMeters, a.fresnel60LowerMeters)
                    val clipTopB = max(b.effectiveTerrainMeters, b.fresnel60LowerMeters)
                    val grazingPatch = Path().apply {
                        moveTo(ax, yForElev(a.fresnel60LowerMeters))
                        lineTo(ax, yForElev(clipTopA))
                        lineTo(bx, yForElev(clipTopB))
                        lineTo(bx, yForElev(b.fresnel60LowerMeters))
                        close()
                    }
                    drawPath(path = grazingPatch, color = NodeSelfAmber.copy(alpha = 0.25f))
                }
            }

            // Upper terrain ridge stroke
            val terrainStroke = Path().apply {
                samples.forEachIndexed { idx, s ->
                    val x = xForDist(s.distanceFromStartKm)
                    val y = yForElev(s.effectiveTerrainMeters)
                    if (idx == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            drawPath(
                path = terrainStroke,
                color = Color(0xFF94A3B8),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )

            // RF Ray path(s)
            fun rayColorForSlice(slice: List<BridgeProfileSample>): Color {
                val interior = if (slice.size > 2) slice.subList(1, slice.lastIndex) else slice
                val blocked = interior.any { it.clearanceMeters < 0.0 }
                val grazing = interior.any { it.fresnelRatio < 0.60 }
                return when {
                    blocked -> AlertErrorRed
                    grazing -> NodeSelfAmber
                    else -> NodeDirectGreen
                }
            }

            val hingeIdx = profile.relayHingeIndex
            if (profile.mode == BridgeProfileMode.RELAY && hingeIdx != null && hingeIdx in 1 until samples.lastIndex) {
                val hop1 = samples.subList(0, hingeIdx + 1)
                val hop2 = samples.subList(hingeIdx, samples.size)
                val hingeSample = samples[hingeIdx]

                // Vertical dashed Relay tower separator
                val rx = xForDist(hingeSample.distanceFromStartKm)
                drawLine(
                    color = NodeDirectGreen.copy(alpha = 0.45f),
                    start = Offset(rx, topPad),
                    end = Offset(rx, topPad + plotH),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f)
                )

                drawLine(
                    color = rayColorForSlice(hop1),
                    start = Offset(xForDist(samples.first().distanceFromStartKm), yForElev(samples.first().rayElevationMeters)),
                    end = Offset(rx, yForElev(hingeSample.rayElevationMeters)),
                    strokeWidth = 2.2.dp.toPx()
                )
                drawLine(
                    color = rayColorForSlice(hop2),
                    start = Offset(rx, yForElev(hingeSample.rayElevationMeters)),
                    end = Offset(xForDist(samples.last().distanceFromStartKm), yForElev(samples.last().rayElevationMeters)),
                    strokeWidth = 2.2.dp.toPx()
                )

                // Relay mast stem
                drawLine(
                    color = NodeDirectGreen,
                    start = Offset(rx, yForElev(hingeSample.effectiveTerrainMeters)),
                    end = Offset(rx, yForElev(hingeSample.rayElevationMeters)),
                    strokeWidth = 2.6.dp.toPx()
                )
                drawCircle(
                    color = NodeDirectGreen,
                    radius = 3.5.dp.toPx(),
                    center = Offset(rx, yForElev(hingeSample.rayElevationMeters))
                )
            } else {
                drawLine(
                    color = rayColorForSlice(samples),
                    start = Offset(xForDist(samples.first().distanceFromStartKm), yForElev(samples.first().rayElevationMeters)),
                    end = Offset(xForDist(samples.last().distanceFromStartKm), yForElev(samples.last().rayElevationMeters)),
                    strokeWidth = 2.2.dp.toPx()
                )
            }

            // TX & RX mast stems
            val txX = xForDist(samples.first().distanceFromStartKm)
            drawLine(
                color = NodeSelfAmber,
                start = Offset(txX, yForElev(samples.first().effectiveTerrainMeters)),
                end = Offset(txX, yForElev(samples.first().rayElevationMeters)),
                strokeWidth = 2.6.dp.toPx()
            )
            drawCircle(
                color = NodeSelfAmber,
                radius = 3.2.dp.toPx(),
                center = Offset(txX, yForElev(samples.first().rayElevationMeters))
            )

            val rxEndX = xForDist(samples.last().distanceFromStartKm)
            drawLine(
                color = NodeMultiHopSky,
                start = Offset(rxEndX, yForElev(samples.last().effectiveTerrainMeters)),
                end = Offset(rxEndX, yForElev(samples.last().rayElevationMeters)),
                strokeWidth = 2.6.dp.toPx()
            )
            drawCircle(
                color = NodeMultiHopSky,
                radius = 3.2.dp.toPx(),
                center = Offset(rxEndX, yForElev(samples.last().rayElevationMeters))
            )

            // Tappable Obstruction / Pinch Peak Diamond Markers
            val interactivePeaks = profile.visibleObstructions.filter {
                it.point.status != ViewshedPointStatus.CLEAR || it.id == selectedObstructionId
            }
            interactivePeaks.forEachIndexed { idx, obs ->
                val isSelected = obs.id == selectedObstructionId
                val peakColor = when (obs.point.status) {
                    ViewshedPointStatus.OBSTRUCTED -> AlertErrorRed
                    ViewshedPointStatus.GRAZING -> NodeSelfAmber
                    ViewshedPointStatus.CLEAR -> NodeDirectGreen
                }
                val px = xForDist(distanceAlongCompositeProfileKm(profile, obs))
                val py = yForElev(obs.point.effectiveTerrainMeters)

                if (isSelected) {
                    drawLine(
                        color = peakColor.copy(alpha = 0.8f),
                        start = Offset(px, topPad),
                        end = Offset(px, topPad + plotH),
                        strokeWidth = 1.2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f), 0f)
                    )
                }

                val halfDiag = (if (isSelected) 7.5f else 5.5f).dp.toPx()
                val diamond = Path().apply {
                    moveTo(px, py - halfDiag)
                    lineTo(px + halfDiag, py)
                    lineTo(px, py + halfDiag)
                    lineTo(px - halfDiag, py)
                    close()
                }
                if (isSelected) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.9f),
                        radius = halfDiag + 3.dp.toPx(),
                        center = Offset(px, py),
                        style = Stroke(width = 1.6.dp.toPx())
                    )
                }
                drawPath(path = diamond, color = peakColor)
                drawPath(
                    path = diamond,
                    color = TacticalCanvas,
                    style = Stroke(width = 1.2.dp.toPx())
                )

                val badgeLayout = textMeasurer.measure("${idx + 1}", peakNumberStyle)
                drawText(
                    textLayoutResult = badgeLayout,
                    topLeft = Offset(
                        px - badgeLayout.size.width / 2f,
                        py - badgeLayout.size.height / 2f
                    )
                )
            }

            // Scrub Probe Crosshair
            scrubbedSample?.let { sample ->
                val sx = xForDist(sample.distanceFromStartKm)
                val sy = yForElev(sample.effectiveTerrainMeters)
                drawLine(
                    color = NodeMultiHopSky.copy(alpha = 0.85f),
                    start = Offset(sx, topPad),
                    end = Offset(sx, topPad + plotH),
                    strokeWidth = 1.3.dp.toPx()
                )
                drawCircle(
                    color = Color.White,
                    radius = 5.5.dp.toPx(),
                    center = Offset(sx, sy)
                )
                drawCircle(
                    color = NodeMultiHopSky,
                    radius = 4.dp.toPx(),
                    center = Offset(sx, sy)
                )
            }
        }
    }
}

@Composable
private fun ProfileModeChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    accentColor: Color,
    testTag: String,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    selected -> accentColor.copy(alpha = 0.20f)
                    else -> TacticalSurfaceElevated
                }
            )
            .border(
                width = if (selected) 1.dp else 1.dp,
                color = if (selected) accentColor.copy(alpha = 0.8f) else TacticalOutline,
                shape = RoundedCornerShape(8.dp)
            )
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .testTag(testTag)
    ) {
        Text(
            text = label,
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) accentColor else TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ProfileLegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
    }
}
