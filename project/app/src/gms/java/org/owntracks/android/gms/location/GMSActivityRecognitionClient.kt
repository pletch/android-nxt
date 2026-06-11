package org.owntracks.android.gms.location

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresPermission
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import org.owntracks.android.location.ActivityRecognitionClient
import timber.log.Timber

/**
 * Play Services implementation of [ActivityRecognitionClient]: registers ENTER/EXIT activity
 * transitions and delivers them to [ActivityRecognitionReceiver].
 */
class GMSActivityRecognitionClient(private val context: Context) : ActivityRecognitionClient {
  private val client = ActivityRecognition.getClient(context)

  @RequiresPermission("android.permission.ACTIVITY_RECOGNITION")
  override fun requestActivityUpdates() {
    client
        .requestActivityTransitionUpdates(
            ActivityTransitionRequest(TRANSITIONS), getPendingIntent())
        .addOnSuccessListener { Timber.d("Registered for activity transition updates") }
        .addOnFailureListener { Timber.w(it, "Failed to register for activity transition updates") }
  }

  @RequiresPermission("android.permission.ACTIVITY_RECOGNITION")
  override fun removeActivityUpdates() {
    client
        .removeActivityTransitionUpdates(getPendingIntent())
        .addOnSuccessListener { Timber.d("Removed activity transition updates") }
        .addOnFailureListener { Timber.w(it, "Failed to remove activity transition updates") }
  }

  private fun getPendingIntent(): PendingIntent {
    val intent = Intent(context, ActivityRecognitionReceiver::class.java)
    val flags =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
          PendingIntent.FLAG_UPDATE_CURRENT
        }
    return PendingIntent.getBroadcast(context, ACTIVITY_TRANSITION_REQUEST_CODE, intent, flags)
  }

  companion object {
    private const val ACTIVITY_TRANSITION_REQUEST_CODE = 0

    private val MONITORED_ACTIVITIES =
        listOf(
            DetectedActivity.WALKING,
            DetectedActivity.RUNNING,
            DetectedActivity.ON_FOOT,
            DetectedActivity.STILL,
            DetectedActivity.IN_VEHICLE,
            DetectedActivity.ON_BICYCLE)

    private val TRANSITIONS =
        MONITORED_ACTIVITIES.flatMap { activity ->
          listOf(
              ActivityTransition.Builder()
                  .setActivityType(activity)
                  .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                  .build(),
              ActivityTransition.Builder()
                  .setActivityType(activity)
                  .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                  .build())
        }

    fun create(context: Context): ActivityRecognitionClient = GMSActivityRecognitionClient(context)
  }
}
