package us.lemesh.nodedb.data

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class ViewshedFixStrategy(val label: String) {
    RAISE_TX_MAST("Raise TX Mast"),
    RAISE_RX_MAST("Raise RX Mast"),
    RAISE_BOTH_MASTS("Raise Both Masts"),
    BRIDGE_RELAY_FALLBACK("Bridge Relay Fallback")
}

data class ViewshedLinkFixOption(
    val strategy: ViewshedFixStrategy,
    val title: String,
    val subtitle: String,
    val targetTxHeightMeters: Int,
    val targetRxHeightMeters: Int,
    val txDeltaMeters: Int,
    val rxDeltaMeters: Int,
    val isWithinMastLimit: Boolean,
    val isRecommended: Boolean,
    val resultingHop: BridgeHopEvaluation? = null
)

data class ViewshedFixPlan(
    val options: List<ViewshedLinkFixOption>,
    val diagnosticNote: String? = null
) {
    val hasViableMastFix: Boolean
        get() = options.any { it.strategy != ViewshedFixStrategy.BRIDGE_RELAY_FALLBACK && it.isWithinMastLimit }
}

data class ViewshedObstructedNode(
    val node: MeshNode,
    val distanceKm: Double,
    val distanceMiles: Double,
    val status: ViewshedPointStatus,
    val predictedRssiDbm: Double?,
    val linkMarginDb: Double?,
    val quickFixHint: String,
    val recommendedTxHeightMeters: Int? = null
)

object ViewshedFixAdvisor {
    const val MIN_MAST_HEIGHT_METERS = 1
    const val MAX_MAST_HEIGHT_METERS = 60
    private const val EARTH_RADIUS_KM = 6371.0088
    private const val K_FACTOR = 4.0 / 3.0
    private const val EFFECTIVE_EARTH_RADIUS_KM = EARTH_RADIUS_KM * K_FACTOR
    private const val MIN_HOP_SAMPLES = 64
    private const val FRESNEL_CLEAR_RATIO = 0.60

    fun analyzeHopFixes(
        txLat: Double,
        txLon: Double,
        currentTxMeters: Double,
        rxLat: Double,
        rxLon: Double,
        currentRxMeters: Double,
        freqMhz: Double,
        eirpDbm: Double,
        sensitivityDbm: Double,
        grid: TerrariumTileGrid,
        currentHop: BridgeHopEvaluation? = null
    ): ViewshedFixPlan {
        val safeFreqMhz = freqMhz.coerceAtLeast(1.0)
        val freqGhz = safeFreqMhz / 1000.0
        val hop = currentHop ?: RelayBridgeEngine.evaluateHop(
            latA = txLat,
            lonA = txLon,
            heightAMeters = currentTxMeters,
            latB = rxLat,
            lonB = rxLon,
            heightBMeters = currentRxMeters,
            freqMhz = safeFreqMhz,
            freqGhz = freqGhz,
            eirpDbm = eirpDbm,
            sensitivityDbm = sensitivityDbm,
            grid = grid,
            collectObstructionPeaks = false
        )

        if (hop.status == ViewshedPointStatus.CLEAR && hop.linkMarginDb >= 0.0) {
            return ViewshedFixPlan(options = emptyList(), diagnosticNote = null)
        }

        val curTxInt = currentTxMeters.roundToInt().coerceIn(MIN_MAST_HEIGHT_METERS, MAX_MAST_HEIGHT_METERS)
        val curRxInt = currentRxMeters.roundToInt().coerceIn(MIN_MAST_HEIGHT_METERS, MAX_MAST_HEIGHT_METERS)
        val curTx = curTxInt.toDouble()
        val curRx = curRxInt.toDouble()

        val totalKm = MeshGeoUtils.haversineKm(txLat, txLon, rxLat, rxLon).coerceAtLeast(0.01)
        val elevA = grid.sampleElevation(txLat, txLon) ?: 0.0
        val elevB = grid.sampleElevation(rxLat, rxLon) ?: 0.0
        val antA = elevA + curTx
        val antB = elevB + curRx

        val phi1 = Math.toRadians(txLat)
        val lambda1 = Math.toRadians(txLon)
        val phi2 = Math.toRadians(rxLat)
        val lambda2 = Math.toRadians(rxLon)
        val dPhi = phi2 - phi1
        val dLambda = lambda2 - lambda1
        val haversineA = sin(dPhi / 2.0) * sin(dPhi / 2.0) +
            cos(phi1) * cos(phi2) * sin(dLambda / 2.0) * sin(dLambda / 2.0)
        val delta = 2.0 * atan2(sqrt(haversineA), sqrt(max(0.0, 1.0 - haversineA)))

        val sampleCount = max(MIN_HOP_SAMPLES, (totalKm * 2.0).roundToInt()).coerceIn(MIN_HOP_SAMPLES, 128)

        var reqTxOnly = curTx
        var reqRxOnly = curRx
        var reqBothDelta = 0.0

        val pathReqElevs = DoubleArray(sampleCount)
        val pathFractions = DoubleArray(sampleCount)

        for (i in 1 until sampleCount - 1) {
            val f = i.toDouble() / (sampleCount - 1)
            val sLat: Double
            val sLon: Double
            if (delta < 1e-7) {
                sLat = txLat + (rxLat - txLat) * f
                sLon = txLon + (rxLon - txLon) * f
            } else {
                val sinDelta = sin(delta)
                val wA = sin((1.0 - f) * delta) / sinDelta
                val wB = sin(f * delta) / sinDelta
                val x = wA * cos(phi1) * cos(lambda1) + wB * cos(phi2) * cos(lambda2)
                val y = wA * cos(phi1) * sin(lambda1) + wB * cos(phi2) * sin(lambda2)
                val z = wA * sin(phi1) + wB * sin(phi2)
                sLat = Math.toDegrees(atan2(z, sqrt(x * x + y * y)))
                sLon = Math.toDegrees(atan2(y, x))
            }

            val rawTerrain = grid.sampleElevation(sLat, sLon) ?: elevA
            val d1Km = f * totalKm
            val d2Km = (1.0 - f) * totalKm
            val bulgeM = ((d1Km * d2Km) / (2.0 * EFFECTIVE_EARTH_RADIUS_KM)) * 1000.0
            val effTerrain = rawTerrain + bulgeM
            val f1 = 17.32 * sqrt((d1Km * d2Km) / (freqGhz * totalKm))
            val reqElev = effTerrain + FRESNEL_CLEAR_RATIO * f1 + 0.05
            pathReqElevs[i] = reqElev
            pathFractions[i] = f

            val curRay = antA + (antB - antA) * f
            val deficit = reqElev - curRay
            if (deficit > reqBothDelta) {
                reqBothDelta = deficit
            }

            if (1.0 - f > 0.02) {
                val txForSample = ((reqElev - f * antB) / (1.0 - f)) - elevA
                if (txForSample > reqTxOnly) reqTxOnly = txForSample
            } else {
                reqTxOnly = Double.POSITIVE_INFINITY
            }

            if (f > 0.02) {
                val rxForSample = ((reqElev - (1.0 - f) * antA) / f) - elevB
                if (rxForSample > reqRxOnly) reqRxOnly = rxForSample
            } else {
                reqRxOnly = Double.POSITIVE_INFINITY
            }
        }

        val minNeededOutOfLimit = mutableListOf<Int>()

        fun tryVerifyMastCandidate(
            strategy: ViewshedFixStrategy,
            baseTxInt: Int,
            baseRxInt: Int
        ): ViewshedLinkFixOption? {
            if (baseTxInt > MAX_MAST_HEIGHT_METERS || baseRxInt > MAX_MAST_HEIGHT_METERS) {
                if (baseTxInt < 10_000 && baseRxInt < 10_000) {
                    minNeededOutOfLimit.add(max(baseTxInt, baseRxInt))
                }
                return null
            }
            if (baseTxInt <= curTxInt && baseRxInt <= curRxInt && hop.status != ViewshedPointStatus.CLEAR) {
                return null
            }

            for (nudge in 0..2) {
                val candTx = when (strategy) {
                    ViewshedFixStrategy.RAISE_TX_MAST, ViewshedFixStrategy.RAISE_BOTH_MASTS ->
                        (baseTxInt + nudge).coerceAtMost(MAX_MAST_HEIGHT_METERS)
                    else -> baseTxInt
                }
                val candRx = when (strategy) {
                    ViewshedFixStrategy.RAISE_RX_MAST, ViewshedFixStrategy.RAISE_BOTH_MASTS ->
                        (baseRxInt + nudge).coerceAtMost(MAX_MAST_HEIGHT_METERS)
                    else -> baseRxInt
                }
                val verified = RelayBridgeEngine.evaluateHop(
                    latA = txLat,
                    lonA = txLon,
                    heightAMeters = candTx.toDouble(),
                    latB = rxLat,
                    lonB = rxLon,
                    heightBMeters = candRx.toDouble(),
                    freqMhz = safeFreqMhz,
                    freqGhz = freqGhz,
                    eirpDbm = eirpDbm,
                    sensitivityDbm = sensitivityDbm,
                    grid = grid,
                    collectObstructionPeaks = false
                )
                if (verified.status == ViewshedPointStatus.CLEAR) {
                    val txDelta = candTx - curTxInt
                    val rxDelta = candRx - curRxInt
                    if (txDelta <= 0 && rxDelta <= 0) return null
                    val subtitle = when (strategy) {
                        ViewshedFixStrategy.RAISE_TX_MAST ->
                            "TX ${curTxInt}m → ${candTx}m (+${txDelta}m)"
                        ViewshedFixStrategy.RAISE_RX_MAST ->
                            "RX ${curRxInt}m → ${candRx}m (+${rxDelta}m)"
                        ViewshedFixStrategy.RAISE_BOTH_MASTS ->
                            "TX ${curTxInt}m→${candTx}m (+${txDelta}m) • RX ${curRxInt}m→${candRx}m (+${rxDelta}m)"
                        ViewshedFixStrategy.BRIDGE_RELAY_FALLBACK -> ""
                    }
                    return ViewshedLinkFixOption(
                        strategy = strategy,
                        title = strategy.label,
                        subtitle = subtitle,
                        targetTxHeightMeters = candTx,
                        targetRxHeightMeters = candRx,
                        txDeltaMeters = txDelta,
                        rxDeltaMeters = rxDelta,
                        isWithinMastLimit = true,
                        isRecommended = false,
                        resultingHop = verified
                    )
                }
            }
            return null
        }

        val candidates = mutableListOf<ViewshedLinkFixOption>()

        if (hop.status != ViewshedPointStatus.CLEAR) {
            if (reqTxOnly.isFinite()) {
                val txOnlyInt = ceil(reqTxOnly).toInt().coerceAtLeast(curTxInt)
                tryVerifyMastCandidate(ViewshedFixStrategy.RAISE_TX_MAST, txOnlyInt, curRxInt)
                    ?.let { candidates.add(it) }
            }

            if (reqRxOnly.isFinite()) {
                val rxOnlyInt = ceil(reqRxOnly).toInt().coerceAtLeast(curRxInt)
                tryVerifyMastCandidate(ViewshedFixStrategy.RAISE_RX_MAST, curTxInt, rxOnlyInt)
                    ?.let { candidates.add(it) }
            }

            if (reqBothDelta > 0.0) {
                val deltaInt = ceil(reqBothDelta).toInt().coerceAtLeast(1)
                var bothTx = curTxInt + deltaInt
                var bothRx = curRxInt + deltaInt

                // If one mast was already higher and equal delta pushed it past 60m, cap high side at 60m and solve remaining on partner mast
                if ((bothTx > MAX_MAST_HEIGHT_METERS || bothRx > MAX_MAST_HEIGHT_METERS) &&
                    !(bothTx > MAX_MAST_HEIGHT_METERS && bothRx > MAX_MAST_HEIGHT_METERS)
                ) {
                    if (bothTx > MAX_MAST_HEIGHT_METERS && curTxInt < MAX_MAST_HEIGHT_METERS) {
                        val capTx = MAX_MAST_HEIGHT_METERS
                        val capAntA = elevA + capTx
                        var neededRx = curRx
                        for (i in 1 until sampleCount - 1) {
                            val f = pathFractions[i]
                            if (f > 0.02) {
                                val r = ((pathReqElevs[i] - (1.0 - f) * capAntA) / f) - elevB
                                if (r > neededRx) neededRx = r
                            }
                        }
                        bothTx = capTx
                        bothRx = ceil(neededRx).toInt().coerceAtLeast(curRxInt)
                    } else if (bothRx > MAX_MAST_HEIGHT_METERS && curRxInt < MAX_MAST_HEIGHT_METERS) {
                        val capRx = MAX_MAST_HEIGHT_METERS
                        val capAntB = elevB + capRx
                        var neededTx = curTx
                        for (i in 1 until sampleCount - 1) {
                            val f = pathFractions[i]
                            if (1.0 - f > 0.02) {
                                val t = ((pathReqElevs[i] - f * capAntB) / (1.0 - f)) - elevA
                                if (t > neededTx) neededTx = t
                            }
                        }
                        bothTx = ceil(neededTx).toInt().coerceAtLeast(curTxInt)
                        bothRx = capRx
                    }
                }

                tryVerifyMastCandidate(ViewshedFixStrategy.RAISE_BOTH_MASTS, bothTx, bothRx)
                    ?.let { candidates.add(it) }
            }
        }

        val dedupedViable = candidates
            .distinctBy { it.targetTxHeightMeters to it.targetRxHeightMeters }
            .filter { (it.resultingHop?.linkMarginDb ?: -999.0) >= 0.0 }
            .sortedWith(
                compareBy<ViewshedLinkFixOption> { it.txDeltaMeters + it.rxDeltaMeters }
                    .thenBy { it.strategy.ordinal }
            )

        val finalOptions = mutableListOf<ViewshedLinkFixOption>()
        var diagnosticNote: String? = null

        if (dedupedViable.isNotEmpty()) {
            dedupedViable.forEachIndexed { idx, opt ->
                finalOptions.add(opt.copy(isRecommended = idx == 0))
            }
            finalOptions.add(
                ViewshedLinkFixOption(
                    strategy = ViewshedFixStrategy.BRIDGE_RELAY_FALLBACK,
                    title = ViewshedFixStrategy.BRIDGE_RELAY_FALLBACK.label,
                    subtitle = "Find an elevated midpoint relay to bypass terrain blockage",
                    targetTxHeightMeters = curTxInt,
                    targetRxHeightMeters = curRxInt,
                    txDeltaMeters = 0,
                    rxDeltaMeters = 0,
                    isWithinMastLimit = true,
                    isRecommended = false,
                    resultingHop = null
                )
            )
        } else {
            diagnosticNote = when {
                hop.status == ViewshedPointStatus.CLEAR && hop.linkMarginDb < 0.0 ->
                    String.format(
                        Locale.US,
                        "Path is line-of-sight clear, but link margin is %.1f dB. A Bridge relay cuts hop distance.",
                        hop.linkMarginDb
                    )
                minNeededOutOfLimit.isNotEmpty() -> {
                    val lowestExcess = minNeededOutOfLimit.minOrNull() ?: (MAX_MAST_HEIGHT_METERS + 1)
                    "Clearing terrain requires ~${lowestExcess}m mast (exceeds ${MAX_MAST_HEIGHT_METERS}m limit). Use Bridge relay."
                }
                else ->
                    "Terrain or link budget blocks direct single-hop LoS within ${MAX_MAST_HEIGHT_METERS}m masts. Use Bridge relay."
            }

            finalOptions.add(
                ViewshedLinkFixOption(
                    strategy = ViewshedFixStrategy.BRIDGE_RELAY_FALLBACK,
                    title = ViewshedFixStrategy.BRIDGE_RELAY_FALLBACK.label,
                    subtitle = "Solve optimal 2-hop relay site across elevated terrain",
                    targetTxHeightMeters = curTxInt,
                    targetRxHeightMeters = curRxInt,
                    txDeltaMeters = 0,
                    rxDeltaMeters = 0,
                    isWithinMastLimit = false,
                    isRecommended = true,
                    resultingHop = null
                )
            )
        }

        return ViewshedFixPlan(
            options = finalOptions,
            diagnosticNote = diagnosticNote
        )
    }

    fun buildQuickNodeHint(
        txLat: Double,
        txLon: Double,
        currentTxMeters: Double,
        rxLat: Double,
        rxLon: Double,
        currentRxMeters: Double,
        freqMhz: Double,
        eirpDbm: Double,
        sensitivityDbm: Double,
        grid: TerrariumTileGrid
    ): Pair<BridgeHopEvaluation, ViewshedFixPlan> {
        val hop = RelayBridgeEngine.evaluateHop(
            latA = txLat,
            lonA = txLon,
            heightAMeters = currentTxMeters,
            latB = rxLat,
            lonB = rxLon,
            heightBMeters = currentRxMeters,
            freqMhz = freqMhz.coerceAtLeast(1.0),
            freqGhz = freqMhz.coerceAtLeast(1.0) / 1000.0,
            eirpDbm = eirpDbm,
            sensitivityDbm = sensitivityDbm,
            grid = grid,
            collectObstructionPeaks = false
        )
        val plan = analyzeHopFixes(
            txLat = txLat,
            txLon = txLon,
            currentTxMeters = currentTxMeters,
            rxLat = rxLat,
            rxLon = rxLon,
            currentRxMeters = currentRxMeters,
            freqMhz = freqMhz,
            eirpDbm = eirpDbm,
            sensitivityDbm = sensitivityDbm,
            grid = grid,
            currentHop = hop
        )
        return hop to plan
    }
}
