package dev.apollo.ossa

import android.Manifest
import android.app.KeyguardManager
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.apollo.ui.Action
import dev.apollo.ui.ApolloTheme
import dev.apollo.ui.Tone
import dev.apollo.ui.Type
import java.util.Locale

/**
 * The call screen, over the lock screen from the call notification. Ringing: Answer, Decline, Send as message. Live:
 * a talk ([CallTalk]): Athena's text is spoken in her Sarvam voice when the server answers within 3 s, else Android's
 * own; then the phone listens (Sarvam when it is up, else Android's recognizer), posts his words to her call thread,
 * waits for her reply and speaks it, until either hangs up. On a locked phone the reason and her text stay hidden and
 * the phone doesn't listen by itself: Reply and "call me back" ask for the unlock first (scan F15), so whoever holds a
 * locked phone can't talk to her as him.
 */
class OssaCallActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null
    private var player: android.media.MediaPlayer? = null
    private val mic = dev.apollo.voice.Mic()
    /** Android's recognizer while it listens: Done stops it, teardown destroys it. */
    private var recognizer: SpeechRecognizer? = null
    private var spoken = false
    private val talk = CallTalk()
    /** Each spoken line's number: a finished line moves the talk on only if it is still the latest. */
    private var line = 0
    private var hers by mutableStateOf("")
    private var heard by mutableStateOf("")
    private var waiting by mutableStateOf(false)
    private var listening by mutableStateOf(false)
    private var locked by mutableStateOf(true)
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) listen() else heardWords("") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(ANSWER, false)) Ossa.answer(this)
        setContent {
            ApolloTheme {
                val ui by Ossa.ui.collectAsState()
                val cur = ui
                if (cur == null) { finish(); return@ApolloTheme }
                if (cur.live && !spoken) { spoken = true; hers = cur.ask.text; say(cur.ask.text) }
                Column(Modifier.fillMaxSize().background(Tone.ground).safeDrawingPadding().padding(24.dp)) {
                    Text("Athena", style = Type.title)
                    Text(if (cur.live) "On the call" else if (locked) "Calling" else "Calling: ${cur.ask.reason}", style = Type.small)
                    Spacer(Modifier.height(24.dp))
                    if (cur.live) {
                        Text(if (locked) "Unlock to read what she says or to reply." else hers, style = Type.body)
                        if (waiting) Text("Athena is thinking", style = Type.small)
                        Spacer(Modifier.height(20.dp))
                        if (heard.isNotBlank()) Text("You: $heard", style = Type.body.copy(color = Tone.saffron))
                        Spacer(Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Action(if (listening) "Done" else "Reply") { if (listening) { mic.stop(); recognizer?.stopListening() } else if (!waiting) unlocked { reply() } }
                            Action("Hang up") { talk.hungUp(); Ossa.end(this@OssaCallActivity, Ending.TALKED) }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Action("Answer") { Ossa.answer(this@OssaCallActivity) }
                            Action("Decline") { Ossa.end(this@OssaCallActivity, Ending.DECLINED) }
                        }
                        Spacer(Modifier.height(12.dp))
                        Action("Send as message") { Ossa.end(this@OssaCallActivity, Ending.AS_MESSAGE) }
                        Spacer(Modifier.height(12.dp))
                        Text("Call me back", style = Type.small)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OssaRules.LATER_MIN.forEach { m -> Action("$m min", primary = false) { unlocked { Ossa.later(this@OssaCallActivity, m) } } }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        locked = keyguard().isKeyguardLocked
    }

    private fun keyguard() = getSystemService(KeyguardManager::class.java)

    /** Runs [then] at once on an unlocked phone; on a locked one only after he unlocks (his credential, not a swipe). */
    private fun unlocked(then: () -> Unit) {
        if (!keyguard().isKeyguardLocked) { locked = false; return then() }
        keyguard().requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { locked = false; then() }
        })
    }

    /** Speaks one line of the talk; when it finishes, the talk moves on ([next]). */
    private fun say(text: String) {
        val n = ++line
        val done = { runOnUiThread { if (n == line && !isDestroyed) next() } }
        Thread {
            val wav = dev.apollo.voice.SarvamVoice.speech(this, dev.apollo.ask.Ask.spoken(text))
            runOnUiThread {
                if (isDestroyed || n != line) return@runOnUiThread
                stopSpeaking()
                player = wav?.let { dev.apollo.voice.SarvamVoice.play(this, it, android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION) { done() } }
                if (player != null) Ossa.voice = "sarvam" else androidSpeak(text, done)
            }
        }.start()
    }

    /** After a line: the end, or his turn (by itself only on an unlocked phone; locked, Reply unlocks first). */
    private fun next() {
        if (Ossa.ui.value?.live != true) return
        when (talk.spoke()) {
            CallTalk.Phase.ENDED -> Ossa.end(this, Ending.TALKED)
            CallTalk.Phase.LISTENING -> if (!keyguard().isKeyguardLocked) reply()
            else -> {}
        }
    }

    /** What he said (blank: nothing): to her and back, another listen, or the phone's goodbye. */
    private fun heardWords(text: String) {
        if (Ossa.ui.value?.live != true) return
        if (text.isNotBlank()) heard = text
        talk.heard(text)?.let { bye -> hers = bye; return say(bye) }
        if (talk.phase == CallTalk.Phase.LISTENING) return reply()
        waiting = true
        Thread {
            val r = Ossa.ask(this, text)
            runOnUiThread {
                waiting = false
                if (isDestroyed || Ossa.ui.value?.live != true) return@runOnUiThread
                val words = talk.answered(r)
                hers = words
                say(words)
            }
        }.start()
    }

    private fun androidSpeak(text: String, done: () -> Unit) {
        tts?.let { return androidSay(it, text, done) }
        tts = TextToSpeech(this) { status ->
            Ossa.voice = tts?.defaultEngine.orEmpty()
            val t = tts
            if (status == TextToSpeech.SUCCESS && t != null) {
                t.language = Locale.US
                androidSay(t, text, done)
            } else { Ossa.failed(this, "speak", "tts_init_$status"); done() }
        }
    }

    private fun androidSay(t: TextToSpeech, text: String, done: () -> Unit) {
        t.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) = done()
            @Deprecated("Deprecated in Java") override fun onError(id: String?) = done()
        })
        if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "athena") == TextToSpeech.ERROR) { Ossa.failed(this, "speak", "tts_speak_error"); done() }
    }

    private fun reply() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) askMic.launch(Manifest.permission.RECORD_AUDIO)
        else listen()
    }

    private fun listen() {
        if (listening || waiting) return
        line++    // a line still playing no longer moves the talk on
        stopSpeaking()
        if (!dev.apollo.voice.SarvamVoice.likely()) return androidListen()
        listening = true
        Thread {
            val pcm = mic.record(this)
            val text = if (pcm.isEmpty()) "" else dev.apollo.voice.SarvamVoice.words(this, pcm)
            runOnUiThread {
                listening = false
                if (isDestroyed) return@runOnUiThread
                if (text == null) { heard = "(say it again)"; androidListen() } else heardWords(text)
            }
        }.start()
    }

    private fun stopSpeaking() {
        tts?.stop()
        runCatching { player?.stop(); player?.release() }
        player = null
    }

    private fun androidListen() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            // Nothing can listen: the phone says goodbye rather than wait forever.
            Ossa.failed(this, "listen", "no_recognizer")
            heard = "(speech recognition isn't available on this phone)"
            if (Ossa.ui.value?.live == true) talk.cantHear().let { hers = it; say(it) }
            return
        }
        tts?.stop()
        val sr = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = sr
        listening = true
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                listening = false
                release(sr)
                heardWords(text)
            }
            override fun onError(error: Int) {
                listening = false
                release(sr)
                // Nothing heard is silence; any other failure is logged and counts the same, so the talk can't hang.
                if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) Ossa.failed(this@OssaCallActivity, "listen", "speech_$error")
                if (!isDestroyed) heardWords("")
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        sr.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
    }

    private fun release(sr: SpeechRecognizer) {
        if (recognizer === sr) recognizer = null
        sr.destroy()
    }

    override fun onDestroy() {
        mic.stop()
        recognizer?.let { it.cancel(); it.destroy() }
        recognizer = null
        stopSpeaking()
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val ANSWER = "answer"
        fun intent(c: Context, answer: Boolean): Intent = Intent(c, OssaCallActivity::class.java).putExtra(ANSWER, answer)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
