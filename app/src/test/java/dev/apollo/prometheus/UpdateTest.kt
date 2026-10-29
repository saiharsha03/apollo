package dev.apollo.prometheus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateTest {
    private val sha = "a".repeat(64)

    @Test fun onlyNewerWithAChecksum() {
        assertTrue(Update.offered(301, sha, 300))
        assertFalse(Update.offered(300, sha, 300))
        assertFalse(Update.offered(0, "", 300))
        assertFalse(Update.offered(301, "not-a-sha", 300))
    }
}
