package it.federico.g7monitor.ui.home

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Vaccines
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import it.federico.g7monitor.R
import it.federico.g7monitor.data.ChartEvent
import it.federico.g7monitor.data.EventType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun EventType.icon(): ImageVector = when (this) {
    EventType.Insulin -> Icons.Outlined.Vaccines
    EventType.Carbs -> Icons.Outlined.Restaurant
    EventType.Exercise -> Icons.AutoMirrored.Outlined.DirectionsRun
    EventType.Note -> Icons.Outlined.EditNote
}

/** What the value field asks for, by kind. */
private fun EventType.valueLabel(): Int = when (this) {
    EventType.Insulin -> R.string.event_value_insulin
    EventType.Carbs -> R.string.event_value_carbs
    else -> R.string.event_value_exercise
}

/**
 * Adds a note at [at], or edits [initial]. Insulin, carbs and exercise need their number (the
 * text is optional), a note needs its text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventDialog(
    initial: ChartEvent?,
    at: Long,
    onSave: (ChartEvent) -> Unit,
    onDelete: (ChartEvent) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var type by remember { mutableStateOf(initial?.type ?: EventType.Insulin) }
    var value by remember { mutableStateOf(initial?.value?.let { ChartEvent.format(it) } ?: "") }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var time by remember { mutableLongStateOf(initial?.timestamp ?: at) }
    val number = value.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it <= 1000 }
    val valid = if (type == EventType.Note) note.isNotBlank() else number != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.event_new_title else R.string.event_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (t in EventType.entries) {
                        FilterChip(
                            selected = type == t, onClick = { type = t },
                            label = { Text(t.label) },
                            leadingIcon = { Icon(t.icon(), contentDescription = null) },
                        )
                    }
                }
                if (type != EventType.Note) {
                    OutlinedTextField(
                        value = value, onValueChange = { value = it.take(6) },
                        label = { Text(stringResource(type.valueLabel())) },
                        suffix = { type.unit?.let { Text(it) } },
                        singleLine = true, isError = value.isNotBlank() && number == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = note, onValueChange = { note = it.take(500) },
                    label = { Text(stringResource(if (type == EventType.Note) R.string.event_text_required else R.string.event_text)) },
                    minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        stringResource(R.string.event_time, timeLabel(time)),
                        Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = {
                        val cal = Calendar.getInstance().apply { timeInMillis = time }
                        TimePickerDialog(context, { _, h, m ->
                            cal.set(Calendar.HOUR_OF_DAY, h)
                            cal.set(Calendar.MINUTE, m)
                            time = cal.timeInMillis
                        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), DateFormat.is24HourFormat(context)).show()
                    }) { Text(stringResource(R.string.action_change)) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val v = if (type == EventType.Note) null else number
                val text = note.trim().takeIf { it.isNotEmpty() }
                onSave(
                    initial?.copy(timestamp = time, type = type, value = v, note = text, updated = System.currentTimeMillis())
                        ?: ChartEvent.new(time, type, v, text)
                )
            }) { Text(stringResource(R.string.event_save)) }
        },
        dismissButton = {
            Row {
                if (initial != null) {
                    TextButton(onClick = { onDelete(initial) }) {
                        Text(stringResource(R.string.event_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

/** "14:35", with the day when it is not today. */
private fun timeLabel(t: Long): String {
    val locale = Locale.getDefault()
    val today = SimpleDateFormat("yyyyMMdd", locale).let { it.format(Date(t)) == it.format(Date()) }
    val pattern = if (today) "HH:mm" else DateFormat.getBestDateTimePattern(locale, "EEEdMMMHHmm")
    return SimpleDateFormat(pattern, locale).format(Date(t))
}
