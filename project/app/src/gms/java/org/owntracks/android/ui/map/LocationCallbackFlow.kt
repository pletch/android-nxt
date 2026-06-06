package org.owntracks.android.ui.map

import android.content.Context
import android.location.Location
import android.os.Looper
import androidx.annotation.RequiresPermission
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import java.time.Duration
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.owntracks.android.gms.location.toGMSLocationRequest
import org.owntracks.android.location.LocationRequest
import org.owntracks.android.location.LocatorPriority
import timber.log.Timber

@RequiresPermission(
    anyOf =
        ["android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"])
fun locationCallbackFlow(client: FusedLocationProviderClient): Flow<Location> = callbackFlow {
  val callback =
      object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
          result.lastLocation?.let { trySend(it) }
        }

        override fun onLocationAvailability(availability: LocationAvailability) {
          Timber.d("locationCallbackFlow availability: $availability")
        }
      }
  client
      .requestLocationUpdates(
          // Balanced power for the on-screen "blue dot": high accuracy every 2s pins the GPS on
          // (and the location privacy indicator) the whole time the map is open, which is overkill
          // for a viewing map. Balanced + a 5s cadence lets the provider duty-cycle while keeping
          // the dot perfectly usable; the foreground tracking service still drives the actual
          // fixes.
          LocationRequest(
                  smallestDisplacement = 5f,
                  priority = LocatorPriority.BalancedPowerAccuracy,
                  interval = Duration.ofSeconds(5),
                  waitForAccurateLocation = false)
              .toGMSLocationRequest(),
          callback,
          Looper.getMainLooper())
      .addOnFailureListener { e ->
        Timber.e(e, "locationCallbackFlow requestLocationUpdates failed")
      }
  awaitClose {
    Timber.d("locationCallbackFlow removing location updates")
    client.removeLocationUpdates(callback)
  }
}

@RequiresPermission(
    anyOf =
        ["android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"])
fun locationCallbackFlow(context: Context): Flow<Location> =
    locationCallbackFlow(LocationServices.getFusedLocationProviderClient(context))
