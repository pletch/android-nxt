package org.owntracks.android.services

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.owntracks.android.preferences.Preferences
import org.owntracks.android.preferences.types.MonitoringMode
import timber.log.Timber

enum class DetectedActivityChange {
  ON_FOOT,
  IN_VEHICLE,
  STILL
}

/**
 * Decision engine for activity-triggered adaptive monitoring
 * ([Preferences.autoMonitoringByActivity]).
 *
 * On an active transition it sets a high-accuracy boost flag the location request reads (via
 * [effectiveLocatorSettings]) without changing the user's stored config: on foot ->
 * [Preferences.locatorBoostedByActivity] (fixed on-foot interval); driving ->
 * [Preferences.locatorBoostedByDriving] (speed-tiered interval, see [DrivingSpeedTier]). The two
 * are mutually exclusive. Hysteresis is fast-in / slow-out: boost immediately, but revert only
 * after a sustained still period of [Preferences.activityRevertDelaySeconds]. A manual
 * monitoring-mode change clears the boost and suppresses it until the next clean still -> active
 * cycle.
 *
 * Entry points are `@Synchronized`: a remote `setConfiguration` change can arrive on a background
 * thread while transitions are handled on the main thread.
 */
class ActivityMonitoringModeController(
    private val preferences: Preferences,
    private val scope: CoroutineScope,
    private val hasPreciseLocation: () -> Boolean = { true },
) {
  private var revertJob: Job? = null

  private var suppressedByManualOverride = false
  private var sawStillSinceOverride = false

  /**
   * Clears a boost left stale by a previous process (e.g. killed mid-trip) so it can't drain power.
   */
  @Synchronized
  fun onServiceStart() {
    if (preferences.locatorBoostedByActivity || preferences.locatorBoostedByDriving) {
      Timber.i("Clearing stale activity locator boost on service start")
      clearBoost()
    }
  }

  @Synchronized
  fun onActivityChange(change: DetectedActivityChange) {
    when (change) {
      DetectedActivityChange.ON_FOOT -> onActiveDetected(onFoot = true)
      DetectedActivityChange.IN_VEHICLE -> onActiveDetected(onFoot = false)
      DetectedActivityChange.STILL -> stillDetected()
    }
  }

  private fun onActiveDetected(onFoot: Boolean) {
    if (!proceedWithActiveDetection()) return
    cancelPendingRevert()
    if (preferences.monitoring == MonitoringMode.Move) {
      Timber.d("Active detected but already in Move mode; nothing to boost")
      return
    }
    if (!hasPreciseLocation()) {
      // Without Precise location, high accuracy is silently downgraded to coarse, so a boost gains
      // nothing.
      Timber.i("Active detected but Precise location isn't granted; skipping locator boost")
      return
    }
    // The two boosts are mutually exclusive; switching transport clears the other.
    if (onFoot) {
      if (preferences.locatorBoostedByDriving) preferences.locatorBoostedByDriving = false
      if (preferences.locatorBoostedByActivity) {
        Timber.d("On-foot detected; locator is already boosted")
      } else {
        Timber.i("On-foot detected; boosting locator to high accuracy")
        preferences.locatorBoostedByActivity = true
      }
    } else {
      if (preferences.locatorBoostedByActivity) preferences.locatorBoostedByActivity = false
      if (preferences.locatorBoostedByDriving) {
        Timber.d("Driving detected; locator is already boosted")
      } else {
        Timber.i("Driving detected; boosting locator to high accuracy (speed-tiered)")
        preferences.locatorBoostedByDriving = true
      }
    }
  }

  /** Returns whether to act on an active (on-foot / driving) detection given the override state. */
  private fun proceedWithActiveDetection(): Boolean {
    if (suppressedByManualOverride) {
      if (!sawStillSinceOverride) {
        Timber.d("Active detected but suppressed by manual override (no still yet); ignoring")
        return false
      }
      Timber.i("Clean still -> active cycle seen; resuming activity-based monitoring")
      suppressedByManualOverride = false
    }
    return true
  }

  private fun stillDetected() {
    if (suppressedByManualOverride) {
      sawStillSinceOverride = true
      Timber.d("Still detected while suppressed; arming resume on the next active transition")
      return
    }
    if (!preferences.locatorBoostedByActivity && !preferences.locatorBoostedByDriving) {
      Timber.d("Still detected but locator wasn't boosted; nothing to revert")
      return
    }
    val delaySeconds = preferences.activityRevertDelaySeconds
    Timber.i("Still detected; arming locator-boost revert in ${delaySeconds}s")
    cancelPendingRevert()
    revertJob =
        scope.launch {
          delay(delaySeconds.seconds)
          onRevertTimerElapsed()
        }
  }

  /** Clears the boost immediately, e.g. when the feature is switched off. */
  @Synchronized
  fun onFeatureDisabled() {
    cancelPendingRevert()
    clearBoost()
  }

  /**
   * The controller never changes [Preferences.monitoring] itself, so any change is the user's:
   * clear the boost and back off until the next clean still -> active cycle.
   */
  @Synchronized
  fun onMonitoringModeChangedExternally(newMode: MonitoringMode) {
    Timber.i("Manual monitoring change to $newMode detected; backing off activity-based switching")
    cancelPendingRevert()
    clearBoost()
    suppressedByManualOverride = true
    sawStillSinceOverride = false
  }

  @Synchronized
  private fun onRevertTimerElapsed() {
    clearBoost()
  }

  private fun clearBoost() {
    if (preferences.locatorBoostedByActivity) {
      Timber.i("Reverting on-foot locator boost")
      preferences.locatorBoostedByActivity = false
    }
    if (preferences.locatorBoostedByDriving) {
      Timber.i("Reverting driving locator boost")
      preferences.locatorBoostedByDriving = false
    }
  }

  private fun cancelPendingRevert() {
    revertJob?.cancel()
    revertJob = null
  }
}
