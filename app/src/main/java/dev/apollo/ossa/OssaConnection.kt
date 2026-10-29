package dev.apollo.ossa

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.OutcomeReceiver
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.apollo.R

/** Apollo's self-managed ConnectionService: Telecom binds it for each incoming Athena call. No outgoing side. */
class OssaConnectionService : ConnectionService() {
    override fun onCreateIncomingConnection(account: PhoneAccountHandle?, request: ConnectionRequest?): Connection =
        OssaConnection(applicationContext).also { conn ->
            conn.connectionProperties = Connection.PROPERTY_SELF_MANAGED
            conn.audioModeIsVoip = true
            conn.setCallerDisplayName("Athena", android.telecom.TelecomManager.PRESENTATION_ALLOWED)
            conn.setInitialized()
            conn.setRinging()
            Ossa.connection = conn
        }

    override fun onCreateIncomingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest?) {
        Ossa.failed(applicationContext, "telecom", "incoming_connection_failed")
        Ossa.end(applicationContext, Ending.AS_MESSAGE)
    }
}

/**
 * One Athena call in Telecom. Every button (the car's, the notification's, the screen's) ends up in [Ossa]. Whenever
 * Bluetooth (the car or buds) becomes available mid-call, the call moves to it (the 10-26 bug, [OssaRules.moveTo]).
 */
class OssaConnection(private val app: Context) : Connection() {
    override fun onShowIncomingCallUi() = Ossa.showRinging(app)
    override fun onAnswer() = Ossa.answer(app)
    override fun onAnswer(videoState: Int) = Ossa.answer(app)
    override fun onReject() = Ossa.end(app, Ending.DECLINED)
    override fun onDisconnect() = Ossa.end(app, Ending.TALKED)
    override fun onAbort() = Ossa.end(app, Ending.DECLINED)

    private var current: CallEndpoint? = null

    // Android 14+: endpoints.
    override fun onCallEndpointChanged(endpoint: CallEndpoint) { current = endpoint; Ossa.route = route(endpoint.endpointType) }

    override fun onAvailableCallEndpointsChanged(endpoints: MutableList<CallEndpoint>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val now = current?.let { route(it.endpointType) } ?: Route.OTHER
        val want = OssaRules.moveTo(now, endpoints.map { route(it.endpointType) }.toSet()) ?: return
        val target = endpoints.firstOrNull { route(it.endpointType) == want } ?: return
        requestCallEndpointChange(target, app.mainExecutor, object : OutcomeReceiver<Void, CallEndpointException> {
            override fun onResult(result: Void?) {}
            override fun onError(error: CallEndpointException) { Ossa.failed(app, "move_route", "route_${error.code}") }
        })
    }

    // Before Android 14: the audio state.
    @Deprecated("Telecom calls it below API 34")
    override fun onCallAudioStateChanged(state: CallAudioState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val now = when (state.route) {
            CallAudioState.ROUTE_BLUETOOTH -> Route.BLUETOOTH
            CallAudioState.ROUTE_SPEAKER -> Route.SPEAKER
            CallAudioState.ROUTE_WIRED_HEADSET -> Route.WIRED
            else -> Route.EARPIECE
        }
        Ossa.route = now
        val avail = buildSet {
            if (state.supportedRouteMask and CallAudioState.ROUTE_BLUETOOTH != 0) add(Route.BLUETOOTH)
            if (state.supportedRouteMask and CallAudioState.ROUTE_SPEAKER != 0) add(Route.SPEAKER)
        }
        if (OssaRules.moveTo(now, avail) == Route.BLUETOOTH) @Suppress("DEPRECATION") setAudioRoute(CallAudioState.ROUTE_BLUETOOTH)
    }

    private fun route(type: Int) = when (type) {
        CallEndpoint.TYPE_BLUETOOTH -> Route.BLUETOOTH
        CallEndpoint.TYPE_SPEAKER -> Route.SPEAKER
        CallEndpoint.TYPE_WIRED_HEADSET -> Route.WIRED
        CallEndpoint.TYPE_EARPIECE -> Route.EARPIECE
        else -> Route.OTHER
    }
}

/** The call in progress: a phone-call foreground service with the ongoing call notification and Hang up. */
class OssaCallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Ossa.channels(this)
        val open = PendingIntent.getActivity(this, 5, OssaCallActivity.intent(this, false), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, Ossa.CH_LIVE).setSmallIcon(R.drawable.ic_launcher_sun)
            .setContentTitle("Call with Athena").setContentText(Ossa.ui.value?.ask?.reason.orEmpty())
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(Ossa.person(this), OssaReceiver.pending(this, OssaReceiver.HANG_UP)))
            .setContentIntent(open).setCategory(NotificationCompat.CATEGORY_CALL).setOngoing(true)
            .setUsesChronometer(true).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).build()
        runCatching {
            ServiceCompat.startForeground(this, Ossa.LIVE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        }.onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        getSystemService(NotificationManager::class.java).cancel(Ossa.LIVE_ID)
        super.onDestroy()
    }
}

/** The notification's buttons: Decline, Send as message, Hang up. */
class OssaReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            DECLINE -> Ossa.end(c, Ending.DECLINED)
            MESSAGE -> Ossa.end(c, Ending.AS_MESSAGE)
            HANG_UP -> Ossa.end(c, Ending.TALKED)
            LATER -> Ossa.later(c, 15)
        }
    }

    companion object {
        const val DECLINE = "dev.apollo.ossa.DECLINE"
        const val MESSAGE = "dev.apollo.ossa.MESSAGE"
        const val HANG_UP = "dev.apollo.ossa.HANG_UP"
        const val LATER = "dev.apollo.ossa.LATER"

        fun pending(c: Context, action: String): PendingIntent = PendingIntent.getBroadcast(c, action.hashCode(),
            Intent(c, OssaReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
