package dev.openlattice.app.monitor

import dev.openlattice.core.MachineState
import dev.openlattice.core.PausedReason
import dev.openlattice.core.PrinterState

data class PrintAlert(val title: String, val text: String)

/** The alert to raise when a printer goes from [before] to [now], or null if none. */
fun alertFor(printerName: String, before: PrinterState, now: PrinterState): PrintAlert? {
    val file = before.job?.fileName ?: now.job?.fileName ?: ""
    val newExceptions = now.exceptions.filter { e -> before.exceptions.none { it.code == e.code } }
    if (newExceptions.isNotEmpty()) return PrintAlert("$printerName: ${newExceptions.first().message}", file)
    if (before.machine == now.machine) return null
    return when {
        now.machine == MachineState.COMPLETED ||
            (before.machine.isJobActive && before.machine != MachineState.STOPPING && now.machine == MachineState.IDLE) ->
            PrintAlert("$printerName: print finished", file)
        now.machine == MachineState.STOPPED || (before.machine == MachineState.STOPPING && !now.machine.isJobActive) ->
            PrintAlert("$printerName: print stopped", file)
        now.machine == MachineState.ERROR -> PrintAlert("$printerName: printer error", file)
        now.machine == MachineState.PAUSED && now.pausedReason == PausedReason.FILAMENT_RUNOUT ->
            PrintAlert("$printerName: filament ran out", "Print paused. Load filament and resume.")
        now.machine == MachineState.PAUSED && now.pausedReason == PausedReason.OTHER ->
            PrintAlert("$printerName: print paused by the printer", file)
        else -> null
    }
}
