package dev.openlattice.app.ui.printer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.openlattice.app.ui.formatDuration
import dev.openlattice.core.HistoryEntry
import dev.openlattice.core.HistoryResult
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryTab(vm: PrinterViewModel) {
    val ui by vm.history.collectAsState()
    var deleting by remember { mutableStateOf<HistoryEntry?>(null) }
    LaunchedEffect(Unit) { vm.loadHistory() }

    Column(Modifier.fillMaxSize()) {
        when {
            ui.loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            ui.error != null -> Text(ui.error!!, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            ui.entries.isEmpty() -> Text("No print history on the printer", Modifier.padding(16.dp))
        }
        LazyColumn {
            items(ui.entries, key = { it.taskId }) { e ->
                ListItem(
                    leadingContent = {
                        Icon(
                            when (e.result) {
                                HistoryResult.COMPLETED -> Icons.Default.CheckCircle
                                HistoryResult.STOPPED -> Icons.Default.Cancel
                                HistoryResult.IN_PROGRESS -> Icons.Default.Print
                                HistoryResult.UNKNOWN -> Icons.AutoMirrored.Filled.Help
                            },
                            null,
                            tint = when (e.result) {
                                HistoryResult.COMPLETED -> MaterialTheme.colorScheme.tertiary
                                HistoryResult.STOPPED -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    headlineContent = { Text(e.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        val start = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.startedAtEpochSec * 1000))
                        val duration = e.endedAtEpochSec?.let { " · ${formatDuration(it - e.startedAtEpochSec)}" }.orEmpty()
                        val video = e.timelapse?.let { if (it.ready) " · timelapse" else " · timelapse pending" }.orEmpty()
                        Text(start + duration + video)
                    },
                    trailingContent = {
                        Box {
                            if (e.timelapse?.ready == true) Icon(Icons.Default.Movie, "Timelapse", Modifier.padding(end = 48.dp))
                            IconButton(onClick = { deleting = e }) { Icon(Icons.Default.Delete, "Delete") }
                        }
                    },
                )
            }
        }
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
