package dev.openlattice.app.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.openlattice.app.R
import dev.openlattice.app.data.SavedPrinter
import dev.openlattice.app.ui.MainActivity
import dev.openlattice.app.ui.app
import dev.openlattice.app.ui.formatDuration
import dev.openlattice.app.ui.label
import dev.openlattice.core.ConnectionState
import dev.openlattice.core.PrinterState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps printer connections open while a print is running so progress and alerts arrive
 * without the app on screen (there is no cloud push in LAN mode). Stops itself once no
 * printer has had an active job for [IDLE_STOP_MS].
 */
class PrintMonitorService : LifecycleService() {
    private val watched = mutableMapOf<String, Job>()
    private val latest = mutableMapOf<String, Pair<SavedPrinter, PrinterState>>()
    private var idleStopper: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        ServiceCompat.startForeground(
            this, ONGOING_ID, ongoing(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
        lifecycleScope.launch {
            app.store.printers.collect { printers -> sync(printers) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    private fun sync(printers: List<SavedPrinter>) {
        val ids = printers.map { it.id }.toSet()
        (watched.keys - ids).forEach { id ->
            watched.remove(id)?.cancel()
            latest.remove(id)
            app.connections.release(id)
        }
        printers.filter { it.id !in watched }.forEach { p ->
            val driver = app.connections.acquire(p)
            watched[p.id] = lifecycleScope.launch {
                var previous: PrinterState? = null
                driver.state.collect { s ->
                    if (s.connection == ConnectionState.Connected) {
                        previous?.let { alertOnTransition(p, it, s) }
                        previous = s
                    }
                    latest[p.id] = p to s
                    refresh()
                }
            }
        }
        refresh()
    }

    private fun refresh() {
        val active = latest.values.filter { (_, s) -> s.machine.isJobActive }
        if (active.isEmpty()) {
            if (idleStopper?.isActive != true) idleStopper = lifecycleScope.launch {
                delay(IDLE_STOP_MS)
                stopSelf()
            }
        } else {
            idleStopper?.cancel()
            idleStopper = null
        }
        notify(ONGOING_ID, ongoing())
    }

    private fun ongoing(): Notification {
        val active = latest.values.filter { (_, s) -> s.machine.isJobActive }
        val builder = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_print)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp(null))
        if (active.isEmpty()) {
            return builder.setContentTitle("Watching printers").setContentText("No print running").build()
        }
        val (p, s) = active.first()
        val job = s.job
        val title = "${p.name.ifEmpty { p.model }} · ${s.machine.label()}"
        val text = job?.let { "${it.progress}% · ${formatDuration(it.remainingSec)} left · ${it.fileName}" } ?: s.machine.label()
        builder.setContentTitle(title).setContentText(text).setContentIntent(openApp(p.id))
        job?.let { builder.setProgress(100, it.progress, false) }
        if (active.size > 1) {
            builder.setStyle(NotificationCompat.InboxStyle().also { style ->
                active.forEach { (pp, ss) ->
                    style.addLine("${pp.name.ifEmpty { pp.model }}: ${ss.job?.progress ?: 0}% · ${ss.machine.label()}")
                }
            })
        }
        return builder.build()
    }

    private fun alertOnTransition(p: SavedPrinter, before: PrinterState, now: PrinterState) {
        val (title, text) = alertFor(p.name.ifEmpty { p.model }, before, now) ?: return
        notify(
            alertId(p.id),
            NotificationCompat.Builder(this, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_print)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(openApp(p.id))
                .build(),
        )
    }

    private fun notify(id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(this).notify(id, n)
    }

    private fun openApp(printerId: String?): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_PRINTER_ID, printerId)
        return PendingIntent.getActivity(this, printerId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    override fun onDestroy() {
        watched.keys.toList().forEach { app.connections.release(it) }
        watched.values.forEach { it.cancel() }
        watched.clear()
        running = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_PROGRESS = "print_progress"
        private const val CHANNEL_ALERTS = "print_alerts"
        private const val ONGOING_ID = 1
        private const val IDLE_STOP_MS = 60_000L

        @Volatile private var running = false

        private fun alertId(printerId: String) = 1000 + (printerId.hashCode() and 0xFFFF)

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, "Print progress", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERTS, "Print alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Finished, stopped, errors and filament runout"
                },
            )
        }

        /** Call from the foreground when an active job is seen. No-op if already running. */
        fun start(context: Context) {
            if (running) return
            running = true
            runCatching { ContextCompat.startForegroundService(context, Intent(context, PrintMonitorService::class.java)) }
                .onFailure { running = false }
        }
    }
}
