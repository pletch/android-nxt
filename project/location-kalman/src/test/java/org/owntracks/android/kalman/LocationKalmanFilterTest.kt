package org.owntracks.android.kalman

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationKalmanFilterTest {

  private fun fix(
      lat: Double,
      lon: Double,
      accuracy: Float = 10f,
      timeMillis: Long,
      speed: Float = 0f
  ) = KalmanFix(lat, lon, accuracy, timeMillis, speed)

  @Test
  fun `first fix is returned unchanged`() {
    val filter = LocationKalmanFilter()
    val input = fix(51.0, 0.1, timeMillis = 1000)
    val out = filter.filter(input)
    assertEquals(SmoothedPosition(input.latitude, input.longitude, input.accuracyMetres), out)
  }

  @Test
  fun `smooths jitter around a stationary point`() {
    val filter = LocationKalmanFilter()
    filter.filter(fix(51.0, 0.1, accuracy = 10f, timeMillis = 0))

    // A noisy fix jittering off the true position by a consistent amount.
    val out = filter.filter(fix(51.0001, 0.1, accuracy = 10f, timeMillis = 1000))

    // The filtered latitude should move toward, but not all the way to, the noisy measurement.
    assertTrue("expected smoothed lat between 51.0 and 51.0001", out.latitude in 51.0..51.0001)
    assertTrue(
        "expected smoothing to pull the estimate back from the raw jitter",
        out.latitude < 51.0001)
  }

  @Test
  fun `tracks a steadily moving fix without excessive lag`() {
    val filter = LocationKalmanFilter()
    // Simulate driving north at ~25 m/s (90 km/h), one fix per second, reporting that speed.
    var lat = 51.0
    var timeMillis = 0L
    filter.filter(fix(lat, 0.1, accuracy = 5f, timeMillis = timeMillis, speed = 25f))
    repeat(30) {
      timeMillis += 1000
      lat += 25.0 / 111_320.0 // ~metres-per-degree-latitude
      val out = filter.filter(fix(lat, 0.1, accuracy = 5f, timeMillis = timeMillis, speed = 25f))
      // The filter should stay within ~5m of the true position, not lag far behind.
      val errorMetres = abs(out.latitude - lat) * 111_320.0
      assertTrue("filter lagged by ${errorMetres}m at t=$timeMillis", errorMetres < 5.0)
    }
  }

  @Test
  fun `resets after a long gap instead of blending against stale state`() {
    val filter = LocationKalmanFilter()
    filter.filter(fix(51.0, 0.1, accuracy = 10f, timeMillis = 0))

    // The app was backgrounded for well over the reset threshold, then resumes far away.
    val farAway = fix(52.0, 1.0, accuracy = 10f, timeMillis = 10 * 60 * 1000)
    val out = filter.filter(farAway)

    assertEquals(
        SmoothedPosition(farAway.latitude, farAway.longitude, farAway.accuracyMetres), out)
  }

  @Test
  fun `weights a low-accuracy measurement less than a high-accuracy one`() {
    val preciseFilter = LocationKalmanFilter()
    preciseFilter.filter(fix(51.0, 0.1, accuracy = 5f, timeMillis = 0))
    val preciseOut = preciseFilter.filter(fix(51.001, 0.1, accuracy = 5f, timeMillis = 1000))

    val noisyFilter = LocationKalmanFilter()
    noisyFilter.filter(fix(51.0, 0.1, accuracy = 5f, timeMillis = 0))
    val noisyOut = noisyFilter.filter(fix(51.001, 0.1, accuracy = 500f, timeMillis = 1000))

    // A low-accuracy (high-uncertainty) new measurement should move the estimate less.
    val preciseMove = preciseOut.latitude - 51.0
    val noisyMove = noisyOut.latitude - 51.0
    assertTrue(
        "expected the noisy measurement to move the estimate less: precise=$preciseMove noisy=$noisyMove",
        noisyMove < preciseMove)
  }

  @Test
  fun `tracks movement without excessive lag even when fixes carry no speed reading`() {
    val filter = LocationKalmanFilter()
    // Network fixes report speed=0. Driving north at ~30 m/s, one fix per 15s: the fix-implied
    // displacement must raise the process noise so the filter doesn't crawl behind the vehicle.
    var lat = 51.0
    var timeMillis = 0L
    filter.filter(fix(lat, 0.1, accuracy = 20f, timeMillis = timeMillis, speed = 0f))
    repeat(10) {
      timeMillis += 15_000
      lat += 30.0 * 15 / 111_320.0 // ~metres-per-degree-latitude
      val out = filter.filter(fix(lat, 0.1, accuracy = 20f, timeMillis = timeMillis, speed = 0f))
      // Within one fix interval's tolerance of the true position, not hundreds of metres behind.
      val errorMetres = abs(out.latitude - lat) * 111_320.0
      assertTrue("filter lagged by ${errorMetres}m at t=$timeMillis", errorMetres < 30.0)
    }
  }
}
