package dev.apollo.senses

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import dev.apollo.approvals.ApolloDb
import org.json.JSONObject

/** WhatsApp's call lines and the call log's types, as words. Pure. */
object CallRules {
    enum class Kind { ONGOING, INCOMING, OUTGOING, MISSED }
    data class WaCall(val name: String, val kind: Kind, val video: Boolean, val group: Boolean)

    val PKGS = setOf("com.whatsapp", "com.whatsapp.w4b")
    private val ONGOING = Regex("(?i)^ongoing (group )?(voice|video) call$")
    private val INCOMING = Regex("(?i)^incoming (group )?(voice|video) call$")
    private val OUTGOING = Regex("(?i)^(calling|ringing)(…|\\.\\.\\.)?$")
    private val MISSED = Regex("(?i)^(\\d+ )?missed (group )?(voice |video )?calls?( at .{1,12})?$")
    private val COUNT = Regex("\\s*\\(\\d+\\)$")

    /** Only WhatsApp's fixed call lines match (Zeus's CallNotes); a message, whatever it says, is null. */
    fun parse(pkg: String, title: String?, text: String?): WaCall? {
        if (pkg !in PKGS) return null
        val t = text?.trim().orEmpty()
        val (kind, m) = when {
            ONGOING.matches(t) -> Kind.ONGOING to ONGOING.find(t)
            INCOMING.matches(t) -> Kind.INCOMING to INCOMING.find(t)
            OUTGOING.matches(t) -> Kind.OUTGOING to null
            MISSED.matches(t) -> Kind.MISSED to null
            else -> return null
        }
        val name = title?.replace(COUNT, "")?.trim()?.takeIf { it.isNotEmpty() && !it.equals("WhatsApp", true) && !MISSED.matches(it) } ?: return null
        val video = m?.groupValues?.get(2)?.equals("video", true) ?: t.contains("video", true)
        val group = m?.groupValues?.get(1)?.isNotBlank() ?: t.contains("group", true)
        return WaCall(name.take(60), kind, video, group)
    }

    /** What a WhatsApp call line says happened; ringing (incoming) is not yet a call. */
    fun dir(k: Kind): String? = when (k) {
        Kind.ONGOING -> "connected"
        Kind.OUTGOING -> "outgoing"
        Kind.MISSED -> "missed"
        Kind.INCOMING -> null
    }

    /** The call log's TYPE as a word. */
    fun dir(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "incoming"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        CallLog.Calls.BLOCKED_TYPE -> "blocked"
        CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "answered elsewhere"
        else -> "other"
    }

    /** WhatsApp re-posts an ongoing call every few seconds: the same person and line within 20 minutes is one call. */
    fun repeat(last: Long?, now: Long): Boolean = last != null && now - last < 20 * 60_000L

    /** A person he sealed in Calypso, by name or number (Zeus's keys). */
    fun sealed(name: String?, number: String?, people: Set<String>): Boolean =
        listOfNotNull(name, number).any { it.isNotBlank() && NoticeRules.personKey(it) in people }
}

/**
 * Calls (routines.calls): the phone's call log, read once a minute from where it left off, and WhatsApp's call lines,
 * heard by the notification listener. Each is a `call` fact {at, who, number, how, dir, missed, seconds}. People and
 * apps sealed in Calypso and apps on the skip list never leave the phone.
 */
object CallSense {
    private fun prefs(c: Context) = c.getSharedPreferences("senses_calls", Context.MODE_PRIVATE)
    private val wa = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun allowed(c: Context) = c.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    /** The listener's minute loop. The first read reaches back 14 days, so routines has his recent calls from day one. */
    fun look(c: Context) {
        if (!allowed(c)) return
        val p = prefs(c)
        val last = p.getLong("id", -1L)
        val sealed = CalypsoChoices.read(c).people
        val (where, args) = if (last < 0) "${CallLog.Calls.DATE} >= ?" to arrayOf("${System.currentTimeMillis() - 14 * 86_400_000L}")
        else "${CallLog.Calls._ID} > ?" to arrayOf("$last")
        var top = last
        c.contentResolver.query(CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls._ID, CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
            where, args, "${CallLog.Calls._ID} ASC")?.use { k ->
            while (k.moveToNext()) {
                top = maxOf(top, k.getLong(0))
                val name = k.getString(1)?.takeIf { it.isNotBlank() }
                val number = k.getString(2)?.takeIf { it.isNotBlank() }
                if (CallRules.sealed(name, number, sealed)) continue
                val dir = CallRules.dir(k.getInt(3))
                Senses.sink.post("call", JSONObject().put("at", k.getLong(4)).put("who", name ?: number ?: "unknown").put("number", number ?: JSONObject.NULL)
                    .put("how", "phone").put("dir", dir).put("missed", dir == "missed").put("seconds", k.getInt(5)), false)
            }
        }
        if (top != last) p.edit().putLong("id", top).apply()
    }

    /** Every posted notification, ongoing ones too (WhatsApp's "Ongoing voice call" is ongoing), before the listener's own filters. */
    fun notification(c: Context, pkg: String, title: String?, text: String?, at: Long) {
        val call = CallRules.parse(pkg, title, text) ?: return
        val dir = CallRules.dir(call.kind) ?: return
        if (pkg in ApolloDb.get(c).skipApps()) return
        val sealed = CalypsoChoices.read(c)
        if (pkg in sealed.apps || CallRules.sealed(call.name, null, sealed.people)) return
        val key = "${call.name}|${call.kind}"
        if (CallRules.repeat(wa[key], at)) return
        wa[key] = at
        Senses.sink.post("call", JSONObject().put("at", at).put("who", call.name).put("number", JSONObject.NULL)
            .put("how", if (call.video) "whatsapp video" else "whatsapp").put("dir", dir).put("missed", call.kind == CallRules.Kind.MISSED)
            .put("group", call.group).put("seconds", JSONObject.NULL), false)
    }
}
