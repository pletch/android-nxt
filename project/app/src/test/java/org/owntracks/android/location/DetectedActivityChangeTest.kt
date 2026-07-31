package org.owntracks.android.location

import org.junit.Assert.assertEquals
import org.junit.Test

class DetectedActivityChangeTest {
  @Test
  fun `each detected activity maps onto the OwnTracks motionactivities vocabulary`() {
    assertEquals("walking", DetectedActivityChange.ON_FOOT.motionActivity)
    assertEquals("automotive", DetectedActivityChange.IN_VEHICLE.motionActivity)
    assertEquals("stationary", DetectedActivityChange.STILL.motionActivity)
    assertEquals("cycling", DetectedActivityChange.CYCLING.motionActivity)
  }
}
