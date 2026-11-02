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
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The alarm ringing on the phone, shown on the watch: it vibrates, and "Posticipa" silences the
 * alarm on the phone too. The phone's own alarm notification is not bridged (it is ongoing).
 */
object WatchAlarm {
    private const val CHANNEL = "alarms"
    private const val NOTIFICATION_ID = 2
    const val EXTRA_TYPE = "type"

    /** Message from the phone: an alarm to show, or nothing (empty) when it stopped. */
    fun onMessage(context: Context, data: ByteArray) {
        if (data.isEmpty()) return clear(context)
        val alarm = DataMap.fromByteArray(data)
        val type = alarm.getString(WatchContract.ALARM_TYPE) ?: return
        val snoozeMin = alarm.getInt(WatchContract.ALARM_SNOOZE_MIN)
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.w_channel_alarms), NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 700, 300, 700, 300, 700)
            }
        )
        val snooze = PendingIntent.getBroadcast(
            context, 0, Intent(context, AlarmSnoozeReceiver::class.java).putExtra(EXTRA_TYPE, type),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_glucose)
            .setContentTitle(alarm.getString(WatchContract.ALARM_TITLE))
            .setContentText(alarm.getString(WatchContract.ALARM_TEXT))
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_glucose),
                    context.getString(R.string.w_snooze, snoozeMin), snooze,
                ).build()
            )
            .build()
        nm.notify(NOTIFICATION_ID, n)
    }

    fun clear(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
}

/** "Posticipa" on the watch: tells the phone, which stops the alarm and then clears this notification too. */
class AlarmSnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val type = intent.getStringExtra(WatchAlarm.EXTRA_TYPE) ?: return
        WatchAlarm.clear(context)
        val pending = goAsync()
        thread {
            try {
                val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes, 10, TimeUnit.SECONDS)
                val messages = Wearable.getMessageClient(context)
                for (node in nodes) {
                    runCatching { Tasks.await(messages.sendMessage(node.id, WatchContract.ALARM_SNOOZE, type.toByteArray()), 10, TimeUnit.SECONDS) }
                }
            } catch (_: Exception) {
                // Phone out of reach: the alarm keeps ringing there, where it can still be snoozed.
            } finally {
                pending.finish()
            }
        }
    }
}
