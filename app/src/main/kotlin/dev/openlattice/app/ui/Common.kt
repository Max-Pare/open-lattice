package dev.openlattice.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.openlattice.app.OpenLatticeApp
import dev.openlattice.core.ConnectionState
import dev.openlattice.core.MachineState
import dev.openlattice.core.PrinterState

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

/** One-line status: connection problems first, then machine state. */
fun PrinterState.summary(): String = when (val c = connection) {
    ConnectionState.Disconnected -> "Disconnected"
    ConnectionState.Connecting -> "Connecting…"
    is ConnectionState.Failed -> if (c.authRejected) "Access code rejected" else c.reason
    ConnectionState.Connected -> machine.label()
}

@Composable
fun StatusDot(state: PrinterState) {
    val color = when {
        state.connection is ConnectionState.Failed -> MaterialTheme.colorScheme.error
        state.connection != ConnectionState.Connected -> Color.Gray
        state.machine == MachineState.ERROR || state.exceptions.isNotEmpty() -> MaterialTheme.colorScheme.error
        state.machine.isJobActive -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.tertiary
    }
    Box(Modifier.size(10.dp).background(color, CircleShape))
}

@Composable
fun StatusLine(state: PrinterState, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusDot(state)
        Text(state.summary(), style = MaterialTheme.typography.bodyMedium)
    }
}
