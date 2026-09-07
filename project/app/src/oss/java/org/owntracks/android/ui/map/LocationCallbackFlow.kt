package org.owntracks.android.ui.map

import android.content.Context
import android.location.Location
import android.location.LocationManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider
import timber.log.Timber

fun locationCallbackFlow(context: Context): Flow<Location> = callbackFlow {
  val provider =
      // A 2s/1m cadence keeps the GPS (and the location privacy indicator) pinned on the whole time
      // the map is open, which is overkill for a viewing map. A 5s/5m cadence keeps the dot usable
      // while letting the providers rest; the foreground tracking service still drives actual
      // fixes.
      GpsMyLocationProvider(context).apply {
        clearLocationSources()
        addLocationSource("gps")
        addLocationSource("network")
        addLocationSource("passive")
        locationUpdateMinTime = TimeUnit.SECONDS.toMillis(5)
        locationUpdateMinDistance = 5f
      }
  // Seed with whatever fix the device already has: requestLocationUpdates (inside
  // startLocationProvider below) only emits once a new fix arrives, which can take a while, so
  // without this a fresh subscription (e.g. opening the map) reports no location at all in the
  // meantime, even though the device already has a perfectly good last-known one.
  val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
  for (source in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
    try {
      locationManager.getLastKnownLocation(source)?.let { trySend(it) }
    } catch (e: SecurityException) {
      Timber.w(e, "locationCallbackFlow getLastKnownLocation failed for $source")
    }
  }
  val consumer = IMyLocationConsumer { location: Location?, _: IMyLocationProvider? ->
    location?.let { trySend(it) }
  }
  provider.startLocationProvider(consumer)
  awaitClose { provider.stopLocationProvider() }
}
