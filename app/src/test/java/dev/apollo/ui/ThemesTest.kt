package dev.apollo.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ThemesTest {
    @Test fun theSeededDefaultIsTheBuiltInLook() {
        val f = File("../server/seed/themes/default.json")
        assertEquals(DEFAULT_LOOK, Themes.parse(f.readText()))
    }

    @Test fun badFilesAreNull() {
        assertNull(Themes.parse("not json"))
        assertNull(Themes.parse("""{"light": {"ground": "#FFF"}}"""))
        val ok = File("../server/seed/themes/default.json").readText()
        assertNull(Themes.parse(ok.replace("\"figtree\"", "\"comic-sans\"")))
        assertNull(Themes.parse(ok.replace("\"radius\": 22", "\"radius\": 99")))
    }

    @Test fun colours() {
        assertEquals(Color(0xFF2F63EA), Themes.color("#2F63EA"))
        assertEquals(Color(0xB8FFFFFF), Themes.color("#B8FFFFFF"))
        assertNull(Themes.color("blue"))
    }
}
