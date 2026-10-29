package dev.apollo.senses

import java.security.MessageDigest

/** Where a sense sends what it saw. The app wires it to the server's `/v1/facts` (net/Facts). */
fun interface FactSink {
    fun post(kind: String, data: org.json.JSONObject, urgent: Boolean)
}

/** The sink every sense uses; until the network layer is wired, facts are dropped. */
object Senses {
    @Volatile var sink: FactSink = FactSink { _, _, _ -> }
}

/** Which notifications never become facts. Pure, so tested exactly as they run. */
object NoticeRules {
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    const val GMAIL = "com.google.android.gm"

    private val CODE_WORDS = Regex(
        "(?i)(\\botp\\b|one[- ]time (pass(word|code)?|code|pin)|verification code|verify code|security code|log ?in code|" +
            "sign[- ]?in code|pass ?code|auth(entication)? code|confirmation code|access code|\\b2fa\\b|two[- ]factor|2[- ]step|" +
            "is your( [\\w-]+){0,2} code|your code is|\\bpin is\\b|do not share (this|the) code|don't share (this|the) code)",
    )
    /** A 4 to 8 digit code, also split as 123-456 or 123 456, or Google's G-123456. */
    private val CODE = Regex("(?<![\\d])(\\d{4,8}|\\d{3}[- ]\\d{3}|[A-Z]-\\d{5,6})(?![\\d])")
    private val SIGN_IN_LINK = Regex("(?i)(reset (your )?password|password reset|magic link|sign[- ]in link|log ?in link)")

    /** One-time codes and sign-in links, in any app: they belong to the authenticator, never to Athena. */
    fun isCode(text: String): Boolean =
        CODE_WORDS.containsMatchIn(text) && CODE.containsMatchIn(text) || Regex("\\bG-\\d{6}\\b").containsMatchIn(text) ||
            Regex("(?i)\\botp\\b").containsMatchIn(text) || SIGN_IN_LINK.containsMatchIn(text)

    /** Gmail names the account in the sub text: kept only when it is one he keeps; unnamed or another account is dropped. */
    fun gmailKept(subText: String?, keep: Set<String>): Boolean =
        subText?.let { EMAIL.find(it)?.value }?.let { a -> keep.any { it.equals(a, ignoreCase = true) } } ?: false

    /** A stable key for a name or number (Zeus's People.keyOf, unchanged, so his Calypso-sealed people still match). */
    fun personKey(name: String): String {
        val digits = name.filter { it.isDigit() }
        val norm = if (digits.length >= 7 && digits.length >= name.count { it.isLetter() }) digits.takeLast(10)
        else name.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
        val d = MessageDigest.getInstance("SHA-256").digest(norm.toByteArray(Charsets.UTF_8))
        return d.take(8).joinToString("") { "%02x".format(it) }
    }

    /** Apps where a notification is a person talking to him: these go to Athena at once. */
    val PEOPLE_APPS = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "com.google.android.apps.messaging", "com.samsung.android.messaging",
        "com.facebook.orca", "com.instagram.android", "com.discord", "com.Slack", "com.microsoft.teams",
        "com.google.android.dialer", "com.samsung.android.dialer", "com.android.server.telecom", "com.snapchat.android", "org.thoughtcrime.securesms",
    )

    sealed interface Verdict {
        data class Drop(val why: String) : Verdict
        data class Keep(val urgent: Boolean) : Verdict
    }

    /** What happens to one notification. The order: his own app, skip list, Calypso apps, Gmail account, codes, Calypso people. */
    fun check(
        pkg: String, title: String, text: String, sub: String?, conversation: String?, own: String,
        skip: Set<String>, gmailKeep: Set<String>, calypsoApps: Set<String>, calypsoPeople: Set<String>,
    ): Verdict {
        if (pkg == own) return Verdict.Drop("own app")
        if (pkg in skip) return Verdict.Drop("skip list")
        if (pkg in calypsoApps) return Verdict.Drop("Calypso")
        if (pkg == GMAIL && !gmailKept(sub, gmailKeep)) return Verdict.Drop("another Gmail account")
        if (isCode("$title\n$text")) return Verdict.Drop("code")
        if (title.isNotBlank() && personKey(title) in calypsoPeople) return Verdict.Drop("Calypso")
        if (!conversation.isNullOrBlank() && personKey(conversation) in calypsoPeople) return Verdict.Drop("Calypso")
        return Verdict.Keep(urgent = pkg in PEOPLE_APPS)
    }
}

/**
 * Sleep, from idle time at night (Owner 2026-10-26: he falls asleep with the screen on, watching). Idle = no unlock, no
 * app switch and no screen change. Playing + [settings] `watch_idle_min` idle: ask "Still watching?" once; answered: no
 * new ask for `reask_min`; unanswered `answer_min`: asleep at the last activity. Nothing playing + `still_idle_min` idle
 * at night: asleep too. Asleep, a first activity is a stir; it becomes a wake (Zeus's confirmed wake) once he keeps
 * going `wake_confirm_min` or the night is over, and fades back to sleep after `still_idle_min` quiet. Pure: the
 * listener feeds it ticks and events.
 */
class SleepWatch(val settings: Map<String, Long> = DEFAULTS) {
    sealed interface Out {
        data object Ask : Out
        data class Asleep(val at: Long) : Out
        data class Awake(val sleptAt: Long, val wokeAt: Long) : Out
    }

    private var lastActivity: Long? = null
    private var askedAt = 0L
    private var answeredAt: Long? = null
    private var asleep = false
    private var sleptAt = 0L
    private var stirAt: Long? = null

    private fun min(key: String) = (settings[key] ?: DEFAULTS.getValue(key)) * 60_000L

    /** An unlock, app switch or screen change. Asleep, the first one is a stir that [tick] may confirm as a wake. */
    fun activity(at: Long) {
        if (asleep && stirAt == null) stirAt = at
        lastActivity = at; askedAt = 0L
    }

    /** He tapped "Yes" on Still watching. */
    fun answered(at: Long) { answeredAt = at; activity(at) }

    /** One look, about every minute. */
    fun tick(now: Long, playing: Boolean, night: Boolean): Out? {
        val last = lastActivity ?: return null
        if (asleep) {
            val stir = stirAt ?: return null
            if (last - stir >= min("wake_confirm_min") || !night) { asleep = false; stirAt = null; return Out.Awake(sleptAt, stir) }
            if (now - last >= min("still_idle_min")) stirAt = null
            return null
        }
        val idle = now - last
        if (askedAt > 0L) {
            if (now - askedAt >= min("answer_min")) { asleep = true; sleptAt = last; askedAt = 0L; return Out.Asleep(last) }
            return null
        }
        if (playing) {
            if (idle >= min("watch_idle_min") && answeredAt.let { it == null || now - it >= min("reask_min") }) { askedAt = now; return Out.Ask }
            return null
        }
        if (night && idle >= min("still_idle_min")) { asleep = true; sleptAt = last; return Out.Asleep(last) }
        return null
    }

    companion object {
        val DEFAULTS = mapOf("watch_idle_min" to 20L, "reask_min" to 45L, "answer_min" to 4L, "still_idle_min" to 30L, "wake_confirm_min" to 5L, "night_start_h" to 0L, "night_end_h" to 8L)

        /**
         * Night is 12 AM to 8 AM on most days (Owner, 2026-10-26); a weekday can have its own window
         * (night_start_h_sat, night_end_h_sat...), set by him or by Athena.
         */
        fun isNight(hour: Int, settings: Map<String, Long> = DEFAULTS, day: String? = null): Boolean {
            val s = (day?.let { settings["night_start_h_$it"] } ?: settings["night_start_h"] ?: 0L).toInt()
            val e = (day?.let { settings["night_end_h_$it"] } ?: settings["night_end_h"] ?: 8L).toInt()
            return if (s > e) hour >= s || hour < e else hour in s until e
        }
    }
}
