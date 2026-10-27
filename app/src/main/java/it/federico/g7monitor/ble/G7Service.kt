package it.federico.g7monitor.ble

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import it.federico.g7monitor.G7App
import it.federico.g7monitor.MainActivity
import it.federico.g7monitor.R
import it.federico.g7monitor.ui.Glucose
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service hosting the [G7Collector]. Type "connectedDevice" keeps the
 * Bluetooth session alive while the screen is off.
 */
class G7Service : LifecycleService() {

    private lateinit var collector: G7Collector
    private val app get() = G7App.instance

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification("Avvio…", null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        collector = G7Collector(this, lifecycleScope)
        collector.start()

        lifecycleScope.launch {
            combine(app.status, app.readings.changes, app.settings.settings) { st, _, s -> st to s }
                .collect { (st, s) ->
                    val latest = app.readings.latest()
                    val title = latest?.let {
                        "${Glucose.format(it.mgdl, s.useMmol)} ${Glucose.unit(s.useMmol)} ${Glucose.arrow(it.trend)}"
                    } ?: "Nessuna lettura recente"
                    val text = st.message ?: st.sensorState?.label?.let { "Sensore: $it" } ?: st.link.label
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification(title, text, latest?.timestamp))
                    if (latest != null) scheduleWatchdog()
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                collector.stop()
                stopSelf()
            }
            ACTION_WATCHDOG -> collector.kick()
            ACTION_RESTART -> {
                collector.stop()
                collector = G7Collector(this, lifecycleScope)
                collector.start()
            }
            else -> collector.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        collector.stop()
        cancelWatchdog()
        super.onDestroy()
    }

    private fun buildNotification(title: String, text: String?, `when`: Long? = null): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, G7App.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { if (`when` != null) setWhen(`when`).setShowWhen(true) }
            .build()
    }

    private fun watchdogIntent() = PendingIntent.getService(
        this, 1, Intent(this, G7Service::class.java).setAction(ACTION_WATCHDOG),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Inexact, doze-friendly fallback in case the sensor is not seen for a while. */
    private fun scheduleWatchdog() {
        getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 12 * 60_000L,
            watchdogIntent(),
        )
    }

    private fun cancelWatchdog() = getSystemService(AlarmManager::class.java).cancel(watchdogIntent())

    companion object {
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "stop"
        private const val ACTION_WATCHDOG = "watchdog"
        private const val ACTION_RESTART = "restart"

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, G7Service::class.java))

        /** Re-reads settings from scratch (new pairing code, forgotten sensor). */
        fun restart(context: Context) = ContextCompat.startForegroundService(
            context, Intent(context, G7Service::class.java).setAction(ACTION_RESTART),
        )

        fun stop(context: Context) =
            context.startService(Intent(context, G7Service::class.java).setAction(ACTION_STOP))
    }
}

/** Restarts the collector after a reboot or app update, if it was enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as G7App
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                if (app.settings.current().collectorEnabled) G7Service.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}
