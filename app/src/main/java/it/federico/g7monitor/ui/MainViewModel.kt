package it.federico.g7monitor.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.federico.g7monitor.G7App
import it.federico.g7monitor.alarm.AlarmConfig
import it.federico.g7monitor.alarm.AlarmType
import it.federico.g7monitor.ble.BondHelper
import it.federico.g7monitor.ble.G7Service
import it.federico.g7monitor.data.AppSettings
import it.federico.g7monitor.data.DataSource
import it.federico.g7monitor.data.DebugLog
import it.federico.g7monitor.data.Fingerstick
import it.federico.g7monitor.data.GlucoseReading
import it.federico.g7monitor.data.Link
import it.federico.g7monitor.data.SensorStatus
import it.federico.g7monitor.meter.GlucoseProfile
import it.federico.g7monitor.net.ServerApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import it.federico.g7monitor.update.AppRelease
import it.federico.g7monitor.update.UpdateState
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import it.federico.g7monitor.R
import it.federico.g7monitor.str
import it.federico.g7monitor.predict.GlucoseModel
import it.federico.g7monitor.data.ChartEvent

/** Names of common Bluetooth meters (used when the advertisement does not list the Glucose service). */
private val METER_NAMES = Regex("contour|accu-?chek|meter|onetouch|verio|caresens|gluco|true ?metrix|beurer|wellion|glucomen", RegexOption.IGNORE_CASE)

enum class ChartWindow(val hours: Int, val label: String) {
    H3(3, "3h"), H6(6, "6h"), H12(12, "12h"), H24(24, "24h");

    companion object {
        fun of(hours: Int) = entries.firstOrNull { it.hours == hours } ?: H6
    }
}

/** What a server setup is for: uploading from the sensor, or receiving (follower). */
enum class ServerPurpose { Upload, Follow }

data class HomeState(
    val settings: AppSettings = AppSettings(),
    val status: SensorStatus = SensorStatus(),
    val window: ChartWindow = ChartWindow.H6,
    /** Readings of the period the chart shows (the present, or the past while scrolled back). */
    val readings: List<GlucoseReading> = emptyList(),
    /** Blood glucose meter results in the chart window. */
    val fingersticks: List<Fingerstick> = emptyList(),
    val now: Long = System.currentTimeMillis(),
    /** false until the settings have been read: avoids flashing the welcome screen. */
    val ready: Boolean = false,
    /** Personal forecast for the next hour after the newest reading; empty while the model is learning. */
    val forecast: List<GlucoseModel.Point> = emptyList(),
    /** Sensor changes in the chart window. */
    val sensorStarts: List<Long> = emptyList(),
    /** Notes (insulin, carbs, exercise, text) in the chart window. */
    val events: List<ChartEvent> = emptyList(),
    /** Start of the sensor in use, as recorded (a follower gets it from the server). */
    val sensorStart: Long? = null,
    /** The newest two readings, whatever period the chart shows: the current value and its change. */
    val recent: List<GlucoseReading> = emptyList(),
    /** End of the chart while looking at the past; null when it follows the present. */
    val chartEnd: Long? = null,
    /** Time of the oldest stored reading: the chart does not go further back. */
    val oldest: Long? = null,
) {
    val latest get() = recent.lastOrNull()
    val previous get() = recent.getOrNull(recent.size - 2)
    val live get() = chartEnd == null
    val chartTo get() = chartEnd ?: now
    val chartFrom get() = chartTo - window.hours * 3_600_000L
    val canGoBack get() = oldest != null && chartFrom > oldest
    val delta: Int?
        get() {
            val l = latest ?: return null
            val p = previous ?: return null
            return if (l.timestamp - p.timestamp < 12 * 60_000L) l.mgdl - p.mgdl else null
        }
    val minutesAgo: Long? get() = latest?.let { (now - it.timestamp) / 60_000L }
    val isStale get() = (minutesAgo ?: Long.MAX_VALUE) >= 15

    // Computed once per state rather than on every recomposition.
    val timeInRange: Int? by lazy(LazyThreadSafetyMode.NONE) {
        if (readings.isEmpty()) null
        else readings.count { it.mgdl in settings.lowMgdl..settings.highMgdl } * 100 / readings.size
    }
    val average: Int? by lazy(LazyThreadSafetyMode.NONE) {
        if (readings.isEmpty()) null else readings.sumOf { it.mgdl } / readings.size
    }
}

/** What the home chart shows, read together from the store. */
private class ChartData(
    val readings: List<GlucoseReading>,
    val fingersticks: List<Fingerstick>,
    val forecast: List<GlucoseModel.Point>,
    val sensorStarts: List<Long>,
    val sensorStart: Long?,
    val recent: List<GlucoseReading>,
    val end: Long?,
    val oldest: Long?,
    val events: List<ChartEvent>,
)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel : ViewModel() {
    private val app = G7App.instance
    /** The chart's hours, saved: the app reopens on the last choice. */
    private val window = app.settings.settings.map { ChartWindow.of(it.chartHours) }.distinctUntilChanged()

    /** Ticks every 30 s so "x min ago" and the chart's time axis stay current. */
    private val clock = flow { while (true) { emit(System.currentTimeMillis()); delay(30_000) } }

    /** End of the chart while looking at the past; null follows the present. */
    private val chartEnd = MutableStateFlow<Long?>(null)

    private val readings = combine(window, app.readings.changes, chartEnd) { w, _, end -> w to end }
        .mapLatest { (w, end) ->
            val now = System.currentTimeMillis()
            val span = w.hours * 3_600_000L
            val from = (end ?: now) - span
            val to = end ?: Long.MAX_VALUE
            val shown = app.readings.between(from, to)
            ChartData(
                shown, app.readings.fingersticksBetween(from, to),
                // The forecast belongs to the present only.
                if (end == null) app.predictor.forecast() else emptyList(),
                app.readings.sensorStartsBetween(from, to), app.readings.latestSensorStart(),
                recent = if (end == null) shown.takeLast(2) else app.readings.newest(2, now - span),
                end = end, oldest = app.readings.oldestTimestamp(),
                events = app.readings.eventsBetween(from, to),
            )
        }

    val state: StateFlow<HomeState> = combine(app.settings.settings, app.status, window, readings, clock) { s, st, w, r, now ->
        HomeState(
            s, st, w, r.readings, r.fingersticks, now, ready = true, r.forecast, r.sensorStarts, events = r.events,
            sensorStart = r.sensorStart, recent = r.recent, chartEnd = r.end, oldest = r.oldest,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    /**
     * Moves the chart one window back (-1) or forward (+1). Forward up to the present goes back to
     * following it; backward stops at the oldest stored reading.
     */
    fun scrollChart(direction: Int) {
        val st = state.value
        val span = st.window.hours * 3_600_000L
        val now = System.currentTimeMillis()
        val end = (chartEnd.value ?: now) + direction * span
        chartEnd.value = when {
            end >= now -> null
            else -> st.oldest?.let { end.coerceAtLeast(minOf(it + span, now)) } ?: end
        }
    }

    /** A note added or changed: on the chart at once, then to the server and Nightscout. */
    fun saveEvent(e: ChartEvent) = viewModelScope.launch {
        app.readings.saveEvent(e)
        app.events.sync()
        app.nightscout.maybeUpload()
    }

    /** Kept as a deletion until the server has it, so the other phones remove it too. */
    fun deleteEvent(e: ChartEvent) = saveEvent(e.copy(deleted = true, updated = System.currentTimeMillis()))

    /** Back to the present (the "now" button, and whenever the app leaves the screen). */
    fun chartToNow() {
        chartEnd.value = null
    }

    init {
        viewModelScope.launch { app.readings.prune(System.currentTimeMillis() - 90L * 24 * 3_600_000L) }
    }

    // ---------------------------------------------------------------- alarms

    val alarmConfigs: StateFlow<Map<AlarmType, AlarmConfig>> = app.alarmSettings.configs
        .stateIn(viewModelScope, SharingStarted.Eagerly, AlarmType.entries.associateWith { AlarmConfig(it) })

    /** Saves and re-checks at once, so a change (or turning an alarm off) applies to an alarm that is ringing. */
    fun saveAlarm(config: AlarmConfig) = viewModelScope.launch {
        app.alarmSettings.save(config)
        app.alarms.evaluate()
    }

    /** All alarms paused until this time (see AlarmRepository.pausedUntil), null when active. */
    val alarmsPausedUntil: StateFlow<Long?> = app.alarmSettings.pausedUntil
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun pauseAlarms(until: Long?) = viewModelScope.launch { app.alarms.pause(until) }

    fun previewAlarm(config: AlarmConfig) = app.alarms.preview(config)

    fun testAlarm(config: AlarmConfig, delayed: Boolean) {
        if (delayed) app.alarms.scheduleTest(config.type, 10_000L) else app.alarms.test(config)
    }
    fun stopAlarmPreview() = app.alarms.stopPreview()

    fun setSensorReminder(v: Boolean) = viewModelScope.launch {
        app.settings.setSensorReminder(v)
        if (v) app.sensorReminder.check()
    }

    // ---------------------------------------------------------------- app updates

    val updateState: StateFlow<UpdateState> = app.updater.state
    val installedVersion: String = app.updater.installedVersion

    /** [force]: "Controlla ora"; otherwise at most every few hours (called whenever the app is opened). */
    fun checkForUpdates(force: Boolean) = viewModelScope.launch { app.updater.check(force) }

    fun installUpdate(release: AppRelease) = viewModelScope.launch { app.updater.install(release) }

    fun resetUpdate() = app.updater.reset()

    fun setWindow(w: ChartWindow) = viewModelScope.launch { app.settings.setChartHours(w.hours) }

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

    /** Sensor mode: Bluetooth collection. Server mode: download in the background (notification). */
    fun setCollector(enabled: Boolean) = viewModelScope.launch {
        app.settings.setCollectorEnabled(enabled)
        if (enabled) G7Service.start(app) else G7Service.stop(app)
    }

    // ---------------------------------------------------------------- data source

    /** Switching mode stops whatever was running; each mode keeps its own configuration. */
    fun setDataSource(source: DataSource) = viewModelScope.launch {
        val s = app.settings.current()
        if (s.dataSource == source) return@launch
        G7Service.stop(app)
        app.settings.setCollectorEnabled(false)
        app.settings.setDataSource(source)
        app.status.value = SensorStatus()
        // A follower already linked to a server resumes on its own.
        if (source == DataSource.Server && s.followConnected) setCollector(true)
    }

    /** While the app is open in follower mode: refresh now (the service, if running, keeps going on its own). */
    fun refreshFromServer() = viewModelScope.launch {
        val s = app.settings.current()
        if (s.dataSource != DataSource.Server || !s.followConnected) return@launch
        app.follower.sync()
    }

    /**
     * App open in follower mode without the background service: live connection while the screen is visible.
     */
    suspend fun followWhileVisible() {
        refreshFromServer().join()
        while (true) {
            val s = app.settings.current()
            // With the background service on, the service already holds the live connection.
            if (s.dataSource == DataSource.Server && s.followConnected && !s.collectorEnabled) app.follower.follow()
            delay(30_000)
        }
    }

    // ---------------------------------------------------------------- server

    sealed interface ServerResult {
        /** Address checked or account linked; [url] is the normalized address. */
        data class Ok(val url: String) : ServerResult
        data object NeedsTotp : ServerResult
        data class Error(val message: String) : ServerResult
    }

    /** Step 1: is there a G7 Monitor server at this address? No credentials are sent. */
    fun checkServer(url: String, onResult: (ServerResult) -> Unit) = viewModelScope.launch {
        val base = ServerApi.normalizeUrl(url)
            ?: return@launch onResult(ServerResult.Error(str(R.string.vm_bad_address)))
        val result = withContext(Dispatchers.IO) { runCatching { ServerApi.checkServer(base) } }
        onResult(result.fold({ ServerResult.Ok(base) }, { ServerResult.Error(describe(it)) }))
    }

    /** Step 2: pairs this phone with the account; the password is not stored. */
    fun connectServer(
        purpose: ServerPurpose, url: String, user: String, password: String, totp: String?,
        onResult: (ServerResult) -> Unit,
    ) = viewModelScope.launch {
        val name = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
        val reader = purpose == ServerPurpose.Follow
        val result = withContext(Dispatchers.IO) { runCatching { ServerApi.pair(url, user.trim(), password, totp, name, reader) } }
        result.onSuccess { paired ->
            when (purpose) {
                ServerPurpose.Upload -> {
                    app.settings.setServer(url, paired.username, paired.token)
                    app.readings.markAllPending()
                    onResult(ServerResult.Ok(url))
                    restoreFromServer(url, paired.token)
                    app.uploader.maybeUpload(force = true)
                }
                ServerPurpose.Follow -> {
                    app.settings.setFollow(url, paired.username, paired.token)
                    app.status.value = SensorStatus(link = Link.Waiting, message = str(R.string.vm_connected_server))
                    onResult(ServerResult.Ok(url))
                    app.follower.sync()
                    setCollector(true)
                }
            }
        }.onFailure { e ->
            onResult(
                if (e is ServerApi.HttpError && e.code == "totp_richiesto") ServerResult.NeedsTotp
                else ServerResult.Error(describe(e))
            )
        }
    }

    /**
     * After a reset the phone only gets the last 24 h back from the sensor's backfill, if that:
     * download them from the server too, so the chart is complete at once. They are stored as
     * already uploaded; where the phone has its own reading for the same moment, that one is kept
     * (local ±2 min de-duplication) and the server ignores it as a duplicate when it is sent.
     */
    private suspend fun restoreFromServer(url: String, token: String) {
        val since = System.currentTimeMillis() - 24 * 3_600_000L
        val fetched = withContext(Dispatchers.IO) { runCatching { ServerApi.fetchSince(url, token, since, since, 0L) }.getOrNull() } ?: return
        app.readings.insertFingersticks(fetched.fingersticks, fromServer = true)
        app.readings.addSensorStarts(fetched.sensorStarts, fromServer = true)
        val added = app.readings.insertAll(fetched.readings, fromServer = true)
        if (added > 0) DebugLog.log(str(R.string.upload_restored, added))
    }

    private fun describe(e: Throwable): String = when (e) {
        is ServerApi.HttpError -> e.message ?: str(R.string.vm_server_error)
        is ServerApi.NotG7Server -> e.message!!
        is UnknownHostException -> str(R.string.vm_server_not_found)
        is SSLException -> str(R.string.vm_bad_certificate)
        is ConnectException, is SocketTimeoutException -> str(R.string.vm_no_response)
        else -> str(R.string.vm_connection_failed, e.javaClass.simpleName)
    }

    fun disconnectServer(purpose: ServerPurpose) = viewModelScope.launch {
        val s = app.settings.current()
        val (url, token) = when (purpose) {
            ServerPurpose.Upload -> s.serverUrl to s.serverToken
            ServerPurpose.Follow -> s.followUrl to s.followToken
        }
        if (token.isNotBlank()) withContext(Dispatchers.IO) { runCatching { ServerApi.unpair(url, token) } }
        when (purpose) {
            ServerPurpose.Upload -> app.settings.disconnectServer(null)
            ServerPurpose.Follow -> {
                setCollector(false).join()
                app.settings.disconnectFollow(null)
                app.status.value = SensorStatus()
            }
        }
    }

    fun uploadNow() = viewModelScope.launch { app.uploader.maybeUpload(force = true) }

    /** Pausing keeps the server link; resuming sends at once what was collected meanwhile. */
    fun setUploadPaused(paused: Boolean) = viewModelScope.launch {
        app.settings.setUploadPaused(paused)
        DebugLog.log(str(if (paused) R.string.upload_paused_log else R.string.upload_resumed_log))
        if (!paused) app.uploader.maybeUpload(force = true)
    }
    fun setUploadInterval(minutes: Int) = viewModelScope.launch { app.settings.setUploadInterval(minutes) }
    fun setUploadWifiOnly(v: Boolean) = viewModelScope.launch {
        app.settings.setUploadWifiOnly(v)
        if (!v) app.uploader.maybeUpload()
    }

    // ---------------------------------------------------------------- blood glucose meter

    data class FoundMeter(val address: String, val name: String?, val rssi: Int)

    /** Meter results of the last 7 days, newest first (meter page). */
    val recentFingersticks: StateFlow<List<Fingerstick>> = app.readings.changes
        .mapLatest { app.readings.fingersticksSince(System.currentTimeMillis() - 7 * 24 * 3_600_000L).reversed() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _meters = MutableStateFlow<List<FoundMeter>>(emptyList())
    val meters: StateFlow<List<FoundMeter>> = _meters
    private val _scanning = MutableStateFlow(false)
    val meterScanning: StateFlow<Boolean> = _scanning
    private var scanCallback: ScanCallback? = null
    private var scanTimer: Job? = null

    /**
     * Looks for meters in pairing mode for 30 s: devices advertising the standard Glucose service,
     * plus the usual meter names (some only list the service once connected).
     * @return an error to show, or null
     */
    @SuppressLint("MissingPermission")
    fun startMeterScan(): String? {
        stopMeterScan()
        val scanner = app.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
            ?: return str(R.string.vm_bluetooth_off)
        _meters.value = emptyList()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull()
                val glucose = result.scanRecord?.serviceUuids?.any { it.uuid == GlucoseProfile.GLUCOSE_SERVICE } == true
                if (!glucose && (name == null || !METER_NAMES.containsMatchIn(name))) return
                val found = FoundMeter(result.device.address, name, result.rssi)
                _meters.value = (_meters.value.filter { it.address != found.address } + found).sortedByDescending { it.rssi }
            }
        }
        val started = runCatching {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        }
        if (started.isFailure) return str(R.string.vm_needs_service)
        scanCallback = cb
        _scanning.value = true
        scanTimer = viewModelScope.launch {
            delay(30_000)
            stopMeterScan()
        }
        return null
    }

    @SuppressLint("MissingPermission")
    fun stopMeterScan() {
        scanTimer?.cancel()
        scanTimer = null
        scanCallback?.let { cb ->
            runCatching { app.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(cb) }
        }
        scanCallback = null
        _scanning.value = false
    }

    override fun onCleared() {
        stopMeterScan()
    }

    /** Android asks for the PIN shown on the meter; then the service downloads the measurements. */
    fun pairMeter(meter: FoundMeter, onResult: (error: String?) -> Unit) = viewModelScope.launch {
        stopMeterScan()
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return@launch onResult(str(R.string.vm_bluetooth_off))
        val device = adapter.getRemoteDevice(meter.address)
        val bonded = runCatching { BondHelper.createBond(app, device, 120_000) }.getOrDefault(false)
        if (!bonded) return@launch onResult(str(R.string.vm_pairing_failed))
        app.settings.setMeter(meter.address, meter.name)
        DebugLog.log(str(R.string.mc_paired, meter.name ?: meter.address))
        onResult(null)
    }

    @SuppressLint("MissingPermission")
    fun forgetMeter() = viewModelScope.launch {
        val mac = app.settings.current().meterMac
        if (mac != null) runCatching {
            BondHelper.removeBond(app.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(mac))
        }
        app.settings.forgetMeter()
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
