package dev.openlattice.app.ui.printer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.openlattice.app.ui.formatDuration
import dev.openlattice.core.ConnectionState
import dev.openlattice.core.Fan
import dev.openlattice.core.MachineState
import dev.openlattice.core.PausedReason
import dev.openlattice.core.PrinterCommand
import dev.openlattice.core.PrinterState
import dev.openlattice.core.SpeedMode
import dev.openlattice.core.Temperature

@Composable
fun StatusTab(vm: PrinterViewModel, state: PrinterState, onCamera: () -> Unit) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val conn = state.connection
        if (conn is ConnectionState.Failed) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (conn.authRejected) "The printer rejected the access code. Update it under Settings." else conn.reason)
                    OutlinedButton(onClick = vm::retryConnection) { Text("Retry now") }
                }
            }
        }

        if (state.connection == ConnectionState.Connected) {
            Card(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clickable(onClick = onCamera)) {
                CameraView(vm::cameraUrl, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            }
        }

        state.exceptions.forEach { e ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Warning, null)
                    Text("${e.message} (${e.code})")
                }
            }
        }

        JobCard(vm, state)
        TemperatureCard(vm, state)
        FanLightCard(vm, state)
        SpeedCard(vm, state)
        MotionCard(vm, state)
    }
}

@Composable
private fun JobCard(vm: PrinterViewModel, state: PrinterState) {
    val job = state.job ?: return
    var confirmStop by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(job.fileName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            LinearProgressIndicator(progress = { job.progress / 100f }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${job.progress}%", style = MaterialTheme.typography.titleLarge)
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        if (job.totalLayers > 0) "Layer ${job.currentLayer} / ${job.totalLayers}" else "Layer ${job.currentLayer}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("${formatDuration(job.elapsedSec)} elapsed · ${formatDuration(job.remainingSec)} left", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.pausedReason == PausedReason.FILAMENT_RUNOUT) {
                Text("Paused: filament ran out. Load filament, then resume.", color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state.machine) {
                    MachineState.PAUSED -> FilledTonalButton(onClick = { vm.send(PrinterCommand.Resume) }) {
                        Icon(Icons.Default.PlayArrow, null); Text("Resume")
                    }
                    MachineState.PRINTING, MachineState.PREHEATING, MachineState.HOMING, MachineState.LEVELING ->
                        FilledTonalButton(onClick = { vm.send(PrinterCommand.Pause) }) { Icon(Icons.Default.Pause, null); Text("Pause") }
                    else -> {}
                }
                if (state.machine.isJobActive && state.machine != MachineState.STOPPING) {
                    Button(
                        onClick = { confirmStop = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Icon(Icons.Default.Stop, null); Text("Stop") }
                }
            }
        }
    }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Stop print?") },
            text = { Text("The print cannot be resumed after stopping.") },
            confirmButton = { TextButton(onClick = { confirmStop = false; vm.send(PrinterCommand.Stop) }) { Text("Stop print") } },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TemperatureCard(vm: PrinterViewModel, state: PrinterState) {
    var editing by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            TempCell("Nozzle", state.nozzle) { editing = "Nozzle" }
            TempCell("Bed", state.bed) { editing = "Bed" }
            TempCell("Chamber", state.chamber, editable = false) {}
        }
    }
    editing?.let { which ->
        val current = if (which == "Nozzle") state.nozzle else state.bed
        val max = if (which == "Nozzle") 350 else 110
        TargetDialog(which, current?.target?.toInt() ?: 0, max, onDismiss = { editing = null }) { value ->
            editing = null
            vm.send(if (which == "Nozzle") PrinterCommand.SetNozzleTarget(value) else PrinterCommand.SetBedTarget(value))
        }
    }
}

@Composable
private fun TempCell(label: String, t: Temperature?, editable: Boolean = true, onClick: () -> Unit) {
    Column(
        Modifier.clickable(enabled = editable && t != null, onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(t?.let { "%.0f°".format(it.current) } ?: "–", style = MaterialTheme.typography.headlineSmall)
        if (editable) Text(t?.let { if (it.target > 0) "→ %.0f°".format(it.target) else "off" } ?: "", style = MaterialTheme.typography.bodySmall)
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

@Composable
private fun FanLightCard(vm: PrinterViewModel, state: PrinterState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Light", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Switch(checked = state.lightOn == true, onCheckedChange = { vm.send(PrinterCommand.SetLight(it)) }, enabled = state.lightOn != null)
            }
            FanSlider("Part fan", state.fans[Fan.MODEL]) { vm.send(PrinterCommand.SetFan(Fan.MODEL, it)) }
            FanSlider("Auxiliary fan", state.fans[Fan.AUX]) { vm.send(PrinterCommand.SetFan(Fan.AUX, it)) }
            FanSlider("Chamber fan", state.fans[Fan.CHAMBER]) { vm.send(PrinterCommand.SetFan(Fan.CHAMBER, it)) }
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
        Text(label, Modifier.width(110.dp), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = shown,
            onValueChange = { dragging = true; local = it },
            onValueChangeFinished = { dragging = false; onSet(local.toInt()) },
            valueRange = 0f..100f,
            steps = 9,
            modifier = Modifier.weight(1f),
        )
        Text("${shown.toInt()}%", Modifier.width(44.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SpeedCard(vm: PrinterViewModel, state: PrinterState) {
    val current = state.speedMode ?: return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Print speed", style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val modes = SpeedMode.entries
                modes.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = m == current,
                        onClick = { vm.send(PrinterCommand.SetSpeedMode(m)) },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    ) {
                        Text(
                            when (m) {
                                SpeedMode.SILENT -> "Silent"
                                SpeedMode.BALANCED -> "Normal"
                                SpeedMode.SPORT -> "Sport"
                                SpeedMode.LUDICROUS -> "Ludicrous"
                            },
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MotionCard(vm: PrinterViewModel, state: PrinterState) {
    if (state.connection != ConnectionState.Connected) return
    val locked = state.machine.isJobActive
    val steps = listOf(0.1, 1.0, 10.0, 50.0)
    var step by remember { mutableStateOf(10.0) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Move", style = MaterialTheme.typography.titleSmall)
            if (locked) {
                Text("Disabled while a print is running.", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            state.position?.let { Text("X %.1f  Y %.1f  Z %.1f".format(it.x, it.y, it.z), style = MaterialTheme.typography.bodySmall) }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                steps.forEachIndexed { i, s ->
                    SegmentedButton(selected = s == step, onClick = { step = s }, shape = SegmentedButtonDefaults.itemShape(i, steps.size)) {
                        Text(if (s < 1) "$s" else "${s.toInt()}")
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                // XY pad
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledTonalIconButton(onClick = { vm.send(PrinterCommand.Jog('y', step)) }) { Icon(Icons.Default.KeyboardArrowUp, "Y+") }
                    Row {
                        FilledTonalIconButton(onClick = { vm.send(PrinterCommand.Jog('x', -step)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "X-") }
                        FilledTonalIconButton(onClick = { vm.send(PrinterCommand.Home("xy")) }) { Icon(Icons.Default.Home, "Home XY") }
                        FilledTonalIconButton(onClick = { vm.send(PrinterCommand.Jog('x', step)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "X+") }
                    }
                    FilledTonalIconButton(onClick = { vm.send(PrinterCommand.Jog('y', -step)) }) { Icon(Icons.Default.KeyboardArrowDown, "Y-") }
                }
                // Z column
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledTonalIconButton(onClick = { vm.send(PrinterCommand.Jog('z', step)) }) { Icon(Icons.Default.KeyboardArrowUp, "Z+") }
                    FilledTonalIconButton(onClick = { vm.send(PrinterCommand.Home("z")) }) { Text("Z") }
                    FilledTonalIconButton(onClick = { vm.send(PrinterCommand.Jog('z', -step)) }) { Icon(Icons.Default.KeyboardArrowDown, "Z-") }
                }
            }
            OutlinedButton(onClick = { vm.send(PrinterCommand.Home("xyz")) }, modifier = Modifier.fillMaxWidth()) { Text("Home all axes") }
        }
    }
}
