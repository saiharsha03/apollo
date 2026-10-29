package dev.apollo.net

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import dev.apollo.calypso.KeystoreSealer
import dev.apollo.senses.CalypsoChoices
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Zeus's data on this phone, read once on his tap and sent to the server's importer (server/apollo/zeusimport.py has the
 * shape): drives, places and routes (odyssey.sealed), money (moneta.db), tasks, people, cards and notification notes
 * (senses.db), feedback, card learning, the phone's copy of Athena's memory, sleep nights, music skips, habits, his
 * Athena chats and his trips (argo.sealed). Zeus's sealed files open here with Zeus's own keys (same app id, same Keystore aliases); nothing is
 * changed or deleted. People and apps he sealed in Calypso are left out. Every database is opened read-only.
 */
object ZeusData {
    private const val DAY = 86_400_000L

    private fun db(c: Context, name: String): SQLiteDatabase? = c.getDatabasePath(name).takeIf { it.exists() }
        ?.let { runCatching { SQLiteDatabase.openDatabase(it.path, null, SQLiteDatabase.OPEN_READONLY) }.getOrNull() }

    private inline fun rows(db: SQLiteDatabase, sql: String, args: Array<String>? = null, each: (Cursor) -> Unit) =
        runCatching { db.rawQuery(sql, args).use { k -> while (k.moveToNext()) each(k) } }

    private fun Cursor.str(i: Int): String? = if (isNull(i)) null else getString(i)
    private fun Cursor.num(i: Int): Long? = if (isNull(i)) null else getLong(i)

    /** Builds the export. [missing] collects the parts that weren't there or didn't open. */
    fun build(c: Context, missing: MutableList<String>): JSONObject {
        val out = JSONObject().put("version", 1)
        val sealed = runCatching { CalypsoChoices.read(c) }.getOrNull()
        val sealedPeople = sealed?.people.orEmpty()
        val sealedApps = sealed?.apps.orEmpty()
        fun part(name: String, f: () -> Unit) { runCatching(f).onFailure { missing += "$name (${it.javaClass.simpleName})" } }

        part("drives") { odyssey(c, out) ?: missing.add("drives (no odyssey.sealed)") }
        part("money") { db(c, "moneta.db")?.use { money(it, out) } ?: missing.add("money (no moneta.db)") }
        part("tasks") { db(c, "tasks.db")?.use { tasks(it, out) } ?: missing.add("tasks (no tasks.db)") }
        part("people") { db(c, "senses.db")?.use { senses(it, out, sealedPeople, sealedApps) } ?: missing.add("people (no senses.db)") }
        part("feedback") {
            db(c, "prometheus.db")?.use { d -> val a = JSONArray(); rows(d, "SELECT line_id, up, at FROM feedback") { a.put(JSONObject().put("line_id", it.str(0)).put("up", it.num(1)).put("at", it.num(2))) }; out.put("feedback", a) }
        }
        part("learning") {
            db(c, "learning.db")?.use { d -> val a = JSONArray(); rows(d, "SELECT key, how, taps, dismissals FROM engagement") { a.put(JSONObject().put("key", it.str(0)).put("how", it.num(1)).put("taps", it.num(2)).put("dismissals", it.num(3))) }; out.put("engagement", a) }
        }
        part("memory") {
            val s = KeystoreSealer("zeus.aegis.mind")
            db(c, "memmirror.db")?.use { d -> val a = JSONArray(); rows(d, "SELECT path, content, updated_at FROM memories WHERE deleted_at IS NULL") { k -> s.open(k.getString(1))?.let { a.put(JSONObject().put("path", k.str(0)).put("content", it).put("updated_at", k.num(2))) } }; out.put("memories", a) }
        }
        part("chats") { db(c, "brain.db")?.use { chats(it, out) } }
        part("trips") { argo(c, out) ?: missing.add("trips (no argo.sealed)") }
        part("sleep") { prefsJson(c, "hypnos", "hypnos")?.let { h -> out.put("nights", JSONArray().apply { h.optJSONArray("nights")?.let { n -> for (i in 0 until n.length()) put(n.getJSONObject(i)) } }) } }
        part("music") { c.getSharedPreferences("music", Context.MODE_PRIVATE).getString("skips", null)?.let { out.put("skips", JSONArray(it)) } }
        part("habits") { c.getSharedPreferences("athena", Context.MODE_PRIVATE).getString("ethos_habits", null)?.let { out.put("habits", runCatching { JSONObject(it) }.getOrElse { _ -> JSONArray(it) }) } }
        c.getSharedPreferences("mind", Context.MODE_PRIVATE).getString("id.store", null)?.let { out.put("memory_store_id", it) }
        return out
    }

    private fun prefsJson(c: Context, prefs: String, key: String) = c.getSharedPreferences(prefs, Context.MODE_PRIVATE).getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun odyssey(c: Context, out: JSONObject): Unit? {
        val f = File(c.noBackupFilesDir, "odyssey.sealed").takeIf { it.exists() } ?: return null
        val s = KeystoreSealer("zeus.aegis.odyssey")
        val o = JSONObject(String(s.openBytes(f.readBytes()) ?: error("odyssey.sealed didn't open"), Charsets.UTF_8))
        val places = o.optJSONArray("places") ?: JSONArray()
        val byId = HashMap<String, JSONObject>()
        val outPlaces = JSONArray()
        for (i in 0 until places.length()) places.getJSONObject(i).let { p ->
            byId[p.optString("id")] = p
            outPlaces.put(JSONObject().put("name", p.optString("name")).put("lat", p.optDouble("lat")).put("lng", p.optDouble("lng")).put("kind", p.optString("kind")))
        }
        out.put("places", outPlaces)
        val visits = JSONArray()
        o.optJSONArray("visits")?.let { v -> for (i in 0 until v.length()) v.getJSONObject(i).let { x ->
            val p = byId[x.optString("placeId")]
            visits.put(JSONObject().put("start", x.optLong("start")).put("end", x.optLong("end")).put("place", p?.optString("name")).put("lat", p?.optDouble("lat")).put("lng", p?.optDouble("lng")))
        } }
        out.put("visits", visits)
        val drives = JSONArray()
        val routes = File(c.noBackupFilesDir, "odyssey-routes")
        o.optJSONArray("drives")?.let { d -> for (i in 0 until d.length()) d.getJSONObject(i).let { x ->
            val pts = JSONArray()
            File(routes, "${x.optString("id")}.sealed").takeIf { it.exists() }?.let { rf ->
                runCatching { JSONObject(String(s.openBytes(rf.readBytes())!!, Charsets.UTF_8)).optJSONArray("points") }.getOrNull()?.let { p ->
                    for (j in 0 until p.length()) p.getJSONObject(j).let { pt -> pts.put(JSONArray().put(pt.optDouble("lat")).put(pt.optDouble("lng")).put(pt.optLong("t"))) }
                }
            }
            drives.put(JSONObject().put("id", x.opt("id")).put("start", x.optLong("start")).put("end", x.optLong("end")).put("meters", x.optDouble("meters"))
                .put("from", x.opt("from")).put("to", x.opt("to")).put("points", pts))
        } }
        out.put("drives", drives)
        o.optJSONObject("car")?.let { out.put("car", it) }
        return Unit
    }

    /** Argo's trips (argo.sealed, Zeus's own key): where, when, status, legs, stays, plans and his notes; nothing else of Argo's. */
    private fun argo(c: Context, out: JSONObject): Unit? {
        val f = File(c.noBackupFilesDir, "argo.sealed").takeIf { it.exists() } ?: return null
        val o = JSONObject(String(KeystoreSealer("zeus.aegis.argo").openBytes(f.readBytes()) ?: error("argo.sealed didn't open"), Charsets.UTF_8))
        fun pick(x: JSONObject, vararg keys: String) = JSONObject().apply { keys.forEach { k -> x.opt(k)?.takeIf { it != JSONObject.NULL }?.let { put(k, it) } } }
        fun each(a: JSONArray?, vararg keys: String) = JSONArray().apply { a?.let { for (i in 0 until it.length()) put(pick(it.getJSONObject(i), *keys)) } }
        val trips = JSONArray()
        o.optJSONArray("trips")?.let { t -> for (i in 0 until t.length()) t.getJSONObject(i).let { x ->
            trips.put(pick(x, "id", "place", "startDate", "endDate", "status", "notes")
                .put("legs", each(x.optJSONArray("legs"), "mode", "from", "to", "departAt", "arriveAt", "carrier", "number", "platform", "returning"))
                .put("stays", each(x.optJSONArray("stays"), "name", "address", "checkIn", "checkOut"))
                .put("plans", each(x.optJSONArray("plans"), "title", "at", "end", "place")))
        } }
        out.put("trips", trips)
        return Unit
    }

    private fun money(d: SQLiteDatabase, out: JSONObject) {
        val s = KeystoreSealer("zeus.aegis.moneta")
        val txns = JSONArray()
        rows(d, "SELECT id, at, blob, k FROM txns") { k ->
            s.open(k.getString(2))?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { t ->
                txns.put(t.put("id", k.getLong(0)).put("at", t.optLong("at", k.getLong(1))).put("k", k.str(3) ?: JSONObject.NULL))
            }
        }
        val subs = JSONArray()
        rows(d, "SELECT blob FROM kv WHERE k = 'subs'") { k -> s.open(k.getString(0))?.let { runCatching { JSONArray(it) }.getOrNull() }?.let { a -> for (i in 0 until a.length()) subs.put(a.get(i)) } }
        out.put("money", JSONObject().put("transactions", txns).put("subscriptions", subs))
    }

    private fun tasks(d: SQLiteDatabase, out: JSONObject) {
        val projects = JSONArray()
        rows(d, "SELECT id, name FROM projects WHERE sample = 0") { projects.put(JSONObject().put("id", it.getLong(0)).put("name", it.str(1))) }
        val tasks = JSONArray()
        rows(d, "SELECT id, title, list, project_id, due, done_at, context, waiting_on, follow_up, sample FROM tasks") { k ->
            tasks.put(JSONObject().put("id", k.getLong(0)).put("title", k.str(1)).put("list", k.str(2)).put("project_id", k.num(3)).put("due", k.num(4))
                .put("done_at", k.num(5)).put("context", k.str(6)).put("waiting_on", k.str(7)).put("follow_up", k.num(8)).put("sample", k.getInt(9)))
        }
        out.put("tasks", JSONObject().put("projects", projects).put("tasks", tasks))
    }

    private fun senses(d: SQLiteDatabase, out: JSONObject, sealedPeople: Set<String>, sealedApps: Set<String>) {
        val s = KeystoreSealer("zeus.aegis.senses")
        val people = JSONArray()
        rows(d, "SELECT key, name, relation, zone, last_at FROM people WHERE COALESCE(calypso, 0) = 0") { k ->
            if (k.str(0) in sealedPeople) return@rows
            val name = k.str(1)?.let { s.open(it) } ?: return@rows
            people.put(JSONObject().put("key", k.str(0)).put("name", name).put("relation", k.str(2)).put("zone", k.str(3)).put("last_at", k.num(4)))
        }
        out.put("people", people)
        val cards = JSONArray()
        rows(d, "SELECT title, body, at FROM cards") { k -> cards.put(JSONObject().put("title", k.str(0)?.let { s.open(it) }).put("body", k.str(1)?.let { s.open(it) }).put("at", k.num(2))) }
        out.put("cards", cards)
        // Notification notes, the last 90 days, never from an app or person he sealed.
        val notes = JSONArray()
        rows(d, "SELECT at, pkg, app, person, kind, summary FROM notes WHERE at > ?", arrayOf((System.currentTimeMillis() - 90 * DAY).toString())) { k ->
            if (k.str(1) in sealedApps || k.str(3) in sealedPeople) return@rows
            val summary = k.str(5)?.let { s.open(it) } ?: return@rows
            notes.put(JSONObject().put("at", k.num(0)).put("app", k.str(2)).put("person", k.str(3)).put("kind", k.str(4)).put("summary", summary))
        }
        out.put("notes", notes)
    }

    private fun chats(d: SQLiteDatabase, out: JSONObject) {
        val s = KeystoreSealer("zeus.aegis.chat")
        val chats = JSONArray()
        rows(d, "SELECT id, started_at FROM chats ORDER BY id") { k ->
            val lines = JSONArray()
            rows(d, "SELECT at, kind, text, sealed FROM chat_lines WHERE chat_id = ? AND kind IN ('USER', 'ATHENA') ORDER BY id", arrayOf(k.getLong(0).toString())) { l ->
                val text = if (l.getInt(3) == 1) l.str(2)?.let { s.open(it) } else l.str(2)
                if (!text.isNullOrBlank()) lines.put(JSONObject().put("at", l.num(0)).put("kind", if (l.str(1) == "USER") "user" else "athena").put("text", text))
            }
            if (lines.length() > 0) chats.put(JSONObject().put("id", k.getLong(0)).put("started_at", k.num(1)).put("lines", lines))
        }
        out.put("chats", chats)
    }

    private fun p(c: Context) = c.getSharedPreferences("zeus_data", Context.MODE_PRIVATE)

    /** When it last went, and what the server said (null before). */
    fun last(c: Context): String? = p(c).getString("result", null)

    /** Blocking, off the main thread, on his tap. Returns a line for the screen. Safe to run again: the server adds nothing twice. */
    fun send(c: Context): String {
        val missing = mutableListOf<String>()
        val body = build(c, missing).toString().toByteArray()
        val r = Server(c).importZeusData(body)
        val added = r.optJSONObject("added")?.let { a -> a.keys().asSequence().joinToString { "${a.optInt(it)} $it" } }.orEmpty()
        val line = (if (added.isEmpty()) "Nothing new: it was all there already." else "Brought over: $added.") +
            (if (missing.isEmpty()) "" else " Not found: ${missing.joinToString()}.")
        p(c).edit().putString("result", line).putLong("at", System.currentTimeMillis()).apply()
        dev.apollo.clio.Clio.info("zeus", "data sent", "${body.size} bytes, ${missing.size} parts missing")
        return line
    }
}
