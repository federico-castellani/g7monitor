package it.federico.g7monitor.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory event log for the Diagnostics screen (last [MAX] entries), also mirrored to Logcat
 * under the tag "G7Collector".
 */
object DebugLog {
    data class Entry(val time: Long, val message: String)

    /** Live counters about the current scan. */
    data class ScanInfo(
        val active: Boolean = false,
        val startedAt: Long? = null,
        val filteredResults: Int = 0,
        val lastResultAt: Long? = null,
        val lastResult: String? = null,
    )

    private const val MAX = 400
    private const val TAG = "G7Collector"

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    val scan = MutableStateFlow(ScanInfo())

    fun log(message: String) {
        Log.d(TAG, message)
        val e = Entry(System.currentTimeMillis(), message)
        synchronized(this) {
            val list = _entries.value
            _entries.value = if (list.size >= MAX) list.drop(list.size - MAX + 1) + e else list + e
        }
    }

    fun clear() {
        _entries.value = emptyList()
    }

    fun asText(): String {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.ITALY)
        return _entries.value.joinToString("\n") { "${fmt.format(Date(it.time))}  ${it.message}" }
    }
}
