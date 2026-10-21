package dev.openlattice.cc2

import dev.openlattice.core.BuildPlate
import dev.openlattice.core.Capability
import dev.openlattice.core.ConnectionState
import dev.openlattice.core.Fan
import dev.openlattice.core.FileColor
import dev.openlattice.core.HistoryEntry
import dev.openlattice.core.HistoryResult
import dev.openlattice.core.MachineState
import dev.openlattice.core.PrinterCommand
import dev.openlattice.core.PrinterCommandException
import dev.openlattice.core.PrinterDriver
import dev.openlattice.core.PrinterFile
import dev.openlattice.core.PrinterInfo
import dev.openlattice.core.PrinterState
import dev.openlattice.core.Storage
import dev.openlattice.core.StorageInfo
import dev.openlattice.core.Timelapse
import dev.openlattice.core.UploadProgress
import dev.openlattice.core.UploadSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import java.util.Base64
import java.util.concurrent.TimeUnit

/** [PrinterDriver] for the Centauri Carbon 2 / Centauri 2 local MQTT API. */
class Cc2Driver(
    private val host: String,
    private val serial: String,
    private val accessCode: String,
    private val http: OkHttpClient = defaultHttp,
    private val transportFactory: MqttTransportFactory = HiveMqTransport.Factory,
    private val mqttPort: Int = Cc2Method.MQTT_PORT,
    private val httpPort: Int = Cc2Method.HTTP_PORT,
    private val exceptionRefreshMs: Long = 15_000L,
) : PrinterDriver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectLock = Mutex()
    private var client: Cc2Client? = null
    private var stateJob: Job? = null
    private var exceptionRefresh: Job? = null
    private var reconnectJob: Job? = null
    @Volatile private var closed = false

    private val fileLayers = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val layerLookups: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val canvas = MutableStateFlow<JsonObject?>(null)
    private val mono = MutableStateFlow<JsonObject?>(null)

    private val _state = MutableStateFlow(PrinterState())
    override val state: StateFlow<PrinterState> = _state.asStateFlow()

    private val _info = MutableStateFlow<PrinterInfo?>(null)
    override val info: StateFlow<PrinterInfo?> = _info.asStateFlow()

    override val capabilities: Set<Capability> = setOf(
        Capability.CAMERA, Capability.CANVAS, Capability.USB_STORAGE, Capability.TIMELAPSE,
        Capability.CHAMBER_FAN, Capability.AUX_FAN,
    )

    private val baseUrl = if (httpPort == 80) "http://$host" else "http://$host:$httpPort"
    private val uploader = Cc2Uploader(http, baseUrl, accessCode)

    override suspend fun connect() {
        reconnectJob?.cancel()
        backoffMs = INITIAL_BACKOFF_MS
        doConnect()
    }

    override suspend fun disconnect() {
        reconnectJob?.cancel()
        connectLock.withLock {
            stateJob?.cancel()
            exceptionRefresh?.cancel()
            val c = client
            client = null
            c?.onLost = null
            runCatching { withTimeout(3000) { c?.close() } }
            connection.value = ConnectionState.Disconnected
            publishState()
        }
    }

    private suspend fun doConnect() = connectLock.withLock {
        if (closed) return@withLock
        client?.close()
        connection.value = ConnectionState.Connecting
        publishState()
        val c = Cc2Client(host, serial, accessCode, scope, transportFactory, mqttPort)
        try {
            c.connect()
        } catch (e: MqttAuthException) {
            c.close()
            connection.value = ConnectionState.Failed(e.message ?: "Access code rejected", authRejected = true)
            publishState()
            throw e
        } catch (e: Exception) {
            c.close()
            connection.value = ConnectionState.Failed(e.message ?: e.toString())
            publishState()
            scheduleReconnect()
            throw e
        }
        client = c
        c.onLost = { reason ->
            connection.value = ConnectionState.Failed(reason)
            publishState()
            scheduleReconnect()
        }
        connection.value = ConnectionState.Connected
        publishState()
        stateJob?.cancel()
        stateJob = combine(c.status, canvas, mono, connection) { _, _, _, _ -> publishState() }.launchIn(scope)
        exceptionRefresh?.cancel()
        exceptionRefresh = scope.launch { refreshWhileExceptions(c) }
        c.attributes.onEach { it?.let(::updateInfo) }.launchIn(scope)
        refreshFilament()
    }

    private var backoffMs = INITIAL_BACKOFF_MS

    private fun scheduleReconnect() {
        if (closed || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            while (!closed) {
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                try {
                    doConnect()
                    backoffMs = INITIAL_BACKOFF_MS
                    return@launch
                } catch (_: MqttAuthException) {
                    return@launch
                } catch (_: Exception) {
                    // connect() scheduled nothing new because this job is still active; keep looping.
                }
            }
        }
    }

    // Called from the status collector, the connection-lost callback and command paths concurrently.
    @Synchronized
    private fun publishState() {
        val raw = client?.status?.value
        val conn = connection.value
        val previousMachine = _state.value.machine
        val mapped = if (raw != null && conn == ConnectionState.Connected) {
            Cc2StatusMapper.map(raw, canvas.value, mono.value).let { s ->
                // print_status carries no layer total on 02.01.x; the file list does.
                val job = s.job
                if (job != null && job.totalLayers == 0) {
                    val layers = fileLayers[job.fileName]
                    if (layers == null && layerLookups.add(job.fileName)) scope.launch { runCatching { listFiles(Storage.LOCAL) } }
                    s.copy(job = job.copy(totalLayers = layers ?: 0))
                } else s
            }
        } else {
            _state.value.copy(connection = conn)
        }
        _state.value = mapped.copy(connection = conn)
        // Tray contents only change around loads, unloads and job transitions.
        if (conn == ConnectionState.Connected && previousMachine != mapped.machine && previousMachine != MachineState.UNKNOWN) {
            scope.launch { refreshFilament() }
        }
    }

    /**
     * The printer does not reliably push a delta when an exception clears, so the merged cache can
     * keep a resolved error forever. While any is shown, re-read the authoritative full status.
     */
    private suspend fun refreshWhileExceptions(c: Cc2Client) {
        while (true) {
            delay(exceptionRefreshMs)
            if (client !== c || connection.value != ConnectionState.Connected) return
            if (_state.value.exceptions.isNotEmpty()) runCatching { c.refreshStatus() }
        }
    }

    private fun updateInfo(attrs: JsonObject) {
        _info.value = PrinterInfo(
            name = (attrs / "hostname").str().orEmpty(),
            model = (attrs / "machine_model").str().orEmpty(),
            serial = (attrs / "sn").str() ?: serial,
            firmware = ((attrs / "software_version").obj() / "ota_version").str().orEmpty(),
            host = host,
        )
    }

    private suspend fun refreshFilament() {
        val c = client ?: return
        runCatching { canvas.value = (c.request(Cc2Method.GET_CANVAS_STATUS) / "canvas_info").obj() }
        runCatching { mono.value = (c.request(Cc2Method.GET_MONO_FILAMENT) / "mono_filament_info").obj() }
    }

    private fun requireClient(): Cc2Client =
        client?.takeIf { connection.value == ConnectionState.Connected }
            ?: throw PrinterCommandException(-1, "Printer is not connected")

    override suspend fun send(command: PrinterCommand) {
        val c = requireClient()
        when (command) {
            PrinterCommand.Pause -> c.request(Cc2Method.PAUSE_PRINT)
            PrinterCommand.Resume -> c.request(Cc2Method.RESUME_PRINT)
            PrinterCommand.Stop -> c.request(Cc2Method.STOP_PRINT)
            is PrinterCommand.SetNozzleTarget -> c.request(Cc2Method.SET_TEMPERATURE, buildJsonObject { put("extruder", command.celsius) })
            is PrinterCommand.SetBedTarget -> c.request(Cc2Method.SET_TEMPERATURE, buildJsonObject { put("heater_bed", command.celsius) })
            is PrinterCommand.SetFan -> c.request(Cc2Method.SET_FAN, buildJsonObject {
                val key = when (command.fan) {
                    Fan.MODEL -> "fan"
                    Fan.AUX -> "aux_fan"
                    Fan.CHAMBER -> "box_fan"
                }
                put(key, Cc2StatusMapper.percentToFanValue(command.percent))
            })
            is PrinterCommand.SetLight -> c.request(Cc2Method.SET_LIGHT, buildJsonObject { put("power", if (command.on) 1 else 0) })
            is PrinterCommand.SetSpeedMode -> c.request(Cc2Method.SET_SPEED_MODE, buildJsonObject {
                put("mode", Cc2StatusMapper.SPEED_MODES.indexOf(command.mode))
            })
            is PrinterCommand.Home -> c.request(Cc2Method.HOME_AXES, buildJsonObject { put("homed_axes", command.axes.lowercase()) }, LONG_TIMEOUT_MS)
            is PrinterCommand.Jog -> c.request(Cc2Method.MOVE_AXES, buildJsonObject {
                val axis = command.axis.lowercaseChar()
                put("axes", axis.toString())
                // The stock UI sends Z inverted relative to its buttons (bed moves, not the head).
                put("distance", if (axis == 'z') -command.distanceMm else command.distanceMm)
            })
            is PrinterCommand.StartPrint -> startPrint(c, command)
            is PrinterCommand.Rename -> c.request(Cc2Method.SET_NAME, buildJsonObject { put("hostname", command.name) })
            PrinterCommand.LoadFilament -> c.request(Cc2Method.FEED, timeoutMs = FILAMENT_TIMEOUT_MS)
            PrinterCommand.UnloadFilament -> c.request(Cc2Method.RETRACT, timeoutMs = FILAMENT_TIMEOUT_MS)
            is PrinterCommand.LoadTray -> c.request(Cc2Method.CANVAS_LOAD, tray(command.hubId, command.trayId), FILAMENT_TIMEOUT_MS)
            is PrinterCommand.UnloadTray -> c.request(Cc2Method.CANVAS_UNLOAD, tray(command.hubId, command.trayId), FILAMENT_TIMEOUT_MS)
            is PrinterCommand.SetFilament -> {
                // Shape from elegoo-cc2-webui; the external spool takes the same fields with canvas 0 / tray 0.
                val m = command.material
                val params = buildJsonObject {
                    put("brand", "Generic")
                    put("filament_type", m.label)
                    put("filament_name", m.label)
                    put("filament_code", m.code)
                    put("filament_color", command.color.uppercase())
                    put("filament_min_temp", m.minTemp)
                    put("filament_max_temp", m.maxTemp)
                    put("canvas_id", command.hubId.coerceAtLeast(0))
                    put("tray_id", if (command.hubId < 0) 0 else command.trayId)
                }
                c.request(if (command.hubId < 0) Cc2Method.SET_MONO_FILAMENT else Cc2Method.CANVAS_SET_FILAMENT, params)
                refreshFilament()
            }
            is PrinterCommand.SetAutoRefill -> {
                c.request(Cc2Method.SET_AUTO_REFILL, buildJsonObject { put("auto_refill", command.enabled) })
                refreshFilament()
            }
            PrinterCommand.Refresh -> {
                c.refreshStatus()
                refreshFilament()
            }
        }
    }

    private suspend fun startPrint(c: Cc2Client, cmd: PrinterCommand.StartPrint) {
        // Field meanings from elegoo-link (StartPrintParams in include/types/printer.h).
        fun params(forceLeveling: Boolean) = buildJsonObject {
            put("filename", cmd.fileName)
            put("storage_media", cmd.storage.wire)
            putJsonObject("config") {
                put("bedlevel_force", forceLeveling)
                put("delay_video", cmd.timelapse)
                // A = textured high-temperature plate, B = smooth low-temperature plate.
                put("print_layout", if (cmd.plate == BuildPlate.SMOOTH) "B" else "A")
                put("printer_check", cmd.autoBedLeveling)
                putJsonArray("slot_map") {
                    for (m in cmd.slotMap) add(buildJsonObject {
                        put("t", m.tool)
                        put("canvas_id", m.hubId)
                        put("tray_id", m.trayId)
                    })
                }
            }
        }
        try {
            c.request(Cc2Method.START_PRINT, params(forceLeveling = false), LONG_TIMEOUT_MS)
        } catch (e: PrinterCommandException) {
            // 1026: no bed mesh saved yet; force a leveling pass, as ElegooSlicer does.
            if (e.code != 1026) throw e
            c.request(Cc2Method.START_PRINT, params(forceLeveling = true), LONG_TIMEOUT_MS)
        }
    }

    override suspend fun listFiles(storage: Storage): List<PrinterFile> {
        val c = requireClient()
        val out = mutableListOf<PrinterFile>()
        var offset = 0
        while (offset < MAX_FILES) {
            val result = try {
                c.request(Cc2Method.FILE_LIST, buildJsonObject {
                    put("storage_media", storage.wire)
                    if (storage == Storage.USB) put("dir", "/")
                    put("offset", offset)
                    put("limit", FILE_PAGE)
                })
            } catch (e: PrinterCommandException) {
                if (e.code == 1017 || e.code == 1018) return emptyList()
                throw e
            }
            val page = (result / "file_list").arr().orEmpty().mapNotNull { it.obj() }
            page.mapTo(out, ::toFile)
            if (storage == Storage.LOCAL) {
                val known = fileLayers.size
                out.forEach { f -> f.layers?.let { fileLayers[f.name] = it } }
                if (fileLayers.size != known) publishState()
            }
            val total = (result / "total").int()
            if (page.size < FILE_PAGE || (total != null && out.size >= total)) break
            offset += FILE_PAGE
        }
        return out
    }

    private fun toFile(f: JsonObject) = PrinterFile(
        name = ((f / "filename").str() ?: (f / "name").str()).orEmpty().substringAfterLast('/'),
        sizeBytes = (f / "size").lng() ?: 0,
        createdAtEpochSec = (f / "create_time").lng(),
        printTimeSec = (f / "print_time").lng(),
        layers = (f / "layer").int(),
        filamentUsedGrams = (f / "total_filament_used").dbl(),
        colors = (f / "color_map").arr().orEmpty().mapNotNull { it.obj() }.map {
            FileColor(
                tool = (it / "t").int() ?: 0,
                color = Cc2StatusMapper.parseColor((it / "color").str()) ?: "#888888",
                material = (it / "name").str().orEmpty(),
            )
        },
        isFolder = (f / "type").str() == "folder",
    )

    private fun tray(hubId: Int, trayId: Int) = buildJsonObject {
        put("canvas_id", hubId)
        put("tray_id", trayId)
    }

    override suspend fun storageInfo(): StorageInfo? {
        val internal = (requireClient().request(Cc2Method.DISK_INFO) / "internal").obj() ?: return null
        val total = (internal / "total_bytes").lng() ?: return null
        return StorageInfo(total, (internal / "used_bytes").lng() ?: 0)
    }

    override suspend fun deleteFiles(storage: Storage, names: List<String>) {
        requireClient().request(Cc2Method.FILE_DELETE, buildJsonObject {
            put("storage_media", storage.wire)
            // Must be an array of bare names (no leading slash); scalars return 1003.
            put("file_path", buildJsonArray { names.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.removePrefix("/"))) } })
        })
    }

    override suspend fun thumbnail(storage: Storage, name: String): ByteArray? {
        val result = try {
            requireClient().request(Cc2Method.FILE_THUMBNAIL, buildJsonObject {
                put("storage_media", storage.wire)
                put("file_name", name.removePrefix("/"))
            })
        } catch (e: PrinterCommandException) {
            if (e.code == 1015) return null
            throw e
        }
        val b64 = (result / "thumbnail").str()?.substringAfter("base64,")?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { Base64.getMimeDecoder().decode(b64) }.getOrNull()
    }

    override suspend fun history(): List<HistoryEntry> {
        val result = requireClient().request(Cc2Method.HISTORY_LIST)
        val list = ((result / "history_task_list") ?: (result / "task_list")).arr().orEmpty().mapNotNull { it.obj() }
        return list.map { t ->
            val videoStatus = (t / "time_lapse_video_status").int() ?: 0
            HistoryEntry(
                taskId = (t / "task_id").str().orEmpty(),
                fileName = (t / "task_name").str().orEmpty(),
                startedAtEpochSec = (t / "begin_time").lng() ?: 0,
                endedAtEpochSec = (t / "end_time").lng()?.takeIf { it > 0 },
                // 0 other, 1 complete, 2/3 cancelled, 4 printing, 5 paused
                result = when ((t / "task_status").int()) {
                    1 -> HistoryResult.COMPLETED
                    2, 3 -> HistoryResult.STOPPED
                    4, 5 -> HistoryResult.IN_PROGRESS
                    else -> HistoryResult.UNKNOWN
                },
                timelapse = if (videoStatus == 1 || videoStatus == 2) Timelapse(
                    ready = videoStatus == 2,
                    path = (t / "time_lapse_video_url").str().orEmpty(),
                    sizeBytes = (t / "time_lapse_video_size").lng(),
                    durationSec = (t / "time_lapse_video_duration").int(),
                ) else null,
            )
        }.sortedByDescending { it.startedAtEpochSec }
    }

    override suspend fun deleteHistory(taskIds: List<String>) {
        requireClient().request(Cc2Method.HISTORY_DELETE, buildJsonObject {
            put("list", buildJsonArray { taskIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        })
    }

    override fun upload(source: UploadSource): Flow<UploadProgress> = uploader.upload(source)

    override suspend fun cameraUrl(): String? {
        val c = requireClient()
        val reported = runCatching { c.request(Cc2Method.VIDEO_STREAM_URL) }.getOrNull()
            ?.let { ((it / "url") ?: (it / "video_url")).str() }
        // Always talk to the host we connected to: the printer reports its LAN IP,
        // which may differ from the address reachable over a VPN or hostname.
        val path = reported?.let { runCatching { java.net.URI(it) }.getOrNull() }
        val port = path?.port?.takeIf { it > 0 } ?: CAMERA_PORT
        val query = path?.rawQuery ?: "action=stream"
        return "http://$host:$port/?$query"
    }

    override fun close() {
        closed = true
        reconnectJob?.cancel()
        val c = client
        client = null
        runBlocking { runCatching { withTimeout(3000) { c?.close() } } }
        scope.cancel()
        connection.value = ConnectionState.Disconnected
        _state.value = _state.value.copy(connection = ConnectionState.Disconnected)
    }

    private val Storage.wire: String get() = if (this == Storage.USB) "u-disk" else "local"

    companion object {
        const val CAMERA_PORT = 8080
        private const val FILE_PAGE = 20
        private const val MAX_FILES = 1000
        private const val LONG_TIMEOUT_MS = 30_000L
        private const val FILAMENT_TIMEOUT_MS = 300_000L
        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 30_000L

        val defaultHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }
}
