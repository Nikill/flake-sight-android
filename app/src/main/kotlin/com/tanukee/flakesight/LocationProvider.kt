package com.tanukee.flakesight

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.icu.text.MeasureFormat
import android.icu.text.NumberFormat
import android.icu.util.LocaleData
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.icu.util.ULocale
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.util.Consumer
import com.tanukee.flakesight.core.Coordinate
import java.util.Locale

/**
 * The user's position, read once on demand to sort resorts by distance and
 * frame the map. Port of `LocationProvider`.
 *
 * No continuous tracking: each request keeps the first fix and stops. The
 * approximate position is enough to rank resorts kilometres apart, so the app
 * only asks for it.
 */
class LocationProvider(private val context: Context) {
    /** The last fix, `null` until the first one. */
    var location by mutableStateOf<Coordinate?>(null)
        private set
    private var isLocating = false

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Reads the position once permission is granted; a failure keeps the last fix. */
    @SuppressLint("MissingPermission") // Checked by `hasPermission`, which lint does not follow.
    fun locate() {
        if (isLocating || !hasPermission) return
        val manager = context.getSystemService(LocationManager::class.java) ?: return
        // The first one switched on: a disabled provider answers with no position at all.
        val candidates = listOfNotNull(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) LocationManager.FUSED_PROVIDER else null,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
        )
        val provider = candidates.firstOrNull { it in manager.allProviders && manager.isProviderEnabled(it) } ?: return
        isLocating = true
        try {
            val onFix = Consumer<Location?> { fix ->
                isLocating = false
                if (fix != null) location = Coordinate(fix.latitude, fix.longitude)
            }
            LocationManagerCompat.getCurrentLocation(manager, provider, android.os.CancellationSignal(), ContextCompat.getMainExecutor(context), onFix)
        } catch (_: SecurityException) {
            // Before Android 12, the GPS needs the precise position, which the app does not ask for.
            isLocating = false
        } catch (_: IllegalArgumentException) {
            // No such provider on this device.
            isLocating = false
        }
    }

    companion object {
        const val PERMISSION = Manifest.permission.ACCESS_COARSE_LOCATION
    }
}

/**
 * Asks for location access when needed, then locates. [onDenied] runs when
 * the user refuses, or refused for good before, so the caller can explain.
 */
@Composable
fun rememberLocationRequest(provider: LocationProvider, onDenied: () -> Unit = {}): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) provider.locate() else onDenied()
    }
    return { if (provider.hasPermission) provider.locate() else launcher.launch(LocationProvider.PERMISSION) }
}

/**
 * "18 km", "4.2 km", or miles where roads are signposted in miles (the UK,
 * the US), as iOS formats a road distance.
 */
internal fun roadDistance(metres: Double, locale: Locale): String {
    val system = LocaleData.getMeasurementSystem(ULocale.forLocale(locale))
    val miles = system == LocaleData.MeasurementSystem.US || system == LocaleData.MeasurementSystem.UK
    val value = if (miles) metres / 1609.344 else metres / 1000
    val number = NumberFormat.getInstance(locale).apply { maximumFractionDigits = if (value < 10) 1 else 0 }
    return MeasureFormat.getInstance(ULocale.forLocale(locale), MeasureFormat.FormatWidth.SHORT, number)
        .format(Measure(value, if (miles) MeasureUnit.MILE else MeasureUnit.KILOMETER))
}
