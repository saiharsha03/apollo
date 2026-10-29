package dev.apollo.home

import android.content.Context
import dev.apollo.net.Card
import dev.apollo.net.CardButton
import org.json.JSONArray
import org.json.JSONObject

/**
 * What a card button does on the phone itself (docs/API.md, outbox `card`): a label alone goes back to Athena as before;
 * a button with `do` runs here, behind his approvals like any of her actions, and she gets the outcome as a fact.
 * `do`: call:<who>, alarm:<HH:MM>, maps:<place>, open_app:<app>, open:<mini app>, thread:<id> (one of his chats).
 */
object CardActions {
    /** What a `do` asks for: a phone action with its args, or a mini app to open in Apollo. */
    sealed interface Run
    data class Phone(val action: String, val args: Map<String, Any>) : Run
    data class OpenMini(val app: String) : Run
    data class OpenThread(val id: String) : Run

    /** The card's buttons from the outbox: labels, or {label, do}. Pure. */
    fun buttons(a: JSONArray?): List<CardButton> = (0 until (a?.length() ?: 0)).mapNotNull { i ->
        when (val x = a!!.opt(i)) {
            is String -> x.trim().takeIf { it.isNotEmpty() }?.let { CardButton(it) }
            is JSONObject -> x.optString("label").trim().takeIf { it.isNotEmpty() }?.let { CardButton(it, x.optString("do").trim().ifEmpty { null }) }
            else -> null
        }
    }

    /** Null when the phone can't run it (an unknown kind, a bad time): the tap then goes to Athena as a label. Pure. */
    fun parse(run: String?): Run? {
        val kind = run?.substringBefore(':', "")?.trim()?.lowercase() ?: return null
        val what = run.substringAfter(':').trim().takeIf { it.isNotEmpty() } ?: return null
        return when (kind) {
            "call" -> Phone("call_person", mapOf("name" to what))
            "alarm" -> Regex("^(\\d{1,2}):(\\d{2})$").find(what)?.let { m ->
                val h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
                if (h in 0..23 && min in 0..59) Phone("alarm", mapOf("hour" to h, "minute" to min)) else null
            }
            // A link, so his open_url rule decides (a tap by default): the place goes to Google Maps.
            "maps" -> Phone("open_url", mapOf("url" to "geo:0,0?q=" + java.net.URLEncoder.encode(what, "UTF-8").replace("+", "%20")))
            "open_app" -> Phone("open_app", mapOf("app" to what))
            "open" -> OpenMini(what.lowercase())
            "thread" -> dev.apollo.chat.ThreadLink.parse(run)?.let { OpenThread(it) }
            else -> null
        }
    }

    /**
     * His tap on [b]: run on the phone (approvals decide; a tap or fingerprint one waits on Home like her own asks), then a
     * card_feedback fact with what ran. [open] opens a mini app. Blocking: call off the main thread. Null: nothing ran.
     */
    fun tap(c: Context, card: Card, b: CardButton, open: (String) -> Unit): String? {
        val data = CardFeedback.data(card, b.label)
        val out = when (val r = parse(b.run)) {
            null -> null
            is OpenMini -> { open(r.app); "opened ${r.app}" }
            // MainActivity checks it is one of his threads (an unknown id does nothing).
            is OpenThread -> { dev.apollo.chat.ThreadLink.wanted.value = r.id; "opened thread ${r.id}" }
            is Phone -> runCatching { dev.apollo.net.Actions.current.run(c, "card-${System.currentTimeMillis()}", r.action, JSONObject(r.args)).second }.getOrElse { "failed: ${it.javaClass.simpleName}" }
        }
        if (out != null) data.put("do", b.run).put("result", out)
        dev.apollo.net.Facts.post(c, "card_feedback", data)
        return out
    }
}
