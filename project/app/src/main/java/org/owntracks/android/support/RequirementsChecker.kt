package org.owntracks.android.support

interface RequirementsChecker {
  fun hasLocationPermissions(): Boolean

  fun hasBackgroundLocationPermission(): Boolean

  /** Whether Precise (fine) location is granted, as opposed to Approximate (coarse) only. */
  fun hasPreciseLocationPermission(): Boolean

  fun hasActivityRecognitionPermission(): Boolean

  fun isLocationServiceEnabled(): Boolean

  fun isPlayServicesCheckPassed(): Boolean

  fun hasNotificationPermissions(): Boolean
}
