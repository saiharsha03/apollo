package dev.apollo.lemnos

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AColor
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.apollo.ui.Tone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

/**
 * One live terminal (Zeus's TermLink): the WebSocket to `tmux attach` on the server, bridged to xterm.js in a WebView.
 * The page itself never touches the network; it only sees bytes handed over here.
 */
class TermLink(private val c: Context, private val session: String, private val readOnly: Boolean, pass: Map<String, String>?, private val onStatus: (String) -> Unit) {
    /** The server's one-time pass for the next connection (a signed challenge or a new shell's ticket). */
    @Volatile private var pass: Map<String, String>? = pass
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var ws: WebSocket? = null
    @Volatile var web: WebView? = null
    @Volatile private var cols = 80
    @Volatile private var rows = 24
    @Volatile private var started = false
    /** The keys row's Ctrl: the next letter typed becomes its control code. */
    @Volatile var ctrl = false

    private fun js(call: String) { web?.let { w -> w.post { w.evaluateJavascript(call, null) } } }
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun css(x: androidx.compose.ui.graphics.Color) = "#%06X".format(x.toArgb() and 0xFFFFFF)

    /** Called by the page once it has measured itself, so tmux attaches at the right size. */
    fun start() {
        if (started) return
        val p = pass ?: run { onStatus("Tap Reconnect: your fingerprint opens it"); return }
        pass = null
        started = true
        onStatus("Connecting")
        scope.launch {
            try {
                ws = Lemnos(c).terminal(session, readOnly, cols, rows, p, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) = onStatus(if (readOnly) "Watching" else "Live")
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val o = runCatching { JSONObject(text) }.getOrNull() ?: return
                        if (o.optString("t") == "history") js("zh('${b64(o.optString("d").toByteArray(Charsets.UTF_8))}')")
                    }
                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) = js("zw('${b64(bytes.toByteArray())}')")
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { onStatus("Closed"); started = false }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        onStatus(if (response?.code == 403) "The server refused it: tap Reconnect" else "Dropped: ${t.message ?: t.javaClass.simpleName}"); started = false
                    }
                })
            } catch (e: Exception) { onStatus("Couldn't attach: ${e.message}"); started = false }
        }
    }

    fun send(data: String) {
        var d = data
        if (ctrl && d.length == 1 && d[0].lowercaseChar() in 'a'..'z') { d = (d[0].uppercaseChar().code - 64).toChar().toString(); ctrl = false }
        ws?.send(JSONObject().put("t", "in").put("d", d).toString())
    }

    /** A page of the session's scrollback (tmux copy mode): up, down, or end to go back to live. */
    fun scroll(dir: String) { ws?.send(JSONObject().put("t", "scroll").put("dir", dir).toString()) }

    fun resize(c: Int, r: Int) {
        cols = c; rows = r
        if (ws == null) start() else ws?.send(JSONObject().put("t", "resize").put("cols", c).put("rows", r).toString())
    }

    fun reconnect(p: Map<String, String>) { close(); js("zclear()"); pass = p; start() }

    /** The keyboard came up or went down: the page holds the session's size meanwhile. */
    fun hold(on: Boolean) = js("zhold($on)")

    fun close() { ws?.close(1000, null); ws = null; started = false }

    /** What the page calls. */
    inner class Bridge {
        @JavascriptInterface fun ready() {
            val sel = Tone.saffron.let { "rgba(${(it.red * 255).toInt()},${(it.green * 255).toInt()},${(it.blue * 255).toInt()},0.35)" }
            js("zinit({bg:'${css(Tone.mine)}',fg:'${css(Tone.ink)}',accent:'${css(Tone.saffron)}',sel:'$sel',size:12})")
        }
        @JavascriptInterface fun input(d: String) = send(d)
        @JavascriptInterface fun resize(c: Int, r: Int) = this@TermLink.resize(c, r)
    }
}

/** The special keys the soft keyboard lacks. Sent as the bytes a terminal would send. */
val TERM_KEYS = listOf(
    "Esc" to "\u001b", "Tab" to "\t", "Ctrl" to "", "Up" to "\u001b[A", "Down" to "\u001b[B", "Left" to "\u001b[D", "Right" to "\u001b[C",
    "^C" to "\u0003", "PgUp" to "\u001b[5~", "PgDn" to "\u001b[6~", "Enter" to "\r",
)

@SuppressLint("SetJavaScriptEnabled")
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TerminalPane(link: TermLink, modifier: Modifier = Modifier) {
    val ime = WindowInsets.isImeVisible
    LaunchedEffect(link, ime) { link.hold(ime) }
    Box(modifier.background(Tone.mine)) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(AColor.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.blockNetworkLoads = true      // the page loads its own assets only
                    settings.allowFileAccess = false       // android_asset still works
                    settings.allowContentAccess = false
                    webViewClient = WebViewClient()
                    addJavascriptInterface(link.Bridge(), "Apollo")
                    link.web = this
                    loadUrl("file:///android_asset/lemnos/term.html")
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
fun KeysRow(link: TermLink) {
    var ctrl by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // Scroll the session's own history in any program (Claude Code and Codex included), then Live to type again.
        Chip("Scroll up", false) { link.scroll("up") }
        Chip("Scroll down", false) { link.scroll("down") }
        Chip("Live", false) { link.scroll("end") }
        for ((label, bytes) in TERM_KEYS) {
            if (label == "Ctrl") Chip("Ctrl", ctrl) { ctrl = !ctrl; link.ctrl = ctrl }
            else Chip(label, false) { link.send(bytes); if (ctrl) { ctrl = false; link.ctrl = false } }
        }
    }
}

@Composable
fun rememberTermLink(c: Context, session: String, readOnly: Boolean, pass: Map<String, String>?, onStatus: (String) -> Unit): TermLink {
    val link = remember(session, readOnly) { TermLink(c, session, readOnly, pass, onStatus) }
    DisposableEffect(link) { onDispose { link.close(); link.web?.destroy(); link.web = null } }
    return link
}
