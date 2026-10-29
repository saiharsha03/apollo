package dev.apollo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.apollo.approvals.ApprovalsScreen
import dev.apollo.calypso.CalypsoScreen
import dev.apollo.chat.ChatScreen
import dev.apollo.chat.Room
import dev.apollo.home.HomeScreen
import dev.apollo.net.Facts
import dev.apollo.net.Sync
import dev.apollo.senses.SensesScreen
import dev.apollo.ui.Agent
import dev.apollo.ui.ApolloTheme
import dev.apollo.ui.Island
import dev.apollo.ui.Row2
import dev.apollo.ui.Tone
import dev.apollo.ui.Type
import dev.apollo.ui.glass
import dev.apollo.ui.rememberDoing
import dev.apollo.ui.rememberIrisDoing
import dev.apollo.ui.rememberIsland
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** The places in the nav. Iris has her own; Atlas lives under More; Calypso is reached from Home. */
enum class Place(val word: String) { HOME("Home"), CHAT("Chat"), IRIS("Iris"), APPS("Apps"), MORE("More") }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark only: light bar icons whatever the phone's mode (ApolloTheme keeps them light).
        enableEdgeToEdge(androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        // Google Home's consent screen is an activity result: registered before the activity starts.
        runCatching { dev.apollo.house.House.attach(this) }
        dev.apollo.chat.ThreadLink.take(intent)
        setContent {
            ApolloTheme {
                var place by rememberSaveable { mutableStateOf(Place.HOME) }
                // Athena's chat or the group, inside Chat.
                var room by rememberSaveable { mutableStateOf(Room.ATHENA) }
                // Calypso, Senses and Approvals open over a place; Back closes them first.
                // A widget tap opens its app: open_app names it.
                var sub by rememberSaveable { mutableStateOf(intent?.getStringExtra("open_app")?.let { "app:$it" } ?: "") }
                // The thread open in the chat showing, and where Back goes (BackStack).
                var thread by rememberSaveable { mutableStateOf<String?>(null) }
                var stack by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.listSaver<List<Spot>, String>({ l -> l.map { it.wire } }, { l -> l.mapNotNull(Spot::of) })) { mutableStateOf(emptyList()) }
                val here = Spot(place, sub, room, thread)
                // Where each screen was scrolled is kept only for the spots still behind him (and the one showing).
                val show: (Spot) -> Unit = { s -> place = s.place; sub = s.sub; room = s.room; thread = s.thread; ScrollBook.main.live(stack + s) }
                // Going somewhere from here (a More row, a mini app, a thread): Back returns here.
                val to: (Spot) -> Unit = { s -> stack = BackStack.push(stack, here, s); show(s) }
                val back: () -> Unit = { BackStack.pop(stack, here)?.let { (s, rest) -> stack = rest; show(s) } }
                val over = sub.isNotEmpty()
                // thread:<id> (an alert's Open, from a card or a notification): to the place its room lives; Chat opens it.
                val want by dev.apollo.chat.ThreadLink.wanted.collectAsState()
                val ctx = LocalContext.current
                LaunchedEffect(want) {
                    val id = want ?: return@LaunchedEffect
                    val t = withContext(Dispatchers.IO) { dev.apollo.chat.ThreadLink.known(ctx, id) }
                    if (t == null) { dev.apollo.chat.ThreadLink.wanted.value = null; return@LaunchedEffect }
                    dev.apollo.chat.ThreadLink.wanted.value = null
                    when (val r = dev.apollo.chat.ThreadLink.room(t.agent)) {
                        Room.IRIS -> to(Spot(Place.IRIS, "", room, id))
                        Room.ATLAS -> to(Spot(place, "atlas", room, id))
                        else -> to(Spot(Place.CHAT, "", r, id))
                    }
                }
                BackHandler(stack.isNotEmpty() || !here.home) { back() }
                val inner = LocalConfiguration.current.screenWidthDp >= 600
                val doing = rememberDoing()
                val island = rememberIsland("athena")
                dev.apollo.pixel.PixelFeed.Poll()
                val bars = WindowInsets.systemBars.asPaddingValues()
                val top = bars.calculateTopPadding() + 60.dp
                val bottom = bars.calculateBottomPadding() + if (inner) 16.dp else 88.dp
                val home = place == Place.HOME && !over

                val chat: @Composable () -> Unit = {
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(Room.ATHENA, Room.IRIS, Room.GROUP).forEach { r -> Tab(r.word, r == room, dev.apollo.chat.rememberUnread(r.wire)) { show(here.copy(room = r, thread = null)) } }
                        }
                        ChatScreen(room, thread, { to(here.copy(thread = it)) }, back)
                    }
                }
                // A nav tab starts over; the same entry reached from More or Apps is a step Back undoes.
                val spot: (NavEntry) -> Spot = { e -> if (e.place != null) Spot(e.place, "", room) else Spot(place, e.sub.orEmpty(), room) }
                val go: (NavEntry) -> Unit = { e -> stack = emptyList(); show(spot(e)) }
                val open: (String) -> Unit = { s -> to(Spot(place, s, room)) }
                val page: @Composable (Boolean) -> Unit = { homeHere ->
                    when {
                        sub == "calypso" -> CalypsoScreen()
                        sub == "senses" -> SensesScreen()
                        sub == "approvals" -> ApprovalsScreen()
                        // Keyed: one app's saved state (its open page) must not carry over to the next app tapped on the nav.
                        sub.startsWith("app:") -> androidx.compose.runtime.key(sub) { dev.apollo.apps.AppsScreen(sub.removePrefix("app:")) }
                        sub == "pair" -> dev.apollo.net.PairScreen()
                        sub == "house" -> dev.apollo.house.HouseScreen()
                        sub == "atlas" -> ChatScreen(Room.ATLAS, thread, { to(here.copy(thread = it)) }, back)
                        sub == "lemnos" -> dev.apollo.lemnos.LemnosScreen()
                        sub == "logs" -> dev.apollo.clio.ClioScreen()
                        sub == "backup" -> dev.apollo.mnemosyne.MnemosyneScreen()
                        sub == "updates" -> dev.apollo.prometheus.UpdateScreen()
                        sub == "pan" -> dev.apollo.pan.PanScreen(open = { to(Spot(place, it, room)) })
                        sub == "phone" -> dev.apollo.actions.PhoneScreen()
                        sub == "netlog" -> dev.apollo.net.NetLogScreen()
                        place == Place.CHAT -> chat()
                        place == Place.IRIS -> ChatScreen(Room.IRIS, thread, { to(here.copy(thread = it)) }, back)
                        place == Place.APPS -> dev.apollo.apps.AppsScreen(go = { s -> Place.entries.firstOrNull { "place:" + it.name == s }?.let { to(Spot(it, "", room)) } ?: open(s) })
                        place == Place.MORE -> Column(Modifier.verticalScroll(rememberBackScroll("more")).padding(horizontal = 20.dp, vertical = 8.dp)) {
                            Text("More", style = Type.title)
                            // What his nav order leaves off the bar (mini apps past the cap, places he left out).
                            navOrder(LocalContext.current).second.forEach { e -> Row2(e.word, if (e.app != null) "A mini app on your nav" else null, "Open") { to(spot(e)) } }
                            Nav.MORE_ROWS.forEach { (word, line, s) -> Row2(word, line, "Open") { open(s) } }
                        }
                        homeHere -> HomeScreen(doing = doing, top = top, bottom = bottom, open = { open("app:$it") })
                        else -> chat()
                    }
                }

                androidx.compose.runtime.CompositionLocalProvider(LocalSpot provides here.wire) {
                Box(Modifier.fillMaxSize().background(Tone.ground)) {
                    if (inner) Row(Modifier.fillMaxSize()) {
                        // Unfolded: Home always on the left with the rail over its sky, the chosen place (Athena's chat by default) beside it.
                        val chatBeside = sub == "atlas" || (sub.isEmpty() && place in setOf(Place.CHAT, Place.IRIS, Place.HOME))
                        // Clipped: the sky's clouds drift past its edge otherwise, over the right pane.
                        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                            HomeScreen(doing = doing, top = top, bottom = bottom, start = 112.dp, scene = !chatBeside, open = { open("app:$it") })
                            Index(place, sub, go, Modifier.align(Alignment.CenterStart))
                        }
                        Column(Modifier.weight(1f).fillMaxHeight().padding(top = top, bottom = bars.calculateBottomPadding())) {
                            Tabs(place, sub, go, Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp))
                            Box(Modifier.weight(1f)) { page(false) }
                        }
                    } else {
                        Box(Modifier.fillMaxSize().then(if (home) Modifier else Modifier.padding(top = top, bottom = bottom))) { page(true) }
                        Dock(place, sub, home, go, Modifier.align(Alignment.BottomCenter).padding(bottom = bars.calculateBottomPadding() + 12.dp))
                    }
                    // On Iris's own place the island is hers: her cloud and what she's doing, unless something more pressing is up.
                    val irisHere = place == Place.IRIS && !over
                    val irisDoing = rememberIrisDoing()
                    val irisIsland = rememberIsland("iris")
                    if (irisHere) Island(irisIsland, irisDoing,
                        Modifier.align(Alignment.TopCenter).padding(top = bars.calculateTopPadding() + 4.dp, start = 16.dp, end = 16.dp), Agent.IRIS)
                    else Island(island, doing, Modifier.align(Alignment.TopCenter).padding(top = bars.calculateTopPadding() + 4.dp, start = 16.dp, end = 16.dp))
                }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        dev.apollo.chat.ThreadLink.take(intent)
    }

    override fun onStart() {
        super.onStart()
        // Opening Apollo pulls what's waiting and sends what's queued; a call put off "unless seen" now won't ring.
        dev.apollo.ossa.Seen.mark(this)
        // Thread kinds from the server first, so a system thread never counts as unread.
        lifecycleScope.launch(Dispatchers.IO) { runCatching { dev.apollo.chat.Chat.pullThreads(applicationContext) }; Sync.now(applicationContext); Facts.uploadNow(applicationContext); dev.apollo.net.ZeusApps.upload(applicationContext) }
    }
}

/** The dock: a glass pill floating over the sky on Home, a plain raised pill over the other screens. */
@Composable
private fun Dock(place: Place, sub: String, onSky: Boolean, go: (NavEntry) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier.then(if (onSky) Modifier.glass(shape, strong = true) else Modifier.background(Tone.raised, shape).border(1.dp, Tone.line, shape)).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        navOrder(LocalContext.current).first.forEach { e -> NavItem(e.word, lit(e, place, sub), onSky, unread(e)) { go(e) } }
    }
}

/** Unfolded, Home is always showing on the left, so the places are the rest; Home itself shows Athena's chat beside. */
private fun beside(place: Place) = if (place == Place.HOME) Place.CHAT else place

/** A mini app is lit while it's open; a place while nothing is open over it. */
private fun lit(e: NavEntry, place: Place, sub: String) = if (e.app != null) sub == e.sub else e.place == place && sub.isEmpty()

/** Unfolded, on Home's sky: the places as plain words at the left edge, the open one bright with a thin bar. */
@Composable
private fun Index(place: Place, sub: String, go: (NavEntry) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        navOrder(LocalContext.current).first.filter { it.place != Place.HOME }.forEach { p ->
            val on = lit(p, beside(place), sub)
            Box(Modifier.height(IntrinsicSize.Min).clickable { go(p) }) {
                if (on) Box(Modifier.width(3.dp).fillMaxHeight().padding(vertical = 8.dp).background(Tone.onSky, RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp)))
                Row(Modifier.padding(start = 22.dp, end = 12.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.word, maxLines = 1, style = Type.body.copy(color = if (on) Tone.onSky else Tone.onSky.copy(alpha = .62f), fontWeight = FontWeight.SemiBold))
                    UnreadDot(unread(p), Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

/** Unfolded, on top of the right pane: the same places as one segmented row, the page under it. */
@Composable
private fun Tabs(place: Place, sub: String, go: (NavEntry) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Row(modifier.fillMaxWidth().background(Tone.raised, shape).border(1.dp, Tone.line, shape).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        navOrder(LocalContext.current).first.filter { it.place != Place.HOME }.forEach { p ->
            val on = lit(p, beside(place), sub)
            Row(Modifier.weight(1f).background(if (on) Tone.soft else Color.Transparent, RoundedCornerShape(10.dp)).clickable { go(p) }.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text(p.word, maxLines = 1, style = Type.small.copy(color = if (on) Tone.ink else Tone.quiet, fontWeight = FontWeight.SemiBold))
                UnreadDot(unread(p), Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun UnreadDot(n: Int, modifier: Modifier = Modifier) {
    if (n > 0) Box(modifier.size(8.dp).background(Tone.alarm, CircleShape))
}

/** His nav (phone_setting nav_order "chat,app:money,iris,more", kept in prefs apollo_ui): the bar, then what's under More (Nav). */
fun navOrder(c: android.content.Context): Pair<List<NavEntry>, List<NavEntry>> = Nav.order(
    c.getSharedPreferences("apollo_ui", android.content.Context.MODE_PRIVATE).getString("nav_order", null),
    dev.apollo.apps.MiniApps.all(c).associate { it.name to it.title })

/** Unread agents' messages behind a place: Chat holds Athena's and the group, Iris hers, More has Atlas. */
@Composable
private fun unread(e: NavEntry): Int = when (e.place) {
    Place.CHAT -> dev.apollo.chat.rememberUnread("athena", "group")
    Place.IRIS -> dev.apollo.chat.rememberUnread("iris")
    Place.MORE -> dev.apollo.chat.rememberUnread("atlas")
    else -> 0
}

@Composable
private fun NavItem(word: String, selected: Boolean, onSky: Boolean, badge: Int = 0, onClick: () -> Unit) {
    val fg = when { selected -> Tone.ground; onSky -> Tone.onSky; else -> Tone.quiet }
    val bg = if (selected) Tone.ink else Color.Transparent
    Box {
        Text(word, maxLines = 1, style = Type.small.copy(color = fg, fontWeight = FontWeight.SemiBold), modifier = Modifier
            .background(bg, RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp))
        dev.apollo.chat.UnreadBadge(badge, Modifier.align(Alignment.TopEnd))
    }
}

/** Athena / Everyone at the top of Chat. */
@Composable
private fun Tab(word: String, selected: Boolean, badge: Int = 0, onClick: () -> Unit) = Box {
    Text(word, style = Type.small.copy(color = if (selected) Tone.ground else Tone.ink, fontWeight = FontWeight.SemiBold), modifier = Modifier
        .background(if (selected) Tone.ink else Tone.soft, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp))
    dev.apollo.chat.UnreadBadge(badge, Modifier.align(Alignment.TopEnd))
}
