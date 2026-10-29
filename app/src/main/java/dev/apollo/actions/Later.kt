package dev.apollo.actions

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.apollo.clio.Clio
import org.json.JSONObject

/**
 * Things the phone holds for a time, so they happen on time even offline or in Doze: Athena's timed and repeating
 * notifications, her unseen checks, her reminders, calls she put off (after_minutes, Call me back), and the next
 * routines row. One exact alarm per job; a reboot or an update arms them again ([BootReceiver]).
 */
object Later {
    private fun prefs(c: Context) = c.getSharedPreferences("later_jobs", Context.MODE_PRIVATE)

    fun put(c: Context, id: String, kind: String, at: Long, data: JSONObject = JSONObject()) {
        prefs(c).edit().putString(id, JSONObject().put("kind", kind).put("at", at).put("data", data).toString()).apply()
        arm(c, id, at)
    }

    fun has(c: Context, id: String) = prefs(c).contains(id)

    fun cancel(c: Context, id: String) {
        prefs(c).edit().remove(id).apply()
        c.getSystemService(AlarmManager::class.java)?.cancel(pending(c, id))
    }

    private fun pending(c: Context, id: String) = PendingIntent.getBroadcast(c, id.hashCode(),
        Intent(c, LaterReceiver::class.java).setData(Uri.parse("apollo-later://job/" + Uri.encode(id))),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun arm(c: Context, id: String, at: Long) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        runCatching { if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c, id)) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c, id)) }
            .onFailure { Clio.warn("later", "not armed", it.javaClass.simpleName) }
    }

    /** After a reboot or an update: every job again (one already due runs at once). */
    fun rearm(c: Context) = prefs(c).all.forEach { (id, v) -> runCatching { arm(c, id, JSONObject(v as String).getLong("at")) } }

    /** A job's time came. It leaves the list first, so a crash can't make it run twice. */
    fun due(c: Context, id: String) {
        val raw = prefs(c).getString(id, null) ?: return
        prefs(c).edit().remove(id).apply()
        val j = JSONObject(raw)
        val data = j.optJSONObject("data") ?: JSONObject()
        runCatching {
            when (j.optString("kind")) {
                "notify" -> Notices.dueLater(c, id, data)
                "check" -> Notices.checkUnseen(c, data)
                "reminder" -> Notices.reminder(c, id, data.optString("text"))
                "call" -> dev.apollo.ossa.Ossa.dueLater(c, data)
                "routine" -> RoutineAlarm.due(c)
            }
        }.onFailure { Clio.warn("later", "job failed", it.javaClass.simpleName) }
    }
}

class LaterReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.data?.lastPathSegment ?: return
        val done = goAsync()
        Thread { try { Later.due(c.applicationContext, id) } finally { done.finish() } }.start()
    }
}

/** A reboot or an Apollo update drops every alarm: arm them again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED && i.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Later.rearm(c.applicationContext)
        RoutineAlarm.arm(c.applicationContext)
    }
}

/**
 * PLAN, Routines: "Apollo arms an alarm for the next time row". The server's clock fires the row; this wakes the phone
 * at that minute and pulls the outbox, so what Athena does about it lands on time even when FCM is held back in Doze.
 */
object RoutineAlarm {
    private const val ID = "routine-next"

    /** From the phone's copy of the routines table (re-armed after every mini-app pull). */
    fun arm(c: Context) {
        val f = java.io.File(dev.apollo.apps.MiniApps.root(c), "routines/data.db")
        if (!f.isFile) return Later.cancel(c, ID)
        val rows = runCatching {
            android.database.sqlite.SQLiteDatabase.openDatabase(f.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT id, when_time, tz, repeat, on_off FROM routines WHERE when_time IS NOT NULL", null).use { k ->
                    buildList { while (k.moveToNext()) add(Repeat.Row(k.getLong(0), k.getString(1), k.getString(2), k.getString(3), k.getInt(4) != 0)) }
                }
            }
        }.getOrDefault(emptyList())
        // A little after the minute starts: the server's clock runs at :01 of each minute.
        Repeat.soonest(rows, System.currentTimeMillis())?.let { Later.put(c, ID, "routine", it + 20_000L) } ?: Later.cancel(c, ID)
    }

    fun due(c: Context) {
        dev.apollo.net.Sync.now(c)
        arm(c)
    }
}
