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
          30 to 8, // city / traffic: tight, capture turns
          70 to 13, // arterial roads
          110 to 18, // highway
          Int.MAX_VALUE to 22) // motorway: coarse, let the GPS sleep between fixes

  private const val HYSTERESIS_KMH = 8

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
