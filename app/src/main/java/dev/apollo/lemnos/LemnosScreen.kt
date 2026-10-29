package dev.apollo.lemnos

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.apollo.calypso.AndroidBiometric
import dev.apollo.clio.Clio
import dev.apollo.ui.Action
import dev.apollo.ui.Row2
import dev.apollo.ui.Tone
import dev.apollo.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lemnos: the VPS. Opens with his fingerprint each visit (as Zeus did); Server, Files, Terminal, Desktop and CloudHost inside. */
@Composable
fun LemnosScreen() {
    val c = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var why by remember { mutableStateOf<String?>(null) }
    val unlock = { AndroidBiometric(c).authenticate("Open Lemnos", "Your server", null, { open = true }, { why = it }) }
    LaunchedEffect(Unit) { unlock() }
    if (!open) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Lemnos", style = Type.title)
            Text(why ?: "Your fingerprint opens it.", style = Type.small)
            Action("Unlock") { unlock() }
        }
        return
    }
    var tab by rememberSaveable { mutableStateOf("Server") }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("Lemnos", style = Type.title)
        Row(Modifier.padding(vertical = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Server", "Files", "Terminal", "Desktop", "CloudHost").forEach { t -> Chip(t, t == tab) { tab = t } }
        }
        when (tab) {
            "Server" -> ServerPane()
            "Files" -> FilesPane()
            "Terminal" -> TermTab()
            "Desktop" -> DesktopTab()
            else -> CloudHostPane()
        }
    }
}

@Composable
internal fun Chip(word: String, on: Boolean, onClick: () -> Unit) =
    Text(word, style = Type.small.copy(color = if (on) Tone.ground else Tone.ink, fontWeight = FontWeight.SemiBold), modifier = Modifier
        .background(if (on) Tone.ink else Tone.soft, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp))

private fun gb(b: Long) = "%.1f".format(b / 1e9)

/** His fingerprint, then [then]; a failed or cancelled prompt lands in [onFail]. */
private fun withFinger(c: android.content.Context, title: String, sub: String, onFail: (String) -> Unit, then: () -> Unit) =
    AndroidBiometric(c).authenticate(title, sub, null, then, onFail)

@Composable
private fun ServerPane() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var h by remember { mutableStateOf<Health?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var line by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) { runCatching { Lemnos(c).health() } }.onSuccess { h = it; err = null }.onFailure { err = it.message ?: it.javaClass.simpleName }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        err?.let { item { Text("Can't reach the server: $it", style = Type.small.copy(color = Tone.alarm)) } }
        h?.let { s ->
            item { Stat("Load", s.load.joinToString("  ") { "%.2f".format(it) }) }
            item { Stat("Memory", "${gb(s.memUsed)} of ${gb(s.memTotal)} GB") }
            item { Stat("Disk", "${gb(s.diskUsed)} of ${gb(s.diskTotal)} GB") }
            item { Stat("Up", "${s.uptime / 86_400} d ${s.uptime % 86_400 / 3600} h") }
            if (s.units.isNotEmpty()) item { Text("SERVICES", style = Type.kicker, modifier = Modifier.padding(top = 12.dp)) }
            items(s.units.toList()) { (u, state) ->
                Stat(u, state, bad = state != "active")
                if (u in s.control) Text("Restart", style = Type.small.copy(color = Tone.saffron, fontWeight = FontWeight.SemiBold), modifier = Modifier.clickable {
                    withFinger(c, "Restart $u", "On your server", { line = it }) {
                        scope.launch {
                            line = withContext(Dispatchers.IO) { runCatching { Lemnos(c).unit(u, "restart") }.fold({ "$u: $it" }, { "Restart failed: ${it.message}" }) }
                            Clio.info("lemnos", "restart", u)
                            tick++
                        }
                    }
                }.padding(bottom = 6.dp))
            }
        }
        line?.let { item { Text(it, style = Type.small) } }
        item { Spacer(Modifier.height(12.dp)); Action("Refresh", primary = false) { tick++ } }
    }
}

@Composable
private fun Stat(label: String, value: String, bad: Boolean = false) = Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
    Text(label, style = Type.body, modifier = Modifier.weight(1f))
    Text(value, style = Type.dots.copy(color = if (bad) Tone.alarm else Tone.ink))
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, mono: Boolean = false, single: Boolean = true) =
    BasicTextField(value, onChange, singleLine = single, cursorBrush = SolidColor(Tone.saffron),
        textStyle = if (mono) Type.small.copy(color = Tone.ink, fontFamily = FontFamily.Monospace, fontSize = 12.sp) else Type.body,
        modifier = modifier.background(Tone.raised, RoundedCornerShape(12.dp)).padding(10.dp))

private fun picked(c: android.content.Context, uri: Uri): Pair<String, ByteArray> {
    val name = c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "upload"
    val bytes = c.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
    return name to bytes
}

@Composable
private fun FilesPane() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var root by rememberSaveable { mutableStateOf<String?>(null) }
    var path by rememberSaveable { mutableStateOf("") }
    var file by rememberSaveable { mutableStateOf<String?>(null) }
    var roots by remember { mutableStateOf(emptyList<String>()) }
    var entries by remember { mutableStateOf(emptyList<Entry>()) }
    var text by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    /** The file open, or else the folder open: what Rename and Delete act on. */
    val target = file ?: path
    fun act(word: String, block: () -> Unit, after: () -> Unit = {}) = scope.launch {
        err = withContext(Dispatchers.IO) { runCatching(block).exceptionOrNull()?.let { "$word failed: ${it.message}" } }
        if (err == null) { Clio.info("lemnos", "files", word); after(); tick++ }
    }
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val r = root ?: return@rememberLauncherForActivityResult
        uri?.let { u -> act("Upload", { val (name, bytes) = picked(c, u); Lemnos(c).put(r, if (path.isEmpty()) name else "$path/$name", bytes) }) }
    }
    BackHandler(root != null) {
        when {
            editing != null -> editing = null
            renaming != null -> renaming = null
            naming != null -> naming = null
            file != null -> file = null
            path.isNotEmpty() -> path = path.substringBeforeLast('/', "")
            else -> root = null
        }
    }
    LaunchedEffect(root, path, file, tick) {
        err = null
        withContext(Dispatchers.IO) {
            runCatching {
                val l = Lemnos(c)
                when {
                    root == null -> roots = l.roots()
                    file != null -> text = l.text(root!!, file!!)
                    else -> entries = l.list(root!!, path)
                }
            }.onFailure { err = it.message ?: it.javaClass.simpleName }
        }
    }
    Column(Modifier.imePadding()) {
        Text(listOfNotNull(root, path.ifEmpty { null }, file?.substringAfterLast('/')).joinToString(" / ").ifEmpty { "The workspace and the mini apps" }, style = Type.small)
        err?.let { Text(it, style = Type.small.copy(color = Tone.alarm)) }
        if (root != null && target.isNotEmpty()) {
            renaming?.let { name ->
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(name, { renaming = it }, Modifier.weight(1f))
                    Action("Save") {
                        val r = root!!; val t = target
                        act("Rename", { Lemnos(c).rename(r, t, name.trim()) }) {
                            val moved = t.substringBeforeLast('/', "").let { if (it.isEmpty()) name.trim() else "$it/${name.trim()}" }
                            if (file != null) file = moved else path = moved
                            renaming = null
                        }
                    }
                }
            }
            if (renaming == null && editing == null) Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (file != null && text != null) Action("Edit", primary = false) { editing = text }
                Action("Rename", primary = false) { renaming = target.substringAfterLast('/') }
                Action("Delete", primary = false) {
                    val r = root!!; val t = target
                    withFinger(c, "Delete ${t.substringAfterLast('/')}", "No undo: it's gone from the server", { err = it }) {
                        act("Delete", { Lemnos(c).delete(r, t) }) { if (file != null) file = null else path = path.substringBeforeLast('/', "") }
                    }
                }
            }
        }
        when {
            root == null -> LazyColumn { items(roots) { r -> Row2(r, null, "Open") { root = r; path = "" } } }
            editing != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action("Save") { val r = root!!; val f = file!!; val t = editing!!; act("Save", { Lemnos(c).put(r, f, t.toByteArray(), replace = true) }) { editing = null } }
                    Action("Cancel", primary = false) { editing = null }
                }
                Field(editing!!, { editing = it }, Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), mono = true, single = false)
            }
            file != null -> SelectionContainer {
                Text(text.orEmpty(), style = Type.small.copy(color = Tone.ink, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    modifier = Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(vertical = 8.dp))
            }
            else -> LazyColumn {
                item {
                    val name = naming
                    if (name == null) Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Action("Upload here", primary = false) { upload.launch("*/*") }
                        Action("New folder", primary = false) { naming = "" }
                    } else Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field(name, { naming = it }, Modifier.weight(1f))
                        Action("Create", enabled = name.isNotBlank()) {
                            val r = root!!; val n = name.trim()
                            act("New folder", { Lemnos(c).newFolder(r, if (path.isEmpty()) n else "$path/$n") }) { naming = null }
                        }
                        Action("Cancel", primary = false) { naming = null }
                    }
                }
                items(entries, key = { it.name }) { e ->
                    Row2(e.name, if (e.dir) "Folder" else "${e.size} bytes", null) {
                        val p = if (path.isEmpty()) e.name else "$path/${e.name}"
                        if (e.dir) path = p else { text = null; file = p }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.TermTab() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf(emptyList<Session>()) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var pass by remember { mutableStateOf<Map<String, String>?>(null) }
    var line by remember { mutableStateOf<String?>(null) }
    var reset by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(tick, open) {
        if (open == null) withContext(Dispatchers.IO) { runCatching { Lemnos(c).sessions() } }.onSuccess { sessions = it; line = null }.onFailure { line = it.message }
    }
    // The server checks his fingerprint too: each terminal, new shell and close is signed by the terminal key (TermKey).
    fun keyed(go: suspend (TermKey) -> Unit) = scope.launch {
        try {
            go(TermKey(c))
            reset = false
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            reset = e is TermKey.NeedsReset
            line = e.message ?: e.javaClass.simpleName
        }
    }
    fun signed(what: String, title: String, then: suspend (Proof) -> Unit) = keyed { val p = it.prove(what, title); line = null; then(p) }
    val name = open
    if (name != null) {
        BackHandler { open = null }
        var status by remember(name) { mutableStateOf("Connecting") }
        val link = rememberTermLink(c, name, false, pass) { status = it }
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$name  ${line ?: status}", style = Type.small, modifier = Modifier.weight(1f))
            Chip("Reconnect", false) { line = null; signed("term:$name", "Reconnect $name") { link.reconnect(it.query) } }
            Chip("Done", false) { open = null }
        }
        TerminalPane(link, Modifier.weight(1f).fillMaxWidth())
        KeysRow(link)
        return
    }
    LazyColumn(Modifier.weight(1f)) {
        item { Text("tmux sessions on the server. Your fingerprint opens one. Iris's session is never offered.", style = Type.small) }
        line?.let { item { Text(it, style = Type.small.copy(color = Tone.alarm)) } }
        if (reset) item {
            Row(Modifier.padding(vertical = 6.dp)) {
                Action("Make a new key", primary = false) { keyed { it.renew() } }
            }
        }
        items(sessions, key = { it.name }) { s ->
            Row2(s.name, if (s.attached > 0) "Attached elsewhere too" else null, "Open") {
                signed("term:${s.name}", "Open ${s.name}") { p -> pass = p.query; open = s.name; Clio.info("lemnos", "terminal", s.name) }
            }
            if (s.name.startsWith("shell-")) Text("Close shell", style = Type.small.copy(color = Tone.saffron, fontWeight = FontWeight.SemiBold), modifier = Modifier.clickable {
                signed("kill:${s.name}", "Close ${s.name}") { p -> line = withContext(Dispatchers.IO) { runCatching { Lemnos(c).closeShell(s.name, p) }.exceptionOrNull()?.message }; tick++ }
            }.padding(bottom = 6.dp))
        }
        item {
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("New shell") {
                    signed("new-shell", "New shell") { p -> withContext(Dispatchers.IO) { Lemnos(c).newShell(p) }.let { (s, t) -> pass = t; open = s } }
                }
                Action("Refresh", primary = false) { tick++ }
            }
        }
    }
}

/** The keys a soft keyboard lacks. Modifiers are sticky: they hold for the next key. */
private val DESK_KEYS: List<Pair<String, Int>> = listOf(
    "Esc" to Keysym.ESCAPE, "Tab" to Keysym.TAB, "Ctrl" to Keysym.CONTROL_L, "Alt" to Keysym.ALT_L, "Super" to Keysym.SUPER_L,
    "Left" to Keysym.LEFT, "Up" to Keysym.UP, "Down" to Keysym.DOWN, "Right" to Keysym.RIGHT, "Home" to Keysym.HOME, "End" to Keysym.END,
    "PgUp" to Keysym.PAGE_UP, "PgDn" to Keysym.PAGE_DOWN, "Del" to Keysym.DELETE, "Enter" to Keysym.RETURN,
) + (1..12).map { "F$it" to Keysym.f(it) }

/**
 * Zeus's Desktop tab, smaller: start/stop the desktop unit, then the picture. Unfolded it's a mouse and keyboard
 * (tap = click, hold = right click, hold then move = drag, two fingers = scroll or zoom); on the cover, pan and zoom only.
 */
@Composable
private fun ColumnScope.DesktopTab() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    var state by remember { mutableStateOf<DesktopState?>(null) }
    var line by remember { mutableStateOf("") }
    var clip by remember { mutableStateOf<String?>(null) }
    var want by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val sticky = remember { mutableStateListOf<Int>() }
    val link = remember { DesktopLink(c, { line = it }, { clip = it }) }
    var view by remember { mutableStateOf<DesktopView?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) { runCatching { Lemnos(c).desktop() } }.onSuccess { state = it }.onFailure { line = it.message ?: "Can't reach the server" }
            delay(if (state?.state == "active") 5_000 else 15_000)
        }
    }
    LaunchedEffect(state?.ready, want) { if (state?.ready == true && want) link.open() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { link.close() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { if (want && state?.ready == true) link.open() }
    DisposableEffect(link) { onDispose { link.close() } }
    fun act(action: String) = scope.launch {
        busy = true
        if (action == "stop") { want = false; link.close() }
        withContext(Dispatchers.IO) { runCatching { Lemnos(c).desktop(action) } }.onSuccess { state = it; if (action == "start") want = true }.onFailure { line = "Couldn't $action: ${it.message}" }
        busy = false
    }
    val s = state
    if (s?.state != "active") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("An XFCE desktop on the server, as its own user. It can't see the agents' files and has no open port. It stops itself 15 min after you leave and never takes more than 1 GB.", style = Type.small)
            if (line.isNotBlank()) Text(line, style = Type.small.copy(color = Tone.alarm))
            Action(if (busy) "Starting" else "Start the desktop", enabled = !busy && s != null) { act("start") }
        }
        return
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (line != "Live") Chip("Connect", true) { want = true; link.open() }
        Chip("Whole desktop", false) { view?.fit() }
        if (wide) {
            Chip("Keyboard", false) { view?.showKeyboard(true) }
            Chip("Fit to screen", false) { view?.let { v -> val (w, h) = v.idealSize(); if (link.rfb?.resizeDesktop(w, h) != true) line = "This desktop can't change size" } }
            Chip("Paste", false) {
                val t = c.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(c)?.toString()
                if (t.isNullOrEmpty()) line = "Nothing on the phone's clipboard" else { link.paste(t); line = "Clipboard sent: paste with Ctrl+V on the desktop" }
            }
        }
        clip?.let { t -> Chip("Copy desktop text", true) { c.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Lemnos desktop", t)); clip = null } }
        Chip("Stop", false) { act("stop") }
    }
    Text(line.ifBlank { if (wide) "Mouse and keyboard" else "View only on the cover: unfold for the mouse and keyboard" }, style = Type.small, maxLines = 2)
    AndroidView(
        factory = { ctx -> DesktopView(ctx, link).also { v -> link.view = v; view = v; v.onStickyCleared = { sticky.clear() } } },
        update = { v -> v.interactive = wide; link.view = v; view = v },
        modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp),
    )
    if (wide) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((label, ks) in DESK_KEYS) Chip(label, ks in sticky) {
            if (ks in DesktopView.MODIFIERS) { if (ks in sticky) { sticky.remove(ks); link.sticky.remove(ks) } else { sticky.add(ks); link.sticky.add(ks) } }
            else link.tap(ks)
        }
    }
}

@Composable
private fun CloudHostPane() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var box by remember { mutableStateOf<CloudHost.Box?>(null) }
    var line by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) {
            val t = CloudHost.token(c) ?: return@withContext run { line = "No CloudHost token in Calypso's keys (slot cloudhost)." }
            runCatching { CloudHost.box(t) }.onSuccess { box = it; if (it == null) line = "No server in that project." }.onFailure { line = it.message }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        box?.let { b ->
            Stat("Server", b.name)
            Stat("Status", b.status, bad = b.status != "running")
            Stat("Type", b.type)
            Text("Reboot and power cycle need your fingerprint.", style = Type.small, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CloudHost.Act.entries.forEach { a ->
                    Action(a.label, primary = false) {
                        AndroidBiometric(c).authenticate("${a.label} the server", b.name, null, {
                            scope.launch {
                                line = withContext(Dispatchers.IO) { runCatching { CloudHost.act(CloudHost.token(c)!!, b.id, a) }.fold({ "${a.label}: $it" }, { "${a.label} failed: ${it.message}" }) }
                                Clio.info("lemnos", "cloudhost", a.path)
                                tick++
                            }
                        }, { line = it })
                    }
                }
            }
        }
        line?.let { Text(it, style = Type.small) }
        Action("Refresh", primary = false) { tick++ }
    }
}
