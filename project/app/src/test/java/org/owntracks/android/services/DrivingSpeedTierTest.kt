package org.owntracks.android.services

import org.junit.Assert.assertEquals
import org.junit.Test

class DrivingSpeedTierTest {
  // Starting from the default interval, the band reflects the raw speed (no current band to stick).
  @Test
  fun `interval steps up as speed increases from default`() {
    val d = DrivingSpeedTier.DEFAULT_INTERVAL_SECONDS
    assertEquals(7, DrivingSpeedTier.intervalSecondsForSpeed(10f, d)) // city
    assertEquals(12, DrivingSpeedTier.intervalSecondsForSpeed(50f, d)) // arterial
    assertEquals(16, DrivingSpeedTier.intervalSecondsForSpeed(90f, d)) // highway
    assertEquals(20, DrivingSpeedTier.intervalSecondsForSpeed(130f, d)) // motorway
  }

  @Test
  fun `band is sticky within the hysteresis margin around a boundary`() {
    // Currently in the city band (7s); 32 km/h is just past the 30 boundary but within the 8
    // margin,
    // so we stay in the city band rather than flapping.
    assertEquals(7, DrivingSpeedTier.intervalSecondsForSpeed(32f, 7))
    // Clear the boundary by the margin (>= 38) to move up to the arterial band.
    assertEquals(12, DrivingSpeedTier.intervalSecondsForSpeed(38f, 7))
  }

  @Test
  fun `dropping speed only steps down past the lower boundary minus margin`() {
    // In the arterial band (12s); 25 km/h is below 30 but not below 30-8=22, so stay put.
    assertEquals(12, DrivingSpeedTier.intervalSecondsForSpeed(25f, 12))
    // Below 22 drops back to the city band.
    assertEquals(7, DrivingSpeedTier.intervalSecondsForSpeed(20f, 12))
  }

  @Test
  fun `large speed jumps cross multiple bands at once`() {
    assertEquals(20, DrivingSpeedTier.intervalSecondsForSpeed(130f, 7))
    assertEquals(7, DrivingSpeedTier.intervalSecondsForSpeed(0f, 20))
  }

  @Test
  fun `mps converts to kmh`() {
    assertEquals(36f, DrivingSpeedTier.mpsToKmh(10f), 0.001f)
  }
}
