package dev.openlattice.app.ui.printers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.openlattice.app.data.SavedPrinter
import dev.openlattice.app.monitor.PrintMonitorService
import dev.openlattice.app.ui.PendingShare
import dev.openlattice.app.ui.app
import dev.openlattice.app.ui.displayName
import dev.openlattice.app.ui.components.Panel
import dev.openlattice.app.ui.components.ThickProgress
import dev.openlattice.app.ui.components.look
import dev.openlattice.app.ui.formatDuration
import dev.openlattice.app.ui.theme.Lattice
import dev.openlattice.app.ui.theme.Readout
import dev.openlattice.core.ConnectionState
import dev.openlattice.core.PrinterState

@Composable
fun PrinterListScreen(onAdd: () -> Unit, onOpen: (String) -> Unit, onAppearance: () -> Unit) {
    val context = LocalContext.current
    val printers by context.app.store.printers.collectAsState(initial = null)
    val pending by PendingShare.uri.collectAsState()

    PrinterListContent(
        printers = printers,
        sharing = pending != null,
        onAdd = onAdd,
        onAppearance = onAppearance,
        onCancelShare = { PendingShare.uri.value = null },
        card = { p ->
            PrinterCard(
                p,
                sharing = pending != null,
                onClick = { onOpen(p.id) },
                onSendAndPrint = {
                    PendingShare.printAfterUpload.value = true
                    onOpen(p.id)
                },
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterListContent(
    printers: List<SavedPrinter>?,
    sharing: Boolean,
    onAdd: () -> Unit,
    onAppearance: () -> Unit,
    onCancelShare: () -> Unit,
    card: @Composable (SavedPrinter) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Printers", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)) },
                actions = {
                    IconButton(onClick = onAppearance) { Icon(Icons.Outlined.Palette, "Appearance") }
                    IconButton(onClick = onAdd) { Icon(Icons.Default.Add, "Add printer") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (sharing) {
                item {
                    Panel(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.UploadFile, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(
                                "Choose a printer for the shared file. Send & print starts it once uploaded, unless the filament needs a look.",
                                Modifier.weight(1f).padding(horizontal = 12.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            TextButton(onClick = onCancelShare) { Text("Cancel") }
                        }
                    }
                }
            }
            if (printers != null && printers.isEmpty()) {
                item {
                    Panel(Modifier.fillMaxWidth()) {
                        Text("No printers yet", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Turn on LAN Only Mode on the printer (Settings → Network), then add it here.",
                            color = Lattice.colors.muted,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Add printer") }
                    }
                }
            }
            items(printers.orEmpty(), key = { it.id }) { card(it) }
            item {
                Text(
                    "LAN only. Nothing leaves your network.",
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    color = Lattice.colors.muted,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun PrinterCard(printer: SavedPrinter, sharing: Boolean, onClick: () -> Unit, onSendAndPrint: () -> Unit) {
    val context = LocalContext.current
    val connections = context.app.connections
    val driver = remember(printer) { connections.acquire(printer) }
    DisposableEffect(printer) { onDispose { connections.release(printer.id) } }
    val state by driver.state.collectAsState()
    LaunchedEffect(state.machine.isJobActive) { if (state.machine.isJobActive) PrintMonitorService.start(context) }
    PrinterCardContent(printer, state, sharing, onClick, onSendAndPrint)
}

@Composable
fun PrinterCardContent(printer: SavedPrinter, state: PrinterState, sharing: Boolean, onClick: () -> Unit, onSendAndPrint: () -> Unit) {
    val look = state.look()
    Panel(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(printer.name.ifEmpty { printer.model }, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${printer.model} · ${printer.host}", color = Lattice.colors.muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
            Text(look.label, Modifier.padding(start = 12.dp, top = 4.dp), color = look.color, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
        }
        val job = state.job
        if (job != null) {
            Spacer(Modifier.height(14.dp))
            Text(displayName(job.fileName), color = Lattice.colors.muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${job.progress}", style = Readout)
                Text("%", Modifier.padding(start = 2.dp, bottom = 5.dp), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (state.machine.isJobActive) {
                    Text("${formatDuration(job.remainingSec)} left", Modifier.padding(bottom = 5.dp), color = Lattice.colors.muted)
                }
            }
            Spacer(Modifier.height(6.dp))
            ThickProgress(job.progress / 100f, color = look.color)
        } else if (state.connection == ConnectionState.Connected) {
            Spacer(Modifier.height(12.dp))
            val temps = listOfNotNull(
                state.nozzle?.let { "Nozzle %.0f°".format(it.current) },
                state.bed?.let { "Bed %.0f°".format(it.current) },
            ).joinToString("  ·  ")
            if (temps.isNotEmpty()) Text(temps, color = Lattice.colors.muted, style = MaterialTheme.typography.bodyMedium)
        }
        if (sharing) {
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onClick, modifier = Modifier.weight(1f).height(48.dp)) { Text("Send") }
                Button(
                    onClick = onSendAndPrint,
                    enabled = state.connection == ConnectionState.Connected && !state.machine.isJobActive,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Text("Send & print", Modifier.padding(start = 6.dp), maxLines = 1)
                }
            }
        }
    }
}
