package dev.apollo.apps

import java.io.File
import java.net.URI

/**
 * The pure rules of the mini-app engine, so they are tested exactly as they run: where a page's file is, what SQL a page
 * may run, and which hosts a page may reach. Mini apps live in filesDir/apollo-apps/<app>/ (a one-way mirror of the
 * server's folder) and are served on a private origin, https://app.apollo.local/<app>/<path>; nothing else is served.
 */
object AppFiles {
    const val HOST = "app.apollo.local"
    const val ORIGIN = "https://$HOST"
    private val APP = Regex("^[a-z][a-z0-9-]{1,39}$")

    /** The app and path a URL on our origin points at, or null when it isn't ours. */
    fun parse(url: String): Pair<String, String>? {
        val u = runCatching { URI(url) }.getOrNull() ?: return null
        if (u.scheme != "https" || u.host != HOST || u.port != -1 || u.rawUserInfo != null) return null
        val parts = (u.path ?: return null).trimStart('/').split('/', limit = 2)
        if (parts.size < 2 || !APP.matches(parts[0]) || parts[1].isEmpty()) return null
        return parts[0] to parts[1]
    }

    /**
     * The file inside root/app for path, or null if it would leave the app (.., absolute, symlinked out), is hidden, or
     * is the app's data.db (pages reach their own tables through apollo.sql only; every route goes through here).
     */
    fun resolve(root: File, app: String, path: String): File? {
        if (!APP.matches(app) || path.split('/').any { it.isEmpty() || it == "." || it == ".." || it.startsWith(".") }) return null
        val dir = File(root, app).canonicalFile
        val f = File(dir, path).canonicalFile
        return f.takeIf { it.path.startsWith(dir.path + File.separator) && it.isFile && !it.name.startsWith("data.db") }
    }

    /**
     * Every app shares one origin, so a page could fetch another app's /<app>/... files. A request for [target] from a
     * page of [current] is served when it is the same app or the page itself (a main-frame load); another app's files only
     * when that app isn't secure, and its phone media (recordings, photos) never.
     */
    fun crossAppOk(target: String, current: String?, mainFrame: Boolean, targetSecure: Boolean, media: Boolean): Boolean =
        mainFrame || target == current || (!targetSecure && !media)

    /** One read-only statement: SELECT or WITH ... SELECT, no second statement, no writes or pragmas anywhere in it. */
    fun readOnly(sql: String): Boolean {
        val s = sql.trim().trimEnd(';').trim()
        if (s.isEmpty() || ';' in s) return false
        val lower = s.lowercase()
        if (!(lower.startsWith("select") || lower.startsWith("with"))) return false
        return !Regex("\\b(insert|update|delete|replace|create|drop|alter|attach|detach|pragma|vacuum|reindex)\\b").containsMatchIn(lower)
    }

    /** A page may reach a host only when Owner's approvals list it (fetch_allow), over https on the default port. */
    fun allowed(url: String, hosts: Set<String>): Boolean {
        val u = runCatching { URI(url) }.getOrNull() ?: return false
        return u.scheme == "https" && u.port == -1 && u.rawUserInfo == null && u.host?.lowercase() in hosts
    }

    fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "md" -> "text/html"
        "js" -> "application/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg" -> "audio/ogg"
        "opus" -> "audio/opus"
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "ttf" -> "font/ttf"
        "woff2" -> "font/woff2"
        "txt", "csv", "yml", "yaml" -> "text/plain"
        else -> "application/octet-stream"
    }
}

/**
 * Asks a page can make on its own (a link out): one per url every [sameMs], at most [perMinute] in a minute, so a
 * redirect loop or a page calling open() in a loop can't flood his asks. Pure; [now] is passed in.
 */
class AskThrottle(private val sameMs: Long = 10_000, private val perMinute: Int = 5) {
    private val last = HashMap<String, Long>()
    private val recent = ArrayDeque<Long>()

    @Synchronized fun allow(key: String, now: Long): Boolean {
        while (recent.isNotEmpty() && now - recent.first() >= 60_000) recent.removeFirst()
        last.entries.removeAll { now - it.value >= sameMs }
        if (key in last || recent.size >= perMinute) return false
        last[key] = now
        recent.addLast(now)
        return true
    }
}

/** A small Markdown to HTML renderer for notes and .md pages: headings, lists, bold, italic, code, links. Text is escaped first. */
object Markdown {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun inline(s: String): String = esc(s)
        .replace(Regex("`([^`]+)`"), "<code>$1</code>")
        .replace(Regex("\\*\\*([^*]+)\\*\\*"), "<b>$1</b>")
        .replace(Regex("(?<![*\\w])\\*([^*]+)\\*(?![*\\w])"), "<i>$1</i>")
        .replace(Regex("\\[([^\\]]+)]\\((https://[^)\\s]+)\\)"), "<a href=\"$2\">$1</a>")

    fun html(md: String): String {
        val out = StringBuilder()
        var list = false
        var code = false
        for (raw in md.lines()) {
            if (raw.trimStart().startsWith("```")) { out.append(if (code) "</pre>" else "<pre>"); code = !code; continue }
            if (code) { out.append(esc(raw)).append('\n'); continue }
            val line = raw.trimEnd()
            val item = Regex("^\\s*[-*] (.*)").find(line)
            if (item == null && list) { out.append("</ul>"); list = false }
            when {
                item != null -> { if (!list) { out.append("<ul>"); list = true }; out.append("<li>").append(inline(item.groupValues[1])).append("</li>") }
                line.startsWith("#") -> { val n = line.takeWhile { it == '#' }.length.coerceAtMost(6); out.append("<h$n>").append(inline(line.drop(n).trim())).append("</h$n>") }
                line.isBlank() -> {}
                else -> out.append("<p>").append(inline(line)).append("</p>")
            }
        }
        if (list) out.append("</ul>")
        if (code) out.append("</pre>")
        return out.toString()
    }
}
