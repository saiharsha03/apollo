package dev.apollo.pixel

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.apollo.net.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/** The latest activity per agent: filled by Sync's "activity" route, kept in prefs so the widget and a restart see it. */
object PixelFeed {
    private const val PREFS = "pixel"
    private val flow = MutableStateFlow<Map<String, Activity>>(emptyMap())
    @Volatile private var loaded = false

    fun state(c: Context): StateFlow<Map<String, Activity>> {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val saved = Activity.AGENTS.mapNotNull { a -> p.getString(a, null)?.let { runCatching { Activity.of(JSONObject(it)) }.getOrNull() } }
                flow.update { now -> saved.associateBy { it.agent } + now }
                loaded = true
            }
        }
        return flow
    }

    fun latest(c: Context, agent: String): Activity? = state(c).value[agent]

    /** How often the screen asks the server while Apollo is in front (sync only runs on FCM wakes and on resume). */
    const val POLL_MS = 3_000L

    /** While Apollo is resumed: GET /v1/activity every POLL_MS into the feed. Nothing runs in the background. */
    @Composable
    fun Poll() {
        val c = LocalContext.current.applicationContext
        val owner = LocalLifecycleOwner.current
        LaunchedEffect(owner) {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    runCatching { withContext(Dispatchers.IO) { Server(c).activity() } }.getOrNull()?.let { all ->
                        all.keys().forEach { k -> all.optJSONObject(k)?.let { post(c, it) } }
                    }
                    delay(POLL_MS)
                }
            }
        }
    }

    /** One outbox item or poll row. An older one than what is shown (a late sync), or the same one again, is dropped. */
    fun post(c: Context, data: JSONObject) {
        val a = Activity.of(data) ?: return
        val cur = latest(c, a.agent)
        if (cur != null && (cur.at > a.at || cur == a)) return
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(a.agent, data.toString()).apply()
        flow.update { it + (a.agent to a) }
        runCatching { PixelWidget.redraw(c, a.agent) }
    }
}
