package dev.openlattice.app.monitor

import dev.openlattice.core.MachineState
import dev.openlattice.core.PausedReason
import dev.openlattice.core.PrintJob
import dev.openlattice.core.PrinterException
import dev.openlattice.core.PrinterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrintAlertsTest {
    private val job = PrintJob("part.gcode", 50, 10, 100, 600, 600, "t1")
    private fun s(m: MachineState, reason: PausedReason? = null, ex: List<PrinterException> = emptyList(), withJob: Boolean = true) =
        PrinterState(machine = m, job = if (withJob) job else null, pausedReason = reason, exceptions = ex)

    @Test fun completed() = assertEquals("P: print finished", alertFor("P", s(MachineState.PRINTING), s(MachineState.COMPLETED))?.title)
    @Test fun completedStraightToIdle() = assertEquals("P: print finished", alertFor("P", s(MachineState.PRINTING), s(MachineState.IDLE, withJob = false))?.title)
    @Test fun completedThenIdleIsSilent() = assertNull(alertFor("P", s(MachineState.COMPLETED), s(MachineState.IDLE, withJob = false)))
    @Test fun stopped() = assertEquals("P: print stopped", alertFor("P", s(MachineState.STOPPING), s(MachineState.STOPPED))?.title)
    @Test fun stoppingToIdleIsStopNotFinish() = assertEquals("P: print stopped", alertFor("P", s(MachineState.STOPPING), s(MachineState.IDLE, withJob = false))?.title)
    @Test fun runout() = assertEquals("P: filament ran out", alertFor("P", s(MachineState.PRINTING), s(MachineState.PAUSED, PausedReason.FILAMENT_RUNOUT))?.title)
    @Test fun userPauseIsSilent() = assertNull(alertFor("P", s(MachineState.PAUSING), s(MachineState.PAUSED, PausedReason.USER)))
    @Test fun progressIsSilent() = assertNull(alertFor("P", s(MachineState.PRINTING), s(MachineState.PRINTING)))

    @Test fun newExceptionAlertsOnce() {
        val ex = listOf(PrinterException(1302, "Foreign object detected on the bed"))
        assertEquals("P: Foreign object detected on the bed", alertFor("P", s(MachineState.PRINTING), s(MachineState.PRINTING, ex = ex))?.title)
        assertNull(alertFor("P", s(MachineState.PRINTING, ex = ex), s(MachineState.PRINTING, ex = ex)))
    }
}
