package dev.apollo.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.apollo.apps.AppFiles
import dev.apollo.apps.MiniApps

/** One mode's colours: the [Tone] tokens. */
data class Palette(
    val ground: Color, val raised: Color, val soft: Color, val ink: Color, val quiet: Color, val line: Color,
    val accent: Color, val alarm: Color, val onSky: Color, val onSkyDim: Color, val mine: Color, val skyOff: Color,
)

/** A theme file (themes mini app, `<name>.json`; server/seed/themes/README.md has the format). */
data class Look(val name: String, val light: Palette, val dark: Palette, val text: String, val numbers: String, val radius: Int, val sky: Boolean)

/**
 * The look Apollo draws with: a theme file from the `themes` mini app, chosen by phone_setting theme=<name> (prefs
 * apollo_ui, key theme). The default is built in, so a missing or bad file always falls back to today's look. Tokens
 * read state, so a switch redraws every screen at once.
 */
object Themes {
    private val current = mutableStateOf(DEFAULT_LOOK)

    val look: Look get() = current.value
    /** Apollo is dark only (Owner, 2026-10-27): the theme's dark colours whatever the phone's mode. */
    val palette: Palette get() = current.value.dark

    /** Fonts a theme may name: the bundled ones only. */
    val FONTS = setOf("figtree", "doto")

    /** Reads the chosen theme file; anything missing or bad is the default (and says so in Clio). */
    fun load(c: Context) {
        val name = c.getSharedPreferences("apollo_ui", Context.MODE_PRIVATE).getString("theme", null)?.trim()?.lowercase().orEmpty()
        if (name.isEmpty() || name == "default" || !Regex("^[a-z0-9][a-z0-9-]{0,39}$").matches(name)) { current.value = DEFAULT_LOOK; return }
        val text = AppFiles.resolve(MiniApps.root(c), "themes", "$name.json")?.takeIf { it.isFile && it.length() < 64_000 }?.let { runCatching { it.readText() }.getOrNull() }
        val look = text?.let { parse(it) }
        if (look == null) dev.apollo.clio.Clio.warn("theme", "fell back to default", if (text == null) "no themes/$name.json" else "themes/$name.json is bad")
        current.value = look ?: DEFAULT_LOOK
    }

    /** A theme file's look, or null when any token is missing or isn't a colour. Pure. */
    fun parse(json: String): Look? = runCatching {
        val o = JsonParser.parseString(json).asJsonObject
        val fonts = o.getAsJsonObject("fonts") ?: JsonObject()
        val text = fonts.get("text")?.asString ?: "figtree"
        val numbers = fonts.get("numbers")?.asString ?: "doto"
        if (text !in FONTS || numbers !in FONTS) return null
        val radius = o.get("radius")?.asInt ?: 22
        if (radius !in 0..40) return null
        val light = palette(o.getAsJsonObject("light")) ?: return null
        Look(o.get("name")?.asString ?: "", light, o.getAsJsonObject("dark")?.let(::palette) ?: light, text, numbers, radius, o.get("sky")?.asBoolean ?: true)
    }.getOrNull()

    private fun palette(p: JsonObject?): Palette? {
        p ?: return null
        fun c(k: String) = color(p.get(k)?.asString) ?: throw IllegalArgumentException(k)
        return Palette(c("ground"), c("raised"), c("soft"), c("ink"), c("quiet"), c("line"), c("accent"), c("alarm"), c("on_sky"), c("on_sky_dim"), c("mine"), c("sky_off"))
    }

    /** "#RRGGBB" or "#AARRGGBB". Pure. */
    fun color(s: String?): Color? {
        val h = s?.trim()?.removePrefix("#") ?: return null
        if (!Regex("^([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$").matches(h)) return null
        return Color((if (h.length == 6) 0xFF000000L or h.toLong(16) else h.toLong(16)).toInt())
    }
}

/** Today's look (Owner, 2026-10-27); server/seed/themes/default.json says the same. Dark only: light mode keeps it. */
private val DEFAULT_PALETTE = Palette(
    ground = Color(0xFF0E1013), raised = Color(0xFF171A1E), soft = Color(0xFF22262B), ink = Color(0xFFECEEF1), quiet = Color(0xFF8B939C),
    line = Color(0xFF262A30), accent = Color(0xFF5B8CFF), alarm = Color(0xFFFF453A), onSky = Color(0xFFFFFFFF), onSkyDim = Color(0xB8FFFFFF),
    mine = Color(0xFF2A2E35), skyOff = Color(0xFF0E1013),
)
val DEFAULT_LOOK = Look("default", DEFAULT_PALETTE, DEFAULT_PALETTE, "figtree", "doto", 22, true)
