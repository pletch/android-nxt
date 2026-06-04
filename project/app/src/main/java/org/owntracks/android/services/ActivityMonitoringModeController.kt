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
  NOT_ON_FOOT
}

/**
 * Decision engine for activity-triggered adaptive monitoring
 * ([Preferences.autoMonitoringByActivity]).
 *
 * On on-foot activity it sets the [Preferences.locatorBoostedByActivity] flag, which the location
 * request reads (via [effectiveLocatorSettings]) to boost the locator to high accuracy without
 * changing the user's stored config. Hysteresis is fast-in / slow-out: boost immediately, but
 * revert only after a sustained still period of [Preferences.activityRevertDelaySeconds]. A manual
 * monitoring-mode change clears the boost and suppresses it until the next still -> on-foot cycle.
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
  private var sawNotOnFootSinceOverride = false

  /**
   * Clears a boost left stale by a previous process (e.g. killed mid-walk) so it can't drain power.
   */
  @Synchronized
  fun onServiceStart() {
    if (preferences.locatorBoostedByActivity) {
      Timber.i("Clearing stale activity locator boost on service start")
      preferences.locatorBoostedByActivity = false
    }
  }

  @Synchronized
  fun onActivityChange(change: DetectedActivityChange) {
    when (change) {
      DetectedActivityChange.ON_FOOT -> onFootDetected()
      DetectedActivityChange.NOT_ON_FOOT -> notOnFootDetected()
    }
  }

  private fun onFootDetected() {
    if (suppressedByManualOverride) {
      if (!sawNotOnFootSinceOverride) {
        Timber.d("On-foot detected but suppressed by manual override (no still seen yet); ignoring")
        return
      }
      Timber.i("Clean still -> on-foot cycle seen; resuming activity-based monitoring")
      suppressedByManualOverride = false
    }
    cancelPendingRevert()
    if (preferences.locatorBoostedByActivity) {
      Timber.d("On-foot detected; locator is already boosted")
      return
    }
    if (preferences.monitoring == MonitoringMode.Move) {
      Timber.d("On-foot detected but already in Move mode; nothing to boost")
      return
    }
    if (!hasPreciseLocation()) {
      // Without Precise location, high accuracy is silently downgraded to coarse, so a boost gains
      // nothing.
      Timber.i("On-foot detected but Precise location isn't granted; skipping locator boost")
      return
    }
    Timber.i("On-foot detected; boosting locator to high accuracy")
    preferences.locatorBoostedByActivity = true
  }

  private fun notOnFootDetected() {
    if (suppressedByManualOverride) {
      sawNotOnFootSinceOverride = true
      Timber.d("Not-on-foot detected while suppressed; arming resume on the next on-foot")
      return
    }
    if (!preferences.locatorBoostedByActivity) {
      Timber.d("Not-on-foot detected but locator wasn't boosted; nothing to revert")
      return
    }
    val delaySeconds = preferences.activityRevertDelaySeconds
    Timber.i("Not-on-foot detected; arming locator-boost revert in ${delaySeconds}s")
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
   * clear the boost and back off until the next clean still -> on-foot cycle.
   */
  @Synchronized
  fun onMonitoringModeChangedExternally(newMode: MonitoringMode) {
    Timber.i("Manual monitoring change to $newMode detected; backing off activity-based switching")
    cancelPendingRevert()
    clearBoost()
    suppressedByManualOverride = true
    sawNotOnFootSinceOverride = false
  }

  @Synchronized
  private fun onRevertTimerElapsed() {
    clearBoost()
  }

  private fun clearBoost() {
    if (preferences.locatorBoostedByActivity) {
      Timber.i("Reverting activity locator boost")
      preferences.locatorBoostedByActivity = false
    }
  }

  private fun cancelPendingRevert() {
    revertJob?.cancel()
    revertJob = null
  }
}
