package it.federico.g7monitor.ble

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import it.federico.g7monitor.G7App
import it.federico.g7monitor.MainActivity
import it.federico.g7monitor.R
import it.federico.g7monitor.data.DataSource
import it.federico.g7monitor.data.DebugLog
import it.federico.g7monitor.data.Link
import it.federico.g7monitor.data.SensorStatus
import it.federico.g7monitor.ui.Glucose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service that keeps the readings coming with the screen off:
 * - sensor mode: hosts the [G7Collector] (type "connectedDevice", Bluetooth) and the uploader
 * - server mode: downloads from the server with the [it.federico.g7monitor.net.Follower] (type "specialUse")
 */
class G7Service : LifecycleService() {

    private var collector: G7Collector? = null
    private var modeScope: CoroutineScope? = null
    private var mode = DataSource.Sensor
    /** Android refused to start this service in the foreground: don't retry until it is started again. */
    private var refused = false
    /** Wakes the follower loop early (alarm while dozing, "Aggiorna ora"). */
    private val followKicks = Channel<Unit>(Channel.CONFLATED)
    private val app get() = G7App.instance

    override fun onCreate() {
        super.onCreate()
        _running.value = true
        startMode(readMode())
        KeepAlive.schedule(this)

        lifecycleScope.launch {
            val mmol = app.settings.settings.map { it.useMmol }.distinctUntilChanged()
            var watchdogFor: Long? = null
            combine(app.status, app.readings.changes, mmol) { st, _, useMmol ->
                val latest = app.readings.latest()
                val title = latest?.let {
                    "${Glucose.format(it.mgdl, useMmol)} ${Glucose.unit(useMmol)} ${Glucose.arrow(it.trend)}"
                } ?: "Nessuna lettura recente"
                val text = st.message ?: st.sensorState?.label?.let { "Sensore: $it" } ?: st.link.label
                Triple(title, text, latest?.timestamp)
            }
                // Status changes often during a cycle: only touch the notification when its text changes.
                .distinctUntilChanged()
                .collect { (title, text, readingTime) ->
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification(title, text, readingTime))
                    if (mode == DataSource.Sensor && readingTime != null && readingTime != watchdogFor) {
                        watchdogFor = readingTime
                        scheduleWatchdog()
                    }
                }
        }
    }

    /** DataStore keeps the settings in memory after the first read: this is a quick local read. */
    private fun readMode(): DataSource = runBlocking { app.settings.current().dataSource } ?: DataSource.Sensor

    private fun startMode(newMode: DataSource) {
        stopMode()
        mode = newMode
        val type = when {
            mode == DataSource.Sensor -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else -> 0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification("Avvio…", null), type)
        } catch (e: RuntimeException) {
            // e.g. Bluetooth permission missing or revoked (Android 14+ requires it for this service type),
            // or a start from the background that Android refuses: report it instead of crashing.
            DebugLog.log("servizio non avviato: ${e.javaClass.simpleName}: ${e.message}")
            app.status.value = SensorStatus(
                link = Link.Error,
                message = if (mode == DataSource.Sensor) "Servono i permessi Bluetooth: riattiva la raccolta dati" else "Avvio non consentito da Android",
            )
            refused = true
            _running.value = false
            stopSelf()
            return
        }
        val scope = CoroutineScope(lifecycleScope.coroutineContext + Job(lifecycleScope.coroutineContext[Job]))
        modeScope = scope
        when (mode) {
            DataSource.Sensor -> {
                collector = G7Collector(this, scope).also { it.start() }
                // Upload right after new readings are stored, while the phone is awake anyway (also catches up at start).
                scope.launch { app.readings.changes.collect { app.uploader.maybeUpload() } }
            }
            DataSource.Server -> scope.launch { follow() }
        }
    }

    private fun stopMode() {
        collector?.stop()
        collector = null
        modeScope?.cancel()
        modeScope = null
        cancelAlarm(watchdogIntent())
        cancelAlarm(followIntent())
    }

    /**
     * Live connection to the server (the follower reconnects by itself). Every 10 minutes an alarm,
     * which also fires in Doze, checks that the link is really alive.
     */
    private suspend fun follow() = coroutineScope {
        launch { app.follower.follow() }
        while (true) {
            setAlarm(followIntent(), LINK_CHECK_MS)
            withTimeoutOrNull(LINK_CHECK_MS) { followKicks.receive() }
            app.follower.checkAlive()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_WATCHDOG -> collector?.kick()
            ACTION_FOLLOW -> followKicks.trySend(Unit)
            // New pairing code, forgotten sensor, or a different data source.
            ACTION_RESTART -> startMode(readMode())
            else -> if (modeScope == null && !refused) startMode(readMode())
        }
        return START_STICKY
    }

    /** Swiped away from recents: some phones kill the process with it, so check again shortly. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        KeepAlive.schedule(this, 5_000L)
    }

    override fun onDestroy() {
        _running.value = false
        stopMode()
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

    private fun serviceIntent(requestCode: Int, action: String) = PendingIntent.getService(
        this, requestCode, Intent(this, G7Service::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun watchdogIntent() = serviceIntent(1, ACTION_WATCHDOG)
    private fun followIntent() = serviceIntent(2, ACTION_FOLLOW)

    /** Inexact, doze-friendly fallback in case the sensor is not seen for a while. */
    private fun scheduleWatchdog() = setAlarm(watchdogIntent(), 12 * 60_000L)

    private fun setAlarm(intent: PendingIntent, delayMs: Long) {
        getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, intent,
        )
    }

    private fun cancelAlarm(intent: PendingIntent) = getSystemService(AlarmManager::class.java).cancel(intent)

    companion object {
        private val _running = MutableStateFlow(false)

        /** True while the service exists in this process (shown in Settings, used by [KeepAlive]). */
        val running: StateFlow<Boolean> = _running

        private const val NOTIFICATION_ID = 7
        private const val LINK_CHECK_MS = 10 * 60_000L
        private const val ACTION_WATCHDOG = "watchdog"
        private const val ACTION_FOLLOW = "follow"
        private const val ACTION_RESTART = "restart"

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, G7Service::class.java))

        /** Re-reads settings from scratch (new pairing code, forgotten sensor, other data source). */
        fun restart(context: Context) = ContextCompat.startForegroundService(
            context, Intent(context, G7Service::class.java).setAction(ACTION_RESTART),
        )

        /** "Aggiorna ora" in follower mode. */

        /** stopService never creates the service (a "stop" command did, and the new instance then crashed). */
        fun stop(context: Context) {
            KeepAlive.cancel(context)
            context.stopService(Intent(context, G7Service::class.java))
        }
    }
}

/** Restarts the collector after a reboot or app update, if it was enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as G7App
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                if (app.settings.current().collectorEnabled) {
                    runCatching { G7Service.start(context) }
                    KeepAlive.schedule(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
