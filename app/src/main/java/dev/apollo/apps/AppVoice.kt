package dev.apollo.apps

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.media.MediaRecorder
import dev.apollo.clio.Clio
import java.io.ByteArrayOutputStream
import java.io.File

/*
 * Voice notes for the mini apps (Calliope, his journal: "audio is as important as text", Owner 2026-10-25), carried over
 * from Zeus (apps/AppVoice.kt). A page can't reach the mic, so Apollo records natively: 16 kHz mono, kept as AAC (.m4a)
 * in the app's phone folder (PhoneFiles: audio/<stamp>.m4a, beside Zeus's old recordings), and plays it back. The words
 * come from Sarvam through the server (as Zeus had it): the recording goes up once to be transcribed and isn't kept there;
 * the page saves the words with the entry, so Athena gets what he said. Offline, the note keeps no words.
 */
object AppVoice {
    private const val RATE = 16_000
    /** One recording's length: PCM is held in memory until it stops. */
    private const val MAX_S = 20 * 60

    /** One recording: its recorder, whom it is for, its reader thread and its own buffer, set and taken as one. */
    private class Recording(val rec: AudioRecord, val app: String) {
        val pcm = ByteArrayOutputStream()
        lateinit var thread: Thread
    }
    @Volatile private var cur: Recording? = null
    private var player: MediaPlayer? = null

    /** Not a valid app name, so no app's page can pass for the private page (apps/PrivateNote.kt). */
    const val PRIVATE = "#private"

    fun micOk(c: Context) = c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Starts recording for [forApp]; null when started, else why not. One recording at a time. */
    @SuppressLint("MissingPermission")
    @Synchronized fun start(c: Context, forApp: String): String? {
        if (!micOk(c)) return "needs_mic"
        // While his private page is open, pages get no mic at all.
        if (forApp != PRIVATE && PrivateNote.open.value != null) return "not now"
        if (cur != null) return "already recording"
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 4) }.getOrNull()
            ?.takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: return "the mic didn't open"
        val rec = Recording(r, forApp)
        r.startRecording()
        rec.thread = Thread({
            val buf = ByteArray(min)
            while (cur === rec) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) synchronized(rec.pcm) { if (rec.pcm.size() < MAX_S * RATE * 2) rec.pcm.write(buf, 0, n) }
            }
        }, "apollo-app-voice")
        cur = rec
        rec.thread.start()
        Clio.info("apps", "voice note started", if (forApp == PRIVATE) "private" else forApp)
        return null
    }

    data class Note(val path: String, val text: String, val seconds: Int, val error: String?)

    /** The recording [whose] app passes, ended, with whom it was for and its bytes; null when there is none of theirs. */
    private fun take(whose: (String) -> Boolean): Pair<String, ByteArray>? {
        val rec = synchronized(this) { cur?.takeIf { whose(it.app) }?.also { cur = null } } ?: return null
        runCatching { rec.rec.stop() }
        rec.thread.join(1000)
        rec.rec.release()
        return rec.app to synchronized(rec.pcm) { rec.pcm.toByteArray() }
    }

    /** Stops and keeps audio/<stamp>.m4a in the app's phone folder. Blocking: call off the main thread. */
    fun stop(c: Context, forApp: String): Note {
        // Only the app that started it stops it: a page can't end (or take) his private recording.
        val (a, bytes) = take { it == forApp } ?: return Note("", "", 0, "not recording")
        val secs = bytes.size / (RATE * 2)
        val name = "audio/" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")) + ".m4a"
        val f = PhoneFiles.file(c, a, name) ?: return Note("", "", secs, "no app $a")
        val saved = runCatching { f.parentFile?.mkdirs(); aac(bytes, f) }.isSuccess
        Clio.info("apps", "voice note kept", "$a ${secs}s")
        val words = if (saved && bytes.isNotEmpty()) dev.apollo.voice.SarvamVoice.words(c, bytes).orEmpty() else ""
        return Note(if (saved) name else "", words, secs, if (saved) null else "couldn't keep the recording")
    }

    /** The private page's recording: AAC bytes in memory only, never an app's folder and never off the phone. */
    fun stopPrivate(c: Context): Pair<ByteArray?, String> {
        val (_, bytes) = take { it == PRIVATE } ?: return null to "not recording"
        val m4a = runCatching { encoded(c, bytes) }.getOrNull()
        return m4a to if (m4a != null) "Recording kept with this page." else "Couldn't keep the recording."
    }

    /** The encoder writes to a file (MediaMuxer); the private one lives only while it is read back. */
    private fun encoded(c: Context, pcm16: ByteArray): ByteArray {
        val f = File(c.noBackupFilesDir, "private-note.m4a")
        try { aac(pcm16, f); return f.readBytes() } finally { f.delete() }
    }

    fun stopIfPrivate() { take { it == PRIVATE } }

    /** A page's recording ends with its view: nothing records after he leaves. */
    fun stopIfApp(forApp: String) { take { it == forApp } }

    /** His private page opened: any page's recording ends at once, unkept. */
    fun dropPages() { take { it != PRIVATE }?.let { (a, _) -> VoiceAsk.ended(a, "ended: the private page opened") } }

    /** Plays an .m4a of this app: its phone folder first (his recordings), then the mirrored folder. */
    fun play(c: Context, forApp: String, path: String): String? {
        val f = (PhoneFiles.file(c, forApp, path)?.takeIf { it.isFile } ?: AppFiles.resolve(MiniApps.root(c), forApp, path))
            ?.takeIf { it.isFile && it.extension == "m4a" } ?: return "no recording $path"
        stopPlay()
        return runCatching { player = MediaPlayer().apply { setDataSource(f.path); setOnCompletionListener { it.release(); if (player === it) player = null }; prepare(); start() }; null }
            .getOrElse { it.message ?: "couldn't play" }
    }

    fun stopPlay() { runCatching { player?.stop(); player?.release() }; player = null }

    /** 16 kHz mono PCM to AAC-LC in an .m4a: about 0.25 MB a minute. */
    private fun aac(pcm16: ByteArray, out: File) {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC); setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
        val mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1; var pos = 0; var eos = false; val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                if (!eos) codec.dequeueInputBuffer(10_000).takeIf { it >= 0 }?.let { i ->
                    val b = codec.getInputBuffer(i)!!; b.clear()
                    val n = minOf(b.remaining(), pcm16.size - pos)
                    b.put(pcm16, pos, n)
                    val us = pos.toLong() * 1_000_000 / (RATE * 2)
                    pos += n
                    if (pos >= pcm16.size) { codec.queueInputBuffer(i, 0, n, us, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true } else codec.queueInputBuffer(i, 0, n, us, 0)
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = mux.addTrack(codec.outputFormat); mux.start() }
                    o >= 0 -> {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) mux.writeSampleData(track, codec.getOutputBuffer(o)!!, info)
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }; codec.release()
            runCatching { if (track >= 0) mux.stop() }; mux.release()
        }
    }
}
