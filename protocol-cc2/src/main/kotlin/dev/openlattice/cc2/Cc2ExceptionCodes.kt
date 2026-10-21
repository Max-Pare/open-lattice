package dev.openlattice.cc2

/**
 * Printer-side exception codes (status `exception.exception_code` / `machine_status.exception_status`)
 * with ELEGOO's wording, from the Centauri Carbon 2 pages of the ELEGOO wiki
 * (https://wiki.elegoo.com/en/centauri-carbon-2-combo/errorcode-<code>), October 2026.
 */
object Cc2ExceptionCodes {
    private const val WIKI = "https://wiki.elegoo.com/en/centauri-carbon-2-combo/errorcode-"

    data class Info(val message: String, val wikiSlug: String?) {
        val helpUrl: String? get() = wikiSlug?.let { WIKI + it }
    }

    private val CODES: Map<Int, Info> = mapOf(
        101 to Info("Heated bed error. Check the heated bed and its wiring (power off first)", "101"),
        102 to Info("Heated bed temperature sensor disconnected", "102"),
        103 to Info("Print head heating error. Check the print head and its wiring (power off first)", "103"),
        104 to Info("Nozzle temperature sensor disconnected", "104"),
        109 to Info("Filament runout detected", null),
        205 to Info("Chamber temperature sensor disconnected", "205-206"),
        206 to Info("Chamber temperature sensor shorted", "205-206"),
        304 to Info("Z-axis motor error. Check the motor and its wiring (power off first)", "304"),
        605 to Info("Pressure sensor data error", "605"),
        701 to Info("Mainboard fan error. Check the fan and its wiring", "701"),
        702 to Info("Heatbreak fan error. Check the fan and its wiring", "702"),
        703 to Info("Model fan error. Check the fan and its wiring", "703"),
        704 to Info("Leveling failed. Please try again", "704"),
        705 to Info("Auxiliary fan error", "705"),
        706 to Info("Case fan error. Check the fan and its wiring", "706"),
        707 to Info("Tool head front cover detached. Check if it is loose", "707"),
        801 to Info("Extruder and control board communication error. Check the tool head cable (power off first)", "801"),
        802 to Info("Leveling sensor communication error", "802"),
        1101 to Info("Exhaust vent failed to open. Check if it is blocked", "1101"),
        1102 to Info("Exhaust vent failed to close. Check if it is jammed", "1102"),
        1211 to Info("CANVAS filament ran out. Insert filament into the same slot and tap Continue on the printer", "1211"),
        1220 to Info("Abnormal filament detected in the extruder. Unload it manually", "1220"),
        1231 to Info("Filament cut failed. Check whether the cutter block has been removed", "1231"),
        1241 to Info("Loading error. Check that the PTFE tube is seated", "1241"),
        1242 to Info("Filament did not reach the tool head from CANVAS. It may have slipped or jammed inside CANVAS", "1242"),
        1243 to Info("Filament extrusion failed at the tool head. Check if the tool head is clogged", "1243"),
        1251 to Info("Filament unload failed at the tool head. It may have slipped or jammed inside CANVAS", "1251"),
        1252 to Info("Filament unload failed at the tool head. The nozzle may be clogged or filament stuck in the extruder", "1252"),
        1263 to Info("Filament extrusion failed at the tool head. The filament may be tangled or the spool jammed", "1263"),
        1264 to Info("Filament extrusion failed at the tool head. The filament may be tangled, the spool jammed, or the nozzle clogged", "1263"),
        1265 to Info("Filament detection failed at the tool head. It may be jammed; try removing it manually", "1265"),
        // Reported by elegoo-cc2-webui (MIT) for the camera-based detections.
        1301 to Info("Print failure (spaghetti) detected", null),
        1302 to Info("Foreign object detected on the bed", null),
        2002 to Info("Cloud access failed (expected in LAN-only mode)", "2003"),
        2003 to Info("Failed to retrieve initialization data from the cloud (expected in LAN-only mode)", "2003"),
    )

    fun info(code: Int): Info = CODES[code] ?: Info("Printer error $code", null)
}
