package dev.openlattice.app.ui.printer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.openlattice.app.ui.components.ColorDot
import dev.openlattice.app.ui.components.Panel
import dev.openlattice.app.ui.components.SectionHeader
import dev.openlattice.app.ui.components.SettingsGroup
import dev.openlattice.app.ui.components.SettingsRow
import dev.openlattice.app.ui.components.Spool
import dev.openlattice.app.ui.theme.Lattice
import dev.openlattice.core.FilamentMaterial
import dev.openlattice.core.FilamentSlot
import dev.openlattice.core.PrinterCommand
import dev.openlattice.core.PrinterState

private data class Confirm(val title: String, val text: String, val command: PrinterCommand, val done: String)

/** Filament sub-page of a printer: trays, external spool, auto refill; sheets to load, unload and edit. */
@Composable
fun FilamentPage(vm: PrinterViewModel, state: PrinterState, onBack: () -> Unit, snackbar: SnackbarHostState) {
    var tray by remember { mutableStateOf<FilamentSlot?>(null) }
    var editing by remember { mutableStateOf<FilamentSlot?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val locked = state.machine.isJobActive

    FilamentContent(
        state = state,
        onBack = onBack,
        onTray = { tray = it },
        onAutoRefill = { vm.send(PrinterCommand.SetAutoRefill(it)) },
        snackbar = snackbar,
    )

    tray?.let { slot ->
        TraySheet(
            slot = slot,
            locked = locked,
            onDismiss = { tray = null },
            onEdit = { tray = null; editing = slot },
            onLoad = {
                tray = null
                confirm = if (slot.hubId < 0) {
                    Confirm("Load filament?", "The nozzle heats up and pulls filament in from the external spool.", PrinterCommand.LoadFilament, "Loading filament")
                } else {
                    val label = "tray ${slot.hubId + 1}-${slot.trayId + 1}"
                    Confirm("Load $label?", "The nozzle heats up and feeds this tray's filament.", PrinterCommand.LoadTray(slot.hubId, slot.trayId), "Loading $label")
                }
            },
            onUnload = {
                tray = null
                confirm = if (slot.hubId < 0) {
                    Confirm("Unload filament?", "The nozzle heats up and pushes the filament out.", PrinterCommand.UnloadFilament, "Unloading filament")
                } else {
                    val label = "tray ${slot.hubId + 1}-${slot.trayId + 1}"
                    Confirm("Unload $label?", "The filament is retracted back into the CANVAS.", PrinterCommand.UnloadTray(slot.hubId, slot.trayId), "Unloading $label")
                }
            },
        )
    }

    editing?.let { slot ->
        val trayColors = state.filament?.hubs.orEmpty().flatMap { it.slots }.filter { it.present && it.color != null }
            .map { "${it.hubId + 1}-${it.trayId + 1}" to it.color!! }
        FilamentEditorSheet(slot, trayColors, onDismiss = { editing = null }) { material, color ->
            editing = null
            // Keep the spool brand unless it is unknown.
            vm.send(PrinterCommand.SetFilament(slot.hubId, slot.trayId, material, color, slot.brand.ifBlank { "Generic" }), success = "Filament updated")
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilamentContent(
    state: PrinterState,
    onBack: () -> Unit,
    onTray: (FilamentSlot) -> Unit,
    onAutoRefill: (Boolean) -> Unit,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val fs = state.filament
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Filament") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (fs == null) {
                Text("No filament information yet.", color = Lattice.colors.muted)
                return@Column
            }
            if (state.machine.isJobActive) {
                Panel(Modifier.fillMaxWidth(), color = Lattice.colors.tile) {
                    Text("Loading and unloading are disabled while printing. You can still edit what each tray holds.", color = Lattice.colors.muted)
                }
            }
            if (fs.hubs.isNotEmpty()) {
                SectionHeader("CANVAS (${fs.hubs.size})")
                fs.hubs.forEach { hub ->
                    Panel(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("CANVAS ${hub.id + 1}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            hub.humidity?.let { HumidityPill(it) }
                        }
                        Spacer(Modifier.height(12.dp))
                        SpoolRow(hub.slots, onTray = onTray)
                    }
                }
                SettingsGroup {
                    SettingsRow(
                        "Auto refill",
                        subtitle = "Switch to a matching tray when one runs out.",
                        trailing = { Switch(fs.autoRefill, onAutoRefill) },
                    )
                }
            }
            fs.external?.let { ext ->
                SectionHeader("External spool")
                Panel(Modifier.fillMaxWidth(), onClick = { onTray(ext) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Spool(ext.color?.let(::parseColor), "Ext", active = ext.active, description = "External spool")
                        Column(Modifier.weight(1f)) {
                            Text(slotTitle(ext), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
                            if (ext.maxNozzleTemp > 0) {
                                Text("${ext.minNozzleTemp}–${ext.maxNozzleTemp} °C", color = Lattice.colors.muted, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (ext.active) Text("In use", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Lattice.colors.muted)
                    }
                }
            }
        }
    }
}

private fun slotTitle(s: FilamentSlot) =
    if (!s.present) "Empty" else listOf(s.brand, s.name.ifEmpty { s.material }).filter { it.isNotBlank() }.joinToString(" ").ifBlank { s.material.ifBlank { "Unknown filament" } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TraySheet(slot: FilamentSlot, locked: Boolean, onDismiss: () -> Unit, onEdit: () -> Unit, onLoad: () -> Unit, onUnload: () -> Unit) {
    val external = slot.hubId < 0
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Spool(if (slot.present) slot.color?.let(::parseColor) else null, if (external) "Ext" else "${slot.hubId + 1}-${slot.trayId + 1}", active = slot.active)
                Column(Modifier.weight(1f)) {
                    Text(if (external) "External spool" else "CANVAS ${slot.hubId + 1} · tray ${slot.trayId + 1}", style = MaterialTheme.typography.titleLarge)
                    Text(slotTitle(slot), style = MaterialTheme.typography.bodyLarge)
                    if (slot.present && slot.maxNozzleTemp > 0) {
                        Text("${slot.material} · ${slot.minNozzleTemp}–${slot.maxNozzleTemp} °C", color = Lattice.colors.muted, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (slot.active) Text("In use", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                }
            }
            FilledTonalButton(onClick = onEdit, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Edit filament") }
            val canLoad = external || (slot.present && !slot.active)
            val canUnload = external || slot.active
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (canLoad) OutlinedButton(onClick = onLoad, enabled = !locked, modifier = Modifier.weight(1f).height(52.dp)) { Text("Load") }
                if (canUnload) OutlinedButton(onClick = onUnload, enabled = !locked, modifier = Modifier.weight(1f).height(52.dp)) { Text("Unload") }
            }
            if (locked) Text("Loading and unloading are disabled while printing.", color = Lattice.colors.muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private val SWATCHES = listOf(
    "#FFFFFF", "#000000", "#9E9E9E", "#D0D0D0", "#FFE600", "#D9F26B", "#00C040",
    "#0B7A4B", "#155E7D", "#00D69B", "#7ADDF2", "#3EA3FF", "#2350E0", "#4B2E9A",
    "#9B30F0", "#F030F0", "#D3B0DD", "#F2607A", "#F02020", "#FF8000", "#7A4A00",
)

/** Edit what a tray holds: material, then colour from the trays' colours, a grid, or a hex code. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilamentEditorSheet(
    slot: FilamentSlot,
    trayColors: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onSave: (FilamentMaterial, String) -> Unit,
) {
    var material by remember { mutableStateOf(FilamentMaterial.fromLabel(slot.material) ?: FilamentMaterial.PLA) }
    var color by remember { mutableStateOf(slot.color ?: "#FFFFFF") }
    var hex by remember { mutableStateOf(color) }
    var open by remember { mutableStateOf(false) }
    val title = if (slot.hubId < 0) "External spool" else "CANVAS ${slot.hubId + 1} · tray ${slot.trayId + 1}"

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Edit filament", style = MaterialTheme.typography.titleLarge)
            Text(title, color = Lattice.colors.muted)
            SettingsGroup {
                Box {
                    SettingsRow("Type", value = material.label, onClick = { open = true })
                    DropdownMenu(open, { open = false }) {
                        FilamentMaterial.entries.forEach { m ->
                            DropdownMenuItem(text = { Text("${m.label}  (${m.minTemp}–${m.maxTemp} °C)") }, onClick = { material = m; open = false })
                        }
                    }
                }
                Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp).fillMaxWidth().clip(MaterialTheme.shapes.small).background(Lattice.colors.tile).padding(14.dp)) {
                    Text("Nozzle temperature: ${material.minTemp}–${material.maxTemp} °C", color = Lattice.colors.muted)
                }
            }
            Panel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Colour", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
                    Text(color, Modifier.padding(end = 10.dp), style = MaterialTheme.typography.bodyLarge)
                    ColorDot(parseColor(color), size = 24.dp)
                }
                Spacer(Modifier.height(16.dp))
                val cells = trayColors.map { (label, c) -> c to label } + SWATCHES.map { it to null }
                cells.distinctBy { (c, label) -> c.uppercase() + (label ?: "") }.chunked(7).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        row.forEach { (c, label) -> SwatchButton(c, label, selected = c.equals(color, ignoreCase = true)) { color = c.uppercase(); hex = c.uppercase() } }
                        repeat(7 - row.size) { Spacer(Modifier.size(40.dp)) }
                    }
                }
                OutlinedTextField(
                    value = hex,
                    onValueChange = { v ->
                        hex = v.take(7)
                        if (Regex("^#[0-9a-fA-F]{6}$").matches(hex)) color = hex.uppercase()
                    },
                    label = { Text("Hex colour (#RRGGBB)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(onClick = { onSave(material, color) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Save") }
        }
    }
}

/** One colour in the grid; tray colours carry their tray number. */
@Composable
private fun SwatchButton(hex: String, label: String?, selected: Boolean, onClick: () -> Unit) {
    val c = parseColor(hex)
    val ring = MaterialTheme.colorScheme.primary
    Box(
        Modifier.size(40.dp)
            .then(if (selected) Modifier.border(2.dp, ring, CircleShape).padding(4.dp) else Modifier)
            .clip(CircleShape).background(c).border(1.dp, Lattice.colors.divider, CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = (label?.let { "Tray $it colour " } ?: "Colour ") + hex },
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) Text(label, color = if (c.luminance() > 0.45f) Color(0xFF111111) else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}
