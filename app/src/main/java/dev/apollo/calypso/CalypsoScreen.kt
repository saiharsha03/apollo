package dev.apollo.calypso

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.apollo.ui.Action
import dev.apollo.ui.Row2
import dev.apollo.ui.Tone
import dev.apollo.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Calypso: the sealed items (each opens with its own fingerprint prompt), the shielded items agents may use, and his
 * saved keys (masked). The whole screen sits behind one fingerprint and FLAG_SECURE, and locks again when he leaves.
 */
@Composable
fun CalypsoScreen() {
    val context = LocalContext.current
    val core = remember { CalypsoCore.get(context) }
    val bio = remember { AndroidBiometric(context) }
    val gate = remember { VaultGate() }
    var sealed by remember { mutableStateOf<List<SealedItem>>(emptyList()) }
    var shielded by remember { mutableStateOf<List<AegisItem>>(emptyList()) }
    var shown by remember { mutableStateOf<Pair<String, String>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        val w = (context as? Activity)?.window
        w?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    // Screen off, Home or another app: locked at once, so a phone picked up within a minute shows nothing (scan F13).
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { gate.stopped(screenOff = true); shown = null }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { gate.started() }

    LaunchedEffect(gate.unlocked, reload) {
        if (!gate.unlocked) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching { CalypsoDrops.sync(context) }
            sealed = core.calypso.items()
            shielded = core.aegis.items()
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("Calypso", style = Type.title)
        Text("Only you can open what's sealed here.", style = Type.small)
        Spacer(Modifier.height(20.dp))
        if (!gate.unlocked) {
            Action("Unlock") { gate.unlock(bio, "Open Calypso", "Your fingerprint or PIN") }
            gate.failure?.let { Text(it, style = Type.small.copy(color = Tone.alarm)) }
            return@Column
        }
        shown?.let { (label, text) ->
            Text(label, style = Type.heading)
            Text(text, style = Type.body)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 12.dp)) {
                Action("Copy") { SecureClipboard.copy(context, label, text); core.calypso.note(label, "copied"); note = "Copied. The clipboard clears in 30 s." }
                Action("Hide") { shown = null }
            }
        }
        note?.let { Text(it, style = Type.small) }
        LazyColumn(Modifier.fillMaxSize()) {
            item { Text("Sealed", style = Type.heading.copy(color = Tone.saffron), modifier = Modifier.padding(top = 12.dp)) }
            if (sealed.isEmpty()) item { Text("Nothing sealed yet.", style = Type.small) }
            items(sealed, key = { "s" + it.id }) { item ->
                Row2(item.label, "${item.kind.word} · from ${item.from} · ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.at))}", "Open") {
                    core.calypso.open(item.id, bio, { i, o -> shown = i.label to (o.name?.let { "$it (${o.data.size} bytes)" } ?: o.text) }, { note = it })
                }
            }
            item { Text("Shielded", style = Type.heading.copy(color = Tone.saffron), modifier = Modifier.padding(top = 20.dp)) }
            if (shielded.isEmpty()) item { Text("Nothing shielded.", style = Type.small) }
            items(shielded, key = { "a" + it.name }) { a ->
                Row2(a.name, "${a.scope.word}${if (a.revoked) " · agents off" else ""} · used ${a.uses}×", "Show") {
                    shown = a.name to (core.aegis.reveal(a.name) ?: "can't be opened")
                }
            }
            item { KeySlotsSection() }
            item { SealedChoicesSection() }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}
