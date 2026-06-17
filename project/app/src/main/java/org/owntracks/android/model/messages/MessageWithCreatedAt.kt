@file:OptIn(ExperimentalTime::class)

package org.owntracks.android.model.messages

import kotlin.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.ExperimentalTime

interface MessageWithCreatedAt {
  @SerialName("created_at") var createdAt: Instant
}

@OptIn(ExperimentalTime::class)
class MessageCreatedAtNow(clock: Clock) : MessageWithCreatedAt {
  override var createdAt: Instant = clock.time
}

interface Clock {
  @OptIn(ExperimentalTime::class)
  val time: Instant
}

class RealClock : Clock {
  override val time: Instant = kotlin.time.Clock.System.now()
}

/** Serializes [Instant] as Unix epoch seconds (Long) matching the OwnTracks protocol. */
object InstantEpochSecondsSerializer : KSerializer<Instant> {
  override val descriptor: SerialDescriptor =
      PrimitiveSerialDescriptor("InstantEpochSeconds", PrimitiveKind.LONG)

  override fun serialize(encoder: Encoder, value: Instant) {
    encoder.encodeLong(value.epochSeconds)
  }

  // Decode as Double then truncate: some clients send fractional epoch seconds (e.g. 1.78e9.47),
  // which a strict decodeLong() rejects. decodeDouble() accepts both integer and float literals.
  override fun deserialize(decoder: Decoder): Instant =
      Instant.fromEpochSeconds(decoder.decodeDouble().toLong())
}
