package dev.openlattice.cc2

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.exceptions.Mqtt3ConnAckException
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAckReturnCode
import kotlinx.coroutines.future.await
import java.util.concurrent.TimeUnit

/** Minimal MQTT 3.1.1 surface the CC2 client needs; swapped for a fake in tests. */
interface MqttTransport {
    /** Throws [MqttAuthException] when the printer rejects the access code. */
    suspend fun connect()
    suspend fun subscribe(topic: String, onMessage: (topic: String, payload: ByteArray) -> Unit)
    suspend fun publish(topic: String, payload: ByteArray)
    suspend fun disconnect()
    /** Called once when the connection drops without [disconnect] being called. */
    var onConnectionLost: ((Throwable?) -> Unit)?
}

class MqttAuthException(message: String) : Exception(message)

fun interface MqttTransportFactory {
    fun create(host: String, port: Int, clientId: String, username: String, password: String): MqttTransport
}

/** HiveMQ-backed transport; no TLS, QoS 1, clean session (matches ElegooSlicer). */
class HiveMqTransport(
    private val host: String,
    private val port: Int,
    private val clientId: String,
    private val username: String,
    private val password: String,
) : MqttTransport {
    override var onConnectionLost: ((Throwable?) -> Unit)? = null
    @Volatile private var closing = false

    private val client: Mqtt3AsyncClient = MqttClient.builder()
        .useMqttVersion3()
        .identifier(clientId)
        .serverHost(host)
        .serverPort(port)
        .addDisconnectedListener { ctx -> if (!closing) onConnectionLost?.invoke(ctx.cause) }
        .buildAsync()

    override suspend fun connect() {
        try {
            client.connectWith()
                .cleanSession(true)
                .keepAlive(60)
                .simpleAuth().username(username).password(password.toByteArray()).applySimpleAuth()
                .send()
                .orTimeout(10, TimeUnit.SECONDS)
                .await()
        } catch (e: Mqtt3ConnAckException) {
            val rc = e.mqttMessage.returnCode
            if (rc == Mqtt3ConnAckReturnCode.NOT_AUTHORIZED || rc == Mqtt3ConnAckReturnCode.BAD_USER_NAME_OR_PASSWORD) {
                throw MqttAuthException("Printer rejected the access code")
            }
            throw e
        }
    }

    override suspend fun subscribe(topic: String, onMessage: (topic: String, payload: ByteArray) -> Unit) {
        client.subscribeWith()
            .topicFilter(topic)
            .qos(MqttQos.AT_LEAST_ONCE)
            .callback { msg -> onMessage(msg.topic.toString(), msg.payloadAsBytes) }
            .send()
            .orTimeout(10, TimeUnit.SECONDS)
            .await()
    }

    override suspend fun publish(topic: String, payload: ByteArray) {
        client.publishWith()
            .topic(topic)
            .qos(MqttQos.AT_LEAST_ONCE)
            .payload(payload)
            .send()
            .orTimeout(10, TimeUnit.SECONDS)
            .await()
    }

    override suspend fun disconnect() {
        closing = true
        runCatching { client.disconnect().orTimeout(3, TimeUnit.SECONDS).await() }
    }

    companion object {
        val Factory = MqttTransportFactory { host, port, clientId, user, pass -> HiveMqTransport(host, port, clientId, user, pass) }
    }
}
