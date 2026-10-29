package dev.apollo.apps

/** One thing search can find. [go]: a nav sub (app:<name>[/<page>], senses...), place:<PLACE>, or geo:<lat>,<lng>. */
data class Hit(val word: String, val detail: String?, val go: String)

/** One search over mini apps and their pages, More's rows, the nav places and his saved places. Pure. */
object Search {
    /** Every word he typed must be in the hit's name or line; names that start with the first word come first. */
    fun find(query: String, all: List<Hit>, limit: Int = 40): List<Hit> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return all.filter { h -> (h.word + " " + h.detail.orEmpty()).lowercase().let { t -> words.all { it in t } } }
            .sortedBy { if (it.word.lowercase().startsWith(words[0])) 0 else 1 }.take(limit)
    }

    /** A page's name from its path: pages/week-view.html -> "week view". */
    fun pageWord(path: String): String = path.substringAfterLast('/').substringBeforeLast('.').replace('-', ' ').replace('_', ' ')

    /** The apps and every page but the one each opens on. */
    fun apps(apps: List<MiniApp>, home: (MiniApp) -> String?): List<Hit> = apps.flatMap { a ->
        listOf(Hit(a.title, a.about.ifBlank { null }, "app:${a.name}")) +
            a.pages.filter { it != home(a) }.map { p -> Hit("${a.title}: ${pageWord(p)}", "A page of ${a.title}", "app:${a.name}/$p") }
    }
}
