package dev.apollo.senses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RestoredSensesTest {
    private val min = 60_000L

    @Test fun whatsAppCallLinesAreCallsAndMessagesAreNot() {
        val c = CallRules.parse("com.whatsapp", "Amma (2)", "Missed video call")!!
        assertEquals("Amma", c.name); assertEquals(CallRules.Kind.MISSED, c.kind); assertTrue(c.video)
        assertEquals("connected", CallRules.dir(CallRules.parse("com.whatsapp", "Sam", "Ongoing group voice call")!!.kind))
        assertTrue(CallRules.parse("com.whatsapp", "Sam", "Ongoing group voice call")!!.group)
        assertNull(CallRules.dir(CallRules.Kind.INCOMING))
        assertNull(CallRules.parse("com.whatsapp", "Sam", "missed call? call me back"))
        assertNull(CallRules.parse("com.google.android.apps.messaging", "Sam", "Missed voice call"))
    }

    @Test fun callLogTypesRepeatsAndSealedPeople() {
        assertEquals("missed", CallRules.dir(android.provider.CallLog.Calls.MISSED_TYPE))
        assertEquals("outgoing", CallRules.dir(android.provider.CallLog.Calls.OUTGOING_TYPE))
        assertTrue(CallRules.repeat(0L, 19 * min)); assertFalse(CallRules.repeat(0L, 21 * min)); assertFalse(CallRules.repeat(null, 0L))
        val sealed = setOf(NoticeRules.personKey("+1 (716) 555-0100"))
        assertTrue(CallRules.sealed(null, "7165550100", sealed))
        assertFalse(CallRules.sealed("Sam", "7165550199", sealed))
    }

    @Test fun mediaIsOncePerSittingAndNeverMusicOrSealed() {
        assertTrue(MediaRules.kept("com.netflix.mediaclient", "The Rookie", "dev.phonerecall", emptySet(), emptySet()))
        assertFalse(MediaRules.kept("com.spotify.music", "Song", "dev.phonerecall", emptySet(), emptySet()))
        assertFalse(MediaRules.kept("com.netflix.mediaclient", "The Rookie", "dev.phonerecall", emptySet(), setOf("com.netflix.mediaclient")))
        assertFalse(MediaRules.kept("com.netflix.mediaclient", " ", "dev.phonerecall", emptySet(), emptySet()))
        assertTrue(MediaRules.fresh(null, 0)); assertFalse(MediaRules.fresh(0, 5 * 60 * min)); assertTrue(MediaRules.fresh(0, 6 * 60 * min))
    }

    @Test fun chargedOncePerPlugInAndWifiNames() {
        assertTrue(EventRules.full(100, 2, plugged = true, told = false))
        assertTrue(EventRules.full(97, android.os.BatteryManager.BATTERY_STATUS_FULL, plugged = true, told = false))
        assertFalse(EventRules.full(100, 2, plugged = true, told = true))
        assertFalse(EventRules.full(100, 2, plugged = false, told = false))
        assertEquals("Home 5G", EventRules.ssid("\"Home 5G\""))
        assertNull(EventRules.ssid("<unknown ssid>")); assertNull(EventRules.ssid(null))
    }

    @Test fun walksStartOnceAndShortOnesDontCount() {
        assertTrue(WalkRules.tellStart(0L, 0L, 31 * min))
        assertFalse(WalkRules.tellStart(5L, 0L, 31 * min))          // already walking
        assertFalse(WalkRules.tellStart(0L, 10 * min, 20 * min))    // told one 10 minutes ago
        assertTrue(WalkRules.counts(1L, 1L + 3 * min)); assertFalse(WalkRules.counts(1L, 1L + 2 * min)); assertFalse(WalkRules.counts(0L, 10 * min))
    }

    @Test fun setupRowsGoOnlyWhenTheyChange() {
        assertEquals("perm_read_call_log", SetupRules.permKey("android.permission.READ_CALL_LOG"))
        assertEquals(mapOf("usage" to true, "car" to false), SetupRules.changed(mapOf("usage" to true, "car" to false, "pair" to true), mapOf("usage" to false, "pair" to true)))
    }

    @Test fun healthDayMeansAndWriteChecks() {
        assertEquals(mapOf("2026-10-25" to 42.5, "2026-10-26" to 97.0), HealthRules.dailyMean(listOf("2026-10-25" to 40.0, "2026-10-25" to 45.0, "2026-10-26" to 97.0)))
        assertEquals(80.0, HealthWriteRules.kg(80.0, null)!!, 0.001)
        assertEquals(81.6466, HealthWriteRules.kg(null, 180.0)!!, 0.001)
        assertNull(HealthWriteRules.kg(null, 5.0)); assertNull(HealthWriteRules.kcal(-1.0)); assertNull(HealthWriteRules.grams(null))
        assertEquals(androidx.health.connect.client.records.MealType.MEAL_TYPE_LUNCH, HealthWriteRules.meal(" Lunch"))
    }

    @Test fun docxTextComesOutOfTheZip() {
        val xml = "<w:document><w:body><w:p><w:r><w:t>Lease &amp; deposit</w:t></w:r></w:p><w:p><w:r><w:t>Rent</w:t><w:tab/><w:t>\$1,200</w:t></w:r></w:p></w:body></w:document>"
        val zip = ByteArrayOutputStream().also { o ->
            ZipOutputStream(o).use { z ->
                z.putNextEntry(ZipEntry("[Content_Types].xml")); z.write("<x/>".toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("word/document.xml")); z.write(xml.toByteArray()); z.closeEntry()
            }
        }.toByteArray()
        assertEquals("Lease & deposit\nRent\t\$1,200", DocText.docx(ByteArrayInputStream(zip)))
        assertEquals(DocRules.How.DOCX, DocRules.how(null, "Lease.DOCX"))
        assertEquals(DocRules.How.DOCX, DocRules.how("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "x"))
    }
}
