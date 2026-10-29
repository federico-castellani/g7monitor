package it.federico.g7monitor.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import it.federico.g7monitor.G7App
import it.federico.g7monitor.MainActivity
import it.federico.g7monitor.R
import it.federico.g7monitor.ble.GlucoseNotification
import it.federico.g7monitor.ble.GlucoseNotificationData
import it.federico.g7monitor.ui.Glucose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Home screen widget: value coloured by range, trend, change, time and the chart of the last
 * 3 hours, sized to the widget. Updated by the app at every reading (see G7App); the system
 * refresh every 30 minutes only turns a value grey when no reading arrives any more.
 */
class GlucoseWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = refresh(context)

    private fun refresh(context: Context) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                update(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val STALE_MS = 15 * 60_000L

        /** Redraws every widget placed on the home screen (nothing to do when there is none). */
        suspend fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, GlucoseWidget::class.java))
            if (ids.isEmpty()) return
            val app = context.applicationContext as G7App
            val s = app.settings.current()
            val data = GlucoseNotification.load(app, "", s.useMmol, s.lowMgdl, s.highMgdl, maxAgeMs = 3 * 3_600_000L)
            for (id in ids) manager.updateAppWidget(id, views(context, manager, id, data))
        }

        private fun views(context: Context, manager: AppWidgetManager, id: Int, d: GlucoseNotificationData?): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_glucose)
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
            v.setOnClickPendingIntent(R.id.root, open)
            if (d == null) {
                v.setTextViewText(R.id.value, "––")
                v.setTextViewText(R.id.arrow, "")
                v.setTextViewText(R.id.delta, "Nessuna lettura")
                v.setTextViewText(R.id.time, "nelle ultime 3 ore")
                v.setImageViewBitmap(R.id.chart, null)
                return v
            }
            val stale = System.currentTimeMillis() - d.latest.timestamp > STALE_MS
            v.setTextViewText(R.id.value, Glucose.format(d.latest.mgdl, d.mmol))
            v.setTextColor(R.id.value, GlucoseNotification.valueColor(context, d, stale))
            v.setTextViewText(R.id.arrow, if (stale) "" else Glucose.arrow(d.latest.trend))
            val delta = d.previous?.takeIf { d.latest.timestamp - it.timestamp < 12 * 60_000L }?.let { d.latest.mgdl - it.mgdl }
            v.setTextViewText(R.id.delta, delta?.let { "${Glucose.formatDelta(it, d.mmol)} ${Glucose.unit(d.mmol)}" } ?: Glucose.unit(d.mmol))
            v.setTextViewText(R.id.time, (if (stale) "non aggiornata · " else "") + "alle ${GlucoseNotification.time(d.latest.timestamp)}")

            // Chart sized to the widget: its current size minus padding and the header row.
            val o = manager.getAppWidgetOptions(id)
            val widthDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 250).coerceIn(140, 600) - 28
            val heightDp = (o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110) - 28 - 52).coerceIn(30, 400)
            v.setImageViewBitmap(R.id.chart, GlucoseNotification.chartBitmap(context, d, widthDp, heightDp, detailed = heightDp >= 60))
            return v
        }
    }
}
