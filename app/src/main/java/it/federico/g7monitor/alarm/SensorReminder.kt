package it.federico.g7monitor.alarm

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import it.federico.g7monitor.G7App
import it.federico.g7monitor.MainActivity
import it.federico.g7monitor.R
import it.federico.g7monitor.data.DataSource
import it.federico.g7monitor.data.DebugLog
import it.federico.g7monitor.str
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * When to remind about changing the sensor. Dexcom says a G7 lasts 10 days and 12 hours, but the
 * 12 hours are a grace period for the change: the reminders count 10 days only.
 */
object SensorReminderRules {
    const val LIFE_MS = 10 * 24 * 3_600_000L
    const val GRACE_MS = 12 * 3_600_000L

    /** How long before the end of the 10 days each reminder comes: a day, two hours, the end itself. */
    val STAGES = longArrayOf(24 * 3_600_000L, 2 * 3_600_000L, 0L)

    /**
     * Index in [STAGES] of the latest reminder due at [now] for a sensor started at [start]; -1 when
     * none is due yet, or the sensor has already stopped (past the grace period).
     */
    fun stage(start: Long, now: Long): Int {
        val end = start + LIFE_MS
        if (now < start || now >= end + GRACE_MS) return -1
        return STAGES.indexOfLast { now >= end - it }
    }
}

/**
 * Sends the sensor change reminders, checked at every new reading (sensor or server, so a follower
 * gets them too). Each reminder is sent once per sensor; only the latest due one, so a phone that
 * was off for a while does not get all of them at once.
 */
class SensorReminder(private val app: G7App) {
    private val prefs = app.getSharedPreferences("sensor_reminder", Context.MODE_PRIVATE)

    suspend fun check() {
        val s = app.settings.current()
        // The sensor phone knows the start from the sensor itself; a follower from the server.
        val start = (if (s.dataSource == DataSource.Sensor) s.sessionStart else null) ?: app.readings.latestSensorStart() ?: return
        val stored = prefs.getLong(KEY_START, 0L)
        // The start is worked out again at every reading, a few seconds apart: same sensor.
        val sameSensor = stored != 0L && abs(stored - start) < SAME_SENSOR_MS
        if (stored != 0L && !sameSensor) {
            // A new sensor: the reminders of the old one have done their job.
            NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID)
            prefs.edit().clear().apply()
        }
        if (!s.sensorReminder) return
        val stage = SensorReminderRules.stage(start, System.currentTimeMillis())
        if (stage < 0 || stage <= (if (sameSensor) prefs.getInt(KEY_STAGE, -1) else -1)) return
        prefs.edit().putLong(KEY_START, start).putInt(KEY_STAGE, stage).apply()
        notify(stage, start + SensorReminderRules.LIFE_MS)
    }

    private fun notify(stage: Int, end: Long) {
        val locale = Locale.getDefault()
        val fmt = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEHHmm"), locale)
        val (title, text) = when (stage) {
            0 -> str(R.string.reminder_sensor_day) to str(R.string.reminder_sensor_day_text, fmt.format(Date(end)))
            1 -> str(R.string.reminder_sensor_hours) to str(R.string.reminder_sensor_hours_text, fmt.format(Date(end)))
            else -> str(R.string.reminder_sensor_over) to
                str(R.string.reminder_sensor_over_text, fmt.format(Date(end + SensorReminderRules.GRACE_MS)))
        }
        DebugLog.log(title)
        val nm = app.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(app, G7App.CHANNEL_REMINDERS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .build(),
        )
    }

    companion object {
        private const val NOTIFICATION_ID = 21
        private const val KEY_START = "start"
        private const val KEY_STAGE = "stage"
        /** Same rule as the stored sensor starts. */
        private const val SAME_SENSOR_MS = 12 * 3_600_000L
    }
}
