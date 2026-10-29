package dev.apollo.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class SenderMarkTest {
    @Test fun everySenderHasItsOwnNameAndMark() {
        assertEquals(listOf("Owner", "Athena", "Iris", "Atlas", "Apollo", "Claude Code"),
            listOf("owner", "athena", "iris", "atlas", "apollo", "claude").map { Chat.name(it) })
        assertEquals(listOf(Mark.NONE, Mark.AGENT, Mark.AGENT, Mark.AGENT, Mark.APOLLO, Mark.CLAUDE),
            listOf("owner", "athena", "iris", "atlas", "apollo", "claude").map { SenderMarks.of(it) })
    }

    @Test fun claudeCodeIsNeverShownAsAnotherAgentsJob() = assertEquals(null, Chat.from(Room.IRIS, "claude"))
}
