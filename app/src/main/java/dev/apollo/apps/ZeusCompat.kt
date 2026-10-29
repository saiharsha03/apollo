package dev.apollo.apps

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.webkit.JavascriptInterface
import android.webkit.WebView
import dev.apollo.net.Facts
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Zeus's page bridge (window.zeus through zeusNative, assets/kit/zeus.js unchanged), so the mini apps Athena built in
 * Zeus (Calliope, the fuel log, the daily wall) run as they were. Reads come from the mirrored data.db; a write becomes
 * a `page_write` fact {app, sql, params} the server applies to that app's own tables (the mirror shows it after the next
 * sync). Calls Apollo doesn't have answer {"error": ...} so a page can say so.
 */
/** [voice]: a full page (AppPage) may ask for the mic; a widget never. */
class ZeusBridge(private val c: Context, private val holder: AppWeb.Bridge, private val voice: Boolean = false,
                 private val pinned: String? = null, private val openApp: (String) -> Unit = {}) {
    @Volatile var view: WebView? = null
    private val main = Handler(Looper.getMainLooper())
    /** A voice answer (a note, or why none) the page had no window.zeusVoice for yet: its own Stop gets it next. */
    @Volatile private var unclaimed: String? = null
    private fun app() = holder.app
    private fun err(msg: String) = JSONObject().put("error", msg).toString()

    /** One statement on this app's own tables: selects as {columns, rows}, writes queued to the server as {changed, queued}. */
    @JavascriptInterface fun sql(statement: String?, args: String?): String {
        val a = app() ?: return err("no app")
        val s = (statement ?: "").trim()
        val params = runCatching { JSONArray(args ?: "[]") }.getOrDefault(JSONArray())
        if (AppFiles.readOnly(s)) return ZeusSql.query(File(MiniApps.root(c), "$a/data.db"), s, params)
        if (s.isEmpty() || s.trimEnd(';').contains(';')) return err("one statement at a time")
        Facts.post(c, "page_write", JSONObject().put("app", a).put("sql", s).put("params", params).put("gesture", holder.gesture()), urgent = true)
        return JSONObject().put("changed", JSONObject.NULL).put("queued", true).toString()
    }

    @JavascriptInterface fun read(area: String?, args: String?): String = err("read_zeus is gone in Apollo: ask Athena, or peek at another app")

    @JavascriptInterface fun file(path: String?): String = readFile(app(), path)

    @JavascriptInterface fun peek(other: String?, statement: String?, args: String?): String {
        val s = (statement ?: "").trim()
        if (!AppFiles.readOnly(s)) return err("peek is one SELECT")
        val o = other?.takeIf { Regex("^[a-z][a-z0-9-]{1,39}$").matches(it) } ?: return err("no such app")
        // A secure app (Money) is read by its own pages only.
        if (o != app() && MiniApps.all(c).any { it.name == o && it.secure }) return err("$o is private")
        return ZeusSql.query(File(MiniApps.root(c), "$o/data.db"), s, runCatching { JSONArray(args ?: "[]") }.getOrDefault(JSONArray()))
    }

    @JavascriptInterface fun peekFile(other: String?, path: String?): String =
        if (other != app() && MiniApps.all(c).any { it.name == other && it.secure }) "" else readFile(other, path)

    private fun readFile(a: String?, path: String?): String {
        a ?: return ""
        val f = AppFiles.resolve(MiniApps.root(c), a, path ?: "") ?: return ""
        return runCatching { f.readText() }.getOrDefault("")
    }

    /** "page:<p>" another page of this app, "app:<name>" another app; Zeus's own places don't exist in Apollo. */
    @JavascriptInterface fun open(target: String?) {
        val a = app() ?: return
        val t = (target ?: "").take(80)
        val url = when {
            t.startsWith("page:") -> AppWeb.url(a, "pages/" + t.removePrefix("page:").let { if ('.' in it) it else "$it.html" })
            t.startsWith("app:") -> t.removePrefix("app:").substringBefore('/').let { o ->
                // A pinned Home view opens another app natively (its secure window and all), never inside itself.
                if (!AppWeb.stays(pinned, o)) { main.post { openApp(o) }; return }
                MiniApps.all(c).firstOrNull { it.name == o }?.let { MiniApps.home(c, it) }?.let { AppWeb.url(o, it) } }
            else -> null
        } ?: return
        main.post { view?.loadUrl(url) }
    }

    @JavascriptInterface fun haptic() { main.post { view?.performHapticFeedback(HapticFeedbackConstants.CONFIRM) } }

    /** A word to Athena from this page: a fact, at once when urgent. */
    @JavascriptInterface fun tell(text: String?, urgent: Boolean) {
        val a = app() ?: return
        Facts.post(c, "page_tell", JSONObject().put("app", a).put("text", (text ?: "").take(500)), urgent)
    }

    // Voice notes (apps/AppVoice): recorded natively into this app's phone folder. The page only asks: his tap on Apollo's
    // own sheet starts the mic (apps/VoiceAsk), and Apollo's banner shows while it records.
    @JavascriptInterface fun voiceStart(): String {
        val a = app() ?: return err("no app")
        if (!voice) return err("no mic here")
        if (!AppVoice.micOk(c)) return err("needs_mic")
        // ok means asked, not recording: window.zeusVoiceState({recording}) says when the mic really starts and ends, and
        // the outcome (the note, or why there is none: cancelled, ended) always comes on window.zeusVoice.
        unclaimed = null
        VoiceAsk.ask.value = VoiceAsk.Ask(a, { why -> voiceAnswer(JSONObject().put("path", "").put("text", "").put("seconds", 0).put("error", why).toString()) },
            started = { state(true) }) { voiceStop() }
        return JSONObject().put("ok", true).put("asked", true).toString()
    }
    @JavascriptInterface fun voiceStop() {
        val a = app() ?: return
        VoiceAsk.clear(a)
        Thread {
            val n = AppVoice.stop(c, a)
            val held = unclaimed
            // Stopped from Apollo's banner first, or cancelled: the page's own Stop then picks up what it missed.
            if (n.error == "not recording" && held != null) { unclaimed = null; voiceAnswer(held) }
            else voiceAnswer(JSONObject().put("path", n.path).put("text", n.text).put("seconds", n.seconds).put("error", n.error ?: JSONObject.NULL).toString())
        }.start()
    }
    private fun state(recording: Boolean) = answer("zeusVoiceState", JSONObject().put("recording", recording).toString())
    /** To window.zeusVoice; kept for the page's next voiceStop when it has none set (Zeus's kit sets it only in stop). */
    private fun voiceAnswer(json: String) {
        state(false)
        main.post { view?.evaluateJavascript("window.zeusVoice ? (window.zeusVoice($json), 1) : 0") { if (it != "1") unclaimed = json } }
    }
    @JavascriptInterface fun voiceRetry(path: String?) { answer("zeusVoice", err("no speech-to-text in Apollo: the recording is kept, type the words")) }
    @JavascriptInterface fun play(path: String?): String {
        val a = app() ?: return err("no app")
        return AppVoice.play(c, a, path ?: "")?.let { err(it) } ?: JSONObject().put("ok", true).toString()
    }
    @JavascriptInterface fun stopPlay() { AppVoice.stopPlay() }

    /** A photo for this app (camera or picker), kept in its phone folder; the page gets {path} on window.zeusPhoto. */
    @JavascriptInterface fun photo(source: String?) {
        val a = app() ?: return
        PhotoAsk.current.value = PhotoAsk.Ask(a, source == "camera") { json -> answer("zeusPhoto", json) }
    }

    @JavascriptInterface fun workspace(path: String?): String = err("Athena's workspace files aren't mirrored to the phone in Apollo yet")
    /** His "Just for me" page: Apollo's own sealed screen over this one (apps/PrivateNote); nothing comes back to the page. */
    @JavascriptInterface fun privateNote() { app()?.let { a -> main.post { PrivateNote.open.value = a } } }

    @JavascriptInterface fun theme(): String = JSONObject().put("dark", true).toString()

    @JavascriptInterface fun screen(): String {
        val m = c.resources.configuration
        return JSONObject().put("width_dp", m.screenWidthDp).put("height_dp", m.screenHeightDp).put("folded", m.screenWidthDp < 600).toString()
    }

    @JavascriptInterface fun now(): String {
        val n = java.time.ZonedDateTime.now()
        return JSONObject().put("boston", n.withZoneSameInstant(java.time.ZoneId.of("America/New_York")).toOffsetDateTime().toString())
            .put("india", n.withZoneSameInstant(java.time.ZoneId.of("Asia/Kolkata")).toOffsetDateTime().toString()).toString()
    }

    private fun answer(fn: String, json: String) { main.post { view?.evaluateJavascript("window.$fn && window.$fn($json)", null) } }
}

/** Read-only queries on a mirrored data.db, in Zeus's answer shape {columns, rows: [{col: value}]}. */
object ZeusSql {
    private const val MAX_ROWS = 2000

    fun query(db: File, sql: String, params: JSONArray): String {
        if (!db.isFile) return JSONObject().put("columns", JSONArray()).put("rows", JSONArray()).toString()
        return runCatching {
            SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
                d.rawQuery(sql, Array(params.length()) { params.opt(it)?.toString() }).use { cur ->
                    val cols = JSONArray(cur.columnNames.toList())
                    val rows = JSONArray()
                    while (cur.moveToNext() && rows.length() < MAX_ROWS) {
                        val r = JSONObject()
                        for (i in 0 until cur.columnCount) r.put(cur.getColumnName(i), when (cur.getType(i)) {
                            android.database.Cursor.FIELD_TYPE_INTEGER -> cur.getLong(i)
                            android.database.Cursor.FIELD_TYPE_FLOAT -> cur.getDouble(i)
                            android.database.Cursor.FIELD_TYPE_STRING -> cur.getString(i)
                            else -> JSONObject.NULL
                        })
                        rows.put(r)
                    }
                    JSONObject().put("columns", cols).put("rows", rows).toString()
                }
            }
        }.getOrElse { JSONObject().put("error", it.javaClass.simpleName).toString() }
    }

    /** The first column of the first row, as text, or null. */
    fun one(db: File, sql: String?): String? {
        if (sql.isNullOrBlank() || !AppFiles.readOnly(sql)) return null
        val r = runCatching { JSONObject(query(db, sql, JSONArray())).optJSONArray("rows")?.optJSONObject(0) }.getOrNull() ?: return null
        val k = r.keys().asSequence().firstOrNull() ?: return null
        return r.opt(k)?.takeIf { it != JSONObject.NULL }?.toString()
    }

    /** Every row's columns as text. */
    fun lines(db: File, sql: String?): List<List<String>> {
        if (sql.isNullOrBlank() || !AppFiles.readOnly(sql)) return emptyList()
        val rows = runCatching { JSONObject(query(db, sql, JSONArray())).optJSONArray("rows") }.getOrNull() ?: return emptyList()
        return List(rows.length()) { i -> rows.getJSONObject(i).let { r -> r.keys().asSequence().map { k -> r.opt(k)?.takeIf { it != JSONObject.NULL }?.toString().orEmpty() }.toList() } }
    }
}
