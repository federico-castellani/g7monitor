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

/** Woken by Play services when the phone sends a new reading; stores it and refreshes the complications. */
class DataLayerListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val item = events.lastOrNull { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == WatchContract.PATH }
            ?.dataItem ?: return
        GlucoseStore.save(this, WatchGlucose.from(DataMapItem.fromDataItem(item).dataMap))
        Complications.refresh(this)
    }

    /** The phone says its version: tell the phone ours (for its "Orologio" page) and offer the update. */
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WatchContract.PHONE_VERSION) return
        publishVersion(this)
        WatchUpdater.notifyPhoneVersion(this, String(event.data, Charsets.UTF_8))
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
                val latest = items.filter { it.uri.path == WatchContract.PATH }
                    .map { WatchGlucose.from(DataMapItem.fromDataItem(it).dataMap) }
                    .maxByOrNull { it.time } ?: return
                if (latest.time > (GlucoseStore.load(context)?.time ?: 0L)) {
                    GlucoseStore.save(context, latest)
                    Complications.refresh(context)
                }
            } finally {
                items.release()
            }
        }
    }
}
