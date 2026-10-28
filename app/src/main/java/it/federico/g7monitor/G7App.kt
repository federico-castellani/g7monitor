package it.federico.g7monitor

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import it.federico.g7monitor.alarm.AlarmEngine
import it.federico.g7monitor.alarm.AlarmRepository
import it.federico.g7monitor.data.ReadingStore
import it.federico.g7monitor.data.SensorStatus
import it.federico.g7monitor.data.SettingsRepository
import it.federico.g7monitor.net.Follower
import it.federico.g7monitor.net.Uploader
import it.federico.g7monitor.watch.WatchSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Manual dependency container: the app is small enough not to need a DI framework. */
class G7App : Application() {
    lateinit var readings: ReadingStore
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var uploader: Uploader
        private set
    lateinit var follower: Follower
        private set
    lateinit var alarmSettings: AlarmRepository
        private set
    lateinit var alarms: AlarmEngine
        private set
    lateinit var watch: WatchSync
        private set

    /** Live collector status, written by the BLE service, read by the UI. */
    val status = MutableStateFlow(SensorStatus())

    /** Work that lives as long as the process (alarm checks). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        readings = ReadingStore(this)
        settings = SettingsRepository(this)
        uploader = Uploader(this)
        follower = Follower(this)
        alarmSettings = AlarmRepository(this)
        alarms = AlarmEngine(this)
        watch = WatchSync(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Glicemia in corso", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Mostra l'ultima glicemia e lo stato del sensore"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARMS, "Allarmi glicemia", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Glicemia bassa o alta, variazioni rapide, mancanza di segnale"
                // Sound and vibration are played by the app on the alarm stream, with the user's choices.
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        // Every new reading, from the sensor or from the server, is checked against the alarms.
        scope.launch { readings.changes.collect { alarms.evaluate() } }
        // ...and sent to the watch complications (again when unit or target range change).
        val display = settings.settings.map { Triple(it.useMmol, it.lowMgdl, it.highMgdl) }.distinctUntilChanged()
        scope.launch { combine(readings.changes, display) { _, _ -> }.collect { watch.push() } }
    }

    companion object {
        const val CHANNEL_STATUS = "status"
        const val CHANNEL_ALARMS = "alarms"
        lateinit var instance: G7App
            private set
    }
}
