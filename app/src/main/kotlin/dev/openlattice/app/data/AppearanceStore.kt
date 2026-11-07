package dev.openlattice.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.openlattice.core.theme.Palette
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** How the app looks: light/dark, and which palette colours it. */
data class Appearance(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    /** A palette id, or [DYNAMIC_ID] for the phone's own wallpaper colours (Android 12+). */
    val activeId: String = Palette.LATTICE.id,
    /** Palettes the user added, newest first. */
    val saved: List<Palette> = emptyList(),
) {
    val palettes: List<Palette> get() = Palette.BUILT_IN + saved
    /** The palette in use; null when using the phone's colours. */
    val active: Palette? get() = if (activeId == DYNAMIC_ID) null else palettes.firstOrNull { it.id == activeId } ?: Palette.LATTICE

    companion object {
        const val DYNAMIC_ID = "system-dynamic"
    }
}

@Serializable
private data class StoredPalette(val id: String, val name: String, val colors: List<Int>, val source: String? = null)

private val Context.appearanceDataStore by preferencesDataStore(name = "appearance")

/** Theme mode, the active palette and the user's palette list, kept on the phone. */
class AppearanceStore(private val context: Context) {
    private val modeKey = stringPreferencesKey("mode")
    private val activeKey = stringPreferencesKey("active")
    private val savedKey = stringPreferencesKey("palettes_v1")
    private val json = Json { ignoreUnknownKeys = true }

    val appearance: Flow<Appearance> = context.appearanceDataStore.data.map { prefs ->
        Appearance(
            mode = prefs[modeKey]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            activeId = prefs[activeKey] ?: Palette.LATTICE.id,
            saved = decode(prefs[savedKey]),
        )
    }

    suspend fun setMode(mode: ThemeMode) {
        context.appearanceDataStore.edit { it[modeKey] = mode.name }
    }

    suspend fun select(id: String) {
        context.appearanceDataStore.edit { it[activeKey] = id }
    }

    /** Adds [palette] to the top of the list (replacing one with the same colours) and, by default, switches to it. */
    suspend fun add(palette: Palette, activate: Boolean = true) {
        context.appearanceDataStore.edit { prefs ->
            val list = decode(prefs[savedKey]).filterNot { it.id == palette.id }
            prefs[savedKey] = encode(listOf(palette) + list)
            if (activate) prefs[activeKey] = palette.id
        }
    }

    suspend fun rename(id: String, name: String) {
        context.appearanceDataStore.edit { prefs ->
            prefs[savedKey] = encode(decode(prefs[savedKey]).map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }) else it })
        }
    }

    /** Removes a saved palette; if it was in use, the app goes back to the default look. */
    suspend fun remove(id: String) {
        context.appearanceDataStore.edit { prefs ->
            prefs[savedKey] = encode(decode(prefs[savedKey]).filterNot { it.id == id })
            if (prefs[activeKey] == id) prefs[activeKey] = Palette.LATTICE.id
        }
    }

    private fun decode(raw: String?): List<Palette> =
        raw?.let { runCatching { json.decodeFromString<List<StoredPalette>>(it) }.getOrNull() }.orEmpty()
            .filter { it.colors.isNotEmpty() }
            .map { Palette(it.id, it.name, it.colors, it.source) }

    private fun encode(list: List<Palette>): String =
        json.encodeToString(list.map { StoredPalette(it.id, it.name, it.colors, it.source) })
}
