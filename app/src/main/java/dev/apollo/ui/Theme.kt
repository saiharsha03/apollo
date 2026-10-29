package dev.apollo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.apollo.R

/*
 * Apollo's look (Owner, 2026-10-26, docs/LOOK.html): a living weather sky on Home with Liquid Glass over it, a Dynamic
 * Island, Athena's owl, dark surfaces everywhere else (dark only, 2026-10-27). Nothing OS touches: dot-matrix numbers (Doto) and red
 * only for what needs him. Screens use these tokens, never raw colours (the sky's own colours live in Sky.kt).
 */
object Tone {
    private val p get() = Themes.palette
    /** The dark surface behind every screen but Home. */
    val ground get() = p.ground
    /** A panel or bubble on [ground]. */
    val raised get() = p.raised
    /** Her bubbles and quiet fills. */
    val soft get() = p.soft
    val ink get() = p.ink
    val quiet get() = p.quiet
    val line get() = p.line
    /** The one action colour: Allow, Send, the selected place. (Named saffron for older code; it is Apollo blue now.) */
    val saffron get() = p.accent
    val accent get() = saffron
    /** Text on [accent] and [alarm] fills: the ground's dark (white on the blue is under 4.5:1). */
    val onAccent get() = p.ground
    /** Nothing red: only for what needs him, and errors. */
    val alarm get() = p.alarm
    /** Text and marks over the sky and glass. */
    val onSky get() = p.onSky
    val onSkyDim get() = p.onSkyDim
    /** His own bubbles ([ink] text on them). */
    val mine get() = p.mine
    /** Home's ground when the theme turns the sky off (under [onSky] text). */
    val skyOff get() = p.skyOff
    /** The theme's corner radius for cards and buttons. */
    val radius get() = Themes.look.radius.dp
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
object Fonts {
    private fun font(res: Int, w: Int) = Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    private fun res(name: String) = if (name == "doto") R.font.doto else R.font.figtree
    private val families = java.util.concurrent.ConcurrentHashMap<String, FontFamily>()
    private fun family(name: String, weights: List<Int>) = families.getOrPut(name + weights) { FontFamily(weights.map { font(res(name), it) }) }

    /** Every word (the theme's text font; Figtree by default). */
    val text get() = family(Themes.look.text, listOf(400, 500, 600, 700))
    /** Numbers only: times, temperatures, minutes (the theme's numbers font; Doto by default). */
    val dots get() = family(Themes.look.numbers, listOf(700, 900))
}

object Type {
    val title get() = TextStyle(fontFamily = Fonts.text, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Tone.ink, letterSpacing = (-0.3).sp)
    val heading get() = TextStyle(fontFamily = Fonts.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Tone.ink)
    val body get() = TextStyle(fontFamily = Fonts.text, fontSize = 15.sp, color = Tone.ink, lineHeight = 21.sp)
    val small get() = TextStyle(fontFamily = Fonts.text, fontSize = 13.sp, color = Tone.quiet, lineHeight = 18.sp)
    /** Uppercase labels over cards ("NEEDS YOU", "TONIGHT"). */
    val kicker get() = TextStyle(fontFamily = Fonts.text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp, color = Tone.quiet)
    /** Dot-matrix numbers. */
    val bigDots get() = TextStyle(fontFamily = Fonts.dots, fontSize = 88.sp, fontWeight = FontWeight.Black, color = Tone.onSky, letterSpacing = (-2).sp, lineHeight = 84.sp)
    val dots get() = TextStyle(fontFamily = Fonts.dots, fontSize = 16.sp, fontWeight = FontWeight.Black, color = Tone.ink)
}

/** Dark whatever the phone's mode: dark colours, light status and navigation bar icons. */
@Composable
fun ApolloTheme(content: @Composable () -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) androidx.compose.runtime.SideEffect {
        (view.context as? android.app.Activity)?.window?.let {
            androidx.core.view.WindowCompat.getInsetsController(it, view).apply { isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false }
        }
    }
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Tone.ground, surface = Tone.raised, primary = Tone.saffron, onPrimary = Tone.onAccent, onBackground = Tone.ink, onSurface = Tone.ink,
            surfaceVariant = Tone.soft, onSurfaceVariant = Tone.quiet, outline = Tone.quiet, outlineVariant = Tone.line, error = Tone.alarm, onError = Tone.onAccent,
            surfaceContainer = Tone.raised, surfaceContainerHigh = Tone.soft, surfaceContainerHighest = Tone.soft, surfaceContainerLow = Tone.raised,
        ),
        content = content,
    )
}

/** A tappable row: a line and an optional quiet second line. */
@Composable
fun Row2(title: String, sub: String? = null, end: String? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body.copy(fontWeight = FontWeight.SemiBold))
            sub?.let { Text(it, style = Type.small) }
        }
        end?.let { Text(it, style = Type.small.copy(color = Tone.saffron, fontWeight = FontWeight.SemiBold)) }
    }
}

/** A Muse-style button: the primary one filled blue, the other soft grey. */
@Composable
fun Action(label: String, enabled: Boolean = true, primary: Boolean = true, onClick: () -> Unit) {
    Text(
        label,
        style = Type.body.copy(fontWeight = FontWeight.SemiBold, color = if (!enabled) Tone.quiet else if (primary) Tone.onAccent else Tone.ink),
        modifier = Modifier.background(if (!enabled) Tone.soft else if (primary) Tone.saffron else Tone.soft, RoundedCornerShape((Tone.radius - 6.dp).coerceAtLeast(0.dp)))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 11.dp),
    )
}
