package dev.openlattice.core.theme

import dev.openlattice.core.theme.PaletteTheme.contrast
import dev.openlattice.core.theme.PaletteTheme.hsl
import dev.openlattice.core.theme.PaletteTheme.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class PaletteTest {
    private val example = "https://colorhunt.co/palette/071b2b167d8db9e4e8eeeeee"

    @Test
    fun `parses a Colorhunt link`() {
        assertEquals(listOf(0x071B2B, 0x167D8D, 0xB9E4E8, 0xEEEEEE), Colorhunt.parse(example))
    }

    @Test
    fun `finds the link inside shared text and ignores case`() {
        val text = "Check out this palette! HTTPS://ColorHunt.co/palette/071B2B167D8DB9E4E8EEEEEE?ref=share #colorhunt"
        assertEquals(listOf(0x071B2B, 0x167D8D, 0xB9E4E8, 0xEEEEEE), Colorhunt.parse(text))
    }

    @Test
    fun `accepts the bare code`() {
        assertEquals(4, Colorhunt.parse(" #071b2b167d8db9e4e8eeeeee ")!!.size)
    }

    @Test
    fun `rejects anything else`() {
        assertNull(Colorhunt.parse("https://colorhunt.co/palette/071b2b167d8db9e4e8eeeee")) // 23 digits
        assertNull(Colorhunt.parse("https://colorhunt.co/palette/071b2b167d8db9e4e8eeeeee0")) // 25 digits
        assertNull(Colorhunt.parse("https://example.com/palette/071b2b167d8db9e4e8eeeeee"))
        assertNull(Colorhunt.parse("https://colorhunt.co/palettes/popular"))
        assertNull(Colorhunt.parse(""))
    }

    @Test
    fun `palette from a link round-trips and is named after its colours`() {
        val p = Colorhunt.palette(example)!!
        assertEquals(example, p.source)
        assertEquals("ch-071b2b167d8db9e4e8eeeeee", p.id)
        assertEquals("Colorhunt #071B2B #167D8D #B9E4E8 #EEEEEE".take(40), p.name)
        assertEquals("Ocean", Colorhunt.palette(example, "  Ocean ")!!.name)
    }

    @Test
    fun `example palette keeps its own colours where they read well`() {
        val p = Colorhunt.palette(example)!!
        val dark = PaletteTheme.dark(p)
        assertEquals(0x071B2B, dark.background)
        assertEquals(0xEEEEEE, dark.onBackground)
        assertTrue("accent keeps the teal hue", hueNear(dark.primary, 0x167D8D))

        val light = PaletteTheme.light(p)
        assertEquals(0xEEEEEE, light.background)
        assertEquals(0x071B2B, light.onBackground)
        assertTrue("accent keeps the teal hue", hueNear(light.primary, 0x167D8D))
    }

    @Test
    fun `default palette maps to the graphite look`() {
        val dark = PaletteTheme.dark(Palette.LATTICE)
        assertEquals(0x0F1215, dark.background)
        assertEquals(0x4FC3F7, dark.primary)
        assertEquals(0xE8EDF2, dark.onBackground)
    }

    @Test
    fun `any palette gives readable colours in both modes`() {
        val rnd = Random(42)
        val awkward = listOf(
            listOf(0x000000, 0x000000, 0x000000, 0x000000),
            listOf(0xFFFFFF, 0xFFFFFF, 0xFFFFFF, 0xFFFFFF),
            listOf(0x808080, 0x7F7F7F, 0x818181, 0x808080),
            listOf(0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00),
            listOf(0xFFF5E1, 0xFFC0CB, 0xFFE4B5, 0xF0FFF0),
        )
        val palettes = awkward + List(500) { List(4) { rnd.nextInt(0x1000000) } }
        for (colors in palettes) {
            val p = Palette("t", "t", colors)
            for (t in listOf(PaletteTheme.dark(p), PaletteTheme.light(p))) {
                val tag = "${colors.joinToString { hex(it) }} dark=${t.isDark}"
                check(tag, "text on background", t.onBackground, t.background, 7.0)
                for (s in listOf(t.surface, t.surfaceHigh, t.surfaceHighest)) {
                    check(tag, "text on surfaces", t.onBackground, s, 4.5)
                }
                check(tag, "secondary text", t.onSurfaceVariant, t.surface, 4.5)
                check(tag, "secondary text on tiles", t.onSurfaceVariant, t.surfaceHigh, 4.5)
                check(tag, "accent on cards", t.primary, t.surface, 4.5)
                check(tag, "text on accent", t.onPrimary, t.primary, 4.5)
                check(tag, "text on accent container", t.onPrimaryContainer, t.primaryContainer, 4.5)
                check(tag, "error on cards", t.error, t.surface, 4.5)
                check(tag, "error container text", t.onErrorContainer, t.errorContainer, 4.5)
                for ((name, c) in listOf("heat" to t.heat, "ok" to t.ok, "warn" to t.warn, "secondary" to t.secondary)) {
                    check(tag, name, c, t.surface, 4.5)
                }
                assertTrue("$tag: cards lighter than ground", luminance(t.surface) >= luminance(t.background))
            }
        }
    }

    private fun check(tag: String, what: String, fg: Int, bg: Int, min: Double) {
        val c = contrast(fg, bg)
        assertTrue("$tag: $what ${hex(fg)} on ${hex(bg)} is %.2f:1, needs %.1f:1".format(c, min), c >= min)
    }

    private fun hueNear(a: Int, b: Int): Boolean {
        val d = abs(hsl(a).first - hsl(b).first)
        return minOf(d, 360 - d) <= 12
    }
}
