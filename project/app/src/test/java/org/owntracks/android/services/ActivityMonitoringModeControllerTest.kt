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
    controller.onActivityChange(DetectedActivityChange.STILL)

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
    controller.onActivityChange(DetectedActivityChange.STILL)
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

        controller.onActivityChange(DetectedActivityChange.STILL) // the "still" half
        controller.onActivityChange(DetectedActivityChange.ON_FOOT) // resume + boost

        assertTrue(preferences.locatorBoostedByActivity)
      }

  @Test
  fun `a manual monitoring change cancels a pending revert without leaving a boost`() = runTest {
    val controller = ActivityMonitoringModeController(preferences, backgroundScope)

    controller.onActivityChange(DetectedActivityChange.ON_FOOT)
    controller.onActivityChange(DetectedActivityChange.STILL) // revert pending

    preferences.monitoring = MonitoringMode.Quiet
    controller.onMonitoringModeChangedExternally(MonitoringMode.Quiet) // clears + cancels revert

    advanceTimeBy(REVERT_DELAY_SECONDS * 1000L + 1000L)
    runCurrent()

    assertFalse(preferences.locatorBoostedByActivity)
  }

  @Test
  fun `in-vehicle detection sets the driving boost flag, not the on-foot one`() = runTest {
    val controller = ActivityMonitoringModeController(preferences, backgroundScope)

    controller.onActivityChange(DetectedActivityChange.IN_VEHICLE)
    runCurrent()

    assertTrue(preferences.locatorBoostedByDriving)
    assertFalse(preferences.locatorBoostedByActivity)
    assertConfiguredLocatorSettingsUntouched()
  }

  @Test
  fun `switching from walking to driving moves the boost between flags`() = runTest {
    val controller = ActivityMonitoringModeController(preferences, backgroundScope)

    controller.onActivityChange(DetectedActivityChange.ON_FOOT)
    runCurrent()
    assertTrue(preferences.locatorBoostedByActivity)

    controller.onActivityChange(DetectedActivityChange.IN_VEHICLE)
    runCurrent()
    assertFalse(preferences.locatorBoostedByActivity)
    assertTrue(preferences.locatorBoostedByDriving)
  }

  @Test
  fun `becoming still reverts the driving boost after the delay`() = runTest {
    val controller = ActivityMonitoringModeController(preferences, backgroundScope)

    controller.onActivityChange(DetectedActivityChange.IN_VEHICLE)
    controller.onActivityChange(DetectedActivityChange.STILL)
    assertTrue(preferences.locatorBoostedByDriving) // still boosted during the slow-out window

    advanceTimeBy(REVERT_DELAY_SECONDS * 1000L + 1000L)
    runCurrent()

    assertFalse(preferences.locatorBoostedByDriving)
  }

  @Test
  fun `with an entry delay the boost waits for sustained activity`() = runTest {
    preferences.activityEntryDelaySeconds = ENTRY_DELAY_SECONDS
    val controller = ActivityMonitoringModeController(preferences, backgroundScope)

    controller.onActivityChange(DetectedActivityChange.ON_FOOT)
    runCurrent()
    assertFalse(preferences.locatorBoostedByActivity) // dwell not elapsed yet

    advanceTimeBy(ENTRY_DELAY_SECONDS * 1000L + 1000L)
    runCurrent()
    assertTrue(preferences.locatorBoostedByActivity)
  }

  @Test
  fun `a brief activity that stops before the entry delay never boosts`() = runTest {
    preferences.activityEntryDelaySeconds = ENTRY_DELAY_SECONDS
    val controller = ActivityMonitoringModeController(preferences, backgroundScope)

    controller.onActivityChange(DetectedActivityChange.ON_FOOT) // e.g. a walk to the kitchen
    advanceTimeBy(10_000L) // less than the entry delay
    controller.onActivityChange(DetectedActivityChange.STILL) // stopped before the dwell elapsed

    advanceTimeBy(ENTRY_DELAY_SECONDS * 1000L + 1000L)
    runCurrent()
    assertFalse(preferences.locatorBoostedByActivity) // never boosted
  }

  @Test
  fun `entry delay does not re-apply once boosted - transport switch is immediate`() = runTest {
    preferences.activityEntryDelaySeconds = ENTRY_DELAY_SECONDS
    val controller = ActivityMonitoringModeController(preferences, backgroundScope)

    controller.onActivityChange(DetectedActivityChange.ON_FOOT)
    advanceTimeBy(ENTRY_DELAY_SECONDS * 1000L + 1000L)
    runCurrent()
    assertTrue(preferences.locatorBoostedByActivity)

    // Already boosted: switching to driving applies immediately, no second dwell.
    controller.onActivityChange(DetectedActivityChange.IN_VEHICLE)
    runCurrent()
    assertFalse(preferences.locatorBoostedByActivity)
    assertTrue(preferences.locatorBoostedByDriving)
  }

  @Test
  fun `detected activity maps to the OwnTracks motionactivities vocabulary`() {
    assertEquals(listOf("walking"), DetectedActivityChange.ON_FOOT.toMotionActivities())
    assertEquals(listOf("automotive"), DetectedActivityChange.IN_VEHICLE.toMotionActivities())
    assertEquals(listOf("stationary"), DetectedActivityChange.STILL.toMotionActivities())
  }

  companion object {
    private const val REVERT_DELAY_SECONDS = 180
    private const val ENTRY_DELAY_SECONDS = 30
    private const val BASE_INTERVAL = 60
    private const val BASE_DISPLACEMENT = 500
  }
}
