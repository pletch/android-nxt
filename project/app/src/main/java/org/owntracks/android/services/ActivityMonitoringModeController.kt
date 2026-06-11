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
  STILL,
  // Appended last so existing ordinals (used for the IntArray IPC to BackgroundService) stay stable.
  CYCLING
}

/**
 * Maps a detected activity onto the OwnTracks `motionactivities` vocabulary (stationary / walking /
 * running / automotive / cycling / unknown) for publishing. The controller collapses Google's
 * richer set to three states, so we emit the closest spec term for each.
 */
fun DetectedActivityChange.toMotionActivities(): List<String> =
    when (this) {
      DetectedActivityChange.ON_FOOT -> listOf("walking")
      DetectedActivityChange.IN_VEHICLE -> listOf("automotive")
      DetectedActivityChange.STILL -> listOf("stationary")
      DetectedActivityChange.CYCLING -> listOf("cycling")
    }

/**
 * Decision engine for activity-triggered adaptive monitoring
 * ([Preferences.autoMonitoringByActivity]).
 *
 * On an active transition it sets a high-accuracy boost flag the location request reads (via
 * [effectiveLocatorSettings]) without changing the user's stored config: on foot ->
 * [Preferences.locatorBoostedByActivity] (fixed on-foot interval); driving ->
 * [Preferences.locatorBoostedByDriving] (speed-tiered interval, see [DrivingSpeedTier]). The two
 * are mutually exclusive. Hysteresis: boost only after an optional entry dwell
 * ([Preferences.activityEntryDelaySeconds]; 0 = immediate) so brief bursts the Activity Recognition
 * API reports don't flap the locator, and revert only after a sustained still period
 * ([Preferences.activityRevertDelaySeconds]). A manual monitoring-mode change clears the boost and
 * suppresses it until the next clean still -> active cycle.
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
  private var entryJob: Job? = null

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
      // Cycling is active but slower/stop-start like walking, so use the fixed on-foot boost
      // rather than the speed-tiered driving profile.
      DetectedActivityChange.CYCLING -> onActiveDetected(onFoot = true)
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
    val alreadyBoosted = preferences.locatorBoostedByActivity || preferences.locatorBoostedByDriving
    val entryDelaySeconds = preferences.activityEntryDelaySeconds
    if (alreadyBoosted || entryDelaySeconds <= 0) {
      // Already high accuracy (just switch transport profile if needed), or no entry dwell set.
      cancelPendingEntry()
      applyBoost(onFoot)
    } else {
      // Gate fast-in behind a dwell: only boost once we've stayed active for the delay, so brief
      // bursts the Activity Recognition API reports aggressively (e.g. a short walk to the kitchen)
      // don't flap the locator. Going STILL before the dwell elapses cancels it.
      Timber.i("Active detected; arming locator boost in ${entryDelaySeconds}s (entry dwell)")
      cancelPendingEntry()
      entryJob =
          scope.launch {
            delay(entryDelaySeconds.seconds)
            onEntryDwellElapsed(onFoot)
          }
    }
  }

  @Synchronized
  private fun onEntryDwellElapsed(onFoot: Boolean) {
    entryJob = null
    // State may have changed during the dwell (mode change, permission revoked).
    if (preferences.monitoring == MonitoringMode.Move || !hasPreciseLocation()) return
    Timber.i("Entry dwell elapsed; boosting locator to high accuracy")
    applyBoost(onFoot)
  }

  /** Sets the appropriate boost flag; the two are mutually exclusive, so clear the other. */
  private fun applyBoost(onFoot: Boolean) {
    if (onFoot) {
      if (preferences.locatorBoostedByDriving) preferences.locatorBoostedByDriving = false
      if (preferences.locatorBoostedByActivity) {
        Timber.d("On-foot; locator is already boosted")
      } else {
        Timber.i("Boosting locator to high accuracy (on foot)")
        preferences.locatorBoostedByActivity = true
      }
    } else {
      if (preferences.locatorBoostedByActivity) preferences.locatorBoostedByActivity = false
      if (preferences.locatorBoostedByDriving) {
        Timber.d("Driving; locator is already boosted")
      } else {
        Timber.i("Boosting locator to high accuracy (driving, speed-tiered)")
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
    // A brief active burst that never dwelled long enough to boost: drop the pending entry.
    cancelPendingEntry()
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
    cancelPendingEntry()
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
    cancelPendingEntry()
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

  private fun cancelPendingEntry() {
    entryJob?.cancel()
    entryJob = null
  }
}
