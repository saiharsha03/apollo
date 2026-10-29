package dev.apollo.senses

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * Usage and sleep, from Android's usage events (Owner 2026-10-26: no global touches exist for an app; USER_PRESENT
 * never arrived in 4 days of Zeus's logs, so unlocks come from KEYGUARD_HIDDEN). Facts: `unlock`, `app_usage` (one
 * per foreground stretch), `sleep`. The listener calls [look] once a minute.
 */
class UsageWatch(private val c: Context) {
    private val prefs = c.getSharedPreferences("senses_usage", Context.MODE_PRIVATE)
    private val sleep = SleepWatch(sleepSettings(c))
    private var open: Pair<String, Long>? = null
    private var seenYes = 0L

    fun look(listener: android.service.notification.NotificationListenerService) {
        val um = c.getSystemService(UsageStatsManager::class.java) ?: return
        val now = System.currentTimeMillis()
        SleepAnswers.yes.takeIf { it > seenYes }?.let { seenYes = it; sleep.answered(it) }
        val since = prefs.getLong("since", now - 60_000L).coerceAtLeast(now - 6 * 3_600_000L)
        val events = um.queryEvents(since, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.KEYGUARD_HIDDEN -> {
                    sleep.activity(e.timeStamp)
                    Unlocks.last = maxOf(Unlocks.last, e.timeStamp)
                    Senses.sink.post("unlock", JSONObject().put("at", e.timeStamp), false)
                }
                UsageEvents.Event.SCREEN_INTERACTIVE, UsageEvents.Event.SCREEN_NON_INTERACTIVE -> sleep.activity(e.timeStamp)
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (open?.first != e.packageName) { close(e.timeStamp); open = e.packageName to e.timeStamp; sleep.activity(e.timeStamp) }
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> if (open?.first == e.packageName) close(e.timeStamp)
            }
        }
        prefs.edit().putLong("since", now).apply()
        val at = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        val day = at.dayOfWeek.name.take(3).lowercase()
        when (val out = sleep.tick(now, playing(listener), SleepWatch.isNight(at.hour, sleep.settings, day))) {
            SleepWatch.Out.Ask -> StillWatching.ask(c)
            is SleepWatch.Out.Asleep -> Senses.sink.post("sleep", JSONObject().put("state", "asleep").put("asleep_at", out.at).put("seen_at", now), false)
            is SleepWatch.Out.Awake -> Senses.sink.post("sleep", JSONObject().put("state", "awake").put("slept_at", out.sleptAt).put("woke_at", out.wokeAt).put("seen_at", now), false)
            null -> {}
        }
    }

    private fun close(at: Long) {
        val (pkg, start) = open ?: return
        open = null
        if (at - start < 2_000L || pkg == c.packageName) return
        Senses.sink.post("app_usage", JSONObject().put("pkg", if (private(pkg)) "other" else pkg).put("start", start).put("end", at), false)
    }

    private fun private(pkg: String): Boolean = UsageRules.private(pkg)

    private fun playing(l: android.service.notification.NotificationListenerService): Boolean = runCatching {
        c.getSystemService(MediaSessionManager::class.java)!!.getActiveSessions(ComponentName(c, l::class.java))
            .any { it.playbackState?.state == PlaybackState.STATE_PLAYING }
    }.getOrDefault(false)

    companion object {
        fun sleepSettings(c: Context): Map<String, Long> {
            val p = c.getSharedPreferences("senses_settings", Context.MODE_PRIVATE)
            // The defaults, plus any number he or Athena set (weekday windows included).
            return SleepWatch.DEFAULTS + p.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }
        }
    }
}

/** Banking, password, health and social apps count in screen time, but never by name. Pure. */
object UsageRules {
    private val PRIVATE = listOf(
        "com.chase", "com.wf.", "com.bankofamerica", "com.citi", "com.capitalone", "com.americanexpress", "com.discover",
        "com.paypal", "com.venmo", "com.zellepay", "com.sbi", "com.snapwork.hdfc", "com.csam.icici", "com.axis",
        "com.x8bit.bitwarden", "com.onepassword", "com.lastpass", "com.google.android.apps.authenticator2", "com.azure.authenticator",
        "com.example.private", "com.sec.android.app.shealth", "com.google.android.apps.healthdata",
        "dev.authenticator",
    )
    private val PRIVATE_WORDS = Regex("(?i)(bank|wallet|password|authenticator|private|health)")

    fun private(pkg: String): Boolean = PRIVATE.any { pkg.startsWith(it) } || PRIVATE_WORDS.containsMatchIn(pkg)
}

/** The one "Still watching?" notification, with Yes. No answer is the answer. */
object StillWatching {
    private const val CHANNEL = "still_watching"
    private const val ID = 4401

    fun ask(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Still watching?", NotificationManager.IMPORTANCE_HIGH))
        val yes = PendingIntent.getBroadcast(c, 0, Intent(c, Answer::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(ID, android.app.Notification.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.ic_media_pause)
            .setContentTitle("Still watching?").setContentText("Tap Yes, or Athena will think you fell asleep.")
            .addAction(android.app.Notification.Action.Builder(null, "Yes", yes).build()).setAutoCancel(true).setTimeoutAfter(4 * 60_000L).build())
    }

    class Answer : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            c.getSystemService(NotificationManager::class.java)?.cancel(ID)
            SleepAnswers.yes = System.currentTimeMillis()
        }
    }
}

/** His last unlock, for the alarm watch ([AlarmRules]): no unlock after the ring means he isn't up. */
object Unlocks { @Volatile var last = 0L }

/** The Yes tap reaches the watcher on its next look (they live in different components). */
object SleepAnswers { @Volatile var yes = 0L }
