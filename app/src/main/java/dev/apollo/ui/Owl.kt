package dev.apollo.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp

/** What Athena is doing, as the owl shows it. */
enum class Pose { IDLE, READING, LISTENING, SLEEPING, UMBRELLA, NEEDS_YOU }

private val Feather = Color(0xFFEFE3CF)
private val Tuft = Color(0xFFE2D2B8)
private val Belly = Color(0xFFF8F0E2)
private val Eye = Color(0xFF1B1D21)
private val Beak = Color(0xFFD9A35A)
private val Line = Color(0xFFC9B69A)

/**
 * Athena's face: a soft cream owl (Muse's softness, Athena's owl). Drawn in a 64 x 64 box and scaled to [size]. She blinks
 * and breathes; her pose shows what she's doing. Still when the phone's animations are off.
 */
@Composable
fun Owl(size: Dp, pose: Pose = Pose.IDLE, modifier: Modifier = Modifier) {
    val c = LocalContext.current
    val moving = remember { motionOn(c) }
    val tr = rememberInfiniteTransition(label = "owl")
    val blink by tr.animateFloat(1f, 1f, infiniteRepeatable(keyframes { durationMillis = 5200; 1f at 4900; .1f at 5000; 1f at 5150 }), label = "blink")
    val bob by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Reverse), label = "bob")
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 64f
        scale(k, k, Offset.Zero) {
            translate(0f, if (moving) -2f * bob else 0f) { owl(pose, if (moving && pose != Pose.SLEEPING) blink else 1f) }
        }
    }
}

private fun DrawScope.owl(pose: Pose, blink: Float) {
    if (pose == Pose.UMBRELLA) {
        val dome = Path().apply { moveTo(6f, 20f); quadraticTo(32f, -8f, 58f, 20f); close() }
        drawPath(dome, Color(0xFF2C3E64))
        drawLine(Color(0xFF2C3E64), Offset(32f, 8f), Offset(32f, 40f), 2.2f)
    }
    // ear tufts, body, belly
    drawLine(Tuft, Offset(17f, 13f), Offset(21f, 20f), 5f, StrokeCap.Round)
    drawLine(Tuft, Offset(47f, 13f), Offset(43f, 20f), 5f, StrokeCap.Round)
    val body = Path().apply {
        moveTo(14f, 28f)
        cubicTo(14f, 17f, 22f, 9f, 32f, 9f); cubicTo(42f, 9f, 50f, 17f, 50f, 28f)
        lineTo(50f, 40f); cubicTo(50f, 50f, 42f, 57f, 32f, 57f); cubicTo(22f, 57f, 14f, 50f, 14f, 40f); close()
    }
    drawPath(body, Feather)
    drawOval(Belly, Offset(23f, 37f), Size(18f, 16f))
    // eyes
    when (pose) {
        Pose.SLEEPING -> {
            drawArc(Eye, 200f, 140f, false, Offset(21.5f, 27f), Size(7f, 6f), style = Stroke(1.6f, cap = StrokeCap.Round))
            drawArc(Eye, 200f, 140f, false, Offset(35.5f, 27f), Size(7f, 6f), style = Stroke(1.6f, cap = StrokeCap.Round))
            drawLine(Line, Offset(50f, 12f), Offset(55f, 12f), 1.4f, StrokeCap.Round)
            drawLine(Line, Offset(55f, 12f), Offset(50f, 17f), 1.4f, StrokeCap.Round)
            drawLine(Line, Offset(50f, 17f), Offset(55f, 17f), 1.4f, StrokeCap.Round)
        }
        else -> {
            val dy = if (pose == Pose.READING) 1.6f else 0f
            val r = if (pose == Pose.LISTENING) 3.8f else 3.2f
            for (x in listOf(25f, 39f)) {
                drawOval(Eye, Offset(x - r, 30f + dy - r * blink), Size(r * 2, r * 2 * blink))
                if (blink > .5f) drawCircle(Color.White, 1f, Offset(x + 1f, 29f + dy))
            }
        }
    }
    // beak and a small smile
    drawPath(Path().apply { moveTo(30f, 35f); lineTo(32f, 38f); lineTo(34f, 35f); close() }, Beak)
    drawArc(Line, 20f, 140f, false, Offset(27f, 36f), Size(10f, 5f), style = Stroke(1.4f, cap = StrokeCap.Round))
    when (pose) {
        Pose.READING -> {
            drawRoundRect(Tone.accent, Offset(20f, 44f), Size(24f, 11f), CornerRadius(2f))
            drawLine(Color.White.copy(alpha = .7f), Offset(32f, 44.5f), Offset(32f, 54.5f), 1f)
        }
        Pose.NEEDS_YOU -> drawRect(Tone.alarm, Offset(27f, 45f), Size(10f, 10f))
        Pose.LISTENING -> {
            drawArc(Line, -40f, 80f, false, Offset(51f, 22f), Size(8f, 14f), style = Stroke(1.5f, cap = StrokeCap.Round))
            drawArc(Line, -40f, 80f, false, Offset(53f, 18f), Size(10f, 22f), style = Stroke(1.5f, cap = StrokeCap.Round))
        }
        else -> Unit
    }
}
