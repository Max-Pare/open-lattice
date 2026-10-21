package dev.openlattice.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.openlattice.core.UploadSource
import java.io.InputStream

/** A file picked through the Storage Access Framework or received via a share intent. */
class UriUploadSource private constructor(
    private val context: Context,
    private val uri: Uri,
    override val name: String,
    override val sizeBytes: Long,
) : UploadSource {
    override fun open(): InputStream =
        requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot open $uri" }

    companion object {
        fun from(context: Context, uri: Uri): UriUploadSource {
            var name: String? = null
            var size = -1L
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
            if (size < 0) {
                // Some providers do not report a size; count it.
                size = context.contentResolver.openInputStream(uri)?.use { s ->
                    var total = 0L
                    val buf = ByteArray(64 * 1024)
                    while (true) { val n = s.read(buf); if (n < 0) break; total += n }
                    total
                } ?: 0L
            }
            return UriUploadSource(context.applicationContext, uri, name ?: uri.lastPathSegment ?: "upload.gcode", size)
        }
    }
}
