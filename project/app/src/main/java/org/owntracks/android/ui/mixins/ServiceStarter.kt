package org.owntracks.android.ui.mixins

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import org.owntracks.android.services.BackgroundService
import timber.log.Timber

/** Provides a mixin for Activities and BroadcastReceivers that want to start the service */
interface ServiceStarter {
  /**
   * Starts [BackgroundService], returning whether the start was accepted.
   *
   * Callers that start the service from the background must handle `false`: the work they were
   * forwarding has not been delivered and never will be. Callers with a visible Activity can ignore
   * the result, as the start cannot be refused in that case.
   */
  fun startService(context: Context, action: String? = null, intent: Intent? = null): Boolean

  class Impl : ServiceStarter {
    override fun startService(context: Context, action: String?, intent: Intent?): Boolean {
      Timber.d("requesting service start with action $action")
      val startIntent =
          (intent ?: Intent()).setClass(context, BackgroundService::class.java).apply {
            action?.also { this.action = it }
          }
      // Receivers call this from the background, where starting a connectedDevice foreground
      // service is only allowed if the service is already foregrounded. If the service has been
      // killed (Doze, app standby, battery restriction) the start is refused, and an uncaught
      // exception here takes the whole process down until the user next opens the app.
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        try {
          ContextCompat.startForegroundService(context, startIntent)
        } catch (e: ForegroundServiceStartNotAllowedException) {
          Timber.e(e, "Android refused the foreground service start for action $action")
          return false
        }
      } else {
        ContextCompat.startForegroundService(context, startIntent)
      }
      return true
    }
  }
}
