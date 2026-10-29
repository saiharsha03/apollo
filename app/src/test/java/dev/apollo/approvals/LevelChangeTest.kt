package dev.apollo.approvals

import org.junit.Assert.assertEquals
import org.junit.Test

class LevelChangeTest {
    @Test fun onlyLooseningNeedsTheFingerprint() {
        assertEquals(Change.NONE, Rules.change(Level.FREE, Level.FREE))
        assertEquals(Change.FINGERPRINT, Rules.change(Level.NEVER, Level.FREE))
        assertEquals(Change.FINGERPRINT, Rules.change(Level.FINGERPRINT, Level.TAP))
        assertEquals(Change.WRITE, Rules.change(Level.FREE, Level.NEVER))
        assertEquals(Change.WRITE, Rules.change(Level.TAP, Level.FINGERPRINT))
    }

    @Test fun labelsForTheMenu() = assertEquals(listOf("Free", "Tap", "Fingerprint", "Never"), Level.entries.map { it.label })

    @Test fun hisPickIsJudgedAgainstTheRowNowNotTheScreen() {
        // His screen showed TAP; Athena raised it to never since: picking fingerprint loosens, so it needs his fingerprint.
        assertEquals(Change.FINGERPRINT, Rules.pick(mapOf("sms" to Level.NEVER), "sms", Level.FINGERPRINT))
        assertEquals(Change.WRITE, Rules.pick(mapOf("sms" to Level.FREE), "sms", Level.FINGERPRINT))
        assertEquals(Change.FINGERPRINT, Rules.pick(emptyMap(), "sms", Level.FREE))     // no row: TAP
        assertEquals(Change.NONE, Rules.pick(emptyMap(), "sms", Level.TAP))
    }
}
