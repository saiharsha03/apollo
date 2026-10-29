package dev.apollo.senses

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import org.json.JSONObject

/** When a walk is a fact. Pure. */
object WalkRules {
    /** Shorter than this is crossing a room, not a walk. */
    const val MIN_MS = 3 * 60_000L
    /** One "started walking" per this long: activity recognition flickers, and each fact wakes Athena (Zeus's Walks). */
    const val TELL_EVERY_MS = 30 * 60_000L

    fun tellStart(walkingSince: Long, lastTold: Long, at: Long): Boolean = walkingSince == 0L && at - lastTold >= TELL_EVERY_MS

    fun counts(since: Long, end: Long): Boolean = since > 0L && end - since >= MIN_MS
}

/**
 * "Started walking" (Zeus's Walks): Play services' activity transitions. Walking starts a walk: a `walk` fact
 * {state started}; standing still or getting in a vehicle ends it: {state ended, start, end, minutes, to still|vehicle}.
 * Where he is comes from stays and places; this says only that he moves.
 */
object Walking {
    private const val ACTION = "dev.apollo.WALK"
    private fun prefs(c: Context) = c.getSharedPreferences("senses_walk", Context.MODE_PRIVATE)

    fun allowed(c: Context) = c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun pending(c: Context) = PendingIntent.getBroadcast(c, 61_200, Intent(c, WalkReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

    /** At app start and after he allows it. Play services drops the request on reboot; the app start re-arms it. */
    @SuppressLint("MissingPermission")
    fun arm(c: Context) {
        if (!allowed(c)) return
        val t = listOf(DetectedActivity.WALKING, DetectedActivity.STILL, DetectedActivity.IN_VEHICLE).map {
            ActivityTransition.Builder().setActivityType(it).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build()
        }
        ActivityRecognition.getClient(c).requestActivityTransitionUpdates(ActivityTransitionRequest(t), pending(c))
    }

    fun onIntent(c: Context, i: Intent) {
        val r = ActivityTransitionResult.extractResult(i) ?: return
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtimeNanos()
        val p = prefs(c)
        for (e in r.transitionEvents) {
            val at = nowWall - (nowElapsed - e.elapsedRealTimeNanos) / 1_000_000L
            val since = p.getLong("since", 0L)
            when (e.activityType) {
                DetectedActivity.WALKING -> {
                    if (since != 0L) continue
                    val tell = WalkRules.tellStart(since, p.getLong("told", 0L), at)
                    p.edit().putLong("since", at).apply { if (tell) putLong("told", at) }.apply()
                    if (tell) Senses.sink.post("walk", JSONObject().put("state", "started").put("at", at), false)
                }
                DetectedActivity.STILL, DetectedActivity.IN_VEHICLE -> {
                    if (since == 0L) continue
                    p.edit().remove("since").apply()
                    if (!WalkRules.counts(since, at)) continue
                    Senses.sink.post("walk", JSONObject().put("state", "ended").put("start", since).put("end", at).put("minutes", (at - since) / 60_000L)
                        .put("to", if (e.activityType == DetectedActivity.IN_VEHICLE) "vehicle" else "still"), false)
                }
            }
        }
    }
}

class WalkReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val done = goAsync()
        Thread { runCatching { Walking.onIntent(c.applicationContext, i) }; done.finish() }.start()
    }
}
