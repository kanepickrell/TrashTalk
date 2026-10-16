package com.trashtalk.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** A position fix, with the accuracy the map needs in order to draw honestly. */
data class DeviceFix(
    val location: LatLng,
    val accuracyMeters: Float,
    val at: Long,
) {
    /** A fix older than a couple of minutes is stale enough to mark as such. */
    fun isStale(now: Long = System.currentTimeMillis()): Boolean =
        now - at > 120_000
}

/** Whether the app may read location, and whether a provider is actually on. */
enum class LocationAvailability { GRANTED, DENIED, PROVIDERS_OFF }

data class LocationState(
    val fix: DeviceFix? = null,
    val availability: LocationAvailability = LocationAvailability.DENIED,
) {
    val isLocating: Boolean
        get() = availability == LocationAvailability.GRANTED && fix == null
}

/**
 * Device location via the platform [LocationManager].
 *
 * Deliberately not Play Services' fused provider. Keeping the core field
 * workflow free of a Play dependency is what lets the app run with no API keys
 * and on devices without Play Services, and GPS plus network is far more
 * accuracy than is needed to pick which street segment somebody is standing on.
 *
 * Shared by the map and the collection form so there is exactly one permission
 * prompt and one listener, rather than one per screen.
 */
@Composable
fun rememberDeviceLocation(enabled: Boolean = true): LocationState {
    val context = LocalContext.current
    var state by remember { mutableStateOf(LocationState()) }

    fun granted(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

    var hasPermission by remember { mutableStateOf(granted()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        hasPermission = result.values.any { it }
        if (!hasPermission) {
            state = state.copy(availability = LocationAvailability.DENIED)
        }
    }

    LaunchedEffect(enabled, hasPermission) {
        if (enabled && !hasPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    DisposableEffect(enabled, hasPermission) {
        if (!enabled || !hasPermission) return@DisposableEffect onDispose { }

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return@DisposableEffect onDispose { }

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

        if (providers.isEmpty()) {
            state = state.copy(availability = LocationAvailability.PROVIDERS_OFF)
            return@DisposableEffect onDispose { }
        }
        state = state.copy(availability = LocationAvailability.GRANTED)

        fun accept(loc: Location) {
            val next = DeviceFix(
                location = LatLng(loc.latitude, loc.longitude),
                accuracyMeters = if (loc.hasAccuracy()) loc.accuracy else 50f,
                at = if (loc.time > 0) loc.time else System.currentTimeMillis(),
            )
            // Prefer the newer fix, but do not let a much vaguer one replace a
            // good recent one -- the network provider often answers first with a
            // kilometre of error and would otherwise shove the map sideways.
            val current = state.fix
            val better = current == null ||
                next.at >= current.at && next.accuracyMeters <= current.accuracyMeters * 2.5f ||
                next.at - current.at > 30_000
            if (better) state = state.copy(fix = next)
        }

        val listener = android.location.LocationListener { accept(it) }

        providers.forEach { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()?.let(::accept)
        }
        providers.forEach { provider ->
            runCatching { manager.requestLocationUpdates(provider, 3_000L, 4f, listener) }
        }

        onDispose { runCatching { manager.removeUpdates(listener) } }
    }

    return state
}
