package dev.apollo.pixel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.apollo.ui.Type
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.Calendar

/** What an agent is on right now, from the server's "activity" outbox kind. [at] is ms. */
data class Activity(val agent: String, val tool: String, val server: String, val at: Long) {
    companion object {
        val AGENTS = setOf("athena", "iris", "atlas")

        fun of(d: JSONObject): Activity? {
            val agent = d.optString("agent").takeIf { it in AGENTS } ?: return null
            return Activity(agent, d.optString("tool").take(80), d.optString("server").take(80), (d.optDouble("at", 0.0) * 1000).toLong())
        }
    }
}

/** A scene to play and the fact to show beside it. */
data class Shown(val scene: String, val label: String)

/** Picks and steps frames: pure, so the PNG sheets (PixelTest) draw exactly what the phone draws. */
object PixelEngine {
    /** A tool with nothing newer for this long is old news: the character rests and the label says when it was. */
    const val STALE_MS = 10 * 60_000L

    /** An unlit dot of the matrix. */
    const val OFF = 0xFF2E2A25.toInt()

    /** The space around each dot, as a share of its cell: tighter when the matrix is off (small sizes). */
    fun gap(matrix: Boolean) = if (matrix) 0.14f else 0.06f

    /** The scene to play: the one asked for, else the generic working scene, else the file's first. */
    fun scene(ch: PixelChar, name: String): PixelScene =
        ch.scenes[name] ?: ch.scenes[ToolScenes.FALLBACK] ?: ch.scenes.values.first()

    /** Frame [i] (wrapping) as w*h ARGB dots, row by row; 0 is empty. */
    fun frame(ch: PixelChar, scene: String, i: Int): IntArray = scene(ch, scene).frames.let { it[Math.floorMod(i, it.size)] }

    /** The scene and label for an activity, or rest when there is none or it is stale. */
    fun show(a: Activity?, now: Long, hour: Int): Shown {
        if (a == null) return Shown(ToolScenes.scene("idle", hour), "")
        val label = ToolScenes.label(a.tool, a.server)
        if (a.tool == "idle") return Shown(ToolScenes.scene("idle", hour), "idle")
        if (now - a.at > STALE_MS) return Shown(ToolScenes.scene("idle", hour), "last: $label, ${(now - a.at) / 60_000}m ago")
        return Shown(ToolScenes.scene(a.tool, hour), label)
    }

    fun hour(now: Long = System.currentTimeMillis()): Int = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
}

/**
 * The character playing its scene in a square of [size]; [matrix] draws the unlit dots too (the dot-matrix look). Below
 * 48dp (the island) the unlit dots and the gaps would be noise, so they go by default.
 */
@Composable
fun Pixel(agent: String, activity: Activity?, size: Dp, modifier: Modifier = Modifier, matrix: Boolean = size >= 48.dp) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val scene = PixelEngine.show(activity, now, PixelEngine.hour(now)).scene
    val ch = PixelArt.builtin(LocalContext.current, agent) ?: return
    val s = PixelEngine.scene(ch, scene)
    var i by remember(scene) { mutableIntStateOf(0) }
    LaunchedEffect(scene) { while (true) { delay(s.ms); i++ } }
    Canvas(modifier.size(size)) {
        val d = minOf(this.size.width / ch.w, this.size.height / ch.h)
        val x0 = (this.size.width - d * ch.w) / 2
        val y0 = (this.size.height - d * ch.h) / 2
        val gap = d * PixelEngine.gap(matrix)
        val r = CornerRadius(d * 0.3f)
        val grid = s.frames[i % s.frames.size]
        val off = Color(PixelEngine.OFF)
        for (k in grid.indices) {
            val v = grid[k]
            if (v == 0 && !matrix) continue
            drawRoundRect(if (v == 0) off else Color(v), Offset(x0 + (k % ch.w) * d + gap, y0 + (k / ch.w) * d + gap), Size(d - 2 * gap, d - 2 * gap), r)
        }
    }
}

/** An agent's character from the live feed, with the plain tool fact beside it ([label] false: the character only). */
@Composable
fun AgentPixel(agent: String, size: Dp = 96.dp, modifier: Modifier = Modifier, label: Boolean = true) {
    val all by PixelFeed.state(LocalContext.current).collectAsState()
    val a = all[agent]
    if (!label) return Pixel(agent, a, size, modifier)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Pixel(agent, a, size)
        val fact = agentLabel(agent)
        if (fact.isNotEmpty()) Text(fact, style = Type.small)
    }
}

/** The plain tool fact for an agent right now ("web search", "Gmail"), or "" when idle, stale or unknown. */
@Composable
fun agentLabel(agent: String): String {
    val all by PixelFeed.state(LocalContext.current).collectAsState()
    val a = all[agent] ?: return ""
    val now = System.currentTimeMillis()
    return if (a.tool == "idle" || now - a.at > PixelEngine.STALE_MS) "" else ToolScenes.label(a.tool, a.server)
}
