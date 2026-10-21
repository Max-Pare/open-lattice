package dev.openlattice.cc2

import dev.openlattice.core.PrinterCommandException
import dev.openlattice.core.UploadSource
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

class Cc2UploaderTest {
    private val server = MockWebServer()

    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private fun source(bytes: ByteArray, name: String = "ECC2_0.4_test model_PLA _0.2_1h.gcode") = object : UploadSource {
        override val name = name
        override val sizeBytes = bytes.size.toLong()
        override fun open() = ByteArrayInputStream(bytes)
    }

    private fun ok(offset: Long) = MockResponse.Builder().code(200).body("{\n  \"error_code\": 0,\n  \"offset\": $offset\n}").build()

    @Test
    fun uploadsInChunksWithCapturedHeaders() = runBlocking {
        val data = ByteArray(2500) { (it % 251).toByte() }
        // First reply mimics the firmware quirk: inclusive end instead of the next offset.
        server.enqueue(ok(1023)); server.enqueue(ok(2048)); server.enqueue(ok(2500))
        val uploader = Cc2Uploader(OkHttpClient(), server.url("/").toString().trimEnd('/'), "abc123", chunkSize = 1024)

        val progress = uploader.upload(source(data)).toList()
        assertEquals(listOf(0L, 1024L, 2048L, 2500L), progress.map { it.sentBytes })

        val md5 = Cc2Uploader.md5Hex(ByteArrayInputStream(data))
        val ranges = (0 until 3).map { server.takeRequest() }.map { r ->
            assertEquals("PUT", r.method)
            assertEquals("/upload", r.url.encodedPath)
            assertEquals("abc123", r.headers["X-Token"])
            assertEquals(md5, r.headers["X-File-MD5"])
            // Sent raw, spaces included, exactly like ElegooLink.
            assertEquals("ECC2_0.4_test model_PLA _0.2_1h.gcode", r.headers["X-File-Name"])
            r.headers["Content-Range"]
        }
        assertEquals(listOf("bytes 0-1023/2500", "bytes 1024-2047/2500", "bytes 2048-2499/2500"), ranges)
    }

    @Test
    fun printerErrorStopsWithoutRetry() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("""{"error_code":9004}""").build())
        val uploader = Cc2Uploader(OkHttpClient(), server.url("/").toString().trimEnd('/'), "abc123", chunkSize = 1024)
        try {
            uploader.upload(source(ByteArray(2000))).toList()
            fail("expected 9004")
        } catch (e: PrinterCommandException) {
            assertEquals(9004, e.code)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun httpErrorStopsWithoutRetry() = runBlocking {
        server.enqueue(MockResponse.Builder().code(429).build())
        val uploader = Cc2Uploader(OkHttpClient(), server.url("/").toString().trimEnd('/'), "abc123", chunkSize = 1024)
        try {
            uploader.upload(source(ByteArray(2000))).toList()
            fail("expected 429")
        } catch (e: PrinterCommandException) {
            assertEquals(429, e.code)
        }
        assertEquals(1, server.requestCount)
    }
}
