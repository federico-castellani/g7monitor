package it.federico.g7monitor.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.BloodGlucose
import it.federico.g7monitor.G7App
import it.federico.g7monitor.R
import it.federico.g7monitor.data.DataSource
import it.federico.g7monitor.data.DebugLog
import it.federico.g7monitor.data.Fingerstick
import it.federico.g7monitor.data.GlucoseReading
import it.federico.g7monitor.data.UploadTarget
import it.federico.g7monitor.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/**
 * Writes the glucose to Health Connect, where other apps (with the user's permission) can read
 * it: sensor readings as interstitial fluid, meter results as capillary blood. Sensor mode only
 * (a follower's readings are someone else's). Each record has an id of its own, so one written
 * twice is replaced, not doubled. Like the other uploads it runs right after a reading is stored.
 */
class HealthConnectWriter(private val app: G7App) {
    private val mutex = Mutex()
    private val client by lazy { HealthConnectClient.getOrCreate(app) }

    val permissions = setOf(HealthPermission.getWritePermission(BloodGlucoseRecord::class))

    /** SDK_AVAILABLE, SDK_UNAVAILABLE (not on this phone) or SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED. */
    fun status(): Int = HealthConnectClient.getSdkStatus(app)

    suspend fun hasPermission(): Boolean =
        status() == HealthConnectClient.SDK_AVAILABLE &&
            runCatching { client.permissionController.getGrantedPermissions().containsAll(permissions) }.getOrDefault(false)

    /** Turned on: the last 7 days are written too. */
    suspend fun enable() {
        app.settings.setHealthConnect(true)
        app.readings.resetHealthConnect(System.currentTimeMillis() - 7 * 24 * 3_600_000L)
        write()
    }

    suspend fun write() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val s = app.settings.current()
            if (!s.healthConnect || s.dataSource != DataSource.Sensor || !hasPermission()) return@withContext
            var written = 0
            try {
                while (true) {
                    val batch = app.readings.pendingUpload(BATCH, UploadTarget.HealthConnect)
                    if (batch.isEmpty()) break
                    client.insertRecords(batch.map(::record))
                    app.readings.markUploaded(batch, UploadTarget.HealthConnect)
                    written += batch.size
                    if (batch.size < BATCH) break
                }
                val sticks = app.readings.pendingFingersticks(BATCH, UploadTarget.HealthConnect)
                if (sticks.isNotEmpty()) {
                    client.insertRecords(sticks.map(::record))
                    app.readings.markFingersticksUploaded(sticks, UploadTarget.HealthConnect)
                    written += sticks.size
                }
                if (written > 0) DebugLog.log(str(R.string.hc_written, written))
            } catch (e: Exception) {
                // Permission taken back meanwhile, Health Connect busy or updating: next reading.
                DebugLog.log(str(R.string.hc_failed, e.javaClass.simpleName))
            }
        }
    }

    private fun record(r: GlucoseReading) = glucose(
        r.timestamp, r.mgdl, BloodGlucoseRecord.SPECIMEN_SOURCE_INTERSTITIAL_FLUID, "g7-${r.timestamp}", SENSOR,
    )

    private fun record(f: Fingerstick) = glucose(
        f.timestamp, f.mgdl, BloodGlucoseRecord.SPECIMEN_SOURCE_CAPILLARY_BLOOD, "meter-${f.timestamp}-${f.mgdl}",
        Device(type = Device.TYPE_UNKNOWN, manufacturer = null, model = f.meter),
    )

    private fun glucose(t: Long, mgdl: Int, source: Int, id: String, device: Device): BloodGlucoseRecord {
        val time = Instant.ofEpochMilli(t)
        return BloodGlucoseRecord(
            time = time,
            zoneOffset = ZoneId.systemDefault().rules.getOffset(time),
            metadata = Metadata.autoRecorded(device = device, clientRecordId = id, clientRecordVersion = 1),
            level = BloodGlucose.milligramsPerDeciliter(mgdl.toDouble()),
            specimenSource = source,
            mealType = MealType.MEAL_TYPE_UNKNOWN,
            relationToMeal = BloodGlucoseRecord.RELATION_TO_MEAL_UNKNOWN,
        )
    }

    private companion object {
        const val BATCH = 500
        val SENSOR = Device(type = Device.TYPE_UNKNOWN, manufacturer = "Dexcom", model = "G7")
    }
}
