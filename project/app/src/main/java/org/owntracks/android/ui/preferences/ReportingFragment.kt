package org.owntracks.android.ui.preferences

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.owntracks.android.R
import org.owntracks.android.preferences.Preferences
import org.owntracks.android.support.RequirementsChecker
import timber.log.Timber

@AndroidEntryPoint
class ReportingFragment : AbstractPreferenceFragment() {
  @Inject lateinit var requirementsChecker: RequirementsChecker

  private val publishMotionActivitiesPreference: SwitchPreferenceCompat?
    get() = findPreference(Preferences::publishMotionActivities.name)

  // Publishing motionactivities needs activity recognition, which is a runtime permission from API
  // 29. Turning the preference back off on a denial keeps it honest about what's actually being
  // published, rather than leaving it on while reporting nothing.
  private val activityRecognitionPermissionRequest =
      registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Timber.d("Activity recognition permission callback, result=$granted")
        if (!granted) {
          publishMotionActivitiesPreference?.isChecked = false
        }
      }

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    super.onCreatePreferences(savedInstanceState, rootKey)
    setPreferencesFromResource(R.xml.preferences_reporting, rootKey)
    publishMotionActivitiesPreference?.onPreferenceChangeListener =
        Preference.OnPreferenceChangeListener { _, newValue ->
          if (newValue == true &&
              Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
              !requirementsChecker.hasActivityRecognitionPermission()) {
            activityRecognitionPermissionRequest.launch(Manifest.permission.ACTIVITY_RECOGNITION)
          }
          true
        }
  }
}
