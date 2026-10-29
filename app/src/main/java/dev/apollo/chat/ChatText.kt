package dev.apollo.chat

import dev.apollo.net.Reaction

/** Reactions under a bubble: one per person and emoji; chips group them by emoji. Pure. */
object Reactions {
    /** Six one-tap ones in the long-press row; the rest are in [all]. */
    val quick = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

    val all = listOf(
        "👍", "👎", "❤️", "🔥", "😂", "🤣", "😊", "😍", "🥰", "😘", "😎", "🤩", "😮", "😯", "😢", "😭", "😡", "🤯", "😴", "🤔",
        "🙄", "😬", "😅", "😇", "🥲", "🤗", "🫡", "🤝", "🙏", "👏", "🙌", "💪", "👀", "✅", "❌", "⭐", "💯", "🎉", "🥳", "🎂",
        "💙", "💚", "💛", "🧡", "💜", "🖤", "🤍", "💔", "😴", "🤒", "🤧", "🥶", "🥵", "😋", "🍕", "☕", "🍺", "🚗", "✈️", "🏠",
        "⚽", "🏏", "🏎️", "🎵", "📞", "📅", "⏰", "💰", "📌", "💡", "⚠️", "❓", "❗", "👌", "✌️", "🤞", "👋", "🫶", "😤", "😏",
    ).distinct()

    @androidx.compose.runtime.Immutable
    data class Chip(val emoji: String, val count: Int, val mine: Boolean)

    fun apply(now: List<Reaction>, who: String, emoji: String, on: Boolean): List<Reaction> {
        val r = Reaction(who, emoji)
        return if (on) (if (r in now) now else now + r) else now.filter { it != r }
    }

    fun chips(list: List<Reaction>, me: String = "owner"): List<Chip> =
        list.groupBy { it.emoji }.map { (e, rs) -> Chip(e, rs.size, rs.any { it.who == me }) }
}

/** Markdown in agents' bubbles: paragraphs, headings, bullet and numbered items, code blocks; bold, italic, code, links. Pure. */
object Markdown {
    @androidx.compose.runtime.Immutable
    data class Span(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false, val url: String? = null)

    sealed interface Block
    data class Para(val spans: List<Span>) : Block
    data class Heading(val spans: List<Span>) : Block
    data class Item(val marker: String, val spans: List<Span>) : Block
    data class Code(val text: String) : Block

    private val heading = Regex("^#{1,6}\\s+(.*)$")
    private val bullet = Regex("^\\s*[-*+•]\\s+(.*)$")
    private val number = Regex("^\\s*(\\d{1,3})[.)]\\s+(.*)$")
    private val link = Regex("^\\[([^\\]\\n]+)]\\((https?://[^)\\s]+)\\)")

    fun parse(text: String): List<Block> {
        val out = mutableListOf<Block>()
        val para = mutableListOf<String>()
        fun flush() { if (para.isNotEmpty()) { out += Para(inline(para.joinToString("\n"))); para.clear() } }
        val lines = text.replace("\r\n", "\n").split("\n")
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.trimStart().startsWith("```")) {
                flush()
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) code += lines[i++]
                out += Code(code.joinToString("\n"))
                i++
                continue
            }
            val h = heading.find(line)
            val b = bullet.find(line)
            val n = number.find(line)
            when {
                line.isBlank() -> flush()
                h != null -> { flush(); out += Heading(inline(h.groupValues[1])) }
                b != null -> { flush(); out += Item("•", inline(b.groupValues[1])) }
                n != null -> { flush(); out += Item(n.groupValues[1] + ".", inline(n.groupValues[2])) }
                else -> para += line
            }
            i++
        }
        flush()
        return out
    }

    /** Inline marks inside one block; marks that never close stay as typed. */
    fun inline(s: String, bold: Boolean = false, italic: Boolean = false): List<Span> {
        val out = mutableListOf<Span>()
        val plain = StringBuilder()
        fun add(span: Span) {
            if (plain.isNotEmpty()) { out += Span(plain.toString(), bold, italic); plain.clear() }
            out += span
        }
        fun wordAt(k: Int) = k in s.indices && s[k].isLetterOrDigit()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '`') {
                val end = s.indexOf('`', i + 1)
                if (end > i + 1) { add(Span(s.substring(i + 1, end), bold, italic, code = true)); i = end + 1; continue }
            }
            if ((c == '*' || c == '_') && s.startsWith("$c$c", i)) {
                val end = s.indexOf("$c$c", i + 2)
                if (end > i + 2) { inline(s.substring(i + 2, end), true, italic).forEach { add(it) }; i = end + 2; continue }
            }
            if ((c == '*' || c == '_') && i + 1 < s.length && !s[i + 1].isWhitespace() && !(c == '_' && wordAt(i - 1))) {
                var end = s.indexOf(c, i + 1)
                while (end > 0 && c == '_' && wordAt(end + 1)) end = s.indexOf(c, end + 1)
                if (end > i + 1 && !s[end - 1].isWhitespace()) { inline(s.substring(i + 1, end), bold, true).forEach { add(it) }; i = end + 1; continue }
            }
            if (c == '[') {
                val m = link.find(s.substring(i))
                if (m != null) { add(Span(m.groupValues[1], bold, italic, url = m.groupValues[2])); i += m.value.length; continue }
            }
            if ((s.startsWith("https://", i) || s.startsWith("http://", i)) && !wordAt(i - 1)) {
                var end = i
                while (end < s.length && !s[end].isWhitespace()) end++
                while (end > i && s[end - 1] in ".,;:!?)\"'") end--
                val url = s.substring(i, end)
                add(Span(url, bold, italic, url = url)); i = end; continue
            }
            plain.append(c)
            i++
        }
        if (plain.isNotEmpty()) out += Span(plain.toString(), bold, italic)
        return out
    }
}
