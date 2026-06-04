package org.owntracks.android.ui

import android.Manifest
import androidx.test.filters.MediumTest
import androidx.test.rule.GrantPermissionRule
import com.adevinta.android.barista.assertion.BaristaVisibilityAssertions.assertContains
import com.adevinta.android.barista.assertion.BaristaVisibilityAssertions.assertDisplayed
import com.adevinta.android.barista.interaction.BaristaClickInteractions.clickBack
import com.adevinta.android.barista.interaction.BaristaClickInteractions.clickOn
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.owntracks.android.BuildConfig
import org.owntracks.android.R
import org.owntracks.android.testutils.TestWithAnActivity
import org.owntracks.android.testutils.scrollToPreferenceWithText
import org.owntracks.android.testutils.writeToPreference
import org.owntracks.android.ui.preferences.PreferencesActivity

/**
 * Instrumented tests for the opt-in activity-triggered adaptive monitoring settings.
 *
 * Activity Recognition is a Google Play Services feature, so the preferences only exist in the
 * `gms` flavor; the tests are skipped (via [assumeTrue]) on `oss`. Permissions are pre-granted with
 * [GrantPermissionRule] so enabling the toggle doesn't have to drive a system permission dialog.
 */
@MediumTest
@HiltAndroidTest
class ActivityMonitoringPreferenceTests : TestWithAnActivity<PreferencesActivity>() {

  @get:Rule
  val activityRecognitionPermissionRule: GrantPermissionRule =
      GrantPermissionRule.grant(
          Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.ACCESS_FINE_LOCATION)

  @Test
  fun activity_boost_toggle_and_tuning_values_are_reflected_in_the_configuration() {
    assumeTrue(
        "Activity-triggered monitoring is a Google Play Services (gms) only feature",
        BuildConfig.FLAVOR == "gms")

    clickOn(R.string.preferencesAdvanced)

    scrollToPreferenceWithText(R.string.preferencesAutoMonitoringByActivity)
    assertDisplayed(R.string.preferencesAutoMonitoringByActivity)
    clickOn(R.string.preferencesAutoMonitoringByActivity)

    // The tuning preferences depend on the toggle, so they're only editable once it's enabled.
    writeToPreference(R.string.preferencesActivityOnFootLocatorInterval, "20")
    writeToPreference(R.string.preferencesActivityOnFootLocatorDisplacement, "15")
    writeToPreference(R.string.preferencesActivityRevertDelay, "90")

    clickBack()
    clickOn(R.string.configurationManagement)

    assertContains(R.id.effectiveConfiguration, "\"autoMonitoringByActivity\": true")
    assertContains(R.id.effectiveConfiguration, "\"activityOnFootLocatorInterval\": 20")
    assertContains(R.id.effectiveConfiguration, "\"activityOnFootLocatorDisplacement\": 15")
    assertContains(R.id.effectiveConfiguration, "\"activityRevertDelaySeconds\": 90")
  }
}
