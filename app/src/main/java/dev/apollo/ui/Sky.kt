package dev.apollo.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** What the weather app last wrote (apollo-apps/weather/now.json: {"kind", "temp", "summary"}), or clear when there's none. */
@Immutable data class Weather(val kind: Kind = Kind.CLEAR, val temp: Int? = null, val summary: String? = null) {
    enum class Kind { CLEAR, CLOUDY, RAIN, SNOW }

    companion object {
        fun parse(json: String?): Weather = runCatching {
            val o = JSONObject(json ?: return Weather())
            val k = when (o.optString("kind").lowercase()) {
                "rain", "drizzle", "showers", "storm", "thunder" -> Kind.RAIN
                "snow", "sleet" -> Kind.SNOW
                "cloudy", "clouds", "overcast", "fog" -> Kind.CLOUDY
                else -> Kind.CLEAR
            }
            Weather(k, if (o.has("temp")) o.optDouble("temp").let { Math.round(it).toInt() } else null, o.optString("summary").ifBlank { null })
        }.getOrDefault(Weather())

        fun read(c: Context): Weather = parse(File(c.filesDir, "apollo-apps/weather/now.json").takeIf { it.isFile }?.readText())
    }
}

/** Sunrise and sunset, and where now sits in the day. Pure (NOAA's simple solar position), so it is tested. */
object Sun {
    /** Boston, NY. */
    const val LAT = 42.386
    const val LON = -71.078
    val ZONE: ZoneId = ZoneId.of("America/New_York")

    /** Sunrise and sunset as minutes after local midnight. */
    fun times(date: LocalDate, lat: Double = LAT, lon: Double = LON, zone: ZoneId = ZONE): Pair<Double, Double> {
        val n = date.dayOfYear
        val g = 2 * PI / 365 * (n - 1)
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) + 0.000907 * sin(2 * g) - 0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val eqt = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val la = Math.toRadians(lat)
        val ha = Math.toDegrees(acos((cos(Math.toRadians(90.833)) / (cos(la) * cos(decl)) - tan(la) * tan(decl)).coerceIn(-1.0, 1.0)))
        val offset = zone.rules.getOffset(date.atStartOfDay(zone).toInstant()).totalSeconds / 60.0
        val noon = 720 - 4 * lon - eqt + offset
        return (noon - 4 * ha) to (noon + 4 * ha)
    }

    /** The sky's four colours (top to bottom) for this minute, eased between night, dawn, day and dusk. */
    fun palette(now: ZonedDateTime, weather: Weather.Kind): List<Color> {
        val (rise, set) = times(now.toLocalDate(), zone = now.zone)
        val m = now.hour * 60 + now.minute + now.second / 60.0
        val (a, b, f) = when {
            m < rise - 45 -> Triple(NIGHT, NIGHT, 0f)
            m < rise -> Triple(NIGHT, DAWN, ((m - (rise - 45)) / 45).toFloat())
            m < rise + 60 -> Triple(DAWN, DAY, ((m - rise) / 60).toFloat())
            m < set - 60 -> Triple(DAY, DAY, 0f)
            m < set -> Triple(DAY, DUSK, ((m - (set - 60)) / 60).toFloat())
            m < set + 45 -> Triple(DUSK, NIGHT, ((m - set) / 45).toFloat())
            else -> Triple(NIGHT, NIGHT, 0f)
        }
        val base = a.indices.map { lerp(a[it], b[it], f) }
        val grey = if (night(now)) GREY_NIGHT else GREY_DAY
        val wet = when (weather) { Weather.Kind.RAIN, Weather.Kind.SNOW -> .62f; Weather.Kind.CLOUDY -> .4f; Weather.Kind.CLEAR -> 0f }
        return base.indices.map { lerp(base[it], grey[it], wet) }
    }

    fun night(now: ZonedDateTime): Boolean {
        val (rise, set) = times(now.toLocalDate(), zone = now.zone)
        val m = now.hour * 60 + now.minute
        return m < rise - 20 || m > set + 20
    }

    private val NIGHT = listOf(Color(0xFF0B1426), Color(0xFF14213B), Color(0xFF1E2E50), Color(0xFF2A3B62))
    // Dark only (2026-10-27): the day skies are deep too, so white text reads on every part of them.
    private val DAWN = listOf(Color(0xFF1E2C4E), Color(0xFF3E4A6A), Color(0xFF7A5A58), Color(0xFF8E6650))
    private val DAY = listOf(Color(0xFF14396B), Color(0xFF1D4F8A), Color(0xFF2A64A3), Color(0xFF3672B0))
    private val DUSK = listOf(Color(0xFF1A2742), Color(0xFF34405E), Color(0xFF6E5254), Color(0xFF85604C))
    private val GREY_DAY = listOf(Color(0xFF2E3846), Color(0xFF3B4655), Color(0xFF485463), Color(0xFF54606E))
    private val GREY_NIGHT = listOf(Color(0xFF10161F), Color(0xFF1A2230), Color(0xFF263142), Color(0xFF323E52))
}

/** Settings > Accessibility > Remove animations (animator scale 0) keeps the sky still. */
fun motionOn(c: Context): Boolean = runCatching { Settings.Global.getFloat(c.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }.getOrDefault(true)

/** The living sky: colour by Boston's real time of day, drifting clouds, rain or snow from the weather app, the city along the bottom. */
@Composable
fun Sky(modifier: Modifier = Modifier) {
    // A theme with the sky off (sky: false): its plain sky_off ground, under the same light text.
    if (Themes.look.sky) LiveSky(modifier) else { val ground = Tone.skyOff; Canvas(modifier) { drawRect(ground) } }
}

@Composable
private fun LiveSky(modifier: Modifier) {
    val c = LocalContext.current
    val moving = remember { motionOn(c) }
    val weather by produceState(Weather()) { while (true) { value = withContext(Dispatchers.IO) { Weather.read(c) }; delay(60_000) } }
    var now by remember { mutableStateOf(ZonedDateTime.now(Sun.ZONE)) }
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        val start = withFrameMillis { it }
        var last = 0L
        while (true) withFrameMillis { ms -> t = (ms - start) / 1000f; if (ms - last > 30_000) { now = ZonedDateTime.now(Sun.ZONE); last = ms } }
    }
    val colors = remember(now, weather.kind) { Sun.palette(now, weather.kind) }
    val night = remember(now) { Sun.night(now) }
    val stars = remember { List(36) { Triple(rnd(it, 1), rnd(it, 2) * .5f, rnd(it, 3)) } }
    val drops = remember { List(110) { Triple(rnd(it, 4), rnd(it, 5), .6f + rnd(it, 6) * .8f) } }
    val clouds = remember { List(5) { Triple(rnd(it, 7), .08f + it * .07f, .5f + rnd(it, 8) * .7f) } }
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawRect(Brush.verticalGradient(colors))
        if (night) {
            if (weather.kind == Weather.Kind.CLEAR) for ((x, y, p) in stars) drawCircle(Color.White.copy(alpha = .35f + .3f * sin(t * .6f + p * 6f)), 1.3f * density, Offset(x * w, y * h))
            drawCircle(Color(0xFFF4F1E8).copy(alpha = if (weather.kind == Weather.Kind.CLEAR) .95f else .35f), 18 * density, Offset(w * .78f, h * .13f))
        } else if (weather.kind == Weather.Kind.CLEAR) {
            drawCircle(Brush.radialGradient(listOf(Color(0x99FFF8DC), Color(0x40FFF4D2), Color(0x00FFF4D2)), Offset(w * .8f, h * .1f), w * .4f), w * .4f, Offset(w * .8f, h * .1f))
        }
        val cloudAlpha = when (weather.kind) { Weather.Kind.CLEAR -> if (night) .08f else .3f; else -> if (night) .14f else .26f }
        for ((x0, y, s) in clouds) {
            val x = ((x0 * w + t * 7f * s * density) % (w * 1.4f)) - w * .2f
            for (k in 0 until 4) drawOval(Color.White.copy(alpha = cloudAlpha), Offset(x + k * 34 * density - 46 * s * density, y * h + sin(k.toFloat()) * 8 * density - 18 * s * density), Size(92 * s * density, 36 * s * density))
        }
        when (weather.kind) {
            Weather.Kind.RAIN -> for ((x, y0, s) in drops) {
                val y = ((y0 * h + t * 180f * s * density) % (h + 40 * density)) - 20 * density
                drawLine(Color(0x59DCE6F5), Offset(x * w, y), Offset(x * w - 3 * density, y + 14 * s * density), density, StrokeCap.Round)
            }
            Weather.Kind.SNOW -> for ((x0, y0, s) in drops) {
                val y = ((y0 * h + t * 26f * s * density) % (h + 20 * density)) - 10 * density
                drawCircle(Color.White.copy(alpha = .8f), 1.8f * s * density, Offset(x0 * w + sin(t * .8f + x0 * 9) * 6 * density, y))
            }
            else -> Unit
        }
        val city = Path().apply {
            moveTo(0f, h)
            SKYLINE.forEachIndexed { i, v -> val x0 = i / SKYLINE.size.toFloat() * w; val x1 = (i + 1) / SKYLINE.size.toFloat() * w; lineTo(x0, h - v * h * .36f); lineTo(x1, h - v * h * .36f) }
            lineTo(w, h); close()
        }
        drawPath(city, Color(0xD90E1420))
    }
}

/** Boston's skyline, left to right, as heights (City Hall's the tall one). */
private val SKYLINE = floatArrayOf(.06f, .14f, .1f, .2f, .12f, .26f, .16f, .34f, .14f, .22f, .1f, .18f, .24f, .12f, .16f, .08f)

/** A stable pseudo-random number in 0..1 for an index and a salt, so the sky looks the same each time. */
private fun rnd(i: Int, salt: Int): Float = (((i * 73856093) xor (salt * 19349663)).toLong() and 0xFFFF).toFloat() / 0xFFFF
