package dev.apollo.ask

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.apollo.clio.Clio
import dev.apollo.hermes.Hermes
import dev.apollo.chat.messageOf
import dev.apollo.net.ChatMessage
import dev.apollo.net.LocalDb
import dev.apollo.net.Server
import dev.apollo.ui.ApolloTheme
import dev.apollo.ui.Tone
import dev.apollo.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** One line in the sheet: his words, or the answer (Hermes's or Athena's). */
data class AskLine(val mine: Boolean, val text: String, val quiet: Boolean = false)

/**
 * The Ask Athena sheet, over any app: from the widget, the side key (Apollo as the device assistant) or a tap. He types
 * (the keyboard is up at once) or speaks ([LISTEN]: the mic starts at once). Speech only fills the box: nothing runs
 * or is sent until he taps Send (another app's audio could be what was heard). His own commands run through Hermes,
 * offline; anything else goes to Athena's chat (tagged as voice when it came from speech), and her answer is read out
 * loud and lands in that chat too.
 */
class AskActivity : ComponentActivity() {
    private val lines = mutableStateListOf<AskLine>()
    private val text = mutableStateOf("")
    private val listening = mutableStateOf(false)
    private val busy = mutableStateOf(false)
    private val useScreen = mutableStateOf(false)
    /** The box holds a transcript he hasn't edited: sent as source=voice. */
    private var fromVoice = false
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var player: android.media.MediaPlayer? = null
    private val voiceMic = dev.apollo.voice.Mic()
    private var thread: String? = null
    private var retry: String? = null

    private val mic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) listen() }
    private val contacts = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> retry?.takeIf { ok }?.let { send(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale.US }
        if (intent.getBooleanExtra(LISTEN, false)) listen()
        setContent {
            ApolloTheme {
                val focus = FocusRequester()
                Box(Modifier.fillMaxSize().clickable(onClick = ::finish), contentAlignment = Alignment.BottomCenter) {
                    Column(
                        Modifier.fillMaxWidth().widthIn(max = 640.dp).clickable(interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}
                            .background(Tone.raised, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                            .navigationBarsPadding().imePadding().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("Ask Athena", style = Type.heading)
                        // "What's this?": the screen he came from, sent only if he taps it (and never a banking or password app).
                        val shown by ScreenText.now.collectAsState()
                        shown?.let { s ->
                            if (s.private) Text("${s.app} is private: its screen isn't read", style = Type.small)
                            else if (s.text.isNotEmpty()) Pill(if (useScreen.value) "Using ${s.app}'s screen" else "Use what's on screen (${s.app})", live = useScreen.value) { useScreen.value = !useScreen.value }
                        }
                        LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(lines) { l -> Line(l) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            BasicTextField(text.value, { text.value = it; fromVoice = false }, textStyle = Type.body, cursorBrush = SolidColor(Tone.saffron), singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { submit() }),
                                modifier = Modifier.weight(1f).focusRequester(focus).background(Tone.soft, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 13.dp),
                                decorationBox = { inner -> if (text.value.isEmpty()) Text(if (listening.value) "Listening" else "Type or tap Speak", style = Type.body.copy(color = Tone.quiet)); inner() })
                            Pill(if (listening.value) "Stop" else "Speak", live = listening.value) { if (listening.value) { voiceMic.stop(); recognizer?.stopListening() } else listen() }
                            Pill("Send", enabled = text.value.isNotBlank() && !busy.value) { submit() }
                        }
                    }
                }
                LaunchedEffect(Unit) { if (!intent.getBooleanExtra(LISTEN, false)) runCatching { focus.requestFocus() } }
            }
        }
    }

    /** The widget's mic or the side key again while the sheet is up. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(LISTEN, false)) listen()
    }

    @androidx.compose.runtime.Composable
    private fun Line(l: AskLine) = Column(Modifier.fillMaxWidth(), horizontalAlignment = if (l.mine) Alignment.End else Alignment.Start) {
        Text(l.text, style = Type.body.copy(color = if (l.mine) Tone.ink else if (l.quiet) Tone.quiet else Tone.ink),
            modifier = Modifier.background(if (l.mine) Tone.mine else Tone.soft, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 9.dp))
    }

    @androidx.compose.runtime.Composable
    private fun Pill(word: String, enabled: Boolean = true, live: Boolean = false, onClick: () -> Unit) =
        Text(word, style = Type.body.copy(fontWeight = FontWeight.SemiBold, color = if (!enabled) Tone.quiet else Tone.onAccent),
            modifier = Modifier.background(if (!enabled) Tone.soft else if (live) Tone.alarm else Tone.saffron, RoundedCornerShape(20.dp))
                .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp))

    private fun submit() {
        val t = text.value.trim()
        if (t.isEmpty() || busy.value) return
        text.value = ""
        send(t, fromVoice)
        fromVoice = false
    }

    /** Hermes first (offline, instant); anything that isn't a command goes to Athena. Only ever from his tap. */
    private fun send(t: String, voice: Boolean = false) {
        lines += AskLine(true, t)
        busy.value = true
        lifecycleScope.launch {
            val said = withContext(Dispatchers.IO) { Hermes.handle(applicationContext, t) }
            if (said != null) {
                lines += AskLine(false, said.text, quiet = !said.ok)
                busy.value = false
                if (said.needs != null && checkSelfPermission(said.needs) != PackageManager.PERMISSION_GRANTED) { retry = t; contacts.launch(said.needs) }
                return@launch
            }
            ask(t, voice)
            busy.value = false
        }
    }

    /** Into Athena's chat (one thread per sheet), then her reply from that thread, read out loud. */
    private suspend fun ask(said: String, voice: Boolean) {
        val c = applicationContext
        val t = ScreenText.now.value?.takeIf { useScreen.value && !it.private && it.text.isNotEmpty() }?.let { s -> useScreen.value = false; ScreenText.attach(said, s) } ?: said
        val sent = withContext(Dispatchers.IO) {
            runCatching {
                val (th, id) = Server(c).chat(thread, Ask.title(t), t, if (thread == null) "athena" else null, source = if (voice) "voice" else null)
                LocalDb.get(c).putMessage(ChatMessage(id, th, "owner", t, System.currentTimeMillis()), Ask.title(t), "athena")
                thread = th
                th to id
            }
        }.getOrElse { lines += AskLine(false, "Not sent: ${it.message ?: it.javaClass.simpleName}", quiet = true); return }
        lines += AskLine(false, "Athena is thinking", quiet = true)
        val until = System.currentTimeMillis() + WAIT_MS
        var since = sent.second
        while (System.currentTimeMillis() < until) {
            delay(1500)
            val replies = withContext(Dispatchers.IO) {
                runCatching {
                    val a = Server(c).thread(sent.first, since)
                    (0 until a.length()).map { messageOf(a.getJSONObject(it), sent.first) }
                        .onEach { LocalDb.get(c).putMessage(it, agent = "athena") }
                }.getOrDefault(emptyList())
            }
            replies.lastOrNull()?.let { since = it.id }
            val hers = replies.filter { it.from == "athena" }
            if (hers.isNotEmpty()) {
                lines.removeAll { it.quiet && it.text == "Athena is thinking" }
                hers.forEach { lines += AskLine(false, it.text) }
                speak(hers.joinToString(" ") { it.text })
                return
            }
        }
        lines.removeAll { it.quiet && it.text == "Athena is thinking" }
        lines += AskLine(false, "Athena is still on it. Her answer will be in the chat.", quiet = true)
    }

    /** Her Sarvam voice when the server answers within 3 s, else Android's. */
    private suspend fun speak(s: String) {
        val said = Ask.spoken(s)
        val wav = withContext(Dispatchers.IO) { dev.apollo.voice.SarvamVoice.speech(applicationContext, said) }
        if (isDestroyed) return
        player = wav?.let { dev.apollo.voice.SarvamVoice.play(this, it, android.media.AudioAttributes.USAGE_ASSISTANT) }
        if (player == null) tts?.speak(said, TextToSpeech.QUEUE_FLUSH, null, "ask")
    }

    private fun stopSpeaking() {
        tts?.stop()
        runCatching { player?.stop(); player?.release() }
        player = null
    }

    /** Sarvam's words when it is up (recorded until he stops talking), else Android's recognizer. */
    private fun listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { mic.launch(Manifest.permission.RECORD_AUDIO); return }
        if (listening.value) return
        stopSpeaking()
        if (!dev.apollo.voice.SarvamVoice.likely()) return androidListen()
        listening.value = true
        lifecycleScope.launch {
            val said = withContext(Dispatchers.IO) {
                val pcm = voiceMic.record(applicationContext)
                if (pcm.isEmpty()) "" else dev.apollo.voice.SarvamVoice.words(applicationContext, pcm)
            }
            listening.value = false
            when {
                said == null -> { lines += AskLine(false, "Sarvam didn't answer: say it again", quiet = true); androidListen() }
                said.isNotBlank() -> { text.value = said; fromVoice = true }     // shown, never sent: his tap on Send (scan F5)
            }
        }
    }

    private fun androidListen() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { lines += AskLine(false, "No speech recognizer on this phone", quiet = true); return }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { listening.value = true }
                override fun onPartialResults(partial: Bundle?) { partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { text.value = it; fromVoice = true } }
                override fun onResults(results: Bundle?) {
                    listening.value = false
                    // Shown, never sent: his tap on Send is the only way it goes (scan F5).
                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { text.value = it; fromVoice = true }
                }
                override fun onError(error: Int) { listening.value = false; Clio.info("ask", "listen", "error $error") }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
        }
    }

    override fun onDestroy() {
        voiceMic.stop()
        stopSpeaking()
        recognizer?.destroy()
        tts?.shutdown()
        AssistSessionService.close()
        super.onDestroy()
    }

    companion object {
        const val LISTEN = "listen"
        private const val WAIT_MS = 90_000L

        fun open(c: Context, listen: Boolean) = c.startActivity(intent(c, listen))

        fun intent(c: Context, listen: Boolean): Intent = Intent(c, AskActivity::class.java).putExtra(LISTEN, listen)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

object Ask {
    /** A new thread's title: the start of what he asked. Pure. */
    fun title(text: String): String = text.trim().lineSequence().first().take(48).ifEmpty { "Ask" }

    /** What TTS reads: markdown marks and links out, long answers cut at a sentence near 600 characters. Pure. */
    fun spoken(text: String): String {
        val plain = text.replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1").replace(Regex("[*_`#>]+"), "").replace(Regex("https?://\\S+"), "").replace(Regex("\\s+"), " ").trim()
        if (plain.length <= 600) return plain
        val cut = plain.take(600)
        return cut.substring(0, (cut.lastIndexOf(". ") + 1).takeIf { it > 200 } ?: 600)
    }
}
