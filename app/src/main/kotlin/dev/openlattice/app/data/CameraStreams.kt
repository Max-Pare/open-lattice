package dev.openlattice.app.data

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.openlattice.cc2.Cc2Driver
import dev.openlattice.core.MjpegReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive
import okhttp3.Request
import java.util.concurrent.TimeUnit

sealed interface CameraFrame {
    data object Connecting : CameraFrame
    data class Image(val bitmap: ImageBitmap) : CameraFrame
    data class Error(val message: String) : CameraFrame
}

/**
 * One upstream MJPEG connection per URL, shared by every viewer (the printer allows only a few
 * camera clients and leaks slots on abrupt disconnects). The upstream closes 3 s after the last viewer leaves.
 */
object CameraStreams {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val streams = mutableMapOf<String, Flow<CameraFrame>>()
    private val http by lazy {
        Cc2Driver.defaultHttp.newBuilder().readTimeout(15, TimeUnit.SECONDS).build()
    }

    @Synchronized
    fun frames(url: String): Flow<CameraFrame> = streams.getOrPut(url) {
        channelFlow<CameraFrame> {
            while (isActive) {
                send(CameraFrame.Connecting)
                try {
                    val call = http.newCall(Request.Builder().url(url).build())
                    val response = call.execute()
                    response.use {
                        if (!it.isSuccessful) throw IllegalStateException("Camera answered HTTP ${it.code}")
                        val reader = MjpegReader(it.body.byteStream())
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val jpeg = reader.nextFrame() ?: break
                            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: continue
                            send(CameraFrame.Image(bmp.asImageBitmap()))
                        }
                    }
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    send(CameraFrame.Error(e.message ?: "Camera unavailable"))
                }
                delay(2_000)
            }
        }.shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 3_000), replay = 1)
    }
}
