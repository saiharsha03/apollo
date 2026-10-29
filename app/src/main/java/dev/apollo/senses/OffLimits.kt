package dev.apollo.senses

import org.json.JSONArray
import org.json.JSONObject

/**
 * Iris's off-limits topics, carried over from Zeus (brain/Senses.kt Topics): legal and social. Their words never
 * leave the phone. A sensed fact that mentions one keeps its shape (kind, time, whether a person wrote) but every text
 * field holding the words is replaced by a marker; a notification from a social app, or one naming a topic, loses its
 * title, text and buttons whole, and the app is withheld too. His own words to Athena (Calliope, page taps) are his
 * choice and pass as they are. Pure ([topics], [withheld]) apart from the JSON walk.
 */
object OffLimits {
    private val LEGAL = Regex(
        "(?i)\\b(lawsuit|subpoena|court date|attorney|lawyer|legal notice|hearing)\\b",
    )
    private val SOCIAL = Regex("(?i)\\b(group invite|party invite)\\b")
    val SOCIAL_APPS = setOf("com.example.private.chat", "com.example.private.club")

    /** What he types to Athena himself, or what Apollo says about its own work: never filtered. */
    private val OWN = setOf("page_write", "page_tap", "page_tell", "action_result", "card_feedback", "undo", "setup")
    private val NOTICE_TEXT = listOf("title", "text", "sub", "conversation")

    fun topics(text: String): Set<String> = buildSet {
        if (LEGAL.containsMatchIn(text)) add("legal")
        if (SOCIAL.containsMatchIn(text)) add("social")
    }

    fun withheld(topics: Set<String>) = "(words withheld: ${topics.sorted().joinToString(", ")})"

    /** The fact as it may leave the phone: the same object when nothing in it is off limits. */
    fun scrub(kind: String, data: JSONObject): JSONObject {
        if (kind in OWN) return data
        if (kind == "notification") {
            val found = topics(NOTICE_TEXT.joinToString("\n") { data.optString(it) }) +
                if (data.optString("pkg") in SOCIAL_APPS) setOf("social") else emptySet()
            if (found.isEmpty()) return data
            val out = JSONObject(data.toString())
            NOTICE_TEXT.forEach { if (out.has(it)) out.put(it, "") }
            out.put("text", withheld(found)).put("actions", JSONArray())
            if ("social" in found) out.put("app", "withheld").put("pkg", "withheld")
            return out.put("off_limits", JSONArray(found.sorted()))
        }
        var hit = false
        fun walk(v: Any?): Any? = when (v) {
            is String -> topics(v).takeIf { it.isNotEmpty() }?.let { hit = true; withheld(it) } ?: v
            is JSONObject -> JSONObject().also { o -> v.keys().forEach { k -> o.put(k, walk(v.opt(k))) } }
            is JSONArray -> JSONArray().also { a -> for (i in 0 until v.length()) a.put(walk(v.opt(i))) }
            else -> v
        }
        val out = walk(data) as JSONObject
        return if (hit) out else data
    }
}
