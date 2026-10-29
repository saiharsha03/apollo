package dev.apollo.chat

import dev.apollo.home.CardActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThreadLinkTest {
    @Test fun aThreadIdIsDigitsOnly() {
        assertEquals("42", ThreadLink.parse("thread:42"))
        assertEquals("7", ThreadLink.parse(" Thread: 7 "))
        for (bad in listOf(null, "", "thread:", "thread:abc", "thread:1/../2", "app:thread", "42")) assertNull(bad, ThreadLink.parse(bad))
    }

    @Test fun aCardButtonOpensTheThread() {
        assertEquals(CardActions.OpenThread("12"), CardActions.parse("thread:12"))
        assertNull(CardActions.parse("thread:x"))
    }

    @Test fun eachAgentHasItsRoom() {
        assertEquals(Room.IRIS, ThreadLink.room("iris"))
        assertEquals(Room.GROUP, ThreadLink.room("group"))
        assertEquals(Room.ATHENA, ThreadLink.room("someone"))
    }
}
