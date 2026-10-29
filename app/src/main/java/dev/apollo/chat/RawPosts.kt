package dev.apollo.chat

import dev.apollo.net.ChatMessage

/**
 * Apollo's raw posts to Athena (the phone's facts dumps, heartbeats, session handoffs) stay out of a thread's view until
 * he taps "show raw": the facts thread then reads as her own short notes. They are all still kept. Pure.
 */
internal object RawPosts {
    private val PREFIXES = listOf("FACTS FROM OWNER'S PHONE", "HEARTBEAT ", "Handoff from your previous session")

    fun raw(m: ChatMessage): Boolean = m.from == "apollo" && PREFIXES.any { m.text.startsWith(it) }

    fun shown(ms: List<ChatMessage>, all: Boolean): List<ChatMessage> = if (all) ms else ms.filterNot(::raw)
}
