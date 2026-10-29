package dev.apollo.clio

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One log line: when, which part of Apollo, how bad (info, warn, error), what happened, a short detail. */
data class ClioLine(val at: Long, val area: String, val level: String, val event: String, val detail: String)

/**
 * Clio: Apollo's own log on the phone, one JSON line per event in filesDir/clio, a file per day (clio-2026-10-26.jsonl),
 * kept 14 days. Features log kinds and outcomes, never his words, message text or keys. Read-only on screen.
 */
class ClioLog(val dir: File, private val clock: () -> Long = System::currentTimeMillis, private val zone: ZoneId = ZoneId.systemDefault()) {
    private fun day(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    /** Apollo's day files and Zeus's (clio-2026-10-20-00.jsonl, same folder: the app id is the same), oldest first. */
    fun files(): List<File> = dir.listFiles { f -> NAME.matches(f.name) || ZEUS.matches(f.name) }.orEmpty().sortedBy { it.name }

    private fun own(): List<File> = dir.listFiles { f -> NAME.matches(f.name) }.orEmpty().sortedBy { it.name }

    @Synchronized fun append(area: String, level: String, event: String, detail: String = "") {
        val now = clock()
        dir.mkdirs()
        val line = JSONObject().put("at", now).put("area", area.take(24)).put("level", level).put("event", event.take(80)).put("detail", detail.take(300))
        File(dir, "clio-${day(now)}.jsonl").appendText(line.toString() + "\n")
        val oldest = "clio-${day(now).minusDays(KEEP_DAYS - 1L)}.jsonl"
        own().filter { it.name < oldest }.forEach { it.delete() }   // Zeus's files are his old logs: only read, never pruned here
    }

    /** Newest first, at most [max]. */
    fun lines(max: Int = 2000): List<ClioLine> {
        val out = ArrayList<ClioLine>()
        for (f in files().asReversed()) {
            for (raw in f.readLines().asReversed()) {
                runCatching { JSONObject(raw) }.getOrNull()?.let { out += line(it) }
                if (out.size >= max) return out
            }
        }
        return out
    }

    companion object {
        const val KEEP_DAYS = 14
        private val NAME = Regex("^clio-\\d{4}-\\d{2}-\\d{2}\\.jsonl$")
        private val ZEUS = Regex("^clio-\\d{4}-\\d{2}-\\d{2}-\\d{2,}\\.jsonl$")

        /** One line, Apollo's shape or Zeus's ({ts, level INFO|WARN|ERROR}). */
        fun line(j: JSONObject) = ClioLine(if (j.has("at")) j.optLong("at") else j.optLong("ts"), j.optString("area"), j.optString("level").lowercase(),
            j.optString("event"), j.optString("detail"))
    }
}

object Clio {
    @Volatile private var log: ClioLog? = null

    /** At app start: the log, and crashes written to it before the process goes. */
    fun init(c: Context) {
        val l = ClioLog(File(c.filesDir, "clio")).also { log = it }
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { l.append("app", "error", "crash", "${e.javaClass.name} on ${t.name}: " + e.stackTrace.take(6).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }) }
            before?.uncaughtException(t, e)
        }
    }

    fun info(area: String, event: String, detail: String = "") = write(area, "info", event, detail)
    fun warn(area: String, event: String, detail: String = "") = write(area, "warn", event, detail)
    fun error(area: String, event: String, detail: String = "") = write(area, "error", event, detail)

    /** The senses' old call (their own log file, which nothing read, is gone): a warn line with the data as its detail. */
    @Suppress("UNUSED_PARAMETER")
    fun log(c: Context, area: String, what: String, data: JSONObject = JSONObject()) = warn(area, what, if (data.length() == 0) "" else data.toString())

    private fun write(area: String, level: String, event: String, detail: String) { runCatching { log?.append(area, level, event, detail) } }

    fun lines(c: Context): List<ClioLine> = (log ?: ClioLog(File(c.filesDir, "clio"))).lines()
}
