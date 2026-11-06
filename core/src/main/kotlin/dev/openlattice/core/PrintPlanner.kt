package dev.openlattice.core

import kotlin.math.sqrt

/** Start-print settings remembered per printer, so the next print can start with one tap. */
data class PrintPreferences(
    val plate: BuildPlate = BuildPlate.TEXTURED,
    val autoBedLeveling: Boolean = false,
    val timelapse: Boolean = false,
)

/** Something the user should see before a print starts. Tool numbers are shown 1-based, as on the printer. */
sealed interface PrintIssue {
    /** True when the print cannot start at all; false when it only needs the user to confirm. */
    val blocking: Boolean
    val message: String

    data object NotConnected : PrintIssue {
        override val blocking = true
        override val message = "The printer is not connected"
    }

    data object Busy : PrintIssue {
        override val blocking = true
        override val message = "A print is running; start this one when it finishes"
    }

    data object PrinterError : PrintIssue {
        override val blocking = false
        override val message = "The printer is reporting an error"
    }

    data object FilamentUnknown : PrintIssue {
        override val blocking = false
        override val message = "Filament status has not loaded yet"
    }

    data object FileFilamentUnknown : PrintIssue {
        override val blocking = false
        override val message = "The file does not say which filament it needs; it will print from the active tray"
    }

    data class NoTray(val tool: Int, val material: String) : PrintIssue {
        override val blocking = true
        override val message get() = "No loaded tray for T${tool + 1} ${material.ifBlank { "filament" }}"
    }

    data class MaterialDiffers(val tool: Int, val wanted: String, val slot: FilamentSlot) : PrintIssue {
        override val blocking = false
        override val message get() = "T${tool + 1} needs $wanted; ${slot.label()} holds ${slot.material.ifBlank { "unknown filament" }}"
    }

    data class ColorDiffers(val tool: Int, val wanted: String, val slot: FilamentSlot) : PrintIssue {
        override val blocking = false
        override val message get() = "T${tool + 1} colour differs from ${slot.label()}"
    }

    data class ExternalMaterialDiffers(val wanted: String, val loaded: String) : PrintIssue {
        override val blocking = false
        override val message get() = "The file needs $wanted; the external spool holds $loaded"
    }

    data class NeedsCanvas(val tools: Int) : PrintIssue {
        override val blocking = false
        override val message get() = "The file uses $tools filaments but only the external spool is available"
    }
}

data class PrintPlan(
    val command: PrinterCommand.StartPrint,
    /** Tool -> CANVAS tray it will print from. Empty when printing from the external spool. */
    val slots: Map<Int, FilamentSlot>,
    val issues: List<PrintIssue>,
) {
    /** Nothing to show the user: a one-tap print may start straight away. */
    val ready: Boolean get() = issues.isEmpty()
    /** The print cannot start, whatever the user confirms. */
    val blocked: Boolean get() = issues.any { it.blocking }
}

/**
 * Turns "print this file" into a start command: remembered settings, CANVAS trays matched to the
 * file's filaments by material and colour, and the reasons (if any) the user should look first.
 */
object PrintPlanner {
    /**
     * Colours closer than this (redmean distance, 0..~765) count as the same filament colour.
     * White vs #DDDDDD is just over it; white vs #F5F5F5 is well under.
     */
    const val COLOR_TOLERANCE = 100.0

    /**
     * [chosen] overrides the automatic tray for a tool (the user picked one); tools left out
     * are matched automatically.
     */
    fun plan(
        file: PrinterFile,
        storage: Storage,
        state: PrinterState,
        prefs: PrintPreferences,
        chosen: Map<Int, FilamentSlot> = emptyMap(),
    ): PrintPlan {
        val issues = mutableListOf<PrintIssue>()
        if (state.connection != ConnectionState.Connected) issues += PrintIssue.NotConnected
        if (state.machine.isJobActive) issues += PrintIssue.Busy
        if (state.machine == MachineState.ERROR || state.exceptions.isNotEmpty()) issues += PrintIssue.PrinterError

        val tools = file.colors.distinctBy { it.tool }.sortedBy { it.tool }
        val trays = state.filament?.hubs.orEmpty().flatMap { it.slots }.filter { it.present }
        var slots: Map<Int, FilamentSlot> = emptyMap()

        when {
            tools.isEmpty() -> if (trays.isNotEmpty()) issues += PrintIssue.FileFilamentUnknown
            state.filament == null -> issues += PrintIssue.FilamentUnknown
            trays.isNotEmpty() -> {
                val auto = matchSlots(tools.filterNot { it.tool in chosen }, trays)
                slots = (auto + chosen.filterKeys { k -> tools.any { it.tool == k } }).toSortedMap()
                for (t in tools) {
                    val s = slots[t.tool]
                    when {
                        s == null -> issues += PrintIssue.NoTray(t.tool, t.material)
                        !sameMaterial(t.material, s.material) -> issues += PrintIssue.MaterialDiffers(t.tool, t.material, s)
                        !sameColor(t.color, s.color) -> issues += PrintIssue.ColorDiffers(t.tool, t.color, s)
                    }
                }
            }
            else -> {
                if (tools.size > 1) issues += PrintIssue.NeedsCanvas(tools.size)
                val ext = state.filament.external
                val wanted = tools.first().material
                if (ext != null && ext.material.isNotBlank() && wanted.isNotBlank() && !sameMaterial(wanted, ext.material)) {
                    issues += PrintIssue.ExternalMaterialDiffers(wanted, ext.material)
                }
            }
        }

        val command = PrinterCommand.StartPrint(
            fileName = file.name,
            storage = storage,
            autoBedLeveling = prefs.autoBedLeveling,
            plate = prefs.plate,
            timelapse = prefs.timelapse,
            slotMap = slots.map { (tool, s) -> SlotMapping(tool, s.hubId, s.trayId) },
        )
        return PrintPlan(command, slots, issues)
    }

    /**
     * Gives each tool the loaded tray that fits best: same material first, then nearest colour.
     * Trays are handed out once each while any are left; with more tools than trays, the
     * remaining tools share their best tray.
     */
    fun matchSlots(colors: List<FileColor>, trays: List<FilamentSlot>): Map<Int, FilamentSlot> {
        val loaded = trays.filter { it.present }
        if (loaded.isEmpty()) return emptyMap()
        val tools = colors.distinctBy { it.tool }
        val pairs = tools.flatMap { t -> loaded.map { s -> Triple(t, s, cost(t, s)) } }.sortedBy { it.third }
        val out = mutableMapOf<Int, FilamentSlot>()
        val used = mutableSetOf<FilamentSlot>()
        for ((t, s, _) in pairs) {
            if (t.tool in out || s in used) continue
            out[t.tool] = s
            used += s
        }
        for ((t, s, _) in pairs) if (t.tool !in out) out[t.tool] = s
        return out
    }

    private fun cost(t: FileColor, s: FilamentSlot): Double {
        val material = if (sameMaterial(t.material, s.material)) 0.0 else 10_000.0
        return material + (colorDistance(t.color, s.color) ?: 1_000.0)
    }

    fun sameMaterial(a: String, b: String): Boolean = norm(a) == norm(b)

    private fun norm(m: String) = m.trim().uppercase().replace(" ", "")

    fun sameColor(a: String?, b: String?): Boolean = (colorDistance(a, b) ?: Double.MAX_VALUE) < COLOR_TOLERANCE

    /** Redmean colour distance between two `#RRGGBB` colours, or null if either is missing or malformed. */
    fun colorDistance(a: String?, b: String?): Double? {
        val x = rgb(a) ?: return null
        val y = rgb(b) ?: return null
        val rMean = (x[0] + y[0]) / 2.0
        val dr = (x[0] - y[0]).toDouble()
        val dg = (x[1] - y[1]).toDouble()
        val db = (x[2] - y[2]).toDouble()
        return sqrt((2 + rMean / 256) * dr * dr + 4 * dg * dg + (2 + (255 - rMean) / 256) * db * db)
    }

    private fun rgb(hex: String?): IntArray? {
        val h = hex?.trim()?.removePrefix("#") ?: return null
        if (h.length < 6) return null
        val v = h.take(6).toIntOrNull(16) ?: return null
        return intArrayOf(v shr 16 and 0xFF, v shr 8 and 0xFF, v and 0xFF)
    }
}

/** "Tray 1-2" for CANVAS trays (1-based, as the printer shows them), "the external spool" otherwise. */
fun FilamentSlot.label(): String = if (hubId < 0) "the external spool" else "tray ${hubId + 1}-${trayId + 1}"
