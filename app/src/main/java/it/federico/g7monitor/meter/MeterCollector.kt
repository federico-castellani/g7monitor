package it.federico.g7monitor.meter

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import it.federico.g7monitor.G7App
import it.federico.g7monitor.ble.GattClient
import it.federico.g7monitor.data.AppSettings
import it.federico.g7monitor.data.DebugLog
import it.federico.g7monitor.data.Fingerstick
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Waits for the paired blood glucose meter and imports its new measurements.
 *
 * Meters turn their radio on for a short while after each test. A pending "auto connect"
 * to the bonded meter waits for that inside the Bluetooth controller, costing almost no
 * battery; then the new records (by sequence number) are downloaded and the link is closed.
 */
@SuppressLint("MissingPermission")
class MeterCollector(private val context: Context, private val scope: CoroutineScope) {
    private val app get() = G7App.instance
    private var job: Job? = null

    private fun log(msg: String) = DebugLog.log("glucometro: $msg")

    private fun status(text: String) {
        app.meterStatus.value = text
    }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        status("Fermo")
    }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            val s = app.settings.current()
            val mac = s.meterMac
            if (mac == null) {
                status("Nessun glucometro abbinato")
                app.settings.settings.first { it.meterMac != null }
                continue
            }
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
            val client = GattClient(context, adapter.getRemoteDevice(mac), GlucoseProfile.GLUCOSE_SERVICE) { log(it) }
            try {
                status("In attesa del glucometro")
                if (connectUnlessUnpaired(client, mac)) {
                    status("Scarico le misurazioni…")
                    sync(client, app.settings.current())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("errore ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                client.disconnect()
                client.close()
            }
            delay(3_000)
        }
    }

    /** Waits for the meter (up to 6 h, then the request is renewed), or stops if it is forgotten or replaced. */
    private suspend fun connectUnlessUnpaired(client: GattClient, mac: String): Boolean = coroutineScope {
        val connect = async { client.connect(autoConnect = true, timeoutMs = 6 * 3_600_000L) }
        val changed = async { app.settings.settings.first { it.meterMac != mac }; false }
        select {
            connect.onAwait { it }
            changed.onAwait { it }
        }.also {
            connect.cancel()
            changed.cancel()
        }
    }

    private suspend fun sync(client: GattClient, s: AppSettings) {
        if (!client.discover() || client.characteristic(GlucoseProfile.MEASUREMENT) == null) {
            log("servizio glicemia non trovato")
            return
        }
        client.enable(GlucoseProfile.MEASUREMENT, indication = false)
        if (client.characteristic(GlucoseProfile.MEASUREMENT_CONTEXT) != null) {
            client.enable(GlucoseProfile.MEASUREMENT_CONTEXT, indication = false)
        }
        if (!client.enable(GlucoseProfile.RACP, indication = true)) {
            log("il glucometro rifiuta la richiesta dei dati: abbinamento mancante?")
            status("Abbinamento non valido: abbina di nuovo il glucometro")
            return
        }
        val clockError = clockError(client)

        // As xDrip+ for Contour/Ascensia: only the records after the newest one already imported.
        val last = s.meterLastSequence
        val records = query(client, if (last < 0) GlucoseProfile.requestAll() else GlucoseProfile.requestFrom(last + 1))

        val now = System.currentTimeMillis()
        // First import: only the last week, not the meter's whole memory.
        val oldest = if (last < 0) now - FIRST_IMPORT_MS else 0L
        val valid = records
            .filter { !it.controlSolution && it.mgdl != null && it.mgdl in 20..600 }
            .map { Fingerstick(it.time - clockError, it.mgdl!!, s.meterName) }
            .filter { it.timestamp in oldest..now + 10 * 60_000L }
        val added = app.readings.insertFingersticks(valid)
        val newestSeq = maxOf(last, records.maxOfOrNull { it.sequence } ?: last)
        app.settings.setMeterSynced(newestSeq, now)
        log("${records.size} misurazioni lette, $added nuove (ultima n. $newestSeq)")
        status(if (added > 0) "Ricevute $added misurazioni" else "Nessuna misurazione nuova")
    }

    /** Sends a RACP command and collects the records until the meter reports the end. */
    private suspend fun query(client: GattClient, command: ByteArray): List<GlucoseProfile.Measurement> {
        val out = mutableListOf<GlucoseProfile.Measurement>()
        if (!client.write(GlucoseProfile.RACP, command)) return out
        while (true) {
            val ev = withTimeoutOrNull(15_000) { client.events.receiveCatching().getOrNull() } ?: return out
            when (ev) {
                is GattClient.Event.Notification -> when (ev.uuid) {
                    GlucoseProfile.MEASUREMENT -> GlucoseProfile.parseMeasurement(ev.value)?.let(out::add)
                    GlucoseProfile.RACP -> if (GlucoseProfile.parseRacpResponse(ev.value) != null) return out
                }
                GattClient.Event.Disconnected -> return out
            }
        }
    }

    /**
     * Meter clocks drift and miss daylight saving changes. As xDrip+ does, the meter's clock is only
     * read, never changed: every record is shifted by the difference from the phone's clock.
     * @return meter time − phone time, in ms (0 when the meter has no readable clock)
     */
    private suspend fun clockError(client: GattClient): Long {
        if (client.characteristic(GlucoseProfile.CURRENT_TIME) == null) return 0
        val meterTime = client.read(GlucoseProfile.CURRENT_TIME)?.let { GlucoseProfile.parseCurrentTime(it) } ?: return 0
        val error = meterTime - System.currentTimeMillis()
        if (abs(error) >= 60_000) log("orologio del glucometro sbagliato di ${error / 1000} s: correggo gli orari delle misurazioni")
        return error
    }

    private companion object {
        const val FIRST_IMPORT_MS = 7 * 24 * 3_600_000L
    }
}
