package org.owntracks.android.kalman

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

/** A single position fix to feed into [LocationKalmanFilter]. No Android dependency, so this
 * module stays a plain, trivially unit-testable Kotlin JVM library. */
data class KalmanFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMetres: Float,
    val timestampMillis: Long,
    val speedMetresPerSecond: Float = 0f
)

/**
 * The filter's output: a smoothed position and the filter's own confidence in it. Deliberately not
 * a [KalmanFix] — the input's timestamp and speed are not filter outputs, and echoing them back
 * would invite callers to trust them as such.
 */
data class SmoothedPosition(
    val latitude: Double,
    val longitude: Double,
    val accuracyMetres: Float
)

/**
 * A minimal single-state Kalman filter for smoothing consecutive GPS fixes.
 *
 * Blends each new fix with a running position estimate, weighted by relative confidence: a
 * low-accuracy (uncertain) new fix is trusted less than the running estimate, and the estimate's
 * uncertainty grows with elapsed time and the fix's movement (so the filter doesn't lag behind or
 * cut corners on genuine fast movement — a fixed process-noise constant would either over-smooth
 * at walking pace or lag badly at highway speed).
 *
 * This is intentionally a single combined position variance (metres²), not a full
 * position+velocity multi-dimensional filter: the same scalar gain is applied to both latitude
 * and longitude, which is a standard, lightweight simplification for this use case. It smooths
 * jitter between already-plausible fixes; it does not reject gross outliers (see the separate
 * speed-based sanity gate in the app's LocationProcessor for that — a Kalman filter alone blends
 * a bad measurement in rather than rejecting it).
 */
class LocationKalmanFilter(
    private val minProcessNoiseMetresPerSecond: Double = DEFAULT_MIN_PROCESS_NOISE_MPS,
    private val maxGapSecondsBeforeReset: Double = DEFAULT_MAX_GAP_SECONDS_BEFORE_RESET
) {
  private var latitude = 0.0
  private var longitude = 0.0
  private var variance = -1.0 // metres²; negative means "not yet initialised"
  private var lastTimestampMillis = 0L

  /** Filters [fix], returning the smoothed position and the filter's own confidence in it. */
  fun filter(fix: KalmanFix): SmoothedPosition {
    val measurementVariance = accuracyVariance(fix.accuracyMetres)
    val dtSeconds = (fix.timestampMillis - lastTimestampMillis) / 1000.0

    if (variance < 0 || dtSeconds < 0 || dtSeconds > maxGapSecondsBeforeReset) {
      reset(fix, measurementVariance)
      return SmoothedPosition(fix.latitude, fix.longitude, fix.accuracyMetres)
    }

    // The estimate could have moved by at least the fix's reported speed since the last update.
    // Fixes without a speed reading (network fixes report 0) would otherwise collapse the noise
    // to the floor and make the filter lag far behind genuine movement, so the displacement the
    // fix itself implies also raises the noise — sustained motion then never trails by more than
    // one blend step, while stationary jitter (small displacement over the fix interval) still
    // gets smoothed. The floor keeps a momentarily-zero reading from freezing the filter.
    val impliedSpeedMetresPerSecond =
        approximateDistanceMetres(latitude, longitude, fix.latitude, fix.longitude) / dtSeconds
    val processNoiseMetresPerSecond =
        maxOf(
            fix.speedMetresPerSecond.toDouble(),
            impliedSpeedMetresPerSecond,
            minProcessNoiseMetresPerSecond)
    variance += dtSeconds * processNoiseMetresPerSecond.pow(2)

    val gain = variance / (variance + measurementVariance)
    latitude += gain * (fix.latitude - latitude)
    longitude += gain * (fix.longitude - longitude)
    variance *= (1 - gain)
    lastTimestampMillis = fix.timestampMillis

    return SmoothedPosition(
        latitude = latitude,
        longitude = longitude,
        accuracyMetres = sqrt(variance).toFloat().coerceAtLeast(MIN_ACCURACY_METRES))
  }

  private fun reset(fix: KalmanFix, measurementVariance: Double) {
    latitude = fix.latitude
    longitude = fix.longitude
    variance = measurementVariance
    lastTimestampMillis = fix.timestampMillis
  }

  private fun accuracyVariance(accuracyMetres: Float): Double {
    val a = if (accuracyMetres > 0f) accuracyMetres else DEFAULT_ACCURACY_METRES
    return (a * a).toDouble()
  }

  companion object {
    private const val DEFAULT_MIN_PROCESS_NOISE_MPS = 1.0
    private const val DEFAULT_MAX_GAP_SECONDS_BEFORE_RESET = 120.0
    private const val DEFAULT_ACCURACY_METRES = 30f
    private const val MIN_ACCURACY_METRES = 1f
    private const val EARTH_RADIUS_METRES = 6_371_000.0

    /** Equirectangular approximation — plenty for the inter-fix distances this filter sees. */
    private fun approximateDistanceMetres(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
      val dLat = Math.toRadians(lat2 - lat1)
      val dLon = Math.toRadians(lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2))
      return EARTH_RADIUS_METRES * sqrt(dLat * dLat + dLon * dLon)
    }
  }
}
