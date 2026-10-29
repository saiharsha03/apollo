package dev.apollo.apps

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.apollo.calypso.Agent
import dev.apollo.calypso.CalypsoCore
import dev.apollo.calypso.ItemKind
import dev.apollo.calypso.SecureWindow
import dev.apollo.clio.Clio
import dev.apollo.ui.Action
import dev.apollo.ui.Tone
import dev.apollo.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * "Just for me" pages in his journal (Calliope; Owner, 2026-10-25), carried over from Zeus (apps/PrivateNote.kt). Her
 * app's page can't be trusted with them, since she writes its code, so this is Apollo's own screen over hers: what he
 * types or says here is sealed straight into Calypso (a note, and the recording as a file) and never reaches her page,
 * her tables, the server or the facts. He reads it in Calypso, with his fingerprint.
 */
object PrivateNote {
    /** The app it was opened from, or null when closed. */
    val open = MutableStateFlow<String?>(null)

    /** [audio]: the recording's .m4a bytes, in memory only. */
    fun keep(c: Context, text: String, audio: ByteArray?) {
        val at = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MMM d, h:mm a"))
        val drop = CalypsoCore.get(c).calypso.drop
        if (text.isNotBlank()) drop.deposit(Agent.OWNER, ItemKind.NOTE, "Journal, $at", text.toByteArray(), null)
        audio?.let { drop.deposit(Agent.OWNER, ItemKind.FILE, "Journal voice, $at", it, "journal-$at.m4a") }
        Clio.info("apps", "private journal page kept in Calypso")
    }
}

@Composable
fun PrivateNoteScreen(close: () -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    var audio by remember { mutableStateOf<ByteArray?>(null) }
    var said by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    BackHandler { AppVoice.stopIfPrivate(); close() }
    // No screenshots or recents preview while it's open, as in Calypso; and any page's mic ends now.
    SecureWindow()
    DisposableEffect(Unit) { AppVoice.dropPages(); onDispose { AppVoice.stopIfPrivate() } }
    // Screen off or away: what he wrote is sealed into Calypso and the page closes, so it never sits readable on a
    // phone someone picks up (scan F13). Nothing is lost; he writes on in a new page.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) {
        if (busy) return@LifecycleEventEffect
        busy = true
        val words = text
        text = ""
        val rec = recording
        recording = false
        scope.launch {
            withContext(Dispatchers.IO) {
                val voice = if (rec) AppVoice.stopPrivate(ctx).first else audio
                if (words.isNotBlank() || voice != null) PrivateNote.keep(ctx, words, voice)
            }
            close()
        }
    }
    Column(Modifier.fillMaxSize().background(Tone.ground).imePadding().padding(horizontal = 20.dp, vertical = 24.dp)) {
        Text("Just for you", style = Type.title)
        Text("Sealed into Calypso. Athena never sees it, and nothing here leaves the phone.", style = Type.small, modifier = Modifier.padding(bottom = 12.dp))
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 240.dp).weight(1f, fill = false), textStyle = Type.body)
        said?.let { Text(it, style = Type.small, modifier = Modifier.padding(top = 8.dp)) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 14.dp)) {
            Action(if (recording) "Stop" else if (audio != null) "Record again" else "Speak", primary = false) {
                if (!recording) {
                    val e = AppVoice.start(ctx, AppVoice.PRIVATE)
                    said = when (e) { null -> { recording = true; "Listening." }; "needs_mic" -> "Apollo needs the microphone (Settings > Apps > Apollo)."; else -> e }
                } else scope.launch {
                    recording = false; said = "Keeping the recording."
                    val n = withContext(Dispatchers.IO) { AppVoice.stopPrivate(ctx) }
                    audio = n.first; said = n.second
                }
            }
            Action("Keep in Calypso", enabled = !busy) {
                busy = true
                scope.launch {
                    // A recording still running is finished and kept with the words, not dropped.
                    withContext(Dispatchers.IO) {
                        if (recording) AppVoice.stopPrivate(ctx).first?.let { audio = it }
                        PrivateNote.keep(ctx, text, audio)
                    }
                    recording = false
                    close()
                }
            }
            Action("Cancel", primary = false) { audio = null; AppVoice.stopIfPrivate(); close() }
        }
    }
}
