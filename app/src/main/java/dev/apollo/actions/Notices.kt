package dev.apollo.actions

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.app.NotificationCompat
import dev.apollo.hermes.Commands
import dev.apollo.net.Facts
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** Where a tap goes, from the word Athena gave (notify's open, a tile, a shortcut). Pure parts tested. */
object Open {
    /** call:<who>[:video|:voice|:phone] to (who, way); null when it isn't a call. */
    fun call(open: String): Pair<String, String?>? {
        if (!open.startsWith("call:", ignoreCase = true)) return null
        val p = open.substring(5).split(':')
        val who = p[0].trim().takeIf { it.isNotEmpty() } ?: return null
        return who to CallWay.via(p.getOrNull(1))
    }

    /** What the tap says it does, in Apollo's words, never only hers. */
    fun words(open: String): String? {
        call(open)?.let { (who, via) -> return "Tap to call $who" + when (via) { CallWay.VOICE -> " (WhatsApp voice)"; CallWay.PHONE -> " (phone)"; CallWay.VIDEO -> " (WhatsApp video)"; else -> "" } }
        if (open.startsWith("https://") || open.startsWith("http://")) return "Opens " + Uri.parse(open).host.orEmpty().removePrefix("www.")
        if (dev.apollo.chat.ThreadLink.parse(open) != null) return "Tap to open the chat"
        return null
    }

    fun apollo(c: Context): Intent = c.packageManager.getLaunchIntentForPackage(c.packageName)!!

    /** The intent: Apollo, one of her mini apps, a link, a call he taps for, or an app on his phone by name. */
    fun intent(c: Context, open: String, allowCall: Boolean = true): Intent {
        val o = open.trim()
        call(o)?.let { (who, via) -> if (allowCall) return Intent(c, TapCallActivity::class.java).putExtra("who", who).putExtra("via", via) else return apollo(c) }
        // One of his threads: the open Apollo gets it (onNewIntent) instead of only coming to the front.
        dev.apollo.chat.ThreadLink.parse(o)?.let { return apollo(c).putExtra(dev.apollo.chat.ThreadLink.EXTRA, it)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP) }
        return when {
            o.isEmpty() || o.equals("chat", true) || o.equals("apollo", true) -> apollo(c)
            o.startsWith("app:") -> apollo(c).putExtra("open_app", o.removePrefix("app:").substringBefore('/'))
            o.startsWith("https://") || o.startsWith("http://") -> Intent(Intent.ACTION_VIEW, Uri.parse(o))
            else -> {
                val pm = c.packageManager
                val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).associate { it.loadLabel(pm).toString() to it.activityInfo.packageName }
                Commands.pick(o, apps.keys.toList())?.let { pm.getLaunchIntentForPackage(apps.getValue(it)) } ?: apollo(c)
            }
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/**
 * His tap on a "call X" notification or tile (her notify open=call:, a tile): his tap is the yes for a call_person row at
 * tap or free, so the call starts (by the number's country). A fingerprint row asks for it first; a never row calls no one.
 */
class TapCallActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val who = intent.getStringExtra("who") ?: return finish()
        val via = intent.getStringExtra("via")
        val go = { Thread { MoreActions.call(applicationContext, who, via) }.start(); Unit }
        when (dev.apollo.approvals.Approvals(dev.apollo.approvals.ApolloDb.get(this)).levelFor(ActionRegistry.key("call_person"))) {
            dev.apollo.approvals.Level.NEVER -> Unit
            // The usual fingerprint ask (its own notification and screen); this screen draws nothing and can't wait.
            dev.apollo.approvals.Level.FINGERPRINT -> PhoneActions.run(applicationContext, "tap-${System.currentTimeMillis()}", "call_person",
                org.json.JSONObject().put("name", who).put("via", via ?: "auto"))
            else -> go()
        }
        finish()
    }
}

/**
 * Athena's `notify` on the phone: now, at a time (held by [Later]), repeating, with a tap target, and her unseen check
 * (a notify_unseen fact if it is still in the shade unopened after her minutes). Her text is shown, never acted on.
 */
object Notices {
    private const val CHANNEL = "athena"
    private const val URGENT = "athena_urgent"     // priority high: a heads-up popup (the channel decides that, not the priority)
    private const val TAG = "athena"

    fun fromOutbox(c: Context, id: String, d: JSONObject) {
        d.optString("cancel").takeIf { it.isNotEmpty() }?.let { n ->
            Later.cancel(c, "n-$n")
            c.getSystemService(NotificationManager::class.java)?.cancel(TAG, n.hashCode())
            return
        }
        val at = d.optLong("at")
        if (at > System.currentTimeMillis() + 30_000L) return Later.put(c, "n-$id", "notify", at, d.put("id", id))
        show(c, id, d)
        again(c, id, d)
    }

    fun dueLater(c: Context, job: String, d: JSONObject) {
        val id = d.optString("id", job.removePrefix("n-"))
        show(c, id, d)
        again(c, id, d)
    }

    /** A repeating one: its next time, at the first one's time of day in its zone. */
    private fun again(c: Context, id: String, d: JSONObject) {
        val rep = d.optString("repeat").ifEmpty { return }
        val tz = d.optString("tz", "America/New_York")
        val hhmm = runCatching { Instant.ofEpochMilli(d.getLong("at")).atZone(ZoneId.of(tz)).toLocalTime().toString().take(5) }.getOrNull() ?: return
        Repeat.next(hhmm, tz, rep, System.currentTimeMillis() + 60_000L)?.let { Later.put(c, "n-$id", "notify", it, d.put("id", id)) }
    }

    private fun channel(c: Context): NotificationManager {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Athena", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(URGENT, "Athena: urgent", NotificationManager.IMPORTANCE_HIGH))
        return nm
    }

    fun show(c: Context, id: String, d: JSONObject) {
        val nm = channel(c)
        val open = d.optString("open")
        val title = d.optString("title")
        val line = listOfNotNull(Open.words(open), d.optString("body").ifEmpty { null }).joinToString("\n")
        val tap = PendingIntent.getActivity(c, id.hashCode(), Open.intent(c, open), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(c, if (d.optString("priority") == "high") URGENT else CHANNEL).setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle(title).setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            .setPriority(when (d.optString("priority")) { "high" -> NotificationCompat.PRIORITY_HIGH; "low" -> NotificationCompat.PRIORITY_LOW; else -> NotificationCompat.PRIORITY_DEFAULT })
            .setSilent(d.optString("priority") == "low").setContentIntent(tap).setAutoCancel(true).build()
        runCatching { nm.notify(TAG, id.hashCode(), n) }
        val min = d.optInt("check_after_min")
        if (min > 0) Later.put(c, "c-$id", "check", System.currentTimeMillis() + min * 60_000L, JSONObject().put("id", id).put("title", title).put("minutes", min))
    }

    /** Still in the shade means he hasn't opened it (a tap or a swipe takes it away). */
    fun checkUnseen(c: Context, d: JSONObject) {
        val id = d.optString("id")
        val there = c.getSystemService(NotificationManager::class.java)?.activeNotifications?.any { it.tag == TAG && it.id == id.hashCode() } == true
        if (there) Facts.post(c, "notify_unseen", JSONObject().put("notify", id).put("title", d.optString("title")).put("minutes", d.optInt("minutes")), urgent = true)
    }

    /** A reminder he or Athena set: a plain notification at its time. */
    fun reminder(c: Context, id: String, text: String) {
        val nm = channel(c)
        runCatching {
            nm.notify("reminder", id.hashCode(), NotificationCompat.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("Reminder").setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(PendingIntent.getActivity(c, id.hashCode(), Open.apollo(c), PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true).build())
        }
        Facts.post(c, "reminder", JSONObject().put("text", text).put("shown", true))
    }
}
