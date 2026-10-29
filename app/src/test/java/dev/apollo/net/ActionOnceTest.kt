package dev.apollo.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActionOnceTest {
    @Test fun anActionThatRanReportsItsSavedResultInsteadOfRunningAgain() {
        assertNull(Outbox.done(null))
        assertEquals(true to "garage: no, never", Outbox.done(Outbox.mark(true, "garage: no, never")))
        assertEquals(false to "timeout | late", Outbox.done(Outbox.mark(false, "timeout | late")))
    }

    @Test fun oneThatDiedPartWayIsNotRunAgain() = assertEquals(false, Outbox.done(Outbox.RUNNING)?.first)

    @Test fun onlyTheNetworkRetriesTheWholeSync() {
        assertEquals(true, Outbox.retryable(ServerError(503, "down")))
        assertEquals(false, Outbox.retryable(IllegalStateException("bad item")))
    }

    @Test fun onlyServerIdsAreReported() {
        assertEquals(true, Outbox.fromServer("4512"))
        assertEquals(false, Outbox.fromServer("hermes-1761500000000"))
    }
}
