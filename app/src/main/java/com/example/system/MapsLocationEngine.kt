package com.example.system

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.URLEncoder
import kotlin.coroutines.resume

data class DeviceLocationInfo(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val timestamp: Long
)

class MapsLocationEngine(private val context: Context) {

    private val fusedClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): Result<DeviceLocationInfo> {
        if (!hasLocationPermission()) {
            return Result.failure(SecurityException("Location permission not granted."))
        }
        return suspendCancellableCoroutine { cont ->
            fusedClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener { loc: Location? ->
                    if (loc != null) {
                        cont.resume(
                            Result.success(
                                DeviceLocationInfo(loc.latitude, loc.longitude, loc.accuracy, loc.time)
                            )
                        )
                    } else {
                        fusedClient.lastLocation
                            .addOnSuccessListener { last ->
                                if (last != null) {
                                    cont.resume(
                                        Result.success(
                                            DeviceLocationInfo(last.latitude, last.longitude, last.accuracy, last.time)
                                        )
                                    )
                                } else {
                                    cont.resume(Result.failure(IllegalStateException("Device location currently unavailable.")))
                                }
                            }
                            .addOnFailureListener { e -> cont.resume(Result.failure(e)) }
                    }
                }
                .addOnFailureListener { e -> cont.resume(Result.failure(e)) }
        }
    }

    fun launchNavigation(destination: String): Result<String> {
        val encoded = URLEncoder.encode(destination, "UTF-8")
        val uri = Uri.parse("google.navigation:q=$encoded")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            "Launched Google Maps navigation to '$destination'."
        }.recoverCatching {
            val webUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$encoded")
            context.startActivity(Intent(Intent.ACTION_VIEW, webUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Opened Maps directions in browser for '$destination'."
        }
    }

    fun searchNearbyPlaces(query: String, location: DeviceLocationInfo? = null): Result<String> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val geoUri = if (location != null) {
            Uri.parse("geo:${location.latitude},${location.longitude}?q=$encoded")
        } else {
            Uri.parse("geo:0,0?q=$encoded")
        }
        val intent = Intent(Intent.ACTION_VIEW, geoUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            "Opened Maps search for '$query'."
        }
    }
}
