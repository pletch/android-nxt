package org.owntracks.android.location

import android.app.PendingIntent
import android.location.Location
import android.os.Looper
import androidx.annotation.RequiresPermission

abstract class LocationProviderClient {
  /**
   * Request location updates is the API called by the app to start requesting location updates,
   * calling the given callback when the location changes. This is generic to the actual location
   * provider, so simply removes any existing location request for the given callback before
   * delegating the request to the actual provider.
   *
   * @param locationRequest
   * @param clientCallBack
   * @param looper
   */
  @RequiresPermission(
      anyOf =
          ["android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"]
  )
  fun requestLocationUpdates(
      locationRequest: LocationRequest,
      clientCallBack: LocationCallback,
      looper: Looper,
  ) {
    removeLocationUpdates(clientCallBack)
    actuallyRequestLocationUpdates(locationRequest, clientCallBack, looper)
  }

  abstract fun singleHighAccuracyLocation(clientCallBack: LocationCallback, looper: Looper)

  protected abstract fun actuallyRequestLocationUpdates(
      locationRequest: LocationRequest,
      clientCallBack: LocationCallback,
      looper: Looper,
  )

  abstract fun removeLocationUpdates(clientCallBack: LocationCallback)

  /**
   * Requests location updates delivered to [pendingIntent] rather than to an in-process callback.
   *
   * The registration is held by the location provider, not by us, so it survives our process dying
   * and restarts it to deliver the next fix. A callback-based request cannot do that: it is torn
   * down with the process that made it, which leaves the densest signal the app receives unable to
   * bring it back. See the wake-up registration in `BackgroundService.setupLocationRequest`.
   */
  @RequiresPermission(
      anyOf =
          ["android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"]
  )
  abstract fun requestLocationUpdates(
      locationRequest: LocationRequest,
      pendingIntent: PendingIntent,
  )

  abstract fun removeLocationUpdates(pendingIntent: PendingIntent)

  abstract fun flushLocations()

  abstract fun getLastLocation(): Location?
}
