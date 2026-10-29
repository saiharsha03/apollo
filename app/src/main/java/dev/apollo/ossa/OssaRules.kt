package dev.apollo.ossa

import org.json.JSONObject

/**
 * One call Athena asked for (outbox kind `call`: reason, text, from; optional critical, after_minutes, unless_seen).
 * [askedAt] is when it reached the phone, for unless_seen.
 */
data class CallAsk(val id: String, val reason: String, val text: String, val from: String, val critical: Boolean = false,
                   val afterMinutes: Int = 0, val unlessSeen: Boolean = false, val askedAt: Long = 0L) {
    fun json(): JSONObject = JSONObject().put("id", id).put("reason", reason).put("text", text).put("from", from).put("critical", critical)
        .put("unless_seen", unlessSeen).put("asked_at", askedAt)

    companion object {
        fun of(id: String, d: JSONObject, now: Long = System.currentTimeMillis()): CallAsk? {
            val text = d.optString("text").trim()
            if (text.isEmpty()) return null
            return CallAsk(id, d.optString("reason").trim().ifEmpty { "Athena" }.take(120), text, d.optString("from").ifEmpty { "athena" },
                d.optBoolean("critical"), d.optInt("after_minutes").coerceIn(0, 24 * 60), d.optBoolean("unless_seen"), d.optLong("asked_at").takeIf { it > 0 } ?: now)
        }
    }
}

enum class CallGate { RING, MESSAGE, REFUSE }

/** How a call ended, and so what he gets afterwards. */
enum class Ending(val word: String, val asMessage: Boolean) {
    TALKED("answered", false),
    DECLINED("declined", true),
    MISSED("missed", true),
    AS_MESSAGE("sent as a message", true),
    /** Call me back in 5, 15 or 30: it rings again then, so no message now. */
    LATER("put off", false),
}

/** Audio routes as Telecom names them (CallAudioState / CallEndpoint), reduced to what the route choice needs. */
enum class Route { EARPIECE, SPEAKER, WIRED, BLUETOOTH, OTHER }

/**
 * The pure rules of an Athena call. The 10-26 bug: the morning call rang before the car's Bluetooth was up and stayed
 * on the phone when the car connected 45 s later. So whenever a Bluetooth route (car or buds) becomes available
 * during a call and the call isn't on it, the call moves to it.
 */
object OssaRules {
    /** A carrier rings about this long before voicemail; after it, the call counts as missed and becomes a message. */
    const val RING_MS = 45_000L

    /** His approval row for her calls. */
    const val KEY = "call_owner"

    /** What her call_owner does under his call_owner row (scan F7): rings when free, a message when it needs him, nothing on never. */
    fun gate(level: dev.apollo.approvals.Level): CallGate = when (level) {
        dev.apollo.approvals.Level.FREE -> CallGate.RING
        dev.apollo.approvals.Level.NEVER -> CallGate.REFUSE
        else -> CallGate.MESSAGE
    }

    /** A failure code for Clio: letters, digits and _ only (an exception's class name, Android's error numbers), 60 at most. */
    fun code(raw: String): String = raw.replace(Regex("[^A-Za-z0-9_]"), "_").take(60)

    /** The route to switch to now, or null to stay. */
    fun moveTo(current: Route, available: Set<Route>): Route? =
        if (Route.BLUETOOTH in available && current != Route.BLUETOOTH) Route.BLUETOOTH else null

    /** What the chat line says after a call that didn't happen as a talk. */
    fun messageLine(ask: CallAsk, how: Ending): String = "Athena called (${ask.reason}) and it was ${how.word}. She said: ${ask.text}"

    /** The Call me back choices, in minutes. */
    val LATER_MIN = listOf(5, 15, 30)

    /** A put-off call with unless_seen rings only if he hasn't opened Apollo since she asked. */
    fun ringLater(ask: CallAsk, seenAt: Long): Boolean = !ask.unlessSeen || seenAt < ask.askedAt
}

/**
 * A call as a talk, turn by turn (Zeus's voice loop, simply): her words are spoken, he answers, the phone waits for her
 * reply, it is spoken, and so on until either hangs up. She ends it with [hang up] at the end of a reply; two silences
 * in a row, or no answer from her in time, end it with a line from the phone. Pure.
 */
class CallTalk(private val maxQuiet: Int = 2) {
    enum class Phase { SPEAKING, LISTENING, WAITING, ENDED }

    var phase = Phase.SPEAKING
        private set
    private var quiet = 0
    private var last = false

    /** Her words (or the phone's) finished playing: his turn, or the end after the last words. */
    fun spoke(): Phase { phase = if (last) Phase.ENDED else Phase.LISTENING; return phase }

    /** What he said; blank is silence. Returns the phone's goodbye when he has been quiet too long. */
    fun heard(text: String): String? {
        if (text.isNotBlank()) { quiet = 0; phase = Phase.WAITING; return null }
        if (++quiet >= maxQuiet) { last = true; phase = Phase.SPEAKING; return QUIET_BYE }
        phase = Phase.LISTENING
        return null
    }

    /** Her reply (null: none in time). Returns what to say now. */
    fun answered(reply: String?): String {
        phase = Phase.SPEAKING
        if (reply.isNullOrBlank()) { last = true; return NO_ANSWER }
        val (say, hang) = split(reply)
        last = hang
        return say.ifBlank { "Bye." }
    }

    fun hungUp() { phase = Phase.ENDED }

    /** The phone can't listen at all (no recognition service): the goodbye, instead of waiting forever. */
    fun cantHear(): String { last = true; phase = Phase.SPEAKING; return CANT_HEAR }

    companion object {
        const val QUIET_BYE = "I didn't hear anything, so I'll hang up. It's all in the chat."
        const val NO_ANSWER = "Athena is still on it. Her answer will be in the chat."
        const val CANT_HEAR = "This phone can't listen right now, so I'll hang up. Reply in the chat."
        /** How long the phone waits for her reply before saying so and hanging up. */
        const val ANSWER_MS = 90_000L
        private val HANG = Regex("(?i)\\[\\s*hang ?up\\s*]")

        /** Her reply without the [hang up] marker, and whether it was there. */
        fun split(reply: String): Pair<String, Boolean> = HANG.replace(reply, "").trim() to HANG.containsMatchIn(reply)

        /** His words into the call's chat: the first carries why she called and what she opened with. */
        fun line(ask: CallAsk, words: String, first: Boolean): String =
            if (first) "[on the call: you called him (${ask.reason}) and opened with: ${ask.text}] ${words.trim()}" else "[on the call] ${words.trim()}"
    }
}
