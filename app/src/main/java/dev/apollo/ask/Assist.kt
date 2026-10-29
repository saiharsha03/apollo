package dev.apollo.ask

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.widget.RemoteViews
import dev.apollo.R

/**
 * The Ask Athena widget: a search bar. Widgets can't take typing, so the bar opens the sheet with the keyboard up, and
 * the mic opens it listening. A tiny provider of its own (a fixed layout), apart from the one drawn widget engine.
 */
class AskWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, wm: AppWidgetManager, ids: IntArray) {
        val v = RemoteViews(c.packageName, R.layout.ask_widget)
        v.setOnClickPendingIntent(R.id.ask_bar, PendingIntent.getActivity(c, 1, AskActivity.intent(c, false), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        v.setOnClickPendingIntent(R.id.ask_mic, PendingIntent.getActivity(c, 2, AskActivity.intent(c, true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        ids.forEach { wm.updateAppWidget(it, v) }
    }
}

// No exported ACTION_ASSIST door (scan F5/F14): any app could open the sheet listening. The side key and the corner
// swipe reach Apollo through the voice interaction service below, which only the system can bind.

/** Makes Apollo selectable as the default digital assistant. It does nothing on its own; the system keeps it bound. */
class AssistService : VoiceInteractionService()

class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = object : VoiceInteractionSession(this) {
        // Opens the sheet as Apollo's own activity (the recognizer needs Apollo in front). The screen's text is kept in
        // memory only and goes to Athena only on his tap (ScreenText).
        override fun onShow(args: Bundle?, showFlags: Int) {
            super.onShow(args, showFlags)
            current = this
            ScreenText.now.value = null
            startAssistantActivity(Intent(context, AskActivity::class.java).putExtra(AskActivity.LISTEN, true))
        }

        // The session's own window draws nothing and lets every touch through to the sheet.
        override fun onComputeInsets(outInsets: Insets) {
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
            outInsets.touchableRegion.setEmpty()
        }

        override fun onHandleAssist(state: AssistState) {
            if (state.isFocused) runCatching { ScreenText.read(context, state.assistStructure) }
        }

        override fun onHide() { if (current === this) current = null; super.onHide() }
    }

    companion object {
        @Volatile private var current: VoiceInteractionSession? = null

        /** The sheet closed: the assistant session closes with it. */
        fun close() { runCatching { current?.hide() }; current = null; ScreenText.now.value = null }
    }
}

/**
 * Android makes a default assistant name a recognition service, and makes it the phone's recognizer. This one hands
 * every request to the phone's own on-device recognizer, so voice typing elsewhere keeps working.
 */
class AssistRecognitionService : RecognitionService() {
    private var inner: SpeechRecognizer? = null

    override fun onStartListening(intent: Intent, cb: Callback) {
        if (checkPermission(Manifest.permission.RECORD_AUDIO, -1, cb.callingUid) != PackageManager.PERMISSION_GRANTED) return safe { cb.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) }
        inner?.destroy()
        val r = runCatching { if (SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) SpeechRecognizer.createOnDeviceSpeechRecognizer(this) else null }.getOrNull()
            ?: return safe { cb.error(SpeechRecognizer.ERROR_CLIENT) }
        inner = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = safe { cb.readyForSpeech(params ?: Bundle()) }
            override fun onBeginningOfSpeech() = safe { cb.beginningOfSpeech() }
            override fun onRmsChanged(rmsdB: Float) = safe { cb.rmsChanged(rmsdB) }
            override fun onBufferReceived(buffer: ByteArray?) { buffer?.let { safe { cb.bufferReceived(it) } } }
            override fun onEndOfSpeech() = safe { cb.endOfSpeech() }
            override fun onError(error: Int) = safe { cb.error(error) }
            override fun onResults(results: Bundle?) = safe { cb.results(results ?: Bundle()) }
            override fun onPartialResults(partialResults: Bundle?) = safe { cb.partialResults(partialResults ?: Bundle()) }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(intent)
    }

    override fun onCancel(cb: Callback) { inner?.cancel() }
    override fun onStopListening(cb: Callback) { inner?.stopListening() }
    override fun onDestroy() { inner?.destroy(); inner = null; super.onDestroy() }

    private inline fun safe(block: () -> Unit) { runCatching(block) }
}
