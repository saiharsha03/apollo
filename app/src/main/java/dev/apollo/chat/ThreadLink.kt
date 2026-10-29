package dev.apollo.chat

import android.content.Context
import android.content.Intent
import dev.apollo.net.LocalDb
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * `thread:<id>`: a card button or a notification's open that lands on one of his threads in Chat (an alert's "Open").
 * Only a thread the phone already has opens; an unknown id is ignored. [wanted] holds it until Chat opens it.
 */
object ThreadLink {
    const val EXTRA = "open_thread"
    val wanted = MutableStateFlow<String?>(null)

    /** The thread id in `thread:<id>`, or null. Pure. */
    fun parse(open: String?): String? = open?.trim()?.takeIf { it.startsWith("thread:", ignoreCase = true) }
        ?.substring(7)?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

    /** Where a thread lives: the room of its agent. Pure. */
    fun room(agent: String): Room = Room.entries.firstOrNull { it.wire == agent } ?: Room.ATHENA

    /** The thread, if it is one of his on this phone. Blocking. */
    fun known(c: Context, id: String): dev.apollo.net.ChatThread? = LocalDb.get(c).threads().firstOrNull { it.id == id }

    /** From a tap's intent (a notification); the activity's own intent and onNewIntent both come here. */
    fun take(intent: Intent?) {
        intent?.getStringExtra(EXTRA)?.let { parse("thread:$it") }?.let { wanted.value = it }
    }
}
