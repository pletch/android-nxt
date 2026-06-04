package org.owntracks.android.ui.mixins

import android.Manifest
import android.os.Build
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.contract.ActivityResultContracts
import timber.log.Timber

/**
 * Runtime request for the `ACTIVITY_RECOGNITION` permission. Mirrors
 * [NotificationPermissionRequester].
 */
class ActivityRecognitionPermissionRequester(
    caller: ActivityResultCaller,
    private val permissionGrantedCallback: () -> Unit,
    private val permissionDeniedCallback: () -> Unit
) {
  private val permissionRequest =
      caller.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        Timber.d("Activity recognition permission callback, result=$it")
        if (it) {
          permissionGrantedCallback()
        } else {
          permissionDeniedCallback()
        }
      }

  fun requestPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      permissionRequest.launch(Manifest.permission.ACTIVITY_RECOGNITION)
    } else {
      permissionGrantedCallback() // install-time granted below API 29
    }
  }
}
