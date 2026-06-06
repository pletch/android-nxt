package org.owntracks.android.net.mqtt

import android.content.Context
import android.net.ConnectivityManager
import androidx.core.content.getSystemService
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.lifecycle.MqttClientConnectedListener
import com.hivemq.client.mqtt.lifecycle.MqttClientDisconnectedListener
import com.hivemq.client.mqtt.lifecycle.MqttDisconnectSource
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish
import com.hivemq.client.mqtt.mqtt3.message.subscribe.Mqtt3Subscribe
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.stream.Collectors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import org.owntracks.android.data.EndpointState
import org.owntracks.android.data.repos.EndpointStateRepo
import org.owntracks.android.di.ApplicationScope
import org.owntracks.android.di.CoroutineScopes
import org.owntracks.android.model.Parser
import org.owntracks.android.model.messages.MessageBase
import org.owntracks.android.model.messages.MessageCard
import org.owntracks.android.model.messages.MessageClear
import org.owntracks.android.model.messages.MessageLocation
import org.owntracks.android.net.MessageProcessorEndpoint
import org.owntracks.android.preferences.Preferences
import org.owntracks.android.preferences.types.ConnectionMode
import org.owntracks.android.services.MessageProcessor
import org.owntracks.android.services.worker.Scheduler
import org.owntracks.android.support.interfaces.ConfigurationIncompleteException
import org.owntracks.android.support.interfaces.StatefulServiceMessageProcessor
import org.owntracks.android.test.SimpleIdlingResource
import timber.log.Timber

class MQTTMessageProcessorEndpoint(
    messageProcessor: MessageProcessor,
    private val endpointStateRepo: EndpointStateRepo,
    private val scheduler: Scheduler,
    private val preferences: Preferences,
    private val parser: Parser,
    private val caKeyStore: KeyStore,
    @ApplicationScope private val scope: CoroutineScope,
    @CoroutineScopes.IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationContext private val applicationContext: Context,
    private val mqttConnectionIdlingResource: SimpleIdlingResource
) :
    MessageProcessorEndpoint(messageProcessor),
    StatefulServiceMessageProcessor,
    Preferences.OnPreferenceChangeListener {

  override val modeId: ConnectionMode = ConnectionMode.MQTT

  private val connectingLock = Mutex()
  private val connectivityManager: ConnectivityManager = applicationContext.getSystemService()!!
  private var client: Mqtt3AsyncClient? = null
  private var connectionConfiguration: MqttConnectionConfiguration? = null

  internal val networkChangeCallback =
      NetworkTrackingCallback(
          { endpointStateRepo.endpointState.value },
          { scope.launch { reconnect() } },
          // Our network is gone: reconnect (binds to whatever's available now; a failed attempt
          // self-schedules a retry) instead of only disconnecting, which previously left the client
          // stranded in DISCONNECTED if no further onAvailable arrived — e.g. a transient cellular
          // blip or a wifi→cell handoff while driving.
          { scope.launch { reconnect() } })

  override fun activate() {
    Timber.v("MQTT activate")
    preferences.registerOnPreferenceChangedListener(this)
    networkChangeCallback.reset()
    connectivityManager.registerDefaultNetworkCallback(networkChangeCallback)
    scope.launch {
      try {
        connect(getEndpointConfiguration())
      } catch (e: ConfigurationIncompleteException) {
        Timber.e(e, "MQTT configuration not complete, cannot activate")
        endpointStateRepo.setState(EndpointState.ERROR_CONFIGURATION.withError(e))
      }
    }
  }

  override fun deactivate() {
    preferences.unregisterOnPreferenceChangedListener(this)
    try {
      connectivityManager.unregisterNetworkCallback(networkChangeCallback)
    } catch (e: IllegalArgumentException) {
      Timber.d("Network callback already unregistered")
    }
    scope.launch {
      connectingLock.withLock { disconnect() }
      scheduler.cancelAllTasks()
    }
  }

  // Fires on every (re)connect. Subscriptions don't survive a reconnect, so (re)subscribe here.
  private val connectedListener = MqttClientConnectedListener {
    scope.launch {
      endpointStateRepo.setState(EndpointState.CONNECTED)
      val c = client ?: return@launch
      val config = connectionConfiguration ?: return@launch
      try {
        // Bound (re)subscription so a stalled SUBACK can't leave this coroutine hung and skip the
        // queue notify below.
        val subscribeTimeout = config.timeout.coerceAtLeast(15.seconds)
        config.topicsToSubscribeTo.forEach { topic ->
          c.subscribe(
                  Mqtt3Subscribe.builder()
                      .topicFilter(topic)
                      .qos(MqttQos.fromCode(config.subQos.value) ?: MqttQos.AT_LEAST_ONCE)
                      .build())
              .await(subscribeTimeout)
        }
        Timber.d("MQTT subscribed to ${config.topicsToSubscribeTo}")
      } catch (e: Exception) {
        Timber.w(e, "MQTT subscribe failed")
      }
      messageProcessor.notifyOutgoingMessageQueue()
      if (preferences.publishLocationOnConnect) {
        messageProcessor.publishLocationMessage(MessageLocation.ReportType.DEFAULT)
      }
    }
  }

  private val disconnectedListener = MqttClientDisconnectedListener { context ->
    Timber.w(context.cause, "MQTT disconnected (source=${context.source})")
    scope.launch { endpointStateRepo.setState(EndpointState.DISCONNECTED) }
    // We own reconnection now that HiveMQ auto-reconnect is off. Schedule a reconnect for any drop
    // or failed connect we didn't initiate ourselves (a USER source is our own disconnect()).
    if (context.source != MqttDisconnectSource.USER) {
      scheduler.scheduleMqttReconnect()
    }
  }

  private suspend fun connect(config: MqttConnectionConfiguration): Result<Unit> =
      connectingLock.withLock {
        disconnect()
        endpointStateRepo.setState(EndpointState.CONNECTING)
        mqttConnectionIdlingResource.setIdleState(false)
        try {
          val newClient =
              withContext(ioDispatcher) {
                config.buildClient(
                    applicationContext, caKeyStore, connectedListener, disconnectedListener)
              }
          client = newClient
          connectionConfiguration = config
          newClient.publishes(MqttGlobalPublishFilter.ALL) { onIncomingPublish(it) }
          Timber.d("Connecting to ${config.host}:${config.port}")
          // Bound the attempt so a stalled connect can never hold connectingLock indefinitely
          // (which previously wedged all reconnects until the process was killed).
          withContext(ioDispatcher) {
            newClient.connect(config.buildConnect()).await(config.timeout.coerceAtLeast(15.seconds))
          }
          Result.success(Unit)
        } catch (e: Exception) {
          Timber.e(e, "MQTT client unable to connect to endpoint")
          endpointStateRepo.setState(EndpointState.ERROR.withError(e))
          // No HiveMQ auto-reconnect, so schedule our own retry (deduped as unique work). A failed
          // CONNACK also fires disconnectedListener; both funnel to the same scheduled reconnect.
          scheduler.scheduleMqttReconnect()
          Result.failure(e)
        } finally {
          mqttConnectionIdlingResource.setIdleState(true)
        }
      }

  private suspend fun disconnect() {
    client?.let { existing ->
      Timber.v("MQTT disconnecting")
      withContext(ioDispatcher) {
        try {
          // Bound the graceful disconnect: on a half-open socket it can hang, and disconnect() runs
          // under connectingLock, so a hang would block every future reconnect. Abandon and move
          // on.
          existing.disconnect().await(5.seconds)
        } catch (e: Exception) {
          Timber.d(e, "Error during MQTT disconnect, ignoring")
        }
      }
    }
    client = null
    endpointStateRepo.setState(EndpointState.DISCONNECTED)
  }

  private fun onIncomingPublish(publish: Mqtt3Publish) {
    val topic = publish.topic.toString()
    Timber.d("Received MQTT message on $topic")
    val payload = publish.payloadAsBytes
    if (payload.isEmpty()) {
      onMessageReceived(
          MessageClear().apply { this.topic = topic.replace(MessageCard.BASETOPIC_SUFFIX, "") })
    } else {
      try {
        onMessageReceived(
            parser.fromJson(payload).apply {
              this.topic = topic
              this.retained = publish.isRetain
              this.qos = publish.qos.code
            })
      } catch (e: Parser.EncryptionException) {
        Timber.e("Unable to decrypt received message on $topic")
      } catch (e: SerializationException) {
        Timber.w("Malformed JSON message received on $topic")
      }
    }
  }

  override fun getEndpointConfiguration(): MqttConnectionConfiguration {
    val configuration = preferences.toMqttConnectionConfiguration()
    configuration.validate()
    return configuration
  }

  override fun onFinalizeMessage(message: MessageBase): MessageBase = message

  override suspend fun sendMessage(message: MessageBase): Result<Unit> {
    Timber.d("Sending message $message")
    val c = client ?: return Result.failure(NotReadyException())
    if (endpointStateRepo.endpointState.value != EndpointState.CONNECTED) {
      // We have outbound work but aren't connected. Nudge a reconnect (deduped) so a stranded
      // DISCONNECTED state — e.g. a network change whose recovery was missed — can't leave the
      // queue backing off forever with nothing trying to restore the connection.
      scheduler.scheduleMqttReconnect()
      return Result.failure(NotConnectedException())
    }
    message.annotateFromPreferences(preferences)
    val publishTimeout = preferences.connectionTimeoutSeconds.seconds.coerceAtLeast(15.seconds)
    return try {
      // The QoS 1/2 PUBACK future completes only on the broker ack. On a half-open socket (walking
      // out of wifi range) the long keepalive won't notice the dead connection, and HiveMQ may
      // never
      // settle the future, so await(timeout) force-completes it to keep the single outbound loop
      // moving instead of wedging it forever.
      withContext(ioDispatcher) {
        c.publishWith()
            .topic(message.topic)
            .payload(message.toJsonBytes(parser))
            .qos(MqttQos.fromCode(message.qos) ?: MqttQos.AT_LEAST_ONCE)
            .retain(message.retained)
            .send()
            .await(publishTimeout)
      }
      Timber.v("MQTT message sent")
      Result.success(Unit)
    } catch (e: Exception) {
      Timber.w(e, "Error publishing message $message")
      messageProcessor.onMessageDeliveryFailed(message)
      // A publish that times out means the connection is dead even though we still think we're
      // CONNECTED (half-open socket the keepalive hasn't caught). The client's outbound flow is
      // likely wedged, so force a fresh reconnect — retrying on the same client would just hang
      // again — and the new client wakes the loop via the connected listener's queue notify.
      if (e is TimeoutException) {
        Timber.w("Publish timed out; forcing reconnect so the retry uses a fresh client")
        scheduler.scheduleMqttReconnect()
      }
      Result.failure(OutgoingMessageSendingException(e))
    }
  }

  override fun onPreferenceChanged(properties: Set<String>) {
    if (preferences.mode != ConnectionMode.MQTT) {
      Timber.d("Preference changed but MQTT not active. Ignoring.")
      return
    }
    val propertiesWeWantToReconnectOn =
        setOf(
            Preferences::deviceId.name,
            Preferences::keepalive.name,
            Preferences::mqttProtocolLevel.name,
            Preferences::password.name,
            Preferences::tls.name,
            Preferences::ws.name,
            Preferences::wsPath.name)
    if (propertiesWeWantToReconnectOn
        .stream()
        .filter(properties::contains)
        .collect(Collectors.toSet())
        .isNotEmpty()) {
      Timber.d("Reconnecting to broker because of preference change")
      scope.launch {
        try {
          connect(getEndpointConfiguration())
        } catch (e: Exception) {
          when (e) {
            is ConfigurationIncompleteException ->
                endpointStateRepo.setState(EndpointState.ERROR_CONFIGURATION.withError(e))
            else -> endpointStateRepo.setState(EndpointState.ERROR.withError(e))
          }
        }
      }
    }
  }

  override suspend fun reconnect(): Result<Unit> {
    // Always rebuild from current preferences so any reconnect picks up credential/host changes.
    val config =
        try {
          getEndpointConfiguration()
        } catch (e: ConfigurationIncompleteException) {
          Timber.w("MQTT not configured, skipping reconnect: ${e.message}")
          endpointStateRepo.setState(EndpointState.ERROR_CONFIGURATION.withError(e))
          return Result.failure(e)
        }
    return connect(config)
  }

  override fun checkConnection(): Boolean = client?.state?.isConnected ?: false

  class NotConnectedException : Exception()
}

/** Schedules the [CompletableFuture.await] force-completions. One daemon thread is plenty. */
private val futureTimeoutScheduler: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { runnable ->
      Thread(runnable, "mqtt-future-timeout").apply { isDaemon = true }
    }

/**
 * Bridges a [CompletableFuture] to a cancellable coroutine, **force-completing the future** after
 * [timeout] if it hasn't settled. HiveMQ's async client has cases where a publish/subscribe/connect
 * future is never completed during connection-state transitions or on a half-open socket (e.g.
 * github hivemq-mqtt-client #554, #612). A coroutine-level `withTimeout` can't unwind such an await
 * (the body is parked on a future that never settles), so we complete the future ourselves — which
 * always resumes this await — rather than relying on cancellation propagation.
 */
internal suspend fun <T> CompletableFuture<T>.await(timeout: Duration): T =
    suspendCancellableCoroutine { cont ->
      val timeoutTask =
          futureTimeoutScheduler.schedule(
              { completeExceptionally(TimeoutException("Future not settled within $timeout")) },
              timeout.inWholeMilliseconds,
              TimeUnit.MILLISECONDS)
      whenComplete { value, error ->
        timeoutTask.cancel(false)
        if (error != null) cont.resumeWithException(error) else cont.resume(value)
      }
      cont.invokeOnCancellation {
        timeoutTask.cancel(false)
        cancel(true)
      }
    }
