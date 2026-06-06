package org.owntracks.android.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable(with = BatteryStatus.BatteryStatusSerializer::class)
enum class BatteryStatus(val value: Int) {
  /** The owntracks model for battery status */
  UNKNOWN(0),
  UNPLUGGED(1),
  CHARGING(2),
  FULL(3);

  object BatteryStatusSerializer : KSerializer<BatteryStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("BatteryStatus", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: BatteryStatus) {
      encoder.encodeInt(value.value)
    }

    override fun deserialize(decoder: Decoder): BatteryStatus {
      // Tolerate a float-encoded value (the iOS app emits e.g. "bs":2.0) and unknown codes, so a
      // stray battery status can't drop the whole location message to MessageUnknown.
      val value =
          if (decoder is JsonDecoder) {
            val primitive = decoder.decodeJsonElement().jsonPrimitive
            primitive.intOrNull ?: primitive.doubleOrNull?.toInt() ?: 0
          } else {
            decoder.decodeInt()
          }
      return entries.firstOrNull { it.value == value } ?: UNKNOWN
    }
  }
}
