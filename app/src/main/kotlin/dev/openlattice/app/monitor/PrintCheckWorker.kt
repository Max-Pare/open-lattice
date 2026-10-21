package dev.openlattice.app.monitor

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.openlattice.app.ui.app
import dev.openlattice.core.ConnectionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Every 15 minutes (Android's minimum), while the live monitor is not running: connect to each
 * printer briefly, post a progress snapshot for running prints and alerts for anything that
 * happened since the last look. Catches prints started from the printer while the app was closed.
 */
class PrintCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (PrintMonitorService.isRunning) return Result.success()
        val ctx = applicationContext
        val app = ctx.app
        val lastSeen = LastSeen(ctx)
        MonitorNotifications.createChannels(ctx)

        for (printer in app.store.printers.first()) {
            val driver = app.connections.acquire(printer, background = true)
            try {
                val state = withTimeoutOrNull(45_000) {
                    driver.state.first { it.connection == ConnectionState.Connected && it.machine != dev.openlattice.core.MachineState.UNKNOWN }
                } ?: continue
                lastSeen.get(printer.id)?.let { before ->
                    alertFor(printer.name.ifEmpty { printer.model }, before, state)?.let { MonitorNotifications.postAlert(ctx, printer, it) }
                }
                lastSeen.put(printer.id, state)
                MonitorNotifications.postSnapshot(ctx, printer, state)
            } finally {
                app.connections.release(printer.id, background = true)
            }
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "print-check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PrintCheckWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
