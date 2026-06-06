package org.owntracks.android.ui.map

import android.content.Context
import android.location.Location
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider

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
  val consumer = IMyLocationConsumer { location: Location?, _: IMyLocationProvider? ->
    location?.let { trySend(it) }
  }
  provider.startLocationProvider(consumer)
  awaitClose { provider.stopLocationProvider() }
}
