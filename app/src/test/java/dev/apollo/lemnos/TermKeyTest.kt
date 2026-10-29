package dev.apollo.lemnos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TermKeyTest {
    @Test fun aGoneOrInvalidatedKeyOnlyAsksForAServerReset() {
        assertEquals(KeyPlan.SIGN, keyPlan("abcdef1234", "abcdef1234", null))
        assertEquals(KeyPlan.REGISTER, keyPlan(null, null, null))                           // first time
        assertEquals(KeyPlan.REGISTER, keyPlan("abcdef1234", null, null))                   // root cleared the server's key
        assertEquals(KeyPlan.APPROVE_ON_SERVER, keyPlan("abcdef1234", null, "abcdef1234"))  // registered, waiting for root
        assertEquals(KeyPlan.RESET_ON_SERVER, keyPlan("abcdef1234", null, "99999999"))      // someone else's pending key: never replaced
        assertEquals(KeyPlan.RESET_ON_SERVER, keyPlan(null, null, "abcdef1234"))            // ours, lost before approval
        assertEquals(KeyPlan.RESET_ON_SERVER, keyPlan(null, "abcdef1234", null))            // reinstall, screen lock removed, invalidated
        assertEquals(KeyPlan.RESET_ON_SERVER, keyPlan("new", "abcdef1234", null))           // a new key here, the old one still there
    }

    @Test fun theResetMessageNamesTheCommand() {
        assertTrue(TermKey.NeedsReset().message!!.contains("deploy/lemnos-reset.sh"))
        assertTrue(TermKey.NeedsApproval("abcdef12").message!!.contains("bash deploy/lemnos-approve.sh abcdef12"))
    }
}
