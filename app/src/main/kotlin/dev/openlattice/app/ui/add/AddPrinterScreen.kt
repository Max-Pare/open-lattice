package dev.openlattice.app.ui.add

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.openlattice.app.data.LanDiscovery
import dev.openlattice.app.data.PrinterKind
import dev.openlattice.app.data.SavedPrinter
import dev.openlattice.app.ui.app
import dev.openlattice.cc2.Cc2DiscoveryResult
import dev.openlattice.cc2.Cc2Driver
import dev.openlattice.cc2.Cc2Method
import dev.openlattice.cc2.MqttAuthException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

class AddPrinterViewModel(application: Application) : AndroidViewModel(application) {
    var scanning by mutableStateOf(false); private set
    var found by mutableStateOf<List<Cc2DiscoveryResult>>(emptyList()); private set
    var selected by mutableStateOf<Cc2DiscoveryResult?>(null); private set
    var host by mutableStateOf("")
    var accessCode by mutableStateOf("")
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    fun scan() {
        if (scanning) return
        scanning = true
        viewModelScope.launch {
            found = runCatching { LanDiscovery.scan(getApplication()) }.getOrDefault(emptyList())
            scanning = false
        }
    }

    fun select(r: Cc2DiscoveryResult) {
        selected = r
        host = r.host
        error = null
        if (!r.accessCodeRequired) accessCode = Cc2Method.DEFAULT_ACCESS_CODE
    }

    fun lookUp() {
        val h = host.trim()
        if (h.isEmpty()) return
        busy = true
        error = null
        viewModelScope.launch {
            val r = LanDiscovery.probe(h)
            busy = false
            if (r == null) {
                error = "No ELEGOO printer answered at $h (UDP ${Cc2Method.DISCOVERY_PORT}). Check the address, and that the printer is on and in LAN Only Mode."
            } else {
                select(r.copy(host = h))
            }
        }
    }

    /** Test-connects with the access code, then saves. Calls [onSaved] with the new printer id. */
    fun save(onSaved: (String) -> Unit) {
        val target = selected ?: return
        val code = accessCode.trim().ifEmpty { Cc2Method.DEFAULT_ACCESS_CODE }
        busy = true
        error = null
        viewModelScope.launch {
            val driver = Cc2Driver(target.host, target.serial, code)
            try {
                val ok = withTimeoutOrNull(15_000) {
                    driver.connect()
                    driver.info.filterNotNull().first()
                }
                if (ok == null) {
                    error = "The printer did not answer in time."
                    return@launch
                }
                val printer = SavedPrinter(
                    id = UUID.randomUUID().toString(),
                    kind = PrinterKind.CC2,
                    name = ok.name.ifEmpty { target.name },
                    model = ok.model.ifEmpty { target.model },
                    host = target.host,
                    serial = target.serial,
                    accessCode = code,
                )
                getApplication<Application>().app.store.upsert(printer)
                onSaved(printer.id)
            } catch (_: MqttAuthException) {
                error = "Wrong access code. It is shown on the printer under Settings → Network."
            } catch (e: Exception) {
                error = e.message ?: "Could not connect"
            } finally {
                driver.close()
                busy = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPrinterScreen(onDone: (String?) -> Unit, vm: AddPrinterViewModel = viewModel()) {
    LaunchedEffect(Unit) { vm.scan() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add printer") },
                navigationIcon = { IconButton(onClick = { onDone(null) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("On this network", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (vm.scanning) CircularProgressIndicator(Modifier.size(24.dp))
                else IconButton(onClick = vm::scan) { Icon(Icons.Default.Refresh, "Scan again") }
            }
            if (!vm.scanning && vm.found.isEmpty()) {
                Text(
                    "Nothing found. Over a VPN, discovery cannot cross subnets: enter the printer's IP below.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            vm.found.forEach { r ->
                val isSelected = vm.selected?.serial == r.serial
                Card(
                    Modifier.fillMaxWidth().clickable { vm.select(r) },
                    colors = if (isSelected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(r.name.ifEmpty { r.model }, style = MaterialTheme.typography.titleSmall)
                        Text("${r.model} · ${r.host}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            HorizontalDivider()
            Text("By address", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = vm.host,
                    onValueChange = { vm.host = it },
                    label = { Text("IP address or hostname") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = vm::lookUp, enabled = !vm.busy && vm.host.isNotBlank()) { Text("Look up") }
            }

            vm.selected?.let { r ->
                HorizontalDivider()
                Text(r.name.ifEmpty { r.model }, style = MaterialTheme.typography.titleMedium)
                Text("${r.model} · serial ${r.serial}", style = MaterialTheme.typography.bodySmall)
                if (!r.lanOnly) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                        Text(
                            "LAN Only Mode is off. Turn it on at the printer (Settings → Network); the local connection is refused otherwise.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                OutlinedTextField(
                    value = vm.accessCode,
                    onValueChange = { vm.accessCode = it },
                    label = { Text("Access code") },
                    supportingText = {
                        Text(if (r.accessCodeRequired) "Shown on the printer under Settings → Network" else "No code set on the printer; the default is ${Cc2Method.DEFAULT_ACCESS_CODE}")
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { vm.save { onDone(it) } }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                    if (vm.busy) CircularProgressIndicator(Modifier.size(18.dp)) else Text("Connect and save")
                }
            }

            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
