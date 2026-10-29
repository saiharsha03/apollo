package dev.apollo.actions

import android.app.NotificationManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.AlarmClock
import android.view.KeyEvent
import dev.apollo.clio.Clio
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Lets Apollo lock the screen (his one-time OK in More > Phone). Its only policy is force-lock. */
class LockAdmin : DeviceAdminReceiver()

/**
 * The phone actions Zeus had that Apollo had dropped: reminders, lock, the fell-asleep wind-down, Do Not Disturb,
 * cancelling an alarm, messages and calls by name, tiles and shortcuts. Each registers under its own action name, which is
 * also its row in the approvals table; [PhoneActions.run] checks that row before any of them runs.
 */
object MoreActions {
    private val CLOCK = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US)

    fun register() {
        val h = ActionRegistry.handlers
        h["reminder"] = ::reminder
        h["lock"] = { c, _ -> lock(c) }
        h["wind_down"] = { c, _ -> windDown(c) }
        h["dnd"] = { c, a -> dnd(c, a.optBoolean("on", true)) }
        h["cancel_alarm"] = ::cancelAlarm
        h["message"] = ::message
        h["call_person"] = { c, a -> call(c, a.optString("name").ifEmpty { a.optString("number") }, a.optString("via").ifEmpty { null }) }
        h["tile"] = Tiles::set
        h["shortcut"] = Tiles::shortcut
    }

    fun reminder(c: Context, a: JSONObject): Pair<Boolean, String> {
        val text = a.optString("text").trim().ifEmpty { return false to "a reminder needs text" }
        val at = a.optLong("at").takeIf { it > System.currentTimeMillis() } ?: return false to "a reminder needs a time in the future"
        val id = "r-$at-${text.hashCode()}"
        Later.put(c, id, "reminder", at, JSONObject().put("text", text))
        Undo.stage { ctx -> Later.cancel(ctx, id) }
        return true to "reminder set for ${CLOCK.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))}"
    }

    fun admin(c: Context) = ComponentName(c, LockAdmin::class.java)
    fun canLock(c: Context) = c.getSystemService(DevicePolicyManager::class.java)?.isAdminActive(admin(c)) == true

    fun lock(c: Context): Pair<Boolean, String> {
        if (!canLock(c)) return false to "Apollo can't lock the screen yet: he turns it on once in More > Phone"
        c.getSystemService(DevicePolicyManager::class.java).lockNow()
        return true to "screen locked"
    }

    private fun mediaKey(c: Context, key: Int) {
        val a = c.getSystemService(AudioManager::class.java)
        a.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key)); a.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
    }

    /**
     * He fell asleep watching (Athena decided that from the sleep facts): the volume eases down over five seconds, what
     * plays pauses, the volume goes back to where it was (for the morning), Home, and the lock when he allowed it.
     * Undo plays it again.
     */
    fun windDown(c: Context): Pair<Boolean, String> {
        val a = c.getSystemService(AudioManager::class.java)
        val before = a.getStreamVolume(AudioManager.STREAM_MUSIC)
        val playing = a.isMusicActive
        Thread {
            runCatching {
                if (playing && before > 0) for (i in 1..5) { a.setStreamVolume(AudioManager.STREAM_MUSIC, before * (5 - i) / 5, 0); Thread.sleep(1_000L) }
                mediaKey(c, KeyEvent.KEYCODE_MEDIA_PAUSE)
                Thread.sleep(500L)
                a.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0)
                c.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                if (canLock(c)) c.getSystemService(DevicePolicyManager::class.java).lockNow()
            }.onFailure { Clio.warn("actions", "wind down step failed", it.javaClass.simpleName) }
        }.start()
        if (playing) Undo.stage { ctx -> mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY) }
        return true to (if (playing) "easing it down, pausing, Home" else "Home") + if (canLock(c)) ", locking" else " (no lock: not allowed yet)"
    }

    fun dnd(c: Context, on: Boolean): Pair<Boolean, String> {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return false to "Apollo can't change Do Not Disturb yet: he allows it once in More > Phone"
        val before = nm.currentInterruptionFilter
        nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        Undo.stage { ctx -> ctx.getSystemService(NotificationManager::class.java).setInterruptionFilter(before) }
        return true to "Do Not Disturb ${if (on) "on" else "off"}"
    }

    /** Android lets an app only open the Clock's dismiss for an alarm; he confirms there. */
    fun cancelAlarm(c: Context, a: JSONObject): Pair<Boolean, String> {
        val i = Intent(AlarmClock.ACTION_DISMISS_ALARM)
        val timed = a.has("hour") && a.has("minute")
        if (timed) i.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME).putExtra(AlarmClock.EXTRA_HOUR, a.optInt("hour")).putExtra(AlarmClock.EXTRA_MINUTES, a.optInt("minute"))
        else i.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
        return start(c, i, "Clock open to dismiss the ${if (timed) "%d:%02d".format(a.optInt("hour"), a.optInt("minute")) else "next"} alarm; he confirms there")
    }

    /** A new message: the chat opens with the text in it (WhatsApp for Indian numbers unless said, else Messages); he sends. */
    fun message(c: Context, a: JSONObject): Pair<Boolean, String> {
        val to = a.optString("to").ifEmpty { a.optString("name") }.ifEmpty { a.optString("number") }
        val text = a.optString("text")
        if (to.isBlank() || text.isBlank()) return false to "a message needs to and text"
        val p = People.find(c, to) ?: return false to if (People.canRead(c)) "no clear contact for $to" else "Apollo needs contacts permission to find $to"
        val whatsapp = when (a.optString("via").lowercase()) { "whatsapp" -> true; "sms", "text" -> false; else -> People.country(c, p) == "IN" }
        val (i, said) = People.messageIntent(c, p, text, whatsapp)
        return i?.let { start(c, it, said) } ?: (false to said)
    }

    /** A call to [who] (a name or a number): WhatsApp video for Indian numbers, the dialer for the rest, unless [via] says. */
    fun call(c: Context, who: String, via: String?): Pair<Boolean, String> {
        val p = People.find(c, who) ?: return false to if (People.canRead(c)) "no clear contact for $who" else "Apollo needs contacts permission to find $who"
        val (i, said) = People.callIntent(c, p, CallWay.pick(via, People.country(c, p)))
        return i?.let { start(c, it, said) } ?: (false to said)
    }

    private fun start(c: Context, i: Intent, done: String): Pair<Boolean, String> =
        runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true to done }.getOrElse { false to "couldn't: ${it.javaClass.simpleName}" }
}
