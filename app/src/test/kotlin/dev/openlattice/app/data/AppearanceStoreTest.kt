package dev.openlattice.app.data

import android.app.Application
import dev.openlattice.core.theme.Colorhunt
import dev.openlattice.core.theme.Palette
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppearanceStoreTest {
    // One test: DataStore keeps one instance per file for the whole process.
    @Test
    fun `palettes are saved, switched, renamed and removed`() = runTest {
        val store = AppearanceStore(RuntimeEnvironment.getApplication())
        val ocean = Colorhunt.palette("https://colorhunt.co/palette/071b2b167d8db9e4e8eeeeee", "Ocean")!!
        val sky = Colorhunt.palette("https://colorhunt.co/palette/f9f7f7dbe2ef3f72af112d4e", "Sky")!!

        var a = store.appearance.first()
        assertEquals(ThemeMode.SYSTEM, a.mode)
        assertEquals(Palette.LATTICE, a.active)
        assertEquals(emptyList<Palette>(), a.saved)

        store.add(ocean)
        store.add(sky)
        a = store.appearance.first()
        assertEquals(listOf(sky, ocean), a.saved)
        assertEquals(sky, a.active)

        // Pasting a link that is already saved moves it to the top instead of adding a copy.
        store.add(ocean.copy(name = "Ocean again"))
        a = store.appearance.first()
        assertEquals(listOf("Ocean again", "Sky"), a.saved.map { it.name })
        assertEquals(ocean.id, a.activeId)

        store.select(sky.id)
        store.rename(sky.id, "  Clear sky ")
        a = store.appearance.first()
        assertEquals("Clear sky", a.active?.name)

        // Removing the palette in use falls back to the default look.
        store.remove(sky.id)
        a = store.appearance.first()
        assertEquals(Palette.LATTICE, a.active)
        assertEquals(listOf(ocean.id), a.saved.map { it.id })

        store.setMode(ThemeMode.DARK)
        store.select(Appearance.DYNAMIC_ID)
        a = store.appearance.first()
        assertEquals(ThemeMode.DARK, a.mode)
        assertEquals(null, a.active)
    }

    @Test
    fun `an unknown active id falls back to the default palette`() {
        assertEquals(Palette.LATTICE, Appearance(activeId = "gone").active)
    }
}
