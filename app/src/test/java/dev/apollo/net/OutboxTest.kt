package dev.apollo.net

import dev.apollo.chat.Chat
import dev.apollo.chat.Room
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxTest {
    @Test fun everyContractKindHasItsHandler() {
        val kinds = mapOf("card" to Route.CARD, "notify" to Route.NOTIFY, "message" to Route.MESSAGE, "apps" to Route.APPS,
            "action" to Route.ACTION, "rule" to Route.RULE, "call" to Route.CALL, "activity" to Route.ACTIVITY)
        kinds.forEach { (k, r) -> assertEquals(k, r, Outbox.route(k)) }
    }

    @Test fun anUnknownKindIsKeptNotFatal() = assertEquals(Route.UNKNOWN, Outbox.route("hologram"))

    @Test fun appPathsStayInsideTheFolder() {
        assertTrue(Outbox.safePath("drives", "pages/home.html"))
        assertTrue(Outbox.safePath("weather", "data.db"))
        assertFalse(Outbox.safePath("drives", "../calypso/vault.db"))
        assertFalse(Outbox.safePath("drives", "/etc/passwd"))
        assertFalse(Outbox.safePath("drives", "pages//x"))
        assertFalse(Outbox.safePath("Drives", "a"))
        assertFalse(Outbox.safePath("../x", "a"))
    }

    @Test fun aNewThreadGoesToItsChatsAgentAnExistingOneSendsNoTo() {
        assertEquals("group", Chat.to(Room.GROUP, null))
        assertEquals("iris", Chat.to(Room.IRIS, null))
        assertEquals(null, Chat.to(Room.IRIS, "t1"))
    }

    @Test fun anotherAgentsMessageInAChatIsMarkedFromThem() {
        assertEquals("from Athena", Chat.from(Room.IRIS, "athena"))
        assertEquals(null, Chat.from(Room.IRIS, "iris"))
        assertEquals(null, Chat.from(Room.IRIS, "owner"))
        assertEquals(null, Chat.from(Room.GROUP, "athena"))
        assertEquals(null, Chat.from(Room.IRIS, "apollo"))
    }

    @Test fun apolloAndOtherSendersAreNamedAsThemselves() {
        assertEquals("Iris", Chat.name("iris"))
        assertEquals("Apollo", Chat.name("apollo"))
        assertEquals("Apollo", Chat.name(""))
        assertEquals("Watchdog", Chat.name("watchdog"))
    }

    @Test fun aNewThreadIsTitledByItsFirstLine() = assertEquals("Plan the trip", Chat.title("  Plan the trip\nto Delhi"))
}
