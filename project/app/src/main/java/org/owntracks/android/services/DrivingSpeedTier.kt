package org.owntracks.android.services

/**
 * Speed-tiered sampling interval for the driving locator boost. The interval is derived from the
 * vehicle's current speed (already carried on every `Location.getSpeed()`), so the GPS can
 * duty-cycle at high speed — a longer interval still gives good spatial coverage when you're
 * covering ground fast — while slow/city driving keeps a tighter interval to capture turns.
 *
 * Bands are sticky: [HYSTERESIS_KMH] of margin is required to cross a boundary, so steady driving
 * near a threshold doesn't churn the location request.
 */
object DrivingSpeedTier {
  // (exclusive upper speed bound in km/h) to (sampling interval in seconds), ascending by speed.
  private val BANDS =
      listOf(
          30 to 7, // city / traffic: tight, capture turns
          70 to 12, // arterial roads
          110 to 16, // highway
          Int.MAX_VALUE to 20) // motorway: coarse, let the GPS sleep between fixes

  private const val HYSTERESIS_KMH = 8

  /**
   * GPS-speed thresholds for engaging/disengaging the driving boost when Activity Recognition
   * misses it (AR is accelerometer-based and reports STILL during smooth constant-velocity
   * cruising, so speed is the only reliable vehicle signal there). [DRIVING_ENTER_KMH] sits above
   * running and typical cycling so those aren't misread as driving; the gap down to
   * [DRIVING_EXIT_KMH] is hysteresis so a stop-and-go crawl doesn't flap the boost.
   */
  const val DRIVING_ENTER_KMH = 36f

  const val DRIVING_EXIT_KMH = 9f

  /**
   * Consecutive vehicular-speed fixes required before GPS speed will override an *already active*
   * on-foot / cycling boost (switching it straight to the driving profile). One fix is enough to
   * engage from no boost; overriding AR's on-foot classification demands sustained speed so a brief
   * fast downhill on a bike isn't misread as driving.
   */
  const val DRIVING_OVERRIDE_CONFIRMATION_FIXES = 2

  /** The interval the boost starts at before any speed is observed. */
  val DEFAULT_INTERVAL_SECONDS = BANDS.last().second

  fun mpsToKmh(speedMetresPerSecond: Float): Float = speedMetresPerSecond * 3.6f

  /**
   * The sampling interval to use for [speedKmh], given the [currentIntervalSeconds] currently in
   * effect. Crossing a band boundary requires [HYSTERESIS_KMH] of margin, so the band is sticky.
   */
  fun intervalSecondsForSpeed(speedKmh: Float, currentIntervalSeconds: Int): Int {
    var band =
        BANDS.indexOfFirst { it.second == currentIntervalSeconds }
            .let { if (it < 0) BANDS.lastIndex else it }
    // Move to faster bands (higher index) only once speed clears the upper bound by the margin.
    while (band < BANDS.lastIndex && speedKmh >= BANDS[band].first + HYSTERESIS_KMH) band++
    // Move to slower bands only once speed drops below the lower boundary by the margin.
    while (band > 0 && speedKmh < BANDS[band - 1].first - HYSTERESIS_KMH) band--
    return BANDS[band].second
  }
}
