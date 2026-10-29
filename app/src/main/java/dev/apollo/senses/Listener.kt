package dev.apollo.senses

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.apollo.approvals.ApolloDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The notification sense. Reads only: each kept notification becomes a `notification` fact (full text, Owner
 * 2026-10-26), people's messages at once. It never cancels or answers anything by itself; the app's own buttons are kept
 * in memory ([NoticeActions]) so a reply Athena drafts can be sent after his approval. While bound it also runs the
 * usage and sleep look once a minute ([UsageWatch]), and the small looks that ride along: battery and alarm
 * ([DeviceWatch]), his calendar ([CalendarSense], every 15 min), where he is ([Places]), his calls ([CallSense]) and
 * Dawn's setup rows ([Setup], every 15 min). Focus comes from the listener's own callback; what plays ([MediaSense]) and
 * device events ([DeviceEvents]) follow while it is bound.
 */
class Listener : NotificationListenerService() {
    private var scope: CoroutineScope? = null
    private var loop: Job? = null

    override fun onListenerConnected() {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
        val watch = UsageWatch(applicationContext)
        runCatching { DeviceWatch.focusChanged(applicationContext, currentInterruptionFilter) }
        Places.start(applicationContext)
        runCatching { MediaSense.start(applicationContext, android.content.ComponentName(this, Listener::class.java)) }
        runCatching { DeviceEvents.start(applicationContext) }
        var setupAt = 0L
        loop = s.launch {
            while (isActive) {
                runCatching { watch.look(this@Listener) }
                runCatching { DeviceWatch.look(applicationContext) }
                runCatching { CalendarSense.look(applicationContext) }
                runCatching { Birthdays.look(applicationContext) }
                runCatching { Places.look(applicationContext) }
                runCatching { CallSense.look(applicationContext) }
                if (System.currentTimeMillis() - setupAt >= 15 * 60_000L) { setupAt = System.currentTimeMillis(); runCatching { Setup.look(applicationContext) } }
                delay(60_000L)
            }
        }
    }

    override fun onListenerDisconnected() {
        scope?.cancel(); scope = null; loop = null
        runCatching { MediaSense.stop(applicationContext) }
        runCatching { DeviceEvents.stop(applicationContext) }
    }

    // A notification Apollo can't read must never take the app down: it is skipped and logged.
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching {
            val ex = sbn.notification?.extras ?: Bundle.EMPTY
            CallSense.notification(this, sbn.packageName, ex.getCharSequence(Notification.EXTRA_TITLE)?.toString(), ex.getCharSequence(Notification.EXTRA_TEXT)?.toString(), sbn.postTime)
        }
        runCatching { AlarmRing.posted(this, sbn.notification?.category, sbn.packageName, System.currentTimeMillis()) }
        runCatching { posted(sbn) }.onFailure { android.util.Log.w("Apollo", "notification skipped: ${it.javaClass.simpleName}") }
    }

    private fun posted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (sbn.isOngoing || (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val ex = n.extras ?: Bundle.EMPTY
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val conversation = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        val db = ApolloDb.get(this)
        val sealed = CalypsoChoices.read(this)
        val verdict = NoticeRules.check(sbn.packageName, title, text, sub, conversation, packageName, db.skipApps(), db.gmailKeep(), sealed.apps, sealed.people)
        if (verdict !is NoticeRules.Verdict.Keep) return
        val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        NoticeActions.remember(sbn.key, n.actions.orEmpty().toList(), title, app)
        Senses.sink.post("notification", JSONObject()
            .put("pkg", sbn.packageName).put("app", app).put("title", title).put("text", text).put("sub", sub ?: "")
            .put("conversation", conversation ?: "").put("at", sbn.postTime).put("key", sbn.key).put("people", verdict.urgent)
            .put("actions", JSONArray(n.actions.orEmpty().map { it.title?.toString().orEmpty() })), verdict.urgent)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NoticeActions.forget(sbn.key)
        runCatching { AlarmRing.removed(this, sbn.notification?.category, sbn.packageName, System.currentTimeMillis()) }
    }

    override fun onInterruptionFilterChanged(interruptionFilter: Int) { runCatching { DeviceWatch.focusChanged(applicationContext, interruptionFilter) } }
}

/**
 * The people and apps he sealed in Calypso: Apollo's own list (ApolloDb calypso_apps / calypso_people, edited on the
 * Calypso screen; Zeus's senses.db list was copied in once). Cached for a minute; [forget] after an edit.
 */
object CalypsoChoices {
    data class Sealed(val apps: Set<String>, val people: Set<String>)
    @Volatile private var cached: Pair<Long, Sealed>? = null

    fun read(c: Context): Sealed {
        cached?.let { (at, s) -> if (System.currentTimeMillis() - at < 60_000L) return s }
        val db = ApolloDb.get(c)
        val s = runCatching { Sealed(db.calypsoApps(), db.calypsoPeopleKeys()) }.getOrDefault(Sealed(emptySet(), emptySet()))
        cached = System.currentTimeMillis() to s
        return s
    }

    fun forget() { cached = null }
}

/** The app's own buttons on a live notification, by key, in memory only. [fire] runs one after his approval. */
object NoticeActions {
    private val live = java.util.concurrent.ConcurrentHashMap<String, List<Notification.Action>>()
    private val senders = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

    fun remember(key: String, actions: List<Notification.Action>, title: String = "", app: String = "") {
        if (actions.isEmpty()) return
        live[key] = actions
        senders[key] = title to app
    }
    fun forget(key: String) { live.remove(key); senders.remove(key) }

    /** Who a reply to notification [key] really goes to, from the phone's own copy of it (title, app); null when gone. */
    fun sender(key: String): Pair<String, String>? = senders[key]

    /** Presses the button labelled [label] on notification [key]; with [text] it fills the button's reply field. False when gone. */
    fun fire(c: Context, key: String, label: String, text: String? = null): Boolean {
        val a = live[key]?.firstOrNull { it.title?.toString().equals(label, ignoreCase = true) } ?: return false
        val intent = Intent()
        val input = a.remoteInputs?.firstOrNull()
        if (text != null && input != null) {
            RemoteInput.addResultsToIntent(arrayOf(input), intent, Bundle().apply { putCharSequence(input.resultKey, text) })
        }
        return runCatching { a.actionIntent.send(c, 0, intent); true }.getOrDefault(false)
    }
}
