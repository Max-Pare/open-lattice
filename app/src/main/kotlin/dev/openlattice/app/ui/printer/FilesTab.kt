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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
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
import dev.openlattice.core.PrintPlanner
import dev.openlattice.core.PrinterFile
import dev.openlattice.core.PrinterState
import dev.openlattice.core.Storage
import java.text.DateFormat
import java.util.Date

@Composable
fun FilesTab(vm: PrinterViewModel, state: PrinterState) {
    val ui by vm.files.collectAsState()
    var printAfterPick by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.upload(it, printAfter = printAfterPick) } }
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
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = { printAfterPick = false; picker.launch(arrayOf("*/*")) },
                enabled = connected,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.UploadFile, null)
                Text("Upload", Modifier.padding(start = 8.dp))
            }
            Button(
                onClick = { printAfterPick = true; picker.launch(arrayOf("*/*")) },
                enabled = connected && !state.machine.isJobActive,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.PlayArrow, null)
                Text("Upload & print", Modifier.padding(start = 8.dp))
            }
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
                    modifier = Modifier.clickable { vm.openPrintSheet(f, ui.storage) },
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

/**
 * Print sheet for one file: remembered settings, CANVAS trays matched automatically (each can
 * be changed), and anything that needs a look before the print starts.
 */
@Composable
internal fun PrintDialog(vm: PrinterViewModel, state: PrinterState, prompt: PrintPrompt, onDismiss: () -> Unit) {
    val f = prompt.file
    val saved by vm.printPrefs.collectAsState()
    var prefs by remember(f) { mutableStateOf(saved) }
    var confirmDelete by remember { mutableStateOf(false) }
    val trays = state.filament?.hubs.orEmpty().flatMap { it.slots }.filter { it.present }
    // Tools whose tray the user picked by hand; the rest follow the automatic match.
    val chosen = remember(f) { mutableStateMapOf<Int, FilamentSlot>() }
    val plan = PrintPlanner.plan(f, prompt.storage, state, prefs, chosen.toMap())

    LaunchedEffect(f.name) { vm.loadThumbnail(prompt.storage, f.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(f.name, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        SegmentedButton(selected = prefs.plate == p, onClick = { prefs = prefs.copy(plate = p) }, shape = SegmentedButtonDefaults.itemShape(i, 2)) {
                            Text(label, maxLines = 1)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Auto bed leveling", Modifier.weight(1f))
                    Switch(prefs.autoBedLeveling, { prefs = prefs.copy(autoBedLeveling = it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Record timelapse", Modifier.weight(1f))
                    Switch(prefs.timelapse, { prefs = prefs.copy(timelapse = it) })
                }
                if (trays.isNotEmpty() && f.colors.isNotEmpty()) {
                    Text("CANVAS trays", style = MaterialTheme.typography.titleSmall)
                    f.colors.distinctBy { it.tool }.forEach { c ->
                        SlotPicker(c.tool, c.color, c.material, trays, plan.slots[c.tool]) { chosen[c.tool] = it }
                    }
                }
                plan.issues.forEach { issue ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val tint = if (issue.blocking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
                        Icon(if (issue.blocking) Icons.Default.Block else Icons.Default.WarningAmber, null, tint = tint, modifier = Modifier.size(18.dp))
                        Text(issue.message, style = MaterialTheme.typography.bodySmall, color = tint)
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = !plan.blocked, onClick = { vm.startPrint(plan.command, prefs) }) { Text("Print") }
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
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDismiss(); vm.deleteFile(prompt.storage, f.name) }) { Text("Delete") } },
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
