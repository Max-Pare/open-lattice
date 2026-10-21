package dev.openlattice.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream

/** One live connection to one printer. Each protocol (CC2 MQTT, SDCP, ...) implements this. */
interface PrinterDriver : AutoCloseable {
    val info: StateFlow<PrinterInfo?>
    val state: StateFlow<PrinterState>
    val capabilities: Set<Capability>

    /** Connects, or reconnects at once if the session is down. Throws if the attempt fails. */
    suspend fun connect()
    /** Ends the session and stops reconnecting; [connect] can be called again later. */
    suspend fun disconnect()
    /** Ends the session for good. */
    override fun close()

    /** Throws [PrinterCommandException] when the printer rejects the command. */
    suspend fun send(command: PrinterCommand)

    suspend fun listFiles(storage: Storage): List<PrinterFile>
    /** Capacity of the printer's internal storage, or null if unknown. */
    suspend fun storageInfo(): StorageInfo?
    suspend fun deleteFiles(storage: Storage, names: List<String>)
    /** PNG bytes, or null when the file has no thumbnail. */
    suspend fun thumbnail(storage: Storage, name: String): ByteArray?

    suspend fun history(): List<HistoryEntry>
    suspend fun deleteHistory(taskIds: List<String>)

    /** Uploads to the printer's local storage. Emits progress, completes when the printer has the whole file. */
    fun upload(source: UploadSource): Flow<UploadProgress>

    /** MJPEG stream URL, or null when no camera is available. */
    suspend fun cameraUrl(): String?
}

data class PrinterInfo(
    val name: String,
    val model: String,
    val serial: String,
    val firmware: String,
    val host: String,
)

enum class Capability { CAMERA, CANVAS, USB_STORAGE, TIMELAPSE, CHAMBER_FAN, AUX_FAN }

enum class Storage { LOCAL, USB }

sealed interface PrinterCommand {
    data object Pause : PrinterCommand
    data object Resume : PrinterCommand
    data object Stop : PrinterCommand
    data class SetNozzleTarget(val celsius: Int) : PrinterCommand
    data class SetBedTarget(val celsius: Int) : PrinterCommand
    data class SetFan(val fan: Fan, val percent: Int) : PrinterCommand
    data class SetLight(val on: Boolean) : PrinterCommand
    data class SetSpeedMode(val mode: SpeedMode) : PrinterCommand
    /** [axes] is any combination of "x", "y", "z". */
    data class Home(val axes: String = "xyz") : PrinterCommand
    /**
     * Relative move in printer coordinates. For Z, positive widens the nozzle–bed gap
     * (on the CC2 the bed moves down). Moves that would end below Z 0 are refused.
     */
    data class Jog(val axis: Char, val distanceMm: Double) : PrinterCommand
    data class StartPrint(
        val fileName: String,
        val storage: Storage = Storage.LOCAL,
        /** Run auto bed leveling before the print. */
        val autoBedLeveling: Boolean = false,
        val plate: BuildPlate = BuildPlate.TEXTURED,
        val timelapse: Boolean = false,
        /** Gcode tool index -> (hub, tray). Empty means single-material / external spool. */
        val slotMap: List<SlotMapping> = emptyList(),
    ) : PrinterCommand
    data class Rename(val name: String) : PrinterCommand
    /** Load / unload through the external spool path. */
    data object LoadFilament : PrinterCommand
    data object UnloadFilament : PrinterCommand
    /** Load / unload one CANVAS tray. */
    data class LoadTray(val hubId: Int, val trayId: Int) : PrinterCommand
    data class UnloadTray(val hubId: Int, val trayId: Int) : PrinterCommand
    /** Sets what is loaded in a tray; [hubId] -1 means the external spool. */
    data class SetFilament(val hubId: Int, val trayId: Int, val material: FilamentMaterial, val color: String, val brand: String = "Generic") : PrinterCommand
    data class SetAutoRefill(val enabled: Boolean) : PrinterCommand
    data object Refresh : PrinterCommand
}

enum class BuildPlate { TEXTURED, SMOOTH }

data class StorageInfo(val totalBytes: Long, val usedBytes: Long)

/**
 * Filament types the CC2 knows, with its type code and nozzle range.
 * Table from elegoo-cc2-webui (MIT, (c) nyakaspeter), server/src/cc2/cc2Printer.ts.
 */
enum class FilamentMaterial(val label: String, val code: String, val minTemp: Int, val maxTemp: Int) {
    PLA("PLA", "0x0000", 190, 230),
    PLA_PLUS("PLA+", "0x0001", 190, 230),
    PLA_CF("PLA-CF", "0x0004", 210, 240),
    PETG("PETG", "0x0100", 230, 260),
    ABS("ABS", "0x0200", 240, 280),
    TPU("TPU", "0x0301", 220, 240),
    PA_CF("PA-CF", "0x0401", 260, 300),
    PC("PC", "0x0600", 260, 310),
    ASA("ASA", "0x0800", 240, 280),
    HIPS("HIPS", "0x0B00", 220, 260);

    companion object {
        fun fromLabel(label: String): FilamentMaterial? = entries.firstOrNull { it.label.equals(label.trim(), ignoreCase = true) }
    }
}

data class SlotMapping(val tool: Int, val hubId: Int, val trayId: Int)

data class PrinterFile(
    val name: String,
    val sizeBytes: Long,
    val createdAtEpochSec: Long?,
    val printTimeSec: Long?,
    val layers: Int?,
    val filamentUsedGrams: Double?,
    val colors: List<FileColor>,
    val isFolder: Boolean,
)

data class FileColor(val tool: Int, val color: String, val material: String)

data class HistoryEntry(
    val taskId: String,
    val fileName: String,
    val startedAtEpochSec: Long,
    val endedAtEpochSec: Long?,
    val result: HistoryResult,
    val timelapse: Timelapse?,
)

enum class HistoryResult { COMPLETED, STOPPED, IN_PROGRESS, UNKNOWN }

data class Timelapse(val ready: Boolean, val path: String, val sizeBytes: Long?, val durationSec: Int?)

interface UploadSource {
    val name: String
    val sizeBytes: Long
    /** Must return a fresh stream positioned at byte 0 on each call. */
    fun open(): InputStream
}

data class UploadProgress(val sentBytes: Long, val totalBytes: Long) {
    val fraction: Float get() = if (totalBytes == 0L) 1f else sentBytes.toFloat() / totalBytes
}

class PrinterCommandException(val code: Int, message: String) : Exception(message)
