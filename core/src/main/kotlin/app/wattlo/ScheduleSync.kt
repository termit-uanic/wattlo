package app.wattlo

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

/**
 * The phone gives the watch the schedule it has just fetched (Wear OS Data Layer, path [PATH]), so that the watch
 * does not have to go to the internet itself while the phone is around: the phone looks every 30 minutes (and on every push), the watch
 * gets the news as soon as the phone has it, and its own periodic refresh stays quiet ([phoneDataIsFresh]).
 *
 * Only the main queue is sent (the extra queues are on the phone only). The watch takes the item when its region
 * and queue are the ones in it. If the phone stops delivering (it is off or far away, the system killed the app),
 * the data on the watch gets old, [PHONE_FRESH_MS] after the last delivery the watch's own refresh starts again,
 * and without a phone nothing changes at all.
 */
object ScheduleSync {
    const val PATH = "/schedule"
    private const val TAG = "ScheduleSync"

    /** The watch trusts what came from the phone this long; after it, it goes to the internet itself. */
    const val PHONE_FRESH_MS = 60 * 60_000L

    /** Even when nothing has changed the phone repeats the item this often, so the watch sees that it is current. */
    const val HEARTBEAT_MS = 25 * 60_000L

    /** The phone sends when the schedule differs from the last one sent, or when the last message is old. */
    fun shouldPush(sameAsLast: Boolean, lastPushAt: Long, now: Long): Boolean =
        !sameAsLast || now - lastPushAt >= HEARTBEAT_MS

    /** The watch takes an item only for its own region and queue, and never goes back in time. */
    fun shouldAccept(
        myRegion: String, myQueue: String, region: String?, queue: String?,
        myFetchedAt: Long, fetchedAt: Long, daysOk: Boolean,
    ): Boolean = daysOk && region == myRegion && queue == myQueue && fetchedAt >= myFetchedAt

    private fun state(c: Context) = c.getSharedPreferences("schedule_sync", Context.MODE_PRIVATE)

    /** Phone, after the main queue's schedule was fetched and saved: tell the watch (see [shouldPush]). */
    fun push(c: Context) {
        if (Settings.isWatch) return
        val days = Store.daysString(c)
        if (days.isEmpty()) return
        val st = state(c)
        val now = System.currentTimeMillis()
        val key = Settings.region(c).name + "|" + Settings.queue(c) + "|" + days
        if (!shouldPush(st.getString("lastKey", null) == key, st.getLong("lastAt", 0L), now)) return
        runCatching {
            val req = PutDataMapRequest.create(PATH).also {
                it.dataMap.putString("region", Settings.region(c).name)
                it.dataMap.putString("queue", Settings.queue(c))
                it.dataMap.putString("days", days)
                it.dataMap.putLong("fetchedAt", Store.fetchedAt(c))
                it.dataMap.putLong("updatedAt", Store.dataUpdatedAt(c))
                it.dataMap.putString("source", Settings.source(c).name)
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(c.applicationContext).putDataItem(req)
                .addOnSuccessListener { st.edit().putString("lastKey", key).putLong("lastAt", now).apply() }
                .addOnFailureListener { Log.w(TAG, "push failed: $it") }
        }.onFailure { Log.w(TAG, "push unavailable: $it") }
    }

    /** Watch: catch up with what the phone sent while this app was not running. Cheap, asynchronous. */
    fun pull(c: Context) {
        if (!Settings.isWatch) return
        runCatching {
            val uri = Uri.Builder().scheme("wear").path(PATH).build()
            Wearable.getDataClient(c.applicationContext).getDataItems(uri)
                .addOnSuccessListener { items ->
                    try {
                        items.map { DataMapItem.fromDataItem(it).dataMap }
                            .maxByOrNull { it.getLong("fetchedAt") }?.let { handle(c, it) }
                    } finally { items.release() }
                }
                .addOnFailureListener { Log.w(TAG, "pull failed: $it") }
        }.onFailure { Log.w(TAG, "pull unavailable: $it") }
    }

    /** Watch: an item from the phone. */
    internal fun handle(c: Context, m: DataMap) {
        if (!Settings.isWatch) return // the phone's own item comes back to its listener too
        val days = m.getString("days", "") ?: ""
        val ok = shouldAccept(
            Settings.region(c).name, Settings.queue(c), m.getString("region"), m.getString("queue"),
            Store.fetchedAt(c), m.getLong("fetchedAt"), Store.parseDays(days).isNotEmpty(),
        )
        if (!ok) return
        Log.i(TAG, "from the phone: fetchedAt=${m.getLong("fetchedAt")} source=${m.getString("source")}")
        Fetcher.acceptFromPhone(c.applicationContext, days, m.getLong("fetchedAt"))
        Store.setDataUpdatedAt(c, m.getLong("updatedAt", 0L)) // an older phone sends none: 0 = unknown
    }

    /** Watch: the data that came from the phone is recent, no reason to ask the internet. */
    fun phoneDataIsFresh(c: Context, now: Long = System.currentTimeMillis()): Boolean =
        isFresh(Settings.isWatch, Store.fromPhone(c), Store.fetchedAt(c), now)

    /** Only the watch, only for data that came from the phone, and only while it is younger than [PHONE_FRESH_MS]. */
    fun isFresh(isWatch: Boolean, fromPhone: Boolean, fetchedAt: Long, now: Long): Boolean =
        isWatch && fromPhone && now - fetchedAt < PHONE_FRESH_MS
}
