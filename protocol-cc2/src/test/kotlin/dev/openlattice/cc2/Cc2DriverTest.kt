package dev.openlattice.cc2

import dev.openlattice.core.BuildPlate
import dev.openlattice.core.ConnectionState
import dev.openlattice.core.Fan
import dev.openlattice.core.HistoryResult
import dev.openlattice.core.MachineState
import dev.openlattice.core.PrinterCommand
import dev.openlattice.core.PrinterCommandException
import dev.openlattice.core.SlotMapping
import dev.openlattice.core.Storage
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Cc2DriverTest {
    private fun driver(fake: FakePrinter, code: String = fake.accessCode) =
        Cc2Driver("192.168.1.116", fake.serial, code, transportFactory = fake)

    @Test
    fun connectRegistersAndLoadsState() = runBlocking {
        val fake = FakePrinter.withCapturedFixtures()
        driver(fake).use { d ->
            d.connect()
            val register = fake.published.first { it.first.endsWith("/api_register") }
            val body = Cc2Json.parseToJsonElement(register.second).jsonObject
            assertEquals((body / "client_id").str() + "_req", (body / "request_id").str())

            val s = withTimeout(2_000) { d.state.first { it.filament != null } }
            assertEquals(ConnectionState.Connected, s.connection)
            assertEquals(MachineState.IDLE, s.machine)
            assertEquals("PLA", s.filament!!.external!!.material)
            val info = withTimeout(2_000) { d.info.filterNotNull().first() }
            assertEquals("Centauri Carbon 2", info.model)
            assertEquals("02.01.01.07", info.firmware)
        }
    }

    @Test
    fun wrongAccessCodeIsReportedAsAuthFailure() = runBlocking {
        val fake = FakePrinter.withCapturedFixtures()
        driver(fake, code = "nope").use { d ->
            try {
                d.connect()
                fail("expected auth failure")
            } catch (_: MqttAuthException) {
            }
            val conn = d.state.value.connection
            assertTrue(conn is ConnectionState.Failed && conn.authRejected)
        }
    }

    @Test
    fun tooManyClientsIsRejected() = runBlocking {
        val fake = FakePrinter.withCapturedFixtures().apply { registerError = "too many clients" }
        driver(fake).use { d ->
            try {
                d.connect()
                fail("expected register failure")
            } catch (e: Cc2RegisterException) {
                assertTrue(e.message!!.contains("too many"))
            }
        }
    }

    @Test
    fun commandsUseCapturedWireShapes() = runBlocking {
        val fake = FakePrinter.withCapturedFixtures()
        driver(fake).use { d ->
            d.connect()
            d.send(PrinterCommand.SetLight(true))
            d.send(PrinterCommand.SetFan(Fan.CHAMBER, 100))
            d.send(PrinterCommand.SetFan(Fan.AUX, 0))
            d.send(PrinterCommand.StartPrint("ECC2_test.gcode", autoBedLeveling = true, plate = BuildPlate.SMOOTH, slotMap = listOf(SlotMapping(0, 0, 2))))

            val byMethod = fake.requests().associateBy { (it / "method").int() }
            assertEquals("""{"power":1}""", byMethod[Cc2Method.SET_LIGHT]!!["params"].toString())
            val fans = fake.requests().filter { (it / "method").int() == Cc2Method.SET_FAN }.map { it["params"].toString() }
            assertEquals(listOf("""{"box_fan":255}""", """{"aux_fan":0}"""), fans)

            val start = (byMethod[Cc2Method.START_PRINT]!! / "params").obj()!!
            assertEquals("ECC2_test.gcode", (start / "filename").str())
            assertEquals("local", (start / "storage_media").str())
            val config = (start / "config").obj()!!
            assertEquals("B", (config / "print_layout").str())
            assertEquals(true, (config / "printer_check").bool())
            assertEquals(false, (config / "bedlevel_force").bool())
            val slot = config["slot_map"]!!.jsonArray.single().jsonObject
            assertEquals(2, (slot / "tray_id").int())
        }
    }

    @Test
    fun startPrintRetriesWithLevelingWhenMeshMissing() = runBlocking {
        val fake = FakePrinter.withCapturedFixtures()
        driver(fake).use { d ->
            d.connect()
            fake.results[Cc2Method.START_PRINT] = buildJsonObject { put("error_code", 1026) }
            try {
                d.send(PrinterCommand.StartPrint("a.gcode"))
            } catch (_: PrinterCommandException) {
                // The fake keeps answering 1026; we only check that the retry asked for leveling.
            }
            val starts = fake.requests().filter { (it / "method").int() == Cc2Method.START_PRINT }
            assertEquals(2, starts.size)
            assertEquals(true, (((starts[1] / "params").obj() / "config").obj() / "bedlevel_force").bool())
        }
    }

    @Test
    fun printerErrorsSurfaceAsCommandExceptions() = runBlocking {
        val fake = FakePrinter.withCapturedFixtures()
        fake.results[Cc2Method.FILE_LIST] = buildJsonObject { put("error_code", 1017) }
        driver(fake).use { d ->
            d.connect()
            // 1017 = no USB drive: an empty list, not an error.
            assertTrue(d.listFiles(Storage.USB).isEmpty())
            fake.results[Cc2Method.PAUSE_PRINT] = buildJsonObject { put("error_code", 1010) }
            try {
                d.send(PrinterCommand.Pause)
                fail("expected 1010")
            } catch (e: PrinterCommandException) {
                assertEquals(1010, e.code)
                assertEquals("Printer is not printing", e.message)
            }
        }
    }

    @Test
    fun filesHistoryThumbnailAndCameraFromCapture() = runBlocking {
        val fake = FakePrinter.withCapturedFixtures()
        driver(fake).use { d ->
            d.connect()
            val files = d.listFiles(Storage.LOCAL)
            assertTrue(files.isNotEmpty())
            assertTrue(files.first().name.endsWith(".gcode"))
            assertEquals("PLA", files.first().colors.first().material)

            val history = d.history()
            assertTrue(history.isNotEmpty())
            assertEquals(HistoryResult.COMPLETED, history.last().result)
            assertTrue(history.zipWithNext().all { (a, b) -> a.startedAtEpochSec >= b.startedAtEpochSec })

            val png = d.thumbnail(Storage.LOCAL, files.first().name)
            assertNotNull(png)
            png!!
            assertEquals(0x89.toByte(), png[0])
            assertEquals('P'.code.toByte(), png[1])

            assertEquals("http://192.168.1.116:8080/?action=stream", d.cameraUrl())
        }
    }

    @Test
    fun statusDeltasUpdateState() = runBlocking {
        val fake = FakePrinter.withCapturedFixtures()
        fake.results[Cc2Method.GET_STATUS] = fixtureObject("status_job_start.json")
        driver(fake).use { d ->
            d.connect()
            fixture("status_deltas.jsonl").lineSequence().filter { it.isNotBlank() }.forEach(fake::pushStatus)
            val s = withTimeout(2_000) { d.state.first { it.nozzle?.target == 140.0 } }
            // Captured sequence: 11 (file transfer) -> 1 (idle) -> 2 (printing) -> sub 1405 (preheating).
            assertEquals(MachineState.PREHEATING, s.machine)
            assertTrue(s.machine.isJobActive)
        }
    }

    @Test
    fun connectionLossTriggersReconnect() = runBlocking {
        val fake = FakePrinter.withCapturedFixtures()
        driver(fake).use { d ->
            d.connect()
            val first = fake.transport!!
            first.drop()
            assertTrue(d.state.value.connection is ConnectionState.Failed)
            // Backoff starts at 2 s.
            withTimeout(5_000) { d.state.first { it.connection == ConnectionState.Connected } }
            assertFalse(fake.transport === first)
        }
    }

    @Test
    fun filamentCommandsAndDiskInfo() = runBlocking {
        val fake = FakePrinter.withCapturedFixtures()
        // Shape observed live on 02.01.01.07.
        fake.results[Cc2Method.DISK_INFO] = Cc2Json.parseToJsonElement("""{"error_code":0,"internal":{"total_bytes":511647744,"used_bytes":95526912}}""").jsonObject
        listOf(Cc2Method.SET_MONO_FILAMENT, Cc2Method.CANVAS_SET_FILAMENT, Cc2Method.CANVAS_LOAD).forEach {
            fake.results[it] = buildJsonObject { put("error_code", 0) }
        }
        driver(fake).use { d ->
            d.connect()
            assertEquals(dev.openlattice.core.StorageInfo(511647744, 95526912), d.storageInfo())

            d.send(PrinterCommand.SetFilament(-1, 0, dev.openlattice.core.FilamentMaterial.PETG, "#ff0000"))
            d.send(PrinterCommand.SetFilament(0, 3, dev.openlattice.core.FilamentMaterial.PLA, "#00FF00"))
            d.send(PrinterCommand.LoadTray(0, 2))
            val reqs = fake.requests()
            val mono = (reqs.first { (it / "method").int() == Cc2Method.SET_MONO_FILAMENT } / "params").obj()!!
            assertEquals("PETG", (mono / "filament_type").str())
            assertEquals("0x0100", (mono / "filament_code").str())
            assertEquals("#FF0000", (mono / "filament_color").str())
            assertEquals(0, (mono / "tray_id").int())
            val canvas = (reqs.first { (it / "method").int() == Cc2Method.CANVAS_SET_FILAMENT } / "params").obj()!!
            assertEquals(3, (canvas / "tray_id").int())
            val load = (reqs.first { (it / "method").int() == Cc2Method.CANVAS_LOAD } / "params").obj()!!
            assertEquals("""{"canvas_id":0,"tray_id":2}""", load.toString())
        }
    }

    @Test
    fun disconnectThenConnectAgain() = runBlocking {
        val fake = FakePrinter.withCapturedFixtures()
        driver(fake).use { d ->
            d.connect()
            val first = fake.transport!!
            d.disconnect()
            assertEquals(ConnectionState.Disconnected, d.state.value.connection)
            // No automatic reconnect after an explicit disconnect.
            kotlinx.coroutines.delay(2_500)
            assertTrue(fake.transport === first)
            d.connect()
            assertEquals(ConnectionState.Connected, d.state.value.connection)
            assertFalse(fake.transport === first)
        }
    }

    @Test
    fun discoveryReplyParses() {
        val r = Cc2Discovery.parse("192.168.1.116", fixture("discovery_reply.json").toByteArray())!!
        assertEquals("F01TESTSERIAL01", r.serial)
        assertEquals("Centauri Carbon 2", r.model)
        assertTrue(r.accessCodeRequired)
        assertTrue(r.lanOnly)
    }
}
