package org.owntracks.android.model.messages

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

object MessageLocationDeserializer : KSerializer<MessageLocation> {
  private val defaultSerializer = MessageLocation.serializer()
  override val descriptor: SerialDescriptor = defaultSerializer.descriptor

  override fun serialize(encoder: Encoder, value: MessageLocation) {
    defaultSerializer.serialize(encoder, value)
  }

  @OptIn(ExperimentalSerializationApi::class)
  override fun deserialize(decoder: Decoder): MessageLocation {
    if (decoder !is JsonDecoder) {
      throw SerializationException("Only JSON decoder is supported")
    }

    val element = decoder.decodeJsonElement()
    require(element is JsonObject) { "Expected JSON object for MessageLocation" }

    val errors = mutableListOf<String>()
    if (!element.containsKey("tst")) errors.add("missing 'tst' (timestamp)")
    if (!element.containsKey("lat")) errors.add("missing 'lat' (latitude)")
    if (!element.containsKey("lon")) errors.add("missing 'lon' (longitude)")
    if (!element.containsKey("tid") && !element.containsKey("topic")) {
      errors.add("missing both 'tid' and 'topic' (at least one required)")
    }

    // Check for a zero, negative, or implausibly-future timestamp (tolerate fractional values
    // some clients send, e.g. 0.0). Only the future side is bounded: devices legitimately replay
    // significantly old queued/offline locations, so a past-side bound would reject real backlog
    // messages. A generous future slack tolerates real device clock skew while still catching
    // clock corruption / garbage values (e.g. a huge or overflowed epoch value).
    val tst = element["tst"]?.jsonPrimitive?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
    if (tst != null) {
      if (tst <= 0L) {
        errors.add("'tst' (timestamp) must be positive")
      } else if (tst > System.currentTimeMillis() / 1000 + FUTURE_SLACK_SECONDS) {
        errors.add("'tst' (timestamp) is implausibly far in the future")
      }
    }

    if (errors.isNotEmpty()) {
      throw SerializationException("Invalid location message: ${errors.joinToString(", ")}")
    }

    // Use the decoder's json instance to deserialize with the default serializer
    return decoder.json.decodeFromJsonElement(defaultSerializer, element)
  }

  // One day of slack tolerates real device clock skew while still catching clock
  // corruption/garbage 'tst' values.
  private const val FUTURE_SLACK_SECONDS = 24 * 60 * 60L
}
