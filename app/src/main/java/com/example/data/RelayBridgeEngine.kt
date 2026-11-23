package com.example.data

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class BridgeParams(
    val txLat: Double,
    val txLon: Double,
    val txLabel: String,
    val txNode: MeshNode? = null,
    val rxLat: Double,
    val rxLon: Double,
    val rxLabel: String,
    val rxNode: MeshNode? = null,
    val txAntennaHeightMeters: Double = 10.0,
    val rxAntennaHeightMeters: Double = 10.0,
    val relayAntennaHeightMeters: Double = 15.0,
    val radiusKm: Int = 25,
    val frequencyMhz: Double = LosAnalyzer.DEFAULT_LORA_US_FREQ_MHZ,
    val txPowerDbm: Double = RelayBridgeEngine.DEFAULT_TX_POWER_DBM,
    val antennaGainDbi: Double = RelayBridgeEngine.DEFAULT_ANTENNA_GAIN_DBI,
    val sensitivityDbm: Double = RelayBridgeEngine.DEFAULT_SENSITIVITY_DBM,
    val rxPowerDbm: Double = txPowerDbm,
    val rxAntennaGainDbi: Double = antennaGainDbi,
    val rxSensitivityDbm: Double = sensitivityDbm,
    val relayPowerDbm: Double = txPowerDbm,
    val relayAntennaGainDbi: Double = antennaGainDbi,
    val relaySensitivityDbm: Double = sensitivityDbm
) {
    val txAntennaGainDbi: Double get() = antennaGainDbi
    val txSensitivityDbm: Double get() = sensitivityDbm
}

enum class BridgeLinkSegment(val label: String) {
    DIRECT("Direct TX–RX"),
    TX_TO_RELAY("TX → Relay"),
    RELAY_TO_RX("Relay → RX")
}

data class HopObstructionPoint(
    val latitude: Double,
    val longitude: Double,
    val distanceFromOriginKm: Double,
    val terrainElevationMeters: Double,
    val earthBulgeMeters: Double,
    val effectiveTerrainMeters: Double,
    val rayElevationMeters: Double,
    val clearanceMeters: Double,
    val fresnelRatio: Double,
    val status: ViewshedPointStatus
)

data class BridgeObstructionItem(
    val id: String,
    val segment: BridgeLinkSegment,
    val rankWithinSegment: Int,
    val point: HopObstructionPoint
)

enum class BridgeProfileMode(val label: String) {
    DIRECT("Direct TX–RX"),
    RELAY("TX → Relay → RX")
}

data class BridgeProfileSample(
    val latitude: Double,
    val longitude: Double,
    val distanceFromStartKm: Double,
    val terrainElevationMeters: Double,
    val earthBulgeMeters: Double,
    val effectiveTerrainMeters: Double,
    val rayElevationMeters: Double,
    val fresnel60LowerMeters: Double,
    val fresnel60UpperMeters: Double,
    val clearanceMeters: Double,
    val fresnelRatio: Double,
    val status: ViewshedPointStatus,
    val segment: BridgeLinkSegment = BridgeLinkSegment.DIRECT
)

data class BridgeCompositeProfile(
    val mode: BridgeProfileMode,
    val samples: List<BridgeProfileSample>,
    val totalDistanceKm: Double,
    val relayHingeIndex: Int?,
    val relayHingeDistanceKm: Double?,
    val relayGroundMeters: Double?,
    val relayAntennaMeters: Double?,
    val txAntennaMeters: Double,
    val rxAntennaMeters: Double,
    val visibleObstructions: List<BridgeObstructionItem>
)

data class BridgeHopEvaluation(
    val distanceKm: Double,
    val status: ViewshedPointStatus,
    val predictedRssiDbm: Double,
    val linkMarginDb: Double,
    val worstClearanceMeters: Double,
    val minFresnelRatio: Double,
    val worstObstruction: HopObstructionPoint? = null,
    val obstructions: List<HopObstructionPoint> = emptyList(),
    val profileSamples: List<BridgeProfileSample> = emptyList(),
    val reverseRssiDbm: Double = predictedRssiDbm,
    val reverseLinkMarginDb: Double = linkMarginDb,
    val bottleneckLinkMarginDb: Double = min(linkMarginDb, reverseLinkMarginDb)
) {
    val forwardPasses: Boolean get() = linkMarginDb >= 0.0
    val reversePasses: Boolean get() = reverseLinkMarginDb >= 0.0
    val isBidirectionalViable: Boolean get() = forwardPasses && reversePasses
}

enum class BridgeRankTier {
    MUTUAL_CLEAR_LOS,
    GRAZING_BRIDGE,
    OBSTRUCTED_FALLBACK
}

data class RelayCandidate(
    val rank: Int,
    val latitude: Double,
    val longitude: Double,
    val groundElevationMeters: Double,
    val txHop: BridgeHopEvaluation,
    val rxHop: BridgeHopEvaluation,
    val worstLinkMarginDb: Double,
    val minFresnelRatio: Double,
    val tier: BridgeRankTier,
    val inViewshedOverlap: Boolean
)

data class BridgeResult(
    val params: BridgeParams,
    val txGroundElevationMeters: Double,
    val rxGroundElevationMeters: Double,
    val directDistanceKm: Double,
    val directBaseline: BridgeHopEvaluation,
    val bitmap: Bitmap,
    val northLat: Double,
    val southLat: Double,
    val westLon: Double,
    val eastLon: Double,
    val txCoveredAreaKm2: Double,
    val rxCoveredAreaKm2: Double,
    val mutualOverlapAreaKm2: Double,
    val candidatesEvaluated: Int,
    val topCandidates: List<RelayCandidate>,
    val tileCountUsed: Int,
    val computeTimeMs: Long,
    val txField: PolarVisibilityField? = null,
    val rxField: PolarVisibilityField? = null
)

object RelayBridgeEngine {
    private const val EARTH_RADIUS_KM = 6371.0088
    private const val K_FACTOR = 4.0 / 3.0
    private const val EFFECTIVE_EARTH_RADIUS_KM = EARTH_RADIUS_KM * K_FACTOR
    private const val DIFFRACTION_MARGIN_METERS = 6.0
    private const val RX_NOMINAL_AGL_METERS = 2.0
    private const val HOP_SAMPLE_COUNT = 64
    private const val SEED_SCAN_SIZE = 512
    const val RASTER_SIZE = 1024

    const val DEFAULT_TX_POWER_DBM = 22.0
    const val DEFAULT_ANTENNA_GAIN_DBI = 3.0
    const val DEFAULT_SENSITIVITY_DBM = -130.0

    fun computeBridgeBounds(params: BridgeParams): DoubleArray {
        val radiusKm = params.radiusKm.coerceIn(1, ViewshedEngine.MAX_SUPPORTED_RADIUS_KM).toDouble()
        val latPad = (radiusKm / 111.12) * 1.04
        val txCos = cos(Math.toRadians(params.txLat)).coerceAtLeast(0.15)
        val rxCos = cos(Math.toRadians(params.rxLat)).coerceAtLeast(0.15)
        val txLonPad = (radiusKm / (111.32 * txCos)) * 1.04
        val rxLonPad = (radiusKm / (111.32 * rxCos)) * 1.04

        val north = (max(params.txLat, params.rxLat) + latPad).coerceAtMost(85.0)
        val south = (min(params.txLat, params.rxLat) - latPad).coerceAtLeast(-85.0)
        val west = min(params.txLon - txLonPad, params.rxLon - rxLonPad)
        val east = max(params.txLon + txLonPad, params.rxLon + rxLonPad)
        return doubleArrayOf(north, south, west, east)
    }

    fun computeBridgeAnalysis(
        params: BridgeParams,
        grid: TerrariumTileGrid
    ): BridgeResult {
        val startMs = System.currentTimeMillis()
        val freqMhz = params.frequencyMhz.coerceAtLeast(1.0)
        val freqGhz = freqMhz / 1000.0

        val txGroundElev = grid.sampleElevation(params.txLat, params.txLon) ?: 0.0
        val rxGroundElev = grid.sampleElevation(params.rxLat, params.rxLon) ?: 0.0
        val directDistKm = MeshGeoUtils.haversineKm(params.txLat, params.txLon, params.rxLat, params.rxLon)

        val directBaseline = evaluateHop(
            latA = params.txLat,
            lonA = params.txLon,
            heightAMeters = params.txAntennaHeightMeters,
            latB = params.rxLat,
            lonB = params.rxLon,
            heightBMeters = params.rxAntennaHeightMeters,
            freqMhz = freqMhz,
            freqGhz = freqGhz,
            eirpDbm = params.txPowerDbm + params.txAntennaGainDbi + params.rxAntennaGainDbi,
            sensitivityDbm = params.rxSensitivityDbm,
            grid = grid,
            collectObstructionPeaks = true,
            reverseEirpDbm = params.rxPowerDbm + params.rxAntennaGainDbi + params.txAntennaGainDbi,
            reverseSensitivityDbm = params.txSensitivityDbm
        )

        val clampedRadiusKm = params.radiusKm.coerceIn(1, ViewshedEngine.MAX_SUPPORTED_RADIUS_KM).toDouble()

        val txBidirectionalThreshold = max(params.relaySensitivityDbm, params.txSensitivityDbm)
        val txBidirectionalEffectiveEirp = min(
            params.txPowerDbm - params.relaySensitivityDbm,
            params.relayPowerDbm - params.txSensitivityDbm
        ) + params.txAntennaGainDbi + params.relayAntennaGainDbi + txBidirectionalThreshold

        val rxBidirectionalThreshold = max(params.relaySensitivityDbm, params.rxSensitivityDbm)
        val rxBidirectionalEffectiveEirp = min(
            params.rxPowerDbm - params.relaySensitivityDbm,
            params.relayPowerDbm - params.rxSensitivityDbm
        ) + params.rxAntennaGainDbi + params.relayAntennaGainDbi + rxBidirectionalThreshold

        val txField = computePolarField(
            originLat = params.txLat,
            originLon = params.txLon,
            antennaHeightMeters = params.txAntennaHeightMeters,
            radiusKm = clampedRadiusKm,
            freqMhz = freqMhz,
            freqGhz = freqGhz,
            eirpDbm = txBidirectionalEffectiveEirp,
            sensitivityDbm = txBidirectionalThreshold,
            grid = grid
        )

        val rxField = computePolarField(
            originLat = params.rxLat,
            originLon = params.rxLon,
            antennaHeightMeters = params.rxAntennaHeightMeters,
            radiusKm = clampedRadiusKm,
            freqMhz = freqMhz,
            freqGhz = freqGhz,
            eirpDbm = rxBidirectionalEffectiveEirp,
            sensitivityDbm = rxBidirectionalThreshold,
            grid = grid
        )

        val bounds = computeBridgeBounds(params)
        val northLat = bounds[0]
        val southLat = bounds[1]
        val westLon = bounds[2]
        val eastLon = bounds[3]

        // Collect mutual-overlap seeds & area on a fast scan grid, then render crisp 1024px bitmap
        val mercNorth = latToMercY(northLat)
        val mercSouth = latToMercY(southLat)
        val mercSpan = mercSouth - mercNorth
        val lonSpan = eastLon - westLon

        val midLatCos = cos(Math.toRadians((northLat + southLat) * 0.5)).coerceAtLeast(0.15)
        val totalHeightKm = (northLat - southLat) * 111.132
        val totalWidthKm = lonSpan * 111.320 * midLatCos
        val pixelAreaKm2 = (totalWidthKm / SEED_SCAN_SIZE) * (totalHeightKm / SEED_SCAN_SIZE)

        var overlapPixels = 0
        val overlapSeeds = ArrayList<Pair<Double, Double>>(512)

        for (py in 0 until SEED_SCAN_SIZE) {
            val yFrac = (py + 0.5) / SEED_SCAN_SIZE.toDouble()
            val lat = mercYToLat(mercNorth + yFrac * mercSpan)
            for (px in 0 until SEED_SCAN_SIZE) {
                val xFrac = (px + 0.5) / SEED_SCAN_SIZE.toDouble()
                val lon = westLon + xFrac * lonSpan

                val txSt = txField.statusAt(lat, lon)
                if (txSt == ViewshedPointStatus.OBSTRUCTED.code) continue
                val rxSt = rxField.statusAt(lat, lon)
                if (rxSt == ViewshedPointStatus.OBSTRUCTED.code) continue

                overlapPixels++
                if (py % 8 == 0 && px % 8 == 0) {
                    overlapSeeds.add(lat to lon)
                }
            }
        }

        val bitmap = PolarVisibilityField.rasterizeDualBridgeSubRegion(
            txField = txField,
            rxField = rxField,
            sensitivityDbm = max(txBidirectionalThreshold, rxBidirectionalThreshold),
            renderNorthLat = northLat,
            renderSouthLat = southLat,
            renderWestLon = westLon,
            renderEastLon = eastLon,
            width = RASTER_SIZE,
            height = RASTER_SIZE
        )
        val overlapAreaKm2 = overlapPixels * pixelAreaKm2

        // Build candidate search grid: Mutual viewshed overlap seeds + Elliptical corridor grid
        val candidateCoords = LinkedHashMap<Long, Triple<Double, Double, Boolean>>()
        fun addCandidate(lat: Double, lon: Double, inOverlap: Boolean) {
            val qLat = (lat * 2000.0).roundToInt().toLong()
            val qLon = (lon * 2000.0).roundToInt().toLong()
            val key = (qLat shl 32) xor (qLon and 0xFFFFFFFFL)
            val prev = candidateCoords[key]
            if (prev == null || (inOverlap && !prev.third)) {
                candidateCoords[key] = Triple(lat, lon, inOverlap)
            }
        }

        overlapSeeds.forEach { (lat, lon) -> addCandidate(lat, lon, true) }

        // Rotated elliptical corridor grid spanning between TX and RX
        val midLat = (params.txLat + params.rxLat) * 0.5
        val midLon = (params.txLon + params.rxLon) * 0.5
        val corridorCos = cos(Math.toRadians(midLat)).coerceAtLeast(0.15)
        val dxSpanKm = (params.rxLon - params.txLon) * 111.320 * corridorCos
        val dySpanKm = (params.rxLat - params.txLat) * 111.132
        val axisLenKm = sqrt(dxSpanKm * dxSpanKm + dySpanKm * dySpanKm).coerceAtLeast(1.0)
        val ux = dxSpanKm / axisLenKm
        val uy = dySpanKm / axisLenKm
        val pxVec = -uy
        val pyVec = ux

        val halfLongKm = max(clampedRadiusKm * 0.65, axisLenKm * 0.62)
        val halfCrossKm = max(3.5, min(clampedRadiusKm, axisLenKm * 0.42 + 2.5))
        val ellipseLimitKm = max(axisLenKm * 1.45, axisLenKm + 6.0)

        val uSteps = 28
        val vSteps = 18
        for (ui in 0..uSteps) {
            val uFrac = -1.0 + (2.0 * ui) / uSteps
            val alongKm = uFrac * halfLongKm
            for (vi in 0..vSteps) {
                val vFrac = -1.0 + (2.0 * vi) / vSteps
                val crossKm = vFrac * halfCrossKm
                val gxKm = alongKm * ux + crossKm * pxVec
                val gyKm = alongKm * uy + crossKm * pyVec
                val cLat = midLat + gyKm / 111.132
                val cLon = midLon + gxKm / (111.320 * corridorCos)

                val dTx = MeshGeoUtils.haversineKm(params.txLat, params.txLon, cLat, cLon)
                val dRx = MeshGeoUtils.haversineKm(cLat, cLon, params.rxLat, params.rxLon)
                if (dTx < 0.25 || dRx < 0.25) continue
                if (dTx + dRx > ellipseLimitKm) continue

                val txVis = txField.statusAt(cLat, cLon) != ViewshedPointStatus.OBSTRUCTED.code
                val rxVis = rxField.statusAt(cLat, cLon) != ViewshedPointStatus.OBSTRUCTED.code
                addCandidate(cLat, cLon, txVis && rxVis)
            }
        }

        // Pre-sort candidates by (inOverlap + terrain elevation) and evaluate top 650 with 2-hop LoS math
        val prioritized = candidateCoords.values
            .map { (lat, lon, inOverlap) ->
                val elev = grid.sampleElevation(lat, lon) ?: ((txGroundElev + rxGroundElev) * 0.5)
                val priority = (if (inOverlap) 1000.0 else 0.0) + elev
                Triple(lat, lon, inOverlap) to Pair(elev, priority)
            }
            .sortedByDescending { it.second.second }
            .take(650)

        val txToRelayFwdEirp = params.txPowerDbm + params.txAntennaGainDbi + params.relayAntennaGainDbi
        val relayToTxRevEirp = params.relayPowerDbm + params.relayAntennaGainDbi + params.txAntennaGainDbi
        val relayToRxFwdEirp = params.relayPowerDbm + params.relayAntennaGainDbi + params.rxAntennaGainDbi
        val rxToRelayRevEirp = params.rxPowerDbm + params.rxAntennaGainDbi + params.relayAntennaGainDbi

        val evaluated = prioritized.map { (coord, elevPair) ->
            val (cLat, cLon, inOverlap) = coord
            val groundElev = elevPair.first
            val txHop = evaluateHop(
                latA = params.txLat,
                lonA = params.txLon,
                heightAMeters = params.txAntennaHeightMeters,
                latB = cLat,
                lonB = cLon,
                heightBMeters = params.relayAntennaHeightMeters,
                freqMhz = freqMhz,
                freqGhz = freqGhz,
                eirpDbm = txToRelayFwdEirp,
                sensitivityDbm = params.relaySensitivityDbm,
                grid = grid,
                collectObstructionPeaks = false,
                reverseEirpDbm = relayToTxRevEirp,
                reverseSensitivityDbm = params.txSensitivityDbm
            )
            val rxHop = evaluateHop(
                latA = cLat,
                lonA = cLon,
                heightAMeters = params.relayAntennaHeightMeters,
                latB = params.rxLat,
                lonB = params.rxLon,
                heightBMeters = params.rxAntennaHeightMeters,
                freqMhz = freqMhz,
                freqGhz = freqGhz,
                eirpDbm = relayToRxFwdEirp,
                sensitivityDbm = params.rxSensitivityDbm,
                grid = grid,
                collectObstructionPeaks = false,
                reverseEirpDbm = rxToRelayRevEirp,
                reverseSensitivityDbm = params.relaySensitivityDbm
            )

            val worstMargin = min(txHop.bottleneckLinkMarginDb, rxHop.bottleneckLinkMarginDb)
            val minFresnel = min(txHop.minFresnelRatio, rxHop.minFresnelRatio)
            val tier = when {
                txHop.status == ViewshedPointStatus.CLEAR &&
                    rxHop.status == ViewshedPointStatus.CLEAR &&
                    worstMargin >= 0.0 -> BridgeRankTier.MUTUAL_CLEAR_LOS
                txHop.status != ViewshedPointStatus.OBSTRUCTED &&
                    rxHop.status != ViewshedPointStatus.OBSTRUCTED &&
                    worstMargin >= 0.0 -> BridgeRankTier.GRAZING_BRIDGE
                else -> BridgeRankTier.OBSTRUCTED_FALLBACK
            }

            RelayCandidate(
                rank = 0,
                latitude = cLat,
                longitude = cLon,
                groundElevationMeters = groundElev,
                txHop = txHop,
                rxHop = rxHop,
                worstLinkMarginDb = worstMargin,
                minFresnelRatio = minFresnel,
                tier = tier,
                inViewshedOverlap = inOverlap
            )
        }.sortedWith(
            compareBy<RelayCandidate> { it.tier.ordinal }
                .thenByDescending { it.worstLinkMarginDb }
                .thenByDescending { it.minFresnelRatio }
        )

        // Spatial de-clustering so Top 3 recommendations represent distinct ridge/site alternatives
        val minSeparationKm = max(0.8, directDistKm * 0.08)
        val top3 = mutableListOf<RelayCandidate>()
        for (cand in evaluated) {
            if (top3.size >= 3) break
            val farEnough = top3.all { chosen ->
                MeshGeoUtils.haversineKm(chosen.latitude, chosen.longitude, cand.latitude, cand.longitude) >= minSeparationKm
            }
            if (farEnough) {
                top3.add(cand.copy(rank = top3.size + 1))
            }
        }
        if (top3.size < 3) {
            for (cand in evaluated) {
                if (top3.size >= 3) break
                val alreadyAdded = top3.any {
                    MeshGeoUtils.haversineKm(it.latitude, it.longitude, cand.latitude, cand.longitude) < 0.1
                }
                if (!alreadyAdded) {
                    top3.add(cand.copy(rank = top3.size + 1))
                }
            }
        }

        val detailedTop3 = top3.map { cand ->
            val detailedTx = evaluateHop(
                latA = params.txLat,
                lonA = params.txLon,
                heightAMeters = params.txAntennaHeightMeters,
                latB = cand.latitude,
                lonB = cand.longitude,
                heightBMeters = params.relayAntennaHeightMeters,
                freqMhz = freqMhz,
                freqGhz = freqGhz,
                eirpDbm = txToRelayFwdEirp,
                sensitivityDbm = params.relaySensitivityDbm,
                grid = grid,
                collectObstructionPeaks = true,
                reverseEirpDbm = relayToTxRevEirp,
                reverseSensitivityDbm = params.txSensitivityDbm
            )
            val detailedRx = evaluateHop(
                latA = cand.latitude,
                lonA = cand.longitude,
                heightAMeters = params.relayAntennaHeightMeters,
                latB = params.rxLat,
                lonB = params.rxLon,
                heightBMeters = params.rxAntennaHeightMeters,
                freqMhz = freqMhz,
                freqGhz = freqGhz,
                eirpDbm = relayToRxFwdEirp,
                sensitivityDbm = params.rxSensitivityDbm,
                grid = grid,
                collectObstructionPeaks = true,
                reverseEirpDbm = rxToRelayRevEirp,
                reverseSensitivityDbm = params.relaySensitivityDbm
            )
            cand.copy(txHop = detailedTx, rxHop = detailedRx)
        }

        return BridgeResult(
            params = params,
            txGroundElevationMeters = txGroundElev,
            rxGroundElevationMeters = rxGroundElev,
            directDistanceKm = directDistKm,
            directBaseline = directBaseline,
            bitmap = bitmap,
            northLat = northLat,
            southLat = southLat,
            westLon = westLon,
            eastLon = eastLon,
            txCoveredAreaKm2 = txField.coveredAreaKm2,
            rxCoveredAreaKm2 = rxField.coveredAreaKm2,
            mutualOverlapAreaKm2 = overlapAreaKm2,
            candidatesEvaluated = evaluated.size,
            topCandidates = detailedTop3,
            tileCountUsed = grid.tileCount,
            computeTimeMs = System.currentTimeMillis() - startMs,
            txField = txField,
            rxField = rxField
        )
    }

    fun recalculateHopsOnGrid(
        existingResult: BridgeResult,
        updatedParams: BridgeParams,
        grid: TerrariumTileGrid
    ): BridgeResult {
        val startMs = System.currentTimeMillis()
        val freqMhz = updatedParams.frequencyMhz.coerceAtLeast(1.0)
        val freqGhz = freqMhz / 1000.0

        val updatedDirectBaseline = evaluateHop(
            latA = updatedParams.txLat,
            lonA = updatedParams.txLon,
            heightAMeters = updatedParams.txAntennaHeightMeters,
            latB = updatedParams.rxLat,
            lonB = updatedParams.rxLon,
            heightBMeters = updatedParams.rxAntennaHeightMeters,
            freqMhz = freqMhz,
            freqGhz = freqGhz,
            eirpDbm = updatedParams.txPowerDbm + updatedParams.txAntennaGainDbi + updatedParams.rxAntennaGainDbi,
            sensitivityDbm = updatedParams.rxSensitivityDbm,
            grid = grid,
            collectObstructionPeaks = true,
            reverseEirpDbm = updatedParams.rxPowerDbm + updatedParams.rxAntennaGainDbi + updatedParams.txAntennaGainDbi,
            reverseSensitivityDbm = updatedParams.txSensitivityDbm
        )

        val txToRelayFwdEirp = updatedParams.txPowerDbm + updatedParams.txAntennaGainDbi + updatedParams.relayAntennaGainDbi
        val relayToTxRevEirp = updatedParams.relayPowerDbm + updatedParams.relayAntennaGainDbi + updatedParams.txAntennaGainDbi
        val relayToRxFwdEirp = updatedParams.relayPowerDbm + updatedParams.relayAntennaGainDbi + updatedParams.rxAntennaGainDbi
        val rxToRelayRevEirp = updatedParams.rxPowerDbm + updatedParams.rxAntennaGainDbi + updatedParams.relayAntennaGainDbi

        val updatedCandidates = existingResult.topCandidates.map { cand ->
            val updatedTxHop = evaluateHop(
                latA = updatedParams.txLat,
                lonA = updatedParams.txLon,
                heightAMeters = updatedParams.txAntennaHeightMeters,
                latB = cand.latitude,
                lonB = cand.longitude,
                heightBMeters = updatedParams.relayAntennaHeightMeters,
                freqMhz = freqMhz,
                freqGhz = freqGhz,
                eirpDbm = txToRelayFwdEirp,
                sensitivityDbm = updatedParams.relaySensitivityDbm,
                grid = grid,
                collectObstructionPeaks = true,
                reverseEirpDbm = relayToTxRevEirp,
                reverseSensitivityDbm = updatedParams.txSensitivityDbm
            )
            val updatedRxHop = evaluateHop(
                latA = cand.latitude,
                lonA = cand.longitude,
                heightAMeters = updatedParams.relayAntennaHeightMeters,
                latB = updatedParams.rxLat,
                lonB = updatedParams.rxLon,
                heightBMeters = updatedParams.rxAntennaHeightMeters,
                freqMhz = freqMhz,
                freqGhz = freqGhz,
                eirpDbm = relayToRxFwdEirp,
                sensitivityDbm = updatedParams.rxSensitivityDbm,
                grid = grid,
                collectObstructionPeaks = true,
                reverseEirpDbm = rxToRelayRevEirp,
                reverseSensitivityDbm = updatedParams.relaySensitivityDbm
            )
            val worstMargin = min(updatedTxHop.bottleneckLinkMarginDb, updatedRxHop.bottleneckLinkMarginDb)
            val minFresnel = min(updatedTxHop.minFresnelRatio, updatedRxHop.minFresnelRatio)
            val tier = when {
                updatedTxHop.status == ViewshedPointStatus.CLEAR &&
                    updatedRxHop.status == ViewshedPointStatus.CLEAR &&
                    worstMargin >= 0.0 -> BridgeRankTier.MUTUAL_CLEAR_LOS
                updatedTxHop.status != ViewshedPointStatus.OBSTRUCTED &&
                    updatedRxHop.status != ViewshedPointStatus.OBSTRUCTED &&
                    worstMargin >= 0.0 -> BridgeRankTier.GRAZING_BRIDGE
                else -> BridgeRankTier.OBSTRUCTED_FALLBACK
            }
            cand.copy(
                txHop = updatedTxHop,
                rxHop = updatedRxHop,
                worstLinkMarginDb = worstMargin,
                minFresnelRatio = minFresnel,
                tier = tier
            )
        }

        return existingResult.copy(
            params = updatedParams,
            directBaseline = updatedDirectBaseline,
            topCandidates = updatedCandidates,
            computeTimeMs = System.currentTimeMillis() - startMs
        )
    }

    fun buildObstructionList(
        result: BridgeResult,
        selectedCandidateIndex: Int
    ): List<BridgeObstructionItem> {
        val candidate = result.topCandidates.getOrNull(selectedCandidateIndex)
            ?: result.topCandidates.firstOrNull()

        val collected = mutableListOf<BridgeObstructionItem>()

        fun appendSegment(segment: BridgeLinkSegment, hop: BridgeHopEvaluation) {
            val points = if (hop.obstructions.isNotEmpty()) {
                hop.obstructions
            } else {
                listOfNotNull(hop.worstObstruction)
            }
            points.forEachIndexed { idx, pt ->
                val rankWithin = idx + 1
                collected.add(
                    BridgeObstructionItem(
                        id = "${segment.name}_$rankWithin",
                        segment = segment,
                        rankWithinSegment = rankWithin,
                        point = pt
                    )
                )
            }
        }

        appendSegment(BridgeLinkSegment.DIRECT, result.directBaseline)
        if (candidate != null) {
            appendSegment(BridgeLinkSegment.TX_TO_RELAY, candidate.txHop)
            appendSegment(BridgeLinkSegment.RELAY_TO_RX, candidate.rxHop)
        }

        return collected.sortedWith(
            compareBy<BridgeObstructionItem> {
                when (it.point.status) {
                    ViewshedPointStatus.OBSTRUCTED -> 0
                    ViewshedPointStatus.GRAZING -> 1
                    ViewshedPointStatus.CLEAR -> 2
                }
            }
                .thenBy { it.point.fresnelRatio }
                .thenBy { it.point.clearanceMeters }
                .thenBy { it.segment.ordinal }
        )
    }

    fun buildDirectObstructionList(directHop: BridgeHopEvaluation?): List<BridgeObstructionItem> {
        val hop = directHop ?: return emptyList()
        val points = if (hop.obstructions.isNotEmpty()) {
            hop.obstructions
        } else {
            listOfNotNull(hop.worstObstruction)
        }
        return points.mapIndexed { idx, pt ->
            val rankWithin = idx + 1
            BridgeObstructionItem(
                id = "${BridgeLinkSegment.DIRECT.name}_$rankWithin",
                segment = BridgeLinkSegment.DIRECT,
                rankWithinSegment = rankWithin,
                point = pt
            )
        }
    }

    fun buildCompositeProfile(
        mode: BridgeProfileMode,
        directHop: BridgeHopEvaluation?,
        result: BridgeResult?,
        selectedCandidateIndex: Int
    ): BridgeCompositeProfile? {
        val candidate = result?.topCandidates?.getOrNull(selectedCandidateIndex)
            ?: result?.topCandidates?.firstOrNull()
        val effectiveDirect = result?.directBaseline ?: directHop

        if (mode == BridgeProfileMode.RELAY && candidate != null) {
            val txSamples = candidate.txHop.profileSamples
            val rxSamples = candidate.rxHop.profileSamples
            if (txSamples.size >= 2 && rxSamples.size >= 2) {
                val txDist = candidate.txHop.distanceKm
                val combined = ArrayList<BridgeProfileSample>(txSamples.size + rxSamples.size)
                txSamples.forEach { sample ->
                    combined.add(sample.copy(segment = BridgeLinkSegment.TX_TO_RELAY))
                }
                val hingeIndex = combined.lastIndex
                val relayGround = txSamples.last().terrainElevationMeters
                val relayAnt = txSamples.last().rayElevationMeters
                rxSamples.forEachIndexed { idx, sample ->
                    if (idx == 0) return@forEachIndexed
                    combined.add(
                        sample.copy(
                            distanceFromStartKm = txDist + sample.distanceFromStartKm,
                            segment = BridgeLinkSegment.RELAY_TO_RX
                        )
                    )
                }
                val relayObs = buildObstructionList(result!!, selectedCandidateIndex)
                    .filter { it.segment != BridgeLinkSegment.DIRECT }
                return BridgeCompositeProfile(
                    mode = BridgeProfileMode.RELAY,
                    samples = combined,
                    totalDistanceKm = txDist + candidate.rxHop.distanceKm,
                    relayHingeIndex = hingeIndex,
                    relayHingeDistanceKm = txDist,
                    relayGroundMeters = relayGround,
                    relayAntennaMeters = relayAnt,
                    txAntennaMeters = combined.first().rayElevationMeters,
                    rxAntennaMeters = combined.last().rayElevationMeters,
                    visibleObstructions = relayObs
                )
            }
        }

        val fallbackDirect = effectiveDirect ?: return null
        val samples = fallbackDirect.profileSamples
        if (samples.size < 2) return null
        val directObs = if (result != null) {
            buildObstructionList(result, selectedCandidateIndex).filter { it.segment == BridgeLinkSegment.DIRECT }
        } else {
            buildDirectObstructionList(fallbackDirect)
        }
        return BridgeCompositeProfile(
            mode = BridgeProfileMode.DIRECT,
            samples = samples,
            totalDistanceKm = fallbackDirect.distanceKm,
            relayHingeIndex = null,
            relayHingeDistanceKm = null,
            relayGroundMeters = null,
            relayAntennaMeters = null,
            txAntennaMeters = samples.first().rayElevationMeters,
            rxAntennaMeters = samples.last().rayElevationMeters,
            visibleObstructions = directObs
        )
    }

    fun evaluateHop(
        latA: Double,
        lonA: Double,
        heightAMeters: Double,
        latB: Double,
        lonB: Double,
        heightBMeters: Double,
        freqMhz: Double,
        freqGhz: Double,
        eirpDbm: Double,
        sensitivityDbm: Double,
        grid: TerrariumTileGrid,
        collectObstructionPeaks: Boolean = true,
        reverseEirpDbm: Double? = null,
        reverseSensitivityDbm: Double? = null
    ): BridgeHopEvaluation {
        val totalKm = MeshGeoUtils.haversineKm(latA, lonA, latB, lonB).coerceAtLeast(0.01)
        val elevA = grid.sampleElevation(latA, lonA) ?: 0.0
        val elevB = grid.sampleElevation(latB, lonB) ?: 0.0
        val antA = elevA + heightAMeters.coerceAtLeast(0.5)
        val antB = elevB + heightBMeters.coerceAtLeast(0.5)

        var worstClearanceMeters = Double.POSITIVE_INFINITY
        var minFresnelRatio = Double.POSITIVE_INFINITY
        var worstPoint: HopObstructionPoint? = null
        val sampledInteriorPoints = if (collectObstructionPeaks) ArrayList<HopObstructionPoint>(128) else null
        val fullProfileSamples = if (collectObstructionPeaks) ArrayList<BridgeProfileSample>(128) else null

        val phi1 = Math.toRadians(latA)
        val lambda1 = Math.toRadians(lonA)
        val phi2 = Math.toRadians(latB)
        val lambda2 = Math.toRadians(lonB)

        val dPhi = phi2 - phi1
        val dLambda = lambda2 - lambda1
        val haversineA = sin(dPhi / 2.0) * sin(dPhi / 2.0) +
            cos(phi1) * cos(phi2) * sin(dLambda / 2.0) * sin(dLambda / 2.0)
        val delta = 2.0 * atan2(sqrt(haversineA), sqrt(max(0.0, 1.0 - haversineA)))

        val minSamples = if (collectObstructionPeaks) 96 else HOP_SAMPLE_COUNT
        val sampleCount = max(minSamples, (totalKm * 3.0).roundToInt()).coerceIn(minSamples, 128)
        val startIndex = if (collectObstructionPeaks) 0 else 1
        val endExclusive = if (collectObstructionPeaks) sampleCount else sampleCount - 1

        for (i in startIndex until endExclusive) {
            val f = i.toDouble() / (sampleCount - 1)
            val sLat: Double
            val sLon: Double
            if (delta < 1e-7) {
                sLat = latA + (latB - latA) * f
                sLon = lonA + (lonB - lonA) * f
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

            val isEndpoint = i == 0 || i == sampleCount - 1
            val rawTerrain = when (i) {
                0 -> elevA
                sampleCount - 1 -> elevB
                else -> grid.sampleElevation(sLat, sLon) ?: elevA
            }
            val d1Km = f * totalKm
            val d2Km = (1.0 - f) * totalKm
            val bulgeM = ((d1Km * d2Km) / (2.0 * EFFECTIVE_EARTH_RADIUS_KM)) * 1000.0
            val effTerrain = rawTerrain + bulgeM
            val rayElev = antA + (antB - antA) * f
            val clearance = rayElev - effTerrain
            val f1 = if (d1Km > 0.0 && d2Km > 0.0) {
                17.32 * sqrt((d1Km * d2Km) / (freqGhz * totalKm))
            } else {
                0.0
            }
            val fresnel60Radius = 0.60 * f1
            val ratio = if (f1 > 0.001) clearance / f1 else 1.0
            val pointStatus = if (isEndpoint) {
                ViewshedPointStatus.CLEAR
            } else {
                when {
                    clearance < 0.0 -> ViewshedPointStatus.OBSTRUCTED
                    ratio < 0.60 -> ViewshedPointStatus.GRAZING
                    else -> ViewshedPointStatus.CLEAR
                }
            }

            if (fullProfileSamples != null) {
                fullProfileSamples.add(
                    BridgeProfileSample(
                        latitude = sLat,
                        longitude = sLon,
                        distanceFromStartKm = d1Km,
                        terrainElevationMeters = rawTerrain,
                        earthBulgeMeters = bulgeM,
                        effectiveTerrainMeters = effTerrain,
                        rayElevationMeters = rayElev,
                        fresnel60LowerMeters = rayElev - fresnel60Radius,
                        fresnel60UpperMeters = rayElev + fresnel60Radius,
                        clearanceMeters = clearance,
                        fresnelRatio = ratio,
                        status = pointStatus
                    )
                )
            }

            if (!isEndpoint) {
                if (clearance < worstClearanceMeters) worstClearanceMeters = clearance
                if (ratio < minFresnelRatio) minFresnelRatio = ratio

                if (sampledInteriorPoints != null) {
                    val pt = HopObstructionPoint(
                        latitude = sLat,
                        longitude = sLon,
                        distanceFromOriginKm = d1Km,
                        terrainElevationMeters = rawTerrain,
                        earthBulgeMeters = bulgeM,
                        effectiveTerrainMeters = effTerrain,
                        rayElevationMeters = rayElev,
                        clearanceMeters = clearance,
                        fresnelRatio = ratio,
                        status = pointStatus
                    )
                    sampledInteriorPoints.add(pt)
                    val prevWorst = worstPoint
                    if (prevWorst == null || ratio < prevWorst.fresnelRatio || (ratio == prevWorst.fresnelRatio && clearance < prevWorst.clearanceMeters)) {
                        worstPoint = pt
                    }
                }
            }
        }

        if (worstClearanceMeters.isInfinite()) {
            worstClearanceMeters = min(heightAMeters, heightBMeters)
            minFresnelRatio = 1.0
        }

        val status = when {
            worstClearanceMeters < 0.0 -> ViewshedPointStatus.OBSTRUCTED
            minFresnelRatio < 0.60 -> ViewshedPointStatus.GRAZING
            else -> ViewshedPointStatus.CLEAR
        }

        val obstructionPeaks = if (sampledInteriorPoints != null && sampledInteriorPoints.isNotEmpty()) {
            extractDeclusteredObstructionPeaks(sampledInteriorPoints, totalKm, worstPoint)
        } else {
            emptyList()
        }

        val fspl = 20.0 * log10(totalKm) + 20.0 * log10(freqMhz) + 32.44
        val diffractionLossDb = if (status != ViewshedPointStatus.CLEAR) {
            ViewshedEngine.knifeEdgeDiffractionLossDb(minFresnelRatio)
        } else {
            0.0
        }
        val rssi = eirpDbm - fspl - diffractionLossDb
        val margin = rssi - sensitivityDbm
        val reverseRssi = (reverseEirpDbm ?: eirpDbm) - fspl - diffractionLossDb
        val reverseMargin = reverseRssi - (reverseSensitivityDbm ?: sensitivityDbm)
        val bottleneckMargin = min(margin, reverseMargin)

        return BridgeHopEvaluation(
            distanceKm = totalKm,
            status = status,
            predictedRssiDbm = rssi,
            linkMarginDb = margin,
            worstClearanceMeters = worstClearanceMeters,
            minFresnelRatio = minFresnelRatio,
            worstObstruction = worstPoint,
            obstructions = obstructionPeaks,
            profileSamples = fullProfileSamples ?: emptyList(),
            reverseRssiDbm = reverseRssi,
            reverseLinkMarginDb = reverseMargin,
            bottleneckLinkMarginDb = bottleneckMargin
        )
    }

    private fun extractDeclusteredObstructionPeaks(
        samples: List<HopObstructionPoint>,
        totalKm: Double,
        overallWorst: HopObstructionPoint?
    ): List<HopObstructionPoint> {
        val localMinima = mutableListOf<HopObstructionPoint>()
        for (i in samples.indices) {
            val cur = samples[i]
            if (cur.status == ViewshedPointStatus.CLEAR) continue
            val prevRatio = if (i > 0) samples[i - 1].fresnelRatio else Double.POSITIVE_INFINITY
            val nextRatio = if (i < samples.lastIndex) samples[i + 1].fresnelRatio else Double.POSITIVE_INFINITY
            if (cur.fresnelRatio <= prevRatio && cur.fresnelRatio <= nextRatio) {
                localMinima.add(cur)
            }
        }
        if (localMinima.isEmpty()) {
            val nonClearWorst = overallWorst?.takeIf { it.status != ViewshedPointStatus.CLEAR }
            return listOfNotNull(nonClearWorst)
        }

        val sorted = localMinima.sortedWith(
            compareBy<HopObstructionPoint> { it.fresnelRatio }
                .thenBy { it.clearanceMeters }
        )
        val minSeparationKm = max(0.35, totalKm * 0.05)
        val peaks = mutableListOf<HopObstructionPoint>()
        for (candidate in sorted) {
            if (peaks.size >= 5) break
            val farEnough = peaks.all { chosen ->
                kotlin.math.abs(chosen.distanceFromOriginKm - candidate.distanceFromOriginKm) >= minSeparationKm
            }
            if (farEnough) {
                peaks.add(candidate)
            }
        }
        return peaks
    }

    private fun computePolarField(
        originLat: Double,
        originLon: Double,
        antennaHeightMeters: Double,
        radiusKm: Double,
        freqMhz: Double,
        freqGhz: Double,
        eirpDbm: Double,
        sensitivityDbm: Double,
        grid: TerrariumTileGrid
    ): PolarVisibilityField {
        val stepMeters = ViewshedEngine.fineStepMeters(radiusKm)
        val numSteps = max(10, ((radiusKm * 1000.0) / stepMeters).roundToInt())
        val stepKm = stepMeters / 1000.0
        val originElev = grid.sampleElevation(originLat, originLon) ?: 0.0
        val txElev = originElev + antennaHeightMeters.coerceAtLeast(0.5)

        val statusGrid = ByteArray(ViewshedEngine.RAY_COUNT * numSteps)
        val rssiGrid = FloatArray(ViewshedEngine.RAY_COUNT * numSteps)
        val terrainEffElev = DoubleArray(numSteps)
        val hullDistM = DoubleArray(numSteps + 1)
        val hullEffElev = DoubleArray(numSteps + 1)
        val hullRawElev = DoubleArray(numSteps + 1)

        val lat1Rad = Math.toRadians(originLat)
        val lon1Rad = Math.toRadians(originLon)
        val sinLat1 = sin(lat1Rad)
        val cosLat1 = cos(lat1Rad)
        val dThetaRad = (2.0 * PI) / ViewshedEngine.RAY_COUNT
        var coveredAreaKm2 = 0.0

        for (rIdx in 0 until ViewshedEngine.RAY_COUNT) {
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

                val destLatRad = asin((sinLat1 * cosAng + cosLat1 * sinAng * cosBearing).coerceIn(-1.0, 1.0))
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
                        val isProm = obsRaw > rawElev + 0.5 || obsRaw > originElev + 0.5
                        val isMid = obsDistM >= distM * 0.35 && obsDistM <= distM * 0.65
                        if (!isProm && !isMid) continue

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

                if (terrainSlope > maxTerrainSlope) maxTerrainSlope = terrainSlope
                while (hullSize >= 2) {
                    val ax = hullDistM[hullSize - 2]
                    val ay = hullEffElev[hullSize - 2]
                    val bx = hullDistM[hullSize - 1]
                    val by = hullEffElev[hullSize - 1]
                    val cross = (bx - ax) * (effTerrainElev - ay) - (by - ay) * (distM - ax)
                    if (cross >= 0.0) hullSize-- else break
                }
                hullDistM[hullSize] = distM
                hullEffElev[hullSize] = effTerrainElev
                hullRawElev[hullSize] = rawElev
                hullSize++

                if (pointStatus != ViewshedPointStatus.OBSTRUCTED.code) {
                    val fspl = 20.0 * log10(distKm) + 20.0 * log10(freqMhz) + 32.44
                    val diffractionLossDb = if (pointStatus == ViewshedPointStatus.GRAZING.code) {
                        ViewshedEngine.knifeEdgeDiffractionLossDb(minFresnelRatio)
                    } else {
                        0.0
                    }
                    val rssi = eirpDbm - fspl - diffractionLossDb
                    if (rssi >= sensitivityDbm) {
                        val cellOffset = rIdx * numSteps + sIdx
                        statusGrid[cellOffset] = pointStatus
                        rssiGrid[cellOffset] = rssi.toFloat()
                        coveredAreaKm2 += dThetaRad * distKm * stepKm
                    }
                }
            }
        }

        val latDelta = (radiusKm / 111.12) * 1.01
        val lonDelta = (radiusKm / (111.32 * cosLat1.coerceAtLeast(0.15))) * 1.01
        val northLat = (originLat + latDelta).coerceAtMost(85.0)
        val southLat = (originLat - latDelta).coerceAtLeast(-85.0)
        val westLon = originLon - lonDelta
        val eastLon = originLon + lonDelta

        return PolarVisibilityField(
            originLat = originLat,
            originLon = originLon,
            radiusKm = radiusKm,
            rayCount = ViewshedEngine.RAY_COUNT,
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
    }

    private fun latToMercY(latDeg: Double): Double {
        val latRad = Math.toRadians(latDeg.coerceIn(-85.05112878, 85.05112878))
        return ln(tan(PI / 4.0 + latRad / 2.0))
    }

    private fun mercYToLat(mercY: Double): Double {
        return Math.toDegrees(2.0 * atan2(exp(mercY), 1.0) - PI / 2.0)
    }
}
