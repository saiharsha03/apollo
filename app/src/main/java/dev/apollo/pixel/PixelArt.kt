package dev.apollo.pixel

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import dev.apollo.ui.Tone
import java.util.concurrent.ConcurrentHashMap

/** One character file (docs/PIXEL.md): scenes of frames, each frame [w] x [h] ARGB dots (0 = empty). */
class PixelChar(val name: String, val w: Int, val h: Int, val scenes: Map<String, PixelScene>)
class PixelScene(val ms: Long, val frames: List<IntArray>)

/**
 * The pixel art is data: assets/pixel/<name>.json (owl, cloud, titan built in), the same files the mini-app pages'
 * apollo.pixel draws. A bad file or scene fails soft: the scene is dropped, and a file with no good scene is null.
 */
object PixelArt {
    const val MAX_BYTES = 262_144
    const val MAX_SIDE = 64
    const val MAX_SCENES = 40
    const val MAX_FRAMES = 32
    const val MAX_PALETTE = 16

    /** Each agent's built-in character. */
    val BUILTIN = mapOf("athena" to "owl", "iris" to "cloud", "atlas" to "titan")

    private val cache = ConcurrentHashMap<String, PixelChar>()

    /** The agent's built-in character, from the app's assets (an unknown agent gets the owl). */
    fun builtin(c: Context, agent: String): PixelChar? {
        val name = BUILTIN[agent] ?: "owl"
        cache[name]?.let { return it }
        val ch = runCatching { c.assets.open("pixel/$name.json").use { parse(it.readBytes().decodeToString(), Tone.alarm.toArgb()) } }.getOrNull() ?: return null
        cache[name] = ch
        return ch
    }

    /** The palette's "red": Apollo's one red. The phone passes the Theme's (Tone.alarm); this is its value for tests. */
    const val RED = 0xFFD71921.toInt()

    /** Validates and builds a character file. Pure (the JVM tests use it). A palette value may be "red". */
    fun parse(text: String, red: Int = RED): PixelChar? {
        if (text.length > MAX_BYTES) return null
        val j = runCatching { Json(text).parse() }.getOrNull() as? Map<*, *> ?: return null
        val w = (j["w"] as? Number)?.toInt() ?: return null
        val h = (j["h"] as? Number)?.toInt() ?: return null
        if (w !in 1..MAX_SIDE || h !in 1..MAX_SIDE) return null
        val palette = HashMap<Char, Int>()
        (j["palette"] as? Map<*, *>)?.forEach { (k, v) ->
            val key = (k as? String)?.singleOrNull()
            val col = if (v == "red") red else color(v as? String)
            if (key != null && key != '.' && col != null && palette.size < MAX_PALETTE) palette[key] = col
        }
        val scenes = LinkedHashMap<String, PixelScene>()
        (j["scenes"] as? Map<*, *>)?.forEach { (k, v) ->
            val name = k as? String ?: return@forEach
            if (scenes.size >= MAX_SCENES || !Regex("^[a-z0-9_-]{1,40}$").matches(name)) return@forEach
            val s = v as? Map<*, *> ?: return@forEach
            val frames = (s["frames"] as? List<*>)?.takeIf { it.size in 1..MAX_FRAMES } ?: return@forEach
            val built = frames.map { f -> frame(f, w, h, palette) ?: return@forEach }
            val ms = ((s["ms"] as? Number)?.toLong() ?: 200L).coerceIn(40L, 10_000L)
            scenes[name] = PixelScene(ms, built)
        }
        if (scenes.isEmpty()) return null
        return PixelChar((j["name"] as? String).orEmpty().take(40), w, h, scenes)
    }

    private fun frame(f: Any?, w: Int, h: Int, palette: Map<Char, Int>): IntArray? {
        val rows = f as? List<*> ?: return null
        if (rows.size != h) return null
        val out = IntArray(w * h)
        rows.forEachIndexed { y, r ->
            val row = r as? String ?: return null
            if (row.length != w) return null
            row.forEachIndexed { x, ch -> out[y * w + x] = if (ch == '.') 0 else palette[ch] ?: return null }
        }
        return out
    }

    /** "#RGB" or "#RRGGBB" to opaque ARGB. */
    fun color(s: String?): Int? {
        if (s == null || !Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$").matches(s)) return null
        val hex = if (s.length == 4) s.drop(1).map { "$it$it" }.joinToString("") else s.drop(1)
        return (0xFF000000 or hex.toLong(16)).toInt()
    }
}

/** Just enough JSON for character files (objects, arrays, strings, numbers, true/false/null); org.json isn't on the JVM test path. */
internal class Json(private val s: String) {
    private var i = 0
    private var depth = 0

    fun parse(): Any? = value().also { ws(); require(i == s.length) }

    private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
    private fun eat(c: Char) { ws(); require(i < s.length && s[i] == c); i++ }
    private fun peek(): Char { ws(); require(i < s.length); return s[i] }

    private fun value(): Any? = when (peek()) {
        '{' -> nested {
            eat('{'); val m = LinkedHashMap<String, Any?>()
            if (peek() == '}') i++ else do { val k = string(); eat(':'); m[k] = value() } while (comma('}'))
            m
        }
        '[' -> nested {
            eat('['); val l = ArrayList<Any?>()
            if (peek() == ']') i++ else do { l += value() } while (comma(']'))
            l
        }
        '"' -> string()
        't' -> word("true", true)
        'f' -> word("false", false)
        'n' -> word("null", null)
        else -> number()
    }

    private fun <T> nested(f: () -> T): T { require(++depth <= 16); return f().also { depth-- } }

    /** After an item: true on a comma (another follows), false on the closing [end]. */
    private fun comma(end: Char): Boolean { val c = peek(); i++; require(c == ',' || c == end); return c == ',' }

    private fun word(w: String, v: Any?): Any? { require(s.startsWith(w, i)); i += w.length; return v }

    private fun number(): Double {
        val start = i
        while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
        return s.substring(start, i).toDouble()
    }

    private fun string(): String {
        eat('"')
        val b = StringBuilder()
        while (true) {
            require(i < s.length)
            when (val c = s[i++]) {
                '"' -> return b.toString()
                '\\' -> when (val e = s[i++]) {
                    'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                    'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> b.append(e)
                }
                else -> b.append(c)
            }
        }
    }
}
