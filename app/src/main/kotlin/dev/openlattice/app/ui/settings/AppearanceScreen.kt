package dev.openlattice.app.ui.settings

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.openlattice.app.data.Appearance
import dev.openlattice.app.data.ThemeMode
import dev.openlattice.app.ui.app
import dev.openlattice.app.ui.components.GroupDivider
import dev.openlattice.app.ui.components.GroupLabel
import dev.openlattice.app.ui.components.Panel
import dev.openlattice.app.ui.components.SettingsGroup
import dev.openlattice.app.ui.components.ThickProgress
import dev.openlattice.app.ui.theme.Lattice
import dev.openlattice.app.ui.theme.LatticeTheme
import dev.openlattice.app.ui.theme.c
import dev.openlattice.app.ui.theme.dynamicPalette
import dev.openlattice.core.theme.Colorhunt
import dev.openlattice.core.theme.Palette
import dev.openlattice.core.theme.PaletteTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** A Colorhunt link shared into the app, waiting for the Appearance screen to pick it up. */
object PendingPalette {
    val text = MutableStateFlow<String?>(null)
}

@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = context.app.appearanceStore
    val appearance by context.app.appearance.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val snackbar = remember { SnackbarHostState() }
    var link by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        PendingPalette.text.collect { shared ->
            if (shared != null) {
                link = shared
                PendingPalette.text.value = null
            }
        }
    }

    AppearanceContent(
        appearance = appearance ?: Appearance(),
        dynamic = dynamicPalette(dark = true),
        link = link,
        onLinkChange = { link = it },
        name = name,
        onNameChange = { name = it.take(40) },
        snackbar = snackbar,
        onBack = onBack,
        onPaste = {
            scope.launch {
                val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                if (text.isNullOrBlank()) snackbar.showSnackbar("The clipboard is empty") else link = text
            }
        },
        onSave = { palette ->
            scope.launch {
                store.add(palette)
                link = ""
                name = ""
                snackbar.showSnackbar("Saved and applied ${palette.name}")
            }
        },
        onSelect = { id -> scope.launch { store.select(id) } },
        onMode = { m -> scope.launch { store.setMode(m) } },
        onRename = { p, n -> scope.launch { store.rename(p.id, n) } },
        onDelete = { p -> scope.launch { store.remove(p.id); snackbar.showSnackbar("Removed ${p.name}") } },
        onCopyLink = { p ->
            scope.launch {
                p.source?.let { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Colorhunt palette", it))) }
                snackbar.showSnackbar("Link copied")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceContent(
    appearance: Appearance,
    dynamic: Palette?,
    link: String,
    onLinkChange: (String) -> Unit,
    name: String,
    onNameChange: (String) -> Unit,
    onBack: () -> Unit,
    onPaste: () -> Unit,
    onSave: (Palette) -> Unit,
    onSelect: (String) -> Unit,
    onMode: (ThemeMode) -> Unit,
    onRename: (Palette, String) -> Unit,
    onDelete: (Palette) -> Unit,
    onCopyLink: (Palette) -> Unit,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    var renaming by remember { mutableStateOf<Palette?>(null) }
    var deleting by remember { mutableStateOf<Palette?>(null) }
    val parsed = remember(link, name) { Colorhunt.palette(link, name) }
    val dark = Lattice.colors.isDark

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Appearance") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        ) {
            item { GroupLabel("Theme") }
            item {
                Panel(Modifier.fillMaxWidth()) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val modes = listOf(ThemeMode.SYSTEM to "System", ThemeMode.DARK to "Dark", ThemeMode.LIGHT to "Light")
                        modes.forEachIndexed { i, (m, label) ->
                            SegmentedButton(
                                selected = appearance.mode == m,
                                onClick = { onMode(m) },
                                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                            ) { Text(label) }
                        }
                    }
                }
            }

            item { GroupLabel("Palettes") }
            item {
                SettingsGroup {
                    val rows = buildList {
                        Palette.BUILT_IN.forEach { add(it to "Built in") }
                        if (dynamic != null) add(dynamic to "From your wallpaper")
                        appearance.saved.forEach { add(it to "Colorhunt") }
                    }
                    rows.forEachIndexed { i, (p, kind) ->
                        if (i > 0) GroupDivider()
                        PaletteRow(
                            palette = p,
                            kind = kind,
                            selected = appearance.activeId == p.id,
                            onClick = { onSelect(p.id) },
                            menu = if (p.source != null) {
                                listOf(
                                    "Rename" to { renaming = p },
                                    "Copy link" to { onCopyLink(p) },
                                    "Delete" to { deleting = p },
                                )
                            } else {
                                emptyList()
                            },
                        )
                    }
                }
            }

            item { GroupLabel("Add from Colorhunt") }
            item {
                Panel(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = link,
                        onValueChange = onLinkChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Palette link") },
                        placeholder = { Text("colorhunt.co/palette/…") },
                        singleLine = true,
                        isError = link.isNotBlank() && parsed == null,
                        supportingText = {
                            Text(
                                if (link.isNotBlank() && parsed == null) "Not a Colorhunt palette link. Open a palette on colorhunt.co and share or copy its link."
                                else "Colours come from the link itself. Nothing is downloaded.",
                            )
                        },
                        trailingIcon = { IconButton(onClick = onPaste) { Icon(Icons.Default.ContentPaste, "Paste") } },
                    )
                    if (parsed != null) {
                        Spacer(Modifier.height(8.dp))
                        PalettePreview(parsed, dark)
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = name,
                            onValueChange = onNameChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Name") },
                            placeholder = { Text(Colorhunt.defaultName(parsed.colors), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            singleLine = true,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { onSave(parsed) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Save and use") }
                    }
                }
            }
        }
    }

    renaming?.let { p ->
        var text by remember(p) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename palette") },
            text = { OutlinedTextField(text, { text = it.take(40) }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { renaming = null; onRename(p, text) }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${p.name}?") },
            text = { Text("It is removed from your list. If it is in use, the app goes back to the Lattice palette.") },
            confirmButton = { TextButton(onClick = { deleting = null; onDelete(p) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PaletteRow(palette: Palette, kind: String, selected: Boolean, onClick: () -> Unit, menu: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Swatches(palette.colors, Modifier.width(76.dp).height(32.dp))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(palette.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(kind, style = MaterialTheme.typography.bodySmall, color = Lattice.colors.muted)
        }
        if (selected) Icon(Icons.Default.Check, "In use", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = if (menu.isEmpty()) 12.dp else 0.dp))
        if (menu.isNotEmpty()) {
            Box {
                IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More for ${palette.name}", tint = Lattice.colors.muted) }
                DropdownMenu(open, { open = false }) {
                    menu.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() }) }
                }
            }
        }
    }
}

/** The palette's colours side by side in one rounded bar. */
@Composable
fun Swatches(colors: List<Int>, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, Lattice.colors.divider.copy(alpha = 0.6f), RoundedCornerShape(8.dp))) {
        colors.forEach { Box(Modifier.weight(1f).fillMaxHeight().background(c(it))) }
    }
}

/** A small slice of the app drawn in [palette], so the look can be judged before saving. */
@Composable
private fun PalettePreview(palette: Palette, dark: Boolean) {
    Swatches(palette.colors, Modifier.fillMaxWidth().height(40.dp))
    Spacer(Modifier.height(12.dp))
    val theme = remember(palette, dark) { if (dark) PaletteTheme.dark(palette) else PaletteTheme.light(palette) }
    LatticeTheme(theme) {
        Column(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.background).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Panel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Workshop printer", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text("Printing", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("64%", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.weight(1f))
                    Text("Nozzle 220°", color = Lattice.colors.heat, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(8.dp))
                ThickProgress(0.64f)
                Spacer(Modifier.height(8.dp))
                Text("1h 12m left · layer 131 / 204", color = Lattice.colors.muted, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Print") }
        }
    }
}
