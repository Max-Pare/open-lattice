package dev.openlattice.app.ui.printer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.openlattice.app.data.CameraFrame
import dev.openlattice.app.data.CameraStreams
import kotlinx.coroutines.flow.emptyFlow

/** Live MJPEG view. [urlProvider] is resolved once; the stream is shared with other viewers. */
@Composable
fun CameraView(urlProvider: suspend () -> String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit) {
    var url by remember { mutableStateOf<String?>(null) }
    var resolved by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        url = urlProvider()
        resolved = true
    }
    val frames = remember(url) { url?.let(CameraStreams::frames) ?: emptyFlow() }
    val frame by frames.collectAsState(initial = CameraFrame.Connecting)

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        when {
            resolved && url == null -> Text("Camera unavailable", color = Color.White)
            else -> when (val f = frame) {
                is CameraFrame.Image -> Image(f.bitmap, contentDescription = "Printer camera", contentScale = contentScale, modifier = Modifier.fillMaxSize())
                is CameraFrame.Error -> Text(f.message, color = Color.White, modifier = Modifier.padding(16.dp))
                CameraFrame.Connecting -> CircularProgressIndicator()
            }
        }
    }
}
