package dev.openlattice.app.ui.printer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun SettingsTab(vm: PrinterViewModel, onRemoved: () -> Unit) {
    val printer by vm.printer.collectAsState()
    val info by vm.info.collectAsState()
    val p = printer ?: return
    var renaming by remember { mutableStateOf(false) }
    var editingConnection by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Printer", style = MaterialTheme.typography.titleSmall)
                InfoRow("Name", info?.name ?: p.name)
                InfoRow("Model", info?.model ?: p.model)
                InfoRow("Serial", p.serial)
                InfoRow("Firmware", info?.firmware ?: "–")
                InfoRow("Address", p.host)
            }
        }
        OutlinedButton(onClick = { renaming = true }, modifier = Modifier.fillMaxWidth()) { Text("Rename printer") }
        OutlinedButton(onClick = { editingConnection = true }, modifier = Modifier.fillMaxWidth()) { Text("Change address or access code") }
        Button(
            onClick = { removing = true },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Remove from this app") }
    }

    if (renaming) {
        var name by remember { mutableStateOf(info?.name ?: p.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename printer") },
            text = { OutlinedTextField(name, { name = it.take(32) }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { renaming = false; vm.rename(name.trim()) }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }

    if (editingConnection) {
        var host by remember { mutableStateOf(p.host) }
        var code by remember { mutableStateOf(p.accessCode) }
        AlertDialog(
            onDismissRequest = { editingConnection = false },
            title = { Text("Connection") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(host, { host = it }, singleLine = true, label = { Text("IP address or hostname") })
                    OutlinedTextField(code, { code = it }, singleLine = true, label = { Text("Access code") }, visualTransformation = PasswordVisualTransformation())
                }
            },
            confirmButton = {
                TextButton(enabled = host.isNotBlank(), onClick = { editingConnection = false; vm.updateConnection(host, code) }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingConnection = false }) { Text("Cancel") } },
        )
    }

    if (removing) {
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text("Remove printer?") },
            text = { Text("This only removes it from open-lattice. Nothing on the printer changes.") },
            confirmButton = { TextButton(onClick = { removing = false; vm.remove(onRemoved) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    androidx.compose.foundation.layout.Row {
        Text(label, Modifier.weight(0.35f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(0.65f), style = MaterialTheme.typography.bodyMedium)
    }
}
