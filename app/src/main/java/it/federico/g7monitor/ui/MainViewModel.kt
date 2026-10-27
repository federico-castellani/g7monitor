package it.federico.g7monitor.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.federico.g7monitor.G7App
import it.federico.g7monitor.ble.BondHelper
import it.federico.g7monitor.ble.G7Service
import it.federico.g7monitor.data.AppSettings
import it.federico.g7monitor.data.GlucoseReading
import it.federico.g7monitor.data.SensorStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ChartWindow(val hours: Int, val label: String) {
    H3(3, "3h"), H6(6, "6h"), H12(12, "12h"), H24(24, "24h")
}

data class HomeState(
    val settings: AppSettings = AppSettings(),
    val status: SensorStatus = SensorStatus(),
    val window: ChartWindow = ChartWindow.H6,
    val readings: List<GlucoseReading> = emptyList(),
    val now: Long = System.currentTimeMillis(),
) {
    val latest get() = readings.lastOrNull()
    val previous get() = readings.getOrNull(readings.size - 2)
    val delta: Int?
        get() {
            val l = latest ?: return null
            val p = previous ?: return null
            return if (l.timestamp - p.timestamp < 12 * 60_000L) l.mgdl - p.mgdl else null
        }
    val minutesAgo: Long? get() = latest?.let { (now - it.timestamp) / 60_000L }
    val isStale get() = (minutesAgo ?: Long.MAX_VALUE) >= 15

    val timeInRange: Int?
        get() = if (readings.isEmpty()) null
        else readings.count { it.mgdl in settings.lowMgdl..settings.highMgdl } * 100 / readings.size
    val average: Int? get() = if (readings.isEmpty()) null else readings.sumOf { it.mgdl } / readings.size
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel : ViewModel() {
    private val app = G7App.instance
    private val window = MutableStateFlow(ChartWindow.H6)

    /** Ticks every 30 s so "x min ago" and the chart's time axis stay current. */
    private val clock = flow { while (true) { emit(System.currentTimeMillis()); delay(30_000) } }

    private val readings = combine(window, app.readings.changes) { w, _ -> w }
        .mapLatest { w -> app.readings.since(System.currentTimeMillis() - w.hours * 3_600_000L) }

    val state: StateFlow<HomeState> = combine(app.settings.settings, app.status, window, readings, clock) { s, st, w, r, now ->
        HomeState(s, st, w, r, now)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    init {
        viewModelScope.launch { app.readings.prune(System.currentTimeMillis() - 90L * 24 * 3_600_000L) }
    }

    fun setWindow(w: ChartWindow) { window.value = w }

    fun setPairingCode(code: String) = viewModelScope.launch {
        val changed = app.settings.current().pairingCode != code
        app.settings.setPairingCode(code)
        if (changed) restartCollectorIfEnabled()
    }

    private suspend fun restartCollectorIfEnabled() {
        if (app.settings.current().collectorEnabled) G7Service.restart(app)
    }
    fun setUseMmol(v: Boolean) = viewModelScope.launch { app.settings.setUseMmol(v) }
    fun setRange(low: Int, high: Int) = viewModelScope.launch { app.settings.setRange(low, high) }
    fun setCertificates(p1: String, p2: String, p3: String) = viewModelScope.launch {
        app.settings.setCertificates(p1, p2, p3)
        app.settings.clearBlocks()
        restartCollectorIfEnabled()
    }

    fun setCollector(enabled: Boolean) = viewModelScope.launch {
        app.settings.setCollectorEnabled(enabled)
        if (enabled) G7Service.start(app) else G7Service.stop(app)
    }

    /** Forget the paired sensor: drops the saved key, address and the Android bond. */
    @SuppressLint("MissingPermission")
    fun forgetSensor() = viewModelScope.launch {
        val mac = app.settings.current().sensorMac
        if (mac != null) runCatching {
            val adapter = app.getSystemService(BluetoothManager::class.java).adapter
            BondHelper.removeBond(adapter.getRemoteDevice(mac))
        }
        app.settings.forgetSensor()
        restartCollectorIfEnabled()
    }
}
