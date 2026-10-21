package dev.openlattice.cc2

import dev.openlattice.core.ConnectionState
import dev.openlattice.core.MjpegReader
import dev.openlattice.core.Storage
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.serialization.json.put
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress

/**
 * Read-only checks against a real printer. Skipped unless OPEN_LATTICE_CC2_HOST and
 * OPEN_LATTICE_CC2_CODE are set. Sends no command that changes printer state.
 */
class Cc2LiveTest {
    private val host = System.getenv("OPEN_LATTICE_CC2_HOST").orEmpty()
    private val code = System.getenv("OPEN_LATTICE_CC2_CODE").orEmpty()

    @Test
    fun readOnlySession() = runBlocking {
        assumeTrue("live printer not configured", host.isNotEmpty() && code.isNotEmpty())

        val found = Cc2Discovery.probe(listOf(InetAddress.getByName(host)))
        assertEquals(1, found.size)
        val printer = found.single()
        println("discovered: $printer")
        assertTrue("printer must be in LAN-only mode", printer.lanOnly)

        Cc2Driver(host, printer.serial, code).use { d ->
            d.connect()
            val info = withTimeout(5_000) { d.info.filterNotNull().first() }
            println("info: $info")
            val state = withTimeout(5_000) { d.state.first { it.connection == ConnectionState.Connected && it.nozzle != null } }
            println("state: $state")

            val files = d.listFiles(Storage.LOCAL)
            println("local files: ${files.size}, first: ${files.firstOrNull()}")
            files.firstOrNull()?.let { f ->
                val png = d.thumbnail(Storage.LOCAL, f.name)
                println("thumbnail: ${png?.size} bytes")
            }
            // The printer answers 1009 (busy) to USB listing while printing.
            val usb = runCatching { d.listFiles(Storage.USB).size }
            println("usb files: ${usb.getOrElse { it.message }}")

            val history = d.history()
            println("history: ${history.size}, latest: ${history.firstOrNull()}")

            // Read-only probes of methods not seen in the capture.
            val raw = Cc2Client(host, printer.serial, code, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO))
            raw.connect()
            for ((method, params) in listOf(
                Cc2Method.DISK_INFO to null,
                Cc2Method.FILE_DETAIL to kotlinx.serialization.json.buildJsonObject {
                    put("storage_media", "local")
                    put("filename", files.first().name)
                },
            )) {
                val r = runCatching { raw.request(method, params) }
                println("probe $method: ${r.getOrElse { "ERROR ${it.message}" }}")
            }
            raw.close()
            history.firstOrNull { it.timelapse != null }?.timelapse?.let { t ->
                val url = "http://$host/download?X-Token=$code&file_name=" + java.net.URLEncoder.encode(t.path, "UTF-8")
                val quick = Cc2Driver.defaultHttp.newBuilder().readTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build()
                val r = runCatching {
                    quick.newCall(Request.Builder().url(url).header("Range", "bytes=0-15").build()).execute().use {
                        "HTTP ${it.code} ${it.header("Content-Type")} ${it.body.bytes().size} bytes"
                    }
                }
                println("probe download (${t.path}, ready=${t.ready}): ${r.getOrElse { "ERROR $it" }}")
            }

            val camera = d.cameraUrl()!!
            println("camera: $camera")
            val response = Cc2Driver.defaultHttp.newCall(Request.Builder().url(camera).build()).execute()
            response.use {
                val frame = MjpegReader(it.body.byteStream()).nextFrame()!!
                println("camera frame: ${frame.size} bytes")
                assertTrue(frame.size > 1000)
            }
        }
    }
}
