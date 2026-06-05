package org.owntracks.android.services

import org.owntracks.android.location.LocatorPriority
import org.owntracks.android.preferences.types.MonitoringMode

/**
 * The locator parameters currently in effect; [smallestDisplacement] is null in Move mode (no
 * filter).
 */
data class EffectiveLocatorSettings(
    val priority: LocatorPriority,
    val intervalSeconds: Int,
    val smallestDisplacement: Int?,
)

/** Coverage floor (metres) for the driving boost, so a stationary vehicle stops emitting points. */
const val DRIVING_BOOST_DISPLACEMENT_METRES = 50

/**
 * Computes the effective locator settings without mutating any stored preference. While boosting
 * (and not in Move mode) the locator is elevated to high accuracy: on foot with the configured
 * on-foot interval/displacement, or driving with the speed-tiered [drivingIntervalSeconds] (see
 * [DrivingSpeedTier]) and a small displacement floor. Otherwise the configured per-mode settings
 * apply.
 */
fun effectiveLocatorSettings(
    monitoring: MonitoringMode,
    locatorPriority: LocatorPriority?,
    locatorInterval: Int,
    locatorDisplacement: Int,
    moveModeLocatorInterval: Int,
    boostedByActivity: Boolean,
    activityOnFootLocatorInterval: Int,
    activityOnFootLocatorDisplacement: Int,
    boostedByDriving: Boolean = false,
    drivingIntervalSeconds: Int = DrivingSpeedTier.DEFAULT_INTERVAL_SECONDS,
): EffectiveLocatorSettings {
  if (boostedByActivity && monitoring != MonitoringMode.Move) {
    return EffectiveLocatorSettings(
        LocatorPriority.HighAccuracy,
        activityOnFootLocatorInterval,
        activityOnFootLocatorDisplacement)
  }
  if (boostedByDriving && monitoring != MonitoringMode.Move) {
    return EffectiveLocatorSettings(
        LocatorPriority.HighAccuracy, drivingIntervalSeconds, DRIVING_BOOST_DISPLACEMENT_METRES)
  }
  return when (monitoring) {
    MonitoringMode.Quiet,
    MonitoringMode.Manual ->
        EffectiveLocatorSettings(
            locatorPriority ?: LocatorPriority.LowPower, locatorInterval, locatorDisplacement)
    MonitoringMode.Significant ->
        EffectiveLocatorSettings(
            locatorPriority ?: LocatorPriority.BalancedPowerAccuracy,
            locatorInterval,
            locatorDisplacement)
    MonitoringMode.Move ->
        EffectiveLocatorSettings(
            locatorPriority ?: LocatorPriority.HighAccuracy, moveModeLocatorInterval, null)
  }
}
