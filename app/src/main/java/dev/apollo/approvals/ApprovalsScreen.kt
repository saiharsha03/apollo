package dev.apollo.approvals

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import dev.apollo.calypso.AndroidBiometric
import dev.apollo.ui.Row2
import dev.apollo.ui.Tone
import dev.apollo.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The approvals table: each action and how much of him it needs. Tap a row, pick its level; loosening takes one fingerprint. */
@Composable
fun ApprovalsScreen() {
    val context = LocalContext.current
    val approvals = remember { Approvals(ApolloDb.get(context)) }
    val bio = remember { AndroidBiometric(context) }
    var rows by remember { mutableStateOf<List<RuleRow>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }
    // Every action the phone can run shows, by the row it is checked under, even one with no row yet (scan F8).
    LaunchedEffect(reload) { rows = withContext(Dispatchers.IO) { Rules.listed(approvals.rows(), dev.apollo.actions.ActionRegistry.all()) } }
    // What he allows is nobody else's business: no screenshots or recents preview (docs/PLAN.md, Security kept).
    dev.apollo.calypso.SecureWindow()

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("Approvals", style = Type.title)
        Text("What Athena may do on her own. Loosening a row needs your fingerprint.", style = Type.small)
        note?.let { Text(it, style = Type.small.copy(color = Tone.alarm)) }
        Spacer(Modifier.height(12.dp))
        LazyColumn {
            items(rows, key = { it.action }) { r ->
                LevelRow(r) { to -> note = null; approvals.set(r.action, to, bio, { reload++ }, { note = it }) }
            }
            item { FetchHosts(bio) }
        }
    }
}

/** One rule: tap it and pick the level straight from the menu. */
@Composable
private fun LevelRow(r: RuleRow, pick: (Level) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row2(r.action.replace('_', ' '), "${r.level.word}, added by ${r.by}", r.level.label) { menu = true }
        DropdownMenu(menu, { menu = false }, containerColor = Tone.raised) {
            Level.entries.forEach { l ->
                DropdownMenuItem({ Text(l.label + if (l == r.level) " (now)" else "", style = Type.body) }, { menu = false; pick(l) })
            }
        }
    }
}
