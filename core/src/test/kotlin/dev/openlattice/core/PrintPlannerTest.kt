package dev.openlattice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintPlannerTest {
    private fun tray(hub: Int, tray: Int, material: String, color: String?, present: Boolean = true) = FilamentSlot(
        hubId = hub, trayId = tray, material = material, name = material, brand = "Generic", color = color,
        present = present, active = false, minNozzleTemp = 190, maxNozzleTemp = 230,
    )

    private fun file(vararg colors: FileColor) =
        PrinterFile("part.gcode", 1000, null, 600, 100, 5.0, colors.toList(), isFolder = false)

    private fun state(
        hubs: List<FilamentSlot>? = emptyList(),
        external: FilamentSlot? = null,
        machine: MachineState = MachineState.IDLE,
        connection: ConnectionState = ConnectionState.Connected,
    ) = PrinterState(
        connection = connection,
        machine = machine,
        filament = hubs?.let { FilamentSystem(if (it.isEmpty()) emptyList() else listOf(FilamentHub(0, null, it)), external, autoRefill = false) },
    )

    private val prefs = PrintPreferences(plate = BuildPlate.SMOOTH, autoBedLeveling = true, timelapse = true)

    @Test
    fun `matching trays and idle printer is ready and carries the remembered settings`() {
        val trays = listOf(tray(0, 0, "PLA", "#000000"), tray(0, 1, "PLA", "#FFFFFF"), tray(0, 2, "PETG", "#FF0000"))
        val plan = PrintPlanner.plan(
            file(FileColor(0, "#FFFFFF", "PLA"), FileColor(1, "#FF0000", "PETG")),
            Storage.LOCAL, state(trays), prefs,
        )
        assertTrue(plan.issues.toString(), plan.ready)
        assertEquals(listOf(SlotMapping(0, 0, 1), SlotMapping(1, 0, 2)), plan.command.slotMap)
        assertEquals(BuildPlate.SMOOTH, plan.command.plate)
        assertTrue(plan.command.autoBedLeveling)
        assertTrue(plan.command.timelapse)
    }

    @Test
    fun `material wins over colour`() {
        val trays = listOf(tray(0, 0, "PETG", "#FFFFFF"), tray(0, 1, "PLA", "#F0F0F0"))
        val m = PrintPlanner.matchSlots(listOf(FileColor(0, "#FFFFFF", "pla")), trays)
        assertEquals(1, m[0]!!.trayId)
    }

    @Test
    fun `each tray is used once while trays remain`() {
        val trays = listOf(tray(0, 0, "PLA", "#FFFFFF"), tray(0, 1, "PLA", "#EEEEEE"))
        val m = PrintPlanner.matchSlots(listOf(FileColor(0, "#FFFFFF", "PLA"), FileColor(1, "#FFFFFF", "PLA")), trays)
        assertEquals(setOf(0, 1), m.values.map { it.trayId }.toSet())
    }

    @Test
    fun `more tools than trays share the best tray`() {
        val trays = listOf(tray(0, 0, "PLA", "#FFFFFF"))
        val m = PrintPlanner.matchSlots(listOf(FileColor(0, "#FFFFFF", "PLA"), FileColor(1, "#FFFFFF", "PLA")), trays)
        assertEquals(0, m[0]!!.trayId)
        assertEquals(0, m[1]!!.trayId)
    }

    @Test
    fun `empty trays are never chosen`() {
        val trays = listOf(tray(0, 0, "PLA", "#FFFFFF", present = false), tray(0, 1, "PETG", "#000000"))
        val plan = PrintPlanner.plan(file(FileColor(0, "#FFFFFF", "PLA")), Storage.LOCAL, state(trays), prefs)
        assertEquals(1, plan.slots[0]!!.trayId)
        assertTrue(plan.issues.single() is PrintIssue.MaterialDiffers)
        assertFalse(plan.blocked)
    }

    @Test
    fun `different colour asks for review`() {
        val trays = listOf(tray(0, 0, "PLA", "#0000FF"))
        val plan = PrintPlanner.plan(file(FileColor(0, "#FFFFFF", "PLA")), Storage.LOCAL, state(trays), prefs)
        assertTrue(plan.issues.single() is PrintIssue.ColorDiffers)
        assertFalse(plan.ready)
        assertFalse(plan.blocked)
    }

    @Test
    fun `user choice overrides the automatic tray`() {
        val trays = listOf(tray(0, 0, "PLA", "#FFFFFF"), tray(0, 1, "PLA", "#000000"))
        val plan = PrintPlanner.plan(
            file(FileColor(0, "#FFFFFF", "PLA")), Storage.LOCAL, state(trays), prefs, chosen = mapOf(0 to trays[1]),
        )
        assertEquals(listOf(SlotMapping(0, 0, 1)), plan.command.slotMap)
        assertTrue(plan.issues.single() is PrintIssue.ColorDiffers)
    }

    @Test
    fun `busy or disconnected printer blocks`() {
        val f = file(FileColor(0, "#FFFFFF", "PLA"))
        val ext = tray(-1, -1, "PLA", "#FFFFFF")
        assertTrue(PrintPlanner.plan(f, Storage.LOCAL, state(external = ext, machine = MachineState.PRINTING), prefs).blocked)
        assertTrue(
            PrintPlanner.plan(f, Storage.LOCAL, state(external = ext, connection = ConnectionState.Connecting), prefs).issues
                .contains(PrintIssue.NotConnected),
        )
    }

    @Test
    fun `external spool prints without a slot map and checks the material`() {
        val f = file(FileColor(0, "#FFFFFF", "PLA"))
        val ok = PrintPlanner.plan(f, Storage.USB, state(external = tray(-1, -1, "PLA", "#000000")), prefs)
        assertTrue(ok.issues.toString(), ok.ready)
        assertTrue(ok.command.slotMap.isEmpty())
        assertEquals(Storage.USB, ok.command.storage)

        val wrong = PrintPlanner.plan(f, Storage.LOCAL, state(external = tray(-1, -1, "PETG", "#FFFFFF")), prefs)
        assertTrue(wrong.issues.single() is PrintIssue.ExternalMaterialDiffers)
    }

    @Test
    fun `multi-colour file without CANVAS asks for review`() {
        val f = file(FileColor(0, "#FFFFFF", "PLA"), FileColor(1, "#000000", "PLA"))
        val plan = PrintPlanner.plan(f, Storage.LOCAL, state(external = tray(-1, -1, "PLA", "#FFFFFF")), prefs)
        assertTrue(plan.issues.single() is PrintIssue.NeedsCanvas)
    }

    @Test
    fun `unknown filament status asks for review instead of guessing`() {
        val plan = PrintPlanner.plan(file(FileColor(0, "#FFFFFF", "PLA")), Storage.LOCAL, state(hubs = null), prefs)
        assertEquals(listOf(PrintIssue.FilamentUnknown), plan.issues)
    }

    @Test
    fun `file without colour info needs no filament checks on the external spool`() {
        assertTrue(PrintPlanner.plan(file(), Storage.LOCAL, state(hubs = null), prefs).ready)
        assertTrue(PrintPlanner.plan(file(), Storage.LOCAL, state(external = tray(-1, -1, "PLA", "#FFFFFF")), prefs).ready)
    }

    @Test
    fun `file without colour info on a loaded CANVAS asks for review`() {
        val plan = PrintPlanner.plan(file(), Storage.LOCAL, state(listOf(tray(0, 0, "PLA", "#FFFFFF"))), prefs)
        assertEquals(listOf(PrintIssue.FileFilamentUnknown), plan.issues)
        assertTrue(plan.command.slotMap.isEmpty())
    }

    @Test
    fun `colour tolerance`() {
        assertTrue(PrintPlanner.sameColor("#FFFFFF", "#F5F5F5"))
        assertFalse(PrintPlanner.sameColor("#FFFFFF", "#DDDDDD"))
        assertFalse(PrintPlanner.sameColor("#FF0000", "#FF8000"))
        assertFalse(PrintPlanner.sameColor("#FFFFFF", null))
        assertEquals(0.0, PrintPlanner.colorDistance("#123456", "123456")!!, 0.0)
    }
}
