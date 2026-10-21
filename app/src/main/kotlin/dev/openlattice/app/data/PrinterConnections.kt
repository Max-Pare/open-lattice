package dev.openlattice.app.data

import dev.openlattice.cc2.Cc2Driver
import dev.openlattice.core.PrinterDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns one [PrinterDriver] per saved printer and shares it between screens and the monitor service.
 * Each user ([acquire]) holds a lease; the connection is closed a short while after the last lease is
 * released, so navigating between screens does not reconnect but idle printers free their client slot.
 */
class PrinterConnections(private val store: PrinterStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val drivers = mutableMapOf<String, Entry>()

    private class Entry(val config: SavedPrinter, val driver: PrinterDriver) {
        var leases = 0
        var closer: Job? = null
    }

    /** Returns the shared driver, connecting it if needed. Pair with [release]. */
    @Synchronized
    fun acquire(printer: SavedPrinter): PrinterDriver {
        val existing = drivers[printer.id]
        val entry = if (existing != null && existing.config == printer) {
            existing
        } else {
            existing?.let { drivers.remove(printer.id); it.closer?.cancel(); it.driver.close() }
            Entry(printer, create(printer)).also {
                drivers[printer.id] = it
                scope.launch { runCatching { it.driver.connect() } }
            }
        }
        entry.leases++
        entry.closer?.cancel()
        entry.closer = null
        return entry.driver
    }

    @Synchronized
    fun release(printerId: String) {
        val entry = drivers[printerId] ?: return
        entry.leases = (entry.leases - 1).coerceAtLeast(0)
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

    private fun create(p: SavedPrinter): PrinterDriver = when (p.kind) {
        PrinterKind.CC2 -> Cc2Driver(p.host, p.serial, p.accessCode)
    }

    companion object {
        private const val IDLE_CLOSE_MS = 30_000L
    }
}
