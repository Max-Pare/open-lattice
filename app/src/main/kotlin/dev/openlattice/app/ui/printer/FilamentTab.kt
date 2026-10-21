package dev.openlattice.app.ui.printer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import dev.openlattice.core.FilamentMaterial
import dev.openlattice.core.FilamentSlot
import dev.openlattice.core.PrinterCommand
import dev.openlattice.core.PrinterState

private data class Confirm(val title: String, val text: String, val command: PrinterCommand, val done: String)

@Composable
fun FilamentTab(vm: PrinterViewModel, state: PrinterState) {
    val fs = state.filament
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var editing by remember { mutableStateOf<FilamentSlot?>(null) }
    val locked = state.machine.isJobActive

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (fs == null) {
            Text("No filament information yet.")
            return@Column
        }
        if (locked) Text("Loading and unloading are disabled while printing.", style = MaterialTheme.typography.bodySmall)

        fs.external?.let { slot ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("External spool", style = MaterialTheme.typography.titleSmall)
                    SlotRow(slot, onEdit = { editing = slot })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(enabled = !locked, onClick = {
                            confirm = Confirm("Load filament?", "The nozzle heats up and pulls filament in from the external spool.", PrinterCommand.LoadFilament, "Loading filament")
                        }) { Text("Load") }
                        OutlinedButton(enabled = !locked, onClick = {
                            confirm = Confirm("Unload filament?", "The nozzle heats up and pushes the filament out.", PrinterCommand.UnloadFilament, "Unloading filament")
                        }) { Text("Unload") }
                    }
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
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "CANVAS ${hub.id + 1}" + (hub.humidity?.let { " · humidity %.0f%%".format(it) } ?: ""),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    hub.slots.forEach { slot ->
                        SlotRow(slot, onEdit = { editing = slot }) {
                            val label = "slot ${slot.trayId + 1}"
                            if (slot.active) {
                                TextButton(enabled = !locked, onClick = {
                                    confirm = Confirm("Unload $label?", "The filament is retracted back into the CANVAS.", PrinterCommand.UnloadTray(slot.hubId, slot.trayId), "Unloading $label")
                                }) { Text("Unload") }
                            } else if (slot.present) {
                                TextButton(enabled = !locked, onClick = {
                                    confirm = Confirm("Load $label?", "The nozzle heats up and feeds this slot's filament.", PrinterCommand.LoadTray(slot.hubId, slot.trayId), "Loading $label")
                                }) { Text("Load") }
                            }
                        }
                    }
                }
            }
        }
    }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(c.title) },
            text = { Text(c.text) },
            confirmButton = { TextButton(onClick = { confirm = null; vm.send(c.command, success = c.done) }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }

    editing?.let { slot ->
        FilamentEditor(slot, onDismiss = { editing = null }) { material, color ->
            editing = null
            // Keep the spool brand unless it is unknown.
            vm.send(PrinterCommand.SetFilament(slot.hubId, slot.trayId, material, color, slot.brand.ifBlank { "Generic" }), success = "Filament updated")
        }
    }
}

@Composable
private fun SlotRow(slot: FilamentSlot, onEdit: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.weight(1f).clickable(onClick = onEdit), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Swatch(slot.color, 28)
            Column {
                Text(
                    if (!slot.present) "Empty" else listOf(slot.brand, slot.name.ifEmpty { slot.material }).filter { it.isNotBlank() }.joinToString(" "),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (slot.present && slot.maxNozzleTemp > 0) {
                    Text("${slot.material} · ${slot.minNozzleTemp}–${slot.maxNozzleTemp} °C", style = MaterialTheme.typography.bodySmall)
                }
                if (slot.active) Text("In use", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
        }
        actions()
    }
}

@Composable
private fun Swatch(color: String?, sizeDp: Int, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    var m = Modifier.size(sizeDp.dp).clip(CircleShape).background(parseColor(color))
        .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Box(m)
}

private val PALETTE = listOf(
    "#FFFFFF", "#000000", "#808080", "#C0C0C0", "#FF0000", "#FF8000", "#FFD700", "#00A000",
    "#00C0C0", "#0050FF", "#8000FF", "#FF60C0", "#8B4513", "#F5DEB3", "#2F4F4F", "#00FF80",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilamentEditor(slot: FilamentSlot, onDismiss: () -> Unit, onSave: (FilamentMaterial, String) -> Unit) {
    var material by remember { mutableStateOf(FilamentMaterial.fromLabel(slot.material) ?: FilamentMaterial.PLA) }
    var color by remember { mutableStateOf(slot.color ?: "#FFFFFF") }
    var hex by remember { mutableStateOf(color) }
    var open by remember { mutableStateOf(false) }
    val title = if (slot.hubId < 0) "External spool" else "CANVAS ${slot.hubId + 1} · slot ${slot.trayId + 1}"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box {
                    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("${material.label} · ${material.minTemp}–${material.maxTemp} °C")
                    }
                    DropdownMenu(open, { open = false }) {
                        FilamentMaterial.entries.forEach { m ->
                            DropdownMenuItem(text = { Text("${m.label}  (${m.minTemp}–${m.maxTemp} °C)") }, onClick = { material = m; open = false })
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PALETTE.forEach { c -> Swatch(c, 32, selected = c.equals(color, ignoreCase = true)) { color = c; hex = c } }
                }
                OutlinedTextField(
                    value = hex,
                    onValueChange = { v ->
                        hex = v.take(7)
                        if (Regex("^#[0-9a-fA-F]{6}$").matches(hex)) color = hex.uppercase()
                    },
                    label = { Text("Colour (#RRGGBB)") },
                    singleLine = true,
                    leadingIcon = { Swatch(color, 20) },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(material, color) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
