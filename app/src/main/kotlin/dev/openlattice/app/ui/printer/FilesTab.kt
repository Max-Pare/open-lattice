package dev.openlattice.app.ui.printer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.openlattice.app.ui.formatBytes
import dev.openlattice.app.ui.formatDuration
import dev.openlattice.core.BuildPlate
import dev.openlattice.core.ConnectionState
import dev.openlattice.core.FilamentSlot
import dev.openlattice.core.PrinterCommand
import dev.openlattice.core.PrinterFile
import dev.openlattice.core.PrinterState
import dev.openlattice.core.SlotMapping
import dev.openlattice.core.Storage
import java.text.DateFormat
import java.util.Date

@Composable
fun FilesTab(vm: PrinterViewModel, state: PrinterState) {
    val ui by vm.files.collectAsState()
    var selected by remember { mutableStateOf<PrinterFile?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::upload) }
    val connected = state.connection == ConnectionState.Connected

    LaunchedEffect(connected) { if (connected) vm.loadFiles() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                listOf(Storage.LOCAL to "Printer", Storage.USB to "USB").forEachIndexed { i, (s, label) ->
                    SegmentedButton(selected = ui.storage == s, onClick = { vm.loadFiles(s) }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                }
            }
            IconButton(onClick = { vm.loadFiles() }) { Icon(Icons.Default.Refresh, "Refresh") }
        }
        ui.space?.let { sp ->
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                LinearProgressIndicator(progress = { if (sp.totalBytes > 0) sp.usedBytes.toFloat() / sp.totalBytes else 0f }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${formatBytes(sp.usedBytes)} of ${formatBytes(sp.totalBytes)} used · ${formatBytes(sp.totalBytes - sp.usedBytes)} free",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        FilledTonalButton(
            onClick = { picker.launch(arrayOf("*/*")) },
            enabled = connected,
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        ) {
            Icon(Icons.Default.UploadFile, null)
            Text("Upload a sliced file from this phone", Modifier.padding(start = 8.dp))
        }
        when {
            ui.loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            ui.error != null -> Text(ui.error!!, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            ui.files.isEmpty() -> Text("No files", Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(ui.files, key = { it.name }) { f ->
                LaunchedEffect(f.name) { vm.loadThumbnail(ui.storage, f.name) }
                ListItem(
                    modifier = Modifier.clickable { selected = f },
                    leadingContent = { Thumbnail(vm, f.name) },
                    headlineContent = { Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                f.printTimeSec?.let { formatDuration(it) },
                                f.filamentUsedGrams?.let { "%.1f g".format(it) },
                                formatBytes(f.sizeBytes),
                            ).joinToString(" · "),
                        )
                    },
                    trailingContent = { ColorDots(f) },
                )
            }
        }
    }

    selected?.let { f ->
        FileDialog(vm, state, ui.storage, f, onDismiss = { selected = null })
    }
}

@Composable
private fun Thumbnail(vm: PrinterViewModel, name: String) {
    val bmp = vm.thumbnails[name]
    Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun ColorDots(f: PrinterFile) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        f.colors.take(4).forEach { c ->
            Box(Modifier.size(12.dp).clip(CircleShape).background(parseColor(c.color)).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
        }
    }
}

internal fun parseColor(hex: String?): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex ?: "#888888")) }.getOrDefault(Color.Gray)

@Composable
private fun FileDialog(vm: PrinterViewModel, state: PrinterState, storage: Storage, f: PrinterFile, onDismiss: () -> Unit) {
    var leveling by remember { mutableStateOf(false) }
    var plate by remember { mutableStateOf(BuildPlate.TEXTURED) }
    var timelapse by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val slots = state.filament?.hubs.orEmpty().flatMap { it.slots }.filter { it.present }
    // Tool index -> chosen CANVAS slot. Defaults to the first slot with a matching material.
    val mapping = remember(f, slots) {
        mutableStateMapOf<Int, FilamentSlot?>().apply {
            f.colors.forEach { c -> put(c.tool, slots.firstOrNull { it.material.equals(c.material, ignoreCase = true) } ?: slots.firstOrNull()) }
        }
    }
    val busy = state.machine.isJobActive

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(f.name, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.thumbnails[f.name]?.let { Image(it, null, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))) }
                Text(
                    listOfNotNull(
                        f.printTimeSec?.let { "Print time ${formatDuration(it)}" },
                        f.layers?.let { "$it layers" },
                        f.filamentUsedGrams?.let { "%.1f g filament".format(it) },
                        f.createdAtEpochSec?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it * 1000)) },
                    ).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(BuildPlate.TEXTURED to "Textured plate", BuildPlate.SMOOTH to "Smooth plate").forEachIndexed { i, (p, label) ->
                        SegmentedButton(selected = plate == p, onClick = { plate = p }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label, maxLines = 1) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Auto bed leveling", Modifier.weight(1f))
                    Switch(leveling, { leveling = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Record timelapse", Modifier.weight(1f))
                    Switch(timelapse, { timelapse = it })
                }
                if (slots.isNotEmpty() && f.colors.isNotEmpty()) {
                    Text("CANVAS slots", style = MaterialTheme.typography.titleSmall)
                    f.colors.forEach { c ->
                        SlotPicker(c.tool, c.color, c.material, slots, mapping[c.tool]) { mapping[c.tool] = it }
                    }
                }
                if (busy) Text("A print is running; start this one when it finishes.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(
                enabled = !busy,
                onClick = {
                    onDismiss()
                    vm.send(
                        PrinterCommand.StartPrint(
                            fileName = f.name,
                            storage = storage,
                            autoBedLeveling = leveling,
                            plate = plate,
                            timelapse = timelapse,
                            slotMap = mapping.mapNotNull { (tool, slot) -> slot?.let { SlotMapping(tool, it.hubId, it.trayId) } },
                        ),
                        success = "Print started",
                    )
                },
            ) { Text("Print") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete file?") },
            text = { Text(f.name) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDismiss(); vm.deleteFile(storage, f.name) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SlotPicker(tool: Int, color: String, material: String, slots: List<FilamentSlot>, chosen: FilamentSlot?, onChoose: (FilamentSlot) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(parseColor(color)))
        Text("T${tool + 1} $material", Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { open = true }) {
                Text(chosen?.let { "Slot ${it.hubId + 1}-${it.trayId + 1} ${it.material}" } ?: "Choose")
            }
            DropdownMenu(open, { open = false }) {
                slots.forEach { s ->
                    DropdownMenuItem(
                        text = { Text("Slot ${s.hubId + 1}-${s.trayId + 1} · ${s.material} ${s.name}") },
                        leadingIcon = { Box(Modifier.size(16.dp).clip(CircleShape).background(parseColor(s.color))) },
                        onClick = { open = false; onChoose(s) },
                    )
                }
            }
        }
    }
}
