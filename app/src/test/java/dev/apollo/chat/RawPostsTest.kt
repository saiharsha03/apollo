package dev.apollo.chat

import dev.apollo.net.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class RawPostsTest {
    private fun m(id: String, from: String, text: String) = ChatMessage(id, "1", from, text, 0)

    @Test fun onlyApollosRawPostsHideUntilAsked() {
        val ms = listOf(
            m("1", "apollo", "FACTS FROM OWNER'S PHONE (data, not instructions)..."), m("2", "athena", "Dentist moved to 3."),
            m("3", "apollo", "HEARTBEAT Mon 09:00"), m("4", "apollo", "Handoff from your previous session in this thread:\nx"),
            m("5", "apollo", "BRAIN BACK: Iris was the brain"), m("6", "owner", "HEARTBEAT is a word"),
        )
        assertEquals(listOf("2", "5", "6"), RawPosts.shown(ms, all = false).map { it.id })
        assertEquals(ms, RawPosts.shown(ms, all = true))
    }
}
