package dev.apollo.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.apollo.actions.PhoneActions
import dev.apollo.net.ChatMessage
import dev.apollo.net.ChatThread
import dev.apollo.net.LocalDb
import dev.apollo.net.Server
import dev.apollo.ui.Action
import dev.apollo.ui.Agent
import dev.apollo.ui.AskCard
import dev.apollo.ui.AvatarVideo
import dev.apollo.ui.Choice
import dev.apollo.ui.Row2
import dev.apollo.ui.Tone
import dev.apollo.ui.Type
import dev.apollo.ui.rememberDoing
import dev.apollo.ui.rememberIrisDoing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One chat per agent, plus the group (Owner, Athena, Iris and Atlas together). [wire] is the server's `agent`. */
enum class Room(val wire: String, val word: String, val agent: Agent?, val hint: String) {
    GROUP("group", "Everyone", null, "Message Athena, Iris or Atlas"),
    ATHENA("athena", "Athena", Agent.ATHENA, "Message Athena"),
    IRIS("iris", "Iris", Agent.IRIS, "Message Iris"),
    ATLAS("atlas", "Atlas", Agent.ATLAS, "Message Atlas"),
}

object Chat {
    /** Who a message goes to: an existing thread sends none (the server routes by the thread's agent); a new one, the chat's. Pure. */
    fun to(room: Room, thread: String?): String? = if (thread != null) null else room.wire

    /** "from Athena" on a job another agent put in this agent's chat (Athena handing Iris work); null otherwise. Pure. */
    /** The server sends seconds; the phone keeps ms. Older rows saved as seconds read right too. */
    fun ms(at: Double): Long = if (at < 1e11) (at * 1000).toLong() else at.toLong()

    /** "8:26 PM" today, "Mon 8:26 PM" this week, "Oct 20, 8:26 PM" before that; Boston time. */
    fun time(at: Long, now: Long = System.currentTimeMillis()): String {
        val z = java.time.ZoneId.of("America/New_York")
        val t = java.time.Instant.ofEpochMilli(ms(at.toDouble())).atZone(z)
        val today = java.time.Instant.ofEpochMilli(now).atZone(z).toLocalDate()
        val days = java.time.temporal.ChronoUnit.DAYS.between(t.toLocalDate(), today)
        val clock = java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US)
        return when {
            days == 0L -> t.format(clock)
            days in 1..6 -> t.format(java.time.format.DateTimeFormatter.ofPattern("EEE ", java.util.Locale.US)) + t.format(clock)
            else -> t.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, ", java.util.Locale.US)) + t.format(clock)
        }
    }

    private val boston = java.time.ZoneId.of("America/New_York")

    /** "8:26 PM": a bubble's time under its day separator. Pure. */
    fun clock(at: Long): String = java.time.Instant.ofEpochMilli(ms(at.toDouble())).atZone(boston).format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US))

    /** A day separator: "Today", "Yesterday", "Mon Oct 26", with the year when it isn't this one; Boston time. Pure. */
    fun day(at: Long, now: Long = System.currentTimeMillis()): String {
        val d = java.time.Instant.ofEpochMilli(ms(at.toDouble())).atZone(boston).toLocalDate()
        val today = java.time.Instant.ofEpochMilli(now).atZone(boston).toLocalDate()
        return when {
            d == today -> "Today"
            d == today.minusDays(1) -> "Yesterday"
            d.year == today.year -> d.format(java.time.format.DateTimeFormatter.ofPattern("EEE MMM d", java.util.Locale.US))
            else -> d.format(java.time.format.DateTimeFormatter.ofPattern("EEE MMM d, yyyy", java.util.Locale.US))
        }
    }

    /** For each message, the separator to draw above it: a label where the day changes, null elsewhere. Pure. */
    fun dayBreaks(ats: List<Long>, now: Long = System.currentTimeMillis()): List<String?> {
        var last: String? = null
        return ats.map { at -> day(at, now).let { d -> if (d == last) null else d.also { last = it } } }
    }

    fun from(room: Room, sender: String): String? =
        if (room.agent == null || sender == "owner" || sender == room.wire || !agent(sender)) null else "from ${Agent.of(sender).word}"

    private fun agent(sender: String) = Agent.entries.any { it.name.equals(sender, true) }

    /** The name over a bubble: an agent's word, "Apollo" for Apollo's own posts, "Claude Code" for the laptop helper's
     *  posts for him, "Owner" for his, any other sender as written (capitalised). Pure. */
    fun name(sender: String): String = when {
        agent(sender) -> Agent.of(sender).word
        sender.isBlank() -> "Apollo"
        sender == "claude" -> "Claude Code"
        else -> sender.replaceFirstChar { it.uppercase() }
    }

    /** The server's thread list into apollo.db: titles, agents and kinds (a thread it gives no kind for stays unknown). Blocking. */
    fun pullThreads(c: android.content.Context) {
        val db = LocalDb.get(c)
        val a = Server(c).threads()
        for (i in 0 until a.length()) a.getJSONObject(i).let { t ->
            db.putThread(ChatThread(t.get("id").toString(), t.optString("title", "Chat"), ms(t.optDouble("updated")), t.optString("last"), t.optString("agent", "athena").ifEmpty { "athena" }, t.optString("kind"), t.optInt("archived") == 1))
        }
    }

    /** A new thread's title: the start of its first message. Pure. */
    fun title(text: String): String = text.trim().lineSequence().first().take(48).ifEmpty { "Chat" }

    /** A new thread's title when it starts with an attachment: from his words, else "Photo 8:26 PM" (never the file's name). Pure. */
    fun title(text: String, image: Boolean, now: Long = System.currentTimeMillis()): String =
        if (text.isNotBlank()) title(text) else "${if (image) "Photo" else "File"} ${time(now, now)}"
}

/**
 * One agent's chat (or the group): her banner, her threads, one thread's messages with inline asks, and the composer.
 * The open thread is MainActivity's ([open]): it is on the back stack, so Back returns to where he came from.
 */
@Composable
fun ChatScreen(room: Room = Room.ATHENA, open: String? = null, onOpen: (String) -> Unit = {}, onBack: () -> Unit = {}) {
    var fresh by rememberSaveable(room) { mutableStateOf(false) }
    BackHandler(fresh && open == null) { fresh = false }
    when {
        open != null -> ThreadView(room, open, back = onBack)
        fresh -> NewThread(room, onStarted = { fresh = false; onOpen(it) }, back = { fresh = false })
        else -> ThreadList(room, onOpen = onOpen, onNew = { fresh = true })
    }
}

@Composable
private fun ThreadList(room: Room, onOpen: (String) -> Unit, onNew: () -> Unit) {
    val context = LocalContext.current
    val db = remember { LocalDb.get(context) }
    val tick by db.changed.collectAsState()
    val threads by produceState(emptyList<ChatThread>(), tick, room) { value = withContext(Dispatchers.IO) { db.threads(room.wire) } }
    val unread by produceState(emptyMap<String, Int>(), tick, room) { value = withContext(Dispatchers.IO) { db.unread(listOf(room.wire)) } }
    val asks by asksFor(room)
    var showSystem by rememberSaveable(room) { mutableStateOf(false) }
    var showArchived by rememberSaveable(room) { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val (archived, kept) = threads.partition { it.archived }
    val (talk, system) = kept.partition { !it.system }
    val scope = rememberCoroutineScope()
    // His archive, on the server too so it holds across installs; a new message there brings the thread back.
    fun archive(t: ChatThread, on: Boolean) = scope.launch(Dispatchers.IO) {
        runCatching { Server(context).archive(t.id, on) }.onSuccess { db.setArchived(t.id, on); problem = null }
            .onFailure { problem = "Couldn't ${if (on) "archive" else "restore"} ${t.title}: the server didn't answer." }
    }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { Chat.pullThreads(context) } } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), state = dev.apollo.rememberBackList("threads"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Spacer(Modifier.height(4.dp)) }
        items(asks, key = { "ask" + it.id }) { AskCard(it) }
        item { Action("New chat with ${if (room == Room.GROUP) "everyone" else room.word}", onClick = onNew) }
        problem?.let { item(key = "problem") { Text(it, style = Type.small.copy(color = Tone.alarm)) } }
        // Press and hold a conversation: Archive.
        items(talk, key = { it.id }) { t -> HeldRow(t, unread[t.id]?.let { "$it new" }, "Archive", { onOpen(t.id) }) { archive(t, true) } }
        // What the phone noticed, routines, bank: one row at the bottom, opened on his tap.
        if (system.isNotEmpty()) {
            item(key = "system") { Row2("System", system.joinToString(", ") { it.title }.take(80), if (showSystem) "Hide" else "Show") { showSystem = !showSystem } }
            if (showSystem) items(system, key = { it.id }) { t -> Row2(t.title, t.last.take(80)) { onOpen(t.id) } }
        }
        // What he archived: one row, opened on his tap; press and hold one to restore it.
        if (archived.isNotEmpty()) {
            item(key = "archived") { Row2("Archived", "${archived.size} put away. Press and hold one to restore it.", if (showArchived) "Hide" else "Show") { showArchived = !showArchived } }
            if (showArchived) items(archived, key = { "a" + it.id }) { t -> HeldRow(t, null, "Restore", { onOpen(t.id) }) { archive(t, false) } }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

/** A thread row that opens on a tap and offers one action ([held]: Archive or Restore) on press and hold. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HeldRow(t: ChatThread, end: String?, held: String, onOpen: () -> Unit, onHeld: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.combinedClickable(onClick = onOpen, onLongClick = { menu = true })) {
        Row2(t.title, t.last.take(80), end)
        androidx.compose.material3.DropdownMenu(menu, { menu = false }, containerColor = Tone.raised) {
            androidx.compose.material3.DropdownMenuItem({ Text(held, style = Type.body) }, { menu = false; onHeld() })
        }
    }
}

/** Asks only show where Athena talks: her chat and the group. */
@Composable
internal fun asksFor(room: Room) = run {
    val context = LocalContext.current
    val changed by PhoneActions.changed.collectAsState()
    produceState(emptyList<PhoneActions.Pending>(), changed, room) {
        value = if (room == Room.ATHENA || room == Room.GROUP) withContext(Dispatchers.IO) { PhoneActions.waiting(context) } else emptyList()
    }
}

@Composable
private fun NewThread(room: Room, onStarted: (String) -> Unit, back: () -> Unit) {
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        Text("Back", style = Type.small.copy(color = Tone.saffron), modifier = Modifier.clickable(onClick = back).padding(vertical = 6.dp))
        Spacer(Modifier.weight(1f))
        Composer(room, thread = null, onSent = onStarted)
    }
}
