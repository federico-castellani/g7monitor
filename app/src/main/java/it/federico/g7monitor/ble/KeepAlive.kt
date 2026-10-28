package it.federico.g7monitor.ble

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import it.federico.g7monitor.G7App
import it.federico.g7monitor.data.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Brings the service back if Android (or an aggressive battery manager) killed it:
 * an exact alarm every [INTERVAL_MS] checks that it runs and restarts it if needed.
 * Alarms survive the death of the process (not a "Force stop", which nothing survives),
 * and exact alarms are allowed to start a foreground service from the background.
 */
object KeepAlive {
    private const val INTERVAL_MS = 15 * 60_000L

    private fun intent(context: Context) = PendingIntent.getBroadcast(
        context, 30, Intent(context, KeepAliveReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun schedule(context: Context, delayMs: Long = INTERVAL_MS) {
        val am = context.getSystemService(AlarmManager::class.java)
        val at = SystemClock.elapsedRealtime() + delayMs
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, intent(context))
        else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, intent(context))
    }

    fun cancel(context: Context) = context.getSystemService(AlarmManager::class.java).cancel(intent(context))
}

class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as G7App
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (!app.settings.current().collectorEnabled) return@launch
                if (!G7Service.running.value) {
                    DebugLog.log("servizio non attivo: lo riavvio")
                    runCatching { G7Service.start(context) }
                        .onFailure { DebugLog.log("riavvio non consentito: ${it.javaClass.simpleName}") }
                }
                KeepAlive.schedule(context)
            } finally {
                pending.finish()
            }
        }
    }
}
