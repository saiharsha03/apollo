package dev.apollo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime

class LookTest {
    private fun at(h: Int) = ZonedDateTime.of(2026, 6, 21, h, 0, 0, 0, Sun.ZONE)

    @Test fun bostonSunRisesBeforeItSets() {
        val (rise, set) = Sun.times(LocalDate.of(2026, 6, 21))
        // Midsummer in Boston: about 5:35 and 20:55.
        assertTrue(rise in 300.0..360.0)
        assertTrue(set in 1230.0..1290.0)
    }

    @Test fun weatherReadsTheAppsFileAndSurvivesJunk() {
        assertEquals(Weather(), Weather.parse(null))
        assertEquals(Weather(), Weather.parse("not json"))
    }

    @Test fun recentWorkBeatsTheClock() {
        val now = at(14).toInstant().toEpochMilli()
        val w = Weather()
        assertEquals(Doing.PAINTING, Doings.of(now, at(14), 0, now - 60_000, 0, 0, false, w))
        assertEquals(Doing.TYPING, Doings.of(now, at(14), 0, 0, now - 10_000, 0, false, w))
        assertEquals(Doing.MAIL, Doings.of(now, at(14), now - 10_000, 0, 0, 0, false, w))
        assertEquals(Doing.PIANO, Doings.of(now, at(14), 0, 0, 0, 0, true, w))
        assertEquals(Doing.BERRY, Doings.of(now, at(14), 0, 0, 0, 0, false, w))
        assertEquals(Doing.SCOPE, Doings.of(now, at(23), 0, 0, 0, 0, false, w))
        assertEquals(Doing.TEA, Doings.of(now, at(7), 0, 0, 0, 0, false, w))
    }

    @Test fun irisShowsARainbowThenAnErrandThenReady() {
        val now = 10_000_000L
        assertEquals(Doing.DELIVERED, Irises.of(now, now - 2_000, "iris", now - 2_000))
        assertEquals(Doing.AWAY, Irises.of(now, 0, "athena", now - 60_000))
        assertEquals(Doing.READY, Irises.of(now, now - 60_000, "iris", now - 60_000))
        assertEquals(Doing.READY, Irises.of(now, 0, "athena", now - 3_600_000))
    }

    @Test fun theIslandPutsWhatNeedsHimFirst() {
        val now = at(14)
        assertEquals("Athena is calling", Islands.of(true, 2, null, 0.0, null, now, 0).line)
        assertEquals("2 things need you", Islands.of(false, 2, 0L, 0.0, null, now, 0).line)
        assertEquals("Driving, 5 min", Islands.of(false, 0, 0L, 1.0, null, now, 300_000).line)
        // Otherwise what the pixel character shows, never a guessed Doing line.
        assertEquals("Web search", Islands.of(false, 0, null, 0.0, null, now, 0, "web search").line)
        assertEquals("Ready", Islands.of(false, 0, null, 0.0, null, now, 0).line)
        assertEquals("Idle", Islands.of(false, 0, null, 0.0, null, at(23), 0).line)
    }

    private fun field(w: Asks.Words, label: String) = w.fields.first { it.label == label }.value

    @Test fun anAskAlwaysShowsItsTargetNotJustHerWords() {
        val reply = Asks.words("reply", mapOf("key" to "k", "text" to "On my way", "why" to "he asked"), "Mom" to "WhatsApp")
        assertEquals("Mom in WhatsApp", field(reply, "To"))
        assertEquals("On my way", field(reply, "Text"))
        assertEquals("he asked", reply.notes.single().value)

        // Her reason can never stand in for where it goes.
        val gone = Asks.words("reply", mapOf("key" to "k", "why" to "reply to Mom"), null)
        assertEquals("a notification that is gone", field(gone, "To"))

        val url = Asks.words("open_url", mapOf("url" to "https://evil.example.com/login", "why" to "your bank"))
        assertEquals("https://evil.example.com/login", field(url, "Page"))
        assertEquals("evil.example.com", url.fields.first().let { it.value.substring(it.bold!!) })

        val call = Asks.words("call_person", mapOf("name" to "Dad", "number" to "+1 716 555 0100"))
        assertEquals("+1 716 555 0100", field(call, "Number"))

        val pay = Asks.words("pay", mapOf("why" to "rent"))
        assertEquals("not given", field(pay, "Amount"))
        assertEquals("not given", field(pay, "Payee"))

        val light = Asks.words("lights", mapOf("room" to "Bedroom", "on" to "false"))
        assertEquals("Athena wants to turn the lights off", light.title)
        assertEquals("Bedroom", field(light, "Device"))
    }

    @Test fun anAliasThatIsNotShownStillShows() {
        val pay = Asks.words("pay", mapOf("amount" to "40", "payee" to "Landlord", "to" to "Stranger"))
        assertEquals("Landlord", field(pay, "Payee"))
        assertEquals("Stranger", field(pay, "To"))
        val reply = Asks.words("reply", mapOf("key" to "k", "label" to "Mark as read", "text" to "ok"), "Mom" to "WhatsApp")
        assertEquals("Mark as read", field(reply, "Button"))
        val light = Asks.words("lights", mapOf("room" to "Bedroom", "on" to "true", "state" to "off"))
        assertEquals("off", field(light, "State"))
    }

    @Test fun anUnknownAskListsEveryArg() {
        val w = Asks.words("water_plants", mapOf("zone" to "front", "minutes" to "5", "why" to "dry week"))
        assertEquals(listOf("Minutes" to "5", "Zone" to "front"), w.fields.map { it.label to it.value })
        assertEquals("Why" to "dry week", w.notes.single().let { it.label to it.value })
    }
}
