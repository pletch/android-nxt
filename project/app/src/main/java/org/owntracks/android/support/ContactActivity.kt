package org.owntracks.android.support

/**
 * A coarse movement state inferred from a contact's reported velocity, used to badge map markers.
 * OwnTracks location messages don't carry an explicit activity, so this is derived from `vel`.
 */
enum class ContactActivity {
  NONE,
  WALKING,
  DRIVING;

  companion object {
    // Velocity bands in km/h. Below WALKING_MIN_KMH we show no badge (stationary or GPS jitter);
    // the walking/driving split is a coarse on-foot-vs-vehicle heuristic, not a precise classifier.
    const val WALKING_MIN_KMH = 3
    const val DRIVING_MIN_KMH = 12

    fun fromVelocity(velocityKmh: Int): ContactActivity =
        when {
          velocityKmh < WALKING_MIN_KMH -> NONE
          velocityKmh < DRIVING_MIN_KMH -> WALKING
          else -> DRIVING
        }
  }
}
