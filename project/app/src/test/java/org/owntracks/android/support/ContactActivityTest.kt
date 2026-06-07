package org.owntracks.android.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactActivityTest {
  @Test
  fun `motionactivities map to the right badge`() {
    assertEquals(
        ContactActivity.DRIVING, ContactActivity.fromMotionActivities(listOf("automotive")))
    assertEquals(ContactActivity.WALKING, ContactActivity.fromMotionActivities(listOf("walking")))
    assertEquals(ContactActivity.WALKING, ContactActivity.fromMotionActivities(listOf("running")))
    assertEquals(ContactActivity.WALKING, ContactActivity.fromMotionActivities(listOf("cycling")))
    assertEquals(ContactActivity.NONE, ContactActivity.fromMotionActivities(listOf("stationary")))
  }

  @Test
  fun `automotive wins when combined with other activities`() {
    assertEquals(
        ContactActivity.DRIVING,
        ContactActivity.fromMotionActivities(listOf("walking", "automotive")))
  }

  @Test
  fun `motionactivities are case-insensitive`() {
    assertEquals(
        ContactActivity.DRIVING, ContactActivity.fromMotionActivities(listOf("Automotive")))
  }

  @Test
  fun `absent, empty, or unknown motionactivities return null so caller falls back to velocity`() {
    assertNull(ContactActivity.fromMotionActivities(null))
    assertNull(ContactActivity.fromMotionActivities(emptyList()))
    assertNull(ContactActivity.fromMotionActivities(listOf("unknown")))
  }

  @Test
  fun `stationary or jitter velocities produce no badge`() {
    assertEquals(ContactActivity.NONE, ContactActivity.fromVelocity(0))
    assertEquals(ContactActivity.NONE, ContactActivity.fromVelocity(2))
  }

  @Test
  fun `on-foot velocities produce a walking badge`() {
    assertEquals(ContactActivity.WALKING, ContactActivity.fromVelocity(3))
    assertEquals(ContactActivity.WALKING, ContactActivity.fromVelocity(5))
    assertEquals(ContactActivity.WALKING, ContactActivity.fromVelocity(11))
  }

  @Test
  fun `vehicle velocities produce a driving badge`() {
    assertEquals(ContactActivity.DRIVING, ContactActivity.fromVelocity(12))
    assertEquals(ContactActivity.DRIVING, ContactActivity.fromVelocity(50))
    assertEquals(ContactActivity.DRIVING, ContactActivity.fromVelocity(120))
  }
}
