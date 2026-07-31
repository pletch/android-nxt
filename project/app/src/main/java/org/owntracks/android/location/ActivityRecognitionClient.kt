package org.owntracks.android.location

/**
 * Source of device motion-state changes. The real implementation is gms-only
 * ([org.owntracks.android.gms.location.GMSActivityRecognitionClient]); oss binds
 * [NoopActivityRecognitionClient], so the field is simply never published there.
 */
interface ActivityRecognitionClient {
  /**
   * Registration is asynchronous and can fail (for example when Play Services isn't ready yet at
   * boot), in which case [onFailure] is invoked so the caller can retry later.
   */
  fun requestActivityUpdates(onFailure: () -> Unit = {})

  fun removeActivityUpdates()
}
