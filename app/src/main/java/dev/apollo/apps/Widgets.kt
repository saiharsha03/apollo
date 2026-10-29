package dev.apollo.apps

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.apollo.R
import dev.apollo.net.Facts
import dev.apollo.ui.ApolloTheme
import dev.apollo.ui.Row2
import dev.apollo.ui.Tone
import dev.apollo.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * The one Apollo widget (docs/PLAN.md, Widgets): each placed copy shows what it was set up to show, an app's
 * widgets/<w>.html or Dynamic (the app.json "dynamic" page Athena marks), in any style she draws. The page is rendered
 * off-screen at the copy's exact size to a picture, and 9 invisible zones map to the page's data-tap elements. A copy
 * that can't be drawn keeps its last picture.
 */
object Widgets {
    const val DYNAMIC = "dynamic"
    private const val ZONES = 3
    private val ZONE_IDS = intArrayOf(R.id.z0, R.id.z1, R.id.z2, R.id.z3, R.id.z4, R.id.z5, R.id.z6, R.id.z7, R.id.z8)
    private fun p(c: Context) = c.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun choice(c: Context, id: Int): String? = p(c).getString("w$id", null)
    fun choose(c: Context, id: Int, what: String) = p(c).edit().putString("w$id", what).apply()
    fun forget(c: Context, id: Int) = p(c).edit().remove("w$id").apply()

    /** "app/path" for a choice; Dynamic is the first app that marks a dynamic page. */
    fun target(c: Context, choice: String?): Pair<String, String>? = when {
        choice == null -> null
        choice == DYNAMIC -> MiniApps.all(c).firstOrNull { it.dynamic != null }?.let { it.name to it.dynamic!! }
        else -> choice.substringBefore('/') to choice.substringAfter('/')
    }

    /** The grid cell (0..8) a point falls in, for a page of w x h. Pure. */
    fun zone(x: Float, y: Float, w: Int, h: Int): Int =
        (y / h * ZONES).toInt().coerceIn(0, ZONES - 1) * ZONES + (x / w * ZONES).toInt().coerceIn(0, ZONES - 1)

    /** For each zone, the label of the data-tap element under its centre (rects in page px), or null. Pure. */
    fun labels(rects: List<Pair<String, FloatArray>>, w: Int, h: Int): Array<String?> = Array(ZONES * ZONES) { z ->
        val cx = (z % ZONES + 0.5f) * w / ZONES; val cy = (z / ZONES + 0.5f) * h / ZONES
        rects.lastOrNull { (_, r) -> cx >= r[0] && cx <= r[2] && cy >= r[1] && cy <= r[3] }?.first
    }

    fun refresh(c: Context) {
        WorkManager.getInstance(c).enqueueUniqueWork("apollo-widgets-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<WidgetWorker>().build())
    }

    fun schedule(c: Context) {
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("apollo-widgets", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WidgetWorker>(15, TimeUnit.MINUTES).build())
    }

    fun ids(c: Context): IntArray = AppWidgetManager.getInstance(c).getAppWidgetIds(ComponentName(c, ApolloWidget::class.java))

    /** A zone with no label, or data-tap="open" (the seed widgets' whole body), opens the app; any other label is a page_tap. Pure. */
    fun opensApp(label: String?): Boolean = label == null || label == "open"

    /** Draws one copy. Main thread (WebView). */
    suspend fun draw(c: Context, id: Int) {
        dev.apollo.pixel.PixelWidget.agentOf(choice(c, id))?.let { return dev.apollo.pixel.PixelWidget.draw(c, id, it) }
        val awm = AppWidgetManager.getInstance(c)
        val t = target(c, choice(c, id)) ?: return
        val o = awm.getAppWidgetOptions(id)
        val d = c.resources.displayMetrics.density
        val wPx = (o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 160) * d).toInt().coerceIn(48, 1400)
        val hPx = (o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 160) * d).toInt().coerceIn(48, 1400)
        val result = withContext(Dispatchers.Main) { withTimeoutOrNull(20_000) { render(c, t.first, t.second, wPx, hPx) } } ?: return
        val (bmp, labels) = result
        val views = RemoteViews(c.packageName, R.layout.apollo_widget)
        views.setImageViewBitmap(R.id.widget_image, bmp)
        ZONE_IDS.forEachIndexed { z, zid ->
            val label = labels[z]
            val intent = if (!opensApp(label)) Intent(c, ApolloWidget::class.java).setAction(ApolloWidget.TAP).putExtra("app", t.first).putExtra("label", label).putExtra("id", id)
                .let { PendingIntent.getBroadcast(c, id * 16 + z, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
            else c.packageManager.getLaunchIntentForPackage(c.packageName)?.putExtra("open_app", t.first)
                ?.let { PendingIntent.getActivity(c, id * 16 + z, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
            if (intent != null) views.setOnClickPendingIntent(zid, intent)
        }
        awm.updateAppWidget(id, views)
    }

    private suspend fun render(c: Context, app: String, path: String, w: Int, h: Int): Pair<Bitmap, Array<String?>>? {
        if (!File(MiniApps.root(c), "$app/$path").isFile) return null
        // Zeus's widgets/<w>.json: drawn as HTML by WidgetJson, served under __json/.
        val load = if (path.endsWith(".json")) "__json/$path" else path
        val web = WebView(c.applicationContext)
        try {
            AppWeb.configure(web, c)
            web.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            web.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            web.layout(0, 0, w, h)
            val loaded = suspendCancellableCoroutine { cont ->
                val base = web.webViewClient
                web.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, r: android.webkit.WebResourceRequest) = base.shouldInterceptRequest(view, r)
                    override fun shouldOverrideUrlLoading(view: WebView, r: android.webkit.WebResourceRequest) = true
                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = base.onPageStarted(view, url, favicon)
                    override fun onPageFinished(view: WebView, url: String) { if (cont.isActive) cont.resume(true) }
                }
                web.loadUrl(AppWeb.url(app, load))
            }
            if (!loaded) return null
            delay(600)   // fonts, images and the page's first paint after load
            val rects = suspendCancellableCoroutine { cont ->
                web.evaluateJavascript("""(function(){var d=window.devicePixelRatio||1,o=[];document.querySelectorAll('[data-tap]').forEach(function(e){var r=e.getBoundingClientRect();o.push([e.getAttribute('data-tap'),r.left*d,r.top*d,r.right*d,r.bottom*d])});return JSON.stringify(o)})()""") { v ->
                    cont.resume(runCatching { JSONArray(JSONArray("[$v]").getString(0)) }.getOrNull())
                }
            }
            val list = rects?.let { a -> List(a.length()) { i -> a.getJSONArray(i).let { r -> r.getString(0).take(80) to floatArrayOf(r.getDouble(1).toFloat(), r.getDouble(2).toFloat(), r.getDouble(3).toFloat(), r.getDouble(4).toFloat()) } } }.orEmpty()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            web.draw(Canvas(bmp))
            return bmp to labels(list, w, h)
        } finally { web.destroy() }
    }
}

class WidgetWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        for (id in Widgets.ids(applicationContext)) runCatching { Widgets.draw(applicationContext, id) }
        return Result.success()
    }
}

class ApolloWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, awm: AppWidgetManager, ids: IntArray) { Widgets.schedule(c); Widgets.refresh(c) }
    override fun onAppWidgetOptionsChanged(c: Context, awm: AppWidgetManager, id: Int, o: Bundle) = Widgets.refresh(c)
    override fun onDeleted(c: Context, ids: IntArray) { ids.forEach { Widgets.forget(c, it) } }

    override fun onReceive(c: Context, intent: Intent) {
        super.onReceive(c, intent)
        if (intent.action != TAP) return
        val app = intent.getStringExtra("app") ?: return
        val label = intent.getStringExtra("label") ?: return
        val id = intent.getIntExtra("id", -1)
        val button = label.removePrefix("zb:").toIntOrNull()?.takeIf { label.startsWith("zb:") }
        if (button == null) { Facts.post(c, "page_tap", JSONObject().put("app", app).put("label", label).put("from", "widget").put("gesture", true), urgent = true); return }
        // A Zeus-format button: its row into one of the app's tables (applied by the server) and/or a word to Athena.
        val target = Widgets.target(c, Widgets.choice(c, id))?.takeIf { it.first == app } ?: return
        val b = runCatching { JSONObject(java.io.File(MiniApps.root(c), "$app/${target.second}").readText()).optJSONArray("buttons")?.optJSONObject(button) }.getOrNull() ?: return
        b.optJSONObject("insert")?.let { ins ->
            val table = ins.optString("table").takeIf { Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$").matches(it) } ?: return@let
            val values = ins.optJSONObject("values") ?: JSONObject()
            val cols = values.keys().asSequence().filter { Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$").matches(it) }.toList()
            if (cols.isNotEmpty()) Facts.post(c, "page_write", JSONObject().put("app", app)
                .put("sql", "INSERT INTO $table(${cols.joinToString(",")}) VALUES(${cols.joinToString(",") { "?" }})")
                .put("params", org.json.JSONArray(cols.map { values.opt(it)?.toString() })).put("gesture", true), urgent = true)
        }
        val tell = b.opt("tell")
        if (tell == true || tell is String) Facts.post(c, "page_tell", JSONObject().put("app", app).put("text", "he tapped \"${b.optString("label")}\"" + ((tell as? String)?.let { ": ${it.take(300)}" } ?: "")), urgent = true)
        Widgets.refresh(c)
    }

    companion object { const val TAP = "dev.apollo.WIDGET_TAP" }
}

/** Setting up a copy: what it shows. Each of an app's widgets, or Dynamic (Athena picks what matters now). */
class WidgetSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        val options = listOf(Widgets.DYNAMIC to "Dynamic: Athena picks what matters now") +
            dev.apollo.pixel.PixelWidget.choices.map { (a, label) -> dev.apollo.pixel.PixelWidget.PREFIX + a to label } +
            MiniApps.all(this).flatMap { a -> a.widgets.map { w -> "${a.name}/$w" to "${a.title}: ${w.substringAfterLast('/').substringBeforeLast('.')}" } }
        setContent {
            ApolloTheme {
                Column(Modifier.fillMaxSize().background(Tone.ground).padding(20.dp)) {
                    Spacer(Modifier.height(24.dp))
                    Text("What should this widget show?", style = Type.title)
                    Spacer(Modifier.height(12.dp))
                    LazyColumn { items(options, key = { it.first }) { (value, label) -> Row2(label) { pick(id, value) } } }
                }
            }
        }
    }

    private fun pick(id: Int, value: String) {
        Widgets.choose(this, id, value)
        Widgets.schedule(this)
        Widgets.refresh(this)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        finish()
    }
}
