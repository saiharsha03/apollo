package dev.apollo.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.apollo.net.LocalDb
import dev.apollo.ui.Tone
import dev.apollo.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * What's live in the chats right now: who is typing in which thread (the server's "typing" outbox items; an agent's
 * answer landing ends it, and it times out on its own in case the "off" never comes). Also files the other messenger
 * outbox kinds (reaction, edit, delete) into the local db.
 */
object ChatNow {
    const val TYPING_MS = 3 * 60_000L

    /** thread -> (who -> until, ms). */
    val typing = MutableStateFlow<Map<String, Map<String, Long>>>(emptyMap())

    fun apply(db: LocalDb, kind: String, data: JSONObject) {
        val message = data.opt("message")?.toString().orEmpty()
        when (kind) {
            "reaction" -> db.react(message, data.optString("who"), data.optString("emoji"), data.optBoolean("on", true))
            "edit" -> db.editMessage(message, data.optString("text"))
            "delete" -> db.deleteMessage(message)
            "typing" -> set(data.opt("thread")?.toString().orEmpty(), data.optString("who"), data.optBoolean("on"))
        }
    }

    fun set(thread: String, who: String, on: Boolean, now: Long = System.currentTimeMillis()) {
        typing.value = Typing.set(typing.value, thread, who, on, now)
    }

    /** Someone's message landed: they are done typing there. */
    fun spoke(thread: String, who: String) = set(thread, who, false)
}

/** Pure bits of the typing state. */
object Typing {
    fun set(now: Map<String, Map<String, Long>>, thread: String, who: String, on: Boolean, at: Long): Map<String, Map<String, Long>> {
        val here = now[thread].orEmpty().let { if (on) it + (who to at + ChatNow.TYPING_MS) else it - who }
        return if (here.isEmpty()) now - thread else now + (thread to here)
    }

    /** Who is typing in [thread] at [at], in a stable order. */
    fun who(state: Map<String, Map<String, Long>>, thread: String, at: Long): List<String> =
        state[thread].orEmpty().filterValues { it > at }.keys.sorted()

    /** "Athena is typing", "Athena and Iris are typing". */
    fun line(names: List<String>): String? = when (names.size) {
        0 -> null
        1 -> "${names[0]} is typing"
        else -> names.dropLast(1).joinToString(", ") + " and ${names.last()} are typing"
    }
}

/** A small red count on a nav item or tab (red: it needs him); nothing at zero. */
@Composable
fun UnreadBadge(n: Int, modifier: Modifier = Modifier) {
    if (n <= 0) return
    Text(if (n > 99) "99+" else n.toString(), style = Type.small.copy(color = Tone.onSky, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold),
        modifier = modifier.background(Tone.alarm, CircleShape).padding(horizontal = 5.dp, vertical = 1.dp))
}

/** Unread agents' messages across these chats (athena, iris, atlas, group), for the nav and tabs. */
@Composable
fun rememberUnread(vararg agents: String): Int {
    val context = LocalContext.current
    val db = remember { LocalDb.get(context) }
    val tick by db.changed.collectAsState()
    val key = agents.toList()
    val n by produceState(0, tick, key) { value = withContext(Dispatchers.IO) { db.unread(key).values.sum() } }
    return n
}
