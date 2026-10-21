package dev.openlattice.cc2

import dev.openlattice.core.ConnectionState
import dev.openlattice.core.Fan
import dev.openlattice.core.FilamentMaterial
import dev.openlattice.core.MachineState
import dev.openlattice.core.PrinterCommand
import dev.openlattice.core.PrinterState
import dev.openlattice.core.SpeedMode
import dev.openlattice.core.Storage
import dev.openlattice.core.UploadSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Reversible control checks against a real, idle CC2. Runs only with OPEN_LATTICE_CC2_CONTROL=1
 * (plus host/code). Every step restores what it changed; nothing prints, extrudes, homes Z, or
 * moves Z towards the nozzle.
 */
class Cc2LiveControlTest {
    private val host = System.getenv("OPEN_LATTICE_CC2_HOST").orEmpty()
    private val code = System.getenv("OPEN_LATTICE_CC2_CODE").orEmpty()
    private val enabled = System.getenv("OPEN_LATTICE_CC2_CONTROL") == "1"

    private val results = mutableListOf<String>()
    private fun report(step: String, ok: Boolean, detail: String = "") {
        val line = "${if (ok) "PASS" else "FAIL"}  $step${if (detail.isNotEmpty()) " — $detail" else ""}"
        results += line
        println(line)
    }

    private suspend fun Cc2Driver.await(timeoutMs: Long = 15_000, cond: (PrinterState) -> Boolean): PrinterState? =
        withTimeoutOrNull(timeoutMs) { state.first(cond) }

    @Test
    fun reversibleControls() = runBlocking {
        assumeTrue("control test not enabled", enabled && host.isNotEmpty() && code.isNotEmpty())
        val serial = Cc2Discovery.probe(listOf(java.net.InetAddress.getByName(host))).single().serial
        val d = Cc2Driver(host, serial, code)
        try {
            d.connect()
            val start = d.await { it.connection == ConnectionState.Connected && it.nozzle != null }!!
            println("start: machine=${start.machine} pos=${start.position} fans=${start.fans} speed=${start.speedMode} light=${start.lightOn}")
            check(start.machine == MachineState.IDLE) { "Printer is not idle (${start.machine}); aborting" }
            val info = withTimeoutOrNull(5_000) { d.info.filterNotNull().first() }!!

            // 1. Upload a comments-only file (3 chunks), confirm it is listed, delete it.
            val name = "open-lattice-upload-test.gcode"
            val body = buildString {
                append("; open-lattice upload test. Comments only; never print.\n")
                while (length < 2_500_000) append("; padding line for chunked upload test ..........................................\n")
            }.toByteArray()
            val source = object : UploadSource {
                override val name = name
                override val sizeBytes = body.size.toLong()
                override fun open() = ByteArrayInputStream(body)
            }
            runCatching {
                val progress = d.upload(source).toList()
                val listed = d.listFiles(Storage.LOCAL).firstOrNull { it.name == name }
                report("upload ${body.size} B in ${progress.size - 1} chunks", listed != null && listed.sizeBytes == body.size.toLong(), "listed size=${listed?.sizeBytes}")
                d.deleteFiles(Storage.LOCAL, listOf(name))
                delay(1_000)
                report("delete uploaded file", d.listFiles(Storage.LOCAL).none { it.name == name })
            }.onFailure { report("upload/delete", false, it.toString()) }
            d.await(30_000) { it.machine == MachineState.IDLE }

            // 2. Rename and restore.
            val raw = Cc2Client(host, serial, code, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO))
            raw.connect()
            suspend fun hostname() = (raw.request(Cc2Method.GET_ATTRIBUTES) / "hostname").str()
            runCatching {
                d.send(PrinterCommand.Rename("${info.name}-ol"))
                delay(1_500)
                val renamed = hostname()
                d.send(PrinterCommand.Rename(info.name))
                delay(1_500)
                val restored = hostname()
                report("rename (hostname)", renamed == "${info.name}-ol" && restored == info.name, "renamed=$renamed restored=$restored")
            }.onFailure { report("rename", false, it.toString()); runCatching { d.send(PrinterCommand.Rename(info.name)) } }
            raw.close()

            // 3. Temperatures: heat a little, then off.
            runCatching {
                d.send(PrinterCommand.SetNozzleTarget(50))
                d.send(PrinterCommand.SetBedTarget(40))
                val s = d.await { it.nozzle?.target == 50.0 && it.bed?.target == 40.0 }
                report("set nozzle 50 / bed 40", s != null, "targets=${d.state.value.nozzle?.target}/${d.state.value.bed?.target}")
            }.onFailure { report("set temperatures", false, it.toString()) }
            runCatching { d.send(PrinterCommand.SetNozzleTarget(0)); d.send(PrinterCommand.SetBedTarget(0)) }
            report("temperatures off", d.await { it.nozzle?.target == 0.0 && it.bed?.target == 0.0 } != null)

            // 4. Fans and speed mode, restored to their starting values.
            runCatching {
                d.send(PrinterCommand.SetFan(Fan.MODEL, 50))
                report("part fan 50%", d.await { (it.fans[Fan.MODEL] ?: -1) in 48..52 } != null, "now=${d.state.value.fans[Fan.MODEL]}")
                d.send(PrinterCommand.SetFan(Fan.AUX, 100))
                report("aux fan 100%", d.await { it.fans[Fan.AUX] == 100 } != null, "now=${d.state.value.fans[Fan.AUX]}")
            }.onFailure { report("fans", false, it.toString()) }
            runCatching {
                d.send(PrinterCommand.SetFan(Fan.MODEL, start.fans[Fan.MODEL] ?: 0))
                d.send(PrinterCommand.SetFan(Fan.AUX, start.fans[Fan.AUX] ?: 0))
            }
            // Speed mode is print-only: the printer answers 1010 when idle.
            val speed = runCatching { d.send(PrinterCommand.SetSpeedMode(SpeedMode.SPORT)) }
            report("speed mode refused while idle (1010)", (speed.exceptionOrNull() as? dev.openlattice.core.PrinterCommandException)?.code == 1010)
            if (speed.isSuccess) runCatching { d.send(PrinterCommand.SetSpeedMode(start.speedMode ?: SpeedMode.BALANCED)) }

            // 5. Home (the CC2 always homes XYZ and probes with the nozzle at 140 °C), then a small X move.
            runCatching {
                d.send(PrinterCommand.Home())
                val homed = d.await(180_000) { it.homedAxes.contains('z') && it.machine == MachineState.IDLE }
                report("home", homed != null, "homed=${d.state.value.homedAxes} pos=${d.state.value.position}")
                report("nozzle switched off after homing", d.await(20_000) { it.nozzle?.target == 0.0 } != null, "target=${d.state.value.nozzle?.target}")
                val x0 = d.state.value.position!!.x
                d.send(PrinterCommand.Jog('x', if (x0 > 100) -10.0 else 10.0))
                val moved = d.await(20_000) { kotlin.math.abs((it.position?.x ?: x0) - x0) in 9.0..11.0 }
                report("jog X 10 mm", moved != null, "x: $x0 -> ${d.state.value.position?.x}")
            }.onFailure { report("home/jog XY", false, it.toString()) }

            // 6. Z +1 mm widens the nozzle-bed gap (bed down); always safe.
            val z0 = d.state.value.position?.z ?: 0.0
            runCatching {
                d.send(PrinterCommand.Jog('z', 1.0))
                val s = d.await(20_000) { (it.position?.z ?: z0) > z0 + 0.5 }
                report("jog Z +1 mm moves bed down", s != null, "z: $z0 -> ${d.state.value.position?.z}")
            }.onFailure { report("jog Z", false, it.toString()) }
            runCatching { d.send(PrinterCommand.SetNozzleTarget(0)); d.send(PrinterCommand.SetBedTarget(0)) }

            // 7. Rewrite the external spool info with the values it already has.
            runCatching {
                val ext = d.state.value.filament?.external
                val mat = ext?.let { FilamentMaterial.fromLabel(it.material) }
                if (ext == null || mat == null || ext.color == null) {
                    report("spool info skipped (unknown current values: $ext)", true)
                } else {
                    d.send(PrinterCommand.SetFilament(-1, 0, mat, ext.color!!, ext.brand))
                    delay(1_500)
                    val after = d.state.value.filament?.external
                    report("rewrite spool info unchanged", after == ext, "before=$ext after=$after")
                }
            }.onFailure { report("spool info", false, it.toString()) }

            val end = d.state.value
            println("end: machine=${end.machine} pos=${end.position} fans=${end.fans} speed=${end.speedMode} light=${end.lightOn} targets=${end.nozzle?.target}/${end.bed?.target}")
        } finally {
            d.close()
            println("==== SUMMARY ====")
            results.forEach(::println)
        }
    }
}
