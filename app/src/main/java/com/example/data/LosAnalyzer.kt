package com.example.data

import androidx.compose.ui.graphics.Color
import com.example.ui.theme.AlertErrorRed
import com.example.ui.theme.NodeDirectGreen
import com.example.ui.theme.NodeSelfAmber
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class LosStatus(
    val label: String,
    val color: Color,
    val androidColorInt: Int
) {
    CLEAR_LOS(
        label = "Clear LoS",
        color = NodeDirectGreen,
        androidColorInt = 0xFF22C55E.toInt()
    ),
    MARGINAL_GRAZING(
        label = "Marginal / Grazing",
        color = NodeSelfAmber,
        androidColorInt = 0xFFEAB308.toInt()
    ),
    OBSTRUCTED(
        label = "Obstructed",
        color = AlertErrorRed,
        androidColorInt = 0xFFEF4444.toInt()
    )
}

enum class LosElevationSource(val label: String) {
    TERRARIUM_DEM("Terrarium DEM"),
    OPEN_METEO("Open-Meteo"),
    FLAT_FALLBACK("Flat Fallback")
}

enum class RfViabilityTier(
    val label: String,
    val color: Color,
    val androidColorInt: Int
) {
    VIABLE_CLEAR(
        label = "Viable Clear",
        color = NodeDirectGreen,
        androidColorInt = 0xFF22C55E.toInt()
    ),
    MARGINAL_GRAZING(
        label = "Fresnel Grazing",
        color = NodeSelfAmber,
        androidColorInt = 0xFFEAB308.toInt()
    ),
    WEAK_MARGIN(
        label = "Weak Margin",
        color = Color(0xFFF97316),
        androidColorInt = 0xFFF97316.toInt()
    ),
    OBSTRUCTED(
        label = "Terrain Blocked",
        color = AlertErrorRed,
        androidColorInt = 0xFFEF4444.toInt()
    )
}

data class LosEndpoint(
    val latitude: Double,
    val longitude: Double,
    val label: String,
    val shortLabel: String,
    val node: MeshNode? = null
)

data class LosSamplePoint(
    val index: Int,
    val latitude: Double,
    val longitude: Double,
    val distanceFromAKm: Double,
    val rawTerrainMeters: Double,
    val earthBulgeMeters: Double,
    val adjustedTerrainMeters: Double,
    val losRayMeters: Double,
    val fresnelRadiusMeters: Double,
    val fresnel60RadiusMeters: Double,
    val fresnel60UpperMeters: Double,
    val fresnel60LowerMeters: Double,
    val clearanceMeters: Double,
    val clearancePercentFresnel: Double?,
    val fresnel100UpperMeters: Double = losRayMeters + fresnelRadiusMeters,
    val fresnel100LowerMeters: Double = losRayMeters - fresnelRadiusMeters
)

data class LosAnalysisResult(
    val pointA: LosEndpoint,
    val pointB: LosEndpoint,
    val txHeightMeters: Double,
    val rxHeightMeters: Double,
    val frequencyMhz: Double,
    val totalDistanceKm: Double,
    val totalDistanceMiles: Double,
    val fsplDb: Double,
    val status: LosStatus,
    val samples: List<LosSamplePoint>,
    val worstSample: LosSamplePoint,
    val worstClearanceMeters: Double,
    val worstClearancePercentFresnel: Double?,
    val txPowerDbm: Double = RelayBridgeEngine.DEFAULT_TX_POWER_DBM,
    val txGainDbi: Double = RelayBridgeEngine.DEFAULT_ANTENNA_GAIN_DBI,
    val rxGainDbi: Double = RelayBridgeEngine.DEFAULT_ANTENNA_GAIN_DBI,
    val sensitivityDbm: Double = RelayBridgeEngine.DEFAULT_SENSITIVITY_DBM,
    val maxFresnel1RadiusMeters: Double = 0.0,
    val diffractionLossDb: Double = 0.0,
    val totalPathLossDb: Double = fsplDb,
    val predictedRssiDbm: Double = 0.0,
    val linkMarginDb: Double = 0.0,
    val viabilityTier: RfViabilityTier = RfViabilityTier.VIABLE_CLEAR,
    val requiredTxMastDeltaMeters: Double = 0.0,
    val requiredRxMastDeltaMeters: Double = 0.0,
    val requiredEqualMastDeltaMeters: Double = 0.0,
    val elevationSource: LosElevationSource = LosElevationSource.OPEN_METEO
)

object LosAnalyzer {
    private const val EARTH_RADIUS_KM = 6371.0088
    private const val K_FACTOR = 4.0 / 3.0
    private const val EFFECTIVE_EARTH_RADIUS_KM = EARTH_RADIUS_KM * K_FACTOR // ~8494.68 km
    const val DEFAULT_LORA_US_FREQ_MHZ = 906.875
    const val DEFAULT_SAMPLE_COUNT = 100

    fun nodeSightlineKey(node: MeshNode): String = "${node.num}:${node.id}"

    /**
     * Generates [count] evenly spaced coordinates along the great-circle path
     * from [pointA] to [pointB] using spherical linear interpolation.
     */
    fun sampleGreatCirclePath(
        pointA: LosEndpoint,
        pointB: LosEndpoint,
        count: Int = DEFAULT_SAMPLE_COUNT
    ): List<Pair<Double, Double>> {
        require(count >= 2) { "Sample count must be at least 2." }

        val lat1 = Math.toRadians(pointA.latitude)
        val lon1 = Math.toRadians(pointA.longitude)
        val lat2 = Math.toRadians(pointB.latitude)
        val lon2 = Math.toRadians(pointB.longitude)

        val dLat = lat2 - lat1
        val dLon = lon2 - lon1
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        val angularDistance = 2.0 * atan2(sqrt(a), sqrt((1.0 - a).coerceAtLeast(0.0)))

        if (angularDistance < 1e-12) {
            return List(count) { pointA.latitude to pointA.longitude }
        }

        val sinDelta = sin(angularDistance)
        return List(count) { index ->
            val f = index.toDouble() / (count - 1).toDouble()
            val weightA = sin((1.0 - f) * angularDistance) / sinDelta
            val weightB = sin(f * angularDistance) / sinDelta

            val x = weightA * cos(lat1) * cos(lon1) + weightB * cos(lat2) * cos(lon2)
            val y = weightA * cos(lat1) * sin(lon1) + weightB * cos(lat2) * sin(lon2)
            val z = weightA * sin(lat1) + weightB * sin(lat2)

            val lat = Math.toDegrees(atan2(z, sqrt(x * x + y * y)))
            val lon = Math.toDegrees(atan2(y, x))
            lat to lon
        }
    }

    fun analyze(
        pointA: LosEndpoint,
        pointB: LosEndpoint,
        coordinates: List<Pair<Double, Double>>,
        elevationsMeters: List<Double>,
        txHeightMeters: Double,
        rxHeightMeters: Double,
        frequencyMhz: Double,
        txPowerDbm: Double = RelayBridgeEngine.DEFAULT_TX_POWER_DBM,
        txGainDbi: Double = RelayBridgeEngine.DEFAULT_ANTENNA_GAIN_DBI,
        rxGainDbi: Double = RelayBridgeEngine.DEFAULT_ANTENNA_GAIN_DBI,
        sensitivityDbm: Double = RelayBridgeEngine.DEFAULT_SENSITIVITY_DBM,
        elevationSource: LosElevationSource = LosElevationSource.OPEN_METEO
    ): LosAnalysisResult {
        require(coordinates.size >= 2 && coordinates.size == elevationsMeters.size) {
            "Coordinates and elevation samples must match and have at least 2 points."
        }
        val safeTxHeight = txHeightMeters.coerceAtLeast(0.0)
        val safeRxHeight = rxHeightMeters.coerceAtLeast(0.0)
        val safeFreqMhz = frequencyMhz.coerceAtLeast(1.0)
        val freqGhz = safeFreqMhz / 1000.0

        val totalKm = MeshGeoUtils.haversineKm(
            pointA.latitude,
            pointA.longitude,
            pointB.latitude,
            pointB.longitude
        )
        val totalMiles = MeshGeoUtils.kmToMiles(totalKm)

        val fsplDb = if (totalKm > 1e-6) {
            20.0 * log10(totalKm) + 20.0 * log10(safeFreqMhz) + 32.44
        } else {
            0.0
        }

        val antennaAElev = elevationsMeters.first() + safeTxHeight
        val antennaBElev = elevationsMeters.last() + safeRxHeight
        val count = coordinates.size

        var maxFresnel1 = 0.0
        var reqEqualDelta = 0.0
        var reqTxDelta = 0.0
        var reqRxDelta = 0.0

        val samples = coordinates.mapIndexed { index, (lat, lon) ->
            val t = index.toDouble() / (count - 1).toDouble()
            val d1Km = totalKm * t
            val d2Km = totalKm * (1.0 - t)

            // h_bulge = (d1 * d2) / (2 * k * R_earth) in km -> * 1000 for meters
            val bulgeMeters = if (totalKm > 1e-6) {
                ((d1Km * d2Km) / (2.0 * EFFECTIVE_EARTH_RADIUS_KM)) * 1000.0
            } else {
                0.0
            }

            val rawTerrain = elevationsMeters[index]
            val adjustedTerrain = rawTerrain + bulgeMeters
            val rayMeters = antennaAElev + t * (antennaBElev - antennaAElev)

            // F1 = 17.32 * sqrt((d1 * d2) / (f_GHz * D_total))
            val fresnel1 = if (totalKm > 1e-6 && d1Km > 0.0 && d2Km > 0.0) {
                17.32 * sqrt((d1Km * d2Km) / (freqGhz * totalKm))
            } else {
                0.0
            }
            if (fresnel1 > maxFresnel1) {
                maxFresnel1 = fresnel1
            }

            val fresnel60 = fresnel1 * 0.60
            val clearance = rayMeters - adjustedTerrain
            val clearancePercent = if (fresnel1 > 1e-6) {
                (clearance / fresnel1) * 100.0
            } else {
                null
            }

            if (index in 1 until (count - 1)) {
                val deficit60 = (adjustedTerrain + fresnel60) - rayMeters
                if (deficit60 > 0.0) {
                    reqEqualDelta = max(reqEqualDelta, deficit60)
                    reqTxDelta = max(reqTxDelta, deficit60 / (1.0 - t).coerceAtLeast(0.05))
                    reqRxDelta = max(reqRxDelta, deficit60 / t.coerceAtLeast(0.05))
                }
            }

            LosSamplePoint(
                index = index,
                latitude = lat,
                longitude = lon,
                distanceFromAKm = d1Km,
                rawTerrainMeters = rawTerrain,
                earthBulgeMeters = bulgeMeters,
                adjustedTerrainMeters = adjustedTerrain,
                losRayMeters = rayMeters,
                fresnelRadiusMeters = fresnel1,
                fresnel60RadiusMeters = fresnel60,
                fresnel60UpperMeters = rayMeters + fresnel60,
                fresnel60LowerMeters = rayMeters - fresnel60,
                clearanceMeters = clearance,
                clearancePercentFresnel = clearancePercent,
                fresnel100UpperMeters = rayMeters + fresnel1,
                fresnel100LowerMeters = rayMeters - fresnel1
            )
        }

        val interiorSamples = if (samples.size > 2) samples.subList(1, samples.size - 1) else samples

        val hasDirectObstruction = interiorSamples.any { it.adjustedTerrainMeters > it.losRayMeters }
        val hasFresnelIntrusion = !hasDirectObstruction && interiorSamples.any {
            it.adjustedTerrainMeters > it.fresnel60LowerMeters
        }

        val status = when {
            hasDirectObstruction -> LosStatus.OBSTRUCTED
            hasFresnelIntrusion -> LosStatus.MARGINAL_GRAZING
            else -> LosStatus.CLEAR_LOS
        }

        val worstSample = when {
            hasDirectObstruction -> interiorSamples.minBy { it.clearanceMeters }
            else -> interiorSamples.minBy { it.clearancePercentFresnel ?: Double.POSITIVE_INFINITY }
        }

        // ITU-R P.526 single knife-edge diffraction parameter v at the dominant pinch point
        val maxNu = interiorSamples.maxOfOrNull { s ->
            if (s.fresnelRadiusMeters > 1e-6) {
                (-s.clearanceMeters * sqrt(2.0)) / s.fresnelRadiusMeters
            } else {
                -1.0
            }
        } ?: -1.0

        val minFresnelRatio = (worstSample.clearancePercentFresnel ?: 100.0) / 100.0
        val diffractionLossDb = if (minFresnelRatio >= 0.60 || maxNu <= -0.78) {
            0.0
        } else {
            val term = maxNu - 0.1
            val loss = 6.9 + 20.0 * log10(sqrt(term * term + 1.0) + term)
            loss.coerceIn(0.0, 45.0)
        }

        val totalPathLossDb = fsplDb + diffractionLossDb
        val eirpDbm = txPowerDbm + txGainDbi + rxGainDbi
        val predictedRssiDbm = eirpDbm - totalPathLossDb
        val linkMarginDb = predictedRssiDbm - sensitivityDbm

        val viabilityTier = when {
            status == LosStatus.OBSTRUCTED -> RfViabilityTier.OBSTRUCTED
            linkMarginDb < 0.0 -> RfViabilityTier.WEAK_MARGIN
            status == LosStatus.MARGINAL_GRAZING || linkMarginDb < 6.0 -> RfViabilityTier.MARGINAL_GRAZING
            else -> RfViabilityTier.VIABLE_CLEAR
        }

        val roundedEqual = if (reqEqualDelta > 1e-3) ceil((reqEqualDelta + 1e-4) * 10.0) / 10.0 else 0.0
        val roundedTx = if (reqTxDelta > 1e-3) ceil((reqTxDelta + 1e-4) * 10.0) / 10.0 else 0.0
        val roundedRx = if (reqRxDelta > 1e-3) ceil((reqRxDelta + 1e-4) * 10.0) / 10.0 else 0.0

        return LosAnalysisResult(
            pointA = pointA,
            pointB = pointB,
            txHeightMeters = safeTxHeight,
            rxHeightMeters = safeRxHeight,
            frequencyMhz = safeFreqMhz,
            totalDistanceKm = totalKm,
            totalDistanceMiles = totalMiles,
            fsplDb = fsplDb,
            status = status,
            samples = samples,
            worstSample = worstSample,
            worstClearanceMeters = worstSample.clearanceMeters,
            worstClearancePercentFresnel = worstSample.clearancePercentFresnel,
            txPowerDbm = txPowerDbm,
            txGainDbi = txGainDbi,
            rxGainDbi = rxGainDbi,
            sensitivityDbm = sensitivityDbm,
            maxFresnel1RadiusMeters = maxFresnel1,
            diffractionLossDb = diffractionLossDb,
            totalPathLossDb = totalPathLossDb,
            predictedRssiDbm = predictedRssiDbm,
            linkMarginDb = linkMarginDb,
            viabilityTier = viabilityTier,
            requiredTxMastDeltaMeters = roundedTx,
            requiredRxMastDeltaMeters = roundedRx,
            requiredEqualMastDeltaMeters = roundedEqual,
            elevationSource = elevationSource
        )
    }
}
