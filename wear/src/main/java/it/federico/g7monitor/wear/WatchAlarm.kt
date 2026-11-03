package it.federico.g7monitor.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The alarm ringing on the phone, shown on the watch: a full-screen page that vibrates (as on the
 * phone), and "Posticipa" silences the alarm on the phone too. The phone's own alarm notification
 * is not bridged (it is ongoing and local only).
 */
object WatchAlarm {
    data class Alarm(val type: String, val title: String, val text: String, val snoozeMin: Int, val since: Long)

    private const val CHANNEL = "alarms"
    private const val NOTIFICATION_ID = 2
    const val EXTRA_TYPE = "type"

    private val _current = MutableStateFlow<Alarm?>(null)
    /** The alarm ringing now; the alarm page closes when it goes back to null. */
    val current: StateFlow<Alarm?> = _current

    /** Message from the phone: an alarm to show, or nothing (empty) when it stopped. */
    fun onMessage(context: Context, data: ByteArray) {
        if (data.isEmpty()) return clear(context)
        val map = DataMap.fromByteArray(data)
        val alarm = Alarm(
            type = map.getString(WatchContract.ALARM_TYPE) ?: return,
            title = map.getString(WatchContract.ALARM_TITLE).orEmpty(),
            text = map.getString(WatchContract.ALARM_TEXT).orEmpty(),
            snoozeMin = map.getInt(WatchContract.ALARM_SNOOZE_MIN),
            since = System.currentTimeMillis(),
        )
        _current.value = alarm
        notify(context, alarm)
    }

    private fun notify(context: Context, alarm: Alarm) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.w_channel_alarms), NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 700, 300, 700, 300, 700)
            }
        )
        // The full-screen page: opened by the system when the alarm arrives, or by tapping the notification.
        val page = PendingIntent.getActivity(
            context, 1, Intent(context, WatchAlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val snooze = PendingIntent.getBroadcast(
            context, 0, Intent(context, AlarmSnoozeReceiver::class.java).putExtra(EXTRA_TYPE, alarm.type),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_glucose)
            .setContentTitle(alarm.title)
            .setContentText(alarm.text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(page, true)
            .setContentIntent(page)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_glucose),
                    context.getString(R.string.w_snooze, alarm.snoozeMin), snooze,
                ).build()
            )
            .build()
        nm.notify(NOTIFICATION_ID, n)
    }

    fun clear(context: Context) {
        _current.value = null
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    /** "Posticipa" on the watch: tells the phone, which stops the alarm and then confirms with a clear. */
    fun snooze(context: Context, type: String, done: () -> Unit = {}) {
        clear(context)
        val app = context.applicationContext
        thread {
            try {
                val nodes = Tasks.await(Wearable.getNodeClient(app).connectedNodes, 10, TimeUnit.SECONDS)
                val messages = Wearable.getMessageClient(app)
                for (node in nodes) {
                    runCatching { Tasks.await(messages.sendMessage(node.id, WatchContract.ALARM_SNOOZE, type.toByteArray()), 10, TimeUnit.SECONDS) }
                }
            } catch (_: Exception) {
                // Phone out of reach: the alarm keeps ringing there, where it can still be snoozed.
            } finally {
                done()
            }
        }
    }
}

/** "Posticipa" from the notification's button. */
class AlarmSnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val type = intent.getStringExtra(WatchAlarm.EXTRA_TYPE) ?: return
        val pending = goAsync()
        WatchAlarm.snooze(context, type) { pending.finish() }
    }
}
