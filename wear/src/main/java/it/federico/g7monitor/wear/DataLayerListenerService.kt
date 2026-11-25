package it.federico.g7monitor.wear

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import it.federico.g7monitor.wear.complication.Complications
import java.util.concurrent.TimeUnit
import it.federico.g7monitor.wear.tile.GlucoseTile

/** Woken by Play services when the phone sends a new reading; stores it and refreshes the complications. */
class DataLayerListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val item = events.lastOrNull { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == WatchContract.PATH }
            ?.dataItem ?: return
        val map = DataMapItem.fromDataItem(item).dataMap
        GlucoseStore.save(this, WatchGlucose.from(map))
        GlucoseStore.saveHistory(this, map)
        Complications.refresh(this)
        GlucoseTile.refresh(this)
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            // The phone says its version: tell the phone ours (for its "Orologio" page) and offer the update.
            WatchContract.PHONE_VERSION -> {
                publishVersion(this)
                WatchUpdater.notifyPhoneVersion(this, String(event.data, Charsets.UTF_8))
            }
            WatchContract.ALARM -> WatchAlarm.onMessage(this, event.data)
        }
    }

    companion object {
        /** This app's version, as a data item the phone reads to show whether the watch is up to date. */
        fun publishVersion(context: Context) {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val request = PutDataMapRequest.create(WatchContract.WATCH_INFO).apply {
                dataMap.putString("version", info.versionName ?: "")
                dataMap.putLong("code", info.longVersionCode)
            }.asPutDataRequest()
            Wearable.getDataClient(context).putDataItem(request)
        }

        /**
         * Reads the last item the phone already sent (e.g. right after installing the watch app,
         * before the next reading). Blocking: call off the main thread.
         */
        fun fetchLatest(context: Context) {
            val items = runCatching { Tasks.await(Wearable.getDataClient(context).dataItems, 10, TimeUnit.SECONDS) }.getOrNull() ?: return
            try {
                val map = items.filter { it.uri.path == WatchContract.PATH }
                    .map { DataMapItem.fromDataItem(it).dataMap }
                    .maxByOrNull { it.getLong(WatchContract.TIME) } ?: return
                val latest = WatchGlucose.from(map)
                if (latest.time > (GlucoseStore.load(context)?.time ?: 0L)) {
                    GlucoseStore.save(context, latest)
                    GlucoseStore.saveHistory(context, map)
                    Complications.refresh(context)
                    GlucoseTile.refresh(context)
                }
            } finally {
                items.release()
            }
        }
    }
}
