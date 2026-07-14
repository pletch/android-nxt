package org.owntracks.android.location

import timber.log.Timber

class NoopActivityRecognitionClient : ActivityRecognitionClient {
  override fun requestActivityUpdates(onFailure: () -> Unit) {
    Timber.d("Activity recognition is unavailable without Google Play Services")
  }

  override fun removeActivityUpdates() {}
}
