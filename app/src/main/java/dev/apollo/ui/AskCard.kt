package dev.apollo.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.apollo.actions.PhoneActions
import dev.apollo.approvals.Level
import dev.apollo.senses.NoticeActions

/**
 * How an ask reads on its card. Every action shows its authoritative target fields, each labelled (who it goes to, which
 * page, which number, how much and to whom), even when missing; free text such as "why" sits apart in [notes] and can never
 * stand in for a target. Args the card doesn't know are listed too, so nothing the phone will act on is hidden. Pure.
 */
object Asks {
    /** One labelled line; [bold] is the part to emphasise (a URL's host). */
    data class Field(val label: String, val value: String, val bold: IntRange? = null)
    data class Words(val title: String, val fields: List<Field>, val notes: List<Field>, val allow: String)

    private const val MISSING = "not given"
    private val FREE = setOf("why")

    /** [sender] is the phone's own record of a reply's notification (title, app); null when it's gone. */
    fun words(p: PhoneActions.Pending, sender: Pair<String, String>? = null): Words =
        words(p.action, p.args.keys().asSequence().associateWith { k -> p.args.opt(k)?.takeIf { it != org.json.JSONObject.NULL }?.toString().orEmpty() }, sender)

    fun words(action: String, a: Map<String, String>, sender: Pair<String, String>? = null): Words {
        // A key counts as shown only when its own value is the one on the card: an alias that isn't shown (payee AND to)
        // falls through to the extra lines below, so nothing the phone acts on is hidden.
        val used = mutableSetOf<String>()
        fun v(vararg keys: String) = keys.firstOrNull { k -> !a[k].isNullOrBlank() }?.let { k -> used += k; a[k] }
        fun f(label: String, vararg keys: String) = Field(label, v(*keys) ?: MISSING)
        fun take(vararg keys: String) { used += keys }
        val (what, allow, fields) = when (action) {
            "reply" -> {
                take("key")
                val to = sender?.let { (t, app) -> listOf(t, app).filter { it.isNotBlank() }.joinToString(" in ") }?.ifBlank { null } ?: "a notification that is gone"
                Triple("send a reply", "Send", listOf(Field("To", to), f("Button", "label"), f("Text", "text")))
            }
            "message", "send_message", "send", "email", "send_email" -> {
                val email = "email" in action
                Triple(if (email) "send an email" else "send a message", "Send",
                    listOfNotNull(f("To", "to"), if (email || "subject" in a) f("Subject", "subject") else null, f(if (email) "Body" else "Text", "body", "text")))
            }
            "call_person" -> Triple("make a call", "Call", listOf(f("Name", "name"), f("Number", "number")))
            "open_url" -> {
                val url = v("url")
                val host = url?.let { runCatching { java.net.URI(it).host }.getOrNull() }
                val i = if (url != null && host != null) url.indexOf(host) else -1
                Triple("open a page", "Open", listOf(Field("Page", url ?: MISSING, if (i >= 0) i until i + host!!.length else null)))
            }
            "spotify" -> if (a["do"] in listOf(null, "play")) Triple("play music", "Play", listOf(f("Spotify", "uri")))
                else Triple("control Spotify", "Allow", listOf(f("Do", "do"), f("Spotify", "uri", "device", "on")))
            // Names are the phone's own look-up (muse/SpotifyPlaylists), never her text; the raw ids follow when it failed.
            "spotify_playlist" -> when (a["op"]) {
                "create" -> Triple("make a Spotify playlist", "Create", listOf(f("Name", "name"), f("Public", "public")))
                "reorder" -> Triple("reorder a Spotify playlist", "Allow", listOf(f("Playlist", "playlist_name"), f("Moves", "track_names"), f("To before position", "insert_before")))
                "remove" -> Triple("remove songs from a Spotify playlist", "Remove", listOf(f("Playlist", "playlist_name"), f("Songs", "track_names")))
                "add" -> Triple("add songs to a Spotify playlist", "Add", listOf(f("Playlist", "playlist_name"), f("Songs", "track_names")))
                else -> Triple("change a Spotify playlist", "Allow", listOf(f("Playlist", "playlist_name")))
            }
            // "opens" is the phone's own lookup (PhoneActions.resolve), so the card shows what really opens.
            "open_app" -> Triple("open an app", "Open", listOf(f("Opens", "opens"), f("Asked for", "app", "label", "package")))
            "open_settings" -> Triple("open a settings page", "Open", listOf(f("Opens", "opens"), f("Asked for", "page")))
            "lights", "fan", "plug" -> {
                val on = v("on")?.let { if (it.toBoolean()) "On" else "Off" } ?: v("state") ?: MISSING
                Triple(if (on == MISSING) "change the $action" else "turn the $action ${on.lowercase()}", "Allow", listOf(f("Device", "device", "room"), Field("Turn", on)))
            }
            "money", "pay", "buy", "send_money", "transfer" -> {
                Triple("spend money", "Pay", listOf(f("Amount", "amount"), f("Payee", "payee", "to")))
            }
            else -> Triple(action.replace('_', ' '), "Allow", emptyList())
        }
        val rest = a.keys.filter { it !in used && it !in FREE }.sorted()
            .map { k -> Field(k.replace('_', ' ').replaceFirstChar { it.uppercase() }, a[k].orEmpty().ifBlank { MISSING }) }
        val notes = FREE.mapNotNull { k -> v(k)?.let { Field(k.replaceFirstChar { it.uppercase() }, it) } }
        return Words("Athena wants to $what", fields + rest, notes, allow)
    }
}

tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** One ask, Muse-style: what she wants to do, exactly what and to whom, and Deny / Allow (Allow takes his fingerprint when the level says so). */
@Composable
fun AskCard(p: PhoneActions.Pending, onSky: Boolean = false) {
    val c = LocalContext.current
    val w = Asks.words(p, if (p.action == "reply") NoticeActions.sender(p.args.optString("key")) else null)
    val ink = if (onSky) Tone.onSky else Tone.ink
    val dim = if (onSky) Tone.onSkyDim else Tone.quiet
    val shape = RoundedCornerShape(Tone.radius)
    Column(
        (if (onSky) Modifier.glass(shape, strong = true) else Modifier.background(Tone.raised, shape).border(1.dp, Tone.line, shape)).fillMaxWidth().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(10.dp).background(Tone.alarm))
            Column {
                Text(w.title, style = Type.body.copy(fontWeight = FontWeight.SemiBold, color = ink))
                if (p.level == Level.FINGERPRINT) Text("Needs your fingerprint", style = Type.small.copy(color = dim))
            }
        }
        if (w.fields.isNotEmpty()) Column(
            Modifier.fillMaxWidth().border(1.dp, if (onSky) Color.White.copy(alpha = .3f) else Tone.line, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            w.fields.forEach { f ->
                Column {
                    Text(f.label, style = Type.small.copy(color = dim))
                    Text(buildAnnotatedString {
                        append(f.value)
                        f.bold?.let { r -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), r.first, r.last + 1) }
                    }, style = Type.body.copy(color = ink))
                }
            }
        }
        w.notes.forEach { n -> Text("${n.label}: ${n.value}", style = Type.small.copy(color = dim)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("Deny", false, onSky, Modifier.weight(1f)) { c.activity()?.let { PhoneActions.answer(it, p.id, false) {} } }
            Choice(w.allow, true, onSky, Modifier.weight(1f)) { c.activity()?.let { PhoneActions.answer(it, p.id, true) {} } }
        }
    }
}

@Composable
fun Choice(label: String, primary: Boolean, onSky: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg = when { primary && onSky -> Tone.ink; primary -> Tone.saffron; onSky -> Color.White.copy(alpha = .2f); else -> Tone.soft }
    val fg = when { primary && onSky -> Tone.ground; primary -> Tone.onAccent; onSky -> Tone.onSky; else -> Tone.ink }
    Text(label, style = Type.body.copy(fontWeight = FontWeight.SemiBold, color = fg), modifier = modifier
        .background(bg, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}
