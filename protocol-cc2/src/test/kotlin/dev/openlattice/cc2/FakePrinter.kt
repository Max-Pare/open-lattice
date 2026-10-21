package dev.openlattice.cc2

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList

internal fun fixture(name: String): String =
    requireNotNull(FakePrinter::class.java.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()

internal fun fixtureObject(name: String): JsonObject = Cc2Json.parseToJsonElement(fixture(name)).jsonObject

/**
 * In-memory stand-in for the printer's MQTT broker, answering like firmware 02.01.01.07 did in the capture.
 * [results] maps method -> `result` object; methods without an entry answer `{"error_code":1001}`.
 */
internal class FakePrinter(
    val serial: String = "F01TESTSERIAL01",
    val accessCode: String = "abc123",
    var registerError: String = "ok",
    val results: MutableMap<Int, JsonObject> = mutableMapOf(),
) : MqttTransportFactory {
    val published = CopyOnWriteArrayList<Pair<String, String>>()
    private val subscriptions = CopyOnWriteArrayList<Pair<String, (String, ByteArray) -> Unit>>()
    var transport: Transport? = null

    override fun create(host: String, port: Int, clientId: String, username: String, password: String): MqttTransport =
        Transport(username, password).also { transport = it }

    fun requests(): List<JsonObject> =
        published.filter { it.first.endsWith("/api_request") }.map { Cc2Json.parseToJsonElement(it.second).jsonObject }

    fun pushStatus(json: String) = deliver("elegoo/$serial/api_status", json)

    private fun deliver(topic: String, json: String) {
        subscriptions.filter { it.first == topic }.forEach { it.second(topic, json.toByteArray()) }
    }

    inner class Transport(private val user: String, private val pass: String) : MqttTransport {
        override var onConnectionLost: ((Throwable?) -> Unit)? = null

        override suspend fun connect() {
            if (user != Cc2Method.MQTT_USER || pass != accessCode) throw MqttAuthException("bad credentials")
        }

        override suspend fun subscribe(topic: String, onMessage: (topic: String, payload: ByteArray) -> Unit) {
            subscriptions += topic to onMessage
        }

        override suspend fun publish(topic: String, payload: ByteArray) {
            val text = payload.decodeToString()
            published += topic to text
            val msg = Cc2Json.parseToJsonElement(text).jsonObject
            when {
                topic == "elegoo/$serial/api_register" -> {
                    val rid = (msg / "request_id").str()!!
                    deliver("elegoo/$serial/$rid/register_response", buildJsonObject {
                        put("client_id", (msg / "client_id").str())
                        put("error", registerError)
                    }.toString())
                }
                topic.endsWith("/api_request") -> {
                    val responseTopic = topic.removeSuffix("api_request") + "api_response"
                    if ((msg / "type").str() == "PING") {
                        deliver(responseTopic, """{"type":"PONG"}""")
                        return
                    }
                    val method = (msg / "method").int()!!
                    val result = results[method] ?: buildJsonObject { put("error_code", if (method in OK_BY_DEFAULT) 0 else 1001) }
                    deliver(responseTopic, buildJsonObject {
                        put("id", (msg / "id").int())
                        put("method", method)
                        put("result", result)
                    }.toString())
                }
            }
        }

        override suspend fun disconnect() {}

        fun drop() = onConnectionLost?.invoke(RuntimeException("dropped"))
    }

    companion object {
        /** Control methods the capture showed answering a bare `{"error_code":0}`. */
        val OK_BY_DEFAULT = setOf(
            Cc2Method.PAUSE_PRINT, Cc2Method.RESUME_PRINT, Cc2Method.STOP_PRINT, Cc2Method.SET_LIGHT,
            Cc2Method.SET_FAN, Cc2Method.SET_TEMPERATURE, Cc2Method.SET_SPEED_MODE, Cc2Method.HOME_AXES,
            Cc2Method.MOVE_AXES, Cc2Method.START_PRINT, Cc2Method.FILE_DELETE, Cc2Method.HISTORY_DELETE,
        )

        fun withCapturedFixtures() = FakePrinter().apply {
            results[Cc2Method.GET_STATUS] = fixtureObject("status_idle.json")
            results[Cc2Method.GET_ATTRIBUTES] = fixtureObject("attributes.json")
            results[Cc2Method.GET_CANVAS_STATUS] = fixtureObject("canvas.json")
            results[Cc2Method.GET_MONO_FILAMENT] = fixtureObject("mono_filament.json")
            results[Cc2Method.FILE_LIST] = fixtureObject("file_list.json")
            results[Cc2Method.HISTORY_LIST] = fixtureObject("history.json")
            results[Cc2Method.FILE_THUMBNAIL] = fixtureObject("thumbnail.json")
            results[Cc2Method.VIDEO_STREAM_URL] = fixtureObject("video_url.json")
        }
    }
}
