package dev.apollo.pixel

/**
 * What a character does for each tool: the one table (Athena may be given it later). The pose always comes from the
 * real tool, connector or event in the activity feed (server store.activity), and the label is that plain fact, never
 * a made-up word. Tool names: Managed Agents' built-ins (bash, read...), Claude Code's (Bash, Read...), Apollo's MCP
 * tools, and the events "message", "running" and "idle".
 */
object ToolScenes {
    /** Exact tool name (lower-cased) -> scene. */
    val exact: Map<String, String> = mapOf(
        // a terminal
        "bash" to "typing", "edit" to "typing", "write" to "typing", "multiedit" to "typing", "notebookedit" to "typing",
        "app_sql" to "typing", "add_rule" to "typing", "app_archive" to "typing", "phone_setting" to "typing",
        // reading
        "read" to "reading", "glob" to "reading", "grep" to "reading", "ls" to "reading", "app_read" to "reading",
        "thread_read" to "reading", "facts" to "reading", "apps_list" to "reading",
        // the web
        "web_search" to "telescope", "web_fetch" to "telescope", "websearch" to "telescope", "webfetch" to "telescope",
        // making something he will see
        "card" to "painting", "app_write" to "painting", "app_create" to "painting",
        // a letter
        "notify" to "mail", "thread_post" to "mail", "thread_new" to "mail",
        // the phone
        "call_owner" to "phone", "phone_do" to "phone",
        // a note to the other character
        "ask_iris" to "note", "ask_atlas" to "note",
        // money
        "usage" to "coins",
        // events
        "message" to "talking", "running" to "working",
    )

    /** Other tools, by the words in their name (another connector's search_threads, send_message...). First match wins. */
    val families: List<Pair<Regex, String>> = listOf(
        Regex("search|find|lookup|browse|fetch") to "telescope",
        Regex("send|mail|post|reply|forward|message") to "mail",
        Regex("call|dial|ring") to "phone",
        Regex("read|get|list|query|show|view") to "reading",
        Regex("write|create|edit|update|draft|add|set|delete|upload") to "typing",
        Regex("cost|usage|billing|price|pay") to "coins",
    )

    /** A tool nothing above knows: a generic working scene, never a guess at a word. */
    const val FALLBACK = "working"

    fun scene(tool: String, hour: Int): String {
        val t = tool.lowercase()
        if (t == "idle" || t.isEmpty()) return if (hour >= 22 || hour < 7) "sleep" else "tea"
        return exact[t] ?: families.firstOrNull { it.first.containsMatchIn(t) }?.second ?: FALLBACK
    }

    /** The plain fact: another connector's name ("Gmail"), else the tool's own name with spaces ("web search"). */
    fun label(tool: String, server: String): String {
        if (server.isNotBlank() && !server.equals("apollo", ignoreCase = true)) return server.replace('_', ' ').replace('-', ' ')
        return tool.replace('_', ' ').lowercase()
    }
}
