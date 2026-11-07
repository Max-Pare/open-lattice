package dev.openlattice.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.github.takahirom.roborazzi.captureRoboImage
import dev.openlattice.app.data.Appearance
import dev.openlattice.app.data.PrinterKind
import dev.openlattice.app.data.SavedPrinter
import dev.openlattice.app.data.ThemeMode
import dev.openlattice.app.ui.printer.DeviceActions
import dev.openlattice.app.ui.printer.DeviceHome
import dev.openlattice.app.ui.printer.FilamentContent
import dev.openlattice.app.ui.printer.FilesContent
import dev.openlattice.app.ui.printer.FilesUi
import dev.openlattice.app.ui.printer.HistoryContent
import dev.openlattice.app.ui.printer.HistoryUi
import dev.openlattice.app.ui.printer.MovePage
import dev.openlattice.app.ui.printer.PrintSheetContent
import dev.openlattice.app.ui.printer.PrinterScaffold
import dev.openlattice.app.ui.printer.PrinterTab
import dev.openlattice.app.ui.printer.SettingsContent
import dev.openlattice.app.ui.printers.PrinterCardContent
import dev.openlattice.app.ui.printers.PrinterListContent
import dev.openlattice.app.ui.settings.AppearanceContent
import dev.openlattice.app.ui.theme.LatticeTheme
import dev.openlattice.core.BuildPlate
import dev.openlattice.core.ConnectionState
import dev.openlattice.core.Fan
import dev.openlattice.core.FileColor
import dev.openlattice.core.FilamentHub
import dev.openlattice.core.FilamentSlot
import dev.openlattice.core.FilamentSystem
import dev.openlattice.core.HistoryEntry
import dev.openlattice.core.HistoryResult
import dev.openlattice.core.MachineState
import dev.openlattice.core.Position
import dev.openlattice.core.PrintJob
import dev.openlattice.core.PrintPlanner
import dev.openlattice.core.PrintPreferences
import dev.openlattice.core.PrinterFile
import dev.openlattice.core.PrinterState
import dev.openlattice.core.SpeedMode
import dev.openlattice.core.Storage
import dev.openlattice.core.StorageInfo
import dev.openlattice.core.Temperature
import dev.openlattice.core.Timelapse
import dev.openlattice.core.theme.Colorhunt
import dev.openlattice.core.theme.Palette
import dev.openlattice.core.theme.PaletteTheme
import dev.openlattice.core.theme.ThemeColors
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders every screen from fake printer data in a few palettes, so the UI can be reviewed (and
 * checked for regressions) without a phone. Images land in app/build/screenshots/.
 * Run: ./gradlew :app:recordRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A plain Application: the real one schedules background work the screenshots do not need.
@Config(sdk = [35], qualifiers = "w390dp-h1280dp-xhdpi", application = android.app.Application::class)
class ScreensScreenshotTest {
    private val example = Colorhunt.palette("https://colorhunt.co/palette/071b2b167d8db9e4e8eeeeee", "Ocean")!!
    private val themes: List<Pair<String, ThemeColors>> = listOf(
        "lattice-dark" to PaletteTheme.dark(Palette.LATTICE),
        "ocean-dark" to PaletteTheme.dark(example),
        "ocean-light" to PaletteTheme.light(example),
    )

    private fun shoot(name: String, content: @Composable () -> Unit) {
        for ((theme, colors) in themes) {
            captureRoboImage("build/screenshots/$name-$theme.png") {
                LatticeTheme(colors) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
                }
            }
        }
    }

    private fun tray(hub: Int, tray: Int, material: String, color: String?, active: Boolean = false, present: Boolean = true) = FilamentSlot(
        hubId = hub, trayId = tray, material = material, name = material, brand = "ELEGOO", color = color,
        present = present, active = active, minNozzleTemp = 190, maxNozzleTemp = 230,
    )

    private val trays = listOf(
        tray(0, 0, "PLA", "#F28C28", active = true),
        tray(0, 1, "PLA", "#1B1B1B"),
        tray(0, 2, "PLA", "#D9D9D9"),
        tray(0, 3, "PETG", "#FFFFFF"),
    )

    private val printing = PrinterState(
        connection = ConnectionState.Connected,
        machine = MachineState.PRINTING,
        job = PrintJob("lattice_vase_PETG_0.2.gcode", 64, 131, 204, 3000, 4320, "t1"),
        nozzle = Temperature(240.0, 240.0),
        bed = Temperature(80.0, 80.0),
        chamber = Temperature(38.0, 0.0),
        fans = mapOf(Fan.MODEL to 100, Fan.AUX to 40, Fan.CHAMBER to 0),
        lightOn = true,
        speedMode = SpeedMode.BALANCED,
        position = Position(120.0, 110.0, 5.2),
        filament = FilamentSystem(listOf(FilamentHub(0, 38.0, trays)), tray(-1, -1, "PLA", "#3EA3FF"), autoRefill = false),
    )

    private val idle = printing.copy(
        machine = MachineState.IDLE, job = null,
        nozzle = Temperature(28.0, 0.0), bed = Temperature(24.0, 0.0), speedMode = null,
    )

    private val printers = listOf(
        SavedPrinter("a", PrinterKind.CC2, "Workshop CC2", "Centauri Carbon 2", "192.168.1.40", "SN1", "123456"),
        SavedPrinter("b", PrinterKind.CC2, "Garage CC2", "Centauri Carbon 2", "10.0.4.12", "SN2", "123456"),
    )

    private val camera: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().background(Color(0xFF15181B)), contentAlignment = Alignment.Center) { Text("Live camera", color = Color.Gray) }
    }

    private val files = listOf(
        PrinterFile("desk_tidy_3colour_0.2.gcode", 4_200_000, 1_792_578_688, 11_100, 412, 88.0,
            listOf(FileColor(0, "#FFFFFF", "PLA"), FileColor(1, "#1B1B1B", "PLA"), FileColor(2, "#F28C28", "PETG")), false),
        PrinterFile("benchy_PLA_0.2_40m.gcode", 5_354_917, 1_792_478_688, 2_435, 204, 16.6, listOf(FileColor(0, "#FFFFFF", "PLA")), false),
        PrinterFile("cable_clip_x8_PETG.gcode", 1_200_000, 1_792_378_688, 3_120, 96, 21.0, listOf(FileColor(0, "#3EA3FF", "PETG")), false),
    )

    @Test
    fun printers() = shoot("01-printers") {
        PrinterListContent(printers, sharing = true, onAdd = {}, onAppearance = {}, onCancelShare = {}) { p ->
            PrinterCardContent(p, if (p.id == "a") printing else idle, sharing = true, onClick = {}, onSendAndPrint = {})
        }
    }

    @Test
    fun devicePrinting() = shoot("02-device-printing") {
        PrinterScaffold("Workshop CC2", printing, PrinterTab.DEVICE, onTab = {}, onBack = {}) {
            DeviceHome(printing, null, emptySet(), camera, DeviceActions())
        }
    }

    @Test
    fun deviceIdle() = shoot("03-device-idle") {
        PrinterScaffold("Garage CC2", idle, PrinterTab.DEVICE, onTab = {}, onBack = {}) {
            DeviceHome(idle, null, emptySet(), camera, DeviceActions())
        }
    }

    @Test
    fun move() = shoot("04-move") {
        MovePage(idle, camera, onJog = { _, _ -> }, onHome = {}, onBack = {})
    }

    @Test
    fun filament() = shoot("05-filament") {
        FilamentContent(idle, onBack = {}, onTray = {}, onAutoRefill = {})
    }

    @Test
    fun filesList() = shoot("06-files") {
        PrinterScaffold("Garage CC2", idle, PrinterTab.FILES, onTab = {}, onBack = {}) {
            FilesContent(
                FilesUi(Storage.LOCAL, files, space = StorageInfo(7_700_000_000, 3_300_000_000)),
                emptyMap(), connected = true, busy = false,
                onStorage = {}, onRefresh = {}, onUpload = {}, onUploadAndPrint = {}, onVisible = {}, onOpen = {},
            )
        }
    }

    @Test
    fun history() = shoot("07-history") {
        val day = 86_400L
        PrinterScaffold("Workshop CC2", idle, PrinterTab.HISTORY, onTab = {}, onBack = {}) {
            HistoryContent(
                HistoryUi(
                    listOf(
                        HistoryEntry("1", "lattice_vase_PETG_0.2.gcode", 1_792_578_688, 1_792_578_688 + 12_000, HistoryResult.COMPLETED, Timelapse(true, "/v.mp4", 1, 30)),
                        HistoryEntry("2", "desk_tidy_3colour_0.2.gcode", 1_792_578_688 - day, 1_792_578_688 - day + 3_060, HistoryResult.COMPLETED, null),
                        HistoryEntry("3", "test-jar-lid.gcode", 1_792_578_688 - 3 * day, 1_792_578_688 - 3 * day + 4_300, HistoryResult.STOPPED, null),
                    ),
                ),
                emptyMap(), onVisible = {}, onOpen = {},
            )
        }
    }

    @Test
    fun settings() = shoot("08-settings") {
        PrinterScaffold("Workshop CC2", idle, PrinterTab.SETTINGS, onTab = {}, onBack = {}) {
            SettingsContent("Workshop CC2", "Centauri Carbon 2", "F01ACB2C1A2B3C4D", "02.01.01.07", "192.168.1.40", "Ocean", {}, {}, {}, {})
        }
    }

    @Test
    fun printSheet() = shoot("09-print-sheet") {
        val file = files[0]
        val prefs = PrintPreferences(BuildPlate.TEXTURED, autoBedLeveling = true, timelapse = false)
        PrintSheetContent(
            file, null, PrintPlanner.plan(file, Storage.LOCAL, idle, prefs), prefs, trays,
            onPrefs = {}, onChoose = { _, _ -> }, onPrint = {}, onDelete = {},
        )
    }

    @Test
    fun appearance() = shoot("10-appearance") {
        AppearanceContent(
            appearance = Appearance(ThemeMode.SYSTEM, example.id, listOf(example)),
            dynamic = null,
            link = "https://colorhunt.co/palette/f9f7f7dbe2ef3f72af112d4e",
            onLinkChange = {}, name = "", onNameChange = {},
            onBack = {}, onPaste = {}, onSave = {}, onSelect = {}, onMode = {}, onRename = { _, _ -> }, onDelete = {}, onCopyLink = {},
        )
    }
}
