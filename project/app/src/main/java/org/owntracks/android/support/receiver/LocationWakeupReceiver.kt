package org.owntracks.android.support.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.EarlyEntryPoints
import org.owntracks.android.BaseApp
import org.owntracks.android.services.BackgroundService
import org.owntracks.android.ui.mixins.ServiceStarter
import timber.log.Timber

/**
 * Receives the location updates [BackgroundService] registers by PendingIntent, whose only job is
 * to bring the process back after it has died.
 *
 * The in-process location callback is torn down with the process that registered it, so nothing in
 * the densest signal the app receives can restart it — recovery falls to the fifteen-minute ping
 * worker, and a trip loses every point in between. This registration is held by the location
 * provider instead, so it survives us and restarts the process to deliver the next fix.
 *
 * The fix in the broadcast is deliberately discarded: the service re-registers its own location
 * request as it starts and reports from that, so parsing a flavour-specific payload here would buy
 * one fix at the cost of a second, divergent ingestion path.
 */
class LocationWakeupReceiver : BroadcastReceiver(), ServiceStarter by ServiceStarter.Impl() {
  override fun onReceive(context: Context, intent: Intent) {
    // Delivered on the location interval for as long as the request is registered, so this is the
    // hot path while the app is perfectly healthy: BackgroundService treats the action as a no-op
    // once it has already set itself up in this process.
    if (!startService(context, BackgroundService.INTENT_ACTION_LOCATION_WAKEUP)) {
      Timber.i("Could not start the service for a location wake-up; deferring it to WorkManager")
      EarlyEntryPoints.get(context.applicationContext, BaseApp.ApplicationEntrypoint::class.java)
          .scheduler()
          .scheduleServiceStart()
    }
  }
}
