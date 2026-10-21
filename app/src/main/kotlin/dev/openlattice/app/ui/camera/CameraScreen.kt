package dev.openlattice.app.ui.camera

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.openlattice.app.ui.printer.CameraView
import dev.openlattice.app.ui.printer.PrinterViewModel
import dev.openlattice.core.PrinterCommand

@Composable
fun CameraScreen(printerId: String, onBack: () -> Unit) {
    val application = LocalContext.current.applicationContext as Application
    val vm: PrinterViewModel = viewModel(
        key = "camera-$printerId",
        factory = viewModelFactory { initializer { PrinterViewModel(application, printerId) } },
    )
    val state by vm.state.collectAsState()
    val buttonColors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f), contentColor = Color.White)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        CameraView(vm::cameraUrl, Modifier.fillMaxSize())
        IconButton(onClick = onBack, colors = buttonColors, modifier = Modifier.statusBarsPadding().padding(8.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
        }
        state.lightOn?.let { on ->
            IconButton(
                onClick = { vm.send(PrinterCommand.SetLight(!on)) },
                colors = buttonColors,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
            ) { Icon(if (on) Icons.Filled.Lightbulb else Icons.Outlined.Lightbulb, if (on) "Light off" else "Light on") }
        }
    }
}
