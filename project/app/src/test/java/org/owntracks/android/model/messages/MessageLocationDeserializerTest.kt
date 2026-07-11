package org.owntracks.android.model.messages

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MessageLocationDeserializerTest {

  private fun json(tst: String, lat: Double = 51.0, lon: Double = 0.3) =
      """{"tst":$tst,"lat":$lat,"lon":$lon,"tid":"aa"}"""

  @Test
  fun `a normal current timestamp is accepted`() {
    val nowSeconds = System.currentTimeMillis() / 1000
    val message = Json.decodeFromString(MessageLocationDeserializer, json(nowSeconds.toString()))
    assertEquals(nowSeconds, message.timestamp)
  }

  @Test
  fun `a legitimately old replayed timestamp is accepted`() {
    // Simulates a device replaying a backlog message queued while offline for weeks.
    val monthsAgoSeconds = System.currentTimeMillis() / 1000 - 60L * 60 * 24 * 90
    val message =
        Json.decodeFromString(MessageLocationDeserializer, json(monthsAgoSeconds.toString()))
    assertEquals(monthsAgoSeconds, message.timestamp)
  }

  @Test
  fun `a zero timestamp is rejected`() {
    val exception =
        assertThrows(SerializationException::class.java) {
          Json.decodeFromString(MessageLocationDeserializer, json("0"))
        }
    assert(exception.message?.contains("'tst'") == true)
  }

  @Test
  fun `a negative timestamp is rejected`() {
    assertThrows(SerializationException::class.java) {
      Json.decodeFromString(MessageLocationDeserializer, json("-1234567890"))
    }
  }

  @Test
  fun `an implausibly far future timestamp is rejected`() {
    val farFutureSeconds = System.currentTimeMillis() / 1000 + 60L * 60 * 24 * 365 * 10 // +10y
    assertThrows(SerializationException::class.java) {
      Json.decodeFromString(MessageLocationDeserializer, json(farFutureSeconds.toString()))
    }
  }

  @Test
  fun `a huge overflowed timestamp is rejected`() {
    assertThrows(SerializationException::class.java) {
      Json.decodeFromString(MessageLocationDeserializer, json("9.9e20"))
    }
  }

  @Test
  fun `a small clock-skew future timestamp within slack is accepted`() {
    val slightlyAheadSeconds = System.currentTimeMillis() / 1000 + 60 * 5 // +5 min
    val message =
        Json.decodeFromString(MessageLocationDeserializer, json(slightlyAheadSeconds.toString()))
    assertEquals(slightlyAheadSeconds, message.timestamp)
  }
}
