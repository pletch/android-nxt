package org.owntracks.android.data

import android.content.Context
import com.hivemq.client.mqtt.mqtt3.exceptions.Mqtt3ConnAckException
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAckReturnCode
import java.io.EOFException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLProtocolException
import org.owntracks.android.R
import org.owntracks.android.net.mqtt.MqttConnectionConfiguration
import org.owntracks.android.support.interfaces.ConfigurationIncompleteException
import timber.log.Timber

enum class EndpointState {
  INITIAL,
  IDLE,
  CONNECTING,
  CONNECTED,
  DISCONNECTED,
  ERROR,
  ERROR_CONFIGURATION;

  var message: String? = null
  var error: Throwable? = null
    private set

  fun withMessage(message: String): EndpointState {
    this.message = message
    return this
  }

  fun getLabel(context: Context): String =
      when (this) {
        INITIAL -> context.resources.getString(R.string.INITIAL)
        IDLE -> context.resources.getString(R.string.IDLE)
        CONNECTING -> context.resources.getString(R.string.CONNECTING)
        CONNECTED -> context.resources.getString(R.string.CONNECTED)
        DISCONNECTED -> context.resources.getString(R.string.DISCONNECTED)
        ERROR -> context.resources.getString(R.string.ERROR)
        ERROR_CONFIGURATION -> context.resources.getString(R.string.ERROR_CONFIGURATION)
      }

  fun getErrorLabel(context: Context): String =
      when (val e = error) {
        // Configuration validator has failed
        is ConfigurationIncompleteException ->
            when (e.cause) {
              is MqttConnectionConfiguration.MissingHostException ->
                  context.getString(R.string.statusEndpointStateMessageMissingHost)
              is IllegalArgumentException ->
                  context.getString(R.string.statusEndpointStateMessageMalformedHostPort)
              else -> e.toString()
            }
        else -> {
          // Inspect the whole cause chain. The MQTT/HTTP clients wrap the underlying network/TLS
          // failure, so we match on the cause type rather than on a particular client exception.
          val causes = generateSequence(e) { it.cause }.toList()
          val connAck = causes.firstNotNullOfOrNull { it as? Mqtt3ConnAckException }
          when {
            // Broker rejected the connection with a CONNACK return code
            connAck != null ->
                when (connAck.mqttMessage.returnCode) {
                  Mqtt3ConnAckReturnCode.UNSUPPORTED_PROTOCOL_VERSION ->
                      context.getString(R.string.statusEndpointStateMessageInvalidProtocolVersion)
                  Mqtt3ConnAckReturnCode.IDENTIFIER_REJECTED ->
                      context.getString(R.string.statusEndpointStateMessageInvalidClientId)
                  Mqtt3ConnAckReturnCode.BAD_USER_NAME_OR_PASSWORD ->
                      context.getString(R.string.statusEndpointStateMessageAuthenticationFailed)
                  Mqtt3ConnAckReturnCode.NOT_AUTHORIZED ->
                      context.getString(R.string.statusEndpointStateMessageNotAuthorized)
                  else -> context.getString(R.string.statusEndpointStateMessageUnableToConnect)
                }
            causes.any { it is UnknownHostException } ->
                context.getString(R.string.statusEndpointStateMessageUnknownHost)
            causes.any { it is SocketTimeoutException } ->
                context.getString(R.string.statusEndpointStateMessageSocketTimeout)
            causes.any { it is ConnectException } ->
                if (causes.any { it.message?.contains("ECONNREFUSED") == true }) {
                  context.getString(R.string.statusEndpointStateMessageConnectionRefused)
                } else {
                  context.getString(R.string.statusEndpointStateMessageUnableToConnect)
                }
            causes.any { it is CertPathValidatorException } ->
                context.getString(R.string.statusEndpointStateMessageTLSEndpointCANotTrustedError)
            causes.any {
              it is SSLProtocolException &&
                  it.message?.contains("TLSV1_ALERT_CERTIFICATE_REQUIRED") == true
            } ->
                context.getString(R.string.statusEndpointStateMessageTLSEndpointClientCertsRequired)
            causes.any { it is SSLException } ->
                context.getString(R.string.statusEndpointStateMessageTLSError, e?.message)
            causes.any { it is EOFException } ->
                context.getString(R.string.statusEndpointStateMessageEOFError)
            else -> e?.message ?: e.toString()
          }
        }
      }.also { Timber.v(error, "Rendering error as $it") }

  fun withError(error: Throwable): EndpointState {
    this.error = error
    return this
  }

  override fun toString(): String {
    return if (message != null) {
      "${super.toString()} ($message)"
    } else {
      super.toString()
    }
  }
}
