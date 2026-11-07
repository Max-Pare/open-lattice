package dev.openlattice.app.ui.printer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.openlattice.app.ui.displayName
import dev.openlattice.app.ui.components.ColorDot
import dev.openlattice.app.ui.components.ThickProgress
import dev.openlattice.app.ui.components.Thumb
import dev.openlattice.app.ui.formatBytes
import dev.openlattice.app.ui.formatDuration
import dev.openlattice.app.ui.theme.Lattice
import dev.openlattice.core.ConnectionState
import dev.openlattice.core.PrinterFile
import dev.openlattice.core.PrinterState
import dev.openlattice.core.Storage

@Composable
fun FilesTab(vm: PrinterViewModel, state: PrinterState) {
    val ui by vm.files.collectAsState()
    var printAfterPick by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.upload(it, printAfter = printAfterPick) } }
    val connected = state.connection == ConnectionState.Connected

    LaunchedEffect(connected) { if (connected) vm.loadFiles() }

    FilesContent(
        ui = ui,
        thumbnails = vm.thumbnails,
        connected = connected,
        busy = state.machine.isJobActive,
        onStorage = { vm.loadFiles(it) },
        onRefresh = { vm.loadFiles() },
        onUpload = { printAfterPick = false; picker.launch(arrayOf("*/*")) },
        onUploadAndPrint = { printAfterPick = true; picker.launch(arrayOf("*/*")) },
        onVisible = { vm.loadThumbnail(ui.storage, it.name) },
        onOpen = { vm.openPrintSheet(it, ui.storage) },
    )
}

@Composable
fun FilesContent(
    ui: FilesUi,
    thumbnails: Map<String, ImageBitmap?>,
    connected: Boolean,
    busy: Boolean,
    onStorage: (Storage) -> Unit,
    onRefresh: () -> Unit,
    onUpload: () -> Unit,
    onUploadAndPrint: () -> Unit,
    onVisible: (PrinterFile) -> Unit,
    onOpen: (PrinterFile) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                        listOf(Storage.LOCAL to "Printer", Storage.USB to "USB").forEachIndexed { i, (s, label) ->
                            SegmentedButton(selected = ui.storage == s, onClick = { onStorage(s) }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                        }
                    }
                    IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh") }
                }
                ui.space?.let { sp ->
                    ThickProgress(if (sp.totalBytes > 0) sp.usedBytes.toFloat() / sp.totalBytes else 0f, color = Lattice.colors.muted)
                    Text(
                        "${formatBytes(sp.usedBytes)} of ${formatBytes(sp.totalBytes)} used · ${formatBytes(sp.totalBytes - sp.usedBytes)} free",
                        color = Lattice.colors.muted, style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = onUpload, enabled = connected, modifier = Modifier.weight(1f).height(48.dp)) {
                        Icon(Icons.Default.UploadFile, null)
                        Text("Upload", Modifier.padding(start = 8.dp))
                    }
                    Button(onClick = onUploadAndPrint, enabled = connected && !busy, modifier = Modifier.weight(1f).height(48.dp)) {
                        Icon(Icons.Default.PlayArrow, null)
                        Text("Upload & print", Modifier.padding(start = 8.dp), maxLines = 1)
                    }
                }
                when {
                    ui.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    ui.error != null -> Text(ui.error, color = MaterialTheme.colorScheme.error)
                    ui.files.isEmpty() -> Text("No files here yet.", color = Lattice.colors.muted)
                }
            }
        }
        items(ui.files, key = { it.name }) { f ->
            LaunchedEffect(f.name) { onVisible(f) }
            FileRow(f, thumbnails[f.name], onClick = { onOpen(f) })
        }
    }
}

@Composable
private fun FileRow(f: PrinterFile, thumb: ImageBitmap?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Thumb(thumb, 88.dp)
        Column(Modifier.weight(1f).height(88.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(displayName(f.name), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(f.printTimeSec?.let { formatDuration(it) }, f.filamentUsedGrams?.let { "%.1f g".format(it) }, formatBytes(f.sizeBytes)).joinToString(" · "),
                    Modifier.weight(1f), color = Lattice.colors.muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    f.colors.distinctBy { it.tool }.take(6).forEach { ColorDot(parseColor(it.color), size = 14.dp) }
                }
            }
        }
    }
}

internal fun parseColor(hex: String?): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex ?: "#888888")) }.getOrDefault(Color.Gray)
