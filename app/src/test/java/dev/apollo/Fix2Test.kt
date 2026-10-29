package dev.apollo

import dev.apollo.approvals.AskClaims
import dev.apollo.approvals.Level
import dev.apollo.approvals.Rules
import dev.apollo.net.Outbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Codex round-2 fixes that are pure enough to test here. */
class Fix2Test {
    @Test fun onlyTheFirstAnswerToAnAskGetsIt() {
        assertTrue(AskClaims.claim("fix2-1"))
        assertFalse(AskClaims.claim("fix2-1"))         // a second Allow while the first runs
        assertTrue(AskClaims.claim("fix2-2"))
    }

    @Test fun aRuleTightenedDuringTheFingerprintStopsTheRun() {
        assertTrue(Rules.stillCovers(Level.TAP, Level.TAP, Level.TAP))
        assertFalse(Rules.stillCovers(Level.TAP, Level.TAP, Level.FINGERPRINT))
        assertFalse(Rules.stillCovers(Level.FINGERPRINT, Level.TAP, Level.NEVER))
        assertTrue(Rules.stillCovers(Level.FINGERPRINT, Level.TAP, Level.FINGERPRINT))
    }

    @Test fun aFinalResultStaysQueuedUntilTheServerTakesIt() {
        assertTrue(Outbox.keepReport(503))
        assertTrue(Outbox.keepReport(401))
        assertFalse(Outbox.keepReport(422))            // the server never will: dropped, so it can't hold up syncs
        assertFalse(Outbox.keepReport(413))
        for (code in listOf(404, 408, 421, 429)) assertTrue(code.toString(), Outbox.keepReport(code))   // the way there, not the data
        assertEquals(false to "Owner said no", Outbox.done(Outbox.mark(false, "Owner said no")))
    }
}
