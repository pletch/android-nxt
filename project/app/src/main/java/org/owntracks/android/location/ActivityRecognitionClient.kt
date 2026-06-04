package org.owntracks.android.location

/**
 * Source of activity-transition events for activity-triggered monitoring. The real implementation
 * is gms-only ([org.owntracks.android.gms.location.GMSActivityRecognitionClient]); oss binds a
 * no-op.
 */
interface ActivityRecognitionClient {
  fun requestActivityUpdates()

  fun removeActivityUpdates()
}
