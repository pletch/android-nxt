package org.owntracks.android.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.owntracks.android.location.LocatorPriority
import org.owntracks.android.preferences.types.MonitoringMode

class EffectiveLocatorSettingsTest {
  private fun compute(
      monitoring: MonitoringMode,
      boosted: Boolean,
      locatorPriority: LocatorPriority? = null,
  ) =
      effectiveLocatorSettings(
          monitoring = monitoring,
          locatorPriority = locatorPriority,
          locatorInterval = 60,
          locatorDisplacement = 500,
          moveModeLocatorInterval = 10,
          boostedByActivity = boosted,
          activityOnFootLocatorInterval = 25,
          activityOnFootLocatorDisplacement = 30)

  @Test
  fun `significant mode uses balanced accuracy with the configured interval and displacement`() {
    val s = compute(MonitoringMode.Significant, boosted = false)
    assertEquals(LocatorPriority.BalancedPowerAccuracy, s.priority)
    assertEquals(60, s.intervalSeconds)
    assertEquals(500, s.smallestDisplacement)
  }

  @Test
  fun `move mode uses high accuracy, the move interval and no displacement filter`() {
    val s = compute(MonitoringMode.Move, boosted = false)
    assertEquals(LocatorPriority.HighAccuracy, s.priority)
    assertEquals(10, s.intervalSeconds)
    assertNull(s.smallestDisplacement)
  }

  @Test
  fun `quiet and manual modes default to low power`() {
    assertEquals(LocatorPriority.LowPower, compute(MonitoringMode.Quiet, boosted = false).priority)
    assertEquals(LocatorPriority.LowPower, compute(MonitoringMode.Manual, boosted = false).priority)
  }

  @Test
  fun `an explicit locatorPriority overrides the per-mode default when not boosted`() {
    val s =
        compute(
            MonitoringMode.Significant, boosted = false, locatorPriority = LocatorPriority.LowPower)
    assertEquals(LocatorPriority.LowPower, s.priority)
  }

  @Test
  fun `the boost elevates a non-move mode to high accuracy with the on-foot interval-displacement`() {
    val s = compute(MonitoringMode.Significant, boosted = true)
    assertEquals(LocatorPriority.HighAccuracy, s.priority)
    assertEquals(25, s.intervalSeconds)
    assertEquals(30, s.smallestDisplacement)
  }

  @Test
  fun `the boost overrides an explicit lower priority`() {
    val s =
        compute(
            MonitoringMode.Significant, boosted = true, locatorPriority = LocatorPriority.LowPower)
    assertEquals(LocatorPriority.HighAccuracy, s.priority)
    assertEquals(25, s.intervalSeconds)
  }

  @Test
  fun `the boost is ignored in move mode`() {
    val s = compute(MonitoringMode.Move, boosted = true)
    assertEquals(LocatorPriority.HighAccuracy, s.priority)
    assertEquals(10, s.intervalSeconds)
    assertNull(s.smallestDisplacement)
  }
}
