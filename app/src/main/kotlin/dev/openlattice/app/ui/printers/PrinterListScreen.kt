package dev.openlattice.app.ui.printers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import dev.openlattice.app.monitor.PrintMonitorService
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.openlattice.app.data.SavedPrinter
import dev.openlattice.app.ui.PendingShare
import dev.openlattice.app.ui.StatusLine
import dev.openlattice.app.ui.app
import dev.openlattice.app.ui.formatDuration
import dev.openlattice.core.PrinterState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterListScreen(onAdd: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val printers by context.app.store.printers.collectAsState(initial = null)
    val pending by PendingShare.uri.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Printers") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add printer") })
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (pending != null) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.UploadFile, null)
                            Text("Choose a printer to send the shared file to", Modifier.weight(1f).padding(horizontal = 12.dp))
                            TextButton(onClick = { PendingShare.uri.value = null }) { Text("Cancel") }
                        }
                    }
                }
            }
            val list = printers
            if (list != null && list.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No printers yet", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Turn on LAN Only Mode on the printer (Settings → Network), then add it here.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            items(list.orEmpty(), key = { it.id }) { p -> PrinterCard(p, onClick = { onOpen(p.id) }) }
        }
    }
}

@Composable
private fun PrinterCard(printer: SavedPrinter, onClick: () -> Unit) {
    val context = LocalContext.current
    val connections = context.app.connections
    val driver = remember(printer) { connections.acquire(printer) }
    DisposableEffect(printer) { onDispose { connections.release(printer.id) } }
    val state by driver.state.collectAsState()
    LaunchedEffect(state.machine.isJobActive) { if (state.machine.isJobActive) PrintMonitorService.start(context) }

    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(printer.name.ifEmpty { printer.model }, style = MaterialTheme.typography.titleMedium)
            Text("${printer.model} · ${printer.host}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            StatusLine(state)
            JobSummary(state)
        }
    }
}

@Composable
private fun JobSummary(state: PrinterState) {
    val job = state.job ?: return
    Text(job.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
    LinearProgressIndicator(progress = { job.progress / 100f }, modifier = Modifier.fillMaxWidth())
    Text("${job.progress}% · ${formatDuration(job.remainingSec)} left", style = MaterialTheme.typography.bodySmall)
}
