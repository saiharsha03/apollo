package dev.apollo.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import dev.apollo.clio.Clio
import dev.apollo.net.Server
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Sarvam voice through the server (POST /v1/voice/tts and /v1/voice/stt; the key stays there), as Zeus had it. Null
 * whenever the server can't answer in time, is over its daily cap or has no key: callers then use Android's own voice
 * and recognizer. Blocking: call off the main thread.
 */
object SarvamVoice {
    const val RATE = 16_000
    /** Athena's words wait this long for her Sarvam voice before Android's speaks (Owner, 2026-10-27). */
    const val SPEECH_WAIT_MS = 3_000L
    private const val WORDS_WAIT_MS = 60_000L
    /** After a failure, Android's recognizer is used first for this long (no saying it twice while offline). */
    private const val DOWN_MS = 5 * 60_000L
    @Volatile private var failedAt = 0L

    /** Worth trying Sarvam first: no failure in the last few minutes. */
    fun likely(): Boolean = System.currentTimeMillis() - failedAt > DOWN_MS

    fun speech(c: Context, text: String, waitMs: Long = SPEECH_WAIT_MS): ByteArray? = attempt("tts") {
        Server(c).postBytes("/v1/voice/tts", JSONObject().put("text", text).toString().toByteArray(), "application/json; charset=utf-8", waitMs).takeIf { it.size > 44 }
    }

    /** His words from 16 kHz mono PCM; null when Sarvam couldn't, "" when it heard nothing. */
    fun words(c: Context, pcm16: ByteArray, waitMs: Long = WORDS_WAIT_MS): String? = attempt("stt") {
        JSONObject(String(Server(c).postBytes("/v1/voice/stt", wav(pcm16), "audio/wav", waitMs))).optString("text").trim()
    }

    private fun <T> attempt(what: String, block: () -> T?): T? = runCatching(block).getOrNull().also {
        if (it == null) { failedAt = System.currentTimeMillis(); Clio.info("voice", "sarvam $what unavailable", "android voice") }
    }

    /** Plays WAV bytes (the cache file is overwritten by the next); [usage] routes it (a call's or the assistant's). */
    fun play(c: Context, wav: ByteArray, usage: Int, done: () -> Unit = {}): MediaPlayer? = runCatching {
        val f = File(c.cacheDir, "sarvam-speech.wav").apply { writeBytes(wav) }
        MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            setDataSource(f.path)
            setOnCompletionListener { it.release(); done() }
            prepare()
            start()
        }
    }.getOrNull()

    /** 16-bit mono PCM with a 44-byte WAV header. Pure. */
    fun wav(pcm16: ByteArray, rate: Int = RATE): ByteArray {
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm16.size).put("WAVE".toByteArray()).put("fmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(pcm16.size)
        return h.array() + pcm16
    }
}

/**
 * A short recording of his reply for Sarvam: 16 kHz mono PCM until he stops talking ([Quiet]), [stop] is tapped, or
 * MAX_MS. One at a time; the audio is only held in memory.
 */
class Mic {
    @Volatile private var on = false

    val recording get() = on

    fun stop() { on = false }

    /** Records until done; empty when the mic didn't open or he said nothing. Blocking. */
    @SuppressLint("MissingPermission")
    fun record(c: Context): ByteArray {
        if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return ByteArray(0)
        val min = AudioRecord.getMinBufferSize(SarvamVoice.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SarvamVoice.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 4) }
            .getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: return ByteArray(0)
        val out = ByteArrayOutputStream()
        val quiet = Quiet()
        val buf = ByteArray(SarvamVoice.RATE / 10 * 2)   // 100 ms
        on = true
        try {
            r.startRecording()
            while (on) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) continue
                out.write(buf, 0, n)
                if (quiet.done(Quiet.loud(buf, n), n * 1000L / (SarvamVoice.RATE * 2))) break
            }
        } finally {
            on = false
            runCatching { r.stop() }
            r.release()
        }
        return if (quiet.heard) out.toByteArray() else ByteArray(0)
    }
}

/** When a reply is over: 1.5 s of quiet after he spoke, 8 s with nothing said, or 30 s in all. Pure. */
class Quiet {
    var heard = false
        private set
    private var total = 0L
    private var still = 0L

    fun done(loud: Boolean, ms: Long): Boolean {
        total += ms
        if (loud) { heard = true; still = 0 } else still += ms
        return total >= MAX_MS || (heard && still >= END_MS) || (!heard && total >= NOTHING_MS)
    }

    companion object {
        const val MAX_MS = 30_000L
        const val END_MS = 1_500L
        const val NOTHING_MS = 8_000L

        /** RMS of 16-bit little-endian PCM over a speaking floor. */
        fun loud(pcm: ByteArray, n: Int): Boolean {
            var sum = 0.0
            var i = 0
            while (i + 1 < n) { val s = (pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8); sum += s.toDouble() * s; i += 2 }
            return n >= 2 && Math.sqrt(sum / (n / 2)) > 700
        }
    }
}
