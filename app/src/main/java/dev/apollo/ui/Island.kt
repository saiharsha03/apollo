package dev.apollo.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.apollo.actions.PhoneActions
import dev.apollo.muse.Muse
import dev.apollo.ossa.Ossa
import dev.apollo.senses.DriveService
import kotlinx.coroutines.delay
import java.time.ZonedDateTime

/** What the island shows: a short line beside the owl, and, opened, the live thing in full. */
@Immutable data class IslandState(val line: String, val pose: Pose, val live: Live? = null)

/** A live thing: its label, a big dot-matrix number, a detail line and how far along it is (0..1, or null). */
@Immutable data class Live(val label: String, val big: String, val detail: String, val progress: Float? = null)

/**
 * The island's state, most pressing first: Athena calling, things that need him, a drive, music, else what the agent's
 * pixel character shows ([activity]: its tool fact, "" when idle or stale; never the guessed Doing line). Pure.
 */
object Islands {
    fun of(calling: Boolean, waiting: Int, driveStartMs: Long?, driveMiles: Double, playing: String?, now: ZonedDateTime, nowMs: Long, activity: String = ""): IslandState = when {
        calling -> IslandState("Athena is calling", Pose.LISTENING)
        waiting > 0 -> IslandState(if (waiting == 1) "1 thing needs you" else "$waiting things need you", Pose.NEEDS_YOU)
        driveStartMs != null -> {
            val min = ((nowMs - driveStartMs) / 60_000).coerceAtLeast(0)
            IslandState("Driving, $min min", Pose.IDLE, Live("Driving", "$min MIN", "%.1f mi so far".format(driveMiles)))
        }
        playing != null -> IslandState(playing, Pose.LISTENING)
        activity.isNotBlank() -> IslandState(line(activity), Pose.READING)
        Sun.night(now) -> IslandState("Idle", Pose.SLEEPING)
        else -> IslandState("Ready", Pose.IDLE)
    }

    /** A tool fact as the island's line: "web search" reads "Web search". */
    fun line(activity: String) = activity.trim().replaceFirstChar { it.uppercase() }
}

/** What Athena looks to be doing right now (see [Doings.of]), live. */
@Composable
fun rememberDoing(): Doing {
    val c = LocalContext.current
    val fact by AthenaNow.factAt.collectAsState()
    val apps by AthenaNow.appsAt.collectAsState()
    val reply by AthenaNow.replyAt.collectAsState()
    val iris by AthenaNow.irisAt.collectAsState()
    val music by Muse.now.collectAsState()
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); tick = System.currentTimeMillis() } }
    val weather = remember(tick / 300_000) { Weather.read(c) }
    return Doings.of(maxOf(tick, fact, apps, reply, iris), ZonedDateTime.now(Sun.ZONE), fact, apps, reply, iris, music?.playing == true, weather)
}

/** What Iris looks to be doing (see [Irises.of]), live: her chat's last word, and her answer just in. */
@Composable
fun rememberIrisDoing(): Doing {
    val c = LocalContext.current
    val db = remember { dev.apollo.net.LocalDb.get(c) }
    val changed by db.changed.collectAsState()
    val answered by AthenaNow.irisAt.collectAsState()
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Wakes again when the rainbow should end, and every half minute for the errand window.
    LaunchedEffect(answered) { delay(6_500); tick = System.currentTimeMillis(); while (true) { delay(30_000); tick = System.currentTimeMillis() } }
    val last by androidx.compose.runtime.produceState<Pair<String, Long>?>(null, changed) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { db.last("iris") }
    }
    return Irises.of(maxOf(tick, System.currentTimeMillis()), answered, last?.first, last?.second ?: 0L)
}

@Composable
fun rememberIsland(agent: String = "athena"): IslandState {
    val activity = dev.apollo.pixel.agentLabel(agent)
    val c = LocalContext.current
    val call by Ossa.ui.collectAsState()
    val changed by PhoneActions.changed.collectAsState()
    val drive by DriveService.live.collectAsState()
    val miles by DriveService.miles.collectAsState()
    val music by Muse.now.collectAsState()
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); tick = System.currentTimeMillis() } }
    val waiting = remember(changed, tick) { PhoneActions.waiting(c).size }
    return Islands.of(call != null, waiting, drive, miles, music?.takeIf { it.playing }?.let { "${it.title}, ${it.artist}" }, ZonedDateTime.now(Sun.ZONE), tick, activity)
}

/**
 * The Dynamic Island: a black pill with Athena's face loop in a small circle and a short line. Tap and it opens with a
 * critically damped spring into her scene clip, plus the live thing in full when there is one.
 */
@Composable
fun Island(state: IslandState, doing: Doing, modifier: Modifier = Modifier, agent: Agent = Agent.ATHENA) {
    var open by rememberSaveable { mutableStateOf(false) }
    val live = state.live
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .animateContentSize(spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
                .widthIn(min = 200.dp)
                .then(if (open) Modifier.fillMaxWidth() else Modifier)
                .clip(RoundedCornerShape(if (open) 28.dp else 26.dp))
                .background(Color.Black)
                .clickable { open = !open }
                .padding(start = 6.dp, end = 18.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Compact: the agent's pixel character doing what it really is doing; open: her face video.
                dev.apollo.pixel.AgentPixel(agent.name.lowercase(), 40.dp, label = false)
                Text(state.line, style = Type.small.copy(color = Color.White, fontWeight = FontWeight.Medium, fontSize = 15.sp), maxLines = 1)
            }
            if (open) Column(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AvatarVideo(agent, doing, face = false, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp)))
                if (live != null) {
                    Text(live.big, style = Type.dots.copy(color = Color.White, fontSize = 30.sp))
                    live.progress?.let { p ->
                        Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = .18f), RoundedCornerShape(2.dp))) {
                            Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).height(3.dp).background(Color.White, RoundedCornerShape(2.dp)))
                        }
                    }
                    Text(live.detail, style = Type.small.copy(color = Color.White.copy(alpha = .8f)))
                }
            }
        }
    }
}
