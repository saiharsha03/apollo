package dev.apollo.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.apollo.MainActivity
import dev.apollo.R
import dev.apollo.actions.ActionRegistry
import dev.apollo.approvals.ApolloDb
import dev.apollo.apps.AppFiles
import dev.apollo.clio.Clio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * What Live shows: a title, a line, a short chip for the status bar / Now Bar, and optionally a URL it polls (JSON; the
 * line, chip and "done" read from dot paths like "events.0.status.type.shortDetail"). It always ends by [until].
 */
data class LiveSpec(
    val title: String, val text: String, val chip: String = "", val url: String? = null,
    val textPath: String? = null, val chipPath: String? = null, val donePath: String? = null,
    val everySeconds: Int = 60, val until: Long = System.currentTimeMillis() + 3 * 3_600_000L, val countdownTo: Long? = null,
) {
    companion object {
        fun of(a: JSONObject, now: Long = System.currentTimeMillis()) = LiveSpec(
            a.optString("title", "Live").take(60), a.optString("text").take(200), a.optString("chip").take(12),
            a.optString("url").ifBlank { null }, a.optString("text_path").ifBlank { null }, a.optString("chip_path").ifBlank { null },
            a.optString("done_path").ifBlank { null }, a.optInt("every_seconds", 60).coerceIn(30, 900),
            // At most 6 hours, whatever she asks: Live is for a drive or a match, never always-on.
            a.optLong("until", now + 3 * 3_600_000L).coerceIn(now + 60_000L, now + 6 * 3_600_000L), a.optLong("countdown_to").takeIf { it > 0 },
        )
    }
}

/**
 * Live (docs/PLAN.md): a foreground service with one ongoing Live Update notification (Android 16 promoted ongoing, the
 * Now Bar on One UI) during a live match or drive. Athena starts it with phone_do `live` (and updates it the same way),
 * stops it with `live_stop`; a drive can start it natively with [start]. It stops itself at `until` or when the polled
 * JSON says done. A polled URL must be on his fetch allowlist, the same one pages use.
 */
object Live {
    private const val CHANNEL = "live"
    const val ID = 62_000
    // The promotion flags as platform extras (what NotificationCompat writes on SDK 36; this build compiles against 35).
    private const val EXTRA_PROMOTE = "android.requestPromotedOngoing"
    private const val EXTRA_CHIP = "android.shortCriticalText"

    @Volatile var spec: LiveSpec? = null

    fun register() {
        ActionRegistry.handlers["live"] = { c, a -> start(c, LiveSpec.of(a)); true to "live on" }
        ActionRegistry.handlers["live_stop"] = { c, _ -> stop(c); true to "live off" }
    }

    fun start(c: Context, s: LiveSpec) {
        spec = s
        c.startForegroundService(Intent(c, LiveService::class.java))
    }

    fun stop(c: Context) { c.stopService(Intent(c, LiveService::class.java)) }

    fun notification(c: Context, s: LiveSpec): Notification {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Live", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "A live match or drive, while it lasts"; setSound(null, null); enableVibration(false); setShowBadge(false)
        })
        val open = PendingIntent.getActivity(c, ID, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_live).setContentTitle(s.title).setContentText(s.text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addExtras(Bundle().apply { putBoolean(EXTRA_PROMOTE, true); if (s.countdownTo == null && s.chip.isNotBlank()) putString(EXTRA_CHIP, s.chip) })
        s.countdownTo?.let { b.setWhen(it).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true) }
        return b.build()
    }

    /** The value at a dot path ("a.0.b") in parsed JSON, as text; null when the path isn't there. */
    fun at(root: Any?, path: String): String? {
        var cur: Any? = root
        for (k in path.split('.')) cur = when (cur) {
            is JSONObject -> cur.opt(k)
            is JSONArray -> k.toIntOrNull()?.let { cur.opt(it) }
            else -> null
        }
        return cur?.takeIf { it != JSONObject.NULL }?.toString()
    }

    /** Done: the done path holds true, "true", "final", "post" or "ended". */
    fun done(v: String?): Boolean = v?.lowercase() in setOf("true", "final", "post", "ended", "complete")
}

class LiveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    // No redirects: a polled URL may only reach the allowed host it names.
    private val client = dev.apollo.net.NetGate.builder().followRedirects(false).followSslRedirects(false).connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val s = Live.spec ?: return stop()
        startForeground(Live.ID, Live.notification(this, s), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        Clio.info("live", "on", if (s.url != null) "polling" else "facts")
        job?.cancel()
        job = scope.launch { run(s) }
        return START_NOT_STICKY
    }

    private suspend fun run(first: LiveSpec) {
        var s = first
        while (scope.isActive && System.currentTimeMillis() < s.until) {
            val url = s.url
            if (url != null && AppFiles.allowed(url, runCatching { ApolloDb.get(this).fetchAllow() }.getOrDefault(emptySet()))) {
                runCatching {
                    val body = client.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() }
                    val json = if (body.trimStart().startsWith("[")) JSONArray(body) else JSONObject(body)
                    s = s.copy(text = s.textPath?.let { Live.at(json, it) } ?: s.text, chip = (s.chipPath?.let { Live.at(json, it) } ?: s.chip).take(12))
                    Live.spec = s
                    getSystemService(NotificationManager::class.java).notify(Live.ID, Live.notification(this, s))
                    if (s.donePath?.let { Live.done(Live.at(json, it)) } == true) { Clio.info("live", "done"); stop(); return }
                }.onFailure { Clio.warn("live", "poll", it.javaClass.simpleName) }
            } else if (url != null) { Clio.warn("live", "poll", "host not on the fetch allowlist"); stop(); return }
            // An update from Athena (phone_do live again) restarts this loop with the new spec (onStartCommand).
            delay(s.everySeconds * 1000L)
        }
        stop()
    }

    private fun stop(): Int { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }

    override fun onDestroy() {
        Live.spec = null
        Clio.info("live", "off")
        scope.cancel()
        super.onDestroy()
    }
}
