package dev.openlattice.app.monitor

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.openlattice.app.R
import dev.openlattice.app.data.SavedPrinter
import dev.openlattice.app.ui.MainActivity
import dev.openlattice.app.ui.formatDuration
import dev.openlattice.app.ui.label
import dev.openlattice.core.PrinterState

/** Notification channels and builders shared by the monitor service and the periodic check. */
object MonitorNotifications {
    const val CHANNEL_PROGRESS = "print_progress"
    const val CHANNEL_ALERTS = "print_alerts"
    const val ONGOING_ID = 1

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, "Print progress", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Print alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Finished, stopped, errors and filament runout"
            },
        )
    }

    fun notify(context: Context, id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(context).notify(id, n)
    }

    fun openApp(context: Context, printerId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_PRINTER_ID, printerId)
        return PendingIntent.getActivity(context, printerId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun alertId(printerId: String) = 1000 + (printerId.hashCode() and 0xFFFF)
    private fun progressId(printerId: String) = 100_000 + (printerId.hashCode() and 0xFFFF)

    fun postAlert(context: Context, printer: SavedPrinter, alert: PrintAlert) {
        notify(
            context, alertId(printer.id),
            NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_print)
                .setContentTitle(alert.title)
                .setContentText(alert.text)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(openApp(context, printer.id))
                .build(),
        )
    }

    /** Non-ongoing progress snapshot from the periodic check; replaced on each check, cleared when idle. */
    fun postSnapshot(context: Context, printer: SavedPrinter, state: PrinterState) {
        val id = progressId(printer.id)
        val job = state.job
        if (!state.machine.isJobActive || job == null) {
            NotificationManagerCompat.from(context).cancel(id)
            return
        }
        notify(
            context, id,
            NotificationCompat.Builder(context, CHANNEL_PROGRESS)
                .setSmallIcon(R.drawable.ic_stat_print)
                .setContentTitle("${printer.name.ifEmpty { printer.model }} · ${state.machine.label()}")
                .setContentText("${job.progress}% · ${formatDuration(job.remainingSec)} left · tap for live view")
                .setProgress(100, job.progress, false)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(openApp(context, printer.id))
                .build(),
        )
    }

    fun cancelSnapshot(context: Context, printerId: String) =
        NotificationManagerCompat.from(context).cancel(progressId(printerId))
}
