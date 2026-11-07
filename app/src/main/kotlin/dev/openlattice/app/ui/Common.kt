package dev.openlattice.app.ui

import android.content.Context
import dev.openlattice.app.OpenLatticeApp
import dev.openlattice.core.MachineState

val Context.app: OpenLatticeApp get() = applicationContext as OpenLatticeApp

fun formatDuration(totalSec: Long): String {
    if (totalSec <= 0) return "0m"
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

fun MachineState.label(): String = when (this) {
    MachineState.UNKNOWN -> "Unknown"
    MachineState.OFFLINE -> "Offline"
    MachineState.IDLE -> "Idle"
    MachineState.BUSY -> "Busy"
    MachineState.PREHEATING -> "Preheating"
    MachineState.HOMING -> "Homing"
    MachineState.LEVELING -> "Leveling"
    MachineState.PRINTING -> "Printing"
    MachineState.PAUSING -> "Pausing"
    MachineState.PAUSED -> "Paused"
    MachineState.RESUMING -> "Resuming"
    MachineState.STOPPING -> "Stopping"
    MachineState.STOPPED -> "Stopped"
    MachineState.COMPLETED -> "Completed"
    MachineState.LOADING -> "Loading filament"
    MachineState.UNLOADING -> "Unloading filament"
    MachineState.ERROR -> "Error"
}

/** A print file's name as people read it: without the `.gcode` extension. */
fun displayName(fileName: String): String = fileName.removeSuffix(".gcode").removeSuffix(".GCODE")
