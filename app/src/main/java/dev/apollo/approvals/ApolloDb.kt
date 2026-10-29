package dev.apollo.approvals

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * apollo.db: Apollo's own rules, on the phone only (no backup, never synced, never written by an agent). Athena can only
 * ask for a stricter approvals row through an outbox `rule`; loosening anything needs his fingerprint.
 */
class ApolloDb(context: Context, name: String? = "approvals.db") : SQLiteOpenHelper(context, name, null, 4) {
    private val app = context.applicationContext ?: context

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE approvals (action TEXT PRIMARY KEY, level TEXT NOT NULL CHECK (level IN ('free','tap','fingerprint','never')), " +
                "added_by TEXT NOT NULL, at INTEGER NOT NULL)",
        )
        db.execSQL("CREATE TABLE skip_apps (pkg TEXT PRIMARY KEY)")
        db.execSQL("CREATE TABLE skip_gmail (keep_account TEXT PRIMARY KEY)")
        db.execSQL("CREATE TABLE fetch_allow (host TEXT PRIMARY KEY)")
        val now = System.currentTimeMillis()
        for ((action, level) in Seed.APPROVALS) db.insert("approvals", null, ContentValues().apply {
            put("action", action); put("level", level.wire); put("added_by", "Owner"); put("at", now)
        })
        for (p in Seed.SKIP_APPS) db.insert("skip_apps", null, ContentValues().apply { put("pkg", p) })
        db.insert("skip_gmail", null, ContentValues().apply { put("keep_account", Seed.GMAIL_KEEP) })
        for (h in Seed.FETCH) db.insert("fetch_allow", null, ContentValues().apply { put("host", h) })
        calypsoTables(db)
    }

    // 2: the rest of the weather page's own hosts (city search, air quality, the radar and its base map), as Zeus allowed
    // them for Aeolia. Added once; anything he removed later stays removed.
    // 3: rows for the phone actions Zeus had (reminder, lock, wind-down, DND, alarms, tiles...). Only missing rows are
    // added, so a row he made stricter stays as he left it.
    // 4: open_app and open_settings (Owner 10-26: free); rows for the actions that had none (so all asked): Spotify,
    // Live, plugs, the home snapshot free; opening a link and editing his calendar ask. Missing rows only, as in 3.
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) for (h in Seed.WEATHER_MORE) db.insertWithOnConflict("fetch_allow", null, ContentValues().apply { put("host", h) }, SQLiteDatabase.CONFLICT_IGNORE)
        if (oldVersion < 3) for ((action, level) in Seed.PHONE_MORE) db.insertWithOnConflict("approvals", null, ContentValues().apply {
            put("action", action); put("level", level.wire); put("added_by", "Owner"); put("at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_IGNORE)
        if (oldVersion < 3) calypsoTables(db)
        if (oldVersion < 4) for ((action, level) in Seed.PHONE_OPEN + Seed.ACTIONS_MORE) db.insertWithOnConflict("approvals", null, ContentValues().apply {
            put("action", action); put("level", level.wire); put("added_by", "Owner"); put("at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /**
     * 3: the apps and people he seals in Calypso (their notifications never become facts). Zeus's list in senses.db
     * (calypso_apps, people.calypso = 1) is copied in once; from then on this table is the list. `name` is the person's
     * name sealed with Zeus's senses key (as senses.db kept it), shown only on the Calypso screen.
     */
    private fun calypsoTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS calypso_apps (pkg TEXT PRIMARY KEY)")
        db.execSQL("CREATE TABLE IF NOT EXISTS calypso_people (key TEXT PRIMARY KEY, name TEXT)")
        val f = app.getDatabasePath("senses.db")
        if (!f.exists()) return
        runCatching {
            SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { z ->
                z.rawQuery("SELECT pkg FROM calypso_apps", null).use { c ->
                    while (c.moveToNext()) db.insertWithOnConflict("calypso_apps", null, ContentValues().apply { put("pkg", c.getString(0)) }, SQLiteDatabase.CONFLICT_IGNORE)
                }
                z.rawQuery("SELECT key, name FROM people WHERE calypso = 1", null).use { c ->
                    while (c.moveToNext()) db.insertWithOnConflict("calypso_people", null, ContentValues().apply { put("key", c.getString(0)); put("name", c.getString(1)) }, SQLiteDatabase.CONFLICT_IGNORE)
                }
            }
        }
    }

    fun calypsoApps(): Set<String> = strings("SELECT pkg FROM calypso_apps")
    fun calypsoPeopleKeys(): Set<String> = strings("SELECT key FROM calypso_people")

    /** (key, sealed name or null) for the Calypso screen. */
    fun calypsoPeople(): List<Pair<String, String?>> = readableDatabase.rawQuery("SELECT key, name FROM calypso_people", null).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
    }

    fun setCalypsoApp(pkg: String, on: Boolean) {
        if (on) writableDatabase.insertWithOnConflict("calypso_apps", null, ContentValues().apply { put("pkg", pkg) }, SQLiteDatabase.CONFLICT_IGNORE)
        else writableDatabase.delete("calypso_apps", "pkg = ?", arrayOf(pkg))
    }

    fun setCalypsoPerson(key: String, sealedName: String?, on: Boolean) {
        if (on) writableDatabase.insertWithOnConflict("calypso_people", null, ContentValues().apply { put("key", key); put("name", sealedName) }, SQLiteDatabase.CONFLICT_REPLACE)
        else writableDatabase.delete("calypso_people", "key = ?", arrayOf(key))
    }

    fun rules(): Map<String, Level> = readableDatabase.rawQuery("SELECT action, level FROM approvals", null).use { c ->
        buildMap { while (c.moveToNext()) Level.of(c.getString(1))?.let { put(c.getString(0), it) } }
    }

    fun rows(): List<RuleRow> = readableDatabase.rawQuery("SELECT action, level, added_by, at FROM approvals ORDER BY action", null).use { c ->
        buildList { while (c.moveToNext()) Level.of(c.getString(1))?.let { add(RuleRow(c.getString(0), it, c.getString(2), c.getLong(3))) } }
    }

    fun put(action: String, level: Level, by: String, at: Long) {
        writableDatabase.insertWithOnConflict("approvals", null, ContentValues().apply {
            put("action", action); put("level", level.wire); put("added_by", by); put("at", at)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun skipApps(): Set<String> = strings("SELECT pkg FROM skip_apps")
    fun gmailKeep(): Set<String> = strings("SELECT keep_account FROM skip_gmail")
    fun fetchAllow(): Set<String> = strings("SELECT host FROM fetch_allow")

    /** His own adds (Approvals screen, behind his fingerprint) and removals (stricter, so free). */
    fun allowHost(host: String) { writableDatabase.insertWithOnConflict("fetch_allow", null, ContentValues().apply { put("host", host) }, SQLiteDatabase.CONFLICT_IGNORE) }
    fun dropHost(host: String) { writableDatabase.delete("fetch_allow", "host = ?", arrayOf(host)) }

    private fun strings(sql: String): Set<String> = readableDatabase.rawQuery(sql, null).use { c ->
        buildSet { while (c.moveToNext()) add(c.getString(0)) }
    }

    companion object {
        @Volatile private var instance: ApolloDb? = null
        fun get(context: Context): ApolloDb = instance ?: synchronized(this) { instance ?: ApolloDb(context.applicationContext).also { instance = it } }
    }
}

data class RuleRow(val action: String, val level: Level, val by: String, val at: Long)

/** What Apollo starts with (docs/PLAN.md, Approvals; Owner 2026-10-26). */
object Seed {
    val APPROVALS: List<Pair<String, Level>> = listOf(
        "lights" to Level.FREE, "fan" to Level.FREE, "pause_media" to Level.FREE, "volume" to Level.FREE, "timer" to Level.FREE,
        "mini_app" to Level.FREE, "her_routines" to Level.FREE, "card" to Level.FREE, "notify" to Level.FREE, "call_owner" to Level.FREE,
        "message" to Level.TAP, "email" to Level.TAP, "reply" to Level.TAP, "call_person" to Level.TAP,
        "add_his_calendar" to Level.TAP, "ask_iris_send" to Level.TAP,
        // His health record: a meal or a weight Athena logs waits for his tap.
        "log_food" to Level.TAP, "log_weight" to Level.TAP,
        "money" to Level.FINGERPRINT, "delete_no_undo" to Level.FINGERPRINT, "calypso" to Level.FINGERPRINT,
        "sensitive_settings" to Level.FINGERPRINT, "loosen_rule" to Level.FINGERPRINT,
        "open_lock" to Level.NEVER, "open_garage" to Level.NEVER, "read_calypso" to Level.NEVER,
    ) + PHONE_MORE + PHONE_OPEN + ACTIONS_MORE
    /** PLAN, Approvals: easy to undo and stays on the phone, so free (with Undo where it can be undone). */
    val PHONE_MORE: List<Pair<String, Level>>
        get() = listOf("mute", "alarm", "cancel_alarm", "reminder", "lock", "wind_down", "dnd", "tile", "shortcut").map { it to Level.FREE }
    /** Opening an app or a settings page: on the phone, nothing leaves it, so free (Owner 2026-10-26). */
    val PHONE_OPEN: List<Pair<String, Level>>
        get() = listOf("open_app", "open_settings").map { it to Level.FREE }
    /** PLAN: music, the Live chip, plugs and the home snapshot stay on the phone and undo easily; a link and his calendar's edits ask. */
    val ACTIONS_MORE: List<Pair<String, Level>>
        get() = listOf("spotify", "live", "live_stop", "plug", "home_state").map { it to Level.FREE } +
            listOf("open_url", "calendar_add", "calendar_update", "calendar_delete").map { it to Level.TAP }
    val SKIP_APPS = listOf("com.twitter.android", "org.telegram.messenger", "org.thunderdog.challegram")
    const val GMAIL_KEEP = "owner@example.com"
    val WEATHER_MORE = listOf("geocoding-api.open-meteo.com", "air-quality-api.open-meteo.com", "api.rainviewer.com", "tilecache.rainviewer.com", "server.arcgisonline.com")
    val FETCH = listOf("api.open-meteo.com", "api.weather.gov", "mesonet.agron.iastate.edu") + WEATHER_MORE
}
