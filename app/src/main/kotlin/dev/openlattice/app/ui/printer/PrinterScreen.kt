package dev.openlattice.app.ui.printer

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Texture
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.openlattice.app.ui.StatusLine
import dev.openlattice.app.ui.formatBytes

private enum class Tab(val label: String) { STATUS("Status"), FILES("Files"), HISTORY("History"), FILAMENT("Filament"), SETTINGS("Settings") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterScreen(printerId: String, onBack: () -> Unit, onCamera: (String) -> Unit, onRemoved: () -> Unit) {
    val application = LocalContext.current.applicationContext as Application
    val vm: PrinterViewModel = viewModel(
        key = printerId,
        factory = viewModelFactory { initializer { PrinterViewModel(application, printerId) } },
    )
    val printer by vm.printer.collectAsState()
    val state by vm.state.collectAsState()
    val upload by vm.upload.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(printer?.name?.ifEmpty { printer?.model } ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        StatusLine(state)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.STATUS -> Icons.Default.Dashboard
                                    Tab.FILES -> Icons.Default.Folder
                                    Tab.HISTORY -> Icons.Default.History
                                    Tab.FILAMENT -> Icons.Default.Texture
                                    Tab.SETTINGS -> Icons.Default.Settings
                                },
                                null,
                            )
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            upload?.let { UploadBanner(it, onCancel = vm::cancelUpload, onDismiss = vm::dismissUpload) }
            when (Tab.entries[tab]) {
                Tab.STATUS -> StatusTab(vm, state, onCamera = { onCamera(printerId) })
                Tab.FILES -> FilesTab(vm, state)
                Tab.HISTORY -> HistoryTab(vm)
                Tab.FILAMENT -> FilamentTab(vm, state)
                Tab.SETTINGS -> SettingsTab(vm, onRemoved)
            }
        }
    }
}

@Composable
private fun UploadBanner(u: UploadUi, onCancel: () -> Unit, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(u.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            when {
                u.error != null -> Text(u.error, color = MaterialTheme.colorScheme.error)
                u.done -> Text("Uploaded. Start it from the Files tab.")
                else -> {
                    val p = u.progress
                    if (p == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                    Text(p?.let { "${formatBytes(it.sentBytes)} / ${formatBytes(it.totalBytes)}" } ?: "Preparing…", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (u.done || u.error != null) TextButton(onClick = onDismiss) { Text("Dismiss") }
                else TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}
