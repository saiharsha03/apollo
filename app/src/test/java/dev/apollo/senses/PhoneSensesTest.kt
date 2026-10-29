package dev.apollo.senses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSensesTest {
    private val min = 60_000L

    private fun asleepAt0(): SleepWatch = SleepWatch().also { w ->
        w.activity(0)
        assertEquals(SleepWatch.Out.Asleep(0), w.tick(30 * min, playing = false, night = true))
    }

    @Test fun aStirIsNotAWake() {
        val w = asleepAt0()
        w.activity(180 * min)
        w.activity(181 * min)
        assertNull(w.tick(182 * min, playing = false, night = true))
        assertNull(w.tick(215 * min, playing = false, night = true))
        // Back to sleep without a second asleep fact.
        assertNull(w.tick(300 * min, playing = false, night = true))
    }

    @Test fun usingThePhoneAWhileIsAWake() {
        val w = asleepAt0()
        w.activity(400 * min)
        w.activity(406 * min)
        assertEquals(SleepWatch.Out.Awake(0, 400 * min), w.tick(406 * min, playing = false, night = true))
        assertNull(w.tick(407 * min, playing = false, night = true))
    }

    @Test fun anyUseAfterTheNightIsAWake() {
        val w = asleepAt0()
        w.activity(500 * min)
        assertEquals(SleepWatch.Out.Awake(0, 500 * min), w.tick(501 * min, playing = false, night = false))
    }

    private val own = "dev.phonerecall"
    private val clock = "com.google.android.deskclock"

    @Test fun aRingIsTheClocksAlarmAtItsTime() {
        val at = 1_000 * min
        assertTrue(AlarmRules.isRing("alarm", clock, own, listOf(at, 0L), at + 30_000L))
        assertTrue(AlarmRules.isRing("alarm", clock, own, listOf(at + 9 * min, at), at + 30_000L))
        assertFalse(AlarmRules.isRing("alarm", clock, own, listOf(at + 60 * min, 0L), at))      // a timer
        assertFalse(AlarmRules.isRing("event", clock, own, listOf(at, 0L), at))
        assertFalse(AlarmRules.isRing("alarm", "com.samsung.android.calendar", own, listOf(at, 0L), at))
        assertTrue(AlarmRules.wentOff(at, at + 9 * min, at + min))
        assertFalse(AlarmRules.wentOff(at, at + 9 * min, at - 30 * min))
    }

    @Test fun snoozedThenDismissedThenMissed() {
        val t = 1_000 * min
        var (r, out) = AlarmRules.start(Ring(), t)
        assertEquals(AlarmRules.Out.Ringing(t, t), out)
        r = AlarmRules.end(r, t + min)
        var looked = AlarmRules.look(r, t + 2 * min, next = t + 10 * min, lastUnlock = 0L)
        assertEquals(listOf(AlarmRules.Out.Snoozed(t, t + 10 * min)), looked.second)
        // Snoozed: not missed while the snooze is still to ring.
        assertEquals(emptyList<AlarmRules.Out>(), AlarmRules.look(looked.first, t + 9 * min, t + 10 * min, 0L).second)
        val again = AlarmRules.start(looked.first, t + 10 * min)
        assertEquals(AlarmRules.Out.Ringing(t + 10 * min, t), again.second)
        r = AlarmRules.end(again.first, t + 11 * min)
        looked = AlarmRules.look(r, t + 12 * min, next = t + 1440 * min, lastUnlock = 0L)
        assertEquals(listOf(AlarmRules.Out.Dismissed(t + 10 * min)), looked.second)
        assertEquals(emptyList<AlarmRules.Out>(), AlarmRules.look(looked.first, t + 21 * min, t + 1440 * min, 0L).second)
        val missed = AlarmRules.look(looked.first, t + 22 * min, t + 1440 * min, 0L)
        assertEquals(listOf(AlarmRules.Out.Missed(t, t + 10 * min, 22)), missed.second)
        // Said once.
        assertEquals(emptyList<AlarmRules.Out>(), AlarmRules.look(missed.first, t + 40 * min, t + 1440 * min, 0L).second)
    }

    @Test fun anUnlockAfterTheRingMeansUp() {
        val t = 1_000 * min
        val r = AlarmRules.end(AlarmRules.start(Ring(), t).first, t + min)
        // The ended ring is still read; only missed is off. The look after clears it.
        val (after, out) = AlarmRules.look(r, t + 20 * min, 0L, lastUnlock = t + 3 * min)
        assertEquals(listOf(AlarmRules.Out.Dismissed(t)), out)
        assertEquals(Ring() to emptyList<AlarmRules.Out>(), AlarmRules.look(after, t + 21 * min, 0L, lastUnlock = t + 3 * min))
        // Unlocked while it still rings: the snooze is reported once it ends.
        val ringing = AlarmRules.start(Ring(), t).first
        val stillOn = AlarmRules.look(ringing, t + 2 * min, t + 10 * min, lastUnlock = t + min)
        assertTrue(stillOn.second.isEmpty())
        val snoozed = AlarmRules.look(AlarmRules.end(stillOn.first, t + 3 * min), t + 4 * min, t + 10 * min, lastUnlock = t + min)
        assertEquals(listOf(AlarmRules.Out.Snoozed(t, t + 10 * min)), snoozed.second)
        // An unlock before the ring doesn't count; the setting changes the wait.
        assertEquals(1, AlarmRules.look(r, t + 6 * min, 0L, lastUnlock = t - min, missedMin = 5).second.count { it is AlarmRules.Out.Missed })
    }

    @Test fun aRingNeverSeenToEndIsOverAfterTenMinutes() {
        val t = 1_000 * min
        val r = AlarmRules.start(Ring(), t).first
        assertTrue(AlarmRules.look(r, t + 9 * min, 0L, 0L).second.isEmpty())
        val (after, out) = AlarmRules.look(r, t + 10 * min, 0L, 0L)
        assertEquals(listOf(AlarmRules.Out.Dismissed(t)), out)
        assertFalse(after.ringing)
        assertEquals(r.copy(ringing = false), Ring.decode(r.copy(ringing = false).encode()))
    }
}
