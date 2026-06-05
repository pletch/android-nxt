package org.owntracks.android.net.mqtt

import android.content.Context
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttWebSocketConfig
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.lifecycle.MqttClientConnectedListener
import com.hivemq.client.mqtt.lifecycle.MqttClientDisconnectedListener
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.message.auth.Mqtt3SimpleAuth
import com.hivemq.client.mqtt.mqtt3.message.connect.Mqtt3Connect
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish
import java.security.KeyStore
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.json.JSONObject
import org.owntracks.android.net.ConnectionConfiguration
import org.owntracks.android.preferences.DefaultsProvider
import org.owntracks.android.preferences.Preferences
import org.owntracks.android.support.interfaces.ConfigurationIncompleteException

/**
 * Holds the MQTT connection settings and builds the HiveMQ [Mqtt3AsyncClient] + [Mqtt3Connect] from
 * them. TLS reuses [buildMqttSslConfig]; WebSocket uses [wsPath] (TLS + WebSocket gives wss).
 */
data class MqttConnectionConfiguration(
    val tls: Boolean,
    val ws: Boolean,
    val wsPath: String,
    val host: String,
    val port: Int,
    val clientId: String,
    val username: String,
    val password: String,
    val keepAlive: Duration,
    val timeout: Duration,
    val cleanSession: Boolean,
    val tlsClientCertAlias: String,
    val willTopic: String,
    val topicsToSubscribeTo: Set<String>,
    val subQos: org.owntracks.android.preferences.types.MqttQos
) : ConnectionConfiguration {

  @Throws(ConfigurationIncompleteException::class)
  override fun validate() {
    if (host.isBlank()) {
      throw ConfigurationIncompleteException(MissingHostException())
    }
  }

  /**
   * Builds (but does not connect) the HiveMQ client. KeyChain access blocks, so call off-thread.
   */
  fun buildClient(
      context: Context,
      caKeyStore: KeyStore,
      connectedListener: MqttClientConnectedListener,
      disconnectedListener: MqttClientDisconnectedListener
  ): Mqtt3AsyncClient {
    val builder =
        MqttClient.builder()
            .identifier(clientId)
            .serverHost(host)
            .serverPort(port)
            // Reconnection is owned by the endpoint (scheduler.scheduleMqttReconnect), not the
            // HiveMQ client: its built-in auto-reconnect retries a cached CONNECT, which deadlocks
            // credential changes and keeps using stale credentials.
            .addConnectedListener(connectedListener)
            .addDisconnectedListener(disconnectedListener)
    if (tls) {
      builder.sslConfig(
          buildMqttSslConfig(
              context, caKeyStore, tlsClientCertAlias, timeout.inWholeSeconds.toInt()))
    }
    if (ws) {
      // TLS + WebSocket composes to wss. The server path defaults to /mqtt (configurable).
      builder.webSocketConfig(MqttWebSocketConfig.builder().serverPath(wsPath).build())
    }
    return builder.useMqttVersion3().buildAsync()
  }

  /** The CONNECT packet (keep-alive, clean session, auth, LWT). Reused across auto-reconnects. */
  fun buildConnect(): Mqtt3Connect {
    val will =
        Mqtt3Publish.builder()
            .topic(willTopic)
            .payload(JSONObject().apply { put("_type", "lwt") }.toString().toByteArray())
            .qos(MqttQos.AT_MOST_ONCE)
            .retain(false)
            .build()
    val builder =
        Mqtt3Connect.builder()
            .keepAlive(keepAlive.inWholeSeconds.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
            .cleanSession(cleanSession)
            .willPublish(will)
    if (username.isNotEmpty()) {
      val auth =
          Mqtt3SimpleAuth.builder()
              .username(username)
              .let { if (password.isNotEmpty()) it.password(password.toByteArray()) else it }
              .build()
      builder.simpleAuth(auth)
    }
    return builder.build()
  }

  class MissingHostException : Exception()
}

fun Preferences.toMqttConnectionConfiguration(): MqttConnectionConfiguration =
    MqttConnectionConfiguration(
        tls,
        ws,
        wsPath,
        host,
        port,
        clientId,
        username,
        password,
        keepalive.seconds,
        connectionTimeoutSeconds.seconds,
        cleanSession,
        tlsClientCrt,
        pubTopicBaseWithUserDetails,
        if (subTopic.contains(" ")) {
          subTopic.split(" ").toSortedSet()
        } else if (subTopic == DefaultsProvider.DEFAULT_SUB_TOPIC) {
          if (info) {
            sortedSetOf(
                subTopic,
                subTopic + infoTopicSuffix,
                subTopic + eventTopicSuffix,
                subTopic + statusTopicSuffix,
                receivedCommandsTopic)
          } else {
            sortedSetOf(subTopic, subTopic + eventTopicSuffix, receivedCommandsTopic)
          }
        } else {
          sortedSetOf(subTopic)
        },
        subQos)
