package dev.openlattice.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Accent = Color(0xFF4FC3F7)
private val Hot = Color(0xFFFF8A65)

private val Dark = darkColorScheme(primary = Accent, secondary = Hot, tertiary = Color(0xFF81C784))
private val Light = lightColorScheme(primary = Color(0xFF0277BD), secondary = Color(0xFFE64A19), tertiary = Color(0xFF2E7D32))

@Composable
fun OpenLatticeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
