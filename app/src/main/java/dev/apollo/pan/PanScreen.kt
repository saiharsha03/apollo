package dev.apollo.pan

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.apollo.calypso.AndroidBiometric
import dev.apollo.net.Server
import dev.apollo.ui.Action
import dev.apollo.ui.Row2
import dev.apollo.ui.Tone
import dev.apollo.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One thing Pan wrote: [ts] in ms, kind postcard, diary, reply or status. Another AI's words: shown as plain text only. */
data class PanLine(val ts: Long, val kind: String, val text: String, val place: String? = null)

/** Pan's feed on its screen (Zeus's PanScreen): the latest postcard on top, then the rest newest first, by Boston day. Pure. */
object PanFeed {
    private val SHOWN = setOf("postcard", "diary", "reply", "status")

    fun of(a: org.json.JSONArray?): List<PanLine> = shown((0 until (a?.length() ?: 0)).mapNotNull { i ->
        a!!.optJSONObject(i)?.let { o -> PanLine(o.optLong("ts"), o.optString("kind"), o.optString("text"), o.optString("place").takeIf { it.isNotBlank() && it != "null" }) }
    })

    fun shown(items: List<PanLine>) = items.filter { it.kind in SHOWN && it.text.isNotBlank() }

    fun top(items: List<PanLine>): PanLine? = items.filter { it.kind == "postcard" }.maxByOrNull { it.ts }

    /** The rest, newest first, grouped by day; a reply's own "Diary: ..." tail is cut (the same lines come as a diary item). */
    fun days(items: List<PanLine>, zone: java.time.ZoneId): List<Pair<java.time.LocalDate, List<PanLine>>> {
        val top = top(items)
        return items.filter { it != top }
            .map { if (it.kind == "reply") it.copy(text = it.text.substringBefore(" Diary:").trim()) else it }
            .sortedByDescending { it.ts }
            .groupBy { java.time.Instant.ofEpochMilli(it.ts).atZone(zone).toLocalDate() }.toList()
    }
}

/**
 * Pan, the Plazmi resident (Zeus's PanTalk, PanKey, PanLimits): say something to it, its daily token limit and pause,
 * claiming it on plazmi.gg, and its own Anthropic key. Limits, claim and key need his fingerprint. What Pan writes is in
 * the Plazmi app; another AI's words, shown as plain text.
 */
@Composable
fun PanScreen(open: (String) -> Unit = {}) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val bio = remember { AndroidBiometric(c) }
    var state by remember { mutableStateOf<JSONObject?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }
    var say by remember { mutableStateOf("") }
    var tokens by remember { mutableStateOf("") }
    var paused by remember { mutableStateOf(false) }
    var phrase by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    LaunchedEffect(reload) {
        state = withContext(Dispatchers.IO) { runCatching { Server(c).pan("GET") }.getOrNull() }
        state?.optJSONObject("limits")?.let { tokens = it.optLong("daily_tokens").toString(); paused = it.optBoolean("paused") }
        if (state == null) note = "The server didn't answer."
    }

    /** One server call off the main thread; [after] gets the answer, the note says what went wrong. */
    fun run(after: (JSONObject) -> String, call: (Server) -> JSONObject) {
        scope.launch {
            note = withContext(Dispatchers.IO) { runCatching { after(call(Server(c))) }.getOrElse { "Not done: ${it.message}" } }
            reload++
        }
    }

    fun withFinger(title: String, go: () -> Unit) = bio.authenticate(title, "Pan, on your server", null, go) { note = it }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp)) {
        Text("Pan", style = Type.title)
        Text("The Plazmi resident on your server. What it writes is in the Plazmi app.", style = Type.small)
        note?.let { Spacer(Modifier.height(8.dp)); Text(it, style = Type.small.copy(color = Tone.alarm)) }
        Row2("Open Pan's diary", "The Plazmi mini app", "Open") { open("app:plazmi") }

        // What Pan wrote: plain text, nothing in it is a link or a button.
        val feed = remember(state) { PanFeed.of(state?.optJSONArray("feed")) }
        PanFeed.top(feed)?.let { p ->
            Spacer(Modifier.height(16.dp))
            Text("LATEST POSTCARD", style = Type.kicker)
            Text(listOfNotNull(p.place, dev.apollo.chat.Chat.time(p.ts)).joinToString(", "), style = Type.small)
            Text(p.text, style = Type.body)
        }
        PanFeed.days(feed, dev.apollo.ui.Sun.ZONE).forEach { (day, lines) ->
            Spacer(Modifier.height(16.dp))
            Text(day.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d")).uppercase(), style = Type.kicker)
            lines.forEach { l ->
                Spacer(Modifier.height(8.dp))
                Text(l.kind.replaceFirstChar { it.uppercase() } + ", " + dev.apollo.chat.Chat.time(l.ts), style = Type.small)
                Text(l.text, style = Type.body)
            }
        }
        if (state != null && feed.isEmpty()) Text("Nothing from Pan yet.", style = Type.small)

        Spacer(Modifier.height(20.dp))
        Text("Say something", style = Type.heading)
        state?.optJSONArray("said")?.let { a ->
            for (i in maxOf(0, a.length() - 5) until a.length()) a.optJSONObject(i)?.let { m -> Row2(m.optString("text"), m.optString("from")) }
        }
        OutlinedTextField(say, { say = it.take(1000) }, label = { Text("To Pan") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Action("Send", enabled = say.isNotBlank()) { val t = say; say = ""; run({ "Sent. Pan reads it when it next wakes." }) { it.pan("POST", "/say", JSONObject().put("text", t)) } }

        Spacer(Modifier.height(24.dp))
        Text("Daily limit", style = Type.heading)
        Text("Tokens a day before Pan stops itself, and a pause.", style = Type.small)
        OutlinedTextField(tokens, { tokens = it.filter(Char::isDigit).take(10) }, label = { Text("Tokens a day") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (paused) "Paused: Pan sleeps until you unpause it" else "Awake", style = Type.body, modifier = Modifier.weight(1f))
            Switch(paused, { paused = it })
        }
        Action("Save limits", enabled = tokens.isNotBlank()) {
            val body = JSONObject().put("daily_tokens", tokens.toLong()).put("paused", paused)
            withFinger("Change Pan's limits") { run({ "Limits saved." }) { it.pan("PUT", "/limits", body) } }
        }

        Spacer(Modifier.height(24.dp))
        Text("Claim Pan", style = Type.heading)
        Text("Opens Pan's claim link on plazmi.gg. The page shows three words: type them here and Pan confirms you.", style = Type.small)
        Spacer(Modifier.height(8.dp))
        Action("Open claim link", primary = false) {
            withFinger("Claim Pan") {
                scope.launch {
                    val link = withContext(Dispatchers.IO) { runCatching { Server(c).pan("POST", "/claim").optString("link") }.getOrElse { note = "Not done: ${it.message}"; null } }
                    if (link != null && link.startsWith("https://plazmi.gg/")) c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    else if (link != null) note = "No claim link yet."
                }
            }
        }
        OutlinedTextField(phrase, { phrase = it.take(80) }, label = { Text("The three words") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Action("Confirm", enabled = phrase.trim().split(Regex("\\s+")).size == 3) {
            val p = phrase
            withFinger("Confirm you're Pan's human") { phrase = ""; run({ "Given to Pan's broker; it confirms you once." }) { it.pan("POST", "/confirm", JSONObject().put("phrase", p)) } }
        }

        Spacer(Modifier.height(24.dp))
        Text("Pan's key", style = Type.heading)
        Text("Pan's own Anthropic key (not Athena's). Stored on the server for Pan's broker only; never shown again.", style = Type.small)
        OutlinedTextField(key, { key = it.trim() }, label = { Text("sk-ant-...") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation())
        Spacer(Modifier.height(8.dp))
        Action("Set key", enabled = key.startsWith("sk-ant-")) {
            val k = key
            withFinger("Set Pan's key") { key = ""; run({ if (it.optBoolean("restarted")) "Key set; Pan restarted." else "Key set." }) { it.pan("PUT", "/key", JSONObject().put("key", k)) } }
        }
        Spacer(Modifier.height(32.dp))
    }
}
