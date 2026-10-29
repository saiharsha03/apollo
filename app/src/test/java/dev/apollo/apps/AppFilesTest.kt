package dev.apollo.apps

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AppFilesTest {
    @Test fun aPageCantFloodHisAsksWithLinks() {
        val t = AskThrottle(sameMs = 10_000, perMinute = 3)
        assertTrue(t.allow("https://a", 0))
        assertFalse(t.allow("https://a", 5_000))           // a redirect loop: once per url per 10 s
        assertTrue(t.allow("https://b", 5_000)); assertTrue(t.allow("https://a", 10_000))
        assertFalse(t.allow("https://c", 11_000))          // three a minute
        assertTrue(t.allow("https://c", 60_000))
    }

    // One origin for every app: a page reaches another app's files only when that app isn't secure, and never its media.
    @Test fun aPageCantFetchASecureAppsFilesOrAnotherAppsMedia() {
        assertTrue(AppFiles.crossAppOk("money", "money", mainFrame = false, targetSecure = true, media = true))
        assertTrue(AppFiles.crossAppOk("money", "weather", mainFrame = true, targetSecure = true, media = false))
        assertFalse(AppFiles.crossAppOk("money", "weather", mainFrame = false, targetSecure = true, media = false))
        assertFalse(AppFiles.crossAppOk("calliope", "weather", mainFrame = false, targetSecure = false, media = true))
        assertTrue(AppFiles.crossAppOk("weather", "drives", mainFrame = false, targetSecure = false, media = false))
    }

    @Test fun onlyOurOriginParses() {
        assertEquals("weather" to "pages/index.html", AppFiles.parse("https://app.apollo.local/weather/pages/index.html"))
        assertNull(AppFiles.parse("http://app.apollo.local/weather/x.html"))
        assertNull(AppFiles.parse("https://app.apollo.local:444/weather/x.html"))
        assertNull(AppFiles.parse("https://evil.example/weather/x.html"))
        assertNull(AppFiles.parse("https://app.apollo.local/Weather/x.html"))
    }

    @Test fun pathsStayInsideTheirApp() {
        val root = Files.createTempDirectory("apps").toFile()
        File(root, "weather/pages").mkdirs(); File(root, "weather/pages/index.html").writeText("x")
        File(root, "drives").mkdirs(); File(root, "drives/data.db").writeText("y")
        assertEquals("index.html", AppFiles.resolve(root, "weather", "pages/index.html")?.name)
        assertNull(AppFiles.resolve(root, "weather", "../drives/data.db"))
        assertNull(AppFiles.resolve(root, "weather", "pages/../../drives/data.db"))
        assertNull(AppFiles.resolve(root, "weather", ".hidden"))
        assertNull(AppFiles.resolve(root, "weather", "pages/missing.html"))
        assertNull(AppFiles.resolve(root, "drives", "data.db"))           // its tables: apollo.sql only
    }

    @Test fun pagesMayOnlyRead() {
        assertTrue(AppFiles.readOnly("SELECT * FROM trips"))
        assertTrue(AppFiles.readOnly("with t as (select 1) select * from t;"))
        assertFalse(AppFiles.readOnly("DELETE FROM trips"))
        assertFalse(AppFiles.readOnly("SELECT 1; DELETE FROM trips"))
        assertFalse(AppFiles.readOnly("WITH x AS (SELECT 1) DELETE FROM trips"))
        assertFalse(AppFiles.readOnly("PRAGMA writable_schema=1"))
        assertFalse(AppFiles.readOnly("ATTACH 'x' AS y"))
    }

    @Test fun onlyAllowedHostsAreReached() {
        val hosts = setOf("api.open-meteo.com")
        assertTrue(AppFiles.allowed("https://api.open-meteo.com/v1/forecast?x=1", hosts))
        assertFalse(AppFiles.allowed("http://api.open-meteo.com/v1/forecast", hosts))
        assertFalse(AppFiles.allowed("https://api.open-meteo.com:8443/v1/forecast", hosts))
        assertFalse(AppFiles.allowed("https://user@api.open-meteo.com/", hosts))
        assertFalse(AppFiles.allowed("https://evil.example/", hosts))
    }

    @Test fun markdownIsEscaped() {
        val h = Markdown.html("# Hi\n- **a** <script>\n[x](https://e.com)")
        assertTrue(h.contains("<h1>Hi</h1>") && h.contains("<b>a</b>") && h.contains("&lt;script&gt;") && h.contains("<a href=\"https://e.com\">x</a>"))
        assertFalse(h.contains("<script>"))
    }

    @Test fun tapZonesFollowTheElements() {
        assertEquals(0, Widgets.zone(1f, 1f, 300, 300))
        assertEquals(8, Widgets.zone(299f, 299f, 300, 300))
        val labels = Widgets.labels(listOf("refresh" to floatArrayOf(0f, 0f, 300f, 100f)), 300, 300)
        assertArrayEquals(arrayOf<String?>("refresh", "refresh", "refresh", null, null, null, null, null, null), labels)
    }

    @Test fun theShimGoesInFirst() {
        assertTrue(AppWeb.inject("<html><head><title>t</title></head></html>").startsWith("<html><head><script src=\"https://app.apollo.local/_apollo.js\"></script><title>"))
        assertTrue(AppWeb.inject("<p>x</p>").startsWith("<script"))
    }
}

class WidgetJsonTest {
    // WidgetJson.html uses org.json, which plain JVM tests don't have; the countdown math is pure.
    @Test fun countdowns() {
        val now = java.time.LocalDateTime.of(2026, 10, 26, 10, 0)
        assertEquals("2h 30m", WidgetJson.left("2026-10-26T12:30", now))
        assertEquals("3d 0h", WidgetJson.left("2026-10-29T10:00", now))
        assertEquals("now", WidgetJson.left("2026-10-25T10:00", now))
    }
}
