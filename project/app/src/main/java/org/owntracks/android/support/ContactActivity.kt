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

    /**
     * Maps a contact's reported `motionactivities` (the documented OwnTracks field; a combination
     * of stationary / walking / running / automotive / cycling / unknown) onto a badge. Returns
     * null when there's nothing usable (absent, empty, or only "unknown") so the caller can fall
     * back to velocity inference. "automotive" wins when combined with others (you're in the
     * vehicle).
     */
    fun fromMotionActivities(activities: List<String>?): ContactActivity? {
      if (activities.isNullOrEmpty()) return null
      val set = activities.map { it.lowercase() }.toHashSet()
      return when {
        "automotive" in set -> DRIVING
        "walking" in set || "running" in set || "cycling" in set -> WALKING
        "stationary" in set -> NONE
        else -> null // unknown / unrecognised -> let the caller fall back to velocity
      }
    }
  }
}
