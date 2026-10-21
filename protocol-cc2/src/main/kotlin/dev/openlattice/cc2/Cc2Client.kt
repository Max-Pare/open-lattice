package dev.openlattice.cc2

import dev.openlattice.core.PrinterCommandException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * One MQTT session with a Centauri Carbon 2.
 *
 * Wire protocol (verified against a capture of ElegooSlicer 1.5.3.5 <-> firmware 02.01.01.07):
 *  - connect to the printer's own broker, user `elegoo`, password = access code
 *  - publish `{client_id, request_id}` to `elegoo/<sn>/api_register`, reply on `.../<request_id>/register_response`
 *  - requests `{id, method, params}` on `elegoo/<sn>/<cid>/api_request`, replies on `.../api_response`
 *  - status deltas (method 6000) on `elegoo/<sn>/api_status`
 *  - heartbeat `{"type":"PING"}` -> `{"type":"PONG"}` on the request/response topics
 */
class Cc2Client(
    private val host: String,
    private val serial: String,
    private val accessCode: String,
    private val scope: CoroutineScope,
    private val transportFactory: MqttTransportFactory = HiveMqTransport.Factory,
    private val port: Int = Cc2Method.MQTT_PORT,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val clientId: String = "1_PC_${Random.nextInt(1000, 10000)}"
    private val requestTopic = "elegoo/$serial/$clientId/api_request"
    private val responseTopic = "elegoo/$serial/$clientId/api_response"
    private val statusTopic = "elegoo/$serial/api_status"
    private val registerTopic = "elegoo/$serial/api_register"
    private val registerRequestId = "${clientId}_req"
    private val registerResponseTopic = "elegoo/$serial/$registerRequestId/register_response"

    private val _status = MutableStateFlow<JsonObject?>(null)
    /** Full status (method 1002 result) with every later 6000 delta merged in. */
    val status: StateFlow<JsonObject?> = _status.asStateFlow()

    private val _attributes = MutableStateFlow<JsonObject?>(null)
    val attributes: StateFlow<JsonObject?> = _attributes.asStateFlow()

    /** Invoked once if the session dies (broker drop or heartbeat timeout). */
    var onLost: ((String) -> Unit)? = null

    private var transport: MqttTransport? = null
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private var registerReply: CompletableDeferred<JsonObject>? = null
    @Volatile private var lastInbound = 0L
    @Volatile private var lastStatusId = -1L
    private var statusGaps = 0
    private var heartbeat: Job? = null
    @Volatile private var closed = false

    /** Connects, registers and loads the first full status. Throws on failure. */
    suspend fun connect() {
        val t = transportFactory.create(host, port, clientId, Cc2Method.MQTT_USER, accessCode)
        transport = t
        t.onConnectionLost = { cause -> lost("Connection lost: ${cause?.message ?: "unknown"}") }
        t.connect()

        val reg = CompletableDeferred<JsonObject>()
        registerReply = reg
        t.subscribe(registerResponseTopic) { _, payload -> parse(payload)?.let { reg.complete(it) } }
        t.subscribe(responseTopic) { _, payload -> onResponse(payload) }
        t.subscribe(statusTopic) { _, payload -> onStatus(payload) }

        t.publish(registerTopic, buildJsonObject {
            put("client_id", clientId)
            put("request_id", registerRequestId)
        }.toString().toByteArray())
        val reply = withTimeout(REGISTER_TIMEOUT_MS) { reg.await() }
        val error = (reply / "error").str().orEmpty()
        if (error != "ok" && !error.contains("already", ignoreCase = true)) {
            throw Cc2RegisterException(error)
        }

        lastInbound = clock()
        heartbeat = scope.launch { heartbeatLoop() }
        refreshStatus()
        runCatching { _attributes.value = request(Cc2Method.GET_ATTRIBUTES) }
    }

    suspend fun refreshStatus() {
        val full = request(Cc2Method.GET_STATUS)
        _status.value = full
        statusGaps = 0
    }

    /**
     * Sends one RPC and returns its `result`. Throws [PrinterCommandException] when
     * `error_code` is non-zero, or a timeout exception when the printer does not answer.
     */
    suspend fun request(method: Int, params: JsonObject? = null, timeoutMs: Long = DEFAULT_TIMEOUT_MS): JsonObject {
        require(method in Cc2Method.ALLOWED) { "Method $method is not on the allowlist" }
        val t = transport ?: error("Not connected")
        val id = nextId.getAndIncrement()
        val reply = CompletableDeferred<JsonObject>()
        pending[id] = reply
        try {
            val body = buildJsonObject {
                put("id", id)
                put("method", method)
                put("params", params ?: JsonObject(emptyMap()))
            }
            t.publish(requestTopic, body.toString().toByteArray())
            val result = withTimeout(timeoutMs) { reply.await() }
            val code = (result / "error_code").int() ?: 0
            if (code != 0) throw PrinterCommandException(code, Cc2Method.errorMessage(code))
            return result
        } finally {
            pending.remove(id)
        }
    }

    private fun onResponse(payload: ByteArray) {
        lastInbound = clock()
        val msg = parse(payload) ?: return
        if ((msg / "type").str() == "PONG") return
        val id = (msg / "id").int() ?: return
        val result = (msg / "result").obj() ?: JsonObject(emptyMap())
        pending[id]?.complete(result)
    }

    private fun onStatus(payload: ByteArray) {
        lastInbound = clock()
        val msg = parse(payload) ?: return
        val result = (msg / "result").obj() ?: return
        when ((msg / "method").int()) {
            Cc2Method.EVENT_STATUS -> {
                val id = (msg / "id").lng() ?: -1
                val prev = lastStatusId
                lastStatusId = id
                if (prev >= 0 && id != prev + 1) statusGaps++
                _status.update { it?.mergeDelta(result) }
                if (statusGaps >= MAX_STATUS_GAPS) {
                    statusGaps = 0
                    scope.launch { runCatching { refreshStatus() } }
                }
            }
            Cc2Method.EVENT_ATTRIBUTES -> _attributes.update { it?.mergeDelta(result) ?: result }
        }
    }

    private suspend fun heartbeatLoop() {
        val ping = """{"type":"PING"}""".toByteArray()
        while (scope.isActive && !closed) {
            delay(HEARTBEAT_INTERVAL_MS)
            if (clock() - lastInbound > HEARTBEAT_TIMEOUT_MS) {
                lost("Printer stopped responding")
                return
            }
            runCatching { transport?.publish(requestTopic, ping) }
        }
    }

    private fun lost(reason: String) {
        if (closed) return
        closed = true
        heartbeat?.cancel()
        pending.values.forEach { it.completeExceptionally(IllegalStateException(reason)) }
        onLost?.invoke(reason)
    }

    suspend fun close() {
        closed = true
        heartbeat?.cancel()
        pending.values.forEach { it.cancel() }
        transport?.disconnect()
        transport = null
    }

    private fun parse(payload: ByteArray): JsonObject? =
        runCatching { Cc2Json.parseToJsonElement(payload.decodeToString()).jsonObject }.getOrNull()

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
        const val REGISTER_TIMEOUT_MS = 5_000L
        const val HEARTBEAT_INTERVAL_MS = 10_000L
        const val HEARTBEAT_TIMEOUT_MS = 65_000L
        const val MAX_STATUS_GAPS = 5
    }
}

class Cc2RegisterException(val reason: String) : Exception(
    if (reason.contains("too many", ignoreCase = true)) "Printer has too many connected clients; try again in a minute"
    else "Printer refused registration: $reason"
)
