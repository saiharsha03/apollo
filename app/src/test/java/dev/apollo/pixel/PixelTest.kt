package dev.apollo.pixel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class PixelTest {
    @Test fun toolsPickTheirScene() {
        assertEquals("typing", ToolScenes.scene("Bash", 12))
        assertEquals("reading", ToolScenes.scene("app_read", 12))
        assertEquals("telescope", ToolScenes.scene("web_search", 12))
        assertEquals("painting", ToolScenes.scene("card", 12))
        assertEquals("note", ToolScenes.scene("ask_atlas", 12))
        assertEquals("telescope", ToolScenes.scene("search_threads", 12))   // another connector, by its name's words
        assertEquals("working", ToolScenes.scene("frobnicate", 12))         // unknown: generic, no guess
        assertEquals("tea", ToolScenes.scene("idle", 15))
        assertEquals("sleep", ToolScenes.scene("idle", 23))
    }

    private val builtins = PixelArt.BUILTIN.mapValues { (_, f) -> PixelArt.parse(File("src/main/assets/pixel/$f.json").readText())!! }

    @Test fun everyMappedSceneHasArtInEveryBuiltIn() {
        val used = ToolScenes.exact.values + ToolScenes.families.map { it.second } + listOf(ToolScenes.FALLBACK, "tea", "sleep", "stand")
        for ((agent, ch) in builtins) used.forEach { assertTrue("$agent $it", it in ch.scenes) }
    }

    @Test fun badFilesFailSoft() {
        val pal = """"palette": {"#": "#fff", "x": "crimson", "..": "#000"}"""
        assertEquals(null, PixelArt.parse("""{"w": 2, "h": 1, $pal, "scenes": {"a": {"frames": [["#"]]}}}"""))   // row too short
        val ch = PixelArt.parse("""{"w": 2, "h": 1, $pal, "scenes": {"ok": {"ms": 5, "frames": [["#."]]}, "bad": {"frames": [["x."]]}, "Bad Name": {"frames": [["##"]]}}}""")!!
        assertEquals(setOf("ok"), ch.scenes.keys)                     // a bad colour, char or name drops the scene, not the file
        assertEquals(40L, ch.scenes.getValue("ok").ms)                // ms clamped
        assertEquals(0xFFFFFFFF.toInt(), ch.scenes.getValue("ok").frames[0][0])
        assertEquals(null, PixelArt.parse("""{"w": 99, "h": 1, $pal, "scenes": {}}"""))
        assertEquals(null, PixelArt.parse("not json"))
        assertEquals(null, PixelArt.parse("[".repeat(40)))
        assertEquals(null, PixelArt.parse(" ".repeat(PixelArt.MAX_BYTES + 1)))
        val red = PixelArt.parse("""{"w": 1, "h": 1, "palette": {"r": "red"}, "scenes": {"a": {"frames": [["r"]]}}}""", red = 0xFF123456.toInt())!!
        assertEquals(0xFF123456.toInt(), red.scenes.getValue("a").frames[0][0])   // "red" is the Theme's red, handed in
    }

    @Test fun labelsAreThePlainFact() {
        assertEquals("web search", ToolScenes.label("web_search", ""))
        assertEquals("app read", ToolScenes.label("app_read", "apollo"))
        assertEquals("Gmail", ToolScenes.label("search_threads", "Gmail"))
        assertEquals("bash", ToolScenes.label("Bash", ""))
    }

    @Test fun staleOrMissingActivityRests() {
        val a = Activity("iris", "web_fetch", "", 0L)
        assertEquals(Shown("telescope", "web fetch"), PixelEngine.show(a, 60_000L, 12))
        assertEquals(Shown("tea", "last: web fetch, 12m ago"), PixelEngine.show(a, 12 * 60_000L, 12))
        assertEquals(Shown("tea", ""), PixelEngine.show(null, 0L, 12))
    }

    /** The sheets to look at: every character x scene x frame, big and at Dynamic Island size. In app/build/pixel-sheets. */
    @Test fun drawTheSheets() {
        val out = File("build/pixel-sheets").apply { mkdirs() }
        sheet(File(out, "pixel-sheet-96dp.png"), 12, matrix = true)
        sheet(File(out, "pixel-sheet-island.png"), 4, matrix = false)
    }

    private fun sheet(f: File, dot: Int, matrix: Boolean) {
        val chars = builtins.values.toList()
        val scenes = chars[0].scenes.keys.toList()
        val most = chars[0].scenes.values.maxOf { it.frames.size }
        val pad = maxOf(8, dot * 2)
        val cw = chars[0].w * dot + pad
        val chh = chars[0].h * dot + pad
        val img = BufferedImage(pad + chars.size * most * cw + (chars.size - 1) * pad, pad + scenes.size * chh, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = java.awt.Color(0x1C1A17); g.fillRect(0, 0, img.width, img.height)
        scenes.forEachIndexed { si, scene ->
            chars.forEachIndexed { ai, ch ->
                repeat(PixelEngine.scene(ch, scene).frames.size) { fi ->
                    val grid = PixelEngine.frame(ch, scene, fi)
                    val ox = pad + (ai * most + fi) * cw + ai * pad; val oy = pad + si * chh
                    val gap = dot * PixelEngine.gap(matrix)
                    for (k in grid.indices) {
                        if (grid[k] == 0 && !matrix) continue
                        g.color = java.awt.Color(if (grid[k] == 0) PixelEngine.OFF else grid[k], true)
                        g.fill(java.awt.geom.RoundRectangle2D.Float(ox + (k % ch.w) * dot + gap, oy + (k / ch.w) * dot + gap, dot - 2 * gap, dot - 2 * gap, dot * 0.6f, dot * 0.6f))
                    }
                }
            }
        }
        g.dispose()
        ImageIO.write(img, "png", f)
    }
}
