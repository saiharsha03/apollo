package dev.apollo.pan

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class PanFeedTest {
    private val zone = ZoneId.of("America/New_York")
    private val day = 86_400_000L
    private val items = listOf(
        PanLine(10 * day + 43_200_000, "postcard", "old card"), PanLine(12 * day + 43_200_000, "postcard", "new card", "Plaza"),
        PanLine(12 * day + 50_000_000, "reply", "Hi Owner. Diary: walked"), PanLine(11 * day + 43_200_000, "diary", "walked"), PanLine(12 * day + 45_000_000, "status", "ok"),
    )

    @Test fun latestPostcardOnTopThenNewestFirstByDay() {
        assertEquals("new card", PanFeed.top(items)?.text)
        val days = PanFeed.days(items, zone)
        assertEquals(listOf(LocalDate.of(1970, 1, 13), LocalDate.of(1970, 1, 12), LocalDate.of(1970, 1, 11)), days.map { it.first })
        assertEquals(listOf("Hi Owner.", "ok"), days[0].second.map { it.text })
        assertEquals(listOf("old card"), days[2].second.map { it.text })
    }

    @Test fun onlyShownKindsWithWords() =
        assertEquals(listOf(PanLine(3, "diary", "y")), PanFeed.shown(listOf(PanLine(1, "event", "x"), PanLine(2, "diary", ""), PanLine(3, "diary", "y"))))
}
