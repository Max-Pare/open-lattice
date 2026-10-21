package dev.openlattice.cc2

import dev.openlattice.core.Fan
import dev.openlattice.core.MachineState
import dev.openlattice.core.SpeedMode
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Cc2StatusMapperTest {

    @Test
    fun idleStatusFromCapture() {
        val s = Cc2StatusMapper.map(fixtureObject("status_idle.json"))
        assertEquals(MachineState.IDLE, s.machine)
        assertNull(s.job)
        assertEquals(25.0, s.nozzle!!.current, 0.0)
        assertEquals(0.0, s.nozzle!!.target, 0.0)
        assertEquals(SpeedMode.BALANCED, s.speedMode)
        assertEquals(false, s.lightOn)
        assertEquals(0, s.fans[Fan.MODEL])
        assertTrue(s.cameraAvailable)
        assertEquals(false, s.usbConnected)
        assertTrue(s.exceptions.isEmpty())
    }

    @Test
    fun jobStartThenDeltasFromCapture() {
        var raw = fixtureObject("status_job_start.json")
        val start = Cc2StatusMapper.map(raw)
        assertEquals(MachineState.PRINTING, start.machine)
        val job = start.job!!
        assertTrue(job.fileName.endsWith(".gcode"))
        assertEquals(20490L, job.remainingSec)

        fixture("status_deltas.jsonl").lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val msg = Cc2Json.parseToJsonElement(line).jsonObject
            raw = raw.mergeDelta((msg / "result").obj()!!)
        }
        val after = Cc2StatusMapper.map(raw)
        // The capture ends with the nozzle and bed heating towards their first-layer targets.
        assertEquals(140.0, after.nozzle!!.target, 0.0)
        assertEquals(60.0, after.bed!!.target, 0.0)
        assertTrue(after.nozzle!!.current > 25.0)
        // The delta set the controller/heater fans; the model fan is untouched and must survive the merge.
        assertEquals(setOf(Fan.MODEL, Fan.AUX, Fan.CHAMBER), after.fans.keys)
        assertNotNull(after.job)
        assertEquals(job.fileName, after.job!!.fileName)
    }

    @Test
    fun deltaMergeReplacesExceptionCodesInsteadOfMerging() {
        val base = Cc2Json.parseToJsonElement("""{"exception":{"exception_code":{"109":1}},"extruder":{"temperature":20,"target":0}}""").jsonObject
        val merged = base.mergeDelta(Cc2Json.parseToJsonElement("""{"exception":{"exception_code":{}},"extruder":{"temperature":21}}""").jsonObject)
        val s = Cc2StatusMapper.map(merged)
        assertTrue(s.exceptions.isEmpty())
        assertEquals(21.0, s.nozzle!!.current, 0.0)
        assertEquals(0.0, s.nozzle!!.target, 0.0)
    }

    @Test
    fun pausedForRunout() {
        assertEquals(MachineState.PAUSED, Cc2StatusMapper.machineState(2, 2505))
        val raw = Cc2Json.parseToJsonElement("""{"machine_status":{"status":2,"sub_status":2505},"print_status":{"filename":"a.gcode"}}""").jsonObject
        assertEquals(dev.openlattice.core.PausedReason.FILAMENT_RUNOUT, Cc2StatusMapper.map(raw).pausedReason)
    }

    @Test
    fun filamentFromCapture() {
        val f = Cc2StatusMapper.mapFilament((fixtureObject("canvas.json") / "canvas_info").obj(), (fixtureObject("mono_filament.json") / "mono_filament_info").obj())!!
        // CANVAS not connected in the capture: only the external spool shows.
        assertTrue(f.hubs.isEmpty())
        assertEquals("PLA", f.external!!.material)
        assertEquals("#000000", f.external!!.color)
    }

    @Test
    fun fanPercentRoundTrip() {
        assertEquals(255, Cc2StatusMapper.percentToFanValue(100))
        assertEquals(0, Cc2StatusMapper.percentToFanValue(0))
        assertEquals(128, Cc2StatusMapper.percentToFanValue(50))
    }
}
