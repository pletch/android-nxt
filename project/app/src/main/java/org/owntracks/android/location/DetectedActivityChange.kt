package org.owntracks.android.location

/**
 * A device motion state we track, decoupled from any particular activity-recognition backend so
 * that `main` doesn't depend on Play Services types.
 */
enum class DetectedActivityChange {
  ON_FOOT,
  IN_VEHICLE,
  STILL,
  CYCLING;

  /**
   * The equivalent term in the OwnTracks `motionactivities` vocabulary (stationary / walking /
   * running / automotive / cycling / unknown), as published by the iOS app.
   */
  val motionActivity: String
    get() =
        when (this) {
          ON_FOOT -> "walking"
          IN_VEHICLE -> "automotive"
          STILL -> "stationary"
          CYCLING -> "cycling"
        }
}
