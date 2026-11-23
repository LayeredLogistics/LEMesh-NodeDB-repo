package us.lemesh.nodedb.data

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

enum class ViewshedPointStatus(val code: Byte) {
    OBSTRUCTED(0),
    CLEAR(1),
    GRAZING(2)
}

data class ViewshedParams(
    val originLat: Double,
    val originLon: Double,
    val originLabel: String,
    val sourceNode: MeshNode? = null,
    val antennaHeightMeters: Double = 10.0,
    val radiusKm: Int = 25,
    val frequencyMhz: Double = LosAnalyzer.DEFAULT_LORA_US_FREQ_MHZ,
    val txPowerDbm: Double = RelayBridgeEngine.DEFAULT_TX_POWER_DBM,
    val antennaGainDbi: Double = RelayBridgeEngine.DEFAULT_ANTENNA_GAIN_DBI,
    val sensitivityDbm: Double = RelayBridgeEngine.DEFAULT_SENSITIVITY_DBM,
    val fadeMarginDb: Double = 0.0
)

data class CoveredNetworkNode(
    val node: MeshNode,
    val distanceKm: Double,
    val distanceMiles: Double,
    val predictedRssiDbm: Double,
    val status: ViewshedPointStatus
)

data class ViewshedResult(
    val params: ViewshedParams,
    val groundElevationMeters: Double,
    val bitmap: Bitmap,
    val northLat: Double,
    val southLat: Double,
    val westLon: Double,
    val eastLon: Double,
    val coveredAreaKm2: Double,
    val maxUnobstructedRangeKm: Double,
    val coveredClearNodes: List<CoveredNetworkNode>,
    val tileCountUsed: Int,
    val computeTimeMs: Long,
    val obstructedNodes: List<ViewshedObstructedNode> = emptyList(),
    val field: PolarVisibilityField? = null
)

object ViewshedEngine {
    private const val EARTH_RADIUS_KM = 6371.0088
    private const val K_FACTOR = 4.0 / 3.0
    private const val EFFECTIVE_EARTH_RADIUS_KM = EARTH_RADIUS_KM * K_FACTOR
    const val RAY_COUNT = 1440 // 0.25 degree increments for sharp street-level contours
    const val STEP_METERS = 100.0
    const val FINE_STEP_METERS = 60.0
    const val MAX_SUPPORTED_RADIUS_KM = 50
    private const val ADAPTIVE_TARGET_STEPS = 280.0
    private const val ADAPTIVE_FINE_TARGET_STEPS = 420.0
    const val RASTER_SIZE = 1024
    private const val DIFFRACTION_MARGIN_METERS = 6.0
    private const val RX_NOMINAL_AGL_METERS = 2.0

    fun adaptiveStepMeters(radiusKm: Double): Double {
        val clamped = radiusKm.coerceAtLeast(1.0)
        return if (clamped <= 25.0) {
            STEP_METERS
        } else {
            max(STEP_METERS, (clamped * 1000.0) / ADAPTIVE_TARGET_STEPS)
        }
    }

    fun fineStepMeters(radiusKm: Double): Double {
        val clamped = radiusKm.coerceAtLeast(1.0)
        return if (clamped <= 25.0) {
            FINE_STEP_METERS
        } else {
            max(FINE_STEP_METERS, (clamped * 1000.0) / ADAPTIVE_FINE_TARGET_STEPS)
        }
    }

    data class PolarFieldOutput(
        val field: PolarVisibilityField,
        val originElevationMeters: Double,
        val maxClearRangeKm: Double
    )

    fun computePolarField(
        originLat: Double,
        originLon: Double,
        antennaHeightMeters: Double,
        radiusKm: Double,
        frequencyMhz: Double,
        txPowerDbm: Double,
        antennaGainDbi: Double,
        sensitivityDbm: Double,
        grid: TerrariumTileGrid,
        rayCount: Int = RAY_COUNT,
        stepMeters: Double = fineStepMeters(radiusKm),
        fadeMarginDb: Double = 0.0
    ): PolarFieldOutput {
        val clampedRadiusKm = radiusKm.coerceIn(1.0, MAX_SUPPORTED_RADIUS_KM.toDouble())
        val numSteps = max(10, ((clampedRadiusKm * 1000.0) / stepMeters).roundToInt())
        val stepKm = stepMeters / 1000.0
        val freqMhz = frequencyMhz.coerceAtLeast(1.0)
        val freqGhz = freqMhz / 1000.0
        val safeFadeMarginDb = fadeMarginDb.coerceAtLeast(0.0)
        val eirpDbm = txPowerDbm + antennaGainDbi

        val originElev = grid.sampleElevation(originLat, originLon) ?: 0.0
        val txElev = originElev + antennaHeightMeters.coerceAtLeast(0.5)

        val statusGrid = ByteArray(rayCount * numSteps)
        val rssiGrid = FloatArray(rayCount * numSteps)

        val lat1Rad = Math.toRadians(originLat)
        val lon1Rad = Math.toRadians(originLon)
        val sinLat1 = sin(lat1Rad)
        val cosLat1 = cos(lat1Rad)

        val dThetaRad = (2.0 * PI) / rayCount
        var coveredAreaKm2 = 0.0
        var maxClearRangeKm = 0.0

        val terrainEffElev = DoubleArray(numSteps)
        val hullDistM = DoubleArray(numSteps + 1)
        val hullEffElev = DoubleArray(numSteps + 1)
        val hullRawElev = DoubleArray(numSteps + 1)

        for (rIdx in 0 until rayCount) {
            val bearingRad = rIdx * dThetaRad
            val sinBearing = sin(bearingRad)
            val cosBearing = cos(bearingRad)

            var maxTerrainSlope = Double.NEGATIVE_INFINITY
            hullDistM[0] = 0.0
            hullEffElev[0] = txElev
            hullRawElev[0] = originElev
            var hullSize = 1

            for (sIdx in 0 until numSteps) {
                val distM = (sIdx + 1) * stepMeters
                val distKm = distM / 1000.0
                val angDist = distKm / EARTH_RADIUS_KM
                val sinAng = sin(angDist)
                val cosAng = cos(angDist)

                val destLatRad = asin(
                    (sinLat1 * cosAng + cosLat1 * sinAng * cosBearing).coerceIn(-1.0, 1.0)
                )
                val destLonRad = lon1Rad + atan2(
                    sinBearing * sinAng * cosLat1,
                    cosAng - sinLat1 * sin(destLatRad)
                )

                val rawElev = grid.sampleElevation(
                    Math.toDegrees(destLatRad),
                    Math.toDegrees(destLonRad)
                ) ?: originElev

                val bulgeM = ((distKm * distKm) / (2.0 * EFFECTIVE_EARTH_RADIUS_KM)) * 1000.0
                val effTerrainElev = rawElev - bulgeM
                val rxTargetElev = (rawElev + RX_NOMINAL_AGL_METERS) - bulgeM

                terrainEffElev[sIdx] = effTerrainElev

                val terrainSlope = (effTerrainElev - txElev) / distM
                val targetSlope = (rxTargetElev - txElev) / distM

                var minFresnelRatio = 1.0
                val pointStatus: Byte
                if (targetSlope >= maxTerrainSlope) {
                    for (h in 1 until hullSize) {
                        val obsDistM = hullDistM[h]
                        val obsElev = hullEffElev[h]
                        val obsRaw = hullRawElev[h]
                        val isTerrainProminence = obsRaw > rawElev + 0.5 || obsRaw > originElev + 0.5
                        val isMidPath = obsDistM >= distM * 0.35 && obsDistM <= distM * 0.65
                        if (!isTerrainProminence && !isMidPath) continue

                        val rayAtObs = txElev + targetSlope * obsDistM
                        val d1Km = obsDistM / 1000.0
                        val d2Km = (distM - obsDistM) / 1000.0
                        if (d1Km > 0.0 && d2Km > 0.0) {
                            val f1 = 17.32 * sqrt((d1Km * d2Km) / (freqGhz * distKm))
                            if (f1 > 0.001) {
                                val ratio = (rayAtObs - obsElev) / f1
                                if (ratio < minFresnelRatio) minFresnelRatio = ratio
                            }
                        }
                    }

                    if (sIdx >= 2) {
                        val sampleIndices = intArrayOf(sIdx / 3, sIdx / 2, (sIdx * 2) / 3)
                        for (k in sampleIndices) {
                            if (k < 0 || k >= sIdx) continue
                            val obsDistM = (k + 1) * stepMeters
                            val obsElev = terrainEffElev[k]
                            val rayAtObs = txElev + targetSlope * obsDistM
                            val d1Km = obsDistM / 1000.0
                            val d2Km = (distM - obsDistM) / 1000.0
                            if (d1Km > 0.0 && d2Km > 0.0) {
                                val f1 = 17.32 * sqrt((d1Km * d2Km) / (freqGhz * distKm))
                                if (f1 > 0.001) {
                                    val ratio = (rayAtObs - obsElev) / f1
                                    if (ratio < minFresnelRatio) minFresnelRatio = ratio
                                }
                            }
                        }
                    }

                    pointStatus = if (minFresnelRatio < 0.60) {
                        ViewshedPointStatus.GRAZING.code
                    } else {
                        ViewshedPointStatus.CLEAR.code
                    }
                } else {
                    val horizonElev = txElev + maxTerrainSlope * distM
                    val shadowDepthM = horizonElev - rxTargetElev
                    if (shadowDepthM <= DIFFRACTION_MARGIN_METERS) {
                        pointStatus = ViewshedPointStatus.GRAZING.code
                        val approxF1 = (17.32 * sqrt((distKm * 0.25) / freqGhz)).coerceAtLeast(4.0)
                        minFresnelRatio = (-shadowDepthM / approxF1).coerceAtMost(0.0)
                    } else {
                        pointStatus = ViewshedPointStatus.OBSTRUCTED.code
                    }
                }

                if (terrainSlope > maxTerrainSlope) {
                    maxTerrainSlope = terrainSlope
                }
                while (hullSize >= 2) {
                    val ax = hullDistM[hullSize - 2]
                    val ay = hullEffElev[hullSize - 2]
                    val bx = hullDistM[hullSize - 1]
                    val by = hullEffElev[hullSize - 1]
                    val cross = (bx - ax) * (effTerrainElev - ay) - (by - ay) * (distM - ax)
                    if (cross >= 0.0) {
                        hullSize--
                    } else {
                        break
                    }
                }
                hullDistM[hullSize] = distM
                hullEffElev[hullSize] = effTerrainElev
                hullRawElev[hullSize] = rawElev
                hullSize++

                val cellOffset = rIdx * numSteps + sIdx
                if (pointStatus != ViewshedPointStatus.OBSTRUCTED.code) {
                    val fspl = 20.0 * log10(distKm) + 20.0 * log10(freqMhz) + 32.44
                    val diffractionLossDb = if (pointStatus == ViewshedPointStatus.GRAZING.code) {
                        knifeEdgeDiffractionLossDb(minFresnelRatio)
                    } else {
                        0.0
                    }
                    val rssi = eirpDbm - fspl - diffractionLossDb - safeFadeMarginDb
                    if (rssi >= sensitivityDbm) {
                        statusGrid[cellOffset] = pointStatus
                        rssiGrid[cellOffset] = rssi.toFloat()

                        coveredAreaKm2 += dThetaRad * distKm * stepKm
                        if (pointStatus == ViewshedPointStatus.CLEAR.code && distKm > maxClearRangeKm) {
                            maxClearRangeKm = distKm
                        }
                    }
                }
            }
        }

        val latDelta = (clampedRadiusKm / 111.12) * 1.01
        val cosOriginLat = cosLat1.coerceAtLeast(0.15)
        val lonDelta = (clampedRadiusKm / (111.32 * cosOriginLat)) * 1.01
        val northLat = (originLat + latDelta).coerceAtMost(85.0)
        val southLat = (originLat - latDelta).coerceAtLeast(-85.0)
        val westLon = originLon - lonDelta
        val eastLon = originLon + lonDelta

        val polarField = PolarVisibilityField(
            originLat = originLat,
            originLon = originLon,
            radiusKm = clampedRadiusKm,
            rayCount = rayCount,
            stepMeters = stepMeters,
            numSteps = numSteps,
            sensitivityDbm = sensitivityDbm,
            statusGrid = statusGrid,
            rssiGrid = rssiGrid,
            coveredAreaKm2 = coveredAreaKm2,
            northLat = northLat,
            southLat = southLat,
            westLon = westLon,
            eastLon = eastLon
        )
        return PolarFieldOutput(
            field = polarField,
            originElevationMeters = originElev,
            maxClearRangeKm = maxClearRangeKm
        )
    }

    fun computeViewshed(
        params: ViewshedParams,
        grid: TerrariumTileGrid,
        existingNodes: List<MeshNode>
    ): ViewshedResult {
        val startMs = System.currentTimeMillis()
        val radiusKm = params.radiusKm.coerceIn(1, MAX_SUPPORTED_RADIUS_KM).toDouble()
        val stepMeters = fineStepMeters(radiusKm)
        val freqMhz = params.frequencyMhz.coerceAtLeast(1.0)
        val eirpDbm = params.txPowerDbm + params.antennaGainDbi

        val output = computePolarField(
            originLat = params.originLat,
            originLon = params.originLon,
            antennaHeightMeters = params.antennaHeightMeters,
            radiusKm = radiusKm,
            frequencyMhz = freqMhz,
            txPowerDbm = params.txPowerDbm,
            antennaGainDbi = params.antennaGainDbi,
            sensitivityDbm = params.sensitivityDbm,
            grid = grid,
            rayCount = RAY_COUNT,
            stepMeters = stepMeters,
            fadeMarginDb = params.fadeMarginDb
        )
        val polarField = output.field
        val originElev = output.originElevationMeters
        val maxClearRangeKm = output.maxClearRangeKm
        val coveredAreaKm2 = polarField.coveredAreaKm2
        val northLat = polarField.northLat
        val southLat = polarField.southLat
        val westLon = polarField.westLon
        val eastLon = polarField.eastLon
        val numSteps = polarField.numSteps
        val statusGrid = polarField.statusGrid
        val rssiGrid = polarField.rssiGrid

        val bitmap = polarField.rasterizeSubRegion(
            width = RASTER_SIZE,
            height = RASTER_SIZE
        )

        // Identify existing network nodes inside the Clear LoS footprint AND Partial/Obstructed nodes
        val eligibleNodes = existingNodes.filter { node ->
            node.hasValidGps &&
                (params.sourceNode == null ||
                    node.id != params.sourceNode.id ||
                    node.num != params.sourceNode.num)
        }

        val coveredClearNodes = mutableListOf<CoveredNetworkNode>()
        data class RawObstructedCandidate(
            val node: MeshNode,
            val distanceKm: Double,
            val distanceMiles: Double,
            val status: ViewshedPointStatus,
            val gridRssi: Double?
        )
        val rawObstructed = mutableListOf<RawObstructedCandidate>()

        eligibleNodes.forEach { node ->
            val nLat = node.latitude!!
            val nLon = node.longitude!!
            val dKm = MeshGeoUtils.haversineKm(params.originLat, params.originLon, nLat, nLon)
            if (dKm > radiusKm || dKm < 0.05) return@forEach

            val bearing = MeshGeoUtils.bearingDegrees(params.originLat, params.originLon, nLat, nLon)
            val rIdx = ((bearing / 360.0) * RAY_COUNT).roundToInt() % RAY_COUNT
            val sIdx = ((dKm * 1000.0) / stepMeters).roundToInt().minus(1).coerceIn(0, numSteps - 1)
            val offset = rIdx * numSteps + sIdx
            val st = statusGrid[offset]
            when (st) {
                ViewshedPointStatus.CLEAR.code -> {
                    coveredClearNodes.add(
                        CoveredNetworkNode(
                            node = node,
                            distanceKm = dKm,
                            distanceMiles = MeshGeoUtils.kmToMiles(dKm),
                            predictedRssiDbm = rssiGrid[offset].toDouble(),
                            status = ViewshedPointStatus.CLEAR
                        )
                    )
                }
                ViewshedPointStatus.GRAZING.code -> {
                    rawObstructed.add(
                        RawObstructedCandidate(
                            node = node,
                            distanceKm = dKm,
                            distanceMiles = MeshGeoUtils.kmToMiles(dKm),
                            status = ViewshedPointStatus.GRAZING,
                            gridRssi = rssiGrid[offset].toDouble()
                        )
                    )
                }
                else -> {
                    rawObstructed.add(
                        RawObstructedCandidate(
                            node = node,
                            distanceKm = dKm,
                            distanceMiles = MeshGeoUtils.kmToMiles(dKm),
                            status = ViewshedPointStatus.OBSTRUCTED,
                            gridRssi = null
                        )
                    )
                }
            }
        }

        val sortedClearNodes = coveredClearNodes.sortedByDescending { it.predictedRssiDbm }
        val sortedRawObstructed = rawObstructed.sortedWith(
            compareBy<RawObstructedCandidate> {
                if (it.status == ViewshedPointStatus.GRAZING) 0 else 1
            }
                .thenByDescending { it.gridRssi ?: Double.NEGATIVE_INFINITY }
                .thenBy { it.distanceKm }
        )

        val obstructedNodes = sortedRawObstructed.mapIndexed { idx, item ->
            if (idx < 40) {
                val (hop, fixPlan) = ViewshedFixAdvisor.buildQuickNodeHint(
                    txLat = params.originLat,
                    txLon = params.originLon,
                    currentTxMeters = params.antennaHeightMeters,
                    rxLat = item.node.latitude!!,
                    rxLon = item.node.longitude!!,
                    currentRxMeters = RX_NOMINAL_AGL_METERS,
                    freqMhz = freqMhz,
                    eirpDbm = eirpDbm - params.fadeMarginDb.coerceAtLeast(0.0),
                    sensitivityDbm = params.sensitivityDbm,
                    grid = grid
                )
                val txOnlyOpt = fixPlan.options.firstOrNull {
                    it.strategy == ViewshedFixStrategy.RAISE_TX_MAST && it.isWithinMastLimit
                }
                val bestViable = fixPlan.options.firstOrNull {
                    it.strategy != ViewshedFixStrategy.BRIDGE_RELAY_FALLBACK && it.isWithinMastLimit
                }
                val hint = when {
                    hop.status == ViewshedPointStatus.CLEAR && hop.linkMarginDb >= 0.0 ->
                        "Ray edge graze • Tap Fix Options to verify TX → RX"
                    bestViable != null ->
                        "Fix: ${bestViable.subtitle} • Clears LoS"
                    else ->
                        fixPlan.diagnosticNote ?: "Needs >60m mast • Bridge relay recommended"
                }
                val effectiveStatus = if (hop.status == ViewshedPointStatus.CLEAR) {
                    item.status
                } else {
                    hop.status
                }
                ViewshedObstructedNode(
                    node = item.node,
                    distanceKm = item.distanceKm,
                    distanceMiles = item.distanceMiles,
                    status = effectiveStatus,
                    predictedRssiDbm = hop.predictedRssiDbm,
                    linkMarginDb = hop.linkMarginDb,
                    quickFixHint = hint,
                    recommendedTxHeightMeters = txOnlyOpt?.targetTxHeightMeters
                )
            } else {
                ViewshedObstructedNode(
                    node = item.node,
                    distanceKm = item.distanceKm,
                    distanceMiles = item.distanceMiles,
                    status = item.status,
                    predictedRssiDbm = item.gridRssi,
                    linkMarginDb = null,
                    quickFixHint = "Tap Fix Options to compute mast or Bridge fixes",
                    recommendedTxHeightMeters = null
                )
            }
        }

        return ViewshedResult(
            params = params,
            groundElevationMeters = originElev,
            bitmap = bitmap,
            northLat = northLat,
            southLat = southLat,
            westLon = westLon,
            eastLon = eastLon,
            coveredAreaKm2 = coveredAreaKm2,
            maxUnobstructedRangeKm = maxClearRangeKm,
            coveredClearNodes = sortedClearNodes,
            tileCountUsed = grid.tileCount,
            computeTimeMs = System.currentTimeMillis() - startMs,
            obstructedNodes = obstructedNodes,
            field = polarField
        )
    }

    /**
     * ITU-R P.526 single knife-edge diffraction loss (dB) as a function of
     * normalized 1st Fresnel clearance ratio (clearance / F1).
     * Unobstructed paths (>= 0.60 F1) suffer 0 dB diffraction loss;
     * grazing horizon (0.0 F1) adds ~6.0 dB, rising smoothly into terrain shadow.
     */
    fun knifeEdgeDiffractionLossDb(minFresnelRatio: Double): Double {
        if (minFresnelRatio >= 0.60) return 0.0
        val v = (-minFresnelRatio * 1.41421356237).coerceIn(-0.78, 3.5)
        if (v <= -0.78) return 0.0
        val term = v - 0.1
        val loss = 6.9 + 20.0 * log10(sqrt(term * term + 1.0) + term)
        return loss.coerceIn(0.0, 28.0)
    }

    const val STRONG_RSSI_DBM = -60.0

    fun rssiNormalized(rssi: Double, sensitivityDbm: Double): Double {
        val floorDbm = min(-90.0, sensitivityDbm)
        val span = (STRONG_RSSI_DBM - floorDbm).coerceAtLeast(10.0)
        return ((rssi - floorDbm) / span).coerceIn(0.0, 1.0)
    }

    fun colorForRssi(
        rssi: Double,
        sensitivityDbm: Double,
        isGrazing: Boolean
    ): Int {
        val alpha = if (isGrazing) 165 else 225
        // Calibrated Green -> Yellow -> Orange -> Red heatmap across link budget:
        // p = 1.0 (>= -60 dBm)       -> Vivid Green (#22C55E)
        // p = 0.58 (~ -89 dBm @-130) -> Yellow (#FACC15)
        // p = 0.28 (~ -110 dBm @-130)-> Orange (#F97316)
        // p = 0.0 (<= sensitivity)   -> Red (#EF4444)
        val p = rssiNormalized(rssi, sensitivityDbm)
        val (r, g, b) = when {
            p >= 0.58 -> {
                val t = ((p - 0.58) / 0.42).coerceIn(0.0, 1.0)
                lerpRgb(0xFA, 0xCC, 0x15, 0x22, 0xC5, 0x5E, t)
            }
            p >= 0.28 -> {
                val t = ((p - 0.28) / 0.30).coerceIn(0.0, 1.0)
                lerpRgb(0xF9, 0x73, 0x16, 0xFA, 0xCC, 0x15, t)
            }
            else -> {
                val t = (p / 0.28).coerceIn(0.0, 1.0)
                lerpRgb(0xEF, 0x44, 0x44, 0xF9, 0x73, 0x16, t)
            }
        }
        return Color.argb(alpha, r, g, b)
    }

    private fun lerpRgb(
        r1: Int, g1: Int, b1: Int,
        r2: Int, g2: Int, b2: Int,
        t: Double
    ): Triple<Int, Int, Int> {
        val r = (r1 + (r2 - r1) * t).roundToInt().coerceIn(0, 255)
        val g = (g1 + (g2 - g1) * t).roundToInt().coerceIn(0, 255)
        val b = (b1 + (b2 - b1) * t).roundToInt().coerceIn(0, 255)
        return Triple(r, g, b)
    }

    private fun latToMercY(latDeg: Double): Double {
        val latRad = Math.toRadians(latDeg.coerceIn(-85.05112878, 85.05112878))
        return ln(tan(PI / 4.0 + latRad / 2.0))
    }

    private fun mercYToLat(mercY: Double): Double {
        return Math.toDegrees(2.0 * atan2(exp(mercY), 1.0) - PI / 2.0)
    }
}
