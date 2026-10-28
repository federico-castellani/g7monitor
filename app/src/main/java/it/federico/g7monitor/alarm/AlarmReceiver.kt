package it.federico.g7monitor.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import it.federico.g7monitor.G7App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** "Posticipa" from the notification, and the timed signal-loss check. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as G7App
        when (intent.action) {
            ACTION_SNOOZE -> AlarmType.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_TYPE) }
                ?.let(app.alarms::snooze)
            ACTION_CHECK -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        app.alarms.evaluate()
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_SNOOZE = "it.federico.g7monitor.alarm.SNOOZE"
        const val ACTION_CHECK = "it.federico.g7monitor.alarm.CHECK"
        const val EXTRA_TYPE = "type"
    }
}
