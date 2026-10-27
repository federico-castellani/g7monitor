package it.federico.g7monitor

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import it.federico.g7monitor.data.ReadingStore
import it.federico.g7monitor.data.SensorStatus
import it.federico.g7monitor.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow

/** Manual dependency container: the app is small enough not to need a DI framework. */
class G7App : Application() {
    lateinit var readings: ReadingStore
        private set
    lateinit var settings: SettingsRepository
        private set

    /** Live collector status, written by the BLE service, read by the UI. */
    val status = MutableStateFlow(SensorStatus())

    override fun onCreate() {
        super.onCreate()
        instance = this
        readings = ReadingStore(this)
        settings = SettingsRepository(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Glicemia in corso", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Mostra l'ultima glicemia e lo stato del sensore"
                setShowBadge(false)
            }
        )
    }

    companion object {
        const val CHANNEL_STATUS = "status"
        lateinit var instance: G7App
            private set
    }
}
