package dev.openlattice.app.data

import dev.openlattice.cc2.Cc2Driver
import dev.openlattice.core.ConnectionState
import dev.openlattice.core.PrinterDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns one [PrinterDriver] per saved printer and shares it between screens, the monitor service
 * and the periodic check.
 *
 * Users take a lease with [acquire]. Screen leases only keep the session up while the app is in
 * the foreground; background leases (monitor, worker) keep it up regardless. A printer accepts
 * only a handful of clients, so idle sessions are dropped: on going to the background, and
 * shortly after the last lease is released.
 */
class PrinterConnections(private val store: PrinterStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val drivers = mutableMapOf<String, Entry>()
    private var foreground = false

    private class Entry(val config: SavedPrinter, val driver: PrinterDriver) {
        var uiLeases = 0
        var bgLeases = 0
        var closer: Job? = null
        val leases get() = uiLeases + bgLeases
    }

    /** Returns the shared driver and makes sure its session is up. Pair with [release]. */
    @Synchronized
    fun acquire(printer: SavedPrinter, background: Boolean = false): PrinterDriver {
        val existing = drivers[printer.id]
        val entry = if (existing != null && existing.config == printer) {
            existing
        } else {
            existing?.let { drivers.remove(printer.id); it.closer?.cancel(); it.driver.close() }
            Entry(printer, create(printer)).also { drivers[printer.id] = it }
        }
        if (background) entry.bgLeases++ else entry.uiLeases++
        entry.closer?.cancel()
        entry.closer = null
        if (background || foreground) ensureConnected(entry)
        return entry.driver
    }

    @Synchronized
    fun release(printerId: String, background: Boolean = false) {
        val entry = drivers[printerId] ?: return
        if (background) entry.bgLeases = (entry.bgLeases - 1).coerceAtLeast(0)
        else entry.uiLeases = (entry.uiLeases - 1).coerceAtLeast(0)
        if (entry.leases == 0) {
            entry.closer = scope.launch {
                delay(IDLE_CLOSE_MS)
                synchronized(this@PrinterConnections) {
                    if (entry.leases == 0 && drivers[printerId] === entry) {
                        drivers.remove(printerId)
                        entry.driver.close()
                    }
                }
            }
        } else if (entry.bgLeases == 0 && !foreground) {
            scope.launch { entry.driver.disconnect() }
        }
    }

    /** Called by the app on process foreground/background transitions. */
    @Synchronized
    fun setForeground(value: Boolean) {
        foreground = value
        for (entry in drivers.values) {
            when {
                value && entry.uiLeases > 0 -> ensureConnected(entry)
                !value && entry.bgLeases == 0 -> scope.launch { entry.driver.disconnect() }
            }
        }
    }

    /** Forces a fresh connection attempt (e.g. after the user fixed the access code). */
    fun reconnect(printerId: String) {
        val entry = synchronized(this) { drivers[printerId] } ?: return
        scope.launch { runCatching { entry.driver.connect() } }
    }

    @Synchronized
    fun forget(printerId: String) {
        drivers.remove(printerId)?.let { it.closer?.cancel(); it.driver.close() }
    }

    private fun ensureConnected(entry: Entry) {
        val c = entry.driver.state.value.connection
        if (c == ConnectionState.Connected || c == ConnectionState.Connecting) return
        scope.launch { runCatching { entry.driver.connect() } }
    }

    private fun create(p: SavedPrinter): PrinterDriver = when (p.kind) {
        PrinterKind.CC2 -> Cc2Driver(p.host, p.serial, p.accessCode)
    }

    companion object {
        private const val IDLE_CLOSE_MS = 30_000L
    }
}
