package dev.apollo.chat

import dev.apollo.hermes.Commands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHermesTest {
    @Test fun onlyShortOneLinersAreTriedAsCommands() {
        assertTrue(Commands.chatSized("timer 10"))
        assertTrue(Commands.chatSized("  call Amma  "))
        assertFalse(Commands.chatSized("take me through the plan for the weekend trip to Toronto please"))
        assertFalse(Commands.chatSized("torch on\nand then"))
    }

    @Test fun eachThreadKeepsItsOwnDraft() {
        assertEquals("t.42", Drafts.key(Room.ATHENA, "42"))
        assertEquals("new.iris", Drafts.key(Room.IRIS, null))
    }
}
