package org.owntracks.android.services.worker

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkRequest.Companion.MIN_BACKOFF_MILLIS
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import org.owntracks.android.preferences.Preferences
import timber.log.Timber

@Singleton
class Scheduler
@Inject
constructor(
    private val preferences: Preferences,
    @param:ApplicationContext private val context: Context
) : Preferences.OnPreferenceChangeListener {
  init {
    preferences.registerOnPreferenceChangedListener(this)
  }

  private val anyNetworkConstraint =
      Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
  private val workManager = WorkManager.getInstance(context)

  /**
   * Used by the background service to periodically ping a location.
   *
   * Called on every actionless start of the background service — including each time the map is
   * opened — so it must be idempotent. A [PeriodicWorkRequest] runs its first iteration as soon as
   * it is enqueued, so cancel-then-enqueue published a spurious PING on every call *and* restarted
   * the interval from zero, meaning the scheduled ping never fired on its own for anyone who opened
   * the app more often than [Preferences.ping] minutes. Enqueued as unique work with KEEP: an
   * existing schedule is left running untouched.
   *
   * [replaceExisting] is for the one caller that genuinely needs to re-arm — a changed ping interval
   * — where the running schedule is stale by definition.
   */
  fun scheduleLocationPing(replaceExisting: Boolean = false) {
    val pingWorkRequest: PeriodicWorkRequest =
        PeriodicWorkRequest.Builder(
                SendLocationPingWorker::class.java, preferences.ping.toLong(), TimeUnit.MINUTES)
            .addTag(PERIODIC_TASK_SEND_LOCATION_PING)
            .setConstraints(anyNetworkConstraint)
            .build()
    Timber.d(
        "WorkManager queue task $PERIODIC_TASK_SEND_LOCATION_PING as ${pingWorkRequest.id} " +
            "with interval ${preferences.ping} minutes (replaceExisting=$replaceExisting)")
    workManager.enqueueUniquePeriodicWork(
        PERIODIC_TASK_SEND_LOCATION_PING,
        if (replaceExisting) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
        pingWorkRequest)
  }

  /** Cancels all WorkManager tasks. Called on app exit */
  fun cancelAllTasks() {
    Timber.d("Cancelling task tag (all mqtt tasks) $ONETIME_TASK_MQTT_RECONNECT")
    workManager.cancelUniqueWork(ONETIME_TASK_MQTT_RECONNECT)
    workManager.cancelAllWorkByTag(PERIODIC_TASK_SEND_LOCATION_PING)
  }

  /**
   * [expedite] replaces any already-scheduled reconnect job (which may be minutes-to-hours out on
   * WorkManager's grown retry backoff) with a fresh immediate one. Use it for genuine new signals
   * that the connection is wanted *now* — queued outbound work, a wedged publish — and never from a
   * failure path, where replacing the retrying job would reset its backoff to the first attempt on
   * every failure.
   *
   * An expedited request deliberately carries no initial delay. It is enqueued with REPLACE, so a
   * delayed one would have its countdown re-armed from zero by the next expedite — and a caller
   * that expedites faster than [RECONNECT_DELAY_SECONDS] (an outbound retry loop backing off
   * against a disconnected endpoint) could then push the reconnect out indefinitely, leaving the
   * connection down for as long as the queue kept asking for it. Expediting is a request to connect
   * now; the settling pause belongs only to the passive path that reacts to a drop.
   */
  fun scheduleMqttReconnect(expedite: Boolean = false) {
    val builder =
        OneTimeWorkRequest.Builder(MQTTReconnectWorker::class.java)
            .addTag(ONETIME_TASK_MQTT_RECONNECT)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL, MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .setConstraints(anyNetworkConstraint)
    if (!expedite && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      // Pause in case there's network turmoil
      builder.setInitialDelay(Duration.ofSeconds(RECONNECT_DELAY_SECONDS))
    }
    // KEEP by default: this is called both to arrange a fresh retry and, redundantly, from inside a
    // failed attempt of the retry job itself (MQTTReconnectWorker's own connect() failure). REPLACE
    // there would swap out the in-flight/pending job for a brand new one on every failure,
    // resetting WorkManager's exponential backoff back to its first attempt each time instead of
    // letting it grow. KEEP leaves an already-scheduled job alone and only enqueues when none
    // exists; expedite (see KDoc) is the escape hatch for nudges that must not wait out a grown
    // backoff.
    workManager.enqueueUniqueWork(
        ONETIME_TASK_MQTT_RECONNECT,
        if (expedite) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
        builder.build())
    Timber.d("Scheduled ONETIME_TASK_MQTT_RECONNECT job (expedite=$expedite)")
  }

  companion object {
    private const val PERIODIC_TASK_SEND_LOCATION_PING = "PERIODIC_TASK_SEND_LOCATION_PING"
    private const val ONETIME_TASK_MQTT_RECONNECT = "ONETIME_TASK_MQTT_RECONNECT"
    private const val RECONNECT_DELAY_SECONDS = 10L
  }

  override fun onPreferenceChanged(properties: Set<String>) {
    if (properties.contains(Preferences::ping.name)) {
      scheduleLocationPing(replaceExisting = true)
    }
  }
}
