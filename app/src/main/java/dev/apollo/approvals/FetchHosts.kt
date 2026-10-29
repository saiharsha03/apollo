package dev.apollo.approvals

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.apollo.calypso.Biometric
import dev.apollo.ui.Action
import dev.apollo.ui.Row2
import dev.apollo.ui.Tone
import dev.apollo.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A host name as he types it, cleaned: lower-case, no scheme, path or port. Null when it isn't one. Pure. */
fun hostOf(typed: String): String? {
    val h = typed.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore('?')
    return h.takeIf { Regex("""^(?=.{4,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$""").matches(it) }
}

/**
 * The hosts mini-app pages (and the Live chip) may fetch from: his list. Adding one needs his fingerprint (Athena can only
 * ask him in chat); taking one off is stricter, so it's a tap. Nothing is ever added for him.
 */
@Composable
fun FetchHosts(bio: Biometric) {
    val c = LocalContext.current
    val db = remember { ApolloDb.get(c) }
    var hosts by remember { mutableStateOf<List<String>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var typed by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reload) { hosts = withContext(Dispatchers.IO) { db.fetchAllow().sorted() } }
    Column {
        Spacer(Modifier.height(24.dp))
        Text("Hosts pages may reach", style = Type.heading)
        Text("Mini-app pages fetch only from these, over https. Tap one to take it off.", style = Type.small)
        note?.let { Text(it, style = Type.small.copy(color = Tone.alarm)) }
        hosts.forEach { h -> Row2(h, null, "Remove") { db.dropHost(h); reload++ } }
        OutlinedTextField(typed, { typed = it.take(260) }, label = { Text("Add a host (api.example.com)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Action("Add host", enabled = typed.isNotBlank()) {
            val h = hostOf(typed)
            if (h == null) note = "That isn't a host name." else bio.authenticate("Let pages reach $h", "Mini apps may fetch from it", null, {
                db.allowHost(h); typed = ""; note = null; reload++
            }, { note = it })
        }
        Spacer(Modifier.height(32.dp))
    }
}
