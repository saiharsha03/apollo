package dev.apollo.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** What the Ask sheet now reads replies with (messageOf): the thread's `sender`, and its seconds as ms. */
class MessageOfTest {
    @Test fun theThreadsSenderWinsAndTheOutboxFromIsTheFallback() {
        assertEquals("athena", senderOf("athena", ""))
        assertEquals("athena", senderOf("", "athena"))
    }

    @Test fun serverSecondsBecomeMs() {
        assertEquals(1_761_500_000_500L, Chat.ms(1_761_500_000.5))
    }
}
