package us.lemesh.nodedb.data

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class AlignmentTarget(
    val id: String,
    val label: String,
    val subtitle: String,
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double? = null
)

data class AntennaAlignmentTelemetry(
    val deviceLat: Double? = null,
    val deviceLon: Double? = null,
    val deviceAltitudeMeters: Double? = null,
    val magneticHeadingDeg: Double? = null,
    val declinationDeg: Double = 0.0,
    val trueHeadingDeg: Double? = null,
    val targetAzimuthDeg: Double? = null,
    val relativeTurnDeg: Double? = null,
    val verticalElevationAngleDeg: Double? = null,
    val distanceKm: Double? = null,
    val distanceMiles: Double? = null,
    val hasCompassSensor: Boolean = true,
    val sensorAccuracyLow: Boolean = false
)

/**
 * Pure geodetic & compass math for rooftop directional antenna aiming (True North azimuth,
 * shortest turn delta, magnetic declination compensation, and vertical tilt angle with 4/3 Earth
 * effective curvature drop).
 */
object AntennaAlignmentMath {
    private const val EFFECTIVE_EARTH_RADIUS_METERS = 6_371_008.8 * (4.0 / 3.0)

    fun normalizeDegrees(deg: Double): Double {
        var r = deg % 360.0
        if (r < 0.0) r += 360.0
        return r
    }

    fun applyMagneticDeclination(magneticHeadingDeg: Double, declinationDeg: Double): Double =
        normalizeDegrees(magneticHeadingDeg + declinationDeg)

    /**
     * Shortest signed angle in [-180, +180] from [currentHeadingDeg] to [targetAzimuthDeg].
     * Positive = turn clockwise (right); negative = turn counter-clockwise (left).
     */
    fun computeRelativeTurnDeg(currentHeadingDeg: Double, targetAzimuthDeg: Double): Double {
        val diff = normalizeDegrees(targetAzimuthDeg - currentHeadingDeg)
        return if (diff > 180.0) diff - 360.0 else diff
    }

    fun computeTargetAzimuthDeg(
        originLat: Double,
        originLon: Double,
        targetLat: Double,
        targetLon: Double
    ): Double = MeshGeoUtils.bearingDegrees(originLat, originLon, targetLat, targetLon)

    /**
     * Computes vertical antenna tilt angle in degrees from origin to target, accounting for
     * both elevation difference and 4/3-Earth RF horizon curvature drop:
     *   effectiveDeltaZ = (targetElevM - originElevM) - (d^2 / (2 * k * R_earth))
     *   elevationAngle = atan2(effectiveDeltaZ, d)
     */
    fun computeVerticalElevationAngleDeg(
        distanceKm: Double,
        originElevationMeters: Double,
        targetElevationMeters: Double
    ): Double {
        val distMeters = (distanceKm * 1000.0).coerceAtLeast(1.0)
        val curvatureDropMeters = (distMeters * distMeters) / (2.0 * EFFECTIVE_EARTH_RADIUS_METERS)
        val effectiveDeltaZ = (targetElevationMeters - originElevationMeters) - curvatureDropMeters
        return Math.toDegrees(atan2(effectiveDeltaZ, distMeters))
    }

    /**
     * Exponential low-pass filter on circular compass angles (0..360) without wraparound jumps.
     */
    fun smoothHeadingDeg(previousDeg: Double?, incomingDeg: Double, alpha: Double = 0.22): Double {
        if (previousDeg == null) return normalizeDegrees(incomingDeg)
        val prevRad = Math.toRadians(previousDeg)
        val nextRad = Math.toRadians(incomingDeg)
        val sinAvg = (1.0 - alpha) * sin(prevRad) + alpha * sin(nextRad)
        val cosAvg = (1.0 - alpha) * cos(prevRad) + alpha * cos(nextRad)
        return normalizeDegrees(Math.toDegrees(atan2(sinAvg, cosAvg)))
    }
}

/**
 * Registers a lifecycle-bound [Sensor.TYPE_ROTATION_VECTOR] listener (with accelerometer +
 * magnetometer fallback) and applies [GeomagneticField] declination to emit True North heading.
 */
class CompassSensorTracker(
    context: Context,
    private val onHeadingUpdate: (magneticHeadingDeg: Double, accuracyLow: Boolean) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotationSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var lastAccel: FloatArray? = null
    private var lastMag: FloatArray? = null
    private var smoothedHeading: Double? = null
    private var accuracyLow = false

    val hasSensor: Boolean
        get() = rotationSensor != null || (accelSensor != null && magSensor != null)

    fun start() {
        val sm = sensorManager ?: return
        if (rotationSensor != null) {
            sm.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        } else if (accelSensor != null && magSensor != null) {
            sm.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_UI)
            sm.registerListener(this, magSensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                val azimuthDeg = AntennaAlignmentMath.normalizeDegrees(Math.toDegrees(orientationAngles[0].toDouble()))
                val smoothed = AntennaAlignmentMath.smoothHeadingDeg(smoothedHeading, azimuthDeg)
                smoothedHeading = smoothed
                onHeadingUpdate(smoothed, accuracyLow)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                lastAccel = event.values.copyOf()
                emitFallbackIfReady()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                lastMag = event.values.copyOf()
                emitFallbackIfReady()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        accuracyLow = accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE ||
            accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW
    }

    private fun emitFallbackIfReady() {
        val a = lastAccel ?: return
        val m = lastMag ?: return
        if (SensorManager.getRotationMatrix(rotationMatrix, null, a, m)) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            val azimuthDeg = AntennaAlignmentMath.normalizeDegrees(Math.toDegrees(orientationAngles[0].toDouble()))
            val smoothed = AntennaAlignmentMath.smoothHeadingDeg(smoothedHeading, azimuthDeg)
            smoothedHeading = smoothed
            onHeadingUpdate(smoothed, accuracyLow)
        }
    }

    companion object {
        fun magneticDeclinationDegrees(
            lat: Double,
            lon: Double,
            altitudeMeters: Double = 200.0,
            epochMs: Long = System.currentTimeMillis()
        ): Double {
            return runCatching {
                GeomagneticField(
                    lat.toFloat(),
                    lon.toFloat(),
                    altitudeMeters.toFloat(),
                    epochMs
                ).declination.toDouble()
            }.getOrDefault(0.0)
        }
    }
}
