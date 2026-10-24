package app.muxway.android.wear

import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

/** The phone's board arrives here, and so does the news that a phone came or went. */
class BoardListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        var changed = false
        for (e in events) {
            if (e.dataItem.uri.path != PATH) continue
            if (e.type == DataEvent.TYPE_DELETED) {
                BoardStore.save(this, null)
            } else {
                BoardStore.save(this, DataMapItem.fromDataItem(e.dataItem).dataMap.getString(KEY))
            }
            changed = true
        }
        if (changed) BoardStore.refresh(this)
    }

    override fun onCapabilityChanged(info: CapabilityInfo) {
        if (info.name != PHONE) return
        BoardStore.savePhone(this, info.nodes.isNotEmpty())
        BoardStore.refresh(this)
    }

    companion object {
        const val PATH = "/board"
        const val KEY = "board"
        const val PHONE = "muxway_phone"
    }
}
