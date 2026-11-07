package dev.openlattice.app.ui.printer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.openlattice.app.ui.displayName
import dev.openlattice.app.ui.components.ColorDot
import dev.openlattice.app.ui.components.GroupDivider
import dev.openlattice.app.ui.components.GroupLabel
import dev.openlattice.app.ui.components.SettingsGroup
import dev.openlattice.app.ui.components.SettingsRow
import dev.openlattice.app.ui.components.Thumb
import dev.openlattice.app.ui.formatDuration
import dev.openlattice.app.ui.theme.Lattice
import dev.openlattice.core.BuildPlate
import dev.openlattice.core.FilamentSlot
import dev.openlattice.core.PrintIssue
import dev.openlattice.core.PrintPlan
import dev.openlattice.core.PrintPlanner
import dev.openlattice.core.PrintPreferences
import dev.openlattice.core.PrinterFile
import dev.openlattice.core.PrinterState
import java.text.DateFormat
import java.util.Date

/**
 * Print sheet for one file: remembered settings, CANVAS trays matched automatically (each can
 * be changed), and anything that needs a look before the print starts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrintSheet(vm: PrinterViewModel, state: PrinterState, prompt: PrintPrompt, onDismiss: () -> Unit) {
    val f = prompt.file
    val saved by vm.printPrefs.collectAsState()
    var prefs by remember(f) { mutableStateOf(saved) }
    var confirmDelete by remember { mutableStateOf(false) }
    // Tools whose tray the user picked by hand; the rest follow the automatic match.
    val chosen = remember(f) { mutableStateMapOf<Int, FilamentSlot>() }
    val plan = PrintPlanner.plan(f, prompt.storage, state, prefs, chosen.toMap())
    val trays = state.filament?.hubs.orEmpty().flatMap { it.slots }.filter { it.present }

    LaunchedEffect(f.name) { vm.loadThumbnail(prompt.storage, f.name) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        PrintSheetContent(
            file = f,
            thumb = vm.thumbnails[f.name],
            plan = plan,
            prefs = prefs,
            trays = trays,
            onPrefs = { prefs = it },
            onChoose = { tool, slot -> chosen[tool] = slot },
            onPrint = { vm.startPrint(plan.command, prefs) },
            onDelete = { confirmDelete = true },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete file?") },
            text = { Text(f.name) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDismiss(); vm.deleteFile(prompt.storage, f.name) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun PrintSheetContent(
    file: PrinterFile,
    thumb: ImageBitmap?,
    plan: PrintPlan,
    prefs: PrintPreferences,
    trays: List<FilamentSlot>,
    onPrefs: (PrintPreferences) -> Unit,
    onChoose: (Int, FilamentSlot) -> Unit,
    onPrint: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(thumb, 72.dp)
            Column(Modifier.weight(1f)) {
                Text(displayName(file.name), style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        file.printTimeSec?.let { formatDuration(it) },
                        file.layers?.let { "$it layers" },
                        file.filamentUsedGrams?.let { "%.1f g".format(it) },
                        file.createdAtEpochSec?.let { DateFormat.getDateInstance(DateFormat.SHORT).format(Date(it * 1000)) },
                    ).joinToString(" · "),
                    color = Lattice.colors.muted, style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        plan.issues.forEach { IssueBanner(it) }

        val tools = file.colors.distinctBy { it.tool }
        if (trays.isNotEmpty() && tools.isNotEmpty()) {
            GroupLabel("Filament · CANVAS")
            SettingsGroup {
                tools.forEachIndexed { i, c ->
                    if (i > 0) GroupDivider()
                    TrayChoice(c.tool, c.color, c.material, plan.slots[c.tool], trays, plan.issues) { onChoose(c.tool, it) }
                }
            }
        }

        GroupLabel("Settings · remembered for this printer")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(BuildPlate.TEXTURED to "Textured plate", BuildPlate.SMOOTH to "Smooth plate").forEachIndexed { i, (p, label) ->
                SegmentedButton(selected = prefs.plate == p, onClick = { onPrefs(prefs.copy(plate = p)) }, shape = SegmentedButtonDefaults.itemShape(i, 2)) {
                    Text(label, maxLines = 1)
                }
            }
        }
        SettingsGroup {
            SettingsRow("Auto bed leveling", trailing = { Switch(prefs.autoBedLeveling, { onPrefs(prefs.copy(autoBedLeveling = it)) }) })
            GroupDivider()
            SettingsRow("Record timelapse", trailing = { Switch(prefs.timelapse, { onPrefs(prefs.copy(timelapse = it)) }) })
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            TextButton(onClick = onDelete, modifier = Modifier.height(52.dp)) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            Button(onClick = onPrint, enabled = !plan.blocked, modifier = Modifier.weight(1f).height(52.dp)) {
                Icon(Icons.Default.PlayArrow, null)
                Text(if (plan.ready || plan.blocked) "Print" else "Print anyway", Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun IssueBanner(issue: PrintIssue) {
    val blocking = issue.blocking
    val tint = if (blocking) MaterialTheme.colorScheme.onErrorContainer else Lattice.colors.warn
    val bg = if (blocking) MaterialTheme.colorScheme.errorContainer else Lattice.colors.warn.copy(alpha = 0.14f)
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(bg).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(if (blocking) Icons.Default.Block else Icons.Default.WarningAmber, null, Modifier.size(20.dp), tint = tint)
        Text(issue.message, color = tint, style = MaterialTheme.typography.bodyMedium)
    }
}

/** One file filament -> tray row; tap to pick a different tray. */
@Composable
private fun TrayChoice(
    tool: Int,
    color: String,
    material: String,
    chosen: FilamentSlot?,
    trays: List<FilamentSlot>,
    issues: List<PrintIssue>,
    onChoose: (FilamentSlot) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val problem = issues.any {
        (it is PrintIssue.MaterialDiffers && it.tool == tool) || (it is PrintIssue.ColorDiffers && it.tool == tool) || (it is PrintIssue.NoTray && it.tool == tool)
    }
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { open = true }.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ColorDot(parseColor(color), size = 16.dp)
            Text("T${tool + 1} · $material", Modifier.width(88.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp), tint = Lattice.colors.muted)
            if (chosen != null) ColorDot(parseColor(chosen.color), size = 16.dp)
            Text(
                chosen?.let { "Tray ${it.hubId + 1}-${it.trayId + 1} · ${it.material}" } ?: "Choose a tray",
                Modifier.weight(1f), color = Lattice.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (problem) Icon(Icons.Default.WarningAmber, "Needs a look", tint = Lattice.colors.warn)
            else if (chosen != null) Icon(Icons.Default.Check, "Matches", tint = Lattice.colors.ok)
        }
        DropdownMenu(open, { open = false }) {
            trays.forEach { s ->
                DropdownMenuItem(
                    text = { Text("Tray ${s.hubId + 1}-${s.trayId + 1} · ${s.material} ${s.name}") },
                    leadingIcon = { ColorDot(parseColor(s.color), size = 16.dp) },
                    onClick = { open = false; onChoose(s) },
                )
            }
        }
    }
}
