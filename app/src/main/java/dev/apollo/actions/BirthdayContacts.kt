package dev.apollo.actions

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import dev.apollo.senses.CalendarRules
import org.json.JSONObject
import java.time.LocalDate

/** The shapes add_birthday works with. Pure. */
object BirthdayRules {
    const val GOOGLE = "com.google"

    /** "10-25" -> "--10-25" (no year, as Contacts keeps it), "1990-10-25" -> itself; null when it isn't a real date. */
    fun startDate(date: String): String? {
        val s = date.trim()
        Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").find(s)?.let { m ->
            val (y, mo, d) = m.destructured
            return runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull()?.takeIf { y.toInt() >= 1900 }?.let { s }
        }
        Regex("^(\\d{2})-(\\d{2})$").find(s)?.let { m ->
            val (mo, d) = m.destructured
            return runCatching { LocalDate.of(2000, mo.toInt(), d.toInt()) }.getOrNull()?.let { "--$s" }    // 2000: Feb 29 is fine
        }
        return null
    }

    /** The same name, whatever the case and spacing. */
    fun same(a: String, b: String): Boolean = a.trim().replace(Regex("\\s+"), " ").equals(b.trim().replace(Regex("\\s+"), " "), ignoreCase = true)

    /** His Google contact with that name ([contacts]: raw contact id to display name), or null for a new one. */
    fun pick(contacts: List<Pair<Long, String>>, name: String): Long? = contacts.firstOrNull { same(it.second, name) }?.first

    /**
     * Whether a contact add_birthday made is still only what it made ([rows]: each data row's mimetype and data1): the
     * name and that birthday, besides the group rows Google's sync adds. Undo deletes such a contact; one he changed since
     * only loses the birthday.
     */
    fun untouched(rows: List<Pair<String, String?>>, name: String, start: String): Boolean {
        val mine = rows.filter { it.first != GroupMembership.CONTENT_ITEM_TYPE }
        return mine.size == 2 && mine.any { it.first == StructuredName.CONTENT_ITEM_TYPE && same(it.second ?: "", name) } &&
            mine.any { it.first == Event.CONTENT_ITEM_TYPE && it.second == start }
    }
}

/**
 * A real Google birthday, the way Contacts makes one: a birthday on the person's contact in his Google account (made with
 * just the name when he has none), which Google Calendar shows in its Birthdays calendar every year. No Google sign-in.
 * add_birthday {name, date} (his tap by default); remove_birthday {name, date} takes back only what add_birthday made
 * here (the birthday it added, or the contact it created). A contact that already has a birthday is never changed.
 */
object BirthdayContacts {
    fun register() {
        ActionRegistry.handlers["add_birthday"] = ::add
        ActionRegistry.handlers["remove_birthday"] = ::remove
    }

    private fun prefs(c: Context) = c.getSharedPreferences("birthday_contacts", Context.MODE_PRIVATE)
    private fun key(name: String, date: String) = "${name.trim().lowercase()}|${date.trim()}"
    private fun can(c: Context) = c.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
        c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** His Google account's contacts: raw contact id to display name. */
    private fun his(c: Context): List<Pair<Long, String>> = c.contentResolver.query(ContactsContract.RawContacts.CONTENT_URI,
        arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY),
        "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND ${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND ${ContactsContract.RawContacts.DELETED} = 0",
        arrayOf(BirthdayRules.GOOGLE, CalendarRules.HIS), null)?.use { k ->
        buildList { while (k.moveToNext()) add(k.getLong(0) to (k.getString(1) ?: "")) }
    }.orEmpty()

    private fun birthdayOf(c: Context, raw: Long): String? = c.contentResolver.query(ContactsContract.Data.CONTENT_URI, arrayOf(Event.START_DATE),
        "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ? AND ${Event.TYPE} = ?",
        arrayOf("$raw", Event.CONTENT_ITEM_TYPE, "${Event.TYPE_BIRTHDAY}"), null)?.use { if (it.moveToFirst()) it.getString(0) ?: "" else null }

    /** A raw contact's data rows: mimetype and data1 (the name, the event's date, ...). */
    private fun rowsOf(c: Context, raw: Long): List<Pair<String, String?>> = c.contentResolver.query(ContactsContract.Data.CONTENT_URI,
        arrayOf(ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1), "${ContactsContract.Data.RAW_CONTACT_ID} = ?", arrayOf("$raw"), null)?.use { k ->
        buildList { while (k.moveToNext()) add((k.getString(0) ?: "") to k.getString(1)) }
    }.orEmpty()

    fun add(c: Context, a: JSONObject): Pair<Boolean, String> {
        val name = a.optString("name").trim().take(80)
        val date = a.optString("date").trim()
        val start = BirthdayRules.startDate(date) ?: return false to "date must be MM-DD or YYYY-MM-DD"
        if (name.isEmpty()) return false to "add_birthday needs a name"
        if (!can(c)) return false to "contacts permission is off on the phone"
        val raw = BirthdayRules.pick(his(c), name)
        if (raw != null) {
            birthdayOf(c, raw)?.let { return false to "$name already has a birthday in Contacts ($it); left as it is" }
            val uri = c.contentResolver.insert(ContactsContract.Data.CONTENT_URI, android.content.ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, raw); put(ContactsContract.Data.MIMETYPE, Event.CONTENT_ITEM_TYPE)
                put(Event.TYPE, Event.TYPE_BIRTHDAY); put(Event.START_DATE, start)
            }) ?: return false to "Contacts refused it"
            prefs(c).edit().putString(key(name, date), "data:${ContentUris.parseId(uri)}").apply()
            return true to "birthday added to $name's contact"
        }
        val ops = arrayListOf(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, BirthdayRules.GOOGLE).withValue(ContactsContract.RawContacts.ACCOUNT_NAME, CalendarRules.HIS).build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE).withValue(StructuredName.DISPLAY_NAME, name).build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, Event.CONTENT_ITEM_TYPE).withValue(Event.TYPE, Event.TYPE_BIRTHDAY).withValue(Event.START_DATE, start).build(),
        )
        val made = runCatching { c.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops) }.getOrNull()?.firstOrNull()?.uri ?: return false to "Contacts refused it"
        prefs(c).edit().putString(key(name, date), "raw:${ContentUris.parseId(made)}").apply()
        return true to "new contact $name with the birthday"
    }

    fun remove(c: Context, a: JSONObject): Pair<Boolean, String> {
        val name = a.optString("name").trim()
        val k = key(name, a.optString("date"))
        val made = prefs(c).getString(k, null) ?: return true to "nothing Apollo added for $name"
        if (!can(c)) return false to "contacts permission is off on the phone"
        val (kind, id) = made.split(':').let { it[0] to it[1].toLong() }
        val start = BirthdayRules.startDate(a.optString("date")) ?: return false to "date must be MM-DD or YYYY-MM-DD"
        // The whole contact only while it is still just what add_birthday made; otherwise (and for a birthday it added to
        // his contact) only that birthday, and only while it still has that date.
        val whole = kind == "raw" && BirthdayRules.untouched(rowsOf(c, id), name, start) && birthdayOf(c, id) == start     // still a birthday, not an anniversary now
        if (whole) c.contentResolver.delete(ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, id), null, null)
        else if (kind == "raw") c.contentResolver.delete(ContactsContract.Data.CONTENT_URI,
            "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ? AND ${Event.TYPE} = ? AND ${Event.START_DATE} = ?",
            arrayOf("$id", Event.CONTENT_ITEM_TYPE, "${Event.TYPE_BIRTHDAY}", start))
        else c.contentResolver.delete(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, id),
            "${Event.START_DATE} = ? AND ${Event.TYPE} = ?", arrayOf(start, "${Event.TYPE_BIRTHDAY}"))
        prefs(c).edit().remove(k).apply()
        return true to if (whole) "removed the contact Apollo made for $name" else "removed the birthday from $name's contact"
    }
}
