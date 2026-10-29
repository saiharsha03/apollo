package dev.apollo.actions

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import dev.apollo.approvals.ApolloDb
import dev.apollo.approvals.Approvals
import dev.apollo.approvals.Level
import dev.apollo.calypso.AndroidBiometric
import dev.apollo.hermes.Hermes
import dev.apollo.net.Actions
import dev.apollo.net.Server
import dev.apollo.senses.NoticeActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * What Athena's `phone_do` actions do, behind Owner's approvals table: free ones run at once, tap and fingerprint ones
 * wait for him on a notification (his tap, or his fingerprint, runs it), never ones are refused. The result goes back
 * to the server either way.
 */
object PhoneActions : Actions {
    private const val CHANNEL = "asks"
    private fun pending(c: Context) = c.getSharedPreferences("pending_actions", Context.MODE_PRIVATE)

    /** One ask waiting for him, as Chat and Home show it. */
    data class Pending(val id: String, val action: String, val args: JSONObject, val level: Level)

    /** Bumps whenever an ask arrives or is answered, so the screens redraw. */
    val changed = kotlinx.coroutines.flow.MutableStateFlow(0)

    fun waiting(c: Context): List<Pending> = pending(c).all.mapNotNull { (id, v) ->
        runCatching { JSONObject(v as String).let { Pending(id, it.getString("action"), it.optJSONObject("args") ?: JSONObject(), Level.of(it.optString("level")) ?: Level.TAP) } }.getOrNull()
    }.sortedBy { it.id }

    /**
     * Athena asked from the facts thread, where other people's text is (server tools.FACTS_ALLOWED): only what is free
     * runs there; anything that needs him is refused, and she asks him in chat instead. Null when it may go on.
     */
    fun factsRefusal(c: Context, action: String): String? =
        // A tile or shortcut stays on his phone and opens what it says (a call, a link), so never from there.
        // Nor a cancel: a facts turn has no asks of its own (only free actions run there), so it could only withdraw his.
        if (action == "tile" || action == "shortcut" || action == "cancel") "refused: $action came from the facts thread; ask him in a chat thread"
        else if (Approvals(ApolloDb.get(c)).levelFor(ActionRegistry.key(action)) == Level.FREE) null
        else "refused: $action needs Owner and came from the facts thread; ask him in a chat thread"

    override fun run(c: Context, id: String, action: String, args: JSONObject): Pair<Boolean, String> =
        // Withdrawing her own ask only takes something away, so it never needs him.
        if (action == "cancel") withdraw(c, args.optString("id"))
        else when (val level = Approvals(ApolloDb.get(c)).levelFor(ActionRegistry.key(action))) {
            Level.NEVER -> false to "refused: $action is on Owner's never list"
            Level.FREE -> perform(c, action, args)
            else -> {
                val asking = {
                    resolve(c, action, args)
                    pending(c).edit().putString(id, JSONObject().put("action", action).put("args", args).put("level", level.wire).toString()).apply()
                    ask(c, id, action, args, level)
                    changed.value++
                }
                // A describer looks names up online (a playlist's songs: a request each), so its ask is made off the sync,
                // which would otherwise hold every other item for minutes.
                if (ActionRegistry.describers.containsKey(action)) CoroutineScope(Dispatchers.IO).launch { asking() } else asking()
                false to "waiting for Owner (${level.word.lowercase()})"
            }
        }

    /** What an open_app / open_settings ask really opens, put on the ask so the card shows it (not only her words). */
    private fun resolve(c: Context, action: String, args: JSONObject) {
        ActionRegistry.describers[action]?.let { d -> runCatching { d(c, args) } }
        when (action) {
            "open_app" -> args.put("opens", Hermes.app(c, target(args))?.first ?: "no app by that name")
            "open_settings" -> args.put("opens", if (Hermes.settings(target(args)) != null) "${target(args).removeSuffix(" settings")} settings" else "no such settings page")
        }
    }

    private fun target(args: JSONObject) = args.optString("app").ifEmpty { args.optString("label") }.ifEmpty { args.optString("package") }.ifEmpty { args.optString("page") }.trim()

    /** The actions [perform] does itself (each its own approval row). */
    val BUILT_IN = listOf("reply", "open_url", "open_app", "open_settings", "timer", "alarm", "volume", "mute", "pause_media")

    /** Athena's `cancel`: the ask leaves Chat, Home and the notification shade, and its own result says it was withdrawn. */
    fun withdraw(c: Context, id: String): Pair<Boolean, String> {
        if (pending(c).getString(id, null) == null) return false to "no ask $id is waiting"
        pending(c).edit().remove(id).apply()
        c.getSystemService(NotificationManager::class.java)?.cancel(id.hashCode())
        changed.value++
        if (dev.apollo.net.Outbox.fromServer(id)) runCatching { Server(c).actionResult(id, false, "withdrawn by Athena") }
        return true to "ask $id withdrawn"
    }

    /** The action itself. Only what the phone can really do; anything else says so. */
    fun perform(c: Context, action: String, args: JSONObject): Pair<Boolean, String> {
        val audio = c.getSystemService(AudioManager::class.java)
        return when (action) {
            "reply" -> NoticeActions.fire(c, args.optString("key"), args.optString("label", "Reply"), args.optString("text")).let { it to if (it) "replied" else "that notification is gone" }
            "open_url" -> start(c, Intent(Intent.ACTION_VIEW, Uri.parse(args.optString("url"))), "opened")
            // By label (closest launcher match) or package; a settings page by name (Hermes's pages).
            "open_app" -> Hermes.app(c, target(args))?.let { (label, i) -> start(c, i, "opened $label") } ?: (false to "no app called ${target(args)}")
            "open_settings" -> Hermes.settings(target(args))?.let { start(c, it, "opened ${target(args).removeSuffix(" settings")} settings") }
                ?: (false to "no settings page called ${target(args)}")
            "timer" -> start(c, Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, args.optInt("seconds"))
                .putExtra(AlarmClock.EXTRA_MESSAGE, args.optString("label", "Timer")).putExtra(AlarmClock.EXTRA_SKIP_UI, true), "timer set")
            "alarm" -> start(c, Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, args.optInt("hour")).putExtra(AlarmClock.EXTRA_MINUTES, args.optInt("minute"))
                .putExtra(AlarmClock.EXTRA_MESSAGE, args.optString("label", "Athena")).putExtra(AlarmClock.EXTRA_SKIP_UI, true), "alarm set")
            "volume" -> { audio.setStreamVolume(AudioManager.STREAM_MUSIC, (audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * args.optDouble("level", 0.5)).toInt(), 0); true to "volume set" }
            "mute" -> { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0); true to "muted" }
            "pause_media" -> {
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE)); true to "paused"
            }
            else -> ActionRegistry.handlers[action]?.let { h -> runCatching { h(c, args) }.getOrElse { false to "failed: ${it.javaClass.simpleName}" } }
                ?: (false to "not supported on the phone yet: $action")
        }
    }

    private fun start(c: Context, i: Intent, done: String): Pair<Boolean, String> =
        runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true to done }.getOrElse { false to "couldn't: ${it.javaClass.simpleName}" }

    private fun ask(c: Context, id: String, action: String, args: JSONObject, level: Level) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Athena asks", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(c, id.hashCode(), Intent(c, ApproveActivity::class.java).putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // Same labelled targets as the in-app card, so the notification never shows only her free text.
        val w = dev.apollo.ui.Asks.words(Pending(id, action, args, level), if (action == "reply") NoticeActions.sender(args.optString("key")) else null)
        val body = (w.fields + w.notes).joinToString("\n") { "${it.label}: ${it.value}" }
        nm.notify(id.hashCode(), NotificationCompat.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(w.title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .addAction(0, if (level == Level.FINGERPRINT) "Approve with fingerprint" else "Yes", open).setAutoCancel(true).build())
    }

    /** His answer: runs the pending action (after the fingerprint when it needs one) and reports it. */
    fun answer(a: Activity, id: String, yes: Boolean, done: () -> Unit) {
        // The first answer claims the ask and takes it off the card and the shade at once: a second tap, or the
        // notification and the card together, can't run it twice while the first is still running.
        if (!dev.apollo.approvals.AskClaims.claim(id)) return done()
        val raw = pending(a).getString(id, null) ?: return done()
        val p = JSONObject(raw)
        pending(a).edit().remove(id).commit()
        a.getSystemService(NotificationManager::class.java)?.cancel(id.hashCode())
        changed.value++
        fun finish(ok: Boolean, detail: String) {
            // Hermes's own asks (hermes-<ms>) never came from the server: nothing to report. Server ones go in Sync's
            // durable queue, so an outcome reached offline is still reported.
            if (dev.apollo.net.Outbox.fromServer(id)) dev.apollo.net.Sync.finalResult(a, id, ok, detail)
            done()
        }
        if (!yes) return finish(false, "Owner said no")
        // His rule as it is now, not as it was when the ask was queued: tightened since, the stricter one holds (scan F17).
        val now = Approvals(ApolloDb.get(a)).levelFor(ActionRegistry.key(p.getString("action")))
        val need = dev.apollo.approvals.Rules.atAnswer(Level.of(p.optString("level")) ?: Level.TAP, now)
            ?: return finish(false, "refused: ${p.getString("action")} is on Owner's never list now")
        // Off the main thread: handlers block (Spotify waits for its connection, Google Home for the device); the
        // answer lands back on main.
        val run = {
            CoroutineScope(Dispatchers.IO).launch {
                // Once more right before it runs: a rule tightened during the fingerprint holds.
                val again = Approvals(ApolloDb.get(a)).levelFor(ActionRegistry.key(p.getString("action")))
                val (ok, d) = if (!dev.apollo.approvals.Rules.stillCovers(need, Level.of(p.optString("level")) ?: Level.TAP, again))
                    false to "refused: Owner's rule for ${p.getString("action")} changed while he answered; ask again"
                else runCatching { perform(a, p.getString("action"), p.optJSONObject("args") ?: JSONObject()) }.getOrElse { false to "failed: ${it.javaClass.simpleName}" }
                withContext(Dispatchers.Main) { finish(ok, d) }
            }
            Unit
        }
        if (need == Level.FINGERPRINT) AndroidBiometric(a).authenticate("Approve ${p.getString("action")}", "Athena asked for this", null, { run() }, { finish(false, "fingerprint failed: $it") })
        else run()
    }
}

/**
 * Phone actions that live with their feature (Google Home's lights, Spotify, calls...): each feature registers its own
 * handlers at app start, so features don't edit this file. Approvals still gate every one of them in [PhoneActions.run].
 */
object ActionRegistry {
    val handlers = java.util.concurrent.ConcurrentHashMap<String, (Context, JSONObject) -> Pair<Boolean, String>>()
    /** Before an ask is shown: the phone's own look-up of what the card names (a playlist's and songs' names), into the args. */
    val describers = java.util.concurrent.ConcurrentHashMap<String, (Context, JSONObject) -> Unit>()
    /** A handler's approval row when it isn't its own name: an alias shares its handler's row (scan F8). */
    val keys = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The one approval row an action is checked, undone and made stricter under. */
    fun key(action: String): String = dev.apollo.approvals.Rules.key(keys, action)

    /** Every action the phone can run, by its approval row, for the Approvals screen. */
    fun all(): Set<String> = (handlers.keys + PhoneActions.BUILT_IN).map(::key).toSortedSet()
}

/** The screen behind an ask's button: Yes or No (a fingerprint first when the action needs one). */
class ApproveActivity : androidx.activity.ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("id") ?: return finish()
        PhoneActions.answer(this, id, true) { finish() }
    }
}
