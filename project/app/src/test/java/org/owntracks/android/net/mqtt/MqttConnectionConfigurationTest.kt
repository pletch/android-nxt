package org.owntracks.android.net.mqtt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert
import org.junit.Test
import org.owntracks.android.preferences.InMemoryPreferencesStore
import org.owntracks.android.preferences.Preferences
import org.owntracks.android.support.interfaces.ConfigurationIncompleteException
import org.owntracks.android.test.SimpleIdlingResource

class MqttConnectionConfigurationTest {
  private val mockIdlingResource = SimpleIdlingResource("mock", true)

  @Test
  fun `MQTT Connection Configuration generates correct topics to subscribe to from single default subTopic`() {
    val preferences = Preferences(InMemoryPreferencesStore(), mockIdlingResource)
    preferences.subTopic = "owntracks/+/+"
    val topics = preferences.toMqttConnectionConfiguration().topicsToSubscribeTo
    Assert.assertEquals(
        setOf(
            "owntracks/+/+",
            "owntracks/+/+/event",
            "owntracks/+/+/info",
            "owntracks/+/+/status",
            "owntracks/user/unknown/cmd",
        ),
        topics,
    )
  }

  @Test
  fun `MQTT Connection Configuration generates correct topics to subscribe to from single custom subTopic`() {
    val preferences = Preferences(InMemoryPreferencesStore(), mockIdlingResource)
    preferences.subTopic = "othertopic/+/+"
    val topics = preferences.toMqttConnectionConfiguration().topicsToSubscribeTo
    Assert.assertEquals(setOf("othertopic/+/+"), topics)
  }

  @Test
  fun `MQTT Connection Configuration generates correct topics to subscribe to from multiple subTopics`() {
    val preferences = Preferences(InMemoryPreferencesStore(), mockIdlingResource)
    preferences.subTopic = "owntracks/+/+ othertopic/+"
    preferences.info = true
    val topics = preferences.toMqttConnectionConfiguration().topicsToSubscribeTo
    Assert.assertEquals(setOf("owntracks/+/+", "othertopic/+"), topics)
  }

  @Test
  fun `MQTT Connection Configuration generates correct topics to subscribe to from multiple subTopics with info not requested`() {
    val preferences = Preferences(InMemoryPreferencesStore(), mockIdlingResource)
    preferences.subTopic = "owntracks/+/+ othertopic/+"
    preferences.info = false
    val topics = preferences.toMqttConnectionConfiguration().topicsToSubscribeTo
    Assert.assertEquals(setOf("owntracks/+/+", "othertopic/+"), topics)
  }

  @Test
  fun `MQTT Connection Configuration generates correct topics to subscribe to from wildcard topic`() {
    val preferences = Preferences(InMemoryPreferencesStore(), mockIdlingResource)
    preferences.subTopic = "owntracks/#"
    val topics = preferences.toMqttConnectionConfiguration().topicsToSubscribeTo
    Assert.assertEquals(setOf("owntracks/#"), topics)
  }

  @Test(expected = Test.None::class)
  fun `MQTT Connection Configuration validates config with valid hostname`() {
    val preferences = Preferences(InMemoryPreferencesStore(), mockIdlingResource)
    preferences.host = "example.com"
    preferences.toMqttConnectionConfiguration().validate()
  }

  @Test(expected = ConfigurationIncompleteException::class)
  fun `MQTT Connection Configuration does not validate config with missing hostname`() {
    val preferences = Preferences(InMemoryPreferencesStore(), mockIdlingResource)
    preferences.toMqttConnectionConfiguration().validate()
  }

  @Test
  fun `the CONNECT packet's will is an lwt message with its timestamps`() {
    val before = System.currentTimeMillis() / 1000
    // Upstream asserts this on Paho's MqttConnectOptions; this fork builds a HiveMQ Mqtt3Connect.
    val connect =
        Preferences(InMemoryPreferencesStore(), mockIdlingResource)
            .apply { tls = false }
            .toMqttConnectionConfiguration()
            .buildConnect()

    val payload = connect.willPublish.get().payloadAsBytes
    val will = Json.parseToJsonElement(String(payload)).jsonObject

    Assert.assertEquals("lwt", will["_type"]?.jsonPrimitive?.content)
    val tst = will["tst"]?.jsonPrimitive?.long
    Assert.assertNotNull("tst in $will", tst)
    Assert.assertTrue("tst in $will is from this connect", tst!! >= before)
    Assert.assertEquals(tst, will["created_at"]?.jsonPrimitive?.long)
  }
}
