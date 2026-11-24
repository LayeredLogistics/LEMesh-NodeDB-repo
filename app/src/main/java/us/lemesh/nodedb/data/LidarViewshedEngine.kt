package us.lemesh.nodedb.data

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class LidarViewshedParams(
    val originLat: Double,
    val originLon: Double,
    val originLabel: String,
    val sourceNode: MeshNode? = null,
    val txHeightFt: Double = LidarViewshedEngine.DEFAULT_TX_HEIGHT_FT.toDouble(),
    val rxHeightFt: Double = LidarViewshedEngine.DEFAULT_RX_HEIGHT_FT.toDouble(),
    val radiusMeters: Int = LidarViewshedEngine.DEFAULT_RADIUS_METERS,
    val frequencyMhz: Double = LosAnalyzer.DEFAULT_LORA_US_FREQ_MHZ,
    val txPowerDbm: Double = RelayBridgeEngine.DEFAULT_TX_POWER_DBM,
    val antennaGainDbi: Double = RelayBridgeEngine.DEFAULT_ANTENNA_GAIN_DBI,
    val cableLossDb: Double = LidarViewshedEngine.DEFAULT_CABLE_LOSS_DB.toDouble(),
    val sensitivityDbm: Double = LidarViewshedEngine.SX1262_LONGFAST_SENSITIVITY_DBM,
    val fadeMarginDb: Double = 0.0
)

data class LidarViewshedResult(
    val params: LidarViewshedParams,
    val dataSource: LidarDataSource,
    val sourceLabel: String,
    val shortSourceLabel: String,
    val terrainResolutionMeters: Double,
    val originElevationMeters: Double,
    val coveredAreaSqMeters: Double,
    val coveredAreaAcres: Double,
    val maxClearRangeMeters: Double,
    val corridorClearancePct: Double,
    val field: PolarVisibilityField,
    val bitmap: Bitmap,
    val northLat: Double,
    val southLat: Double,
    val westLon: Double,
    val eastLon: Double,
    val computeTimeMs: Long
)

/**
 * High-resolution (1.0 m radial step) micro-terrain 915 MHz viewshed engine built for
 * TX placement (0–50 ft AGL) and RX coverage slicing (0–10 ft AGL) using fixed Semtech
 * SX1262 LongFast (250 kHz BW, SF11, -114 dBm noise floor, -134 dBm sensitivity) receiver physics.
 */
object LidarViewshedEngine {

    const val RAY_COUNT = 720 // 0.5 degree angular resolution
    const val STEP_METERS = 1.0
    const val MIN_TX_HEIGHT_FT = 0.0f
    const val MAX_TX_HEIGHT_FT = 50.0f
    const val MIN_RX_HEIGHT_FT = 0.0f
    const val MAX_RX_HEIGHT_FT = 10.0f
    const val DEFAULT_TX_HEIGHT_FT = 10.0f
    const val DEFAULT_RX_HEIGHT_FT = 5.0f
    const val MIN_RADIUS_METERS = 100
    const val MAX_RADIUS_METERS = 1500
    const val DEFAULT_RADIUS_METERS = 500
    const val DEFAULT_CABLE_LOSS_DB = 0.5f

    // Assumed Semtech SX1262 Meshtastic LongFast (250 kHz / SF11) receiver baseline
    const val SX1262_BANDWIDTH_KHZ = 250.0
    const val SX1262_NOISE_FLOOR_DBM = -114.0
    const val SX1262_DEMOD_SNR_DB = -17.5
    const val SX1262_LONGFAST_SENSITIVITY_DBM = -134.0

    const val FEET_TO_METERS = 0.3048
    const val METERS_TO_FEET = 1.0 / 0.3048
    const val SQ_METERS_TO_ACRES = 1.0 / 4046.8564224
    private const val EARTH_RADIUS_KM = 6371.0088
    private const val EFFECTIVE_EARTH_RADIUS_KM = EARTH_RADIUS_KM * (4.0 / 3.0)
    private const val STREET_DIFFRACTION_MARGIN_METERS = 1.5
    private const val RASTER_SIZE = 1024

    suspend fun computeViewshed(
        params: LidarViewshedParams,
        patch: LidarElevationPatch
    ): LidarViewshedResult = withContext(Dispatchers.Default) {
        val startMs = System.currentTimeMillis()
        val radiusMeters = params.radiusMeters.coerceIn(MIN_RADIUS_METERS, MAX_RADIUS_METERS)
        val radiusKm = radiusMeters / 1000.0
        val numSteps = max(20, (radiusMeters / STEP_METERS).roundToInt())
        val freqMhz = params.frequencyMhz.coerceAtLeast(1.0)
        val freqGhz = freqMhz / 1000.0
        val safeFadeMarginDb = params.fadeMarginDb.coerceAtLeast(0.0)
        val safeCableLossDb = params.cableLossDb.coerceIn(0.0, 6.0)
        val eirpDbm = params.txPowerDbm + params.antennaGainDbi - safeCableLossDb
        val rxSensitivityDbm = params.sensitivityDbm

        val txHeightM = params.txHeightFt.coerceIn(MIN_TX_HEIGHT_FT.toDouble(), MAX_TX_HEIGHT_FT.toDouble()) * FEET_TO_METERS
        val rxHeightM = params.rxHeightFt.coerceIn(MIN_RX_HEIGHT_FT.toDouble(), MAX_RX_HEIGHT_FT.toDouble()) * FEET_TO_METERS

        val originElev = patch.sampleElevation(params.originLat, params.originLon) ?: 0.0
        val txElev = originElev + txHeightM

        val cosOriginLat = cos(Math.toRadians(params.originLat)).coerceAtLeast(0.15)
        val metersPerDegLat = 111_132.0
        val metersPerDegLon = 111_320.0 * cosOriginLat

        val statusGrid = ByteArray(RAY_COUNT * numSteps)
        val rssiGrid = FloatArray(RAY_COUNT * numSteps)

        val dThetaRad = (2.0 * PI) / RAY_COUNT
        var coveredAreaSqM = 0.0
        var clearCellCount = 0
        val totalCells = RAY_COUNT * numSteps
        var maxClearRangeM = 0.0

        val terrainEffElev = DoubleArray(numSteps)
        val hullDistM = DoubleArray(numSteps + 1)
        val hullEffElev = DoubleArray(numSteps + 1)

        for (rIdx in 0 until RAY_COUNT) {
            if ((rIdx and 0x0F) == 0) {
                currentCoroutineContext().ensureActive()
            }
            val bearingRad = rIdx * dThetaRad
            val sinBearing = sin(bearingRad)
            val cosBearing = cos(bearingRad)

            var maxTerrainSlope = Double.NEGATIVE_INFINITY
            hullDistM[0] = 0.0
            hullEffElev[0] = txElev
            var hullSize = 1

            for (sIdx in 0 until numSteps) {
                val distM = (sIdx + 1) * STEP_METERS
                val distKm = distM / 1000.0
                val sampleLat = params.originLat + (distM * cosBearing) / metersPerDegLat
                val sampleLon = params.originLon + (distM * sinBearing) / metersPerDegLon
                val rawElev = patch.sampleElevation(sampleLat, sampleLon) ?: originElev

                val bulgeM = ((distKm * distKm) / (2.0 * EFFECTIVE_EARTH_RADIUS_KM)) * 1000.0
                val effTerrainElev = rawElev - bulgeM
                val rxTargetElev = (rawElev + rxHeightM) - bulgeM
                terrainEffElev[sIdx] = effTerrainElev

                val terrainSlope = (effTerrainElev - txElev) / distM
                val targetSlope = (rxTargetElev - txElev) / distM

                var minFresnelRatio = 1.0
                val pointStatus: Byte
                if (targetSlope >= maxTerrainSlope) {
                    for (h in 1 until hullSize) {
                        val obsDistM = hullDistM[h]
                        val obsElev = hullEffElev[h]
                        val d1Km = obsDistM / 1000.0
                        val d2Km = (distM - obsDistM) / 1000.0
                        if (d1Km > 0.0 && d2Km > 0.0) {
                            val rayAtObs = txElev + targetSlope * obsDistM
                            val f1 = 17.32 * sqrt((d1Km * d2Km) / (freqGhz * distKm))
                            if (f1 > 0.001) {
                                val ratio = (rayAtObs - obsElev) / f1
                                if (ratio < minFresnelRatio) minFresnelRatio = ratio
                            }
                        }
                    }

                    if (sIdx >= 2) {
                        val stride = max(1, sIdx / 12)
                        var k = stride
                        while (k < sIdx) {
                            val obsDistM = (k + 1) * STEP_METERS
                            val obsElev = terrainEffElev[k]
                            val d1Km = obsDistM / 1000.0
                            val d2Km = (distM - obsDistM) / 1000.0
                            if (d1Km > 0.0 && d2Km > 0.0) {
                                val rayAtObs = txElev + targetSlope * obsDistM
                                val f1 = 17.32 * sqrt((d1Km * d2Km) / (freqGhz * distKm))
                                if (f1 > 0.001) {
                                    val ratio = (rayAtObs - obsElev) / f1
                                    if (ratio < minFresnelRatio) minFresnelRatio = ratio
                                }
                            }
                            k += stride
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
                    if (shadowDepthM <= STREET_DIFFRACTION_MARGIN_METERS) {
                        pointStatus = ViewshedPointStatus.GRAZING.code
                        val approxF1 = (17.32 * sqrt((distKm * 0.25) / freqGhz)).coerceAtLeast(1.0)
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
                hullSize++

                val cellOffset = rIdx * numSteps + sIdx
                if (pointStatus != ViewshedPointStatus.OBSTRUCTED.code) {
                    val fspl = 20.0 * log10(distKm) + 20.0 * log10(freqMhz) + 32.44
                    val diffractionLossDb = if (pointStatus == ViewshedPointStatus.GRAZING.code) {
                        ViewshedEngine.knifeEdgeDiffractionLossDb(minFresnelRatio)
                    } else {
                        0.0
                    }
                    val rssi = eirpDbm - fspl - diffractionLossDb - safeFadeMarginDb
                    if (rssi >= rxSensitivityDbm) {
                        statusGrid[cellOffset] = pointStatus
                        rssiGrid[cellOffset] = rssi.toFloat()

                        coveredAreaSqM += dThetaRad * distM * STEP_METERS
                        if (pointStatus == ViewshedPointStatus.CLEAR.code) {
                            clearCellCount++
                            if (distM > maxClearRangeM) {
                                maxClearRangeM = distM
                            }
                        }
                    }
                }
            }
        }

        val latDelta = (radiusMeters / metersPerDegLat) * 1.02
        val lonDelta = (radiusMeters / metersPerDegLon) * 1.02
        val northLat = (params.originLat + latDelta).coerceAtMost(85.0)
        val southLat = (params.originLat - latDelta).coerceAtLeast(-85.0)
        val westLon = (params.originLon - lonDelta).coerceAtLeast(-180.0)
        val eastLon = (params.originLon + lonDelta).coerceAtMost(180.0)

        val field = PolarVisibilityField(
            originLat = params.originLat,
            originLon = params.originLon,
            radiusKm = radiusKm,
            rayCount = RAY_COUNT,
            stepMeters = STEP_METERS,
            numSteps = numSteps,
            sensitivityDbm = rxSensitivityDbm,
            statusGrid = statusGrid,
            rssiGrid = rssiGrid,
            coveredAreaKm2 = coveredAreaSqM / 1_000_000.0,
            northLat = northLat,
            southLat = southLat,
            westLon = westLon,
            eastLon = eastLon
        )

        val bitmap = field.rasterizeSubRegion(width = RASTER_SIZE, height = RASTER_SIZE)
        val corridorPct = if (totalCells > 0) {
            (clearCellCount.toDouble() / totalCells.toDouble()) * 100.0
        } else {
            0.0
        }

        LidarViewshedResult(
            params = params,
            dataSource = patch.source,
            sourceLabel = patch.sourceLabel,
            shortSourceLabel = patch.shortBadgeLabel,
            terrainResolutionMeters = patch.resolutionMeters,
            originElevationMeters = originElev,
            coveredAreaSqMeters = coveredAreaSqM,
            coveredAreaAcres = coveredAreaSqM * SQ_METERS_TO_ACRES,
            maxClearRangeMeters = maxClearRangeM,
            corridorClearancePct = corridorPct,
            field = field,
            bitmap = bitmap,
            northLat = northLat,
            southLat = southLat,
            westLon = westLon,
            eastLon = eastLon,
            computeTimeMs = System.currentTimeMillis() - startMs
        )
    }

    fun formatDualDistance(meters: Double): String {
        val feet = meters * METERS_TO_FEET
        return String.format(java.util.Locale.US, "%.0f m / %.0f ft", meters, feet)
    }

    fun formatDualElevation(meters: Double): String {
        val feet = meters * METERS_TO_FEET
        return String.format(java.util.Locale.US, "%.1f m / %.1f ft", meters, feet)
    }

    fun formatDualArea(sqMeters: Double, acres: Double): String {
        return String.format(java.util.Locale.US, "%.2f ac • %,.0f m²", acres, sqMeters)
    }
}
