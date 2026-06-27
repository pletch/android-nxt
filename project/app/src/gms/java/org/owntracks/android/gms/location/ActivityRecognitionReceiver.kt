package org.owntracks.android.gms.location

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import org.owntracks.android.services.BackgroundService
import org.owntracks.android.services.DetectedActivityChange
import org.owntracks.android.ui.mixins.ServiceStarter
import timber.log.Timber

/**
 * Receives activity events from Google Play Services and forwards them to [BackgroundService] (as
 * [DetectedActivityChange] ordinals) — keeping GMS types out of `main`. Handles two delivery types:
 * the ongoing ENTER/EXIT transitions, and the one-shot current-activity sample requested at
 * registration to seed an activity already in progress (see [GMSActivityRecognitionClient]).
 */
class ActivityRecognitionReceiver : BroadcastReceiver(), ServiceStarter by ServiceStarter.Impl() {
  override fun onReceive(context: Context, intent: Intent) {
    when {
      ActivityTransitionResult.hasResult(intent) -> handleTransitionResult(context, intent)
      ActivityRecognitionResult.hasResult(intent) -> handleSampleResult(context, intent)
      else -> Timber.d("Received intent without an activity result; ignoring")
    }
  }

  private fun handleTransitionResult(context: Context, intent: Intent) {
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
    forwardChanges(context, changeOrdinals)
  }

  /**
   * Handles the one-shot current-activity sample: cancel the sampling updates (so it stays
   * one-shot), then forward the most-probable activity if it's confident enough and one we track.
   */
  @SuppressLint("MissingPermission")
  private fun handleSampleResult(context: Context, intent: Intent) {
    ActivityRecognition.getClient(context)
        .removeActivityUpdates(GMSActivityRecognitionClient.getSamplePendingIntent(context))
    val result = ActivityRecognitionResult.extractResult(intent) ?: return
    val mostProbable = result.mostProbableActivity
    Timber.i(
        "Current-activity sample: ${activityName(mostProbable.type)} " +
            "(confidence=${mostProbable.confidence})")
    if (mostProbable.confidence < MIN_SAMPLE_CONFIDENCE) {
      Timber.d("Current-activity sample below confidence threshold; not seeding")
      return
    }
    val change = activityChange(mostProbable.type) ?: return
    forwardChanges(context, mutableListOf(change.ordinal))
  }

  private fun forwardChanges(context: Context, changeOrdinals: List<Int>) {
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
        DetectedActivity.ON_BICYCLE -> DetectedActivityChange.CYCLING
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

  companion object {
    // Only seed from a sample we're reasonably sure about, so a low-confidence guess (the API
    // reports 0-100) can't spuriously boost the locator.
    private const val MIN_SAMPLE_CONFIDENCE = 50
  }
}
