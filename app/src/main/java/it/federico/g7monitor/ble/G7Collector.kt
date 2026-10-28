package it.federico.g7monitor.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.PowerManager
import android.util.Log
import it.federico.g7monitor.G7App
import it.federico.g7monitor.data.DebugLog
import it.federico.g7monitor.data.GlucoseReading
import it.federico.g7monitor.data.Link
import it.federico.g7monitor.protocol.AuthAction
import it.federico.g7monitor.protocol.G7Auth
import it.federico.g7monitor.protocol.G7BackfillRecord
import it.federico.g7monitor.protocol.G7Certificates
import it.federico.g7monitor.protocol.G7Commands
import it.federico.g7monitor.protocol.G7Gatt
import it.federico.g7monitor.protocol.G7Glucose
import it.federico.g7monitor.protocol.SensorState
import it.federico.g7monitor.protocol.hexToBytes
import it.federico.g7monitor.protocol.toHex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Drives the whole G7 cycle: find sensor -> connect -> authenticate (J-PAKE) ->
 * read glucose (+ backfill gaps) -> disconnect -> wait for the next reading.
 *
 * Mirrors the logic of xDrip's Ob1G5CollectionService / Ob1G5StateMachine, reduced
 * to what a G7 needs.
 */
@SuppressLint("MissingPermission")
class G7Collector(private val context: Context, private val scope: CoroutineScope) {

    private val app = G7App.instance
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private var job: Job? = null
    private var currentClient: GattClient? = null

    /** Kept across connections so J-PAKE results survive a reconnect. */
    private var auth: G7Auth? = null
    private var authFor: String? = null

    private var consecutiveFailures = 0
    private val mismatches = HashMap<String, Int>()
    @Volatile private var forceScan = false
    private var lastSuccess = 0L

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        currentClient?.close()
        setLink(Link.Off, null)
    }

    /** Called by the watchdog alarm when readings stopped: abandon the wait and scan again. */
    fun kick() {
        if (System.currentTimeMillis() - lastSuccess < 10 * 60_000L) return
        log("watchdog: no data, rescanning")
        forceScan = true
        currentClient?.close()
    }

    private fun log(msg: String) = DebugLog.log(msg)

    private fun setLink(link: Link, message: String? = null) {
        val old = app.status.value
        if (old.link != link || old.message != message) log("stato: ${link.label}${message?.let { " — $it" } ?: ""}")
        app.status.value = old.copy(link = link, message = message)
    }

    private suspend fun loop() {
        while (scope.isActive) {
            val s = app.settings.current()
            if (!s.pairingCode.matches(PAIRING_CODE)) {
                setLink(Link.Error, "Inserisci il codice di abbinamento del sensore nelle impostazioni")
                app.settings.settings.first { it.pairingCode.matches(PAIRING_CODE) }
                continue
            }
            if (adapter == null || !adapter.isEnabled) {
                setLink(Link.Error, "Bluetooth spento")
                delay(15_000)
                continue
            }
            try {
                // Saved sensor, or (if the address was lost) a G7 Android already has a bond with.
                val savedMac = s.sensorMac ?: bondedG7()?.also { log("uso il G7 già abbinato ad Android: $it") }
                val known = savedMac?.let { runCatching { adapter.getRemoteDevice(it) }.getOrNull() }
                val useKnown = known != null && BondHelper.isBonded(known) && !forceScan && consecutiveFailures < 3
                forceScan = false
                val device = if (useKnown) known!! else scan(savedMac) ?: continue
                val ok = session(device, autoConnect = useKnown, code = s.pairingCode)
                if (ok) {
                    consecutiveFailures = 0
                } else {
                    consecutiveFailures++
                    // Back off a little after repeated failures (sensor busy / out of range).
                    if (consecutiveFailures >= 3) delay(20_000)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "cycle error", e)
                log("errore: ${e.javaClass.simpleName}: ${e.message}")
                setLink(Link.Error, e.message)
                delay(10_000)
            }
        }
    }

    // ---------------------------------------------------------------- scanning

    /** Address of a Dexcom sensor that has an Android bond (most recent wins), if any. */
    private fun bondedG7(): String? = runCatching {
        adapter.bondedDevices.filter { G7Gatt.isG7Name(it.name) }.map { it.address }.lastOrNull()
    }.getOrNull()

    private suspend fun scan(preferredMac: String?): BluetoothDevice? {
        val scanner = adapter.bluetoothLeScanner ?: run {
            log("scanner Bluetooth non disponibile")
            return null
        }
        setLink(Link.Scanning)
        // A bonded G7 may stop advertising the Dexcom service UUID, so also match by address.
        val filters = buildList {
            add(ScanFilter.Builder().setServiceUuid(ParcelUuid(G7Gatt.ADVERTISEMENT)).build())
            if (preferredMac != null) add(ScanFilter.Builder().setDeviceAddress(preferredMac).build())
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val blocked = mutableSetOf<String>()
        val ignoredLogged = mutableSetOf<String>()
        log("scansione avviata (filtro servizio FEBC${preferredMac?.let { ", MAC preferito $it" } ?: ""})")
        DebugLog.scan.value = DebugLog.ScanInfo(active = true, startedAt = System.currentTimeMillis())
        var failed = false
        // Android degrades scans that run for a long time: restart every 10 minutes.
        val found = withTimeoutOrNull(SCAN_RESTART_MS) {
            suspendCancellableCoroutine { cont ->
                val cb = object : ScanCallback() {
                    override fun onScanResult(type: Int, result: ScanResult) {
                        val dev = result.device
                        val name = result.scanRecord?.deviceName ?: dev.name
                        val info = DebugLog.scan.value
                        DebugLog.scan.value = info.copy(
                            filteredResults = info.filteredResults + 1,
                            lastResultAt = System.currentTimeMillis(),
                            lastResult = "${name ?: "(senza nome)"} ${dev.address} ${result.rssi} dBm",
                        )
                        if (dev.address in blocked) return
                        val matches = (preferredMac != null && dev.address == preferredMac) || G7Gatt.isG7Name(name)
                        if (!matches) {
                            if (ignoredLogged.add(dev.address)) log("ignorato ${name ?: "(senza nome)"} ${dev.address}: non è un G7")
                            return
                        }
                        val callback = this
                        scope.launch {
                            if (app.settings.isBlocked(dev.address)) {
                                if (blocked.add(dev.address)) log("ignorato $name ${dev.address}: in pausa dopo un errore (riprovo tra qualche minuto)")
                                return@launch
                            }
                            if (cont.isActive) {
                                log("TROVATO $name ${dev.address} rssi=${result.rssi}")
                                runCatching { scanner.stopScan(callback) }
                                app.status.value = app.status.value.copy(deviceName = name)
                                cont.resume(dev)
                            }
                        }
                    }

                    override fun onScanFailed(errorCode: Int) {
                        log("scansione fallita, codice $errorCode${if (errorCode == 6) " (avvii troppo frequenti)" else ""}")
                        failed = true
                        if (cont.isActive) cont.resume(null)
                    }
                }
                scanner.startScan(filters, settings, cb)
                cont.invokeOnCancellation { runCatching { scanner.stopScan(cb) } }
            }
        }
        DebugLog.scan.value = DebugLog.scan.value.copy(active = false)
        if (found == null && !failed) log("nessun G7 visto in ${SCAN_RESTART_MS / 60_000} min, riavvio la scansione")
        if (failed) delay(30_000)
        return found
    }

    // ---------------------------------------------------------------- session

    private suspend fun session(device: BluetoothDevice, autoConnect: Boolean, code: String): Boolean {
        val mac = device.address
        val wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "g7monitor:session")
        val client = GattClient(context, device) { log(it) }
        currentClient = client
        try {
            log(if (autoConnect) "attendo il sensore $mac (riconnessione automatica)" else "connessione a $mac…")
            setLink(if (autoConnect) Link.Waiting else Link.Connecting)
            // autoConnect waits for the sensor's next advertisement (~every 5 min).
            val connected = client.connect(autoConnect, if (autoConnect) 15 * 60_000L else 30_000L)
            if (!connected) {
                log("connect failed (auto=$autoConnect)")
                if (autoConnect) forceScan = true
                return false
            }
            wakeLock.acquire(90_000)

            if (autoConnect && System.currentTimeMillis() - lastSuccess < 60_000) {
                // Sensor re-advertised right after our last session; nothing new yet.
                log("reconnected too early, dropping connection")
                client.disconnect()
                delay((lastSuccess + 60_000 - System.currentTimeMillis()).coerceIn(1_000, 60_000))
                return true
            }

            log("connesso, scopro i servizi")
            setLink(Link.Connecting)
            if (!client.discover() || client.characteristic(G7Gatt.EXTRA_DATA) == null) {
                log("service discovery failed / not a G7")
                return false
            }
            client.requestHighPriority()
            val result = authenticateAndRead(client, mac, code)
            if (result) {
                lastSuccess = System.currentTimeMillis()
                rememberSensor(mac)
            }
            return result
        } finally {
            withContext(NonCancellable) {
                if (client.isConnected) {
                    withTimeoutOrNull(3_000) { client.write(G7Gatt.CONTROL, G7Commands.DISCONNECT) }
                    client.disconnect()
                    delay(300)
                }
                client.close()
                currentClient = null
                if (wakeLock.isHeld) wakeLock.release()
                if (app.status.value.link != Link.Error) setLink(Link.Waiting)
            }
        }
    }

    private suspend fun rememberSensor(mac: String) {
        val previous = app.settings.current().sensorMac
        if (previous != null && previous != mac) {
            // New sensor: drop the bond of the old one, like xDrip does.
            runCatching { BondHelper.removeBond(adapter.getRemoteDevice(previous)) }
            app.settings.setAuthKey(previous, null)
        }
        app.settings.setSensorMac(mac)
    }

    private suspend fun authFor(mac: String, code: String): G7Auth {
        val id = "$mac/$code"
        auth?.takeIf { authFor == id }?.let { return it }
        val s = app.settings.current()
        val saved = app.settings.authKey(mac)?.let { runCatching { it.hexToBytes() }.getOrNull() }
        val certs = if (s.hasCertificates) G7Certificates.fromHex(s.certP1, s.certP2, s.certP3) else null
        return G7Auth(code, saved, certs) { log(it) }.also { auth = it; authFor = id }
    }

    private class SessionState {
        var dataStarted = false
        var gotGlucose = false
        var backfillRequested = false
        var backfill = ByteArray(0)
        var done = false
    }

    private suspend fun authenticateAndRead(client: GattClient, mac: String, code: String): Boolean {
        val auth = authFor(mac, code)
        auth.locallyBonded = BondHelper.isBonded(client.device)
        setLink(Link.Authenticating)

        if (!client.enable(G7Gatt.EXTRA_DATA, indication = false)) return false
        if (!client.enable(G7Gatt.AUTHENTICATION, indication = true)) return false

        val flow = SessionState()
        if (!execute(client, auth, auth.onConnected(), flow, mac)) return false

        while (!flow.done) {
            // Short idle timeout while backfill streams in; otherwise allow for slow J-PAKE.
            val timeout = if (flow.backfillRequested) 3_000L else 20_000L
            val ev = withTimeoutOrNull(timeout) { client.events.receiveCatching().getOrNull() }
            when (ev) {
                null -> {
                    if (flow.backfillRequested) {
                        finishBackfill(flow)
                        flow.done = true
                    } else {
                        log("timeout in ${auth.state}")
                        return flow.gotGlucose
                    }
                }
                GattClient.Event.Disconnected -> return flow.gotGlucose
                is GattClient.Event.Notification -> when (ev.uuid) {
                    G7Gatt.EXTRA_DATA -> auth.onExtraData(ev.value)?.let {
                        if (!execute(client, auth, it, flow, mac)) return false
                    }
                    G7Gatt.AUTHENTICATION -> for (a in auth.onAuthenticationData(ev.value)) {
                        if (!execute(client, auth, a, flow, mac)) return false
                    }
                    G7Gatt.CONTROL -> onControl(client, ev.value, flow)
                    G7Gatt.BACKFILL -> flow.backfill += ev.value
                }
            }
        }
        return flow.gotGlucose
    }

    /** @return false when the session should end. */
    private suspend fun execute(client: GattClient, auth: G7Auth, action: AuthAction, flow: SessionState, mac: String): Boolean {
        log("action $action")
        when (action) {
            is AuthAction.Write -> {
                action.extraData?.let { data ->
                    for (i in data.indices step G7Gatt.CHUNK) {
                        val chunk = data.copyOfRange(i, minOf(data.size, i + G7Gatt.CHUNK))
                        client.write(G7Gatt.EXTRA_DATA, chunk, withResponse = false)
                        delay(40)
                    }
                    delay(500)
                }
                action.command?.let { if (!client.write(G7Gatt.AUTHENTICATION, it)) return false }
                return true
            }
            AuthAction.ReadyForData -> {
                log("autenticato e abbinato")
                mismatches.remove(mac)
                auth.sharedKey()?.let { app.settings.setAuthKey(mac, it.toHex()) }
                rememberSensor(mac)
                return startData(client, flow)
            }
            AuthAction.CreateBond -> {
                setLink(Link.Bonding, "Conferma l'abbinamento se Android lo chiede")
                auth.sharedKey()?.let { app.settings.setAuthKey(mac, it.toHex()) }
                val bonded = BondHelper.createBond(context, client.device)
                log(if (bonded) "abbinamento Bluetooth completato" else "abbinamento Bluetooth NON riuscito")
                if (!bonded) return false
                auth.locallyBonded = true
                // Remember the sensor now: from here on we reconnect to it directly instead of scanning.
                rememberSensor(mac)
                // Give the link a moment to switch to encryption before asking for data.
                delay(1_500)
                if (!client.isConnected) {
                    log("il sensore ha chiuso la connessione dopo l'abbinamento: riprovo al prossimo ciclo")
                    return false
                }
                return startData(client, flow)
            }
            AuthAction.BondFailure -> {
                setLink(Link.Error, "Il sensore ha rifiutato l'abbinamento, riprovo")
                BondHelper.removeBond(client.device)
                app.settings.setAuthKey(mac, null)
                return false
            }
            AuthAction.Stalled -> return false
            is AuthAction.Mismatch -> {
                auth.forgetKey()
                app.settings.setAuthKey(mac, null)
                val strikes = (mismatches[mac] ?: 0) + 1
                mismatches[mac] = strikes
                log("autenticazione non riuscita su $mac (tentativo $strikes)")
                if (strikes >= 3) {
                    // Probably an old sensor nearby or a really wrong code: pause it briefly.
                    setLink(Link.Error, "Autenticazione fallita 3 volte: controlla il codice di abbinamento")
                    app.settings.block(mac, 10 * 60_000L)
                    mismatches.remove(mac)
                } else {
                    setLink(Link.Error, "Autenticazione non riuscita, riprovo")
                }
                return false
            }
            is AuthAction.ConfigError -> {
                setLink(Link.Error, action.reason)
                app.settings.block(mac, 5 * 60_000L)
                return false
            }
        }
    }

    private suspend fun startData(client: GattClient, flow: SessionState): Boolean {
        if (flow.dataStarted) return true
        flow.dataStarted = true
        setLink(Link.Receiving)
        log("richiedo la glicemia")
        repeat(2) { attempt ->
            if (client.enable(G7Gatt.CONTROL, indication = true) && client.write(G7Gatt.CONTROL, G7Commands.GLUCOSE)) return true
            log("richiesta glicemia fallita (tentativo ${attempt + 1})")
            delay(1_000)
        }
        return false
    }

    private suspend fun onControl(client: GattClient, value: ByteArray, flow: SessionState) {
        val g = G7Glucose.parse(value) ?: run {
            log("control rx ${value.toHex()}")
            return
        }
        log("glicemia ${g.glucoseMgdl} mg/dL, stato ${g.adjustedState.label}, età ${g.ageSeconds}s")
        val now = System.currentTimeMillis()
        val state = g.adjustedState
        log("glucose $g")
        flow.gotGlucose = true
        app.status.value = app.status.value.copy(sensorState = state, lastContact = now)
        val sessionStart = g.sessionStart(now)
        if (state != SensorState.Stopped) app.settings.setSessionStart(sessionStart)

        val readingTime = g.readingTime(now)
        if (state.usable && g.glucoseMgdl in 39..401) {
            app.readings.insert(GlucoseReading(readingTime, g.glucoseMgdl, g.trendMgdlPerMin, GlucoseReading.Source.Live))
        }

        if (state.usable && requestBackfillIfNeeded(client, sessionStart, readingTime)) {
            flow.backfillRequested = true
        } else {
            flow.done = true
        }
    }

    /** Asks for missing readings (max 24 h), like xDrip backFillIfNeeded(). */
    private suspend fun requestBackfillIfNeeded(client: GattClient, sessionStart: Long, latest: Long): Boolean {
        val windowStart = maxOf(latest - MAX_BACKFILL_MS, sessionStart + PERIOD_MS)
        val readings = app.readings.since(windowStart).filter { it.timestamp < latest - 60_000 }
        var gapStart: Long? = null
        var prev = windowStart
        for (r in readings) {
            if (r.timestamp - prev > PERIOD_MS + 2 * 60_000) { gapStart = prev; break }
            prev = r.timestamp
        }
        if (gapStart == null && latest - prev > PERIOD_MS + 2 * 60_000) gapStart = prev
        gapStart ?: return false
        val startDex = ((gapStart - sessionStart) / 1000).toInt()
        val endDex = ((latest - sessionStart) / 1000).toInt()
        if (startDex < 1 || endDex <= startDex) return false
        log("requesting backfill ${(endDex - startDex) / 60} min")
        if (!client.enable(G7Gatt.BACKFILL, indication = false)) return false
        return client.write(G7Gatt.CONTROL, G7Commands.backfill(startDex, endDex))
    }

    private suspend fun finishBackfill(flow: SessionState) {
        val sessionStart = app.settings.current().sessionStart ?: return
        val now = System.currentTimeMillis()
        val records = G7BackfillRecord.parseStream(flow.backfill)
            .filter { it.state.usable || it.state == SensorState.InsufficientCalibration }
            .map {
                GlucoseReading(sessionStart + it.sensorSeconds * 1000, it.glucoseMgdl, it.trendMgdlPerMin, GlucoseReading.Source.Backfill)
            }
            .filter { it.timestamp in (now - MAX_BACKFILL_MS - 3_600_000)..now && it.mgdl in 39..401 }
        val added = app.readings.insertAll(records)
        log("backfill: ${records.size} records, $added new")
    }

    companion object {
        private const val TAG = "G7Collector"
        const val PERIOD_MS = 5 * 60_000L
        const val SCAN_RESTART_MS = 10 * 60_000L
        const val MAX_BACKFILL_MS = 24 * 60 * 60_000L
        private val PAIRING_CODE = Regex("\\d{4}|\\d{6}")
    }
}
