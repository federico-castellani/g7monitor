package it.federico.g7monitor.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

data class AppSettings(
    val pairingCode: String = "",
    val useMmol: Boolean = false,
    val lowMgdl: Int = 70,
    val highMgdl: Int = 180,
    val collectorEnabled: Boolean = false,
    val sensorMac: String? = null,
    val sessionStart: Long? = null,
    val certP1: String = "",
    val certP2: String = "",
    val certP3: String = "",
) {
    val hasCertificates get() = certP1.isNotBlank() && certP2.isNotBlank() && certP3.isNotBlank()
}

class SettingsRepository(private val context: Context) {
    private object K {
        val code = stringPreferencesKey("pairing_code")
        val mmol = booleanPreferencesKey("use_mmol")
        val low = intPreferencesKey("low")
        val high = intPreferencesKey("high")
        val enabled = booleanPreferencesKey("collector_enabled")
        val mac = stringPreferencesKey("sensor_mac")
        val session = longPreferencesKey("session_start")
        val p1 = stringPreferencesKey("keks_p1")
        val p2 = stringPreferencesKey("keks_p2")
        val p3 = stringPreferencesKey("keks_p3")
        fun key(mac: String) = stringPreferencesKey("key_$mac")
        fun blocked(mac: String) = longPreferencesKey("blocked_$mac")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    private fun Preferences.toSettings() = AppSettings(
        pairingCode = this[K.code] ?: "",
        useMmol = this[K.mmol] ?: false,
        lowMgdl = this[K.low] ?: 70,
        highMgdl = this[K.high] ?: 180,
        collectorEnabled = this[K.enabled] ?: false,
        sensorMac = this[K.mac],
        sessionStart = this[K.session],
        certP1 = this[K.p1] ?: "",
        certP2 = this[K.p2] ?: "",
        certP3 = this[K.p3] ?: "",
    )

    /** Changing the code means a new sensor: forget the old one. */
    suspend fun setPairingCode(code: String) = context.dataStore.edit {
        if (it[K.code] != code) {
            it[K.code] = code
            it.remove(K.mac)
            it.remove(K.session)
        }
    }

    suspend fun setUseMmol(v: Boolean) = context.dataStore.edit { it[K.mmol] = v }
    suspend fun setRange(low: Int, high: Int) = context.dataStore.edit { it[K.low] = low; it[K.high] = high }
    suspend fun setCollectorEnabled(v: Boolean) = context.dataStore.edit { it[K.enabled] = v }
    suspend fun setSensorMac(mac: String?) = context.dataStore.edit { if (mac == null) it.remove(K.mac) else it[K.mac] = mac }
    suspend fun setSessionStart(t: Long?) = context.dataStore.edit { if (t == null) it.remove(K.session) else it[K.session] = t }
    suspend fun setCertificates(p1: String, p2: String, p3: String) = context.dataStore.edit {
        it[K.p1] = p1.trim(); it[K.p2] = p2.trim(); it[K.p3] = p3.trim()
    }

    suspend fun authKey(mac: String): String? = context.dataStore.data.first()[K.key(mac)]
    suspend fun setAuthKey(mac: String, hex: String?) = context.dataStore.edit {
        if (hex == null) it.remove(K.key(mac)) else it[K.key(mac)] = hex
    }

    /** Devices that failed authentication are ignored for a while (old sensors nearby). */
    suspend fun isBlocked(mac: String): Boolean =
        (context.dataStore.data.first()[K.blocked(mac)] ?: 0L) > System.currentTimeMillis()

    suspend fun block(mac: String, ms: Long) = context.dataStore.edit {
        it[K.blocked(mac)] = System.currentTimeMillis() + ms
    }

    suspend fun clearBlocks() = context.dataStore.edit { prefs ->
        prefs.asMap().keys.filter { it.name.startsWith("blocked_") }.forEach { prefs.remove(it) }
    }

    suspend fun forgetSensor() = context.dataStore.edit { prefs ->
        prefs.remove(K.mac)
        prefs.remove(K.session)
        prefs.asMap().keys.filter { it.name.startsWith("key_") || it.name.startsWith("blocked_") }
            .forEach { prefs.remove(it) }
    }
}
