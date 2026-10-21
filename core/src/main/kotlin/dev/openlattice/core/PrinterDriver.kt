package dev.openlattice.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream

/** One live connection to one printer. Each protocol (CC2 MQTT, SDCP, ...) implements this. */
interface PrinterDriver : AutoCloseable {
    val info: StateFlow<PrinterInfo?>
    val state: StateFlow<PrinterState>
    val capabilities: Set<Capability>

    suspend fun connect()
    override fun close()

    /** Throws [PrinterCommandException] when the printer rejects the command. */
    suspend fun send(command: PrinterCommand)

    suspend fun listFiles(storage: Storage): List<PrinterFile>
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

enum class Capability { CAMERA, CANVAS, USB_STORAGE, TIMELAPSE, CHAMBER_FAN, AUX_FAN, AI_DETECTION }

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
    data class Jog(val axis: Char, val distanceMm: Double) : PrinterCommand
    data class StartPrint(
        val fileName: String,
        val storage: Storage = Storage.LOCAL,
        val bedLeveling: Boolean = false,
        val timelapse: Boolean = false,
        /** Gcode tool index -> (hub, tray). Empty means single-material / external spool. */
        val slotMap: List<SlotMapping> = emptyList(),
    ) : PrinterCommand
    data class Rename(val name: String) : PrinterCommand
    data object LoadFilament : PrinterCommand
    data object UnloadFilament : PrinterCommand
    data class SetAutoRefill(val enabled: Boolean) : PrinterCommand
    data object Refresh : PrinterCommand
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
