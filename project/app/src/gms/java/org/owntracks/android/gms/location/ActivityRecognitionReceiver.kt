package org.owntracks.android.gms.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import org.owntracks.android.location.DetectedActivityChange
import org.owntracks.android.services.BackgroundService
import org.owntracks.android.ui.mixins.ServiceStarter
import timber.log.Timber

/**
 * Receives activity transitions from Play Services and forwards the ones we care about to
 * [BackgroundService] as [DetectedActivityChange] ordinals, keeping the GMS types out of `main`.
 */
class ActivityRecognitionReceiver : BroadcastReceiver(), ServiceStarter by ServiceStarter.Impl() {
  override fun onReceive(context: Context, intent: Intent) {
    if (!ActivityTransitionResult.hasResult(intent)) {
      Timber.d("Received intent without an activity transition result, ignoring")
      return
    }
    val result = ActivityTransitionResult.extractResult(intent) ?: return
    val changes =
        result.transitionEvents
            .filter { it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER }
            .mapNotNull { activityChange(it.activityType) }
            .distinct()
    if (changes.isEmpty()) {
      return
    }
    Timber.i("Entered activities: ${changes.joinToString()}")
    startService(
        context,
        BackgroundService.INTENT_ACTION_ACTIVITY_CHANGE,
        Intent()
            .putExtra(
                BackgroundService.EXTRA_ACTIVITY_CHANGE_ORDINALS,
                changes.map(DetectedActivityChange::ordinal).toIntArray()))
  }

  /** Maps a detected activity onto a [DetectedActivityChange], or null for ones we don't track. */
  private fun activityChange(activityType: Int): DetectedActivityChange? =
      when (activityType) {
        DetectedActivity.WALKING,
        DetectedActivity.RUNNING,
        DetectedActivity.ON_FOOT -> DetectedActivityChange.ON_FOOT
        DetectedActivity.IN_VEHICLE -> DetectedActivityChange.IN_VEHICLE
        DetectedActivity.STILL -> DetectedActivityChange.STILL
        DetectedActivity.ON_BICYCLE -> DetectedActivityChange.CYCLING
        else -> null
      }
}
