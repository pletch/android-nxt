package org.owntracks.android.gms.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import org.owntracks.android.services.BackgroundService
import org.owntracks.android.services.DetectedActivityChange
import org.owntracks.android.ui.mixins.ServiceStarter
import timber.log.Timber

/**
 * Receives activity-transition events from Google Play Services, logs them, and forwards the
 * on-foot / in-vehicle / still ENTER transitions to [BackgroundService] (as
 * [DetectedActivityChange] ordinals) — keeping GMS types out of `main`.
 */
class ActivityRecognitionReceiver : BroadcastReceiver(), ServiceStarter by ServiceStarter.Impl() {
  override fun onReceive(context: Context, intent: Intent) {
    if (!ActivityTransitionResult.hasResult(intent)) {
      Timber.d("Received intent without an activity transition result; ignoring")
      return
    }
    val result = ActivityTransitionResult.extractResult(intent) ?: return
    val changeOrdinals = mutableListOf<Int>()
    result.transitionEvents.forEach { event ->
      Timber.i(
          "Activity transition: ${activityName(event.activityType)} " +
              "${transitionName(event.transitionType)} (elapsedRealtime=${event.elapsedRealTimeNanos}ns)")
      if (event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
        activityChange(event.activityType)?.let { changeOrdinals.add(it.ordinal) }
      }
    }
    if (changeOrdinals.isNotEmpty()) {
      startService(
          context,
          BackgroundService.INTENT_ACTION_ACTIVITY_TRANSITION,
          Intent()
              .putExtra(
                  BackgroundService.EXTRA_ACTIVITY_CHANGE_ORDINALS, changeOrdinals.toIntArray()))
    }
  }

  /** Maps a detected activity to a [DetectedActivityChange], or null for activities we ignore. */
  private fun activityChange(activityType: Int): DetectedActivityChange? =
      when (activityType) {
        DetectedActivity.WALKING,
        DetectedActivity.RUNNING,
        DetectedActivity.ON_FOOT -> DetectedActivityChange.ON_FOOT
        DetectedActivity.IN_VEHICLE -> DetectedActivityChange.IN_VEHICLE
        DetectedActivity.STILL -> DetectedActivityChange.STILL
        else -> null
      }

  private fun activityName(type: Int): String =
      when (type) {
        DetectedActivity.IN_VEHICLE -> "IN_VEHICLE"
        DetectedActivity.ON_BICYCLE -> "ON_BICYCLE"
        DetectedActivity.ON_FOOT -> "ON_FOOT"
        DetectedActivity.RUNNING -> "RUNNING"
        DetectedActivity.STILL -> "STILL"
        DetectedActivity.WALKING -> "WALKING"
        else -> "UNKNOWN($type)"
      }

  private fun transitionName(type: Int): String =
      when (type) {
        ActivityTransition.ACTIVITY_TRANSITION_ENTER -> "ENTER"
        ActivityTransition.ACTIVITY_TRANSITION_EXIT -> "EXIT"
        else -> "UNKNOWN($type)"
      }
}
