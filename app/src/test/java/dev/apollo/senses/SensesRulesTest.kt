package dev.apollo.senses

import dev.apollo.approvals.Level
import dev.apollo.approvals.Rules
import dev.apollo.approvals.Seed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensesRulesTest {
    private val skip = Seed.SKIP_APPS.toSet()
    private val keep = setOf(Seed.GMAIL_KEEP)
    private fun check(pkg: String, title: String = "Sam", text: String = "hi", sub: String? = null, people: Set<String> = emptySet()) =
        NoticeRules.check(pkg, title, text, sub, null, "dev.phonerecall", skip, keep, emptySet(), people)

    @Test fun skippedAppsNeverLeave() {
        assertTrue(check("com.twitter.android") is NoticeRules.Verdict.Drop)
        assertTrue(check("org.telegram.messenger") is NoticeRules.Verdict.Drop)
        assertTrue(check("dev.phonerecall") is NoticeRules.Verdict.Drop)
    }

    @Test fun gmailKeepsOnlyHisAccount() {
        assertEquals(NoticeRules.Verdict.Keep(false), check(NoticeRules.GMAIL, sub = "owner@example.com"))
        assertTrue(check(NoticeRules.GMAIL, sub = "dad@example.com") is NoticeRules.Verdict.Drop)
        assertTrue(check(NoticeRules.GMAIL, sub = null) is NoticeRules.Verdict.Drop)
    }

    @Test fun codesNeverLeave() {
        assertTrue(NoticeRules.isCode("Your verification code is 482913"))
        assertTrue(NoticeRules.isCode("G-123456 is your Google verification code"))
        assertTrue(NoticeRules.isCode("Click this magic link to sign in"))
        assertFalse(NoticeRules.isCode("Dinner at 7? Table for 4"))
    }

    @Test fun calypsoPeopleUseZeussKey() {
        val k = NoticeRules.personKey("Sam")
        assertTrue(check("com.whatsapp", title = "Sam", people = setOf(k)) is NoticeRules.Verdict.Drop)
        assertEquals(k, NoticeRules.personKey("  sam "))
    }

    @Test fun peopleMessagesAreUrgent() {
        assertEquals(NoticeRules.Verdict.Keep(true), check("com.whatsapp"))
        assertEquals(NoticeRules.Verdict.Keep(false), check("com.ubercab"))
    }

    @Test fun rulesOnlyGetStricter() {
        val rules = Seed.APPROVALS.toMap()
        assertTrue(Rules.canAdd(rules, "lights", Level.TAP))
        assertFalse(Rules.canAdd(rules, "message", Level.FREE))
        assertFalse(Rules.canAdd(rules, "open_lock", Level.FINGERPRINT))
        assertFalse(Rules.canAdd(rules, "brand_new", Level.FREE))
        assertEquals(Level.TAP, Rules.levelFor(rules, "brand_new"))
    }

    @Test fun privateAppsAreUnnamed() {
        assertTrue(UsageRules.private("com.chase.sig.android"))
        assertTrue(UsageRules.private("com.x8bit.bitwarden"))
        assertFalse(UsageRules.private("com.google.android.youtube"))
    }

    private val min = 60_000L

    @Test fun watchingAsksOnceThenSleeps() {
        val w = SleepWatch()
        w.activity(0)
        assertNull(w.tick(19 * min, playing = true, night = true))
        assertEquals(SleepWatch.Out.Ask, w.tick(20 * min, playing = true, night = true))
        assertNull(w.tick(22 * min, playing = true, night = true))
        assertEquals(SleepWatch.Out.Asleep(0), w.tick(24 * min, playing = true, night = true))
        assertNull(w.tick(60 * min, playing = true, night = true))
    }

    @Test fun answeringWaitsBeforeAskingAgain() {
        val w = SleepWatch()
        w.activity(0)
        assertEquals(SleepWatch.Out.Ask, w.tick(20 * min, playing = true, night = true))
        w.answered(21 * min)
        assertNull(w.tick(50 * min, playing = true, night = true))
        assertEquals(SleepWatch.Out.Ask, w.tick(66 * min, playing = true, night = true))
    }

    @Test fun stillAtNightIsAsleepButNotByDay() {
        val w = SleepWatch()
        w.activity(0)
        assertNull(w.tick(40 * min, playing = false, night = false))
        assertEquals(SleepWatch.Out.Asleep(0), w.tick(40 * min, playing = false, night = true))
        // 12 AM to 8 AM by default; a weekday can have its own window.
        assertFalse(SleepWatch.isNight(23))
        assertTrue(SleepWatch.isNight(3))
        assertFalse(SleepWatch.isNight(8))
        val sat = SleepWatch.DEFAULTS + mapOf("night_start_h_sat" to 2L, "night_end_h_sat" to 10L)
        assertTrue(SleepWatch.isNight(9, sat, "sat"))
        assertFalse(SleepWatch.isNight(1, sat, "sat"))
        assertTrue(SleepWatch.isNight(1, sat, "mon"))
    }

    @Test fun milesAlongPoints() {
        val m = Trips.miles(listOf(42.3864 to -71.0784, 42.4500 to -70.9300))
        assertTrue(m in 8.0..10.0)
    }
}
