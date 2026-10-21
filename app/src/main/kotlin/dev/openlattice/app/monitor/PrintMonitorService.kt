package dev.openlattice.app.monitor

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.openlattice.app.R
import dev.openlattice.app.data.SavedPrinter
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
 * printer has had an active job for [IDLE_STOP_MS]; [PrintCheckWorker] covers the gaps.
 */
class PrintMonitorService : LifecycleService() {
    private val watched = mutableMapOf<String, Job>()
    private val latest = mutableMapOf<String, Pair<SavedPrinter, PrinterState>>()
    private var idleStopper: Job? = null
    private lateinit var lastSeen: LastSeen

    override fun onCreate() {
        super.onCreate()
        lastSeen = LastSeen(this)
        MonitorNotifications.createChannels(this)
        ServiceCompat.startForeground(
            this, MonitorNotifications.ONGOING_ID, ongoing(),
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
            lastSeen.remove(id)
            app.connections.release(id, background = true)
        }
        printers.filter { it.id !in watched }.forEach { p ->
            val driver = app.connections.acquire(p, background = true)
            MonitorNotifications.cancelSnapshot(this, p.id)
            watched[p.id] = lifecycleScope.launch {
                var previous: PrinterState? = lastSeen.get(p.id)
                driver.state.collect { s ->
                    if (s.connection == ConnectionState.Connected) {
                        previous?.let { before ->
                            alertFor(p.name.ifEmpty { p.model }, before, s)?.let { MonitorNotifications.postAlert(this@PrintMonitorService, p, it) }
                        }
                        if (previous?.machine != s.machine || previous?.exceptions != s.exceptions) lastSeen.put(p.id, s)
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
        MonitorNotifications.notify(this, MonitorNotifications.ONGOING_ID, ongoing())
    }

    private fun ongoing(): Notification {
        val active = latest.values.filter { (_, s) -> s.machine.isJobActive }
        val builder = NotificationCompat.Builder(this, MonitorNotifications.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_print)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(MonitorNotifications.openApp(this, null))
        if (active.isEmpty()) {
            return builder.setContentTitle("Watching printers").setContentText("No print running").build()
        }
        val (p, s) = active.first()
        val job = s.job
        val title = "${p.name.ifEmpty { p.model }} · ${s.machine.label()}"
        val text = job?.let { "${it.progress}% · ${formatDuration(it.remainingSec)} left · ${it.fileName}" } ?: s.machine.label()
        builder.setContentTitle(title).setContentText(text).setContentIntent(MonitorNotifications.openApp(this, p.id))
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

    override fun onDestroy() {
        watched.keys.toList().forEach { app.connections.release(it, background = true) }
        watched.values.forEach { it.cancel() }
        watched.clear()
        isRunning = false
        super.onDestroy()
    }

    companion object {
        private const val IDLE_STOP_MS = 60_000L

        @Volatile var isRunning = false
            private set

        /** Call from the foreground when an active job is seen. No-op if already running. */
        fun start(context: Context) {
            if (isRunning) return
            isRunning = true
            runCatching { ContextCompat.startForegroundService(context, Intent(context, PrintMonitorService::class.java)) }
                .onFailure { isRunning = false }
        }
    }
}
