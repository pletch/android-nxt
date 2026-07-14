package org.owntracks.android.services

import org.junit.Assert.assertEquals
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

  // Jump gate: implausible jumps and suspicious post-gap jumps are withheld until corroborated.

  @Test
  fun `an ordinary small move publishes immediately`() {
    // 800m in 60s = 48 km/h, well under the quarantine distance
    assertEquals(
        JumpGateDecision.PUBLISH,
        evaluateJumpGate(800f, 60.0, null, null, maxSpeedKmh = 1000))
  }

  @Test
  fun `a teleport jump is withheld`() {
    // 50km in 5s: implausible at any setting
    assertEquals(
        JumpGateDecision.WITHHOLD,
        evaluateJumpGate(50_000f, 5.0, null, null, maxSpeedKmh = 1000))
  }

  @Test
  fun `a plausible-speed jump after a long gap is quarantined, not published`() {
    // The gap-blindness case: 50km after an hour implies only 50 km/h, but a stationary device's
    // first post-gap fix is disproportionately likely to be a network bounce — hold it.
    assertEquals(
        JumpGateDecision.WITHHOLD,
        evaluateJumpGate(50_000f, 3600.0, null, null, maxSpeedKmh = 1000))
  }

  @Test
  fun `a plausible-speed jump under the quarantine distance publishes immediately`() {
    // 4km after an hour: under QUARANTINE_DISTANCE_METRES, no corroboration needed
    assertEquals(
        JumpGateDecision.PUBLISH,
        evaluateJumpGate(4_000f, 3600.0, null, null, maxSpeedKmh = 1000))
  }

  @Test
  fun `a second fix agreeing with the withheld one corroborates a real relocation`() {
    // Still ~50km from the anchor, but only 100m in 30s from the withheld fix: the move is real.
    assertEquals(
        JumpGateDecision.PUBLISH_CORROBORATED,
        evaluateJumpGate(50_000f, 3630.0, 100f, 30.0, maxSpeedKmh = 1000))
  }

  @Test
  fun `a second fix back at the anchor discards the withheld bounce`() {
    // The next fix is back near the published anchor: the withheld fix was a transient bounce.
    // It publishes normally and the withheld state is dropped by the caller.
    assertEquals(
        JumpGateDecision.PUBLISH,
        evaluateJumpGate(120f, 3630.0, 50_000f, 30.0, maxSpeedKmh = 1000))
  }

  @Test
  fun `an implausible jump corroborated by the previous withheld fix escapes a bad anchor`() {
    // The anchor itself is a bounce that reached the wire: real fixes look implausible against
    // it, but agree with each other — accept instead of locking on.
    assertEquals(
        JumpGateDecision.PUBLISH_CORROBORATED,
        evaluateJumpGate(50_000f, 65.0, 90f, 60.0, maxSpeedKmh = 1000))
  }

  @Test
  fun `zero maxSpeedKmh disables the jump gate entirely`() {
    assertEquals(
        JumpGateDecision.PUBLISH,
        evaluateJumpGate(50_000f, 5.0, null, null, maxSpeedKmh = 0))
  }
}
