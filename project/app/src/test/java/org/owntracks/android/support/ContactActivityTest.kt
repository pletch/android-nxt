package org.owntracks.android.support

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactActivityTest {
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
