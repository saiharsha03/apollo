package dev.apollo.net

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A card's button: its label, and what the phone runs itself on his tap ([run], home/CardActions), or null: the label goes to Athena. */
@Immutable data class CardButton(val label: String, val run: String? = null) {
    /** Kept as one string in cards.actions: "label" or "label\u001erun". */
    val stored get() = if (run == null) label else label + "\u001e" + run
    companion object { fun of(stored: String) = stored.split('\u001e', limit = 2).let { CardButton(it[0], it.getOrNull(1)) } }
}

/** One of Athena's cards on Home. [actions] are its buttons; [state] is open, seen or dismissed. [until]: it leaves Home then; [countdown]: the time it counts down to. */
@Immutable data class Card(val id: String, val title: String, val body: String, val actions: List<CardButton>, val app: String?, val at: Long, val state: String,
    val until: Long? = null, val countdown: Long? = null)

/**
 * [agent]: whose chat it is (athena, iris, atlas, or group), as the server says. [kind]: chat (a conversation), zeus (one
 * carried over from Zeus), a system thread (facts, routines, bank, calls, taps), or "" (not known yet: the server hasn't said).
 * Only chat counts as unread. [archived]: he put it away (hidden, never unread) until a new message lands.
 */
@Immutable data class ChatThread(val id: String, val title: String, val updated: Long, val last: String, val agent: String = "athena", val kind: String = "chat",
    val archived: Boolean = false) {
    val system get() = kind !in setOf("chat", "zeus", "")
}

/** Unread counting. Pure. */
object Unread {
    /** One agent's message: its thread, the thread's kind, when (ms), and whether he archived the thread. */
    data class Msg(val thread: String, val kind: String, val at: Long, val archived: Boolean = false)

    /** Per thread, the messages after both [base] and his read mark there; only explicit chat threads count, never archived ones. */
    fun count(msgs: List<Msg>, reads: Map<String, Long>, base: Long): Map<String, Int> =
        msgs.filter { it.kind == "chat" && !it.archived && it.at > maxOf(base, reads[it.thread] ?: 0) }.groupingBy { it.thread }.eachCount()
}

/** [choices]: answer buttons under it (thread_post choices); his tap sends the label. */
@Immutable data class ChatMessage(val id: String, val thread: String, val from: String, val text: String, val at: Long, val choices: List<String> = emptyList(),
    val replyTo: String? = null, val edited: Boolean = false, val deleted: Boolean = false, val file: ChatFile? = null, val reactions: List<Reaction> = emptyList())

/** A photo or file he attached (the server's upload id). */
@Immutable data class ChatFile(val id: String, val name: String, val mime: String, val size: Long) {
    val image get() = mime.startsWith("image/")
}

@Immutable data class Reaction(val who: String, val emoji: String)

/**
 * apollo.db: what the server sent the phone (cards, chat, actions, rules, calls) and the sync cursor. Local only, no
 * backup. [changed] ticks on every write so screens re-read.
 */
class LocalDb private constructor(c: Context) : SQLiteOpenHelper(c, "apollo.db", null, 7) {
    private val tick = MutableStateFlow(0L)
    val changed: StateFlow<Long> get() = tick
    private fun bump() { tick.value = tick.value + 1 }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE cards (id TEXT PRIMARY KEY, title TEXT NOT NULL, body TEXT NOT NULL, actions TEXT NOT NULL, app TEXT, at INTEGER NOT NULL, state TEXT NOT NULL DEFAULT 'open', until INTEGER, countdown INTEGER)")
        db.execSQL("CREATE TABLE threads (id TEXT PRIMARY KEY, title TEXT NOT NULL, updated INTEGER NOT NULL, last TEXT NOT NULL, agent TEXT NOT NULL DEFAULT 'athena', kind TEXT NOT NULL DEFAULT 'chat', archived INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE messages (id TEXT PRIMARY KEY, thread TEXT NOT NULL, sender TEXT NOT NULL, text TEXT NOT NULL, at INTEGER NOT NULL, choices TEXT, " +
            "reply_to TEXT, edited INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0, file TEXT, reactions TEXT)")
        db.execSQL("CREATE TABLE inbox (id TEXT PRIMARY KEY, kind TEXT NOT NULL, data TEXT NOT NULL, at INTEGER NOT NULL, result TEXT)")
        db.execSQL("CREATE TABLE kv (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
    }

    // 2: threads.agent (each agent has its own chat, Owner 2026-10-26).
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE threads ADD COLUMN agent TEXT NOT NULL DEFAULT 'athena'")
        // 3: cards that expire or count down, answer buttons under a message.
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE cards ADD COLUMN until INTEGER")
            db.execSQL("ALTER TABLE cards ADD COLUMN countdown INTEGER")
            db.execSQL("ALTER TABLE messages ADD COLUMN choices TEXT")
        }
        // 4: messenger bits (quote, edit, delete, attached file, reactions).
        if (oldVersion < 4) {
            for (col in listOf("reply_to TEXT", "edited INTEGER NOT NULL DEFAULT 0", "deleted INTEGER NOT NULL DEFAULT 0", "file TEXT", "reactions TEXT"))
                db.execSQL("ALTER TABLE messages ADD COLUMN $col")
        }
        // 5: threads.kind, so system threads stop counting as unread. Known ones by their fixed titles; the next threads fetch sets the rest.
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE threads ADD COLUMN kind TEXT NOT NULL DEFAULT 'chat'")
            db.execSQL("UPDATE threads SET kind = CASE WHEN title = 'What the phone noticed' THEN 'facts' WHEN title = 'Routines' THEN 'routines' " +
                "WHEN title = 'Bank notifications' THEN 'bank' WHEN id = 'calls' THEN 'calls' WHEN title LIKE 'Zeus chat, %' THEN 'zeus' ELSE 'chat' END")
        }
        // 6: v5 guessed by title, and a thread first seen through sync is titled "Chat", so system threads stayed chat
        // and filled the badges. Chat kinds are unknown until the server's thread list says (on start); history so far is read.
        if (oldVersion < 6) {
            db.execSQL("UPDATE threads SET kind = '' WHERE kind = 'chat'")
            db.execSQL("INSERT OR REPLACE INTO kv(k, v) VALUES('read.base', ?)", arrayOf(System.currentTimeMillis().toString()))
        }
        // 7: threads he archived (the server's threads.archived).
        if (oldVersion < 7) db.execSQL("ALTER TABLE threads ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
    }

    fun get(k: String): String? = readableDatabase.rawQuery("SELECT v FROM kv WHERE k = ?", arrayOf(k)).use { if (it.moveToFirst()) it.getString(0) else null }
    fun put(k: String, v: String) { writableDatabase.insertWithOnConflict("kv", null, ContentValues().apply { put("k", k); put("v", v) }, SQLiteDatabase.CONFLICT_REPLACE) }
    fun remove(k: String) { writableDatabase.delete("kv", "k = ?", arrayOf(k)) }
    fun keys(prefix: String): List<String> = readableDatabase.rawQuery("SELECT k FROM kv WHERE substr(k, 1, ?) = ? ORDER BY k", arrayOf(prefix.length.toString(), prefix)).use { cur ->
        buildList { while (cur.moveToNext()) add(cur.getString(0)) }
    }

    // ---- cards ----

    /** [keepState]: Athena changed a card she already sent; it keeps his seen or dismissed, and its place on Home. */
    fun putCard(card: Card, keepState: Boolean = false) {
        if (keepState && writableDatabase.update("cards", ContentValues().apply {
                put("title", card.title); put("body", card.body); put("actions", card.actions.joinToString("\u001f") { it.stored }); put("app", card.app)
                put("until", card.until); put("countdown", card.countdown)
            }, "id = ?", arrayOf(card.id)) > 0) return bump()
        writableDatabase.insertWithOnConflict("cards", null, ContentValues().apply {
            put("id", card.id); put("title", card.title); put("body", card.body); put("actions", card.actions.joinToString("\u001f") { it.stored }); put("app", card.app); put("at", card.at); put("state", card.state)
            put("until", card.until); put("countdown", card.countdown)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        bump()
    }

    /** Open cards whose until hasn't passed. */
    fun openCards(now: Long = System.currentTimeMillis()): List<Card> = readableDatabase.rawQuery(
        "SELECT id, title, body, actions, app, at, state, until, countdown FROM cards WHERE state = 'open' AND (until IS NULL OR until > ?) ORDER BY at DESC", arrayOf(now.toString()),
    ).use { c ->
        buildList { while (c.moveToNext()) add(Card(c.getString(0), c.getString(1), c.getString(2), c.getString(3).split('\u001f').filter { it.isNotEmpty() }.map(CardButton::of), c.getString(4), c.getLong(5), c.getString(6),
            if (c.isNull(7)) null else c.getLong(7), if (c.isNull(8)) null else c.getLong(8))) }
    }

    fun removeCard(id: String) { writableDatabase.delete("cards", "id = ?", arrayOf(id)); bump() }

    fun setCardState(id: String, state: String) { writableDatabase.execSQL("UPDATE cards SET state = ? WHERE id = ?", arrayOf(state, id)); bump() }

    // ---- chat ----

    fun putThread(t: ChatThread) {
        writableDatabase.insertWithOnConflict("threads", null, ContentValues().apply { put("id", t.id); put("title", t.title); put("updated", t.updated); put("last", t.last); put("agent", t.agent); put("kind", t.kind); put("archived", if (t.archived) 1 else 0) }, SQLiteDatabase.CONFLICT_REPLACE)
        bump()
    }

    fun setArchived(thread: String, on: Boolean) { writableDatabase.execSQL("UPDATE threads SET archived = ? WHERE id = ?", arrayOf(if (on) 1 else 0, thread)); bump() }

    /** Every thread, or one agent's chat (athena, iris, atlas, group). */
    fun threads(agent: String? = null): List<ChatThread> = readableDatabase.rawQuery(
        "SELECT id, title, updated, last, agent, kind, archived FROM threads" + (if (agent != null) " WHERE agent = ?" else "") + " ORDER BY updated DESC", agent?.let { arrayOf(it) },
    ).use { c -> buildList { while (c.moveToNext()) add(ChatThread(c.getString(0), c.getString(1), c.getLong(2), c.getString(3), c.getString(4), c.getString(5), c.getInt(6) == 1)) } }

    /**
     * Adds a message and moves its thread up; a thread the phone hasn't seen yet is made with [title]. [replace]: the
     * server's copy (a thread fetch) wins over the one kept, so edits, deletes and reactions made elsewhere show.
     * [touch] false: an older message paged in, which leaves a known thread's last line and time alone. [kind]: the
     * thread's kind as the server sent it; without one a known thread keeps its own and a new one is unknown (""). [fresh]:
     * a new message from the sync, which brings an archived thread back.
     */
    fun putMessage(m: ChatMessage, title: String = "Chat", agent: String? = null, replace: Boolean = false, touch: Boolean = true, kind: String? = null, fresh: Boolean = false) {
        val db = writableDatabase
        db.insertWithOnConflict("messages", null, ContentValues().apply { put("id", m.id); put("thread", m.thread); put("sender", m.from); put("text", m.text); put("at", m.at)
            if (m.choices.isNotEmpty()) put("choices", m.choices.joinToString("\u001f"))
            put("reply_to", m.replyTo); put("edited", if (m.edited) 1 else 0); put("deleted", if (m.deleted) 1 else 0)
            put("file", m.file?.let { listOf(it.id, it.name, it.mime, it.size.toString()).joinToString("\u001f") }); put("reactions", reactionsText(m.reactions))
        }, if (replace) SQLiteDatabase.CONFLICT_REPLACE else SQLiteDatabase.CONFLICT_IGNORE)
        val known = db.rawQuery("SELECT title, agent, kind, archived FROM threads WHERE id = ?", arrayOf(m.thread)).use { if (it.moveToFirst()) Triple(it.getString(0), it.getString(1), it.getString(2)) to (it.getInt(3) == 1) else null }
        val archived = known?.second == true && !fresh
        // A thread the phone hasn't seen: whose it is comes from the caller, else who wrote (the next threads fetch corrects it).
        val owner = known?.first?.second ?: agent ?: m.from.takeIf { it in setOf("iris", "atlas") } ?: "athena"
        if (touch || known == null) db.insertWithOnConflict("threads", null, ContentValues().apply { put("id", m.thread); put("title", known?.first?.first ?: title); put("updated", m.at); put("last", m.text.take(140)); put("agent", owner)
            put("kind", kind ?: known?.first?.third ?: ""); put("archived", if (archived) 1 else 0) }, SQLiteDatabase.CONFLICT_REPLACE)
        bump()
    }

    fun messages(thread: String): List<ChatMessage> = readableDatabase.rawQuery(
        "SELECT id, thread, sender, text, at, choices, reply_to, edited, deleted, file, reactions FROM messages WHERE thread = ? ORDER BY at, id", arrayOf(thread),
    ).use { c ->
        buildList { while (c.moveToNext()) add(ChatMessage(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getString(5)?.split('\u001f')?.filter { it.isNotEmpty() }.orEmpty(),
            c.getString(6), c.getInt(7) == 1, c.getInt(8) == 1,
            c.getString(9)?.split('\u001f')?.takeIf { it.size == 4 }?.let { ChatFile(it[0], it[1], it[2], it[3].toLongOrNull() ?: 0) },
            reactionsOf(c.getString(10)))) }
    }

    private fun reactionsText(r: List<Reaction>) = r.joinToString("\u001f") { it.who + "\u001e" + it.emoji }.ifEmpty { null }

    private fun reactionsOf(s: String?) = s.orEmpty().split('\u001f').mapNotNull { p -> p.split('\u001e').takeIf { it.size == 2 }?.let { Reaction(it[0], it[1]) } }

    // ---- messenger bits, as the outbox or his own taps change them ----

    /** A reaction added or taken back (one per person and emoji). */
    fun react(message: String, who: String, emoji: String, on: Boolean) {
        val now = readableDatabase.rawQuery("SELECT reactions FROM messages WHERE id = ?", arrayOf(message)).use { if (it.moveToFirst()) reactionsOf(it.getString(0)) else return }
        writableDatabase.execSQL("UPDATE messages SET reactions = ? WHERE id = ?", arrayOf(reactionsText(dev.apollo.chat.Reactions.apply(now, who, emoji, on)), message))
        bump()
    }

    fun editMessage(message: String, text: String) { writableDatabase.execSQL("UPDATE messages SET text = ?, edited = 1 WHERE id = ?", arrayOf(text, message)); bump() }

    fun deleteMessage(message: String) {
        writableDatabase.execSQL("UPDATE messages SET text = '', deleted = 1, file = NULL, reactions = NULL, choices = NULL WHERE id = ?", arrayOf(message)); bump()
    }

    /** Read up to [at] (ms) in this thread: the unread counts drop. */
    fun markRead(thread: String, at: Long) {
        if ((get("read.$thread")?.toLongOrNull() ?: 0) >= at) return
        put("read.$thread", at.toString()); bump()
    }

    /**
     * Agents' messages he hasn't seen, per conversation (kind chat) of these chats; system threads never count. Counting
     * starts when this version first ran (read.base), so the history from before isn't all unread.
     */
    fun unread(agents: Collection<String>): Map<String, Int> {
        if (agents.isEmpty()) return emptyMap()
        val base = get("read.base")?.toLongOrNull() ?: System.currentTimeMillis().also { put("read.base", it.toString()) }
        val reads = keys("read.").mapNotNull { k -> get(k)?.toLongOrNull()?.let { k.removePrefix("read.") to it } }.toMap()
        val msgs = readableDatabase.rawQuery(
            "SELECT m.thread, t.kind, m.at, t.archived FROM messages m JOIN threads t ON t.id = m.thread " +
                "WHERE t.agent IN (${agents.joinToString(",") { "?" }}) AND m.sender != 'owner' AND m.deleted = 0 AND m.at > ?",
            (agents.toList() + base.toString()).toTypedArray(),
        ).use { c -> buildList { while (c.moveToNext()) add(Unread.Msg(c.getString(0), c.getString(1), c.getLong(2), c.getInt(3) == 1)) } }
        return Unread.count(msgs, reads, base)
    }

    /** Who spoke last in an agent's chat, and when (milliseconds); null when it has no messages. */
    fun last(agent: String): Pair<String, Long>? = readableDatabase.rawQuery(
        "SELECT m.sender, m.at FROM messages m JOIN threads t ON m.thread = t.id WHERE t.agent = ? ORDER BY m.at DESC, m.id DESC LIMIT 1", arrayOf(agent),
    ).use { if (it.moveToFirst()) it.getString(0) to it.getLong(1) else null }

    fun lastMessageId(thread: String): String? = readableDatabase.rawQuery("SELECT id FROM messages WHERE thread = ? ORDER BY at DESC, id DESC LIMIT 1", arrayOf(thread)).use { if (it.moveToFirst()) it.getString(0) else null }

    // ---- actions, rules, calls (kept as they came until their features are wired) ----

    fun putInbox(id: String, kind: String, data: String, at: Long) {
        writableDatabase.insertWithOnConflict("inbox", null, ContentValues().apply { put("id", id); put("kind", kind); put("data", data); put("at", at) }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun setInboxResult(id: String, result: String) = writableDatabase.execSQL("UPDATE inbox SET result = ? WHERE id = ?", arrayOf(result, id))

    companion object {
        @Volatile private var instance: LocalDb? = null
        fun get(c: Context): LocalDb = instance ?: synchronized(this) { instance ?: LocalDb(c.applicationContext).also { instance = it } }
    }
}
