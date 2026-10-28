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

/** Where this phone gets the readings from. Each mode shows only its own settings. */
enum class DataSource {
    /** Bluetooth from the G7; optional upload to a server. */
    Sensor,

    /** Downloaded from a G7 Monitor server (follower). */
    Server,
}

data class AppSettings(
    /** null until the user picks one on the welcome screen. */
    val dataSource: DataSource? = null,
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
    /** G7 Monitor server (https base URL), account name and this phone's upload token. */
    val serverUrl: String = "",
    val serverUser: String = "",
    val serverToken: String = "",
    /** Minimum minutes between uploads; 5 = every reading. */
    val uploadIntervalMin: Int = 5,
    val uploadWifiOnly: Boolean = false,
    val lastUploadAt: Long? = null,
    val uploadError: String? = null,
    /** Follower mode: server to download from, account and read-only token. */
    val followUrl: String = "",
    val followUser: String = "",
    val followToken: String = "",
    val lastFollowAt: Long? = null,
    val followError: String? = null,
) {
    val hasCertificates get() = certP1.isNotBlank() && certP2.isNotBlank() && certP3.isNotBlank()
    val serverConnected get() = serverUrl.isNotBlank() && serverToken.isNotBlank()
    val followConnected get() = followUrl.isNotBlank() && followToken.isNotBlank()
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
        val serverUrl = stringPreferencesKey("server_url")
        val serverUser = stringPreferencesKey("server_user")
        val serverToken = stringPreferencesKey("server_token")
        val uploadInterval = intPreferencesKey("upload_interval_min")
        val wifiOnly = booleanPreferencesKey("upload_wifi_only")
        val lastUpload = longPreferencesKey("last_upload_at")
        val uploadError = stringPreferencesKey("upload_error")
        val source = stringPreferencesKey("data_source")
        val followUrl = stringPreferencesKey("follow_url")
        val followUser = stringPreferencesKey("follow_user")
        val followToken = stringPreferencesKey("follow_token")
        val lastFollow = longPreferencesKey("last_follow_at")
        val followError = stringPreferencesKey("follow_error")
        fun key(mac: String) = stringPreferencesKey("key_$mac")
        fun blocked(mac: String) = longPreferencesKey("blocked_$mac")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    private fun Preferences.toSettings() = AppSettings(
        // Installs from before the choice existed already use the sensor.
        dataSource = this[K.source]?.let { v -> DataSource.entries.firstOrNull { it.name == v } }
            ?: if (this[K.code] != null) DataSource.Sensor else null,
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
        serverUrl = this[K.serverUrl] ?: "",
        serverUser = this[K.serverUser] ?: "",
        serverToken = this[K.serverToken] ?: "",
        uploadIntervalMin = this[K.uploadInterval] ?: 5,
        uploadWifiOnly = this[K.wifiOnly] ?: false,
        lastUploadAt = this[K.lastUpload],
        uploadError = this[K.uploadError],
        followUrl = this[K.followUrl] ?: "",
        followUser = this[K.followUser] ?: "",
        followToken = this[K.followToken] ?: "",
        lastFollowAt = this[K.lastFollow],
        followError = this[K.followError],
    )

    suspend fun setDataSource(source: DataSource) = context.dataStore.edit { it[K.source] = source.name }

    suspend fun setFollow(url: String, user: String, token: String) = context.dataStore.edit {
        it[K.followUrl] = url
        it[K.followUser] = user
        it[K.followToken] = token
        it.remove(K.lastFollow)
        it.remove(K.followError)
    }

    /** Forgets the token (the address stays, to reconnect easily). */
    suspend fun disconnectFollow(error: String?) = context.dataStore.edit {
        it.remove(K.followToken)
        it.remove(K.lastFollow)
        if (error == null) it.remove(K.followError) else it[K.followError] = error
    }

    suspend fun setFollowResult(at: Long?, error: String?) = context.dataStore.edit {
        if (at != null) it[K.lastFollow] = at
        if (error == null) it.remove(K.followError) else it[K.followError] = error
    }

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

    suspend fun setServer(url: String, user: String, token: String) = context.dataStore.edit {
        it[K.serverUrl] = url
        it[K.serverUser] = user
        it[K.serverToken] = token
        it.remove(K.lastUpload)
        it.remove(K.uploadError)
    }

    /** Forgets the token (the address stays, to reconnect easily). */
    suspend fun disconnectServer(error: String?) = context.dataStore.edit {
        it.remove(K.serverToken)
        it.remove(K.lastUpload)
        if (error == null) it.remove(K.uploadError) else it[K.uploadError] = error
    }

    suspend fun setUploadInterval(minutes: Int) = context.dataStore.edit { it[K.uploadInterval] = minutes }
    suspend fun setUploadWifiOnly(v: Boolean) = context.dataStore.edit { it[K.wifiOnly] = v }

    /** Written only when something changes, to keep DataStore writes rare. */
    suspend fun setUploadResult(at: Long?, error: String?) = context.dataStore.edit {
        if (at != null) it[K.lastUpload] = at
        if (error == null) it.remove(K.uploadError) else it[K.uploadError] = error
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
