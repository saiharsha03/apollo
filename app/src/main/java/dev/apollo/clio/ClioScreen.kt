package dev.apollo.clio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.apollo.ui.Tone
import dev.apollo.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Clio, read only: Apollo's log, newest first, by area. */
@Composable
fun ClioScreen() {
    val c = LocalContext.current
    var area by rememberSaveable { mutableStateOf<String?>(null) }
    val lines by produceState(emptyList<ClioLine>()) { value = withContext(Dispatchers.IO) { Clio.lines(c) } }
    val areas = lines.map { it.area }.distinct().sorted()
    val clock = SimpleDateFormat("MMM d  HH:mm:ss", Locale.US)
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("Logs", style = Type.title)
        Text("What Apollo did, kept 14 days on the phone. Kinds and outcomes only, never your words.", style = Type.small)
        LazyRow(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf<String?>(null) + areas) { a ->
                val on = a == area
                Text(a ?: "All", style = Type.small.copy(color = if (on) Tone.ground else Tone.ink, fontWeight = FontWeight.SemiBold), modifier = Modifier
                    .background(if (on) Tone.ink else Tone.soft, RoundedCornerShape(16.dp)).clickable { area = a }.padding(horizontal = 14.dp, vertical = 7.dp))
            }
        }
        if (lines.isEmpty()) Text("Nothing logged yet.", style = Type.small)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(lines.filter { area == null || it.area == area }) { l ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Row {
                        Text(clock.format(Date(l.at)), style = Type.small, modifier = Modifier.weight(1f))
                        Text(l.area, style = Type.small)
                    }
                    Text(l.event + if (l.detail.isNotBlank()) ": ${l.detail}" else "",
                        style = Type.body.copy(color = when (l.level) { "error" -> Tone.alarm; "warn" -> Tone.ink; else -> Tone.ink },
                            fontWeight = if (l.level == "info") FontWeight.Normal else FontWeight.SemiBold))
                }
            }
        }
    }
}
