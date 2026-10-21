package dev.openlattice.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class PrinterKind { CC2 }

/** A printer the user added. [accessCode] is held decrypted in memory only. */
data class SavedPrinter(
    val id: String,
    val kind: PrinterKind,
    val name: String,
    val model: String,
    val host: String,
    val serial: String,
    val accessCode: String,
)

@Serializable
private data class StoredPrinter(
    val id: String,
    val kind: PrinterKind,
    val name: String,
    val model: String,
    val host: String,
    val serial: String,
    val accessCodeSealed: String,
)

private val Context.printerDataStore by preferencesDataStore(name = "printers")

class PrinterStore(private val context: Context) {
    private val key = stringPreferencesKey("printers_v1")
    private val json = Json { ignoreUnknownKeys = true }

    val printers: Flow<List<SavedPrinter>> = context.printerDataStore.data.map { prefs ->
        val stored = prefs[key]?.let { runCatching { json.decodeFromString<List<StoredPrinter>>(it) }.getOrNull() }.orEmpty()
        stored.map {
            SavedPrinter(
                id = it.id, kind = it.kind, name = it.name, model = it.model, host = it.host, serial = it.serial,
                accessCode = SecretBox.decrypt(it.accessCodeSealed).orEmpty(),
            )
        }
    }

    suspend fun get(id: String): SavedPrinter? = printers.first().firstOrNull { it.id == id }

    suspend fun upsert(printer: SavedPrinter) = edit { list ->
        val stored = StoredPrinter(
            id = printer.id, kind = printer.kind, name = printer.name, model = printer.model,
            host = printer.host, serial = printer.serial, accessCodeSealed = SecretBox.encrypt(printer.accessCode),
        )
        val i = list.indexOfFirst { it.id == printer.id }
        if (i >= 0) list.toMutableList().apply { set(i, stored) } else list + stored
    }

    suspend fun remove(id: String) = edit { list -> list.filterNot { it.id == id } }

    private suspend fun edit(transform: (List<StoredPrinter>) -> List<StoredPrinter>) {
        context.printerDataStore.edit { prefs ->
            val current = prefs[key]?.let { runCatching { json.decodeFromString<List<StoredPrinter>>(it) }.getOrNull() }.orEmpty()
            prefs[key] = json.encodeToString(transform(current))
        }
    }
}
