package dev.apollo.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.apollo.pixel.PixelArt
import dev.apollo.pixel.PixelEngine
import dev.apollo.pixel.ToolScenes
import dev.apollo.ui.Tone
import dev.apollo.ui.Type

/** Which mark goes beside a sender's name in the chat (Owner, 2026-10-28: each knows what came from whom). */
enum class Mark { AGENT, APOLLO, CLAUDE, NONE }

object SenderMarks {
    /** The agents get their pixel character; Apollo (and a blank sender) its ring; Claude Code a prompt; Owner none (his bubbles are his). Pure. */
    fun of(sender: String): Mark = when (sender.lowercase()) {
        "athena", "iris", "atlas" -> Mark.AGENT
        "apollo", "" -> Mark.APOLLO
        "claude" -> Mark.CLAUDE
        else -> Mark.NONE
    }
}

/** The small mark beside a bubble's name: an agent's character standing still, Apollo's hollow ring, Claude Code's ">_". */
@Composable
internal fun SenderMark(sender: String, size: Dp = 18.dp) {
    when (SenderMarks.of(sender)) {
        Mark.AGENT -> PixelStill(sender.lowercase(), size)
        Mark.APOLLO -> Box(Modifier.size(size).border(1.5.dp, Tone.quiet, CircleShape))
        Mark.CLAUDE -> Box(Modifier.size(size).background(Tone.raised, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
            Text(">_", style = Type.small.copy(color = Tone.ink, fontFamily = FontFamily.Monospace, fontSize = 8.sp, lineHeight = 8.sp))
        }
        Mark.NONE -> {}
    }
}

/** The character's first resting frame: a list of bubbles stays still (Pixel animates; one per bubble would be noise). */
@Composable
private fun PixelStill(agent: String, size: Dp) {
    val ch = PixelArt.builtin(LocalContext.current, agent) ?: return
    val grid = remember(ch) { PixelEngine.frame(ch, ToolScenes.scene("idle", 12), 0) }
    Canvas(Modifier.size(size)) {
        val d = minOf(this.size.width / ch.w, this.size.height / ch.h)
        val x0 = (this.size.width - d * ch.w) / 2
        val y0 = (this.size.height - d * ch.h) / 2
        val gap = d * PixelEngine.gap(false)
        for (k in grid.indices) {
            val v = grid[k]
            if (v == 0) continue
            drawRoundRect(Color(v), Offset(x0 + (k % ch.w) * d + gap, y0 + (k / ch.w) * d + gap), Size(d - 2 * gap, d - 2 * gap), CornerRadius(d * 0.3f))
        }
    }
}
