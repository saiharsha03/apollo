package dev.apollo.senses

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset

/**
 * Birthdays from his contacts and Google's birthdays calendar (Zeus's auto birthday sources), as `birthday` facts
 * {name, date, source}: the people app's birthdays table, for Athena to fold into people. Once a day, only what changed;
 * people he sealed in Calypso are left out.
 */
object Birthdays {
    private const val DAY = 86_400_000L

    /** "1990-04-12", "--04-12" or "04-12" -> "1990-04-12" / "04-12"; null when it isn't a date. Pure. */
    fun date(raw: String?): String? {
        val s = raw?.trim()?.removePrefix("--") ?: return null
        return when {
            Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(s) -> if (s.startsWith("0000") || s.startsWith("1604")) s.drop(5) else s   // no year known
            Regex("^\\d{2}-\\d{2}$").matches(s) -> s
            Regex("^\\d{8}$").matches(s) -> "${s.take(4)}-${s.substring(4, 6)}-${s.takeLast(2)}"
            else -> null
        }
    }

    /** "Sam Lee's birthday" -> "Sam Lee". Pure. */
    fun nameOf(title: String): String? = Regex("^(.+?)(?:'s|’s)? birthday$", RegexOption.IGNORE_CASE).find(title.trim())?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    private fun can(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

    fun contacts(c: Context): Map<String, String> {
        if (!can(c, Manifest.permission.READ_CONTACTS)) return emptyMap()
        val out = HashMap<String, String>()
        c.contentResolver.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data.DISPLAY_NAME, ContactsContract.CommonDataKinds.Event.START_DATE),
            "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Event.TYPE} = ?",
            arrayOf(ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString()), null)?.use { k ->
            while (k.moveToNext()) { val n = k.getString(0)?.trim(); val d = date(k.getString(1)); if (!n.isNullOrEmpty() && d != null) out[n] = d }
        }
        return out
    }

    fun calendar(c: Context): Map<String, String> {
        if (!can(c, Manifest.permission.READ_CALENDAR)) return emptyMap()
        val ids = c.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME), null, null, null)?.use { k ->
            buildList { while (k.moveToNext()) if (k.getString(1).orEmpty().contains("birthday", true) && k.getString(2).equals(CalendarRules.HIS, true)) add(k.getLong(0)) }
        }.orEmpty()
        if (ids.isEmpty()) return emptyMap()
        val now = System.currentTimeMillis()
        val out = HashMap<String, String>()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { android.content.ContentUris.appendId(it, now - DAY); android.content.ContentUris.appendId(it, now + 366 * DAY) }.build()
        c.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.CALENDAR_ID), null, null, null)?.use { k ->
            while (k.moveToNext()) {
                if (k.getLong(2) !in ids) continue
                val n = nameOf(k.getString(0).orEmpty()) ?: continue
                out[n] = Instant.ofEpochMilli(k.getLong(1)).atZone(ZoneOffset.UTC).toLocalDate().toString().drop(5)
            }
        }
        return out
    }

    /** A person sealed in Calypso: their birthday never leaves the phone. Pure. */
    fun sealedPerson(name: String, sealed: Set<String>) = CallRules.sealed(name, null, sealed)

    /** From the listener's loop: at most once a day, a fact per birthday that is new or changed. */
    fun look(c: Context) {
        val p = c.getSharedPreferences("senses_birthdays", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - p.getLong("looked", 0L) < DAY) return
        p.edit().putLong("looked", now).apply()
        val sealed = CalypsoChoices.read(c).people      // personKey hashes (CalypsoEdit), as calls check them
        val all = calendar(c).mapValues { it.value to "calendar" } + contacts(c).mapValues { it.value to "contacts" }   // contacts win (they may know the year)
        all.forEach { (name, v) ->
            if (sealedPerson(name, sealed) || p.getString("b:$name", null) == v.first) return@forEach
            Senses.sink.post("birthday", JSONObject().put("name", name).put("date", v.first).put("source", v.second), false)
            p.edit().putString("b:$name", v.first).apply()
        }
    }
}
