package it.federico.g7monitor.watch

import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import it.federico.g7monitor.G7App
import it.federico.g7monitor.alarm.AlarmType

/** "Posticipa" pressed on the watch: snoozes the alarm ringing on the phone, as the notification button does. */
class WatchListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WatchContract.ALARM_SNOOZE) return
        val type = AlarmType.entries.firstOrNull { it.name == String(event.data, Charsets.UTF_8) } ?: return
        // Same thread as the other snooze paths (notification, alarm page): the player is not thread-safe.
        ContextCompat.getMainExecutor(this).execute { G7App.instance.alarms.snooze(type) }
    }
}
