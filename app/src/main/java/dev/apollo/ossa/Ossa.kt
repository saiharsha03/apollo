package dev.apollo.ossa

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import dev.apollo.R
import dev.apollo.net.ChatMessage
import dev.apollo.net.LocalDb
import dev.apollo.net.Server
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The call on screen: ringing, then live. */
data class CallUi(val ask: CallAsk, val live: Boolean)

/**
 * Ossa: Athena rings him like a real incoming call (Owner, 2026-10-21; rebuilt simply 2026-10-26). Apollo's own
 * self-managed line in Telecom, so a connected car takes the call over Bluetooth and its wheel answers and hangs up.
 * Answered: a talk ([CallTalk]): her text is spoken, his reply goes by voice to her chat, her answer is spoken back,
 * until either hangs up. Declined, missed or sent as a message: her text becomes a notification and a chat line.
 */
object Ossa {
    private const val TAG = "Apollo.Ossa"
    const val CH_RING = "ossa_ring"
    const val CH_LIVE = "ossa_live"
    const val CH_MSG = "ossa_msg"
    const val RING_ID = 4101
    const val LIVE_ID = 4102
    const val EXTRA_ID = "apollo.ossa.id"
    /** Where calls that weren't talked through land in the phone's chat store (a local thread). */
    const val THREAD = "calls"

    private val main = Handler(Looper.getMainLooper())
    private val _ui = MutableStateFlow<CallUi?>(null)
    val ui: StateFlow<CallUi?> = _ui.asStateFlow()
    @Volatile var connection: OssaConnection? = null
    /** Where the call's sound is now (Telecom's route), for the failure log. */
    @Volatile var route: Route = Route.OTHER
    /** The phone's text-to-speech engine that speaks for her, for the failure log. */
    @Volatile var voice: String = ""

    fun handle(c: Context) = PhoneAccountHandle(ComponentName(c, OssaConnectionService::class.java), "apollo")
    private fun telecom(c: Context) = c.getSystemService(TelecomManager::class.java)
    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)

    /** At app start: Apollo's line in Telecom, and Zeus's old one (its service is gone) taken out. */
    fun register(c: Context) {
        val t = telecom(c) ?: return
        runCatching { t.unregisterPhoneAccount(PhoneAccountHandle(ComponentName(c.packageName, "dev.phonerecall.ossa.OssaConnectionService"), "zeus")) }
        runCatching {
            t.registerPhoneAccount(PhoneAccount.builder(handle(c), "Apollo").setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .setShortDescription("Athena calls you").build())
        }.onFailure { Log.w(TAG, "phone account: ${it.javaClass.simpleName}") }
        channels(c)
    }

    fun channels(c: Context) {
        val m = nm(c)
        m.createNotificationChannel(NotificationChannel(CH_RING, "Athena calling", NotificationManager.IMPORTANCE_HIGH))
        m.createNotificationChannel(NotificationChannel(CH_LIVE, "Call with Athena", NotificationManager.IMPORTANCE_LOW))
        m.createNotificationChannel(NotificationChannel(CH_MSG, "Athena's calls as messages", NotificationManager.IMPORTANCE_HIGH))
    }

    /**
     * Outbox `call` (her call_owner, now, critical or at after_minutes): his call_owner row first. Free rings; tap or
     * fingerprint comes as a message instead (a call can't wait for a tap); never is refused. (ok, what happened).
     */
    fun fromAgent(c: Context, ask: CallAsk): Pair<Boolean, String> =
        when (OssaRules.gate(dev.apollo.approvals.Approvals(dev.apollo.approvals.ApolloDb.get(c)).levelFor(OssaRules.KEY))) {
            CallGate.RING -> true to ring(c, ask)
            CallGate.MESSAGE -> { asMessage(c.applicationContext, ask, Ending.AS_MESSAGE); false to "not rung: his calls need his tap, so it came as a message" }
            CallGate.REFUSE -> false to "refused: call_owner is on Owner's never list"
        }

    /** Ring now (or at after_minutes). Another call on, or Telecom refusing, makes it a message instead. Returns what happened. */
    fun ring(c: Context, ask: CallAsk): String {
        val app = c.applicationContext
        if (ask.afterMinutes > 0) {
            dev.apollo.actions.Later.put(app, "call-${ask.id}", "call", System.currentTimeMillis() + ask.afterMinutes * 60_000L, ask.json())
            return "rings in ${ask.afterMinutes} min" + if (ask.unlessSeen) " unless he opens Apollo first" else ""
        }
        synchronized(this) {
            if (_ui.value != null) { asMessage(app, ask, Ending.AS_MESSAGE); return "another call is on; sent as a message" }
            _ui.value = CallUi(ask, live = false)
        }
        return try {
            register(app)
            val extras = Bundle().apply {
                putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.fromParts("apollo", "athena", null))
                putBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, Bundle().apply { putString(EXTRA_ID, ask.id) })
            }
            if (ask.critical) Through.open(app)
            telecom(app)!!.addNewIncomingCall(handle(app), extras)
            main.postDelayed({ end(app, Ending.MISSED) }, ask.id, OssaRules.RING_MS)
            "ringing"
        } catch (e: Exception) {
            _ui.value = null
            Through.close(app)
            Log.w(TAG, "ring refused: ${e.javaClass.simpleName}")
            failed(app, "ring", e.javaClass.simpleName)
            asMessage(app, ask, Ending.AS_MESSAGE)
            "Telecom refused the call; sent as a message"
        }
    }

    fun person(c: Context): Person = Person.Builder().setName("Athena")
        .setIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(c, R.drawable.ic_launcher_sun)).setImportant(true).build()

    /** Telecom asks for the ringing UI: a CallStyle notification with the full-screen call screen. */
    fun showRinging(c: Context) {
        val app = c.applicationContext
        val ask = _ui.value?.ask ?: return
        channels(app)
        val full = PendingIntent.getActivity(app, 1, OssaCallActivity.intent(app, answer = false), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val answer = PendingIntent.getActivity(app, 2, OssaCallActivity.intent(app, answer = true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(app, CH_RING).setSmallIcon(R.drawable.ic_launcher_sun)
            .setContentTitle("Athena").setContentText(ask.reason)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person(app), OssaReceiver.pending(app, OssaReceiver.DECLINE), answer))
            .addAction(0, "Send as message", OssaReceiver.pending(app, OssaReceiver.MESSAGE))
            // Calling him back later is his say: on a locked phone the action asks for the unlock first (scan F15).
            .addAction(NotificationCompat.Action.Builder(0, "In 15 min", OssaReceiver.pending(app, OssaReceiver.LATER)).setAuthenticationRequired(true).build())
            .setFullScreenIntent(full, true).setContentIntent(full)
            .setCategory(NotificationCompat.CATEGORY_CALL).setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true).setTimeoutAfter(OssaRules.RING_MS)
            // Lock screen: who is calling; why shows once he unlocks.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(app, CH_RING).setSmallIcon(R.drawable.ic_launcher_sun).setContentTitle("Athena is calling").build())
            .build().apply { flags = flags or Notification.FLAG_INSISTENT }
        runCatching { nm(app).notify(RING_ID, n) }
    }

    /** He answered: from the screen, the notification, or the car (Connection.onAnswer). */
    fun answer(c: Context) {
        val app = c.applicationContext
        val cur = _ui.value ?: return
        if (cur.live) return
        main.removeCallbacksAndMessages(cur.ask.id)
        connection?.setActive()
        nm(app).cancel(RING_ID)
        runCatching { ContextCompat.startForegroundService(app, Intent(app, OssaCallService::class.java)) }
        _ui.value = cur.copy(live = true)
        app.startActivity(OssaCallActivity.intent(app, answer = false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** The call's chat thread with her (call id to thread id): every turn of one call goes to the same thread. */
    @Volatile private var talkThread: Pair<String, String>? = null

    /**
     * His words on the call into its chat thread for Athena; blocking: her reply, or null when none came within
     * [CallTalk.ANSWER_MS], it couldn't be sent, or the call ended.
     */
    fun ask(c: Context, text: String): String? {
        val ask = _ui.value?.ask ?: return null
        if (text.isBlank()) return null
        val server = Server(c.applicationContext)
        val thread = talkThread?.takeIf { it.first == ask.id }?.second
        val (th, id) = runCatching { server.chat(thread, "Call: ${ask.reason}".take(60), CallTalk.line(ask, text, thread == null), if (thread == null) "athena" else null, source = "voice") }
            .getOrElse { Log.w(TAG, "reply not sent: ${it.javaClass.simpleName}"); failed(c, "reply", it.javaClass.simpleName); return null }
        talkThread = ask.id to th
        var since = id
        val until = System.currentTimeMillis() + CallTalk.ANSWER_MS
        while (System.currentTimeMillis() < until && _ui.value?.ask?.id == ask.id) {
            Thread.sleep(1_500L)
            val got = runCatching { server.thread(th, since).let { a -> (0 until a.length()).map { dev.apollo.chat.messageOf(a.getJSONObject(it), th) } } }.getOrNull() ?: continue
            got.lastOrNull()?.let { since = it.id }
            val hers = got.filter { it.from == "athena" && !it.deleted }
            if (hers.isNotEmpty()) return hers.joinToString(" ") { it.text }
        }
        return null
    }

    /**
     * A failed step of a call into Clio (item 12): only a sanitized code (an exception's class name or Android's error
     * number), never her text or his words, with the call id, the phone model, the voice engine and the audio route.
     */
    fun failed(c: Context, step: String, code: String, callId: String? = _ui.value?.ask?.id) {
        dev.apollo.clio.Clio.log(c, "ossa", "call failed", org.json.JSONObject().put("step", step).put("code", OssaRules.code(code))
            .put("call", callId ?: "").put("model", android.os.Build.MODEL).put("voice", voice).put("speaker", route.name.lowercase()))
    }

    /** The call is over, however it ended. A call that wasn't talked through becomes a message. */
    fun end(c: Context, how: Ending) {
        val app = c.applicationContext
        val cur = synchronized(this) { _ui.value?.also { _ui.value = null } } ?: return
        main.removeCallbacksAndMessages(cur.ask.id)
        val ended = if (how == Ending.TALKED && !cur.live) Ending.DECLINED else how
        connection?.let { conn ->
            conn.setDisconnected(android.telecom.DisconnectCause(when (ended) {
                Ending.TALKED -> android.telecom.DisconnectCause.LOCAL
                Ending.MISSED -> android.telecom.DisconnectCause.MISSED
                else -> android.telecom.DisconnectCause.REJECTED
            }))
            conn.destroy()
        }
        connection = null
        nm(app).cancel(RING_ID)
        app.stopService(Intent(app, OssaCallService::class.java))
        Through.close(app)
        if (ended.asMessage) asMessage(app, cur.ask, ended)
    }

    /** Call me back in [minutes]: this call ends quietly and rings again then (Athena hears it as call_later). */
    fun later(c: Context, minutes: Int) {
        val app = c.applicationContext
        val ask = _ui.value?.ask ?: return
        end(app, Ending.LATER)
        dev.apollo.actions.Later.put(app, "call-${ask.id}-again", "call", System.currentTimeMillis() + minutes * 60_000L, ask.copy(unlessSeen = false).json().put("by_owner", true))
        dev.apollo.net.Facts.post(app, "call_later", org.json.JSONObject().put("call", ask.id).put("reason", ask.reason).put("minutes", minutes).put("by", "owner"))
    }

    /**
     * A call held by [dev.apollo.actions.Later] came due: it rings, unless unless_seen and he opened Apollo since. Hers
     * goes through his call_owner row again as it is now (tightened since she filed it, it comes as a message or not at
     * all); his own "call me back" just rings.
     */
    fun dueLater(c: Context, data: org.json.JSONObject) {
        val ask = CallAsk.of(data.optString("id"), data.put("after_minutes", 0)) ?: return
        if (!OssaRules.ringLater(ask, Seen.at(c))) {
            dev.apollo.net.Facts.post(c, "call_later", org.json.JSONObject().put("call", ask.id).put("reason", ask.reason).put("rung", false).put("why", "he opened Apollo"))
            return
        }
        val his = data.optBoolean("by_owner")
        android.os.Handler(Looper.getMainLooper()).post { if (his) ring(c, ask) else fromAgent(c, ask) }
    }

    /** Her words as a notification and a line in the phone's "Athena's calls" thread. */
    fun asMessage(c: Context, ask: CallAsk, how: Ending) {
        val line = OssaRules.messageLine(ask, how)
        runCatching { LocalDb.get(c).putMessage(ChatMessage("call-${ask.id}", THREAD, "athena", line, System.currentTimeMillis()), "Athena's calls", kind = "calls") }
        channels(c)
        val open = c.packageManager.getLaunchIntentForPackage(c.packageName)?.let { PendingIntent.getActivity(c, 3, it, PendingIntent.FLAG_IMMUTABLE) }
        runCatching {
            nm(c).notify("ossa", ask.id.hashCode(), NotificationCompat.Builder(c, CH_MSG).setSmallIcon(R.drawable.ic_launcher_sun)
                .setContentTitle("Athena: ${ask.reason}").setContentText(ask.text).setStyle(NotificationCompat.BigTextStyle().bigText(ask.text))
                .setContentIntent(open).setAutoCancel(true).build())
        }
    }
}

/** When he last opened Apollo (MainActivity coming to the front), for unless_seen. */
object Seen {
    private fun prefs(c: Context) = c.getSharedPreferences("ossa_seen", Context.MODE_PRIVATE)
    fun mark(c: Context) = prefs(c).edit().putLong("at", System.currentTimeMillis()).apply()
    fun at(c: Context): Long = prefs(c).getLong("at", 0L)
}

/**
 * A critical call rings through Do Not Disturb: for the ring, the filter opens (when he allowed Apollo to change it),
 * and goes back to what it was when the call ends.
 */
object Through {
    @Volatile private var before: Int? = null

    fun open(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted || nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) return
        before = nm.currentInterruptionFilter
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
    }

    fun close(c: Context) {
        val b = before ?: return
        before = null
        runCatching { c.getSystemService(NotificationManager::class.java).setInterruptionFilter(b) }
    }
}
