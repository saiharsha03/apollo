package dev.apollo.apps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.apollo.ui.Action
import dev.apollo.ui.Tone
import dev.apollo.ui.Type
import dev.apollo.ui.glass
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A page's voice note. A page can't be told apart from a script, so it only asks: Apollo draws its own sheet, and only his
 * tap on Record opens the mic; while it records, Apollo's own banner shows with Stop. Widgets never get the mic.
 */
object VoiceAsk {
    /** [refused]: why there is no note (back to the page); [started]: the mic is on; [stop]: the page's own stop, which hands it the note. */
    class Ask(val app: String, val refused: (String) -> Unit, val started: () -> Unit = {}, val stop: () -> Unit)
    val ask = MutableStateFlow<Ask?>(null)
    val recording = MutableStateFlow<Ask?>(null)

    fun clear(app: String) {
        if (ask.value?.app == app) ask.value = null
        if (recording.value?.app == app) recording.value = null
    }

    /** A page's recording ended without its Stop (his private page opened): the banner goes and the page hears why. */
    fun ended(app: String, why: String) {
        val r = recording.value?.takeIf { it.app == app } ?: return
        recording.value = null
        r.refused(why)
    }
}

/** Over a page of [app]: the Record / Cancel sheet when it asks, the Recording banner while it records. */
@Composable
fun VoiceAskLayer(app: String) {
    val c = LocalContext.current
    val ask by VoiceAsk.ask.collectAsState()
    val rec by VoiceAsk.recording.collectAsState()
    ask?.takeIf { it.app == app }?.let { a ->
        val title = MiniApps.all(c).firstOrNull { it.name == app }?.title ?: app
        val cancel = { VoiceAsk.ask.value = null; a.refused("cancelled") }
        Dialog(onDismissRequest = cancel) {
            Column(Modifier.fillMaxWidth().background(Tone.raised, RoundedCornerShape(24.dp)).padding(20.dp)) {
                Text("$title wants to record", style = Type.heading)
                Spacer(Modifier.height(6.dp))
                Text("A voice note, kept on your phone. Its words come from Sarvam.", style = Type.small)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Action("Cancel", primary = false, onClick = cancel)
                    Spacer(Modifier.width(10.dp))
                    Action("Record") {
                        VoiceAsk.ask.value = null
                        val why = AppVoice.start(c, app)
                        if (why != null) a.refused(why) else { VoiceAsk.recording.value = a; a.started() }
                    }
                }
            }
        }
    }
    rec?.takeIf { it.app == app }?.let { r ->
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopCenter) {
            Row(Modifier.fillMaxWidth().glass(strong = true).padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Recording", style = Type.body.copy(color = Tone.alarm), modifier = Modifier.weight(1f))
                Action("Stop") { r.stop() }
            }
        }
    }
}
