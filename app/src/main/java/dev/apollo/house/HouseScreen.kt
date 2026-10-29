package dev.apollo.house

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.apollo.ui.Action
import dev.apollo.ui.Tone
import dev.apollo.ui.Type
import kotlinx.coroutines.launch

/** His home by room: tap a tile to switch it. Locks, garages and the like show read only. */
@Composable
fun HouseScreen() {
    val c = LocalContext.current
    val g = remember { House.get(c).also { it.start() } }
    val s by g.state.collectAsState()
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("Home", style = Type.title)
        Text(HouseRules.linkWords(s.link), style = Type.small)
        Spacer(Modifier.height(12.dp))
        if (s.link == Link.NeedsPermission) Action("Connect Google Home") { scope.launch { note = g.requestPermission() ?: "Connected." } }
        note?.let { Text(it, style = Type.small.copy(color = Tone.alarm)) }
        val byRoom = s.devices.groupBy { s.room(it.roomId)?.name ?: "No room" }.toSortedMap()
        LazyColumn(Modifier.fillMaxSize()) {
            byRoom.forEach { (room, devices) ->
                item { Text(room, style = Type.heading.copy(color = Tone.saffron), modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)) }
                items(devices, key = { it.id }) { d -> Tile(d) { scope.launch { runCatching { House.tap(c, d.id, Command.Power(d.on != true)) }.onFailure { note = it.message } } } }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

@Composable
private fun Tile(d: Device, onTap: () -> Unit) {
    val lit = d.on == true && d.online
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).background(if (lit) Tone.raised else Tone.ground, RoundedCornerShape(14.dp))
            .let { if (d.controllable) it.clickable(onClick = onTap) else it }.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(d.name, style = Type.body.copy(color = if (lit) Tone.saffron else Tone.ink))
            val bits = listOfNotNull(d.kind.word, d.level?.takeIf { lit }?.let { "$it%" }, d.tempC?.let { "%.1f°C".format(it) },
                if (!d.online) "offline" else null, if (d.kind.forbidden) "Google Home app only" else null)
            Text(bits.joinToString(" · "), style = Type.small)
        }
        if (d.controllable) Text(if (lit) "On" else "Off", style = Type.heading.copy(color = if (lit) Tone.saffron else Tone.quiet))
    }
}
