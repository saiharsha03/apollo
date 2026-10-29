package dev.apollo.apps

import android.net.Uri
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import dev.apollo.home.Pin
import dev.apollo.ui.Tone
import dev.apollo.ui.Type

/**
 * Athena's mini apps: the ones she marked as sections first, then the rest. Tapping one opens its home page. On top one
 * search over the apps and their pages, More's rows and places; under the list Pin where I am and the archived apps.
 * [initial]: an app ("money") or one of its pages ("money/pages/week.html"); [go]: a nav sub or place:<PLACE>.
 */
@Composable
fun AppsScreen(initial: String? = null, go: (String) -> Unit = {}) {
    val c = LocalContext.current
    var open by rememberSaveable { mutableStateOf(initial) }
    var query by rememberSaveable { mutableStateOf("") }
    var archivedAt by remember { mutableStateOf(0) }
    val apps = remember(open, archivedAt) { MiniApps.all(c) }
    open?.let { target ->
        // An app opened from elsewhere (Home, More, the nav) is a spot of its own: Back there is MainActivity's.
        BackHandler(open != initial) { open = initial }
        val name = target.substringBefore('/')
        val app = apps.firstOrNull { it.name == name }
        // Only a page the app lists: the target can come from outside (a widget's or notification's open_app).
        val page = app?.let { a -> target.substringAfter('/', "").takeIf { it in a.pages } ?: MiniApps.home(c, a) }
        if (app == null || page == null) Column(Modifier.padding(20.dp)) { Text("Nothing to show in $name yet.", style = Type.body) }
        else {
            if (app.secure) dev.apollo.calypso.SecureWindow()
            AppPage(app.name, page)
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("Apps", style = Type.title)
        Text("Athena's mini apps. She builds and reshapes them; you can ask her to change any. Press and hold one to pin it on Home.", style = Type.small)
        Spacer(Modifier.height(12.dp))
        SearchBox(query) { query = it }
        Spacer(Modifier.height(8.dp))
        LazyColumn(state = dev.apollo.rememberBackList("apps")) {
            if (query.isNotBlank()) {
                item {
                    SearchResults(query, apps) { to ->
                        query = ""
                        when {
                            to.startsWith("app:") -> open = to.removePrefix("app:")
                            to.startsWith("geo:") -> openPlace(c, to)
                            else -> go(to)
                        }
                    }
                }
                return@LazyColumn
            }
            if (apps.isEmpty()) item { Text("None yet. They arrive with the next sync.", style = Type.small) }
            items(apps.sortedByDescending { it.section }, key = { it.name }) { a -> AppRow(a, { open = a.name }) { archivedAt++ } }
            item { PinHere() }
            item(key = "archived" + archivedAt) { Archived { archivedAt++ } }
        }
    }
}

/** One app in the list: tap opens it; press and hold for pinning it (or one of its widgets) on Home. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(a: MiniApp, onOpen: () -> Unit, archived: () -> Unit) {
    val c = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var killing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val tick by dev.apollo.home.HomePins.changed.collectAsState()
    val raw = remember(tick, menu) { dev.apollo.home.HomePins.read(c) }
    fun pinned(p: Pin) = dev.apollo.home.HomePins.has(raw, p)
    fun toggle(p: Pin) { dev.apollo.home.HomePins.set(c, dev.apollo.home.HomePins.toggle(raw, p)); menu = false }
    Box {
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = { menu = true }).padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(a.title, style = Type.body.copy(fontWeight = FontWeight.SemiBold))
                a.about.ifBlank { null }?.let { Text(it, style = Type.small) }
            }
            Text(if (pinned(Pin(a.name)) || a.widgets.any { pinned(Pin(a.name, it)) }) "On Home" else "Open", style = Type.small.copy(color = Tone.saffron, fontWeight = FontWeight.SemiBold))
        }
        DropdownMenu(menu, { menu = false }, containerColor = Tone.raised) {
            val app = Pin(a.name)
            DropdownMenuItem({ Text(if (pinned(app)) "Unpin from Home" else "Pin to Home", style = Type.body) }, { toggle(app) })
            a.widgets.forEach { w ->
                val p = Pin(a.name, w)
                val word = w.substringAfterLast('/').substringBeforeLast('.')
                DropdownMenuItem({ Text(if (pinned(p)) "Unpin widget $word" else "Pin widget $word to Home", style = Type.body) }, { toggle(p) })
            }
            DropdownMenuItem({ Text("Archive", style = Type.body.copy(color = Tone.alarm)) }, { menu = false; killing = true })
        }
    }
    // His one-tap kill: it moves to the archive (Restore under the list brings it back as it was).
    if (killing) androidx.compose.material3.AlertDialog(onDismissRequest = { killing = false }, containerColor = Tone.raised,
        title = { Text("Archive ${a.title}?", style = Type.heading) },
        text = { Text("It leaves Apps and the nav. Restore under Archived brings it back as it was.", style = Type.body) },
        confirmButton = {
            androidx.compose.material3.TextButton({
                killing = false
                scope.launch {
                    withContext(Dispatchers.IO) { appCall(c, a.name, "archive"); runCatching { dev.apollo.net.Sync.run(c) } }
                    archived()
                }
            }) { Text("Archive", style = Type.body.copy(color = Tone.alarm)) }
        },
        dismissButton = { androidx.compose.material3.TextButton({ killing = false }) { Text("Cancel", style = Type.body.copy(color = Tone.quiet)) } })
}

/**
 * One page of a mini app, full screen, in the engine's WebView. Back goes back inside the page first. It also runs what a
 * page can only ask for: the camera or photo picker (PhotoAsk), his "Just for me" page over it (PrivateNote), and Dawn's
 * Allow (senses/AllowLayer).
 */
@Composable
fun AppPage(app: String, path: String) {
    val c = LocalContext.current
    var web by remember { mutableStateOf<WebView?>(null) }
    val privateFor by PrivateNote.open.collectAsState()
    val ask by PhotoAsk.current.collectAsState()
    val scope = rememberCoroutineScope()
    var shot by remember { mutableStateOf<Uri?>(null) }
    fun kept(uri: Uri?) {
        val a = ask ?: return
        PhotoAsk.current.value = null
        val taken = shot
        scope.launch(Dispatchers.IO) { PhotoAsk.done(c, a, uri); taken?.let { runCatching { c.contentResolver.delete(it, null, null) } } }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> kept(uri) }
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> kept(if (ok) shot else null) }
    LaunchedEffect(ask) {
        val a = ask ?: return@LaunchedEffect
        if (a.camera) {
            val f = java.io.File(c.cacheDir, "camera/shot.jpg").apply { parentFile?.mkdirs(); delete() }
            shot = FileProvider.getUriForFile(c, "${c.packageName}.photos", f)
            take.launch(shot!!)
        } else { shot = null; pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    }
    if (app == "closet") LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { ClosetImport.runOnce(c) } } }
    BackHandler(privateFor == null && web?.canGoBack() == true) { web?.goBack() }
    // Nothing records or plays after he leaves the page.
    // Back to this page: the page he was on inside the app, scrolled where he left it.
    // Only for the app as its own spot (opened from Home, More, the nav); inside Apps a reopened app starts at its home.
    val spot = dev.apollo.LocalSpot.current
    val own = dev.apollo.Spot.of(spot)?.sub == "app:$app"
    val saved = dev.apollo.backMark("web:$app").takeIf { own }
    DisposableEffect(app, path) { onDispose { if (own) web?.let { dev.apollo.ScrollBook.main.leave(spot, "web:$app", dev.apollo.Mark(it.scrollY, url = it.url)) }; AppVoice.stopIfApp(app); VoiceAsk.clear(app); dev.apollo.senses.Allow.clear(app); AppVoice.stopPlay(); PrivateNote.open.value = null; web?.destroy() } }
    Box(Modifier.fillMaxSize()) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
            WebView(ctx).also { w ->
                AppWeb.configure(w, c, voice = true)
                w.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                w.loadUrl(saved?.url?.takeIf { it.startsWith(AppWeb.url(app, "")) } ?: AppWeb.url(app, path))
                saved?.a?.takeIf { it > 0 }?.let { y -> for (ms in longArrayOf(400, 1200)) w.postDelayed({ w.scrollTo(0, y) }, ms) }
                web = w
            }
        })
        VoiceAskLayer(app)
        dev.apollo.senses.AllowLayer(app)
        if (privateFor != null) PrivateNoteScreen { PrivateNote.open.value = null }
    }
}
