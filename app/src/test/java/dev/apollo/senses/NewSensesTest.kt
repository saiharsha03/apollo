package dev.apollo.senses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class NewSensesTest {
    private val min = 60_000L

    @Test fun aStayIsToldAfterFiveMinutesAndWhenHeLeaves() {
        val w = StayWatch()
        assertNull(w.fix(42.40, -71.00, 0, 30f))
        assertNull(w.fix(42.4003, -71.0002, 3 * min, 30f))
        val here = w.fix(42.4001, -71.0001, 6 * min, 30f)
        assertTrue(here is StayWatch.Out.Here && here.start == 0L)
        assertNull(w.fix(42.4002, -71.0001, 9 * min, 30f))           // still there: said once
        val left = w.fix(42.45, -71.05, 20 * min, 30f)
        assertTrue(left is StayWatch.Out.Left && left.start == 0L && left.end == 9 * min)
    }

    @Test fun passingThroughOrVagueFixesAreNotStays() {
        val w = StayWatch()
        w.fix(42.40, -71.00, 0, 30f)
        assertNull(w.fix(42.45, -71.05, 2 * min, 30f))               // moved on before 5 minutes: nothing to say
        assertNull(w.fix(42.45, -71.05, 10 * min, 900f))             // too vague to count
        assertNull(w.fix(42.45, -71.05, 2 * min, 30f))               // the same last-known fix again
    }

    @Test fun theNearestSavedPlaceNamesTheSpot() {
        val places = listOf(Place(1, "Home", 42.4000, -71.0000, 150.0), Place(2, "Kohl's", 42.4500, -71.0500, 150.0))
        assertEquals("Kohl's", StayWatch.placeAt(42.4501, -71.0501, places)?.name)
        assertNull(StayWatch.placeAt(42.6, -70.8, places))
    }

    @Test fun onlyHisCalendars() {
        assertTrue(CalendarRules.kept("Owner@Example.com", "com.google"))
        assertTrue(CalendarRules.kept("My calendar", "LOCAL"))
        assertFalse(CalendarRules.kept("someone@work.com", "com.google"))
    }

    @Test fun allDayEventsSitOnUtcMidnights() {
        val ny = ZoneId.of("America/New_York")
        val fri8pm = 1793404800000L       // 2026-10-30 20:00 New York (00:00 UTC on the 31st)
        assertEquals(1793318400000L, CalendarRules.allDayUtc(fri8pm, ny))   // 2026-10-30T00:00Z
        assertEquals(1793318400000L + 86_400_000L, CalendarRules.allDayEnd(fri8pm, fri8pm, ny))
    }

    @Test fun anUnchangedCalendarHasTheSameKey() {
        val a = CalEvent(1, "Dentist", 10, 20, false, "", "Owner")
        val b = CalEvent(2, "Call", 5, 6, false, "", "Owner")
        assertEquals(CalendarRules.key(listOf(a, b)), CalendarRules.key(listOf(b, a)))
        assertFalse(CalendarRules.key(listOf(a)) == CalendarRules.key(listOf(a.copy(start = 11))))
    }

    @Test fun lowBatteryIsToldOncePerStepUntilCharged() {
        assertEquals(20, BatteryRules.low(19, false, 101))
        assertNull(BatteryRules.low(18, false, 20))
        assertEquals(10, BatteryRules.low(9, false, 20))
        assertEquals(5, BatteryRules.low(4, false, 101))
        assertNull(BatteryRules.low(4, true, 101))
        assertNull(BatteryRules.low(50, false, 101))
    }

    @Test fun sharedFilesAreReadTheRightWay() {
        assertEquals(DocRules.How.PDF, DocRules.how("application/pdf", "x"))
        assertEquals(DocRules.How.PDF, DocRules.how(null, "Lease.PDF"))
        assertEquals(DocRules.How.IMAGE, DocRules.how("image/jpeg", "a.jpg"))
        assertEquals(DocRules.How.TEXT, DocRules.how("application/octet-stream", "notes.md"))
        assertEquals(DocRules.How.NONE, DocRules.how("application/zip", "a.zip"))
        assertEquals(2200, DocRules.renderWidth(1700))
        val me = "dev.phonerecall"
        assertEquals(true, DocRules.allowed("content", "com.android.providers.media.documents", me, "com.android.providers.media.module"))
        assertEquals(false, DocRules.allowed("file", null, me, null))
        assertEquals(false, DocRules.allowed("content", "dev.phonerecall.files", me, me))
        assertEquals(false, DocRules.allowed("content", "0@dev.phonerecall.files", me, "x"))
        assertEquals(false, DocRules.allowed("content", "renamed.provider", me, me))
        assertEquals(false, DocRules.allowed("content", "nobody.serves.this", me, null))
    }
}
