package org.owntracks.android.services

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.owntracks.android.preferences.InMemoryPreferencesStore
import org.owntracks.android.preferences.Preferences
import org.owntracks.android.preferences.types.MonitoringMode
import org.owntracks.android.test.SimpleIdlingResource

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityMonitoringModeControllerTest {
  private lateinit var preferences: Preferences

  @Before
  fun setup() {
    preferences = Preferences(InMemoryPreferencesStore(), SimpleIdlingResource("mock", true))
    preferences.autoMonitoringByActivity = true
    preferences.activityRevertDelaySeconds = REVERT_DELAY_SECONDS
    preferences.monitoring = MonitoringMode.Significant
    preferences.locatorInterval = BASE_INTERVAL
    preferences.locatorDisplacement = BASE_DISPLACEMENT
    preferences.locatorPriority = null
  }

  /** The whole point of the boost flag: the user's stored locator config is never mutated. */
  private fun assertConfiguredLocatorSettingsUntouched() {
    assertNull(preferences.locatorPriority)
    assertEquals(BASE_INTERVAL, preferences.locatorInterval)
    assertEquals(BASE_DISPLACEMENT, preferences.locatorDisplacement)
  }

  @Test
  fun `on-foot detection sets the boost flag without mutating stored locator settings`() = runTest {
    val controller = ActivityMonitoringModeController(preferences, backgroundScope)

    controller.onActivityChange(DetectedActivityChange.ON_FOOT)
    runCurrent()

    assertTrue(preferences.locatorBoostedByActivity)
    assertEquals(MonitoringMode.Significant, preferences.monitoring)
    assertConfiguredLocatorSettingsUntouched()
  }

  @Test
  fun `boost is cleared only after the revert delay`() = runTest {
    val controller = ActivityMonitoringModeController(preferences, backgroundScope)

    controller.onActivityChange(DetectedActivityChange.ON_FOOT)
    controller.onActivityChange(DetectedActivityChange.NOT_ON_FOOT)

    advanceTimeBy((REVERT_DELAY_SECONDS - 1) * 1000L)
    runCurrent()
    assertTrue(preferences.locatorBoostedByActivity) // not yet

    advanceTimeBy(2_000L)
    runCurrent()
    assertFalse(preferences.locatorBoostedByActivity)
    assertConfiguredLocatorSettingsUntouched()
  }

  @Test
  fun `renewed on-foot before the delay cancels the pending revert`() = runTest {
    val controller = ActivityMonitoringModeController(preferences, backgroundScope)

    controller.onActivityChange(DetectedActivityChange.ON_FOOT)
    controller.onActivityChange(DetectedActivityChange.NOT_ON_FOOT)
    advanceTimeBy((REVERT_DELAY_SECONDS / 2) * 1000L)
    controller.onActivityChange(DetectedActivityChange.ON_FOOT) // e.g. a pause at a crosswalk
    advanceTimeBy(REVERT_DELAY_SECONDS * 1000L)
    runCurrent()

    assertTrue(preferences.locatorBoostedByActivity)
  }

  @Test
  fun `no boost is applied when already in Move mode`() = runTest {
    preferences.monitoring = MonitoringMode.Move
    val controller = ActivityMonitoringModeController(preferences, backgroundScope)

    controller.onActivityChange(DetectedActivityChange.ON_FOOT)

    assertFalse(preferences.locatorBoostedByActivity)
  }

  @Test
  fun `no boost is applied when Precise location is not granted`() = runTest {
    val controller =
        ActivityMonitoringModeController(
            preferences, backgroundScope, hasPreciseLocation = { false })

    controller.onActivityChange(DetectedActivityChange.ON_FOOT)

    assertFalse(preferences.locatorBoostedByActivity)
    assertConfiguredLocatorSettingsUntouched()
  }

  @Test
  fun `disabling the feature while boosted clears the boost immediately`() = runTest {
    val controller = ActivityMonitoringModeController(preferences, backgroundScope)

    controller.onActivityChange(DetectedActivityChange.ON_FOOT)
    assertTrue(preferences.locatorBoostedByActivity)

    controller.onFeatureDisabled()
    assertFalse(preferences.locatorBoostedByActivity)
  }

  @Test
  fun `onServiceStart clears a stale boost left by a previous process`() = runTest {
    preferences.locatorBoostedByActivity = true // simulate a process killed mid-walk

    ActivityMonitoringModeController(preferences, backgroundScope).onServiceStart()

    assertFalse(preferences.locatorBoostedByActivity)
  }

  @Test
  fun `a manual monitoring change clears the boost and backs off`() = runTest {
    val controller = ActivityMonitoringModeController(preferences, backgroundScope)

    controller.onActivityChange(DetectedActivityChange.ON_FOOT)
    preferences.monitoring = MonitoringMode.Quiet
    controller.onMonitoringModeChangedExternally(MonitoringMode.Quiet)

    assertFalse(preferences.locatorBoostedByActivity)

    // suppressed: on-foot alone (no still since the override) must not re-boost
    controller.onActivityChange(DetectedActivityChange.ON_FOOT)
    assertFalse(preferences.locatorBoostedByActivity)
  }

  @Test
  fun `auto-boost resumes only after a clean still then on-foot cycle following an override`() =
      runTest {
        val controller = ActivityMonitoringModeController(preferences, backgroundScope)

        controller.onActivityChange(DetectedActivityChange.ON_FOOT)
        preferences.monitoring = MonitoringMode.Quiet
        controller.onMonitoringModeChangedExternally(MonitoringMode.Quiet) // suppress

        controller.onActivityChange(DetectedActivityChange.ON_FOOT) // no still yet -> ignored
        assertFalse(preferences.locatorBoostedByActivity)

        controller.onActivityChange(DetectedActivityChange.NOT_ON_FOOT) // the "still" half
        controller.onActivityChange(DetectedActivityChange.ON_FOOT) // resume + boost

        assertTrue(preferences.locatorBoostedByActivity)
      }

  @Test
  fun `a manual monitoring change cancels a pending revert without leaving a boost`() = runTest {
    val controller = ActivityMonitoringModeController(preferences, backgroundScope)

    controller.onActivityChange(DetectedActivityChange.ON_FOOT)
    controller.onActivityChange(DetectedActivityChange.NOT_ON_FOOT) // revert pending

    preferences.monitoring = MonitoringMode.Quiet
    controller.onMonitoringModeChangedExternally(MonitoringMode.Quiet) // clears + cancels revert

    advanceTimeBy(REVERT_DELAY_SECONDS * 1000L + 1000L)
    runCurrent()

    assertFalse(preferences.locatorBoostedByActivity)
  }

  companion object {
    private const val REVERT_DELAY_SECONDS = 180
    private const val BASE_INTERVAL = 60
    private const val BASE_DISPLACEMENT = 500
  }
}
