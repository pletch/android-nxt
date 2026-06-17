package org.owntracks.android.ui.preferences

import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.os.Bundle
import android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import org.owntracks.android.location.LocatorPriority
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.owntracks.android.BuildConfig
import org.owntracks.android.R
import org.owntracks.android.preferences.Preferences
import org.owntracks.android.preferences.types.ReverseGeocodeProvider
import org.owntracks.android.support.RequirementsChecker
import org.owntracks.android.ui.mixins.ActivityRecognitionPermissionRequester
import org.owntracks.android.ui.mixins.LocationPermissionRequester

@AndroidEntryPoint
class AdvancedFragment @Inject constructor() :
    AbstractPreferenceFragment(), Preferences.OnPreferenceChangeListener {
  @Inject lateinit var requirementsChecker: RequirementsChecker

  private val activityRecognitionPermissionRequester =
      ActivityRecognitionPermissionRequester(
          this,
          ::activityRecognitionPermissionGranted,
          ::activityRecognitionPermissionDenied,
      )

  private val locationPermissionRequester =
      LocationPermissionRequester(
          this,
          ::onPreciseLocationRequestResult,
          ::onPreciseLocationRequestResult,
      )

  override fun onAttach(context: Context) {
    super.onAttach(context)
    preferences.registerOnPreferenceChangedListener(this)
  }

  override fun onDetach() {
    super.onDetach()
    preferences.unregisterOnPreferenceChangedListener(this)
  }

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    super.onCreatePreferences(savedInstanceState, rootKey)
    setPreferencesFromResource(R.xml.preferences_advanced, rootKey)
    val remoteConfigurationPreference =
        findPreference<SwitchPreferenceCompat>(Preferences::remoteConfiguration.name)
    val remoteCommandPreference = findPreference<SwitchPreferenceCompat>(Preferences::cmd.name)
    val remoteCommandAndConfigurationChangeListener =
        Preference.OnPreferenceChangeListener { preference, newValue ->
          if (newValue is Boolean) {
            when (preference.key) {
              Preferences::cmd.name ->
                  if (!newValue) {
                    remoteConfigurationPreference?.isChecked = false
                  }
              Preferences::remoteConfiguration.name ->
                  if (newValue) {
                    remoteCommandPreference?.isChecked = true
                  }
            }
          }
          true
        }
    remoteConfigurationPreference?.onPreferenceChangeListener =
        remoteCommandAndConfigurationChangeListener
    remoteCommandPreference?.onPreferenceChangeListener =
        remoteCommandAndConfigurationChangeListener

    findPreference<Preference>("autostartWarning")?.isVisible =
        !requirementsChecker.hasBackgroundLocationPermission()

    // Activity Recognition is a Play Services API, so the feature is gms-only.
    val activityRecognitionAvailable = BuildConfig.FLAVOR == "gms"
    findPreference<SwitchPreferenceCompat>(Preferences::autoMonitoringByActivity.name)?.apply {
      isVisible = activityRecognitionAvailable
      onPreferenceChangeListener =
          Preference.OnPreferenceChangeListener { _, newValue ->
            if (newValue == true && !requirementsChecker.hasActivityRecognitionPermission()) {
              // Defer enabling until the permission is granted (see the granted callback).
              activityRecognitionPermissionRequester.requestPermission()
              false
            } else {
              true
            }
          }
    }
    listOf(
            Preferences::activityOnFootLocatorInterval.name,
            Preferences::activityOnFootLocatorDisplacement.name,
            Preferences::activityRevertDelaySeconds.name,
        )
        .forEach { findPreference<Preference>(it)?.isVisible = activityRecognitionAvailable }
    findPreference<Preference>("autoMonitoringByActivityPreciseWarning")
        ?.setOnPreferenceClickListener {
          // Re-request fine location; on API 31+ this offers the Precise/Approximate upgrade
          // dialog.
          locationPermissionRequester.requestLocationPermissions(
              context = requireContext(), showPermissionRationale = { false })
          true
        }
    refreshActivityRecognitionPreciseWarning()

    findPreference<SwitchPreferenceCompat>("useGnss")?.apply {
      isChecked = preferences.locatorPriority == LocatorPriority.HighAccuracy
      onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
        preferences.locatorPriority =
            if (newValue as Boolean) LocatorPriority.HighAccuracy else null
        true
      }
    }

    findPreference<ListPreference>(Preferences::reverseGeocodeProvider.name)
        ?.onPreferenceChangeListener =
        Preference.OnPreferenceChangeListener { preference, newValue ->
          if (newValue == ReverseGeocodeProvider.OpenCage.name) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.preferencesAdvancedOpencagePrivacyDialogTitle)
                .setMessage(R.string.preferencesAdvancedOpencagePrivacyDialogMessage)
                .setPositiveButton(R.string.preferencesAdvancedOpencagePrivacyDialogAccept) { _, _
                  ->
                  (preference as ListPreference).value = newValue.toString()
                }
                .setNegativeButton(R.string.preferencesAdvancedOpencagePrivacyDialogCancel, null)
                .create()
                .apply { show() }
                .findViewById<TextView>(android.R.id.message)
                ?.movementMethod = android.text.method.LinkMovementMethod.getInstance()
            false
          } else {
            true
          }
        }
    setOpenCageAPIKeyPreferenceVisibility()
  }

  private fun activityRecognitionPermissionGranted() {
    // Persisting the checked state writes the preference, which the BackgroundService observes and
    // uses to register for activity transitions.
    findPreference<SwitchPreferenceCompat>(Preferences::autoMonitoringByActivity.name)?.isChecked =
        true
  }

  private fun activityRecognitionPermissionDenied() {
    Toast.makeText(
            requireContext(),
            R.string.preferencesAutoMonitoringByActivityPermissionDenied,
            Toast.LENGTH_LONG)
        .show()
  }

  private fun setOpenCageAPIKeyPreferenceVisibility() {
    setOf(Preferences::opencageApiKey.name, "opencagePrivacy").forEach {
      findPreference<Preference>(it)?.isVisible =
          preferences.reverseGeocodeProvider == ReverseGeocodeProvider.OpenCage
    }
  }

  override fun onResume() {
    super.onResume()
    // The user may have changed the Precise/Approximate location grant in system settings.
    refreshActivityRecognitionPreciseWarning()
  }

  override fun onPreferenceChanged(properties: Set<String>) {
    if (properties.contains(Preferences::reverseGeocodeProvider.name)) {
      setOpenCageAPIKeyPreferenceVisibility()
    }
    if (properties.contains(Preferences::autoMonitoringByActivity.name)) {
      refreshActivityRecognitionPreciseWarning()
    }
    if (properties.contains(Preferences::locatorPriority.name)) {
      findPreference<SwitchPreferenceCompat>("useGnss")?.isChecked =
          preferences.locatorPriority == LocatorPriority.HighAccuracy
    }
  }

  private fun onPreciseLocationRequestResult(@Suppress("UNUSED_PARAMETER") code: Int) {
    refreshActivityRecognitionPreciseWarning()
    // Precise still missing and the system won't prompt again: Settings is the only path left.
    if (isAdded &&
        !requirementsChecker.hasPreciseLocationPermission() &&
        !shouldShowRequestPermissionRationale(ACCESS_FINE_LOCATION)) {
      promptOpenAppSettingsForPreciseLocation()
    }
  }

  private fun promptOpenAppSettingsForPreciseLocation() {
    MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.preferencesAutoMonitoringByActivityPreciseSettingsTitle)
        .setMessage(R.string.preferencesAutoMonitoringByActivityPreciseSettingsMessage)
        .setPositiveButton(R.string.preferencesAutoMonitoringByActivityPreciseSettingsButton) { _, _
          ->
          startActivity(
              Intent(ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:${requireContext().packageName}".toUri()
                flags = FLAG_ACTIVITY_NEW_TASK
              })
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  /** Shows the warning when the feature is on but only Approximate location is granted. */
  private fun refreshActivityRecognitionPreciseWarning() {
    findPreference<Preference>("autoMonitoringByActivityPreciseWarning")?.isVisible =
        BuildConfig.FLAVOR == "gms" &&
            preferences.autoMonitoringByActivity &&
            !requirementsChecker.hasPreciseLocationPermission()
  }
}
