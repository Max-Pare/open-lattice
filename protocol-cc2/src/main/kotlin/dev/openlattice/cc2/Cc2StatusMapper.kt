package dev.openlattice.cc2

import dev.openlattice.core.ConnectionState
import dev.openlattice.core.Fan
import dev.openlattice.core.FilamentHub
import dev.openlattice.core.FilamentSlot
import dev.openlattice.core.FilamentSystem
import dev.openlattice.core.MachineState
import dev.openlattice.core.PausedReason
import dev.openlattice.core.Position
import dev.openlattice.core.PrintJob
import dev.openlattice.core.PrinterException
import dev.openlattice.core.PrinterState
import dev.openlattice.core.SpeedMode
import dev.openlattice.core.Temperature
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt

/**
 * Maps the CC2's raw status JSON (Klipper-flavoured, nested) onto [PrinterState].
 *
 * Status and sub-status tables ported from elegoo-cc2-webui (MIT, (c) nyakaspeter),
 * server/src/cc2/mapper.ts, and checked against captures from firmware 02.01.01.07.
 */
object Cc2StatusMapper {

    fun map(raw: JsonObject, canvas: JsonObject? = null, mono: JsonObject? = null): PrinterState {
        val ms = (raw / "machine_status").obj()
        val status = (ms / "status").int() ?: 1
        val sub = (ms / "sub_status").int() ?: 0
        val machine = machineState(status, sub)

        val print = (raw / "print_status").obj()
        val move = ((raw / "gcode_move") ?: (raw / "gcode_move_inf")).obj()
        val fans = (raw / "fans").obj()
        val extruder = (raw / "extruder").obj()
        val bed = (raw / "heater_bed").obj()
        val chamber = (raw / "ztemperature_sensor").obj()
        val toolHead = ((raw / "tool_head") ?: (raw / "toolhead")).obj()
        val external = (raw / "external_device").obj()

        val fileName = (print / "filename").str().orEmpty()
        val job = if (fileName.isNotEmpty() && (status == STATUS_PRINTING || machine.isJobActive)) {
            PrintJob(
                fileName = fileName,
                progress = ((print / "progress").int() ?: (ms / "progress").int() ?: 0).coerceIn(0, 100),
                currentLayer = (print / "current_layer").int() ?: 0,
                totalLayers = (print / "total_layer").int() ?: 0,
                elapsedSec = (print / "print_duration").lng() ?: 0,
                remainingSec = (print / "remaining_time_sec").lng() ?: 0,
                taskId = (print / "uuid").str()?.takeIf { it.isNotEmpty() },
            )
        } else null

        return PrinterState(
            connection = ConnectionState.Connected,
            machine = machine,
            job = job,
            nozzle = extruder?.let { Temperature((it / "temperature").dbl() ?: 0.0, (it / "target").dbl() ?: 0.0) },
            bed = bed?.let { Temperature((it / "temperature").dbl() ?: 0.0, (it / "target").dbl() ?: 0.0) },
            chamber = chamber?.let { Temperature((it / "temperature").dbl() ?: 0.0, 0.0) },
            fans = buildMap {
                fanPercent(fans, "fan")?.let { put(Fan.MODEL, it) }
                fanPercent(fans, "aux_fan")?.let { put(Fan.AUX, it) }
                fanPercent(fans, "box_fan")?.let { put(Fan.CHAMBER, it) }
            },
            lightOn = ((raw / "led").obj() / "status").int()?.let { it > 0 },
            speedMode = (move / "speed_mode").int()?.let { SPEED_MODES.getOrNull(it) },
            position = move?.let { Position((it / "x").dbl() ?: 0.0, (it / "y").dbl() ?: 0.0, (it / "z").dbl() ?: 0.0) },
            homedAxes = (toolHead / "homed_axes").str().orEmpty(),
            filament = mapFilament(canvas, mono),
            cameraAvailable = (external / "camera").bool() ?: false,
            usbConnected = (external / "u_disk").bool() ?: false,
            exceptions = mapExceptions(raw),
            pausedReason = when {
                machine != MachineState.PAUSED -> null
                sub == SUB_RUNOUT -> PausedReason.FILAMENT_RUNOUT
                status == STATUS_FOREIGN_OBJECT -> PausedReason.OTHER
                else -> PausedReason.USER
            },
        )
    }

    val SPEED_MODES = listOf(SpeedMode.SILENT, SpeedMode.BALANCED, SpeedMode.SPORT, SpeedMode.LUDICROUS)

    private const val STATUS_PRINTING = 2
    private const val STATUS_FOREIGN_OBJECT = 17
    private const val SUB_RUNOUT = 2505

    fun machineState(status: Int, sub: Int): MachineState = when (status) {
        -1 -> MachineState.OFFLINE
        1 -> MachineState.IDLE
        STATUS_PRINTING -> when (sub) {
            2501 -> MachineState.PAUSING
            2502, SUB_RUNOUT -> MachineState.PAUSED
            2401, 2402 -> MachineState.RESUMING
            2503 -> MachineState.STOPPING
            2504 -> MachineState.STOPPED
            2077 -> MachineState.COMPLETED
            1045, 1096, 1405, 1906 -> MachineState.PREHEATING
            2801, 2802 -> MachineState.HOMING
            2901, 2902 -> MachineState.LEVELING
            else -> MachineState.PRINTING
        }
        3 -> MachineState.LOADING
        4 -> MachineState.UNLOADING
        13 -> if (sub == 1062 || sub == 1064) MachineState.UNLOADING else MachineState.LOADING
        5 -> MachineState.LEVELING
        10 -> MachineState.HOMING
        14, 15 -> MachineState.ERROR
        STATUS_FOREIGN_OBJECT -> MachineState.PAUSED
        else -> MachineState.BUSY
    }

    private val EXCEPTION_MESSAGES = mapOf(
        109 to "Filament runout detected",
        1301 to "Print failure (spaghetti) detected",
        1302 to "Foreign object detected on the bed",
    )

    private fun mapExceptions(raw: JsonObject): List<PrinterException> {
        val codes = linkedSetOf<Int>()
        // Older firmware: machine_status.exception_status is an array of codes.
        ((raw / "machine_status").obj() / "exception_status").arr()?.forEach { e -> e.int()?.takeIf { it != 0 }?.let(codes::add) }
        // Newer firmware: exception.exception_code is an object keyed by code.
        ((raw / "exception").obj() / "exception_code").obj()?.keys?.forEach { k -> k.toIntOrNull()?.takeIf { it != 0 }?.let(codes::add) }
        return codes.map { PrinterException(it, EXCEPTION_MESSAGES[it] ?: Cc2Method.errorMessage(it)) }
    }

    /** Wire format is 0-255. */
    private fun fanPercent(fans: JsonObject?, key: String): Int? =
        ((fans / key).obj() / "speed").dbl()?.let { (it / 255.0 * 100).roundToInt().coerceIn(0, 100) }

    fun percentToFanValue(percent: Int): Int = (percent.coerceIn(0, 100) / 100.0 * 255).roundToInt()

    internal fun parseColor(raw: String?): String? {
        var c = raw?.trim().orEmpty()
        if (c.isEmpty()) return null
        if (c.startsWith("0x", ignoreCase = true)) c = c.substring(2)
        if (!c.startsWith("#")) c = "#$c"
        return c.takeIf { Regex("^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$").matches(it) }?.take(7)?.uppercase()
    }

    private fun slot(t: JsonObject, hubId: Int, activeHub: Int, activeTray: Int): FilamentSlot {
        val trayId = (t / "tray_id").int() ?: 0
        val status = (t / "status").int() ?: 0 // 0 empty, 1 preloaded, 2 loaded
        return FilamentSlot(
            hubId = hubId,
            trayId = trayId,
            material = (t / "filament_type").str().orEmpty(),
            name = (t / "filament_name").str().orEmpty(),
            brand = (t / "brand").str().orEmpty(),
            color = parseColor((t / "filament_color").str()),
            present = status > 0,
            active = hubId == activeHub && trayId == activeTray && status == 2,
            minNozzleTemp = (t / "min_nozzle_temp").int() ?: 0,
            maxNozzleTemp = (t / "max_nozzle_temp").int() ?: 0,
        )
    }

    fun mapFilament(canvasInfo: JsonObject?, mono: JsonObject?): FilamentSystem? {
        val activeHub = (canvasInfo / "active_canvas_id").int() ?: -1
        val activeTray = (canvasInfo / "active_tray_id").int() ?: -1
        val hubs = (canvasInfo / "canvas_list").arr().orEmpty()
            .mapNotNull { it.obj() }
            .filter { ((it / "connected").int() ?: 1) != 0 }
            .map { c ->
                val id = (c / "canvas_id").int() ?: 0
                FilamentHub(
                    id = id,
                    humidity = (c / "humidity").dbl(),
                    slots = (c / "tray_list").arr().orEmpty().mapNotNull { it.obj() }
                        .mapIndexed { i, t -> slot(t, id, activeHub, activeTray).let { s -> if (s.trayId == 0 && i > 0) s.copy(trayId = i) else s } },
                )
            }
        val external = mono?.let { slot(it, -1, -1, -1).copy(present = true, active = hubs.isEmpty() || activeTray < 0) }
        if (hubs.isEmpty() && external == null) return null
        return FilamentSystem(hubs, external, autoRefill = (canvasInfo / "auto_refill").bool() ?: false)
    }
}
