package dev.apollo.chat

import android.content.Context
import dev.apollo.calypso.KeystoreSealer

/**
 * What he typed and hasn't sent, per thread (a new chat per room), so it survives a crash or an update. Sealed with its
 * own Keystore key in prefs chat_drafts, like the other private prefs; gone once sent or cleared. Call on IO.
 */
object Drafts {
    private val sealer by lazy { KeystoreSealer("apollo.chat.drafts") }
    private fun p(c: Context) = c.getSharedPreferences("chat_drafts", Context.MODE_PRIVATE)

    /** Pure: a thread's own key, or the room's for a chat not started yet. */
    fun key(room: Room, thread: String?): String = thread?.let { "t.$it" } ?: "new.${room.wire}"

    fun read(c: Context, key: String): String = p(c).getString(key, null)?.let { runCatching { sealer.open(it) }.getOrNull() }.orEmpty()

    fun save(c: Context, key: String, text: String) {
        if (text.isBlank()) p(c).edit().remove(key).apply()
        else runCatching { sealer.seal(text) }.onSuccess { p(c).edit().putString(key, it).apply() }
    }
}
