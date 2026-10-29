package dev.apollo.senses

import android.app.Notification
import android.content.Context
import org.json.JSONObject

/**
 * One morning's alarm, as the phone saw it: the first ring, the latest ring, whether it rings now, when the ring ended
 * (not yet read as dismissed or snoozed), where a snooze goes, and whether missed was said.
 */
data class Ring(val firstAt: Long = 0L, val lastAt: Long = 0L, val ringing: Boolean = false, val endedAt: Long = 0L,
                val snoozedTo: Long = 0L, val missed: Boolean = false) {
    fun encode(): String = listOf(firstAt, lastAt, if (ringing) 1 else 0, endedAt, snoozedTo, if (missed) 1 else 0).joinToString(",")

    companion object {
        fun decode(s: String?): Ring {
            val p = s.orEmpty().split(',').mapNotNull { it.toLongOrNull() }
            if (p.size != 6) return Ring()
            return Ring(p[0], p[1], p[2] == 1L, p[3], p[4], p[5] == 1L)
        }
    }
}

/**
 * The alarm ringing, dismissed, snoozed, and missed (Zeus's Hypnos watch, without its deciding). A ring starts when the
 * clock app posts its alarm notification at the time of his next alarm, or, when no notification was seen, when the
 * next alarm moves on within minutes of its time. The ring ends when that notification goes (or after [RING_MAX_MS],
 * when the clock app has silenced it); the next look reads it as snoozed when a new alarm is due within half an hour,
 * else dismissed. Missed: no unlock since the first ring, nothing ringing or snoozed, and `alarm_missed_min` since the
 * last ring. The phone says so; Athena decides. Pure.
 */
object AlarmRules {
    sealed interface Out {
        data class Ringing(val at: Long, val firstAt: Long) : Out
        data class Snoozed(val at: Long, val to: Long) : Out
        data class Dismissed(val at: Long) : Out
        data class Missed(val firstAt: Long, val lastAt: Long, val minutes: Long) : Out
    }

    const val SNOOZE_MS = 30 * 60_000L
    /** A ring this long after the last one starts a new morning. */
    const val CHAIN_MS = 3 * 3_600_000L
    const val MISSED_MIN = 12L
    /** Clock apps silence a ring after about 10 minutes; one never seen to end counts as over then. */
    const val RING_MAX_MS = 10 * 60_000L

    /**
     * A clock app's alarm notification at the time of his alarm: [due] holds the next alarm the phone saw and the one
     * before it (the clock may drop it from AlarmManager just before posting). Timers use the alarm category too, but
     * no alarm is due then.
     */
    fun isRing(category: String?, pkg: String, own: String, due: List<Long>, now: Long): Boolean =
        category == Notification.CATEGORY_ALARM && pkg != own && "calendar" !in pkg.lowercase() &&
            due.any { it > 0L && it in now - 3 * 60_000L..now + 60_000L }

    /** The next alarm moved from [before] to [after] within minutes of [before]'s time: it went off. */
    fun wentOff(before: Long, after: Long, now: Long): Boolean = before > 0L && before != after && before in now - 90 * 60_000L..now + 60_000L

    fun start(r: Ring, at: Long): Pair<Ring, Out?> {
        if (r.ringing) return r to null
        val fresh = r.firstAt == 0L || at - r.lastAt > CHAIN_MS
        val first = if (fresh) at else r.firstAt
        val next = if (fresh) Ring(firstAt = at, lastAt = at, ringing = true) else r.copy(lastAt = at, ringing = true, endedAt = 0L, snoozedTo = 0L)
        return next to Out.Ringing(at, first)
    }

    fun end(r: Ring, at: Long): Ring = if (r.ringing) r.copy(ringing = false, endedAt = at) else r

    /**
     * The minute look: a ring that ended is read, he is up (an unlock after the first ring), or it is missed. Being up
     * only rules out missed: a ring still going or ended unread is still read as snoozed or dismissed first.
     */
    fun look(r: Ring, now: Long, next: Long, lastUnlock: Long, missedMin: Long = MISSED_MIN): Pair<Ring, List<Out>> {
        if (r.firstAt == 0L) return r to emptyList()
        val up = lastUnlock > r.firstAt
        if (!r.ringing && ((up && r.endedAt == 0L) || now - r.lastAt > CHAIN_MS)) return Ring() to emptyList()
        var cur = if (r.ringing && now - r.lastAt >= RING_MAX_MS) r.copy(ringing = false, endedAt = now - 5_000L) else r
        val out = mutableListOf<Out>()
        if (cur.endedAt > 0L && now - cur.endedAt >= 5_000L) {
            val snoozed = next > cur.endedAt && next <= cur.endedAt + SNOOZE_MS
            out += if (snoozed) Out.Snoozed(cur.lastAt, next) else Out.Dismissed(cur.lastAt)
            cur = cur.copy(endedAt = 0L, snoozedTo = if (snoozed) next else 0L)
        }
        val waiting = cur.ringing || cur.endedAt > 0L || cur.snoozedTo > now
        if (!up && !cur.missed && !waiting && now - maxOf(cur.lastAt, cur.snoozedTo) >= missedMin * 60_000L) {
            out += Out.Missed(cur.firstAt, cur.lastAt, (now - cur.firstAt) / 60_000L)
            cur = cur.copy(missed = true)
        }
        return cur to out
    }

    fun fact(o: Out): JSONObject = when (o) {
        is Out.Ringing -> JSONObject().put("state", "ringing").put("at", o.at).put("first_at", o.firstAt)
        is Out.Snoozed -> JSONObject().put("state", "snoozed").put("at", o.at).put("to", o.to)
        is Out.Dismissed -> JSONObject().put("state", "dismissed").put("at", o.at)
        is Out.Missed -> JSONObject().put("state", "missed").put("rang_at", o.firstAt).put("last_ring_at", o.lastAt)
            .put("minutes", o.minutes).put("unlocked", false)
    }
}

/** The ring's state on the phone, across listener restarts. Posts what [AlarmRules] says. */
object AlarmRing {
    private fun prefs(c: Context) = c.getSharedPreferences("senses_alarm_ring", Context.MODE_PRIVATE)
    private fun load(c: Context) = Ring.decode(prefs(c).getString("ring", null))
    private fun save(c: Context, r: Ring) = prefs(c).edit().putString("ring", r.encode()).apply()

    /** The next alarm the minute look last saw, and the one before it (DeviceWatch keeps them). */
    private fun due(c: Context) = c.getSharedPreferences("senses_device", Context.MODE_PRIVATE).let { listOf(it.getLong("alarm", 0L), it.getLong("alarm_before", 0L)) }

    @Synchronized fun posted(c: Context, category: String?, pkg: String, now: Long) {
        if (!AlarmRules.isRing(category, pkg, c.packageName, due(c), now)) return
        val (r, out) = AlarmRules.start(load(c), now)
        save(c, r)
        out?.let { post(it) }
    }

    @Synchronized fun removed(c: Context, category: String?, pkg: String, now: Long) {
        if (category != Notification.CATEGORY_ALARM || pkg == c.packageName) return
        val r = load(c)
        if (r.ringing) save(c, AlarmRules.end(r, now))
    }

    /** From DeviceWatch when the next alarm moved on at its time: it rings, unless the notification already said so. */
    @Synchronized fun wentOff(c: Context, before: Long) {
        val r = load(c)
        if (r.ringing || (r.lastAt > 0L && kotlin.math.abs(r.lastAt - before) < 5 * 60_000L)) return
        val (started, out) = AlarmRules.start(r, before)
        save(c, started)
        out?.let { post(it) }
    }

    @Synchronized fun look(c: Context, next: Long) {
        val missedMin = UsageWatch.sleepSettings(c)["alarm_missed_min"] ?: AlarmRules.MISSED_MIN
        val before = load(c)
        val (r, out) = AlarmRules.look(before, System.currentTimeMillis(), next, Unlocks.last, missedMin)
        if (r != before) save(c, r)
        out.forEach { post(it) }
    }

    private fun post(o: AlarmRules.Out) = Senses.sink.post("alarm", AlarmRules.fact(o), o is AlarmRules.Out.Missed)
}
