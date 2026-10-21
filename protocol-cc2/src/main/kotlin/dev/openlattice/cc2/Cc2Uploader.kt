package dev.openlattice.cc2

import dev.openlattice.core.PrinterCommandException
import dev.openlattice.core.UploadProgress
import dev.openlattice.core.UploadSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Chunked upload to `PUT http://<host>/upload`, as ElegooLink does it:
 * 1 MiB raw chunks with `Content-Range`, `X-File-Name`, whole-file `X-File-MD5` and `X-Token`.
 * Each chunk answers `{"error_code":0,"offset":<next offset>}`.
 *
 * Never retries after the printer answered: a re-sent chunk after an error response
 * corrupts the file on the printer. Only transport failures (no response) are retried.
 */
class Cc2Uploader(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val accessCode: String,
    private val chunkSize: Int = CHUNK_SIZE,
) {
    fun upload(source: UploadSource): Flow<UploadProgress> = flow {
        val total = source.sizeBytes
        val md5 = source.open().use(::md5Hex)
        emit(UploadProgress(0, total))
        source.open().use { input ->
            var offset = 0L
            val buf = ByteArray(chunkSize)
            while (offset < total) {
                coroutineContext.ensureActive()
                val n = input.readFully(buf)
                if (n <= 0) throw IOException("Source ended at $offset of $total bytes")
                val next = putChunk(source.name, md5, buf, n, offset, total)
                if (next != offset + n) throw PrinterCommandException(9000, "Printer expected offset $next, sent ${offset + n}")
                offset = next
                emit(UploadProgress(offset, total))
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun putChunk(name: String, md5: String, buf: ByteArray, n: Int, offset: Long, total: Long): Long {
        val headers = Headers.Builder()
            .add("Accept", "application/json")
            .add("X-Token", accessCode)
            .add("X-File-MD5", md5)
            .add("Content-Range", "bytes $offset-${offset + n - 1}/$total")
            // The printer takes the raw (unencoded) name; OkHttp needs the unsafe variant for non-ASCII.
            .addUnsafeNonAscii("X-File-Name", name)
            .build()
        val request = Request.Builder()
            .url("$baseUrl/upload")
            .headers(headers)
            .put(buf.toRequestBody(OCTET_STREAM, 0, n))
            .build()

        var attempt = 0
        while (true) {
            val response = try {
                http.newCall(request).execute()
            } catch (e: IOException) {
                if (++attempt >= MAX_TRANSPORT_ATTEMPTS) throw e
                continue
            }
            response.use {
                val body = it.body.string()
                if (!it.isSuccessful) throw PrinterCommandException(it.code, "Upload rejected: HTTP ${it.code} $body")
                val json = runCatching { Cc2Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: throw IOException("Unexpected upload response: $body")
                val code = (json / "error_code").int() ?: 0
                if (code != 0) throw PrinterCommandException(code, Cc2Method.errorMessage(code))
                return (json / "offset").lng() ?: (offset + n)
            }
        }
    }

    companion object {
        const val CHUNK_SIZE = 1024 * 1024
        private const val MAX_TRANSPORT_ATTEMPTS = 3
        private val OCTET_STREAM = "application/octet-stream".toMediaType()

        internal fun md5Hex(input: InputStream): String {
            val digest = MessageDigest.getInstance("MD5")
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun InputStream.readFully(buf: ByteArray): Int {
            var off = 0
            while (off < buf.size) {
                val n = read(buf, off, buf.size - off)
                if (n < 0) break
                off += n
            }
            return off
        }
    }
}
