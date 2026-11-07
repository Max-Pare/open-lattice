package dev.openlattice.app.ui.printer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.openlattice.app.ui.displayName
import dev.openlattice.app.ui.components.ControlTile
import dev.openlattice.app.ui.components.Panel
import dev.openlattice.app.ui.components.SectionHeader
import dev.openlattice.app.ui.components.SettingsGroup
import dev.openlattice.app.ui.components.SettingsRow
import dev.openlattice.app.ui.components.GroupDivider
import dev.openlattice.app.ui.components.Spool
import dev.openlattice.app.ui.components.ThickProgress
import dev.openlattice.app.ui.components.Thumb
import dev.openlattice.app.ui.components.look
import dev.openlattice.app.ui.formatDuration
import dev.openlattice.app.ui.theme.Lattice
import dev.openlattice.app.ui.theme.Readout
import dev.openlattice.core.ConnectionState
import dev.openlattice.core.Fan
import dev.openlattice.core.FilamentSlot
import dev.openlattice.core.MachineState
import dev.openlattice.core.PausedReason
import dev.openlattice.core.PrinterCommand
import dev.openlattice.core.PrinterState
import dev.openlattice.core.SpeedMode
import dev.openlattice.core.Storage
import dev.openlattice.core.Temperature

/** Everything the device home can ask for; plain callbacks so the screen renders without a printer. */
class DeviceActions(
    val retry: () -> Unit = {},
    val openCamera: () -> Unit = {},
    val send: (PrinterCommand) -> Unit = {},
    val openMove: () -> Unit = {},
    val openFilament: () -> Unit = {},
    val openFiles: () -> Unit = {},
    val dismissError: (Int) -> Unit = {},
)

@Composable
fun DeviceTab(vm: PrinterViewModel, state: PrinterState, onCamera: () -> Unit, onMove: () -> Unit, onFilament: () -> Unit, onFiles: () -> Unit) {
    val dismissed by vm.dismissedErrors.collectAsState()
    val jobFile = state.job?.fileName
    if (jobFile != null) androidx.compose.runtime.LaunchedEffect(jobFile) { vm.loadThumbnail(Storage.LOCAL, jobFile) }
    DeviceHome(
        state = state,
        jobThumb = jobFile?.let { vm.thumbnails[it] },
        dismissedErrors = dismissed,
        camera = { CameraView(vm::cameraUrl, Modifier.fillMaxWidth().aspectRatio(16f / 9f)) },
        actions = DeviceActions(
            retry = vm::retryConnection,
            openCamera = onCamera,
            send = { vm.send(it) },
            openMove = onMove,
            openFilament = onFilament,
            openFiles = onFiles,
            dismissError = vm::dismissError,
        ),
    )
}

@Composable
fun DeviceHome(
    state: PrinterState,
    jobThumb: ImageBitmap?,
    dismissedErrors: Set<Int>,
    camera: @Composable () -> Unit,
    actions: DeviceActions,
) {
    val connected = state.connection == ConnectionState.Connected
    var editingTemp by remember { mutableStateOf<String?>(null) }
    var fans by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(false) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val conn = state.connection
        if (conn is ConnectionState.Failed) {
            Panel(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    if (conn.authRejected) "The printer rejected the access code. Update it under Settings." else conn.reason,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = actions.retry) { Text("Retry now") }
            }
        }

        if (connected) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium).clickable(onClick = actions.openCamera)) { camera() }
        }

        val uri = LocalUriHandler.current
        state.exceptions.filter { it.code !in dismissedErrors }.forEach { e ->
            Panel(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer, padding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, top = 8.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Column(Modifier.weight(1f)) {
                        Text("Error ${e.code}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text(e.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                        e.helpUrl?.let { url ->
                            TextButton(onClick = { uri.openUri(url) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("How to fix (ELEGOO wiki)") }
                        }
                    }
                    IconButton(onClick = { actions.dismissError(e.code) }) { Icon(Icons.Default.Close, "Dismiss", tint = MaterialTheme.colorScheme.onErrorContainer) }
                }
            }
        }

        if (state.job != null) JobPanel(state, jobThumb, actions) else if (connected) ReadyPanel(actions.openFiles)

        SectionHeader("Device control")
        Panel(Modifier.fillMaxWidth()) {
            Text("Nozzle & bed", style = MaterialTheme.typography.bodyLarge, color = Lattice.colors.muted)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                TempReadout("Nozzle", state.nozzle, Modifier.weight(1f), onClick = { editingTemp = "Nozzle" }.takeIf { connected })
                TempReadout("Bed", state.bed, Modifier.weight(1f), onClick = { editingTemp = "Bed" }.takeIf { connected })
                TempReadout("Chamber", state.chamber, Modifier.weight(1f), onClick = null)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val locked = state.machine.isJobActive
            ControlTile(
                "Movement", if (locked) "Locked" else "XYZ", Modifier.weight(1f),
                enabled = connected && !locked, onClick = actions.openMove,
            )
            ControlTile(
                "Light", when (state.lightOn) { true -> "On"; false -> "Off"; null -> "–" }, Modifier.weight(1f),
                trailing = {
                    Switch(
                        checked = state.lightOn == true,
                        onCheckedChange = { actions.send(PrinterCommand.SetLight(it)) },
                        enabled = connected && state.lightOn != null,
                    )
                },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ControlTile(
                "Fans", state.fans[Fan.MODEL]?.let { "Part $it%" } ?: "–", Modifier.weight(1f),
                enabled = connected && state.fans.isNotEmpty(), onClick = { fans = true },
            )
            val canSpeed = connected && state.machine.isJobActive && state.speedMode != null
            ControlTile(
                "Speed", if (canSpeed) state.speedMode!!.label() else "While printing", Modifier.weight(1f),
                enabled = canSpeed, onClick = { speed = true },
            )
        }

        SectionHeader("Filament", action = "More", onAction = actions.openFilament)
        FilamentSummary(state, onOpen = actions.openFilament)
    }

    editingTemp?.let { which ->
        val current = if (which == "Nozzle") state.nozzle else state.bed
        val max = if (which == "Nozzle") 350 else 110
        TargetDialog(which, current?.target?.toInt() ?: 0, max, onDismiss = { editingTemp = null }) { value ->
            editingTemp = null
            actions.send(if (which == "Nozzle") PrinterCommand.SetNozzleTarget(value) else PrinterCommand.SetBedTarget(value))
        }
    }
    if (fans) FanSheet(state, onDismiss = { fans = false }) { fan, pct -> actions.send(PrinterCommand.SetFan(fan, pct)) }
    if (speed) SpeedSheet(state.speedMode, onDismiss = { speed = false }) { actions.send(PrinterCommand.SetSpeedMode(it)); speed = false }
}

@Composable
private fun JobPanel(state: PrinterState, thumb: ImageBitmap?, actions: DeviceActions) {
    val job = state.job ?: return
    val look = state.look()
    var confirmStop by remember { mutableStateOf(false) }
    Panel(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Thumb(thumb, 96.dp)
            Column(Modifier.weight(1f).heightIn(min = 96.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(displayName(job.fileName), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${job.progress}", style = Readout)
                    Text("%", Modifier.padding(start = 2.dp, bottom = 5.dp), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Text(look.label, Modifier.padding(bottom = 5.dp), color = look.color, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        ThickProgress(job.progress / 100f, color = look.color)
        Spacer(Modifier.height(10.dp))
        Row {
            Text(
                if (job.totalLayers > 0) "Layer ${job.currentLayer}/${job.totalLayers}" else "Layer ${job.currentLayer}",
                Modifier.weight(1f), color = Lattice.colors.muted, style = MaterialTheme.typography.bodyMedium,
            )
            if (state.machine.isJobActive) {
                Text("${formatDuration(job.remainingSec)} left", color = Lattice.colors.muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (state.pausedReason == PausedReason.FILAMENT_RUNOUT) {
            Spacer(Modifier.height(8.dp))
            Text("Filament ran out. Load filament, then resume.", color = Lattice.colors.warn, style = MaterialTheme.typography.bodyMedium)
        }
        if (state.machine.isJobActive && state.machine != MachineState.STOPPING) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val paused = state.machine == MachineState.PAUSED
                FilledTonalButton(
                    onClick = { actions.send(if (paused) PrinterCommand.Resume else PrinterCommand.Pause) },
                    enabled = paused || state.machine in setOf(MachineState.PRINTING, MachineState.PREHEATING, MachineState.HOMING, MachineState.LEVELING),
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, null)
                    Text(if (paused) "Resume" else "Pause", Modifier.padding(start = 8.dp))
                }
                OutlinedButton(
                    onClick = { confirmStop = true },
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Default.Stop, null)
                    Text("Stop", Modifier.padding(start = 8.dp))
                }
            }
        }
    }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Stop print?") },
            text = { Text("The print cannot be resumed after stopping.") },
            confirmButton = { TextButton(onClick = { confirmStop = false; actions.send(PrinterCommand.Stop) }) { Text("Stop print") } },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ReadyPanel(onFiles: () -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Thumb(null, 64.dp)
            Column(Modifier.weight(1f)) {
                Text("Ready to print", style = MaterialTheme.typography.titleMedium)
                Text("Pick a file on the printer or upload one.", color = Lattice.colors.muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(14.dp))
        Button(onClick = onFiles, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Choose a file") }
    }
}

@Composable
private fun TempReadout(label: String, t: Temperature?, modifier: Modifier, onClick: (() -> Unit)?) {
    val heating = (t?.target ?: 0.0) > 0
    Column(
        modifier.clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null && t != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(t?.let { "%.0f".format(it.current) } ?: "–", style = Readout, color = if (heating) Lattice.colors.heat else MaterialTheme.colorScheme.onSurface)
        Text(
            when {
                t == null -> " "
                onClick == null -> "°C"
                else -> "/%.0f °C".format(t.target)
            },
            color = Lattice.colors.muted, style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The CANVAS trays (or the external spool) as spools, with humidity. */
@Composable
internal fun FilamentSummary(state: PrinterState, onOpen: () -> Unit) {
    val fs = state.filament
    Panel(Modifier.fillMaxWidth(), onClick = onOpen) {
        if (fs == null) {
            Text("No filament information yet.", color = Lattice.colors.muted)
            return@Panel
        }
        fs.hubs.forEachIndexed { i, hub ->
            if (i > 0) Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("CANVAS ${hub.id + 1}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                hub.humidity?.let { HumidityPill(it) }
            }
            Spacer(Modifier.height(10.dp))
            SpoolRow(hub.slots)
        }
        fs.external?.let { ext ->
            if (fs.hubs.isNotEmpty()) Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Spool(ext.color?.let(::parseColor), "Ext", Modifier.size(width = 48.dp, height = 66.dp), active = ext.active && fs.hubs.isEmpty(), description = "External spool")
                Column(Modifier.weight(1f)) {
                    Text("External spool", style = MaterialTheme.typography.bodyLarge)
                    Text(ext.material.ifBlank { "Not set" }, color = Lattice.colors.muted, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
internal fun SpoolRow(slots: List<FilamentSlot>, onTray: ((FilamentSlot) -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        slots.forEach { s ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (s.present) s.material.ifBlank { "?" } else "Empty",
                    color = Lattice.colors.muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                )
                Spacer(Modifier.height(6.dp))
                Spool(
                    color = if (s.present) s.color?.let(::parseColor) else null,
                    label = "${s.hubId + 1}-${s.trayId + 1}",
                    active = s.active,
                    description = "Tray ${s.hubId + 1}-${s.trayId + 1}: " + (if (s.present) "${s.material} ${s.name}" else "empty"),
                    onClick = onTray?.let { { it(s) } },
                )
            }
        }
    }
}

@Composable
internal fun HumidityPill(percent: Double) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Lattice.colors.tile).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Default.WaterDrop, "Humidity", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Text("%.0f%%".format(percent), style = MaterialTheme.typography.bodyMedium)
    }
}

fun SpeedMode.label() = when (this) {
    SpeedMode.SILENT -> "Silent"
    SpeedMode.BALANCED -> "Normal"
    SpeedMode.SPORT -> "Sport"
    SpeedMode.LUDICROUS -> "Ludicrous"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FanSheet(state: PrinterState, onDismiss: () -> Unit, onSet: (Fan, Int) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Fans", style = MaterialTheme.typography.titleLarge)
            Panel(Modifier.fillMaxWidth()) {
                FanSlider("Part", state.fans[Fan.MODEL]) { onSet(Fan.MODEL, it) }
                FanSlider("Auxiliary", state.fans[Fan.AUX]) { onSet(Fan.AUX, it) }
                FanSlider("Chamber", state.fans[Fan.CHAMBER]) { onSet(Fan.CHAMBER, it) }
            }
        }
    }
}

@Composable
private fun FanSlider(label: String, percent: Int?, onSet: (Int) -> Unit) {
    if (percent == null) return
    // Local value while dragging; the printer's value takes over when it reports back.
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(percent.toFloat()) }
    val shown = if (dragging) local else percent.toFloat()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(96.dp), style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = shown,
            onValueChange = { dragging = true; local = it },
            onValueChangeFinished = { dragging = false; onSet(local.toInt()) },
            valueRange = 0f..100f,
            steps = 9,
            modifier = Modifier.weight(1f),
        )
        Text("${shown.toInt()}%", Modifier.width(48.dp).padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedSheet(current: SpeedMode?, onDismiss: () -> Unit, onPick: (SpeedMode) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Print speed", style = MaterialTheme.typography.titleLarge)
            SettingsGroup {
                SpeedMode.entries.forEachIndexed { i, m ->
                    if (i > 0) GroupDivider()
                    SettingsRow(
                        m.label(),
                        onClick = { onPick(m) },
                        chevron = false,
                        trailing = { if (m == current) Icon(Icons.Default.Check, "Current", tint = MaterialTheme.colorScheme.primary) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TargetDialog(label: String, current: Int, max: Int, onDismiss: () -> Unit, onSet: (Int) -> Unit) {
    var text by remember { mutableStateOf(current.toString()) }
    val value = text.toIntOrNull()
    val valid = value != null && value in 0..max
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$label target") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(3) },
                label = { Text("°C (0 = off, max $max)") },
                singleLine = true,
                isError = !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSet(value!!) }) { Text("Set") } },
        dismissButton = {
            Row {
                TextButton(onClick = { onSet(0) }) { Text("Turn off") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
