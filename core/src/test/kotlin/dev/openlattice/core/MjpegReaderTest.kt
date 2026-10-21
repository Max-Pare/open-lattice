package dev.openlattice.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MjpegReaderTest {
    private val jpegA = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
    private val jpegB = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9, 0xFF.toByte(), 0xD9.toByte())

    private fun part(jpeg: ByteArray, withLength: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("--frame\r\nContent-Type: image/jpeg\r\n".toByteArray())
        if (withLength) out.write("Content-Length: ${jpeg.size}\r\n".toByteArray())
        out.write("\r\n".toByteArray())
        out.write(jpeg)
        out.write("\r\n".toByteArray())
        return out.toByteArray()
    }

    @Test
    fun readsFramesWithContentLength() {
        val reader = MjpegReader(ByteArrayInputStream(part(jpegA, true) + part(jpegB, true)))
        assertArrayEquals(jpegA, reader.nextFrame())
        assertArrayEquals(jpegB, reader.nextFrame())
        assertNull(reader.nextFrame())
    }

    @Test
    fun readsFramesWithoutContentLength() {
        val reader = MjpegReader(ByteArrayInputStream(part(jpegA, false) + part(jpegB, false)))
        assertArrayEquals(jpegA, reader.nextFrame())
        assertArrayEquals(jpegB, reader.nextFrame())
        assertNull(reader.nextFrame())
    }
}
