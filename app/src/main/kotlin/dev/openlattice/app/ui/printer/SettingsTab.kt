package dev.openlattice.app.ui.printer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.openlattice.app.ui.app
import dev.openlattice.app.ui.components.GroupDivider
import dev.openlattice.app.ui.components.GroupLabel
import dev.openlattice.app.ui.components.SettingsGroup
import dev.openlattice.app.ui.components.SettingsRow

@Composable
fun SettingsTab(vm: PrinterViewModel, onRemoved: () -> Unit, onAppearance: () -> Unit) {
    val printer by vm.printer.collectAsState()
    val info by vm.info.collectAsState()
    val appearance by LocalContext.current.app.appearance.collectAsState()
    val p = printer ?: return
    var renaming by remember { mutableStateOf(false) }
    var editingConnection by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }

    SettingsContent(
        name = info?.name ?: p.name,
        model = info?.model ?: p.model,
        serial = p.serial,
        firmware = info?.firmware,
        host = p.host,
        paletteName = appearance?.active?.name ?: "Phone colours",
        onRename = { renaming = true },
        onConnection = { editingConnection = true },
        onAppearance = onAppearance,
        onRemove = { removing = true },
    )

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
            text = { Text("This only removes it from Open Lattice. Nothing on the printer changes.") },
            confirmButton = { TextButton(onClick = { removing = false; vm.remove(onRemoved) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun SettingsContent(
    name: String,
    model: String,
    serial: String,
    firmware: String?,
    host: String,
    paletteName: String,
    onRename: () -> Unit,
    onConnection: () -> Unit,
    onAppearance: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        GroupLabel("Printer")
        SettingsGroup {
            SettingsRow("Name", value = name, onClick = onRename)
            GroupDivider()
            SettingsRow("Connection", value = host, onClick = onConnection)
        }
        GroupLabel("About")
        SettingsGroup {
            SettingsRow("Model", value = model)
            GroupDivider()
            SettingsRow("Serial number", value = serial.ifBlank { "–" })
            GroupDivider()
            SettingsRow("Firmware", value = firmware ?: "–")
        }
        GroupLabel("App")
        SettingsGroup {
            SettingsRow("Appearance", value = paletteName, onClick = onAppearance)
        }
        Spacer(Modifier.height(24.dp))
        SettingsGroup {
            SettingsRow("Remove printer", onClick = onRemove, destructive = true)
        }
    }
}
