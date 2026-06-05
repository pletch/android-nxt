package org.owntracks.android.net.mqtt

import android.content.Context
import android.security.KeyChain
import com.hivemq.client.mqtt.MqttClientSslConfig
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManagerFactory
import org.owntracks.android.net.CALeafCertMatchingHostnameVerifier

/**
 * Builds a HiveMQ [MqttClientSslConfig] from the app's CA keystore and optional client certificate,
 * reusing [CALeafCertMatchingHostnameVerifier] so self-signed / CA-leaf setups keep working (the
 * same trust/key/hostname behaviour the Paho SocketFactory provided).
 *
 * KeyChain access is blocking, so call off the main thread.
 */
fun buildMqttSslConfig(
    context: Context,
    caKeyStore: KeyStore,
    clientCertificateAlias: String,
    handshakeTimeoutSeconds: Int,
): MqttClientSslConfig {
  val trustManagerFactory =
      TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
        init(caKeyStore)
      }
  val keyManagerFactory =
      KeyManagerFactory.getInstance("X509").apply {
        init(null, null)
        if (clientCertificateAlias.isNotEmpty()) {
          KeyChain.getPrivateKey(context, clientCertificateAlias)?.let { privateKey ->
            val chain = KeyChain.getCertificateChain(context, clientCertificateAlias)
            val clientKeyStore =
                KeyStore.getInstance("PKCS12", "BC").apply {
                  load(null, null)
                  setKeyEntry(clientCertificateAlias, privateKey, "".toCharArray(), chain)
                }
            init(clientKeyStore, null)
          }
        }
      }
  return MqttClientSslConfig.builder()
      .keyManagerFactory(keyManagerFactory)
      .trustManagerFactory(trustManagerFactory)
      .protocols(listOf("TLSv1.2", "TLSv1.3"))
      .hostnameVerifier(CALeafCertMatchingHostnameVerifier())
      .handshakeTimeout(handshakeTimeoutSeconds.toLong(), TimeUnit.SECONDS)
      .build()
}
