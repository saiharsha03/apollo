package dev.apollo.net

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * FCM wake-ups. A push only ever says {"t":"sync"}; anything else is dropped unread, and all content comes from the
 * server with GET /v1/sync. Firebase is initialised from res/values/firebase.xml (no google-services plugin) and
 * nothing else of Firebase is in the app.
 */
object Push {
    fun isSync(data: Map<String, String>): Boolean = data.size == 1 && data["t"] == "sync"

    /** Turns on FCM and sends the token, once the device token is in Calypso. */
    fun register(c: Context) {
        if (FirebaseApp.getApps(c).isEmpty()) FirebaseApp.initializeApp(c) ?: return
        FirebaseMessaging.getInstance().isAutoInitEnabled = true
        FirebaseMessaging.getInstance().token.addOnSuccessListener { t -> send(c, t) }
    }

    fun send(c: Context, token: String) {
        CoroutineScope(Dispatchers.IO).launch { runCatching { Server(c).fcm(token) } }
    }
}

class ApolloMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) = Push.send(applicationContext, token)

    override fun onMessageReceived(message: RemoteMessage) {
        if (Push.isSync(message.data)) Sync.now(applicationContext)
    }
}
