package dev.openlattice.core.theme

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** A colour palette the app can be themed with. Colours are sRGB `0xRRGGBB`. */
data class Palette(
    val id: String,
    val name: String,
    val colors: List<Int>,
    /** Where it came from (a Colorhunt link), or null for built-ins. */
    val source: String? = null,
) {
    val builtIn: Boolean get() = source == null

    companion object {
        /** The default look: graphite and signal cyan, from the launcher icon. */
        val LATTICE = Palette("lattice", "Lattice", listOf(0x0F1215, 0x1F252C, 0x4FC3F7, 0xE8EDF2))

        /** Built-ins offered alongside the user's own palettes. */
        val BUILT_IN = listOf(LATTICE)
    }
}

/** Reads Colorhunt palette share links. Nothing is fetched: the colours are in the link itself. */
object Colorhunt {
    private val LINK = Regex("""colorhunt\.co/palette/([0-9a-fA-F]{24})(?![0-9a-fA-F])""", RegexOption.IGNORE_CASE)
    private val BARE = Regex("""^#?([0-9a-fA-F]{24})$""")

    /**
     * The 4 colours of a Colorhunt link such as `https://colorhunt.co/palette/071b2b167d8db9e4e8eeeeee`,
     * found anywhere in [text] (shared text often wraps the link in a sentence), or of the bare
     * 24-digit code. Null when there is no palette in it.
     */
    fun parse(text: String): List<Int>? {
        val code = LINK.find(text)?.groupValues?.get(1) ?: BARE.find(text.trim())?.groupValues?.get(1) ?: return null
        return code.chunked(6).map { it.toInt(16) }
    }

    fun code(colors: List<Int>): String = colors.joinToString("") { hex(it).removePrefix("#").lowercase() }

    fun link(colors: List<Int>): String = "https://colorhunt.co/palette/${code(colors)}"

    /** A palette from a pasted link, named [name] or after its colours. */
    fun palette(text: String, name: String? = null): Palette? {
        val colors = parse(text) ?: return null
        val code = code(colors)
        return Palette("ch-$code", name?.trim()?.takeIf { it.isNotEmpty() } ?: defaultName(colors), colors, link(colors))
    }

    fun defaultName(colors: List<Int>): String = colors.joinToString(" ") { hex(it) }.let { "Colorhunt $it".take(40) }
}

/** `#RRGGBB` (upper case). */
fun hex(rgb: Int): String = "#%06X".format(rgb and 0xFFFFFF)

/**
 * Colours for every role the UI paints, worked out from a [Palette] for dark or light mode.
 * Colours are sRGB `0xRRGGBB`.
 */
data class ThemeColors(
    val isDark: Boolean,
    val background: Int,
    val onBackground: Int,
    /** Cards. */
    val surface: Int,
    /** Tiles and inputs inside cards. */
    val surfaceHigh: Int,
    val surfaceHighest: Int,
    /** Secondary text on [surface]. */
    val onSurfaceVariant: Int,
    /** Dividers and borders. */
    val outline: Int,
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val secondary: Int,
    val onSecondary: Int,
    val error: Int,
    val onError: Int,
    val errorContainer: Int,
    val onErrorContainer: Int,
    /** Hot things: heating nozzle and bed. */
    val heat: Int,
    /** Done, idle, matches. */
    val ok: Int,
    /** Needs a look. */
    val warn: Int,
)

/**
 * Maps any palette to [ThemeColors] that stay readable: the background takes the palette's darkest
 * (or, in light mode, lightest) colour, the accent its most colourful one, and every pairing is
 * nudged until it meets WCAG contrast — text 4.5:1 or more, body text on the background 7:1.
 */
object PaletteTheme {
    const val TEXT_CONTRAST = 4.5
    const val BODY_CONTRAST = 7.0

    fun dark(palette: Palette): ThemeColors = build(palette.colors, dark = true)
    fun light(palette: Palette): ThemeColors = build(palette.colors, dark = false)

    private fun build(input: List<Int>, dark: Boolean): ThemeColors {
        val colors = input.ifEmpty { Palette.LATTICE.colors }.map { it and 0xFFFFFF }
        val byLum = colors.sortedBy(::luminance)

        // Ground: the darkest colour when it is already dark (else a deep tone of its hue);
        // mirrored for light mode.
        val groundSrc = if (dark) byLum.first() else byLum.last()
        val background = if (dark) {
            if (luminance(groundSrc) <= 0.03) groundSrc else withLuminance(capSat(groundSrc, 0.45), 0.012)
        } else {
            // Kept off pure white so white cards still stand out from it.
            if (luminance(groundSrc) in 0.82..0.93) groundSrc else withLuminance(capSat(groundSrc, 0.35), 0.88)
        }
        val surface = if (dark) shiftL(capSat(background, 0.4), 0.05) else mix(background, 0xFFFFFF, 0.75)
        val surfaceHigh = if (dark) shiftL(capSat(background, 0.4), 0.10) else shiftL(background, -0.04)
        val surfaceHighest = if (dark) shiftL(capSat(background, 0.4), 0.15) else shiftL(background, -0.09)
        // The ground text has the hardest time on: the lightest surface in dark mode, the darkest in light.
        val hardest = listOf(surface, surfaceHigh, surfaceHighest, background).let { s -> if (dark) s.maxBy(::luminance) else s.minBy(::luminance) }

        // Text: the palette's lightest (darkest in light mode) colour when it reads well, else near white/black.
        val textSrc = if (dark) byLum.last() else byLum.first()
        val onBackground = readable(
            if (dark) (if (luminance(textSrc) >= 0.7) textSrc else mix(textSrc, 0xFFFFFF, 0.85))
            else (if (luminance(textSrc) <= 0.03) textSrc else mix(textSrc, 0x000000, 0.85)),
            listOf(background, hardest), BODY_CONTRAST, dark,
        )
        val onSurfaceVariant = readable(mix(onBackground, surface, 0.38), listOf(surface, hardest), TEXT_CONTRAST, dark)
        val outline = mix(onBackground, surface, if (dark) 0.78 else 0.8)

        // Accent: the most colourful colour that is not the ground; then a second, different hue.
        val candidates = colors.filter { it != groundSrc }.ifEmpty { colors }
        val accentSrc = candidates.maxBy { chroma(it) + 0.001 * contrast(it, background) }
        val primary = readable(accentSrc, listOf(surface, hardest), TEXT_CONTRAST, dark)
        val secondSrc = candidates.filter { it != accentSrc }
            .maxByOrNull { chroma(it) * (0.3 + hueDistance(it, accentSrc)) + 0.2 * chroma(it) } ?: accentSrc
        val secondary = readable(secondSrc, listOf(surface, hardest), TEXT_CONTRAST, dark)

        val primaryContainer = mix(primary, surface, if (dark) 0.78 else 0.82)
        val onPrimaryContainer = readable(if (dark) shiftL(primary, 0.12) else shiftL(primary, -0.12), listOf(primaryContainer), TEXT_CONTRAST, dark)

        val error = readable(if (dark) 0xFF6B6B else 0xC62828, listOf(surface, hardest), TEXT_CONTRAST, dark)
        val errorContainer = mix(error, surface, if (dark) 0.8 else 0.85)

        return ThemeColors(
            isDark = dark,
            background = background,
            onBackground = onBackground,
            surface = surface,
            surfaceHigh = surfaceHigh,
            surfaceHighest = surfaceHighest,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            primary = primary,
            onPrimary = onColor(primary),
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = secondary,
            onSecondary = onColor(secondary),
            error = error,
            onError = onColor(error),
            errorContainer = errorContainer,
            onErrorContainer = readable(error, listOf(errorContainer), TEXT_CONTRAST, dark),
            heat = readable(if (dark) 0xFF8A65 else 0xC2410C, listOf(surface, hardest), TEXT_CONTRAST, dark),
            ok = readable(if (dark) 0x6FD08C else 0x1E7B45, listOf(surface, hardest), TEXT_CONTRAST, dark),
            warn = readable(if (dark) 0xFFC857 else 0x8A5A00, listOf(surface, hardest), TEXT_CONTRAST, dark),
        )
    }

    /** Black or white text, whichever reads better on [bg]. Always at least 4.58:1. */
    fun onColor(bg: Int): Int = if (contrast(0x0B0B0B, bg) >= contrast(0xFFFFFF, bg)) 0x0B0B0B else 0xFFFFFF

    /**
     * [fg] made light enough (dark mode) or dark enough (light mode) to reach [min] contrast
     * against every colour in [bgs]. Keeps the hue; falls back to white or black.
     */
    fun readable(fg: Int, bgs: List<Int>, min: Double, dark: Boolean): Int {
        fun ok(c: Int) = bgs.all { contrast(c, it) >= min }
        if (ok(fg)) return fg
        val (h, s, l) = hsl(fg)
        var light = l
        repeat(100) {
            light = if (dark) light + 0.01 else light - 0.01
            if (light !in 0.0..1.0) return if (dark) 0xFFFFFF else 0x000000
            val c = fromHsl(h, s, light)
            if (ok(c)) return c
        }
        return if (dark) 0xFFFFFF else 0x000000
    }

    // --- colour maths -------------------------------------------------------------------------

    /** WCAG relative luminance, 0..1. */
    fun luminance(rgb: Int): Double {
        fun ch(v: Int): Double = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(rgb shr 16 and 0xFF) + 0.7152 * ch(rgb shr 8 and 0xFF) + 0.0722 * ch(rgb and 0xFF)
    }

    /** WCAG contrast ratio, 1..21. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    fun mix(a: Int, b: Int, t: Double): Int {
        fun ch(shift: Int) = ((a shr shift and 0xFF) * (1 - t) + (b shr shift and 0xFF) * t).toInt().coerceIn(0, 255)
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** HSL chroma, 0..1: how colourful, regardless of lightness. */
    fun chroma(rgb: Int): Double {
        val (_, s, l) = hsl(rgb)
        return s * (1 - abs(2 * l - 1))
    }

    private fun hueDistance(a: Int, b: Int): Double {
        val d = abs(hsl(a).first - hsl(b).first)
        return min(d, 360 - d) / 180.0
    }

    private fun capSat(rgb: Int, maxSat: Double): Int {
        val (h, s, l) = hsl(rgb)
        return fromHsl(h, min(s, maxSat), l)
    }

    private fun shiftL(rgb: Int, by: Double): Int {
        val (h, s, l) = hsl(rgb)
        return fromHsl(h, s, (l + by).coerceIn(0.0, 1.0))
    }

    /** Same hue and saturation, lightness searched until the luminance is close to [target]. */
    private fun withLuminance(rgb: Int, target: Double): Int {
        val (h, s, _) = hsl(rgb)
        var lo = 0.0
        var hi = 1.0
        repeat(30) {
            val mid = (lo + hi) / 2
            if (luminance(fromHsl(h, s, mid)) < target) lo = mid else hi = mid
        }
        return fromHsl(h, s, (lo + hi) / 2)
    }

    /** (hue 0..360, saturation 0..1, lightness 0..1). */
    fun hsl(rgb: Int): Triple<Double, Double, Double> {
        val r = (rgb shr 16 and 0xFF) / 255.0
        val g = (rgb shr 8 and 0xFF) / 255.0
        val b = (rgb and 0xFF) / 255.0
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2
        if (mx == mn) return Triple(0.0, 0.0, l)
        val d = mx - mn
        val s = if (l > 0.5) d / (2 - mx - mn) else d / (mx + mn)
        val h = when (mx) {
            r -> ((g - b) / d + (if (g < b) 6 else 0)) * 60
            g -> ((b - r) / d + 2) * 60
            else -> ((r - g) / d + 4) * 60
        }
        return Triple(h, s, l)
    }

    fun fromHsl(h: Double, s: Double, l: Double): Int {
        if (s == 0.0) {
            val v = (l * 255).toInt().coerceIn(0, 255)
            return (v shl 16) or (v shl 8) or v
        }
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun hue(tIn: Double): Double {
            var t = tIn
            if (t < 0) t += 1
            if (t > 1) t -= 1
            return when {
                t < 1.0 / 6 -> p + (q - p) * 6 * t
                t < 1.0 / 2 -> q
                t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
                else -> p
            }
        }
        val hn = h / 360
        fun ch(v: Double) = (v * 255).toInt().coerceIn(0, 255)
        return (ch(hue(hn + 1.0 / 3)) shl 16) or (ch(hue(hn)) shl 8) or ch(hue(hn - 1.0 / 3))
    }
}
