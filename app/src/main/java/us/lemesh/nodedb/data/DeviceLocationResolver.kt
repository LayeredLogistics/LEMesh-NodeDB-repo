package us.lemesh.nodedb.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.abs

object DeviceLocationResolver {
    const val DEFAULT_FALLBACK_LAT = 42.1292
    const val DEFAULT_FALLBACK_LON = -80.0851
    private const val RECENT_FIX_MAX_AGE_MS = 5 * 60_000L

    fun hasAnyLocationPermission(context: Context): Boolean {
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return coarse || fine
    }

    fun hasFineLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun resolveStartupTarget(
        deviceLocation: Pair<Double, Double>?,
        selfNode: MeshNode?,
        allNodes: List<MeshNode>
    ): Pair<Double, Double> {
        if (deviceLocation != null && isValidCoordinate(deviceLocation.first, deviceLocation.second)) {
            return deviceLocation
        }
        val self = selfNode?.takeIf { it.hasValidGps }
        if (self != null) {
            return self.latitude!! to self.longitude!!
        }
        val centroid = calculateNodesCentroid(allNodes)
        if (centroid != null) {
            return centroid
        }
        return DEFAULT_FALLBACK_LAT to DEFAULT_FALLBACK_LON
    }

    fun calculateNodesCentroid(nodes: List<MeshNode>): Pair<Double, Double>? {
        val gpsNodes = nodes.filter { it.hasValidGps }
        if (gpsNodes.isEmpty()) return null
        val avgLat = gpsNodes.sumOf { it.latitude!! } / gpsNodes.size
        val avgLon = gpsNodes.sumOf { it.longitude!! } / gpsNodes.size
        return if (isValidCoordinate(avgLat, avgLon)) avgLat to avgLon else null
    }

    private fun isValidCoordinate(lat: Double, lon: Double): Boolean {
        if (!lat.isFinite() || !lon.isFinite()) return false
        if (lat < -85.0 || lat > 85.0 || lon < -180.0 || lon > 180.0) return false
        return abs(lat) > 1e-4 || abs(lon) > 1e-4
    }

    @SuppressLint("MissingPermission")
    suspend fun fetchQuickDeviceLocation(
        context: Context,
        timeoutMs: Long = 1200L
    ): Pair<Double, Double>? {
        if (!hasAnyLocationPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        val lastKnown = bestLastKnownLocation(context, lm)
        if (lastKnown != null &&
            (System.currentTimeMillis() - lastKnown.time) in 0L..RECENT_FIX_MAX_AGE_MS
        ) {
            return lastKnown.latitude to lastKnown.longitude
        }

        val fresh = withTimeoutOrNull(timeoutMs) {
            requestSingleLocationFix(context, lm)
        }
        if (fresh != null && isValidCoordinate(fresh.latitude, fresh.longitude)) {
            return fresh.latitude to fresh.longitude
        }

        if (lastKnown != null && isValidCoordinate(lastKnown.latitude, lastKnown.longitude)) {
            return lastKnown.latitude to lastKnown.longitude
        }
        return null
    }

    data class DeviceLocationFix(
        val latitude: Double,
        val longitude: Double,
        val altitudeMeters: Double?,
        val accuracyMeters: Float?
    )

    @SuppressLint("MissingPermission")
    suspend fun fetchDeviceLocationWithAltitude(
        context: Context,
        timeoutMs: Long = 2000L
    ): DeviceLocationFix? {
        if (!hasAnyLocationPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        val lastKnown = bestLastKnownLocation(context, lm)
        if (lastKnown != null &&
            (System.currentTimeMillis() - lastKnown.time) in 0L..60_000L
        ) {
            return lastKnown.toFix()
        }

        val fresh = withTimeoutOrNull(timeoutMs) {
            requestSingleLocationFix(context, lm)
        }
        if (fresh != null && isValidCoordinate(fresh.latitude, fresh.longitude)) {
            return fresh.toFix()
        }

        if (lastKnown != null && isValidCoordinate(lastKnown.latitude, lastKnown.longitude)) {
            return lastKnown.toFix()
        }
        return null
    }

    private fun Location.toFix(): DeviceLocationFix = DeviceLocationFix(
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = if (hasAltitude()) altitude else null,
        accuracyMeters = if (hasAccuracy()) accuracy else null
    )

    @SuppressLint("MissingPermission")
    private fun bestLastKnownLocation(context: Context, lm: LocationManager): Location? {
        val allowFine = hasFineLocationPermission(context)
        val candidateProviders = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(LocationManager.FUSED_PROVIDER)
            }
            if (allowFine) {
                add(LocationManager.GPS_PROVIDER)
            }
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }

        var best: Location? = null
        for (provider in candidateProviders) {
            val loc = runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: continue
            if (!isValidCoordinate(loc.latitude, loc.longitude)) continue
            if (best == null || loc.time > best.time) {
                best = loc
            }
        }
        return best
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestSingleLocationFix(
        context: Context,
        lm: LocationManager
    ): Location? = suspendCancellableCoroutine { cont ->
        val allowFine = hasFineLocationPermission(context)
        val preferredProviders = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(LocationManager.FUSED_PROVIDER)
            }
            if (allowFine) {
                add(LocationManager.GPS_PROVIDER)
            }
            add(LocationManager.NETWORK_PROVIDER)
        }
        val provider = preferredProviders.firstOrNull { p ->
            runCatching { lm.isProviderEnabled(p) }.getOrDefault(false)
        } ?: run {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            runCatching {
                lm.getCurrentLocation(
                    provider,
                    signal,
                    ContextCompat.getMainExecutor(context)
                ) { location ->
                    if (cont.isActive) cont.resume(location)
                }
            }.onFailure {
                if (cont.isActive) cont.resume(null)
            }
        } else {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (cont.isActive) cont.resume(location)
                    runCatching { lm.removeUpdates(this) }
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
            }
            cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
            runCatching {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }.onFailure {
                if (cont.isActive) cont.resume(null)
            }
        }
    }
}
