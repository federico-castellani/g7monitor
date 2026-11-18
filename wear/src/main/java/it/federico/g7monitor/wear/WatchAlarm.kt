package it.federico.g7monitor.wear

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The alarm ringing on the phone, shown on the watch as on the phone: a full-screen page with the
 * value and "Posticipa", vibrating like an alarm clock, plus a notification. "Posticipa" silences
 * the alarm on the phone too. The phone's own alarm notification is not bridged (it is ongoing and
 * local only).
 *
 * The page is the notification's full-screen intent, but on Wear OS 6 that intent is started by
 * the watch interface (not a system process), and Android lets it start a page only while that
 * interface is on screen: with the screen off it is refused, and Samsung's interface then drops
 * its pop-up too. So with the screen off the app first turns the screen on, as an alarm clock does,
 * and then posts the alarm with its full-screen page. If the screen does not come on, the alarm is
 * posted without the page (its pop-up turns the screen on) and again with it once the screen is on.
 */
object WatchAlarm {
    data class Alarm(val type: String, val title: String, val text: String, val snoozeMin: Int, val since: Long)

    private const val CHANNEL = "alarms"
    private const val NOTIFICATION_ID = 2

    /**
     * An alarm the phone does not repeat goes away by itself after this: the phone rings again
     * about every 5 minutes while the condition lasts, and a lost "stopped" message (watch out of
     * reach at that moment) must not leave an old alarm on the watch.
     */
    const val EXPIRE_MS = 12 * 60_000L
    const val EXTRA_TYPE = "type"

    /** About a minute of pulses, as an alarm: it goes on even if the pop-up is not noticed. */
    private val PULSES = LongArray(1 + 2 * 43) { i -> if (i == 0) 0L else if (i % 2 == 1) 800L else 600L }

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
        // The page already on screen vibrates again by itself: an update, no pop-up on top of it.
        if (WatchAlarmActivity.visible) return notify(context, alarm, fullScreen = false, update = true)
        vibrate(context)
        val power = context.getSystemService(PowerManager::class.java)
        if (power.isInteractive) return notify(context, alarm, fullScreen = true)
        // Screen off: turn it on, then the full-screen page straight away, with no pop-up first.
        @Suppress("DEPRECATION") // the way to turn the screen on from a service; alarm clocks still use it
        power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "g7monitor:alarm",
        ).acquire(5_000)
        val handler = Handler(Looper.getMainLooper())
        val started = SystemClock.uptimeMillis()
        var popUpPosted = false
        handler.post(object : Runnable {
            override fun run() {
                if (_current.value !== alarm || WatchAlarmActivity.visible) return
                if (power.isInteractive) return notify(context, alarm, fullScreen = true)
                val waited = SystemClock.uptimeMillis() - started
                // Still off: the pop-up turns it on, and the page follows as soon as it is on.
                if (waited >= WAKE_MS && !popUpPosted) {
                    popUpPosted = true
                    notify(context, alarm, fullScreen = false)
                }
                if (waited < WAIT_SCREEN_MS) handler.postDelayed(this, 100)
            }
        })
    }

    /** How long the screen may take to come on by itself before falling back to the pop-up. */
    private const val WAKE_MS = 1_500L

    /** How long to wait, in all, for the screen to come on. */
    private const val WAIT_SCREEN_MS = 8_000L

    /**
     * @param fullScreen with the full-screen page (only works with the screen on, see the class comment)
     * @param update change the notification in place: no pop-up and no alert
     */
    private fun notify(context: Context, alarm: Alarm, fullScreen: Boolean, update: Boolean = false) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.w_channel_alarms), NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 700, 300, 700, 300, 700)
            }
        )
        val page = pagePendingIntent(context)
        val snooze = PendingIntent.getBroadcast(
            context, 0, Intent(context, AlarmSnoozeReceiver::class.java).putExtra(EXTRA_TYPE, alarm.type),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_glucose)
            .setContentTitle(alarm.title)
            .setContentText(alarm.text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setTimeoutAfter(EXPIRE_MS)
            // Only for the update below (page on screen): a new post always alerts.
            .setOnlyAlertOnce(true)
            .setContentIntent(page)
            .apply { if (fullScreen) setFullScreenIntent(page, true) }
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_glucose),
                    context.getString(R.string.w_snooze, alarm.snoozeMin), snooze,
                ).build()
            )
            .build()
        if (update) return nm.notify(NOTIFICATION_ID, n)
        // Otherwise a new post every time: only a new notification gets a pop-up and its full-screen
        // page (the same alarm again at the next reading, the second post once the screen is on, or
        // one left there because the "stopped" message did not arrive).
        nm.cancel(NOTIFICATION_ID)
        nm.notify(NOTIFICATION_ID, n)
    }

    /**
     * The alarm page. Made anew each time (CANCEL_CURRENT): an existing PendingIntent keeps the
     * options it was created with. On Android 15+ the options let the watch interface start it on
     * our behalf.
     */
    private fun pagePendingIntent(context: Context): PendingIntent {
        val options = if (Build.VERSION.SDK_INT >= 34) {
            val mode = if (Build.VERSION.SDK_INT >= 36) ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
            else @Suppress("DEPRECATION") ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            ActivityOptions.makeBasic().setPendingIntentCreatorBackgroundActivityStartMode(mode).toBundle()
        } else null
        return PendingIntent.getActivity(
            context, 1, Intent(context, WatchAlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT, options,
        )
    }

    fun clear(context: Context) {
        _current.value = null
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        stopVibration(context)
    }

    /**
     * Like an alarm clock (allowed from the background, and in Do not disturb when alarms are), for
     * about a minute. Always through the application's vibrator: Android stops a vibration only for
     * the same Vibrator that started it, so the page and "Posticipa" must use this one too.
     */
    fun vibrate(context: Context) {
        val effect = VibrationEffect.createWaveform(PULSES, -1)
        val v = vibrator(context)
        if (Build.VERSION.SDK_INT >= 33) v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        else @Suppress("DEPRECATION") v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
    }

    fun stopVibration(context: Context) = vibrator(context).cancel()

    private fun vibrator(context: Context): Vibrator {
        val app = context.applicationContext
        return if (Build.VERSION.SDK_INT >= 31) app.getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") app.getSystemService(Vibrator::class.java)
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
