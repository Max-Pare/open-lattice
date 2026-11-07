package dev.openlattice.app.ui.printer

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.openlattice.app.ui.components.Panel
import dev.openlattice.app.ui.components.StatusWord
import dev.openlattice.app.ui.components.ThickProgress
import dev.openlattice.app.ui.formatBytes
import dev.openlattice.app.ui.theme.Lattice
import dev.openlattice.core.PrinterCommand
import dev.openlattice.core.PrinterState

enum class PrinterTab(val label: String, val icon: ImageVector) {
    DEVICE("Device", Icons.Outlined.Print),
    FILES("Files", Icons.Outlined.Folder),
    HISTORY("History", Icons.Outlined.History),
    SETTINGS("Settings", Icons.Outlined.Settings),
}

/** Pages opened from the device home, on top of the tabs. */
private enum class SubPage { MOVE, FILAMENT }

@Composable
fun PrinterScreen(printerId: String, onBack: () -> Unit, onCamera: (String) -> Unit, onRemoved: () -> Unit, onAppearance: () -> Unit) {
    val application = LocalContext.current.applicationContext as Application
    val vm: PrinterViewModel = viewModel(
        key = printerId,
        factory = viewModelFactory { initializer { PrinterViewModel(application, printerId) } },
    )
    val printer by vm.printer.collectAsState()
    val state by vm.state.collectAsState()
    val upload by vm.upload.collectAsState()
    val printPrompt by vm.printPrompt.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(PrinterTab.DEVICE) }
    var page by rememberSaveable { mutableStateOf<SubPage?>(null) }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(enabled = page != null) { page = null }

    when (page) {
        SubPage.MOVE -> MovePage(
            state = state,
            camera = { CameraView(vm::cameraUrl, Modifier.fillMaxWidth().aspectRatio(16f / 9f)) },
            onJog = { axis, mm -> vm.send(PrinterCommand.Jog(axis, mm)) },
            onHome = { vm.send(PrinterCommand.Home(), success = "Homing") },
            onBack = { page = null },
            snackbar = snackbar,
        )
        SubPage.FILAMENT -> FilamentPage(vm, state, onBack = { page = null }, snackbar = snackbar)
        null -> PrinterScaffold(
            title = printer?.name?.ifEmpty { printer?.model } ?: "",
            state = state,
            tab = tab,
            onTab = { tab = it },
            onBack = onBack,
            snackbar = snackbar,
            banner = {
                upload?.let {
                    UploadBanner(
                        it,
                        canPrint = !state.machine.isJobActive,
                        onCancel = vm::cancelUpload,
                        onDismiss = vm::dismissUpload,
                        onPrint = vm::printUploaded,
                    )
                }
            },
        ) {
            when (tab) {
                PrinterTab.DEVICE -> DeviceTab(
                    vm, state,
                    onCamera = { onCamera(printerId) },
                    onMove = { page = SubPage.MOVE },
                    onFilament = { page = SubPage.FILAMENT },
                    onFiles = { tab = PrinterTab.FILES },
                )
                PrinterTab.FILES -> FilesTab(vm, state)
                PrinterTab.HISTORY -> HistoryTab(vm, printerBusy = state.machine.isJobActive)
                PrinterTab.SETTINGS -> SettingsTab(vm, onRemoved, onAppearance)
            }
        }
    }

    printPrompt?.let { PrintSheet(vm, state, it, onDismiss = vm::closePrintSheet) }
}

/** Top bar with the printer's name and status, bottom tabs; no printer needed to render it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterScaffold(
    title: String,
    state: PrinterState,
    tab: PrinterTab,
    onTab: (PrinterTab) -> Unit,
    onBack: () -> Unit,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    banner: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                        StatusWord(state)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Lattice.colors.panel) {
                PrinterTab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { onTab(t) },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = Lattice.colors.muted,
                            unselectedTextColor = Lattice.colors.muted,
                        ),
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            banner()
            content()
        }
    }
}

@Composable
private fun UploadBanner(u: UploadUi, canPrint: Boolean, onCancel: () -> Unit, onDismiss: () -> Unit, onPrint: () -> Unit) {
    Panel(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(u.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            when {
                u.error != null -> Text(u.error, color = MaterialTheme.colorScheme.error)
                u.done -> Text(
                    if (canPrint) "Uploaded" else "Uploaded. The printer is busy; start it from Files when it is free.",
                    color = Lattice.colors.muted,
                )
                else -> {
                    val p = u.progress
                    ThickProgress(p?.fraction ?: 0f)
                    val sent = p?.let { "${formatBytes(it.sentBytes)} / ${formatBytes(it.totalBytes)}" } ?: "Preparing…"
                    Text(if (u.printAfter) "$sent · prints when uploaded" else sent, color = Lattice.colors.muted, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (u.done || u.error != null) TextButton(onClick = onDismiss) { Text("Dismiss") }
                else TextButton(onClick = onCancel) { Text("Cancel") }
                if (u.done && canPrint) Button(onClick = onPrint, modifier = Modifier.padding(start = 8.dp)) { Text("Print now") }
            }
        }
    }
}
