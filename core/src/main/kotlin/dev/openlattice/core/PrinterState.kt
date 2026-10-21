package dev.openlattice.core

/** Protocol-independent snapshot of a printer, as the UI sees it. */
data class PrinterState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val machine: MachineState = MachineState.UNKNOWN,
    val job: PrintJob? = null,
    val nozzle: Temperature? = null,
    val bed: Temperature? = null,
    val chamber: Temperature? = null,
    val fans: Map<Fan, Int> = emptyMap(),
    val lightOn: Boolean? = null,
    val speedMode: SpeedMode? = null,
    val position: Position? = null,
    val homedAxes: String = "",
    val filament: FilamentSystem? = null,
    val cameraAvailable: Boolean = false,
    val usbConnected: Boolean = false,
    val exceptions: List<PrinterException> = emptyList(),
    val pausedReason: PausedReason? = null,
)

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data class Failed(val reason: String, val authRejected: Boolean = false) : ConnectionState
}

enum class MachineState {
    UNKNOWN, OFFLINE, IDLE, BUSY,
    PREHEATING, HOMING, LEVELING, PRINTING,
    PAUSING, PAUSED, RESUMING, STOPPING, STOPPED, COMPLETED,
    LOADING, UNLOADING, ERROR;

    /** States in which a print job exists and pause/resume/stop make sense. */
    val isJobActive: Boolean
        get() = this in setOf(PREHEATING, HOMING, LEVELING, PRINTING, PAUSING, PAUSED, RESUMING, STOPPING)
}

data class PrintJob(
    val fileName: String,
    val progress: Int,
    val currentLayer: Int,
    val totalLayers: Int,
    val elapsedSec: Long,
    val remainingSec: Long,
    val taskId: String?,
)

data class Temperature(val current: Double, val target: Double)

enum class Fan { MODEL, AUX, CHAMBER }

enum class SpeedMode { SILENT, BALANCED, SPORT, LUDICROUS }

enum class PausedReason { USER, FILAMENT_RUNOUT, OTHER }

data class Position(val x: Double, val y: Double, val z: Double)

/** [helpUrl] points at the vendor troubleshooting page for this code, when known. */
data class PrinterException(val code: Int, val message: String, val helpUrl: String? = null)

data class FilamentSystem(val hubs: List<FilamentHub>, val external: FilamentSlot?, val autoRefill: Boolean)

data class FilamentHub(val id: Int, val humidity: Double?, val slots: List<FilamentSlot>)

data class FilamentSlot(
    val hubId: Int,
    val trayId: Int,
    val material: String,
    val name: String,
    val brand: String,
    /** `#RRGGBB`, or null when unknown. */
    val color: String?,
    val present: Boolean,
    val active: Boolean,
    val minNozzleTemp: Int,
    val maxNozzleTemp: Int,
)
