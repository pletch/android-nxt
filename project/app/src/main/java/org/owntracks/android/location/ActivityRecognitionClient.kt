package org.owntracks.android.location

/**
 * Source of activity-transition events for activity-triggered monitoring. The real implementation
 * is gms-only ([org.owntracks.android.gms.location.GMSActivityRecognitionClient]); oss binds a
 * no-op.
 */
interface ActivityRecognitionClient {
  /**
   * Registration is asynchronous and can fail (e.g. Play Services not ready yet at boot);
   * [onFailure] lets the caller reset its registered-state tracking so a later attempt retries.
   */
  fun requestActivityUpdates(onFailure: () -> Unit = {})

  fun removeActivityUpdates()
}
