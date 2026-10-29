package dev.apollo.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneFilesTest {
    @Test fun zeusClosetCsvReadsWithQuotes() {
        val rows = Csv.parse("id,category,kind,description\r\nt1,tops,polo,\"soft, \"\"navy\"\"\"\nb2,bottoms,chino,\n")
        assertEquals(listOf("t1", "tops", "polo", "soft, \"navy\""), rows[1])
        assertEquals(3, rows.size)
    }

    @Test fun zeusGroupsBecomeTheAppsKinds() {
        assertEquals("shoes", Csv.kindOf("footwear", "Sneaker"))
        assertEquals("outer", Csv.kindOf("outerwear", "Parka"))
        assertEquals("bottom", Csv.kindOf("bottoms", "chino"))
        assertEquals("top", Csv.kindOf("tops", "polo"))
    }

    // Voice notes must reach the WebView with an audio type, not octet-stream, or <audio> won't play them.
    @Test fun audioIsServedAsAudio() {
        assertEquals("audio/mp4", AppFiles.mime("2026-10-20-101500.m4a"))
        assertEquals("audio/mpeg", AppFiles.mime("a.MP3"))
        assertEquals("audio/ogg", AppFiles.mime("a.ogg"))
    }
}
