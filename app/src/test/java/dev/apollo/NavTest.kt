package dev.apollo

import dev.apollo.chat.Room
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavTest {
    private val apps = mapOf("money" to "Money", "fuel" to "Fuel", "trips" to "Trips", "closet" to "Closet")
    private fun words(raw: String?) = Nav.order(raw, apps).let { (bar, more) -> bar.map { it.word } to more.map { it.word } }

    @Test fun placesOnlyKeepTheOldRule() {
        assertEquals(listOf("Home", "Chat", "Iris", "Apps", "More") to emptyList<String>(), words(null))
        assertEquals(listOf("Chat", "Home", "Apps", "Iris", "More") to emptyList<String>(), words("chat, home,apps"))
    }

    @Test fun appEntriesJoinTheBarWithHomeAndMore() {
        assertEquals(listOf("Home", "Chat", "Money", "Iris", "More") to listOf("Apps"), words("chat,app:money,iris,more"))
        val (bar, _) = Nav.order("app:money", apps)
        assertEquals("app:money", bar[1].sub)
    }

    @Test fun unknownAppsAreSkippedAndExtrasGoUnderMore() {
        assertEquals(listOf("Home", "Chat", "Money", "More") to listOf("Iris", "Apps"), words("chat,app:gone,app:money,app:money"))
        assertEquals(listOf("Home", "Chat", "Money", "Fuel", "Trips", "More") to listOf("Closet", "Iris", "Apps"),
            words("home,chat,app:money,app:fuel,app:trips,app:closet,more"))
    }

    @Test fun homeListedLateStillGetsItsSlot() {
        assertEquals(listOf("Chat", "Money", "Fuel", "Trips", "Home", "More") to listOf("Closet", "Iris", "Apps"),
            words("chat,app:money,app:fuel,app:trips,app:closet,home"))
    }

    private val home = Spot(Place.HOME)
    private val more = Spot(Place.MORE)
    private val lemnos = Spot(Place.MORE, "lemnos")
    private val thread = Spot(Place.CHAT, room = Room.IRIS, thread = "4")

    @Test fun backReturnsToWhereHeCameFrom() {
        var stack = BackStack.push(emptyList(), more, lemnos)
        assertEquals(listOf(more), stack)
        stack = BackStack.push(stack, lemnos, thread)
        assertEquals(listOf(more, lemnos), stack)
        assertEquals(lemnos to listOf(more), BackStack.pop(stack, thread))
    }

    @Test fun noLoopsOrRepeats() {
        assertEquals(emptyList<Spot>(), BackStack.push(emptyList(), more, more))
        // Going to a spot already behind him goes back to it.
        assertEquals(listOf(home), BackStack.push(listOf(home, more), lemnos, more))
        assertEquals(BackStack.MAX, (1..50).fold(emptyList<Spot>()) { s, i -> BackStack.push(s, Spot(Place.MORE, "app:$i"), Spot(Place.MORE, "app:${i + 1}")) }.size)
    }

    @Test fun nothingBehindGoesHomeThenLeaves() {
        assertEquals(Spot(Place.HOME, room = Room.IRIS) to emptyList<Spot>(), BackStack.pop(emptyList(), thread))
        assertNull(BackStack.pop(emptyList(), home))
    }

    @Test fun spotsSurviveSaving() {
        assertEquals(thread, Spot.of(thread.wire))
        assertEquals(lemnos, Spot.of(lemnos.wire))
        assertNull(Spot.of("junk"))
    }
}
