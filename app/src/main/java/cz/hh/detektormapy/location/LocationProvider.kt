package cz.hh.detektormapy.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GPS access built on the platform [LocationManager] only.
 *
 * Play Services' fused provider is deliberately avoided: this app must work on a phone
 * in airplane mode in a forest, and the fused provider degrades to network positioning
 * (or nothing) exactly there. The raw GPS provider is also what gives us honest accuracy
 * numbers, which the field workflow relies on when deciding whether a find pin is
 * trustworthy.
 */
@Singleton
class LocationProvider @Inject constructor(@param:ApplicationContext private val context: Context) {

    private val manager: LocationManager? =
        ContextCompat.getSystemService(context, LocationManager::class.java)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun isGpsEnabled(): Boolean = manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true

    @SuppressLint("MissingPermission")
    fun lastKnown(): Fix? {
        if (!hasPermission()) return null
        val mgr = manager ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        return providers
            .mapNotNull { runCatching { mgr.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.toFix()
    }

    /**
     * Cold flow of fixes at the cadence of [mode]. Emits nothing (instead of throwing) when
     * the permission is missing, so the map screen can render happily without location.
     */
    @SuppressLint("MissingPermission")
    fun fixes(mode: LocationMode = LocationMode.INTERACTIVE): Flow<Fix> = callbackFlow {
        val mgr = manager
        if (mgr == null || !hasPermission()) {
            close()
            return@callbackFlow
        }

        val listener = LocationListener { location -> trySend(location.toFix()) }

        val providers = buildList {
            if (mgr.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            if (mgr.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
        }
        if (providers.isEmpty()) {
            close()
            return@callbackFlow
        }

        providers.forEach { provider ->
            runCatching {
                mgr.requestLocationUpdates(
                    provider,
                    mode.intervalMs,
                    mode.minDistanceM,
                    listener,
                    Looper.getMainLooper(),
                )
            }.onFailure { Log.w(TAG, "Nelze zapnout provider $provider", it) }
        }

        lastKnown()?.let { trySend(it) }

        awaitClose {
            runCatching { mgr.removeUpdates(listener) }
        }
    }.distinctUntilChanged()

    private fun Location.toFix() = Fix(
        lat = latitude,
        lon = longitude,
        altitude = if (hasAltitude()) altitude else null,
        accuracyM = if (hasAccuracy()) accuracy else null,
        speedMs = if (hasSpeed()) speed else null,
        bearingDeg = if (hasBearing()) bearing else null,
        timestamp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            time
        } else {
            time
        },
    )

    private companion object {
        const val TAG = "LocationProvider"
    }
}
