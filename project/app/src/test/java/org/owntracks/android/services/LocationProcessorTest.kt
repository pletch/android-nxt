package org.owntracks.android.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationProcessorTest {

  @Test
  fun `normal driving speed is plausible`() {
    // 1000m in 60s = 60 km/h
    assertTrue(isPlausibleSpeed(1000f, 60.0, maxSpeedKmh = 1000))
  }

  @Test
  fun `commercial flight speed is plausible`() {
    // 150km in 900s (15 min) = 600 km/h
    assertTrue(isPlausibleSpeed(150_000f, 900.0, maxSpeedKmh = 1000))
  }

  @Test
  fun `a teleport jump is not plausible`() {
    // 50km in 5s implies 36000 km/h - a cell-tower bounce, not real movement
    assertFalse(isPlausibleSpeed(50_000f, 5.0, maxSpeedKmh = 1000))
  }

  @Test
  fun `zero maxSpeedKmh disables the check`() {
    assertTrue(isPlausibleSpeed(50_000f, 5.0, maxSpeedKmh = 0))
  }

  @Test
  fun `negative maxSpeedKmh disables the check`() {
    assertTrue(isPlausibleSpeed(50_000f, 5.0, maxSpeedKmh = -1))
  }

  @Test
  fun `zero or negative dtSeconds does not false-positive`() {
    assertTrue(isPlausibleSpeed(50_000f, 0.0, maxSpeedKmh = 1000))
    assertTrue(isPlausibleSpeed(50_000f, -5.0, maxSpeedKmh = 1000))
  }

  @Test
  fun `speed right at the threshold is plausible`() {
    // 1000km in 3600s = exactly 1000 km/h
    assertTrue(isPlausibleSpeed(1_000_000f, 3600.0, maxSpeedKmh = 1000))
  }

  @Test
  fun `speed just over the threshold is not plausible`() {
    assertFalse(isPlausibleSpeed(1_000_001f, 3600.0, maxSpeedKmh = 1000))
  }
}
