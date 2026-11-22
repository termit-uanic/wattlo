package app.wattlo

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Push messages about changed schedules (Firebase Cloud Messaging). The data service sends one data-only message per
 * changed queue to the topic "<region>_<queue>", for example "poltavska-oblast_1.1"; the message has no text, the app
 * just loads the schedule again and shows its own notifications. The device subscribes to the topics of its main queue
 * and of the extra queues (phone only). Without the Firebase settings file in the build, or without Google Play
 * services, nothing here does anything and the periodic refresh remains.
 */
object Push {
    /** The same name the service uses (collector/push.py). */
    fun topic(region: Region, queue: String) = "${region.slug}_$queue"

    fun wanted(c: Context): Set<String> {
        if (!Settings.pushEnabled(c)) return emptySet() // switched off: unsubscribes from everything
        val main = topic(Settings.region(c), Settings.queue(c))
        val extra = Settings.extraQueues(c).mapNotNull { id -> Extra.region(id)?.let { topic(it, Extra.queue(id)) } }
        return (listOf(main) + extra).toSet()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("push", Context.MODE_PRIVATE)

    /** The topics the server has confirmed. */
    private fun subscribed(c: Context): Set<String> = prefs(c).getStringSet("topics", emptySet()).orEmpty().toSet()

    @Synchronized private fun remember(c: Context, topic: String, on: Boolean) {
        prefs(c).edit().putStringSet("topics", if (on) subscribed(c) + topic else subscribed(c) - topic).apply()
    }

    /** Subscribes to what is wanted and unsubscribes from what is not; cheap when nothing differs. Never throws. */
    fun sync(c: Context) {
        val app = c.applicationContext
        try {
            if (FirebaseApp.getApps(app).isEmpty()) return // built without the Firebase settings
            val fm = FirebaseMessaging.getInstance()
            val want = wanted(app)
            val have = subscribed(app)
            (want - have).forEach { t -> fm.subscribeToTopic(t).addOnSuccessListener { remember(app, t, true) } }
            (have - want).forEach { t -> fm.unsubscribeFromTopic(t).addOnSuccessListener { remember(app, t, false) } }
        } catch (e: Exception) {
            Log.w("Push", "sync failed", e)
        }
    }

    /** A message arrived: refresh if it is about a queue we follow (a stale subscription may still deliver one). */
    fun onMessage(c: Context, region: String?, queue: String?) {
        val r = Region.entries.firstOrNull { it.slug == region } ?: return
        if (queue == null || topic(r, queue) !in wanted(c)) return
        Log.i("Push", "changed: ${topic(r, queue)}")
        Refresh.onPush(c)
    }
}

class PushService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        Push.onMessage(applicationContext, message.data["region"], message.data["queue"])
    }
}
