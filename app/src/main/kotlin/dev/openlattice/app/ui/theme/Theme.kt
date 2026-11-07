package dev.openlattice.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import dev.openlattice.app.data.Appearance
import dev.openlattice.app.data.ThemeMode
import dev.openlattice.core.theme.Palette
import dev.openlattice.core.theme.PaletteTheme
import dev.openlattice.core.theme.ThemeColors

/** Colours the Material scheme has no slot for. Read them with `Lattice.colors`. */
@Immutable
data class LatticeColors(
    val isDark: Boolean,
    /** Cards and panels. */
    val panel: Color,
    /** Tiles and inputs inside panels. */
    val tile: Color,
    val tileHigh: Color,
    val heat: Color,
    val ok: Color,
    val warn: Color,
    val muted: Color,
    val divider: Color,
)

val LocalLatticeColors = staticCompositionLocalOf { latticeColors(PaletteTheme.dark(Palette.LATTICE)) }

object Lattice {
    val colors: LatticeColors @Composable get() = LocalLatticeColors.current
}

fun c(rgb: Int) = Color(0xFF000000.toInt() or rgb)

fun latticeColors(t: ThemeColors) = LatticeColors(
    isDark = t.isDark,
    panel = c(t.surface),
    tile = c(t.surfaceHigh),
    tileHigh = c(t.surfaceHighest),
    heat = c(t.heat),
    ok = c(t.ok),
    warn = c(t.warn),
    muted = c(t.onSurfaceVariant),
    divider = c(t.outline),
)

/** Material roles from palette colours. Top bars sit on the ground; cards use [LatticeColors.panel]. */
fun ThemeColors.toColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c(primary), onPrimary = c(onPrimary),
        primaryContainer = c(primaryContainer), onPrimaryContainer = c(onPrimaryContainer),
        inversePrimary = c(primary),
        secondary = c(secondary), onSecondary = c(onSecondary),
        secondaryContainer = c(primaryContainer), onSecondaryContainer = c(onPrimaryContainer),
        tertiary = c(ok), onTertiary = c(PaletteTheme.onColor(ok)),
        tertiaryContainer = c(surfaceHigh), onTertiaryContainer = c(ok),
        background = c(background), onBackground = c(onBackground),
        surface = c(background), onSurface = c(onBackground),
        surfaceVariant = c(surfaceHigh), onSurfaceVariant = c(onSurfaceVariant),
        surfaceTint = c(primary),
        inverseSurface = c(onBackground), inverseOnSurface = c(background),
        error = c(error), onError = c(onError),
        errorContainer = c(errorContainer), onErrorContainer = c(onErrorContainer),
        outline = c(outline), outlineVariant = c(surfaceHighest),
        scrim = Color.Black,
        surfaceBright = c(surfaceHighest), surfaceDim = c(background),
        surfaceContainerLowest = c(background), surfaceContainerLow = c(surface),
        surfaceContainer = c(surface), surfaceContainerHigh = c(surface),
        surfaceContainerHighest = c(surfaceHigh),
    )
}

private val Tabular = "tnum"

val LatticeTypography = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = Tabular),
        displayMedium = t.displayMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = Tabular),
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = Tabular),
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = Tabular),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = Tabular),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = Tabular),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.copy(fontSize = 16.sp),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Big numeric readouts (temperatures, progress). */
val Readout = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = Tabular)

val LatticeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Theme for a fixed palette and mode; used by the app and by screenshot tests. */
@Composable
fun LatticeTheme(colors: ThemeColors, content: @Composable () -> Unit) {
    val scheme = remember(colors) { colors.toColorScheme() }
    val lattice = remember(colors) { latticeColors(colors) }
    CompositionLocalProvider(LocalLatticeColors provides lattice) {
        MaterialTheme(colorScheme = scheme, typography = LatticeTypography, shapes = LatticeShapes, content = content)
    }
}

/** Resolves [appearance] (mode, palette or the phone's own colours) to [ThemeColors]. */
@Composable
fun resolveColors(appearance: Appearance): ThemeColors {
    val dark = when (appearance.mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val palette = appearance.active ?: dynamicPalette(dark) ?: Palette.LATTICE
    return remember(palette, dark) { if (dark) PaletteTheme.dark(palette) else PaletteTheme.light(palette) }
}

/** The phone's wallpaper colours (Android 12+) as a palette, so they go through the same mapping. */
@Composable
fun dynamicPalette(dark: Boolean): Palette? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val ctx = LocalContext.current
    val s = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    val colors = listOf(s.background, s.surfaceVariant, s.primary, s.onBackground).map { it.toArgb() and 0xFFFFFF }
    return Palette(Appearance.DYNAMIC_ID, "Phone colours", colors)
}

@Composable
fun OpenLatticeTheme(appearance: Appearance = Appearance(), content: @Composable () -> Unit) {
    val colors = resolveColors(appearance)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !colors.isDark
                isAppearanceLightNavigationBars = !colors.isDark
            }
        }
    }
    LatticeTheme(colors, content)
}
