package dev.apollo.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.apollo.net.ChatFile
import dev.apollo.net.ChatMessage
import dev.apollo.net.LocalDb
import dev.apollo.net.Reaction
import dev.apollo.net.Server
import dev.apollo.ui.Action
import dev.apollo.ui.Agent
import dev.apollo.ui.AskCard
import dev.apollo.ui.Choice
import dev.apollo.ui.Tone
import dev.apollo.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

/** The thread endpoint says `sender`; the outbox's message event says `from`. Pure. */
internal fun senderOf(sender: String, from: String): String = sender.ifEmpty { from }

/** One message from GET /v1/threads/{id} (the server's copy: text, edits, tombstone, quote, file, reactions). */
internal fun messageOf(m: JSONObject, thread: String): ChatMessage {
    fun list(k: String) = m.optJSONArray(k)
    val file = m.optJSONObject("file")?.let { ChatFile(it.get("id").toString(), it.optString("name"), it.optString("mime"), it.optLong("size")) }
    val reactions = list("reactions")?.let { a -> List(a.length()) { a.getJSONObject(it).let { r -> Reaction(r.optString("who"), r.optString("emoji")) } } }.orEmpty()
    return ChatMessage(
        m.get("id").toString(), thread, senderOf(m.optString("sender"), m.optString("from")), m.optString("text"), Chat.ms(m.optDouble("at")),
        list("choices")?.let { c -> List(c.length()) { c.getString(it) } }.orEmpty(),
        m.opt("reply_to")?.takeIf { it != JSONObject.NULL }?.toString(), !m.isNull("edited"), m.optInt("deleted") == 1, file, reactions,
    )
}

/** Scrolling back through a thread a page at a time. Pure. */
internal object ThreadPages {
    const val SIZE = 100

    /** The oldest server message id among [ids]; null when there is none. */
    fun before(ids: List<String>): String? = ids.mapNotNull { it.toLongOrNull() }.minOrNull()?.toString()

    /**
     * Where the next older page starts: the oldest id of the pages fetched this time, newest down without a gap. Not the
     * oldest id the phone holds: older cached messages may sit below a gap the server still has to fill.
     */
    fun edge(page: List<String>, was: String?): String? = before(page) ?: was

    /** A page shorter than asked for reached the thread's first message. */
    fun more(got: Int): Boolean = got >= SIZE
}

/** His uploads, kept on the phone once seen (filesDir/chat-uploads/<id>). Blocking. */
internal object ChatFiles {
    const val MAX_BYTES = 20 * 1024 * 1024

    fun cached(c: Context, id: String) = uploadFile(File(c.filesDir, "chat-uploads"), id) ?: throw IllegalArgumentException("bad upload id")

    /** The server's upload id names a file here only when it is a number and stays inside [dir] (scan F6). Pure. */
    fun uploadFile(dir: File, id: String): File? {
        if (!Regex("^[0-9]{1,18}$").matches(id)) return null
        val f = File(dir, id)
        return f.takeIf { it.canonicalFile.parentFile == dir.canonicalFile }
    }

    fun keep(c: Context, id: String, bytes: ByteArray) = cached(c, id).let { it.parentFile?.mkdirs(); it.writeBytes(bytes) }

    /** The image, scaled down to about [px] on its long side. */
    fun image(c: Context, id: String, px: Int = 1280): ImageBitmap? {
        val f = cached(c, id)
        if (!f.exists()) keep(c, id, Server(c).uploadBytes(id))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }

    fun size(n: Long): String = when {
        n >= 1024 * 1024 -> "%.1f MB".format(n / 1048576.0)
        n >= 1024 -> "${n / 1024} KB"
        else -> "$n B"
    }
}

/** A file he picked to send: read when he taps Send. */
private data class Picked(val uri: Uri, val name: String, val mime: String, val size: Long)

private fun describe(c: Context, uri: Uri): Picked {
    var name = "file"
    var size = -1L
    c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
        if (it.moveToFirst()) { name = it.getString(0) ?: name; if (!it.isNull(1)) size = it.getLong(1) }
    }
    val mime = c.contentResolver.getType(uri)?.takeIf { Regex("[a-z]+/[a-z0-9.+-]+").matches(it) } ?: "application/octet-stream"
    return Picked(uri, name, mime, size)
}

/** One list row in a thread: a day separator or a message. */
private sealed interface Line {
    data class Day(val label: String) : Line
    data class Msg(val m: ChatMessage) : Line
}

/** One thread: day separators, bubbles with quotes, files and reactions, the typing line, asks, and the composer. */
@Composable
internal fun ThreadView(room: Room, thread: String, back: () -> Unit) {
    val context = LocalContext.current
    val db = remember { LocalDb.get(context) }
    val tick by db.changed.collectAsState()
    val messages by produceState(emptyList<ChatMessage>(), tick, thread) { value = withContext(Dispatchers.IO) { db.messages(thread) } }
    val title by produceState("Chat", tick, thread) { value = withContext(Dispatchers.IO) { db.threads().firstOrNull { it.id == thread }?.title ?: "Chat" } }
    val asks by asksFor(room)
    val typingState by ChatNow.typing.collectAsState()
    // The typing line expires by time: tick once a while so it goes away when its "off" never came.
    val clock by produceState(System.currentTimeMillis()) { while (true) { delay(15_000); value = System.currentTimeMillis() } }
    val typing = Typing.line(Typing.who(typingState, thread, maxOf(clock, System.currentTimeMillis())).map { Agent.of(it).word })
    // Back from somewhere he went from this thread: where he was, not the newest line.
    val restored = dev.apollo.backMark("thread") != null
    val list = dev.apollo.rememberBackList("thread")
    val scope = rememberCoroutineScope()
    var replyTo by remember(thread) { mutableStateOf<ChatMessage?>(null) }
    var editing by remember(thread) { mutableStateOf<ChatMessage?>(null) }
    var menu by remember { mutableStateOf<ChatMessage?>(null) }
    var picker by remember { mutableStateOf<ChatMessage?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val byId = remember(messages) { messages.associateBy { it.id } }
    // Apollo's raw posts (facts dumps, heartbeats, handoffs) only when he asks for them.
    var raw by remember(thread) { mutableStateOf(false) }
    val visible = remember(messages, raw) { RawPosts.shown(messages, raw) }
    val lines = remember(visible) {
        val breaks = Chat.dayBreaks(visible.map { it.at })
        visible.flatMapIndexed { i, m -> listOfNotNull(breaks[i]?.let { Line.Day(it) }, Line.Msg(m)) }
    }

    fun later(what: String, block: suspend () -> Unit) = scope.launch {
        runCatching { withContext(Dispatchers.IO) { block() } }.onFailure { problem = "$what didn't go through: ${it.message ?: it.javaClass.simpleName}" }
    }
    // His tap on an answer button: the label goes as his reply, like typing it.
    fun send(label: String) = later("Your answer") {
        val (_, id) = Server(context).chat(thread, title, label, null)
        db.putMessage(ChatMessage(id, thread, "owner", label, System.currentTimeMillis()), fresh = true)
    }
    fun react(m: ChatMessage, emoji: String, on: Boolean) = later("The reaction") {
        db.react(m.id, "owner", emoji, on)          // shows at once; taken back below if the server says no
        runCatching { Server(context).react(m.id, emoji, on) }.onFailure { db.react(m.id, "owner", emoji, !on); throw it }
    }

    var loaded by remember(thread) { mutableStateOf(false) }
    var more by remember(thread) { mutableStateOf(true) }
    var paging by remember(thread) { mutableStateOf(false) }
    var edge by remember(thread) { mutableStateOf<String?>(null) }
    LaunchedEffect(thread) {
        withContext(Dispatchers.IO) {
            runCatching {
                // The latest page: edits, deletes and reactions made since show too. Older pages come in as he scrolls up.
                val a = Server(context).thread(thread, null, limit = ThreadPages.SIZE)
                val page = List(a.length()) { messageOf(a.getJSONObject(it), thread) }
                page.forEach { db.putMessage(it, agent = room.wire, replace = true) }
                edge = ThreadPages.edge(page.map { it.id }, null)
                more = ThreadPages.more(a.length())
            }
        }
        loaded = true
    }
    val atTop by remember { derivedStateOf { list.firstVisibleItemIndex == 0 } }
    LaunchedEffect(atTop, loaded, messages.size, edge) {
        val before = edge
        if (!atTop || !loaded || !more || paging || before == null) return@LaunchedEffect
        paging = true
        scope.launch {      // not this effect's own coroutine: the page landing changes its keys
            runCatching {
                withContext(Dispatchers.IO) {
                    val a = Server(context).thread(thread, null, before, ThreadPages.SIZE)
                    List(a.length()) { messageOf(a.getJSONObject(it), thread) }.onEach { db.putMessage(it, agent = room.wire, replace = true, touch = false) }
                }
            }.onSuccess { page -> edge = ThreadPages.edge(page.map { it.id }, edge); more = ThreadPages.more(page.size) }
            paging = false
        }
    }
    LaunchedEffect(messages.lastOrNull()?.at) { messages.lastOrNull()?.let { m -> withContext(Dispatchers.IO) { db.markRead(thread, m.at) } } }
    // Down to the newest line when something new lands (an older page coming in above leaves him where he is).
    val count = lines.size + asks.size + (if (typing != null) 1 else 0)
    var hold by remember(thread) { mutableStateOf(restored) }
    LaunchedEffect(messages.lastOrNull()?.id, asks.size, typing != null) {
        if (hold) { if (messages.isNotEmpty()) hold = false } else if (count > 0) list.animateScrollToItem(count - 1)
    }

    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Back", style = Type.small.copy(color = Tone.saffron), modifier = Modifier.clickable(onClick = back).padding(vertical = 6.dp))
            Text(title, style = Type.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, modifier = Modifier.weight(1f))
            if (raw || visible.size < messages.size) Text(if (raw) "Hide raw" else "Show raw", style = Type.small.copy(color = Tone.quiet),
                modifier = Modifier.clickable { raw = !raw }.padding(vertical = 6.dp))
        }
        Spacer(Modifier.height(10.dp))
        problem?.let { Text(it, style = Type.small.copy(color = Tone.alarm), modifier = Modifier.clickable { problem = null }.padding(bottom = 6.dp)) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(lines, key = { _, l -> if (l is Line.Msg) l.m.id else "day" + (l as Line.Day).label }) { _, l ->
                when (l) {
                    is Line.Day -> DaySeparator(l.label)
                    is Line.Msg -> Bubble(
                        room, l.m, quoted = l.m.replyTo?.let { byId[it] },
                        answer = if (l.m == messages.last()) { label -> send(label) } else null,
                        onLong = { menu = l.m },
                        onReply = { replyTo = l.m; editing = null },
                        onQuote = { id -> lines.indexOfFirst { it is Line.Msg && it.m.id == id }.takeIf { it >= 0 }?.let { scope.launch { list.animateScrollToItem(it) } } },
                        onChip = { chip -> react(l.m, chip.emoji, !chip.mine) },
                    )
                }
            }
            items(asks, key = { "ask" + it.id }) { AskCard(it) }
            if (typing != null) item(key = "typing") { Text(typing, style = Type.small.copy(color = Tone.quiet), modifier = Modifier.padding(start = 6.dp)) }
        }
        Composer(room, thread = thread, onSent = {}, replyTo = replyTo, editing = editing, onCancel = { replyTo = null; editing = null })
    }

    menu?.let { m ->
        MessageMenu(m,
            onReact = { e -> menu = null; react(m, e, Reaction("owner", e) !in m.reactions) },
            onMore = { menu = null; picker = m },
            onReply = { menu = null; replyTo = m; editing = null },
            onEdit = { menu = null; editing = m; replyTo = null },
            onDelete = { menu = null; later("Delete") { Server(context).deleteMessage(m.id); db.deleteMessage(m.id) } },
            dismiss = { menu = null })
    }
    picker?.let { m -> EmojiPicker(onPick = { e -> picker = null; react(m, e, true) }, dismiss = { picker = null }) }
}

@Composable
private fun DaySeparator(label: String) = Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
    Text(label, style = Type.small.copy(color = Tone.quiet, fontWeight = FontWeight.SemiBold),
        modifier = Modifier.background(Tone.soft, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 4.dp))
}

/**
 * Muse bubbles: his dark on the right, theirs soft grey on the left under the speaker's name ("from Athena" on a handed job).
 * Long-press opens the menu, a swipe right replies, the quote on top scrolls to its message, chips are reactions.
 * [answer] set: the message's answer buttons show under it (only on the latest message).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun Bubble(
    room: Room, m: ChatMessage, quoted: ChatMessage?, answer: ((String) -> Unit)?,
    onLong: () -> Unit, onReply: () -> Unit, onQuote: (String) -> Unit, onChip: (Reactions.Chip) -> Unit,
) {
    val mine = m.from == "owner"
    val ink = Tone.ink
    val pull = with(LocalDensity.current) { 64.dp.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        if (!mine) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SenderMark(m.from)
            Text(Chat.name(m.from), style = Type.small.copy(color = Tone.ink, fontWeight = FontWeight.SemiBold))
            Chat.from(room, m.from)?.let { Text(it, style = Type.small.copy(color = Tone.quiet)) }
        }
        Column(
            Modifier.padding(top = 2.dp).widthIn(max = 520.dp)
                .offset { IntOffset(drag.roundToInt(), 0) }
                .pointerInput(m.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = { if (drag >= pull) onReply(); drag = 0f },
                        onDragCancel = { drag = 0f },
                    ) { _, dx -> drag = (drag + dx).coerceIn(0f, pull * 1.5f) }
                }
                .clip(RoundedCornerShape(20.dp))
                .background(if (mine) Tone.mine else Tone.soft)
                .combinedClickable(onClick = {}, onLongClick = onLong)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            quoted?.let { q -> Quote(q, mine) { onQuote(q.id) } }
            m.file?.let { FileView(it, mine) }
            when {
                m.deleted -> Text(if (mine) "You deleted this message" else "Deleted", style = Type.body.copy(color = ink.copy(alpha = .6f), fontStyle = FontStyle.Italic))
                m.text.isEmpty() -> {}
                mine -> Text(m.text, style = Type.body.copy(color = ink))
                else -> MarkdownText(m.text, ink)
            }
        }
        val chips = Reactions.chips(m.reactions)
        if (chips.isNotEmpty()) FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            chips.forEach { c ->
                Text(if (c.count > 1) "${c.emoji} ${c.count}" else c.emoji, style = Type.small.copy(color = Tone.ink),
                    modifier = Modifier.background(if (c.mine) Tone.saffron.copy(alpha = .14f) else Tone.raised, CircleShape)
                        .clickable { onChip(c) }.padding(horizontal = 9.dp, vertical = 3.dp))
            }
        }
        Text(Chat.clock(m.at) + if (m.edited && !m.deleted) "  edited" else "", style = Type.small.copy(color = Tone.quiet), modifier = Modifier.padding(top = 3.dp, start = 6.dp, end = 6.dp))
        if (answer != null && !mine && m.choices.isNotEmpty()) FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            m.choices.forEachIndexed { i, c -> Choice(c, i == 0, false) { answer(c) } }
        }
    }
}

/** The quoted message on top of a bubble (or above the composer): who and the start of it. */
@Composable
private fun Quote(q: ChatMessage, onDark: Boolean, onClick: () -> Unit) {
    val ink = Tone.ink
    Row(Modifier.padding(bottom = 6.dp).height(IntrinsicSize.Min).clickable(onClick = onClick)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(if (onDark) Tone.ink.copy(alpha = .6f) else Tone.saffron, RoundedCornerShape(2.dp)))
        Column(Modifier.padding(start = 8.dp)) {
            Text(if (q.from == "owner") "You" else Chat.name(q.from), style = Type.small.copy(color = ink, fontWeight = FontWeight.SemiBold))
            Text(if (q.deleted) "Deleted" else q.text.ifEmpty { q.file?.name ?: "" }, style = Type.small.copy(color = ink.copy(alpha = .75f)), maxLines = 2)
        }
    }
}

/** An attached photo (tap: full screen) or a file's name and size. */
@Composable
private fun FileView(f: ChatFile, onDark: Boolean) {
    val context = LocalContext.current
    if (!f.image) {
        Column(Modifier.padding(bottom = 6.dp)) {
            Text(f.name, style = Type.body.copy(color = Tone.ink, fontWeight = FontWeight.SemiBold))
            Text(ChatFiles.size(f.size), style = Type.small.copy(color = Tone.quiet))
        }
        return
    }
    val img by produceState<ImageBitmap?>(null, f.id) { value = withContext(Dispatchers.IO) { runCatching { ChatFiles.image(context, f.id) }.getOrNull() } }
    var full by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    img?.let { b ->
        Image(b, f.name, contentScale = ContentScale.Crop, modifier = Modifier.padding(bottom = 6.dp).widthIn(max = 280.dp).heightIn(max = 320.dp).clip(shape).clickable { full = true })
    } ?: Text(f.name, style = Type.small.copy(color = Tone.quiet), modifier = Modifier.padding(bottom = 6.dp))
    if (full) img?.let { b ->
        Dialog(onDismissRequest = { full = false }) {
            Image(b, f.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().clip(shape).clickable { full = false })
        }
    }
}

/** Agents' markdown: paragraphs, headings, items, code blocks; bold, italic, code and links (his tap opens a link). */
@Composable
internal fun MarkdownText(text: String, ink: Color) {
    val blocks = remember(text) { Markdown.parse(text) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEach { b ->
            when (b) {
                is Markdown.Para -> Text(annotated(b.spans), style = Type.body.copy(color = ink))
                is Markdown.Heading -> Text(annotated(b.spans), style = Type.body.copy(color = ink, fontWeight = FontWeight.Bold))
                is Markdown.Item -> Row {
                    Text(b.marker, style = Type.body.copy(color = ink), modifier = Modifier.width(if (b.marker.length > 2) 28.dp else 18.dp))
                    Text(annotated(b.spans), style = Type.body.copy(color = ink))
                }
                is Markdown.Code -> Text(b.text, style = Type.small.copy(color = Tone.ink, fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().background(Tone.ground, RoundedCornerShape(10.dp)).horizontalScroll(rememberScrollState()).padding(10.dp), softWrap = false)
            }
        }
    }
}

private fun annotated(spans: List<Markdown.Span>): AnnotatedString = buildAnnotatedString {
    spans.forEach { s ->
        val style = SpanStyle(
            fontWeight = if (s.bold) FontWeight.Bold else null,
            fontStyle = if (s.italic) FontStyle.Italic else null,
            fontFamily = if (s.code) FontFamily.Monospace else null,
            background = if (s.code) Tone.ground else Color.Unspecified,
            color = if (s.code) Tone.ink else Color.Unspecified,
        )
        if (s.url != null) withLink(LinkAnnotation.Url(s.url, TextLinkStyles(SpanStyle(color = Tone.saffron, textDecoration = TextDecoration.Underline)))) { withStyle(style) { append(s.text) } }
        else withStyle(style) { append(s.text) }
    }
}

/** Long-press: a reaction row (six quick ones, More for the rest), then Reply, Copy, and Edit / Delete on his own. */
@Composable
private fun MessageMenu(m: ChatMessage, onReact: (String) -> Unit, onMore: () -> Unit, onReply: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, dismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val mine = m.from == "owner"
    var confirm by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().background(Tone.raised, RoundedCornerShape(24.dp)).padding(vertical = 10.dp)) {
            if (!m.deleted) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Reactions.quick.forEach { e ->
                    val on = Reaction("owner", e) in m.reactions
                    Text(e, style = Type.heading, modifier = Modifier.clip(CircleShape).background(if (on) Tone.saffron.copy(alpha = .14f) else Color.Transparent)
                        .clickable { onReact(e) }.padding(8.dp))
                }
                Text("More", style = Type.small.copy(color = Tone.saffron, fontWeight = FontWeight.SemiBold), modifier = Modifier.clickable(onClick = onMore).padding(8.dp))
            }
            if (!m.deleted) {
                MenuRow("Reply", onClick = onReply)
                if (m.text.isNotEmpty()) MenuRow("Copy") { clipboard.setText(AnnotatedString(m.text)); dismiss() }
                if (mine && m.text.isNotEmpty()) MenuRow("Edit", onClick = onEdit)
                if (mine) {
                    if (!confirm) MenuRow("Delete") { confirm = true }
                    else MenuRow("Delete for everyone", Tone.alarm, onDelete)
                }
            } else MenuRow("Close", onClick = dismiss)
        }
    }
}

@Composable
private fun MenuRow(label: String, color: Color = Tone.ink, onClick: () -> Unit) =
    Text(label, style = Type.body.copy(color = color, fontWeight = FontWeight.Medium), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmojiPicker(onPick: (String) -> Unit, dismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    Dialog(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).background(Tone.raised, RoundedCornerShape(24.dp)).padding(14.dp)) {
            Text("React with", style = Type.kicker)
            Spacer(Modifier.height(8.dp))
            // Any emoji from the keyboard, beyond the grid.
            BasicTextField(typed, { typed = it.take(16) }, textStyle = Type.heading, singleLine = true, cursorBrush = SolidColor(Tone.saffron),
                modifier = Modifier.fillMaxWidth().background(Tone.soft, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
                decorationBox = { inner -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { if (typed.isEmpty()) Text("Type any emoji", style = Type.body.copy(color = Tone.quiet)); inner() }
                    if (typed.isNotBlank()) Text("Use", style = Type.small.copy(color = Tone.saffron, fontWeight = FontWeight.SemiBold), modifier = Modifier.clickable { onPick(typed.trim()) })
                } })
            Spacer(Modifier.height(10.dp))
            FlowRow(Modifier.verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Reactions.all.forEach { e -> Text(e, style = Type.heading, modifier = Modifier.clip(CircleShape).clickable { onPick(e) }.padding(8.dp)) }
            }
        }
    }
}

/**
 * The composer: text (the system keyboard, emoji included), a quote or an edit above it, and a photo or file to attach.
 * [editing]: Save replaces his message's text; [replyTo]: the message sent quotes it.
 */
@Composable
internal fun Composer(room: Room, thread: String?, onSent: (String) -> Unit, replyTo: ChatMessage? = null, editing: ChatMessage? = null, onCancel: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Picked?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { u -> describe(context, u).let { p -> if (p.size > ChatFiles.MAX_BYTES) error = "That file is over 20 MB" else picked = p } }
    }
    LaunchedEffect(editing?.id) { editing?.let { text = it.text } }
    // His unsent words come back after a crash or an update (Drafts), and are kept as he types (not while editing a message).
    val draft = Drafts.key(room, thread)
    LaunchedEffect(draft) { if (text.isEmpty() && editing == null) text = withContext(Dispatchers.IO) { Drafts.read(context, draft) } }
    LaunchedEffect(text, draft) {
        if (editing != null) return@LaunchedEffect
        kotlinx.coroutines.delay(400)
        withContext(Dispatchers.IO) { Drafts.save(context, draft, text) }
    }
    // What Hermes just did on the phone instead of sending ("timer 10", "torch on"), with Undo where it has one.
    var done by remember { mutableStateOf<dev.apollo.hermes.Said?>(null) }
    Column(Modifier.padding(vertical = 10.dp)) {
        error?.let { Text(it, style = Type.small.copy(color = Tone.alarm)) }
        done?.let { d ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Done on the phone: ${d.text}", style = Type.small.copy(color = Tone.ink), modifier = Modifier.weight(1f))
                d.undo?.let { u -> Text("Undo", style = Type.small.copy(color = Tone.saffron, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.clickable { done = null; scope.launch(Dispatchers.IO) { runCatching { u(context) } } }.padding(start = 10.dp)) }
            }
        }
        val bar: (@Composable () -> Unit)? = when {
            editing != null -> { { Text("Editing your message", style = Type.small.copy(color = Tone.ink, fontWeight = FontWeight.SemiBold)) } }
            replyTo != null -> { { Quote(replyTo, false) {} } }
            else -> null
        }
        bar?.let {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).background(Tone.soft, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { it() }
                Text("Cancel", style = Type.small.copy(color = Tone.saffron, fontWeight = FontWeight.SemiBold), modifier = Modifier.clickable { onCancel(); if (editing != null) text = "" }.padding(start = 10.dp))
            }
        }
        picked?.let { p ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${p.name}  ${if (p.size >= 0) ChatFiles.size(p.size) else ""}", style = Type.small.copy(color = Tone.ink), modifier = Modifier.weight(1f), maxLines = 1)
                Text("Remove", style = Type.small.copy(color = Tone.saffron, fontWeight = FontWeight.SemiBold), modifier = Modifier.clickable { picked = null }.padding(start = 10.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (editing == null) Text("Attach", style = Type.small.copy(color = Tone.saffron, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { pick.launch("*/*") }.padding(vertical = 8.dp))
            BasicTextField(text, { text = it }, textStyle = Type.body, cursorBrush = SolidColor(Tone.saffron), maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.weight(1f).background(Tone.soft, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 13.dp),
                decorationBox = { inner -> if (text.isEmpty()) Text(room.hint, style = Type.body.copy(color = Tone.quiet)); inner() })
            Action(if (editing != null) "Save" else "Send", enabled = (text.isNotBlank() || (picked != null && editing == null)) && !sending) {
                val t = text.trim()
                val file = picked
                val quote = replyTo
                sending = true; error = null; done = null
                scope.launch {
                    // Hermes first, like the Ask sheet: a short command he typed runs on the phone. One it couldn't do
                    // (no such app, which Amma) goes to the agent as words.
                    if (editing == null && file == null && quote == null && dev.apollo.hermes.Commands.chatSized(t)) {
                        val said = withContext(Dispatchers.IO) { dev.apollo.hermes.Hermes.handle(context, t) }
                        if (said?.ok == true) { withContext(Dispatchers.IO) { Drafts.save(context, draft, "") }; sending = false; text = ""; done = said; return@launch }
                    }
                    val r = withContext(Dispatchers.IO) {
                        runCatching {
                            val server = Server(context)
                            val db = LocalDb.get(context)
                            if (editing != null) {
                                server.editMessage(editing.id, t)
                                db.editMessage(editing.id, t)
                                return@runCatching editing.thread
                            }
                            val up = file?.let { p ->
                                val bytes = context.contentResolver.openInputStream(p.uri)?.use { it.readBytes() } ?: throw IllegalStateException("can't read ${p.name}")
                                // A photo goes up shrunk and without its EXIF (location); the original stays here.
                                val (body, name, mime) = PhotoShrink.shrink(bytes, p.name, p.mime)
                                if (body.size > ChatFiles.MAX_BYTES) throw IllegalStateException("over 20 MB")
                                val id = server.upload(body, name, mime)
                                if (p.mime.startsWith("image/")) ChatFiles.keep(context, id, bytes)
                                ChatFile(id, name, mime, body.size.toLong())
                            }
                            val title = Chat.title(t, up?.image == true)
                            val (th, id) = server.chat(thread, title, t, Chat.to(room, thread), quote?.id, up?.id)
                            db.putMessage(ChatMessage(id, th, "owner", t, System.currentTimeMillis(), replyTo = quote?.id, file = up), title, room.wire, fresh = true)
                            // Sent: the draft goes now, not after the 400 ms wait (a new chat's composer is gone by then).
                            Drafts.save(context, draft, "")
                            th
                        }
                    }
                    sending = false
                    r.onSuccess { text = ""; picked = null; onCancel(); onSent(it) }.onFailure { error = "Not sent: ${it.message ?: it.javaClass.simpleName}" }
                }
            }
        }
    }
}
