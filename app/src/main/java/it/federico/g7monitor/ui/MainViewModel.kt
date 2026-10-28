package it.federico.g7monitor.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
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
import it.federico.g7monitor.data.GlucoseReading
import it.federico.g7monitor.data.Link
import it.federico.g7monitor.data.SensorStatus
import it.federico.g7monitor.net.ServerApi
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

enum class ChartWindow(val hours: Int, val label: String) {
    H3(3, "3h"), H6(6, "6h"), H12(12, "12h"), H24(24, "24h")
}

/** What a server setup is for: uploading from the sensor, or receiving (follower). */
enum class ServerPurpose { Upload, Follow }

data class HomeState(
    val settings: AppSettings = AppSettings(),
    val status: SensorStatus = SensorStatus(),
    val window: ChartWindow = ChartWindow.H6,
    val readings: List<GlucoseReading> = emptyList(),
    val now: Long = System.currentTimeMillis(),
    /** false until the settings have been read: avoids flashing the welcome screen. */
    val ready: Boolean = false,
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

    // Computed once per state rather than on every recomposition.
    val timeInRange: Int? by lazy(LazyThreadSafetyMode.NONE) {
        if (readings.isEmpty()) null
        else readings.count { it.mgdl in settings.lowMgdl..settings.highMgdl } * 100 / readings.size
    }
    val average: Int? by lazy(LazyThreadSafetyMode.NONE) {
        if (readings.isEmpty()) null else readings.sumOf { it.mgdl } / readings.size
    }
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
        HomeState(s, st, w, r, now, ready = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

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

    fun previewAlarm(config: AlarmConfig) = app.alarms.preview(config)
    fun stopAlarmPreview() = app.alarms.stopPreview()

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
            ?: return@launch onResult(ServerResult.Error("Indirizzo non valido: serve un indirizzo https://"))
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
                    app.status.value = SensorStatus(link = Link.Waiting, message = "Collegato al server")
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
        val rows = withContext(Dispatchers.IO) { runCatching { ServerApi.fetchSince(url, token, since) }.getOrNull() } ?: return
        val added = app.readings.insertAll(rows, fromServer = true)
        if (added > 0) DebugLog.log("server: ripristinate $added letture delle ultime 24 ore")
    }

    private fun describe(e: Throwable): String = when (e) {
        is ServerApi.HttpError -> e.message ?: "Errore del server"
        is ServerApi.NotG7Server -> e.message!!
        is UnknownHostException -> "Server non trovato: controlla l'indirizzo e la connessione"
        is SSLException -> "Certificato HTTPS del server non valido"
        is ConnectException, is SocketTimeoutException -> "Il server non risponde"
        else -> "Connessione non riuscita (${e.javaClass.simpleName})"
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
    fun setUploadInterval(minutes: Int) = viewModelScope.launch { app.settings.setUploadInterval(minutes) }
    fun setUploadWifiOnly(v: Boolean) = viewModelScope.launch {
        app.settings.setUploadWifiOnly(v)
        if (!v) app.uploader.maybeUpload()
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
