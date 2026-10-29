package dev.apollo.actions

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import dev.apollo.approvals.ApolloDb
import dev.apollo.approvals.Approvals
import dev.apollo.approvals.Level
import dev.apollo.net.Actions
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * PLAN, Approvals: a free action happens and tells him with Undo; Undo asks "once or always?", and always adds a rule
 * (that action asks first from then on). Wraps [PhoneActions] so neither it nor the features change: a handler that can
 * be undone stages its Undo with [stage]; the few built-ins (volume, mute, pause) are undone here.
 */
object Undo {
    private const val CHANNEL = "done"
    private val staged = ThreadLocal<((Context) -> Unit)?>()
    /** Undos for what ran, by action id. Memory only: after a restart it is too late to undo. */
    private val waiting = ConcurrentHashMap<String, Pair<String, (Context) -> Unit>>()

    /** From inside a handler: how to put back what it just did. */
    fun stage(undo: (Context) -> Unit) = staged.set(undo)

    fun around(inner: Actions) = Actions { c, id, action, args ->
        staged.set(null)
        val before = builtIn(c, action)
        val r = inner.run(c, id, action, args)
        val undo = staged.get() ?: before
        staged.set(null)
        // The action's own approval row (an alias's is its handler's), so "Always ask first" lands where run() looks.
        val key = ActionRegistry.key(action)
        if (r.first && Approvals(ApolloDb.get(c)).levelFor(key) == Level.FREE) told(c, id, key, r.second, undo)
        r
    }

    private fun media(c: Context, key: Int) {
        val a = c.getSystemService(AudioManager::class.java)
        a.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key)); a.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
    }

    private fun builtIn(c: Context, action: String): ((Context) -> Unit)? {
        val a = c.getSystemService(AudioManager::class.java) ?: return null
        val vol = a.getStreamVolume(AudioManager.STREAM_MUSIC)
        return when (action) {
            "volume" -> { ctx -> ctx.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0) }
            "mute" -> { ctx -> ctx.getSystemService(AudioManager::class.java).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0) }
            "pause_media" -> { ctx -> media(ctx, KeyEvent.KEYCODE_MEDIA_PLAY) }
            else -> null
        }
    }

    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java).also {
        it.createNotificationChannel(NotificationChannel(CHANNEL, "What Athena did", NotificationManager.IMPORTANCE_LOW))
    }

    private fun button(c: Context, id: String, what: String) = PendingIntent.getBroadcast(c, (id + what).hashCode(),
        Intent(c, UndoReceiver::class.java).setAction(what).putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** "Athena: volume set", with Undo when there is one. Quiet. */
    private fun told(c: Context, id: String, action: String, detail: String, undo: ((Context) -> Unit)?) {
        if (undo != null) waiting[id] = action to undo
        val b = NotificationCompat.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Athena: $detail").setContentText(action.replace('_', ' ')).setSilent(true).setAutoCancel(true)
            .setTimeoutAfter(6 * 3_600_000L)
        if (undo != null) b.addAction(0, "Undo", button(c, id, UndoReceiver.UNDO))
        runCatching { nm(c).notify("done", id.hashCode(), b.build()) }
    }

    /** His Undo: put it back, then ask whether that action should ask him first from now on. */
    fun undo(c: Context, id: String) {
        val w = waiting.remove(id)
        if (w == null) {
            runCatching { nm(c).notify("done", id.hashCode(), NotificationCompat.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("Too late to undo that").setTimeoutAfter(60_000L).build()) }
            return
        }
        val (action, u) = w
        runCatching { u(c) }
        asked[id] = action
        val b = NotificationCompat.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Undone: ${action.replace('_', ' ')}").setContentText("Just this once, or should Athena always ask first?")
            .addAction(0, "Just this once", button(c, id, UndoReceiver.ONCE)).addAction(0, "Always ask first", button(c, id, UndoReceiver.ALWAYS))
            .setSilent(true).setTimeoutAfter(3_600_000L)
        runCatching { nm(c).notify("done", id.hashCode(), b.build()) }
        dev.apollo.net.Facts.post(c, "undo", JSONObject().put("action", action).put("id", id))
    }

    private val asked = ConcurrentHashMap<String, String>()

    /** Always: a stricter row (free to tap), the same as Athena's own add_rule, so it never loosens anything. */
    fun answer(c: Context, id: String, always: Boolean) {
        val action = asked.remove(id)
        nm(c).cancel("done", id.hashCode())
        if (always && action != null) Approvals(ApolloDb.get(c)).addStricter(action, Level.TAP, "Owner")
    }
}

class UndoReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getStringExtra("id") ?: return
        when (i.action) {
            UNDO -> Undo.undo(c, id)
            ONCE -> Undo.answer(c, id, false)
            ALWAYS -> Undo.answer(c, id, true)
        }
    }

    companion object {
        const val UNDO = "dev.apollo.undo.UNDO"
        const val ONCE = "dev.apollo.undo.ONCE"
        const val ALWAYS = "dev.apollo.undo.ALWAYS"
    }
}
