package dev.openlattice.app.monitor

import android.content.Context
import dev.openlattice.core.MachineState
import dev.openlattice.core.PausedReason
import dev.openlattice.core.PrintJob
import dev.openlattice.core.PrinterException
import dev.openlattice.core.PrinterState

/**
 * The last printer state the monitor or the periodic check alerted on, so a transition
 * is alerted once even when the service stops and the periodic check takes over.
 */
class LastSeen(context: Context) {
    private val prefs = context.getSharedPreferences("monitor_last_seen", Context.MODE_PRIVATE)

    fun get(printerId: String): PrinterState? {
        val raw = prefs.getString(printerId, null) ?: return null
        val parts = raw.split('\u0001')
        if (parts.size < 4) return null
        val machine = runCatching { MachineState.valueOf(parts[0]) }.getOrNull() ?: return null
        val file = parts[1]
        return PrinterState(
            machine = machine,
            job = file.takeIf { it.isNotEmpty() }?.let { PrintJob(it, 0, 0, 0, 0, 0, null) },
            pausedReason = parts[2].takeIf { it.isNotEmpty() }?.let { runCatching { PausedReason.valueOf(it) }.getOrNull() },
            exceptions = parts[3].split(',').mapNotNull { it.toIntOrNull() }.map { PrinterException(it, "") },
        )
    }

    fun put(printerId: String, s: PrinterState) {
        val value = listOf(
            s.machine.name,
            s.job?.fileName.orEmpty(),
            s.pausedReason?.name.orEmpty(),
            s.exceptions.joinToString(",") { it.code.toString() },
        ).joinToString("\u0001")
        prefs.edit().putString(printerId, value).apply()
    }

    fun remove(printerId: String) = prefs.edit().remove(printerId).apply()
}
