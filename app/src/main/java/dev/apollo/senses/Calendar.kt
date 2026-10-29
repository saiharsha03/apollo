package dev.apollo.senses

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import dev.apollo.actions.ActionRegistry
import dev.apollo.clio.Clio
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** One event as Athena sees it. [id] is the event's id in the phone's calendar (what calendar_update / _delete take). */
data class CalEvent(val id: Long, val title: String, val start: Long, val end: Long, val allDay: Boolean, val location: String, val calendar: String)

/** Which calendars are his, and the shapes of times. Pure. */
object CalendarRules {
    const val HIS = "owner@example.com"

    /** His one Google calendar account and the phone's own calendar; every other account (work, shared, holidays...) is skipped. */
    fun kept(accountName: String?, accountType: String?): Boolean =
        accountName.equals(HIS, ignoreCase = true) || accountType.equals("LOCAL", ignoreCase = true)

    /** All-day events are stored at UTC midnight of their date (the calendar provider's rule). */
    fun allDayUtc(ms: Long, zone: ZoneId): Long = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** An all-day event's end is the day after its last day ([end] names the last day; the provider's end is exclusive). */
    fun allDayEnd(start: Long, end: Long, zone: ZoneId): Long = maxOf(allDayUtc(start, zone), allDayUtc(end, zone)) + 86_400_000L

    /** A stable fingerprint of the events, so an unchanged calendar sends nothing. */
    fun key(events: List<CalEvent>): String = events.sortedWith(compareBy({ it.start }, { it.id })).joinToString("|") { "${it.id}:${it.title}:${it.start}:${it.end}:${it.location}" }
}

/**
 * His calendar through the phone's CalendarContract (no Google sign-in of Apollo's own). A `calendar` fact with the
 * next 30 days (and yesterday) whenever it changes, every event with its id. Writes are `phone_do` actions
 * (calendar_add / calendar_update / calendar_delete, tap level), and only ever on his calendars.
 */
object CalendarSense {
    private const val DAY = 86_400_000L

    fun canRead(c: Context) = ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    private fun canWrite(c: Context) = ContextCompat.checkSelfPermission(c, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun register() {
        ActionRegistry.handlers["calendar_add"] = ::add
        ActionRegistry.handlers["add_his_calendar"] = ::add
        ActionRegistry.handlers["calendar_update"] = ::update
        ActionRegistry.handlers["calendar_delete"] = ::delete
        // Adding and changing an event are one rule, his add_his_calendar row; deleting keeps its own.
        ActionRegistry.keys["calendar_add"] = "add_his_calendar"
        ActionRegistry.keys["calendar_update"] = "add_his_calendar"
    }

    /** id -> name of his calendars, the primary Google one first. */
    fun calendars(c: Context): List<Pair<Long, String>> = c.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
        arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.Calendars.IS_PRIMARY), null, null, null)?.use { k ->
        buildList { while (k.moveToNext()) if (CalendarRules.kept(k.getString(2), k.getString(3))) add(Triple(k.getLong(0), k.getString(1) ?: "", k.getInt(4) == 1 && k.getString(2).equals(CalendarRules.HIS, true))) }
            .sortedByDescending { it.third }.map { it.first to it.second }
    }.orEmpty()

    fun events(c: Context, from: Long, to: Long): List<CalEvent> {
        val mine = calendars(c).toMap()
        if (mine.isEmpty()) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }.build()
        return c.contentResolver.query(uri, arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.CALENDAR_ID),
            null, null, CalendarContract.Instances.BEGIN)?.use { k ->
            buildList { while (k.moveToNext()) mine[k.getLong(6)]?.let { cal -> add(CalEvent(k.getLong(0), k.getString(1) ?: "", k.getLong(2), k.getLong(3), k.getInt(4) == 1, k.getString(5) ?: "", cal)) } }
        }.orEmpty()
    }

    /** From the listener's loop (every 15 minutes) and after each write: a fact only when something changed. */
    fun look(c: Context, force: Boolean = false) {
        if (!canRead(c)) return
        val p = c.getSharedPreferences("senses_calendar", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - p.getLong("looked", 0L) < 15 * 60_000L) return
        p.edit().putLong("looked", now).apply()
        val from = now - DAY
        val to = now + 30 * DAY
        val list = runCatching { events(c, from, to) }.getOrElse { Clio.log(c, "calendar", "read failed", JSONObject().put("error", it.javaClass.simpleName)); return }
        val key = CalendarRules.key(list)
        if (key == p.getString("key", null)) return
        p.edit().putString("key", key).apply()
        Senses.sink.post("calendar", JSONObject().put("from", from).put("to", to).put("events", JSONArray().apply {
            list.forEach { e -> put(JSONObject().put("id", e.id).put("title", e.title).put("start", e.start).put("end", e.end).put("all_day", e.allDay)
                .put("location", e.location).put("calendar", e.calendar)) }
        }), false)
    }

    private fun values(a: JSONObject, zone: ZoneId): ContentValues = ContentValues().apply {
        val allDay = a.optBoolean("all_day", false)
        if (a.has("title")) put(CalendarContract.Events.TITLE, a.getString("title"))
        if (a.has("location")) put(CalendarContract.Events.EVENT_LOCATION, a.getString("location"))
        if (a.has("notes")) put(CalendarContract.Events.DESCRIPTION, a.getString("notes"))
        if (a.has("all_day")) put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
        if (a.has("start") || a.has("end")) put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else zone.id)
        if (a.has("start")) put(CalendarContract.Events.DTSTART, if (allDay) CalendarRules.allDayUtc(a.getLong("start"), zone) else a.getLong("start"))
        if (a.has("end")) put(CalendarContract.Events.DTEND, if (allDay) CalendarRules.allDayEnd(a.optLong("start", a.getLong("end")), a.getLong("end"), zone) else a.getLong("end"))
    }

    fun add(c: Context, a: JSONObject): Pair<Boolean, String> {
        if (!canWrite(c)) return false to "calendar permission is off on the phone"
        if (!a.has("title") || !a.has("start")) return false to "calendar_add needs title and start"
        val cal = calendars(c).firstOrNull() ?: return false to "his calendar isn't on the phone"
        val args = JSONObject(a.toString()).apply { if (!has("end")) put("end", getLong("start") + if (optBoolean("all_day")) 0L else 3_600_000L) }
        val v = values(args, ZoneId.systemDefault()).apply { put(CalendarContract.Events.CALENDAR_ID, cal.first) }
        val uri = c.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: return false to "the calendar refused it"
        look(c, force = true)
        return true to "added to ${cal.second} (event ${ContentUris.parseId(uri)})"
    }

    fun update(c: Context, a: JSONObject): Pair<Boolean, String> {
        val id = mineOrNull(c, a) ?: return false to "event ${a.optString("id")} isn't in his calendar"
        if (!canWrite(c)) return false to "calendar permission is off on the phone"
        val n = c.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), values(a, ZoneId.systemDefault()), null, null)
        look(c, force = true)
        return (n > 0) to if (n > 0) "event $id changed" else "event $id is gone"
    }

    fun delete(c: Context, a: JSONObject): Pair<Boolean, String> {
        val id = mineOrNull(c, a) ?: return false to "event ${a.optString("id")} isn't in his calendar"
        if (!canWrite(c)) return false to "calendar permission is off on the phone"
        val n = c.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null)
        look(c, force = true)
        return (n > 0) to if (n > 0) "event $id deleted" else "event $id is gone"
    }

    /** The event id, only when the event lives in one of his calendars. */
    private fun mineOrNull(c: Context, a: JSONObject): Long? {
        val id = a.optString("id").toLongOrNull() ?: return null
        if (!canRead(c)) return null
        val cal = c.contentResolver.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), arrayOf(CalendarContract.Events.CALENDAR_ID), null, null, null)
            ?.use { if (it.moveToFirst()) it.getLong(0) else null } ?: return null
        return id.takeIf { calendars(c).any { it.first == cal } }
    }
}
