package dev.openlattice.app.ui.printer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.openlattice.core.FilamentSlot
import dev.openlattice.core.PrinterCommand
import dev.openlattice.core.PrinterState

@Composable
fun FilamentTab(vm: PrinterViewModel, state: PrinterState) {
    val fs = state.filament
    var confirm by remember { mutableStateOf<PrinterCommand?>(null) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (fs == null) {
            Text("No filament information yet.")
            return@Column
        }
        fs.external?.let { slot ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("External spool", style = MaterialTheme.typography.titleSmall)
                    SlotRow(slot)
                    val locked = state.machine.isJobActive
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(enabled = !locked, onClick = { confirm = PrinterCommand.LoadFilament }) { Text("Load") }
                        OutlinedButton(enabled = !locked, onClick = { confirm = PrinterCommand.UnloadFilament }) { Text("Unload") }
                    }
                    if (locked) Text("Loading is disabled while printing.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (fs.hubs.isEmpty()) {
            Text("No CANVAS connected.", style = MaterialTheme.typography.bodySmall)
        } else {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto refill", style = MaterialTheme.typography.titleSmall)
                        Text("Switch to a matching spool when one runs out", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(fs.autoRefill, { vm.send(PrinterCommand.SetAutoRefill(it)) })
                }
            }
        }
        fs.hubs.forEach { hub ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "CANVAS ${hub.id + 1}" + (hub.humidity?.let { " · humidity %.0f%%".format(it) } ?: ""),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    hub.slots.forEach { SlotRow(it) }
                }
            }
        }
    }

    confirm?.let { cmd ->
        val load = cmd == PrinterCommand.LoadFilament
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (load) "Load filament?" else "Unload filament?") },
            text = { Text(if (load) "The nozzle heats up and pulls filament in from the external spool." else "The nozzle heats up and pushes the filament out.") },
            confirmButton = { TextButton(onClick = { confirm = null; vm.send(cmd, success = if (load) "Loading filament" else "Unloading filament") }) { Text(if (load) "Load" else "Unload") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SlotRow(slot: FilamentSlot) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(parseColor(slot.color)))
        Column(Modifier.weight(1f)) {
            Text(
                if (!slot.present) "Empty" else listOf(slot.brand, slot.name.ifEmpty { slot.material }).filter { it.isNotBlank() }.joinToString(" "),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (slot.present && slot.maxNozzleTemp > 0) {
                Text("${slot.material} · ${slot.minNozzleTemp}–${slot.maxNozzleTemp} °C", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (slot.active) Text("In use", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
    }
}
