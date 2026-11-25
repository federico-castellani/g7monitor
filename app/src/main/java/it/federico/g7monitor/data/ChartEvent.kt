package it.federico.g7monitor.data

import androidx.annotation.StringRes
import it.federico.g7monitor.R
import it.federico.g7monitor.str
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID

/**
 * Kind of note on the chart. [wire] is the name the server and the other phones use; [unit] is
 * the symbol after the value (none for a free-text note).
 */
enum class EventType(val wire: String, @param:StringRes private val nameRes: Int, val unit: String?) {
    Insulin("insulin", R.string.event_insulin, "U"),
    Carbs("carbs", R.string.event_carbs, "g"),
    Exercise("exercise", R.string.event_exercise, "min"),
    Note("note", R.string.event_note, null);

    val label: String get() = str(nameRes)

    companion object {
        fun of(wire: String): EventType? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * A note on the chart: insulin, carbs, exercise or free text, added on any phone and shared
 * through the server. A deletion is kept as a change ([deleted]) so it reaches the other phones.
 * @param updated when it was last changed (ms): the newest version wins
 * @param seq the server's order of changes (0 for a change made here and not yet sent)
 */
data class ChartEvent(
    val id: String,
    val timestamp: Long,
    val type: EventType,
    /** Units of insulin, grams of carbs, minutes of exercise; null for a note. */
    val value: Double?,
    val note: String?,
    val deleted: Boolean = false,
    val updated: Long = System.currentTimeMillis(),
    val seq: Long = 0,
) {
    /** "4 U", "45 g", "30 min"; the kind ("Note") for one without a number. */
    val label: String
        get() = if (value == null || type.unit == null) type.label else "${format(value)} ${type.unit}"

    companion object {
        fun new(timestamp: Long, type: EventType, value: Double?, note: String?) =
            ChartEvent(UUID.randomUUID().toString(), timestamp, type, value, note?.trim()?.takeIf { it.isNotEmpty() })

        fun format(value: Double): String =
            NumberFormat.getNumberInstance(Locale.getDefault()).apply { maximumFractionDigits = 1 }.format(value)
    }
}
