package dev.openlattice.app.ui.printer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.openlattice.app.ui.components.GroupDivider
import dev.openlattice.app.ui.components.SettingsGroup
import dev.openlattice.app.ui.components.SettingsRow
import dev.openlattice.app.ui.components.Thumb
import dev.openlattice.app.ui.formatDuration
import dev.openlattice.app.ui.theme.Lattice
import dev.openlattice.core.HistoryEntry
import dev.openlattice.core.HistoryResult
import dev.openlattice.core.Storage
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryTab(vm: PrinterViewModel, printerBusy: Boolean) {
    val ui by vm.history.collectAsState()
    var open by remember { mutableStateOf<HistoryEntry?>(null) }
    var deleting by remember { mutableStateOf<HistoryEntry?>(null) }
    LaunchedEffect(Unit) { vm.loadHistory() }

    HistoryContent(
        ui = ui,
        thumbnails = vm.thumbnails,
        onVisible = { vm.loadThumbnail(Storage.LOCAL, it.fileName) },
        onOpen = { open = it },
    )

    open?.let { e ->
        HistorySheet(
            entry = e,
            thumb = vm.thumbnails[e.fileName],
            canPrint = !printerBusy,
            onDismiss = { open = null },
            onPrintAgain = { open = null; vm.reprint(e.fileName) },
            onDelete = { open = null; deleting = e },
        )
    }
    deleting?.let { e ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete history entry?") },
            text = { Text(if (e.timelapse != null) "${e.fileName}\n\nIts timelapse video is deleted with it." else e.fileName) },
            confirmButton = { TextButton(onClick = { deleting = null; vm.deleteHistory(e.taskId) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun HistoryContent(
    ui: HistoryUi,
    thumbnails: Map<String, ImageBitmap?>,
    onVisible: (HistoryEntry) -> Unit,
    onOpen: (HistoryEntry) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        when {
            ui.loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            ui.error != null -> Text(ui.error, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            ui.entries.isEmpty() -> Text("No print history on the printer.", Modifier.padding(16.dp), color = Lattice.colors.muted)
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(ui.entries, key = { it.taskId }) { e ->
                LaunchedEffect(e.fileName) { onVisible(e) }
                HistoryRow(e, thumbnails[e.fileName], onClick = { onOpen(e) })
            }
        }
    }
}

@Composable
private fun HistoryRow(e: HistoryEntry, thumb: ImageBitmap?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Thumb(thumb, 112.dp)
        Column(Modifier.weight(1f).height(112.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                val (label, color) = e.result.look()
                Text(label, color = color, style = MaterialTheme.typography.bodyMedium)
                Text(displayName(e.fileName), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Column {
                e.durationSec()?.let { d ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Outlined.Schedule, null, Modifier.size(16.dp), tint = Lattice.colors.muted)
                        Text(formatDuration(d), color = Lattice.colors.muted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(formatDate(e.startedAtEpochSec), color = Lattice.colors.muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun HistoryResult.look(): Pair<String, Color> = when (this) {
    HistoryResult.COMPLETED -> "Done" to Lattice.colors.ok
    HistoryResult.STOPPED -> "Stopped" to MaterialTheme.colorScheme.error
    HistoryResult.IN_PROGRESS -> "Printing" to MaterialTheme.colorScheme.primary
    HistoryResult.UNKNOWN -> "Unknown" to Lattice.colors.muted
}

private fun HistoryEntry.durationSec(): Long? = endedAtEpochSec?.let { it - startedAtEpochSec }?.takeIf { it > 0 }

private fun formatDate(epochSec: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochSec * 1000))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(entry: HistoryEntry, thumb: ImageBitmap?, canPrint: Boolean, onDismiss: () -> Unit, onPrintAgain: () -> Unit, onDelete: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Thumb(thumb, 120.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val (label, color) = entry.result.look()
                    Text(label, color = color, style = MaterialTheme.typography.titleLarge)
                    Text(displayName(entry.fileName), style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            SettingsGroup {
                SettingsRow("Started", value = formatDate(entry.startedAtEpochSec))
                entry.endedAtEpochSec?.let { GroupDivider(); SettingsRow("Ended", value = formatDate(it)) }
                entry.durationSec()?.let { GroupDivider(); SettingsRow("Duration", value = formatDuration(it)) }
                GroupDivider()
                SettingsRow(
                    "Timelapse",
                    value = when {
                        entry.timelapse == null -> "None"
                        entry.timelapse?.ready == true -> "On the printer"
                        else -> "Being made"
                    },
                )
            }
            Button(onClick = onPrintAgain, enabled = canPrint, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Print again") }
            if (!canPrint) Text("A print is running; print this again when it finishes.", color = Lattice.colors.muted, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("Delete from history", color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(4.dp))
        }
    }
}
