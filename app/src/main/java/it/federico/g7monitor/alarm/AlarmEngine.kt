package it.federico.g7monitor.alarm

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import it.federico.g7monitor.G7App
import it.federico.g7monitor.R
import it.federico.g7monitor.data.DebugLog
import it.federico.g7monitor.data.GlucoseReading
import it.federico.g7monitor.protocol.SensorState
import it.federico.g7monitor.ui.Glucose
import it.federico.g7monitor.ui.alarm.AlarmActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** The alarm on screen / ringing now. */
/** @param test started from the settings ("Prova"): never snoozes nor hides a real alarm. */
data class ActiveAlarm(val type: AlarmType, val text: String, val since: Long, val snoozeMin: Int, val test: Boolean = false)

/**
 * Checks the alarms after every new reading (sensor or server) and at the moment a
 * missing reading becomes "signal loss". One alarm rings at a time, the most important.
 *
 * Ringing: sound/vibration for up to 2 minutes, full-screen page on the lock screen and a
 * notification. If nobody reacts it rings again at the next reading (about every 5 minutes)
 * while the condition is still true. "Posticipa" silences that alarm for its snooze time.
 */
class AlarmEngine(private val app: G7App) {
    val player = AlarmPlayer(app)
    private val mutex = Mutex()
    private val snoozedUntil = ConcurrentHashMap<AlarmType, Long>()
    private val lastAlertAt = ConcurrentHashMap<AlarmType, Long>()
    private val _active = MutableStateFlow<ActiveAlarm?>(null)
    val active: StateFlow<ActiveAlarm?> = _active

    suspend fun evaluate() = mutex.withLock {
        val configs = app.alarmSettings.current()
        val s = app.settings.current()
        val now = System.currentTimeMillis()

        // All alarms paused: nothing rings (a test still does), and the phone wakes when the pause ends.
        val pausedUntil = app.alarmSettings.pausedUntil()
        if (pausedUntil != null && pausedUntil > now) {
            _active.value?.let { if (!it.test) clear() }
            val am = app.getSystemService(AlarmManager::class.java)
            am.cancel(checkIntent(SIGNAL_CHECK))
            if (pausedUntil != AlarmRepository.PAUSED_UNTIL_RESUMED) setExact(am, pausedUntil - now + 1_000L, checkIntent(RESUME_CHECK))
            return@withLock
        }
        if (pausedUntil != null) {
            app.alarmSettings.setPausedUntil(null)
            DebugLog.log("allarmi riattivati: sospensione scaduta")
        }

        val recent = app.readings.since(now - 30 * 60_000L)
        val newest = app.readings.newestTimestamp()
        val triggered = AlarmRules.triggered(
            configs, recent, newest, now,
            receiving = s.collectorEnabled,
            warmingUp = app.status.value.sensorState == SensorState.WarmingUp,
        )

        // The ringing alarm is no longer true (or was disabled): stop it.
        _active.value?.let { if (!it.test && it.type !in triggered) clear() }

        val candidate = triggered.filter { (snoozedUntil[it] ?: 0L) <= now }.minByOrNull { it.priority }
        if (candidate != null) {
            val current = _active.value?.takeUnless { it.test }?.type // a real alarm replaces a test
            val outranks = current == null || candidate.priority < current.priority
            val again = candidate == current && now - (lastAlertAt[candidate] ?: 0L) >= REALERT_MS
            if (outranks || again) fire(configs.getValue(candidate), recent.lastOrNull(), newest, now, s.useMmol)
        }
        scheduleSignalCheck(configs.getValue(AlarmType.SignalLoss), newest, s.collectorEnabled, now)
    }

    /** "Posticipa": silence this alarm for its snooze time. */
    fun snooze(type: AlarmType) {
        val a = _active.value
        if (a?.test == true && a.type == type) {
            clear() // a test only needs to stop
            return
        }
        val minutes = _active.value?.takeIf { it.type == type }?.snoozeMin ?: type.snoozeMin
        snoozedUntil[type] = System.currentTimeMillis() + minutes * 60_000L
        DebugLog.log("allarme posticipato: ${type.title} per $minutes min")
        if (_active.value?.type == type) clear()
    }

    /**
     * Pauses every alarm until [until] ([AlarmRepository.PAUSED_UNTIL_RESUMED]: until resumed), or
     * resumes them (null). Each alarm's own switch is left as it is.
     */
    suspend fun pause(until: Long?) {
        app.alarmSettings.setPausedUntil(until)
        if (until == null) app.getSystemService(AlarmManager::class.java).cancel(checkIntent(RESUME_CHECK))
        DebugLog.log(if (until == null) "allarmi riattivati" else "allarmi sospesi")
        evaluate()
    }

    /** Settings preview ("Prova"): a few seconds of sound and vibration. */
    fun preview(config: AlarmConfig) = player.start(config, PREVIEW_MS)

    /**
     * Full test of an alarm: notification, full-screen page, sound and vibration exactly as a real
     * one, marked "PROVA". It rings for [TEST_RING_MS] and stays until "Posticipa" (or swipe).
     */
    fun test(config: AlarmConfig) {
        if (_active.value?.test == false) return // a real alarm is ringing: never cover it
        val text = "PROVA · nessun valore reale"
        _active.value = ActiveAlarm(config.type, text, System.currentTimeMillis(), config.snoozeMin, test = true)
        DebugLog.log("prova allarme: ${config.type.title}")
        player.start(config, TEST_RING_MS)
        notifyAlarm(config, text)
    }

    /** Test in [delayMs], to lock the phone meanwhile and see the alarm on the lock screen. */
    fun scheduleTest(type: AlarmType, delayMs: Long) {
        val pi = PendingIntent.getBroadcast(
            app, 13, Intent(app, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_TEST)
                .putExtra(AlarmReceiver.EXTRA_TYPE, type.name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val am = app.getSystemService(AlarmManager::class.java)
        val at = SystemClock.elapsedRealtime() + delayMs
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
    }

    fun stopPreview() {
        if (_active.value == null) player.stop()
    }

    private fun fire(config: AlarmConfig, latest: GlucoseReading?, newest: Long?, now: Long, mmol: Boolean) {
        val text = when (config.type) {
            AlarmType.SignalLoss -> "Nessuna lettura da ${newest?.let { (now - it) / 60_000L } ?: "?"} minuti"
            else -> latest?.let {
                "${Glucose.format(it.mgdl, mmol)} ${Glucose.unit(mmol)} ${Glucose.arrow(it.trend)}".trim()
            } ?: ""
        }
        lastAlertAt[config.type] = now
        _active.value = ActiveAlarm(config.type, text, now, config.snoozeMin)
        DebugLog.log("ALLARME: ${config.type.title} — $text")
        player.start(config, RING_MS)
        notifyAlarm(config, text)
    }

    private fun clear() {
        _active.value?.let { app.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
        _active.value = null
        player.stop()
    }

    private fun notifyAlarm(config: AlarmConfig, text: String) {
        val open = PendingIntent.getActivity(
            app, 10, Intent(app, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val snooze = PendingIntent.getBroadcast(
            app, 11, Intent(app, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_SNOOZE)
                .putExtra(AlarmReceiver.EXTRA_TYPE, config.type.name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(app, G7App.CHANNEL_ALARMS)
            .setSmallIcon(R.drawable.ic_notification)
            // Short title and text: Samsung's compact pop-up puts them side by side on one line.
            .setContentTitle(config.type.title)
            .setContentText(text)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(config.type.title)
                    .bigText(
                        "$text\n\nPosticipa per silenziarlo ${config.snoozeMin} minuti. " +
                            "Se non lo posticipi risuona a ogni nuova lettura finché la condizione resta vera.",
                    )
            )
            // Own group: never bundled with the status notification (a closed bundle hides the buttons).
            .setGroup(GROUP_ALARM)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(0xFFD93A3F.toInt())
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            // Android 14+ lets the user swipe even "ongoing" notifications away: that silences it like "Posticipa".
            .setDeleteIntent(snooze)
            .addAction(R.drawable.ic_notification, "Posticipa ${config.snoozeMin} min", snooze)
            .build()
        app.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
    }

    /** Wakes the phone when the next reading would be overdue, so signal loss rings on time even in Doze. */
    private fun scheduleSignalCheck(config: AlarmConfig, newest: Long?, receiving: Boolean, now: Long) {
        val am = app.getSystemService(AlarmManager::class.java)
        val pi = checkIntent(SIGNAL_CHECK)
        if (!config.enabled || !receiving) {
            am.cancel(pi)
            return
        }
        val due = (newest ?: now) + config.value * 60_000L
        setExact(am, if (due > now) due - now + 5_000L else REALERT_MS, pi)
    }

    /** "Check the alarms now" at a set time: [SIGNAL_CHECK] or [RESUME_CHECK] (different request codes, independent). */
    private fun checkIntent(requestCode: Int) = PendingIntent.getBroadcast(
        app, requestCode, Intent(app, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_CHECK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun setExact(am: AlarmManager, inMs: Long, pi: PendingIntent) {
        val at = SystemClock.elapsedRealtime() + inMs
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
    }

    companion object {
        const val NOTIFICATION_ID = 20
        private const val GROUP_ALARM = "it.federico.g7monitor.ALARM"
        private const val SIGNAL_CHECK = 12
        private const val RESUME_CHECK = 14
        /** An unanswered alarm rings again after this long, if still true. */
        private const val REALERT_MS = 5 * 60_000L - 30_000L
        private const val RING_MS = 2 * 60_000L
        private const val PREVIEW_MS = 4_000L
        private const val TEST_RING_MS = 30_000L
    }
}
