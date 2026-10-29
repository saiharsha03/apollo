package dev.apollo.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.webkit.WebView
import androidx.compose.foundation.clickable
import androidx.compose.ui.viewinterop.AndroidView
import dev.apollo.actions.PhoneActions
import dev.apollo.apps.AppWeb
import dev.apollo.apps.MiniApp
import dev.apollo.apps.MiniApps
import dev.apollo.net.Card
import dev.apollo.net.CardButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import dev.apollo.net.Facts
import dev.apollo.net.LocalDb
import dev.apollo.ui.AskCard
import dev.apollo.ui.Choice
import dev.apollo.ui.Doing
import dev.apollo.ui.Sky
import dev.apollo.ui.Sun
import dev.apollo.ui.Tone
import dev.apollo.ui.Type
import dev.apollo.ui.Weather
import dev.apollo.ui.glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** What a gesture or chip on a card tells Athena (a `card_feedback` fact). Pure. */
object CardFeedback {
    const val SEEN = "seen"
    const val LESS = "less like this"

    fun data(card: Card, what: String): JSONObject = JSONObject().put("card", card.id).put("title", card.title).put("what", what).apply { card.app?.let { put("app", it) } }
}

/** A card's countdown in words: 2d 4h, 3h 12m, 12m, then "now". Pure. */
object Countdown {
    fun words(target: Long, now: Long): String {
        val m = (target - now) / 60_000L
        return when {
            m <= 0 -> "now"
            m < 60 -> "${m}m"
            m < 24 * 60 -> "${m / 60}h ${m % 60}m"
            else -> "${m / (24 * 60)}d ${m / 60 % 24}h"
        }
    }
}

private val MUMBAI: ZoneId = ZoneId.of("Asia/Kolkata")
private val CLOCK = DateTimeFormatter.ofPattern("h:mm")

/**
 * Home: the living sky, the weather (temperature in dot-matrix, Boston and Mumbai clocks), then what needs him (Deny / Allow) and her cards on glass. Swipe right = seen, left = less like this; a chip sends its
 * label back. [top] leaves room for the island, [bottom] for the dock.
 */
@Composable
fun HomeScreen(doing: Doing, top: androidx.compose.ui.unit.Dp = 56.dp, bottom: androidx.compose.ui.unit.Dp = 96.dp,
               start: androidx.compose.ui.unit.Dp = 16.dp, scene: Boolean = true, open: (String) -> Unit = {}) {
    val context = LocalContext.current
    val db = remember { LocalDb.get(context) }
    val tick by db.changed.collectAsState()
    val changed by PhoneActions.changed.collectAsState()
    val asks by produceState(emptyList<PhoneActions.Pending>(), changed) { value = withContext(Dispatchers.IO) { PhoneActions.waiting(context) } }
    var now by remember { mutableStateOf(ZonedDateTime.now(Sun.ZONE)) }
    LaunchedEffect(Unit) { while (true) { delay(20_000); now = ZonedDateTime.now(Sun.ZONE) } }
    // Re-read each minute too, so a card whose until passed leaves and countdowns move.
    val cards by produceState(emptyList<Card>(), tick, now.minute) { value = withContext(Dispatchers.IO) { db.openCards() } }
    val weather by produceState(Weather(), now.hour * 60 + now.minute / 5) { value = withContext(Dispatchers.IO) { Weather.read(context) } }
    val pinsTick by HomePins.changed.collectAsState()
    val appsAt by dev.apollo.ui.AthenaNow.appsAt.collectAsState()
    val pins by produceState(emptyList<Pair<Pin, MiniApp>>(), pinsTick, appsAt) {
        value = withContext(Dispatchers.IO) {
            val apps = MiniApps.all(context)
            val byName = apps.associateBy { it.name }
            HomePins.parse(HomePins.read(context), apps.associate { it.name to it.widgets }).mapNotNull { p -> byName[p.app]?.let { p to it } }
        }
    }

    fun feedback(card: Card, what: String, close: String?) {
        Facts.post(context, "card_feedback", CardFeedback.data(card, what))
        close?.let { db.setCardState(card.id, it) }
    }
    val scope = rememberCoroutineScope()
    // A button with `do` runs on the phone (CardActions); a label alone goes to Athena. Either way she gets the fact.
    fun tapped(card: Card, b: CardButton) = scope.launch(Dispatchers.IO) { CardActions.tap(context, card, b) { app -> scope.launch { open(app) } } }

    Box(Modifier.fillMaxSize()) {
        Sky(Modifier.fillMaxSize())
        LazyColumn(Modifier.fillMaxSize().padding(start = start, end = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Spacer(Modifier.height(top)); Head(now, weather) }
            if (asks.isNotEmpty()) item { Text("NEEDS YOU", style = Type.kicker.copy(color = Tone.onSky)) }
            items(asks, key = { "ask" + it.id }) { AskCard(it, onSky = true) }
            if (cards.isNotEmpty()) item { Text("FROM ATHENA", style = Type.kicker.copy(color = Tone.onSkyDim)) }
            items(cards, key = { it.id }) { card ->
                SwipeCard(card, { tapped(card, it) }, { feedback(card, CardFeedback.SEEN, "seen") }, { feedback(card, CardFeedback.LESS, "dismissed") },
                    card.app?.let { app -> { feedback(card, "opened $app", null); open(app) } })
            }
            if (pins.isNotEmpty()) item { Text("PINNED", style = Type.kicker.copy(color = Tone.onSkyDim)) }
            // A secure app's widget never draws on Home (no FLAG_SECURE here): its tile only.
            items(pins, key = { "pin" + it.first.wire + appsAt }) { (p, a) ->
                if (p.widget != null && !a.secure) WidgetPanel(a, p.widget, open) else PinTile(a) { open(a.name) }
            }
            item { Spacer(Modifier.height(bottom)) }     // Calypso lives in More, not Home
        }
    }
}

/** A pinned mini app: its name and what it is, tap to open. */
@Composable
private fun PinTile(a: MiniApp, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)).clickable(onClick = onOpen).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(a.title, style = Type.heading.copy(color = Tone.onSky))
            if (a.about.isNotBlank()) Text(a.about, style = Type.small.copy(color = Tone.onSkyDim), maxLines = 2)
        }
        Choice("Open", false, true) { onOpen() }
    }
}

/** A pinned widget, live: the widget page itself in the engine's WebView (its taps work as on a page), its app's name over it. */
@Composable
private fun WidgetPanel(a: MiniApp, path: String, open: (String) -> Unit) {
    val c = LocalContext.current
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)).padding(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(a.title, style = Type.heading.copy(color = Tone.onSky), modifier = Modifier.weight(1f))
            Choice("Open", false, true) { open(a.name) }
        }
        AndroidView(modifier = Modifier.fillMaxWidth().height(168.dp).clip(RoundedCornerShape(14.dp)), factory = { ctx ->
            WebView(ctx).also { w ->
                // Pinned to its app: a link to another app (Money) opens that app's own screen, never inside this panel.
                AppWeb.configure(w, c, pinned = a.name, openApp = open)
                w.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                // Zeus's widgets/<w>.json are drawn as HTML by WidgetJson, served under __json/ (as Widgets does).
                w.loadUrl(AppWeb.url(a.name, if (path.endsWith(".json")) "__json/$path" else path))
            }
        }, onRelease = { it.destroy() })
    }
}

/** The weather head: temperature in dot-matrix when the weather app has one (else the time), its summary, and both clocks. */
@Composable
private fun Head(now: ZonedDateTime, w: Weather) {
    Column {
        Text(w.temp?.let { "$it°" } ?: now.format(CLOCK), style = Type.bigDots)
        w.summary?.let { Text(it, style = Type.heading.copy(color = Tone.onSky)) }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Clock("BOSTON", now)
            Clock("MUMBAI", now.withZoneSameInstant(MUMBAI))
        }
    }
}

@Composable
private fun Clock(place: String, t: ZonedDateTime) {
    Column {
        Text(place, style = Type.kicker.copy(color = Tone.onSkyDim))
        Text(t.format(CLOCK) + if (t.hour < 12) " AM" else " PM", style = Type.dots.copy(color = Tone.onSky, fontSize = 20.sp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeCard(card: Card, chip: (CardButton) -> Unit, seen: () -> Unit, less: () -> Unit, onOpen: (() -> Unit)? = null) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = {
        when (it) {
            SwipeToDismissBoxValue.StartToEnd -> { seen(); true }
            SwipeToDismissBoxValue.EndToStart -> { less(); true }
            SwipeToDismissBoxValue.Settled -> false
        }
    })
    val shape = RoundedCornerShape(22.dp)
    SwipeToDismissBox(state, backgroundContent = {
        // Glass is see-through, so the hint only exists mid-swipe.
        if (state.dismissDirection == SwipeToDismissBoxValue.Settled) return@SwipeToDismissBox
        val right = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
        Box(Modifier.fillMaxSize().background(Tone.onSky.copy(alpha = .12f), shape).padding(20.dp), contentAlignment = if (right) Alignment.CenterStart else Alignment.CenterEnd) {
            Text(if (right) "Seen" else "Less like this", style = Type.small.copy(color = Tone.onSky, fontWeight = FontWeight.SemiBold))
        }
    }) {
        Column(Modifier.fillMaxWidth().glass(shape).then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(card.title, style = Type.heading.copy(color = Tone.onSky), modifier = Modifier.weight(1f))
                card.countdown?.let { Text(Countdown.words(it, System.currentTimeMillis()), style = Type.dots.copy(color = Tone.onSky, fontSize = 20.sp)) }
            }
            if (card.body.isNotBlank()) Text(card.body, style = Type.body.copy(color = Tone.onSky), modifier = Modifier.padding(top = 4.dp))
            if (card.actions.isNotEmpty()) FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                card.actions.forEachIndexed { i, a -> Choice(a.label, i == 0, true) { chip(a) } }
            }
        }
    }
}
