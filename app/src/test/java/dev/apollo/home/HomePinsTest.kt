package dev.apollo.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePinsTest {
    private val apps = mapOf("money" to emptyList(), "weather" to listOf("widgets/now.html"))

    @Test fun parseKeepsKnownAppsAndWidgetsOnce() {
        assertEquals(listOf(Pin("money"), Pin("weather", "widgets/now.html")),
            HomePins.parse("app:money, widget:weather/widgets/now.html,app:gone,widget:weather/widgets/old.html,app:money,money", apps))
        assertEquals(emptyList<Pin>(), HomePins.parse(null, apps))
    }

    @Test fun toggleAddsAtTheEndAndRemoves() {
        val w = Pin("weather", "widgets/now.html")
        assertEquals("app:money,widget:weather/widgets/now.html", HomePins.toggle("app:money", w))
        assertEquals("app:money", HomePins.toggle("app:money,widget:weather/widgets/now.html", w))
        assertTrue(HomePins.has("app:money", Pin("money")))
        assertFalse(HomePins.has("app:money", w))
        val full = (1..HomePins.CAP).joinToString(",") { "app:a$it" }
        assertEquals("app:a2", HomePins.toggle(full, Pin("money")).split(',').first())
    }
}
