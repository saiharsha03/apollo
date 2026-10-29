package dev.apollo.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnreadTest {
    private fun m(thread: String, kind: String, at: Long) = Unread.Msg(thread, kind, at)

    @Test fun onlyChatThreadsCount() {
        val msgs = listOf(m("1", "chat", 20), m("1", "chat", 30), m("2", "facts", 20), m("4", "bank", 20), m("5", "routines", 20), m("6", "", 20), m("7", "zeus", 20))
        assertEquals(mapOf("1" to 2), Unread.count(msgs, emptyMap(), 10))
    }

    @Test fun baseAndReadingClear() {
        val msgs = listOf(m("1", "chat", 5), m("1", "chat", 20), m("1", "chat", 30))
        assertEquals(mapOf("1" to 2), Unread.count(msgs, emptyMap(), 10))
        assertEquals(mapOf("1" to 1), Unread.count(msgs, mapOf("1" to 20L), 10))
        assertEquals(emptyMap<String, Int>(), Unread.count(msgs, mapOf("1" to 30L), 10))
    }

    @Test fun archivedThreadsNeverCount() =
        assertEquals(mapOf("1" to 1), Unread.count(listOf(m("1", "chat", 20), Unread.Msg("3", "chat", 20, archived = true)), emptyMap(), 10))

    @Test fun unknownKindIsNeitherCountedNorHidden() {
        assertFalse(ChatThread("6", "Chat", 0, "", kind = "").system)
        assertTrue(ChatThread("2", "What the phone noticed", 0, "", kind = "facts").system)
    }
}
