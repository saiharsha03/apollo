package dev.apollo.apps

import android.annotation.SuppressLint
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.apollo.approvals.ApolloDb
import dev.apollo.house.Command
import dev.apollo.house.House
import dev.apollo.net.APPS_DIR
import dev.apollo.net.Facts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit

/** One mini app as its app.json says. [secure]: "secure": true (money): no screenshots or recents preview of its pages. */
data class MiniApp(val name: String, val title: String, val icon: String, val about: String, val section: Boolean,
                   val pages: List<String>, val widgets: List<String>, val dynamic: String?, val secure: Boolean = false)

object MiniApps {
    fun root(c: Context) = File(c.filesDir, APPS_DIR)

    fun all(c: Context): List<MiniApp> = root(c).listFiles()?.filter { it.isDirectory }?.mapNotNull { d ->
        runCatching {
            val j = JSONObject(File(d, "app.json").readText())
            fun list(k: String) = j.optJSONArray(k)?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
            MiniApp(d.name, j.optString("title", d.name), j.optString("icon"), j.optString("about"), j.optBoolean("section"),
                // Dynamic: app.json's "dynamic", or by convention a widgets/dynamic.html she writes (agents can't edit app.json).
                list("pages"), list("widgets"), j.optString("dynamic").ifBlank { null } ?: "widgets/dynamic.html".takeIf { File(d, it).isFile }, j.optBoolean("secure"))
        }.getOrNull()
    }?.sortedBy { it.title.lowercase() }.orEmpty()

    /** The page an app opens on: pages/home.*, else index.html, else its first page. */
    fun home(c: Context, app: MiniApp): String? =
        listOf("pages/home.html", "pages/index.html", "pages/home.md").firstOrNull { File(root(c), "${app.name}/$it").isFile } ?: app.pages.firstOrNull()
}

/**
 * The WebView every mini-app page and widget renders in. It serves only files of the mirrored folder on the private
 * origin and proxies GETs to hosts on Owner's fetch_allow list; every other request gets an empty 403. The page gets
 * `window.apollo`: sql (read-only, its own app's data.db), tap (a page_tap fact; the home app's tile tap and Muse's tap("spotify", ...) are carried out, its promise says {ok, detail}), spotify (Muse only: search, playlists, playlist_tracks read through the server), fetch (allowed hosts), location,
 * open (an https link in the browser, behind his open_url rule), allows / allow (Dawn's Allow: senses/Allow), pixel (the pixel characters, docs/PIXEL.md), photo (camera or picker, kept in the
 * app's phone folder: apps/PhoneFiles).
 */
object AppWeb {
    /** How soon after a touch on its view a page's tap or write still counts as his own (page JS can call with no gesture). */
    const val GESTURE_MS = 1000L

    /** Pure: whether a call at [now] follows a touch at [touchedAt] (uptime ms, 0: never touched) closely enough. */
    fun gesture(touchedAt: Long, now: Long): Boolean = touchedAt > 0 && now - touchedAt in 0..GESTURE_MS

    /** Pure: a touch counts only as a click (a short press that didn't move), never a scroll or a long drag. */
    fun click(downAt: Long, upAt: Long, dx: Float, dy: Float, slop: Float): Boolean =
        upAt - downAt in 0..600 && dx * dx + dy * dy <= slop * slop

    private val http by lazy { dev.apollo.net.NetGate.builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).followRedirects(false).build() }
    private const val MAX_BYTES = 8 * 1024 * 1024

    private val SHIM = """
        (function(){ if (window.apollo) return; var n = window.ApolloNative, taps = { n: 0 };
          window.__apolloTap = function(id, r){ var f = taps[id]; delete taps[id]; if (f) f(r); };
          window.apollo = {
            sql: function(q){ return JSON.parse(n.sql(String(q))); },
            tap: function(label, data){ var id = ++taps.n; return new Promise(function(res){ taps[id] = res;
              n.tap(String(label), JSON.stringify(data === undefined ? null : data), String(id)); }); },
            spotify: function(kind, args){ var id = ++taps.n; return new Promise(function(res){ taps[id] = res;
              n.spotify(String(kind), JSON.stringify(args || {}), String(id)); }); },
            fetch: function(url){ return fetch(url).then(function(r){ if (!r.ok) throw new Error('HTTP ' + r.status); return r.text(); }); },
            json: function(url){ return window.apollo.fetch(url).then(JSON.parse); },
            location: function(){ return JSON.parse(n.location()); },
            open: function(url){ n.open(String(url)); },
            allows: function(key){ return !!(n.allows && n.allows(String(key))); },
            allow: function(key){ return Promise.resolve({ ok: false, detail: "page not ready" }); },
            photo: function(source, cb){ window.zeusPhoto = function(r){ window.zeusPhoto = null; if (cb) cb(r); }; window.zeusNative.photo(String(source || 'pick')); },
            app: n.app()
          };
        })();
    """.trimIndent()

    @Volatile private var shimJs: String? = null

    /** The apollo shim, then apollo.pixel (assets/pixel/pixel.js): one script every page gets first. */
    private fun shim(c: Context): String = shimJs ?: (SHIM + "\n" + runCatching { c.assets.open("pixel/pixel.js").use { it.readBytes().decodeToString() } }.getOrDefault(""))
        .also { shimJs = it }

    @SuppressLint("SetJavaScriptEnabled")
    /**
     * [voice]: only a full page may ask for the mic (apps/VoiceAsk); widgets never. [pinned]: a Home widget's app; the
     * view stays on it, and a link to another app goes to [openApp] (the native screen, secure window and all).
     */
    fun configure(w: WebView, c: Context, onTap: ((String) -> Unit)? = null, voice: Boolean = false, pinned: String? = null,
                  openApp: (String) -> Unit = {}) {
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.allowFileAccess = false
        w.settings.allowContentAccess = false
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        val bridge = Bridge(c.applicationContext, onTap, fullPage = voice).also { it.view = w }
        w.addJavascriptInterface(bridge, "ApolloNative")
        val slop = android.view.ViewConfiguration.get(c).scaledTouchSlop.toFloat()
        var down = 0L
        var downX = 0f
        var downY = 0f
        w.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { down = e.eventTime; downX = e.x; downY = e.y }
                MotionEvent.ACTION_UP -> if (click(down, e.eventTime, e.x - downX, e.y - downY, slop)) bridge.touchedAt = e.eventTime
            }
            false
        }
        // Zeus's page kit (assets/kit/zeus.js) talks to zeusNative: the mini apps Athena built in Zeus run unchanged.
        w.addJavascriptInterface(ZeusBridge(c.applicationContext, bridge, voice, pinned, openApp).also { it.view = w }, "zeusNative")
        w.webViewClient = Client(c.applicationContext, bridge, pinned, openApp)
    }

    /** Pure: whether a view pinned to [pinned] (null: not pinned) may be on [app]'s pages. */
    fun stays(pinned: String?, app: String?): Boolean = pinned == null || app == pinned

    fun url(app: String, path: String) = "${AppFiles.ORIGIN}/$app/$path"

    /**
     * A link out of a page is phone_do open_url (a URL can carry what the page read off his phone): his open_url rule
     * decides, a tap by default.
     */
    private fun openUrl(c: Context, url: String) {
        if (!links.allow(url, System.currentTimeMillis())) return
        CoroutineScope(Dispatchers.IO).launch { dev.apollo.actions.PhoneActions.run(c, askId(), "open_url", JSONObject().put("url", url)) }
    }
    private val links = AskThrottle()
    private val asks = java.util.concurrent.atomic.AtomicLong()
    /** Unique even within one millisecond, so one pending ask never overwrites another. */
    private fun askId() = "page-${System.currentTimeMillis()}-${asks.incrementAndGet()}"

    private class Client(val c: Context, val bridge: Bridge, val pinned: String?, val openApp: (String) -> Unit) : WebViewClient() {
        private fun app(url: String) = AppFiles.parse(url)?.first?.takeIf { stays(pinned, it) }

        override fun shouldInterceptRequest(view: WebView, r: WebResourceRequest): WebResourceResponse {
            val url = r.url.toString()
            if (r.method != "GET") return blocked()
            if (url == "${AppFiles.ORIGIN}/_apollo.js") return WebResourceResponse("application/javascript", "UTF-8", ByteArrayInputStream(shim(c).toByteArray()))
            // The built-in pixel characters (assets/pixel, docs/PIXEL.md), for apollo.pixel.
            Regex("""^${Regex.escape(AppFiles.ORIGIN)}/_pixel/([a-z0-9-]{1,40})\.json$""").find(url)?.let { m ->
                return runCatching { WebResourceResponse("application/json", "UTF-8", c.assets.open("pixel/${m.groupValues[1]}.json")) }.getOrElse { blocked() }
            }
            zeusRoute(url)?.let { return it }
            AppFiles.parse(url)?.let { (app, path) ->
                // A pinned Home view never shows another app's page, however it got there (a load from the bridge skips
                // shouldOverrideUrlLoading): that app opens natively, with its secure window.
                if (r.isForMainFrame && !stays(pinned, app)) return blocked()
                val secure = app != bridge.app && MiniApps.all(c).any { it.name == app && it.secure }
                fun ok(media: Boolean) = AppFiles.crossAppOk(app, bridge.app, r.isForMainFrame, secure, media)
                if (path.startsWith("__json/")) return (if (ok(false)) jsonWidget(app, path.removePrefix("__json/")) else null) ?: blocked()
                // His recordings and photos live in the app's phone folder (PhoneFiles), media only.
                val f = AppFiles.resolve(MiniApps.root(c), app, path)?.takeIf { ok(false) }
                    ?: PhoneFiles.media(c, app, path)?.takeIf { ok(true) } ?: return blocked()
                return serve(f)
            }
            if (AppFiles.allowed(url, runCatching { ApolloDb.get(c).fetchAllow() }.getOrDefault(emptySet()))) return proxy(url)
            return blocked()
        }

        override fun shouldOverrideUrlLoading(view: WebView, r: WebResourceRequest): Boolean {
            val url = r.url.toString()
            AppFiles.parse(url)?.let { (app, _) ->
                if (stays(pinned, app)) return false
                if (r.isForMainFrame && r.hasGesture()) openApp(app)
                return true
            }
            if (url.startsWith("https://")) openUrl(c, url)
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { bridge.app = app(url); bridge.allowToken = null }

        // Every frame sees ApolloNative, but evaluateJavascript runs in the main frame only: Dawn's main page alone gets
        // the token apollo.allow needs, kept in a closure (a frame from another origin can't read it).
        override fun onPageFinished(view: WebView, url: String) {
            if (!bridge.fullPage || bridge.app != "dawn") return
            val t = java.util.UUID.randomUUID().toString().also { bridge.allowToken = it }
            view.evaluateJavascript("""(function(t){ var n = window.ApolloNative, a = window.apollo; if (!a || !n) return;
              var id = 1e6, pend = {}, base = window.__apolloTap;
              window.__apolloTap = function(k, r){ var f = pend[k]; if (f) { delete pend[k]; f(r); } else if (base) base(k, r); };
              a.allow = function(key){ var i = ++id; return new Promise(function(res){ pend[i] = res; n.allow(String(key), String(i), t); }); };
            })('$t')""", null)
        }
        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) { bridge.app = app(url) }

        /** Zeus's absolute routes: /kit/<x>.js|css (the kit, from assets), /files/<p> (this app's file), /tile/... (a street map tile, MapTiles). */
        private fun zeusRoute(url: String): WebResourceResponse? {
            val u = runCatching { java.net.URI(url) }.getOrNull() ?: return null
            if (u.scheme != "https" || u.host != AppFiles.HOST) return null
            val p = u.path ?: return null
            return when {
                Regex("""^/kit/[a-z0-9-]+\.(js|css)$""").matches(p) -> runCatching {
                    WebResourceResponse(if (p.endsWith(".js")) "application/javascript" else "text/css", "UTF-8", c.assets.open(p.removePrefix("/")))
                }.getOrElse { blocked() }
                p.startsWith("/files/") -> bridge.app?.let { a -> (AppFiles.resolve(MiniApps.root(c), a, p.removePrefix("/files/")) ?: PhoneFiles.media(c, a, p.removePrefix("/files/")))?.takeIf { it.name != "data.db" }?.let { serve(it) } } ?: blocked()
                p.startsWith("/tile/") -> MapTiles.parse(p)?.let { (z, x, y) -> MapTiles.get(c, z, x, y) }?.let {
                    WebResourceResponse("image/png", null, 200, "OK", mapOf("Cache-Control" to "max-age=604800"), ByteArrayInputStream(it))
                } ?: blocked()
                else -> null
            }
        }

        private fun jsonWidget(app: String, path: String): WebResourceResponse? {
            val f = AppFiles.resolve(MiniApps.root(c), app, path) ?: return null
            val spec = runCatching { org.json.JSONObject(f.readText()) }.getOrNull() ?: return null
            val title = MiniApps.all(c).firstOrNull { it.name == app }?.title ?: app
            val html = inject(WidgetJson.html(spec, File(MiniApps.root(c), "$app/data.db"), title))
            return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray()))
        }

        private fun serve(f: File): WebResourceResponse {
            val type = AppFiles.mime(f.name)
            val body: ByteArray = when (f.extension.lowercase()) {
                // Markdown is rendered; every HTML page gets the apollo shim first, before its own scripts.
                "md" -> page("<article>" + Markdown.html(f.readText()) + "</article>").toByteArray()
                "html", "htm" -> inject(f.readText()).toByteArray()
                else -> f.readBytes()
            }
            val text = type.startsWith("text/") || type.endsWith("javascript") || type.endsWith("json") || type.endsWith("svg+xml")
            return WebResourceResponse(type, if (text) "UTF-8" else null, 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(body))
        }

        private fun proxy(start: String): WebResourceResponse = runCatching {
            var url = start
            repeat(4) {
                val res = http.newCall(Request.Builder().url(url).header("User-Agent", "Apollo (personal Android app)").build()).execute()
                res.use { r ->
                    if (r.code in 300..399) {
                        val next = r.header("Location")?.let { java.net.URL(java.net.URL(url), it).toString() } ?: return blocked()
                        if (!AppFiles.allowed(next, ApolloDb.get(c).fetchAllow())) return blocked()
                        url = next
                        return@repeat
                    }
                    val bytes = r.body?.bytes() ?: ByteArray(0)
                    if (bytes.size > MAX_BYTES) return blocked()
                    val type = r.header("Content-Type")?.substringBefore(';')?.trim() ?: "application/octet-stream"
                    return WebResourceResponse(type, if (type.startsWith("image/")) null else "UTF-8", r.code, r.message.ifBlank { "OK" },
                        mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"), ByteArrayInputStream(bytes))
                }
            }
            blocked()
        }.getOrElse { blocked() }

        private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }

    /** The shim goes in first, right after <head> (or at the very top when the page has none). */
    fun inject(html: String): String {
        val tag = "<script src=\"${AppFiles.ORIGIN}/_apollo.js\"></script>"
        val i = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
        return if (i != null) html.substring(0, i.range.last + 1) + tag + html.substring(i.range.last + 1) else tag + html
    }

    fun page(body: String) = inject("""<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>:root{color-scheme:dark}body{margin:0;padding:20px;background:#1C1A17;color:#F2EDE4;font:16px/1.5 Roboto,system-ui,sans-serif}
        h1,h2,h3{font-weight:600;margin:1.2em 0 .4em}a{color:#E8A33D}code,pre{background:#26231F;border-radius:6px;padding:2px 6px}pre{padding:12px;overflow:auto}
        li{margin:.2em 0}</style></head><body>$body</body></html>""")

    /** The approval key a home tile tap is checked against, by the device's kind. Pure. */
    fun homeAction(kind: dev.apollo.house.Kind?): String = when (kind) {
        dev.apollo.house.Kind.LIGHT -> "lights"
        dev.apollo.house.Kind.FAN -> "fan"
        dev.apollo.house.Kind.PLUG, dev.apollo.house.Kind.SWITCH -> "plug"
        else -> "home_tap"
    }

    /** The home app's tile tap {id, on}: his own, so the phone carries it out (House.tap keeps the hard block). Pure. */
    fun homeTap(app: String, label: String, id: String?, on: Boolean?): Pair<String, Boolean>? =
        if (app == "home" && label == "home" && !id.isNullOrBlank() && on != null) id to on else null

    /** Muse's page asking for a Spotify control (`apollo.tap("spotify", {do, ...})`): the same args as phone_do spotify. Pure. */
    fun museTap(app: String, label: String, cmd: dev.apollo.muse.Muse.Command?): Boolean = app == "muse" && label == "spotify" && cmd != null

    /**
     * Muse's page reading his Spotify (`apollo.spotify(kind, args)`): the server's read-only route and its query, or null
     * for another app or kind. Pure.
     */
    fun museRead(app: String?, kind: String?, a: Map<String, Any?>): Pair<String, Map<String, String>>? {
        if (app != "muse") return null
        fun str(k: String, d: String = "") = (a[k] as? String)?.trim()?.take(200) ?: d
        fun int(k: String, d: Int) = (a[k] as? Number)?.toInt() ?: d
        return when (kind) {
            "search" -> "/v1/spotify/search" to mapOf("q" to str("query"), "type" to str("type", "track"), "limit" to int("limit", 10).coerceIn(1, 10).toString())
            "playlists" -> "/v1/spotify/playlists" to emptyMap()
            "playlist_tracks" -> "/v1/spotify/playlist_tracks" to mapOf("playlist" to str("playlist"), "offset" to int("offset", 0).coerceAtLeast(0).toString())
            else -> null
        }
    }

    /** What a page may call. [app] is the app whose page is on screen (set from its URL), so sql only opens that app's db. */
    class Bridge(private val c: Context, private val onTap: ((String) -> Unit)?, val fullPage: Boolean = false) {
        @Volatile var app: String? = null
        /** Set for Dawn's main frame each load (Client.onPageFinished); apollo.allow must carry it. */
        @Volatile var allowToken: String? = null
        @Volatile var view: WebView? = null
        /** His last click on this view (uptime ms): one tap or write right after it marks gesture, and uses it up. */
        @Volatile var touchedAt = 0L
        private val main = Handler(Looper.getMainLooper())
        private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Synchronized fun gesture(): Boolean = gesture(touchedAt, SystemClock.uptimeMillis()).also { if (it) touchedAt = 0L }

        @JavascriptInterface fun app(): String = app ?: ""

        @JavascriptInterface fun sql(q: String?): String {
            val a = app ?: return "[]"
            if (q == null || !AppFiles.readOnly(q)) return JSONObject().put("error", "read-only SELECT only").toString()
            val f = File(MiniApps.root(c), "$a/data.db")
            if (!f.isFile) return "[]"
            return runCatching {
                SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery(q, null).use { cur ->
                        val out = JSONArray()
                        var n = 0
                        while (cur.moveToNext() && n++ < 2000) {
                            val row = JSONObject()
                            for (i in 0 until cur.columnCount) row.put(cur.getColumnName(i), when (cur.getType(i)) {
                                android.database.Cursor.FIELD_TYPE_INTEGER -> cur.getLong(i)
                                android.database.Cursor.FIELD_TYPE_FLOAT -> cur.getDouble(i)
                                android.database.Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                                android.database.Cursor.FIELD_TYPE_BLOB -> JSONObject.NULL
                                else -> cur.getString(i)
                            })
                            out.put(row)
                        }
                        out.toString()
                    }
                }
            }.getOrElse { JSONObject().put("error", it.javaClass.simpleName).toString() }
        }

        /** A page_tap fact for Athena; a home tile tap is also carried out, and [cb] tells the page how it went. */
        @JavascriptInterface fun tap(label: String?, data: String?, cb: String?) {
            val a = app ?: return
            val l = (label ?: "").take(80)
            val d = runCatching { JSONObject(data ?: "null") }.getOrNull()
            val fact = JSONObject().put("app", a).put("label", l).put("data", d ?: JSONObject.NULL).put("gesture", gesture())
            onTap?.invoke(l)
            if (d != null && museTap(a, l, dev.apollo.muse.Muse.Command.of(d))) {
                // His rule for spotify decides, as for phone_do; a device list is a read, so no fact for it.
                work.launch {
                    val (ok, detail) = dev.apollo.actions.PhoneActions.run(c, askId(), "spotify", d)
                    if (d.optString("do") != "devices") Facts.post(c, "page_tap", fact.put("ok", ok).put("detail", detail), urgent = true)
                    answer(cb, ok, detail)
                }
                return
            }
            val home = homeTap(a, l, d?.optString("id"), d?.takeIf { it.has("on") }?.optBoolean("on"))
            if (home == null) { Facts.post(c, "page_tap", fact, urgent = true); answer(cb, true, ""); return }
            work.launch {
                // A page is Athena's to rewrite, so its tap gets no more than phone_do would: his rule for that kind of
                // device decides (free runs, never refuses, anything else waits for his own tap or fingerprint).
                val device = House.get(c).state.value.devices.firstOrNull { it.id == home.first }
                val action = homeAction(device?.kind)
                val level = dev.apollo.approvals.Approvals(ApolloDb.get(c)).levelFor(dev.apollo.actions.ActionRegistry.key(action))
                val (ok, detail) = when {
                    device == null -> false to "Google Home doesn't list that device right now"
                    level == dev.apollo.approvals.Level.NEVER -> false to "refused: $action is on Owner's never list"
                    level != dev.apollo.approvals.Level.FREE ->
                        dev.apollo.actions.PhoneActions.run(c, askId(), action, JSONObject().put("device_id", device.id).put("device", device.name).put("on", home.second))
                    else -> runCatching { House.tap(c, home.first, Command.Power(home.second)); true to "done" }
                        .getOrElse { false to (it.message ?: it.javaClass.simpleName) }
                }
                Facts.post(c, "page_tap", fact.put("ok", ok).put("detail", detail), urgent = true)
                answer(cb, ok, detail)
            }
        }

        /** Muse's read of his Spotify through the server (its Web API sign-in lives there); [cb] gets {ok, detail: JSON list or why}. */
        @JavascriptInterface fun spotify(kind: String?, args: String?, cb: String?) {
            val j = runCatching { JSONObject(args ?: "{}") }.getOrDefault(JSONObject())
            val (path, query) = museRead(app, kind, j.keys().asSequence().associateWith { j.opt(it) })
                ?: return answer(cb, false, "only Muse's page reads Spotify: search, playlists or playlist_tracks")
            work.launch {
                val (ok, detail) = runCatching { JSONObject(dev.apollo.net.Server(c).getJson(path, query)) }.fold(
                    { j -> if (j.optBoolean("ok")) true to (j.optJSONArray("items") ?: JSONArray()).toString() else false to j.optString("detail", "Spotify didn't answer") },
                    { false to "Apollo's server didn't answer (${it.message ?: it.javaClass.simpleName})" })
                answer(cb, ok, detail)
            }
        }

        private fun answer(cb: String?, ok: Boolean, detail: String) {
            if (cb == null || !Regex("^[0-9]{1,9}$").matches(cb)) return
            val r = JSONObject().put("ok", ok).put("detail", detail).toString()
            main.post { view?.evaluateJavascript("window.__apolloTap && window.__apolloTap($cb, $r)", null) }
        }

        /** Last known position, rounded to about 1 km: enough for weather, never a precise trail. */
        @JavascriptInterface fun location(): String = runCatching {
            val lm = c.getSystemService(LocationManager::class.java)
            val l = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER)
                .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }.maxByOrNull { it.time }
            if (l == null) "null" else JSONObject().put("lat", Math.round(l.latitude * 100) / 100.0).put("lon", Math.round(l.longitude * 100) / 100.0).put("at", l.time).toString()
        }.getOrDefault("null")

        /** Whether Dawn's page gets an Allow button for this step key. */
        @JavascriptInterface fun allows(key: String?): Boolean = dev.apollo.senses.Allow.forPage(app, fullPage, key, dev.apollo.senses.Allow.declared(c)) != null

        /**
         * Dawn's Allow: Android's own prompt or settings screen for one allowlisted key (senses/Allow), one at a time. What
         * he picks there is his decision, so it needs no approval; [cb] gets {ok, detail}, the step's status after.
         */
        @JavascriptInterface fun allow(key: String?, cb: String?, token: String?) {
            if (!dev.apollo.senses.Allow.tokenOk(allowToken, token)) return answer(cb, false, "only Dawn's own page can ask")
            val a = app
            val way = dev.apollo.senses.Allow.forPage(a, fullPage, key, dev.apollo.senses.Allow.declared(c)) ?: return answer(cb, false, "not something Apollo can ask for here")
            if (a == null || key == null || dev.apollo.senses.Allow.ask.value != null || !allowAsks.allow(key, System.currentTimeMillis())) return answer(cb, false, "busy")
            dev.apollo.senses.Allow.ask.value = dev.apollo.senses.Allow.Ask(a, key, way) { ok, detail -> answer(cb, ok, detail) }
        }
        private val allowAsks = AskThrottle(sameMs = 3_000, perMinute = 10)

        @JavascriptInterface fun open(url: String?) {
            if (url == null || !url.startsWith("https://")) return
            openUrl(c, url)
        }
    }
}
