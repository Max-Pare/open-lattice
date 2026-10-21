package dev.openlattice.core

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

/**
 * Splits a `multipart/x-mixed-replace` MJPEG body into JPEG frames.
 *
 * Uses each part's `Content-Length` when present and falls back to scanning for the
 * JPEG end-of-image marker otherwise, so it copes with mjpg-streamer variants that omit it.
 */
class MjpegReader(input: InputStream) : AutoCloseable {
    private val input = if (input is BufferedInputStream) input else BufferedInputStream(input, 64 * 1024)

    /** Returns the next JPEG frame, or null at end of stream. */
    fun nextFrame(): ByteArray? {
        while (true) {
            val headers = readPartHeaders() ?: return null
            val length = headers["content-length"]?.trim()?.toIntOrNull()
            val frame = if (length != null && length > 0) readExactly(length) else readUntilEoi() ?: return null
            if (frame.size >= 2 && frame[0] == 0xFF.toByte() && frame[1] == 0xD8.toByte()) return frame
        }
    }

    /** Skips to the next part and parses its headers. Null at end of stream. */
    private fun readPartHeaders(): Map<String, String>? {
        val headers = mutableMapOf<String, String>()
        var inHeaders = false
        while (true) {
            val line = readLine() ?: return null
            if (!inHeaders) {
                // Anything before the first header line (boundary, blank lines) is skipped.
                if (line.contains(':')) inHeaders = true else continue
            }
            if (line.isEmpty()) return headers
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
    }

    private fun readLine(): String? {
        val out = ByteArrayOutputStream(64)
        while (true) {
            val b = input.read()
            if (b == -1) return if (out.size() == 0) null else out.toString(Charsets.ISO_8859_1)
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
            if (out.size() > 8192) break
        }
        return out.toString(Charsets.ISO_8859_1)
    }

    private fun readExactly(n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r == -1) throw EOFException("MJPEG frame truncated at $off/$n bytes")
            off += r
        }
        return buf
    }

    private fun readUntilEoi(): ByteArray? {
        val out = ByteArrayOutputStream(64 * 1024)
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) return null
            out.write(b)
            if (prev == 0xFF && b == 0xD9) return out.toByteArray()
            prev = b
        }
    }

    override fun close() = input.close()
}
