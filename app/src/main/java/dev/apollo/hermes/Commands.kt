package dev.apollo.hermes

/*
 * Hermes: Owner's own instant commands ("torch on", "open Spotify", "wake me at 6", "timer 10 min", "volume 30",
 * "call mom", "text mom running late", "remind me at 6 to...", "play ...", "what's on tomorrow", "dnd on",
 * "no, I meant ..."), read by a fixed parser on the phone: no model, offline, instant. Anything that isn't one goes to Athena.
 * Pure, so the parser is tested exactly as it runs.
 */

/** Something he asked the phone to do. [kind] is what Clio logs: never his words. */
sealed interface Cmd { val kind: String get() = javaClass.simpleName.lowercase() }
data class Open(val target: String) : Cmd
data class Alarm(val hour: Int, val minute: Int) : Cmd
data class Timer(val seconds: Int) : Cmd
data class Torch(val on: Boolean) : Cmd
/** [action]: set (with [percent]), up, down, mute, unmute. */
data class Volume(val action: String, val percent: Int? = null) : Cmd
/** pause, resume, next, previous. */
data class Media(val action: String) : Cmd
/** [via]: whatsapp_video, whatsapp_voice or phone when he said; null lets the number's country pick. */
data class Call(val who: String, val via: String? = null) : Cmd
data class Navigate(val place: String) : Cmd
/** In [seconds] from now, or at [hour]:[minute] (the next one, or [tomorrow]). */
data class Remind(val what: String, val seconds: Int? = null, val hour: Int? = null, val minute: Int? = null, val tomorrow: Boolean = false) : Cmd
data class Play(val query: String) : Cmd
/** today, tomorrow or a weekday name. */
data class Agenda(val day: String) : Cmd
data class Dnd(val on: Boolean) : Cmd
/** [via]: whatsapp or sms when he said; null lets the number's country pick. [body] null: he didn't say what. */
data class Message(val who: String, val body: String?, val via: String? = null) : Cmd
/** "no, I meant Spotify Lite": the last command again with this. */
data class Correction(val target: String) : Cmd
/** "turn on wifi": what Android lets an app do about it (most switches only open their panel). */
data class Toggle(val target: String, val on: Boolean) : Cmd

object Commands {
    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    /**
     * Typed in chat, only a short one-line message is tried as a command ("timer 10", "call Amma"): a longer one is
     * talk for Athena even when it starts like a command ("take me through the plan").
     */
    fun chatSized(text: String): Boolean = '\n' !in text.trim() && text.trim().split(Regex("\\s+")).size in 1..8

    /** Null: not a command, it goes to Athena. */
    fun parse(raw: String): Cmd? {
        val t = raw.trim().trimEnd('.', '!', '?')
            .replace(rx("^(?:hey |ok |okay )?(?:apollo|athena)[,!.]?\\s+"), "")
            .replace(rx("^(?:(?:please|can you|could you)[ ,]+)+"), "").trim()
        val l = t.lowercase()
        if (l.isEmpty()) return null

        when {
            Regex("^(?:pause|stop)(?: the)?(?: music| song| spotify)?$").matches(l) -> return Media("pause")
            Regex("^(?:resume|continue|unpause)(?: the)?(?: music| song| spotify)?$|^play(?: the)? music$").matches(l) -> return Media("resume")
            Regex("^(?:next|skip)(?: song| track)?$").matches(l) -> return Media("next")
            Regex("^(?:previous|last song|previous song|previous track)$").matches(l) -> return Media("previous")
        }

        rx("^(?:no[,.]?\\s+)?i meant\\s+(.+)$").find(t)?.let { return Correction(it.groupValues[1].trim()) }
        // "what's on tomorrow" is his calendar; "what's on Netflix" is Athena's.
        if (rx("^(?:what'?s|what is) on(?: for)?(?: (?:today|tonight|tomorrow|monday|tuesday|wednesday|thursday|friday|saturday|sunday))?$|^what do i have\\b|^do i have anything\\b|^am i free\\b|^(?:show|read|check) (?:me )?my (?:calendar|agenda|schedule)\\b|^what'?s (?:my|in my) (?:calendar|agenda|schedule)\\b").containsMatchIn(l))
            return Agenda(rx("\\b(today|tonight|tomorrow|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b").find(l)?.value?.replace("tonight", "today") ?: "today")
        remind(t)?.let { return it }

        if (rx("\\btimer\\b").containsMatchIn(l)) duration(l)?.let { return Timer(it) }
        if (rx("^(?:set (?:an |the |my )?alarm|wake me(?: up)?|alarm)\\b").containsMatchIn(l)) clock(l)?.let { return Alarm(it.first, it.second) }

        rx("^(?:make a |start a )?(?:whats ?app |wa )?(video )?(call|ring|dial|phone)\\s+(.+?)(?:\\s+(?:on|via|using|over)\\s+whats ?app)?$").find(t)?.let { m ->
            val who = m.groupValues[3].trim()
            // "call me at 6" is Athena's (call_owner), never a contact named "me".
            if (rx("^(?:me|us)\\b").containsMatchIn(who)) return null
            val wa = rx("\\bwhats ?app\\b|^wa ").containsMatchIn(l)
            return Call(who, when { m.groupValues[1].isNotEmpty() -> "whatsapp_video"; wa -> "whatsapp_voice"; m.groupValues[2].lowercase() == "dial" -> "phone"; else -> null })
        }
        rx("^(?:send (?:a )?)?(?:message|msg|text|sms|whats ?app)\\s+(?:to\\s+)?(.+?)(?:\\s+(?:on|via|using|in)\\s+whats ?app)?(?:\\s*[,:]?\\s+(?:saying|that says|that|to say)\\s+(.+))?$").find(t)?.let { m ->
            var who = m.groupValues[1].trim()
            var body = m.groupValues[2].trim().ifEmpty { null }
            // "text mom running late": the first word is who, the rest is the message.
            if (body == null && ' ' in who) {
                val w = who.split(Regex("\\s+"), limit = 2)
                if (w[0].length >= 2 && w[0].lowercase() !in setOf("my", "the")) { who = w[0]; body = w[1] }
            }
            val via = when { rx("\\bwhats ?app\\b").containsMatchIn(l) -> "whatsapp"; l.startsWith("sms") -> "sms"; else -> null }
            return Message(who, body, via)
        }
        rx("^(?:navigate|directions|take me|drive)\\s+(?:me\\s+)?(?:to\\s+)?(.+)$").find(t)?.let { return Navigate(it.groupValues[1].trim()) }

        rx("^play\\s+(.+?)(?:\\s+on\\s+spotify)?$").find(t)?.let { return Play(it.groupValues[1].trim()) }

        val off = rx("\\boff\\b|\\bdisable\\b").containsMatchIn(l)
        if (rx("\\b(?:flash ?light|torch)\\b").containsMatchIn(l)) return Torch(!off)
        if (rx("\\b(?:do not disturb|don'?t disturb|dnd)\\b").containsMatchIn(l) && !rx("^(?:open|show)\\b|settings?$").containsMatchIn(l)) return Dnd(!off)
        if (rx("\\b(?:volume|louder|quieter|unmute|mute)\\b").containsMatchIn(l) && !rx("^(?:open|show)\\b|settings?$").containsMatchIn(l)) {
            rx("(\\d{1,3})\\s*(?:%|percent)?$").find(l)?.let { return Volume("set", it.groupValues[1].toInt().coerceIn(0, 100)) }
            return Volume(when {
                "unmute" in l -> "unmute"
                "mute" in l -> "mute"
                rx("\\b(?:down|lower|quieter)\\b").containsMatchIn(l) -> "down"
                else -> "up"
            })
        }
        rx("^(?:turn|switch|toggle)\\s+(on|off)\\s+(?:the\\s+)?(.+)$|^(?:turn|switch|toggle)\\s+(?:the\\s+)?(.+?)\\s+(on|off)$").find(l)?.let { m ->
            return Toggle(m.groupValues[2].ifEmpty { m.groupValues[3] }.trim(), (m.groupValues[1].ifEmpty { m.groupValues[4] }) == "on")
        }
        rx("^(?:open|launch|start|go to|show)\\s+(?:the |my )?(.+?)(?:\\s+app)?$").find(t)?.let { return Open(it.groupValues[1].trim()) }
        rx("^(.+?)\\s+settings?$").find(l)?.let { return Open(it.groupValues[1] + " settings") }
        return null
    }

    /** "remind me to call mom in 20 min", "remind me at 6 pm to take the bins out", "remind me tomorrow at 9 to pay rent". Null without a time. */
    fun remind(t: String): Remind? {
        val m = rx("^remind me\\s+(.+)$").find(t) ?: return null
        var rest = m.groupValues[1]
        val tomorrow = rx("\\btomorrow\\b").containsMatchIn(rest)
        rest = rest.replace(rx("\\s*\\btomorrow\\b"), "")
        var seconds: Int? = null
        var at: Pair<Int, Int>? = null
        val inM = rx("\\bin\\s+(?:half an hour|(?:\\d+|an?|one|two|three|five|ten|fifteen|twenty|thirty|forty five)\\s*(?:hours?|hrs?|minutes?|mins?|seconds?|secs?)(?:\\s+(?:and\\s+)?\\d+\\s*(?:minutes?|mins?))?)\\b").find(rest)
        if (inM != null) {
            seconds = duration(inM.value); rest = rest.removeRange(inM.range)
        } else rx("\\b(?:at|by)\\s+(noon|midnight|\\d{1,2}(?:[:.]\\d{2})?\\s*(?:a\\.?m\\.?|p\\.?m\\.?)?)(?=\\W|$)").find(rest)?.let { a ->
            at = clock(a.groupValues[1])
            if (at != null) rest = rest.removeRange(a.range)
        }
        if (seconds == null && at == null) return null
        val what = rest.trim().trimEnd(',', '.').replace(rx("^(?:to|that|about)\\s+"), "").replace(Regex("\\s+"), " ").trim()
        if (what.isEmpty()) return null
        return Remind(what, seconds, at?.first, at?.second, tomorrow)
    }

    private val WORDS = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "five" to 5, "ten" to 10, "fifteen" to 15,
        "twenty" to 20, "thirty" to 30, "forty five" to 45)

    /** "10 min", "1 hour 30 minutes", "90 seconds", "half an hour". Seconds, 1 s to a day; null without a duration. */
    fun duration(text: String): Int? {
        val l = text.lowercase()
        if (rx("\\bhalf an? hour\\b").containsMatchIn(l)) return 1800
        var total = 0
        rx("(\\d+|an?|one|two|three|five|ten|fifteen|twenty|thirty|forty five)\\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\\b").findAll(l).forEach { m ->
            val n = m.groupValues[1].toIntOrNull() ?: WORDS[m.groupValues[1]] ?: return@forEach
            total += n * when (m.groupValues[2].first()) { 'h' -> 3600; 'm' -> 60; else -> 1 }
        }
        return total.takeIf { it in 1..86_400 }
    }

    /** "6", "6:30", "7.15 am", "6 pm", "18:45", "noon", "midnight" as (hour, minute). A bare hour reads on a 24-hour clock. */
    fun clock(text: String): Pair<Int, Int>? {
        val l = text.lowercase()
        if (rx("\\bnoon\\b").containsMatchIn(l)) return 12 to 0
        if (rx("\\bmidnight\\b").containsMatchIn(l)) return 0 to 0
        val m = rx("\\b(\\d{1,2})(?:[:.](\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?(?=\\W|$)").findAll(l).firstOrNull { it.groupValues[1].toInt() <= 23 } ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toIntOrNull() ?: 0
        val ampm = m.groupValues[3].replace(".", "")
        if (min > 59 || (ampm.isNotEmpty() && h !in 1..12)) return null
        if (ampm == "pm" && h < 12) h += 12
        if (ampm == "am" && h == 12) h = 0
        return h to min
    }

    /** Lowercase, punctuation to spaces. */
    fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** How well a name he said matches an app's label, 0..1 ("spotfy" to "Spotify", "teams" to "Microsoft Teams"). */
    fun score(query: String, label: String): Float {
        val a = norm(query); val b = norm(label)
        if (a.isEmpty() || b.isEmpty()) return 0f
        if (a == b || a.replace(" ", "") == b.replace(" ", "")) return 1f
        val words = b.split(' ')
        if (words.first() == a) return 0.93f
        if (a.length >= 3 && b.replace(" ", "").startsWith(a.replace(" ", ""))) return 0.9f
        if (a in words) return 0.86f
        val ca = a.replace(" ", ""); val cb = b.replace(" ", "")
        return (1f - edits(ca, cb).toFloat() / maxOf(ca.length, cb.length)) * 0.9f
    }

    /** The best label at 0.7 or better, when no other is within 0.05 of it (then it is not clear which he meant). */
    fun pick(query: String, labels: List<String>): String? {
        val ranked = labels.distinct().map { it to score(query, it) }.sortedByDescending { it.second }
        val best = ranked.firstOrNull()?.takeIf { it.second >= 0.7f } ?: return null
        val next = ranked.getOrNull(1)
        // "WhatsApp" vs "WhatsApp Business": the shorter one is the name he said.
        if (next != null && next.second >= best.second - 0.05f && !norm(next.first).startsWith(norm(best.first) + " ")) return null
        return best.first
    }

    private fun edits(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var diag = prev[0]; prev[0] = i
            for (j in 1..b.length) {
                val tmp = prev[j]
                prev[j] = minOf(prev[j] + 1, prev[j - 1] + 1, diag + if (a[i - 1] == b[j - 1]) 0 else 1)
                diag = tmp
            }
        }
        return prev[b.length]
    }
}
