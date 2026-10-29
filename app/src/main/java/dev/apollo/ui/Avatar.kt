package dev.apollo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.apollo.pixel.AgentPixel
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.MutableStateFlow

/** What an agent can be doing, as her clips show it. */
enum class Doing(val line: String, val pose: Pose) {
    MAIL("Going through your mail", Pose.READING),
    TYPING("Writing", Pose.READING),
    PAINTING("Making something", Pose.READING),
    STORY("Reading by the fire", Pose.IDLE),
    BERRY("All caught up", Pose.IDLE),
    NIGHT("Working with Iris", Pose.IDLE),
    PIANO("Music on", Pose.LISTENING),
    TEA("Morning tea", Pose.IDLE),
    SCOPE("Looking ahead", Pose.IDLE),
    // Iris, the cloud.
    DELIVERED("Job done", Pose.IDLE),
    AWAY("On an errand", Pose.READING),
    READY("Ready", Pose.IDLE),
}

/** Who's talking, and the state to fall back to. Athena is the owl, Iris the cloud, Atlas the laptop: each drawn as its pixel character. */
enum class Agent(val word: String, val rest: Doing) {
    ATHENA("Athena", Doing.BERRY),
    IRIS("Iris", Doing.READY),
    ATLAS("Atlas", Doing.BERRY);

    companion object { fun of(name: String?): Agent = entries.firstOrNull { it.name.equals(name, true) || it.word.equals(name, true) } ?: ATHENA }
}

/** When the phone last saw Athena at something: a fact went to her, she wrote an app, she replied, she asked Iris. */
object AthenaNow {
    val factAt = MutableStateFlow(0L)
    val appsAt = MutableStateFlow(0L)
    val replyAt = MutableStateFlow(0L)
    val irisAt = MutableStateFlow(0L)
}

object Irises {
    /**
     * What Iris looks to be doing, from her chat: she just answered (a few seconds) -> rainbow; the last word in her chat
     * is someone else's (a job from Athena or Owner) within the last half hour -> away on it; else ready. Pure.
     */
    fun of(nowMs: Long, answeredAt: Long, lastFrom: String?, lastAt: Long): Doing = when {
        answeredAt > 0 && nowMs - answeredAt in 0..6_000 -> Doing.DELIVERED
        lastFrom != null && lastFrom != "iris" && nowMs - lastAt in 0..1_800_000 -> Doing.AWAY
        else -> Doing.READY
    }
}

object Doings {
    /**
     * What Athena looks to be doing, from what the phone last saw: recent work first (within a couple of minutes), then
     * music, then the time of day and weather. Late night she watches ahead through the telescope; evenings she reads by
     * the fire; mornings, and cold or snowy days, tea; otherwise she's caught up (berry). Pure.
     */
    fun of(nowMs: Long, now: ZonedDateTime, factAt: Long, appsAt: Long, replyAt: Long, irisAt: Long, music: Boolean, weather: Weather): Doing {
        fun recent(t: Long, win: Long) = t > 0 && nowMs - t in 0..win
        val h = now.hour
        return when {
            recent(appsAt, 120_000) -> Doing.PAINTING
            recent(irisAt, 300_000) -> Doing.NIGHT
            recent(replyAt, 60_000) -> Doing.TYPING
            recent(factAt, 90_000) -> Doing.MAIL
            music -> Doing.PIANO
            h >= 22 || h < 5 -> Doing.SCOPE
            h >= 18 || (Sun.night(now) && h >= 12) -> Doing.STORY
            h in 5..9 || weather.kind == Weather.Kind.SNOW || (weather.temp ?: 99) <= 38 -> Doing.TEA
            else -> Doing.BERRY
        }
    }
}

/**
 * An agent's picture: its pixel character, doing what it really is doing, centred in whatever box it's given (Owner,
 * 2026-10-27: "kill the videos, only use the ascii art everywhere"). [doing] and [face] are kept for callers.
 */
@Composable
fun AvatarVideo(agent: Agent, @Suppress("UNUSED_PARAMETER") doing: Doing, @Suppress("UNUSED_PARAMETER") face: Boolean, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.background(Tone.soft), contentAlignment = Alignment.Center) {
        val side = if (maxWidth < maxHeight) maxWidth else maxHeight
        AgentPixel(agent.name.lowercase(), side * 0.78f, label = false)
    }
}
