package dev.apollo.mnemosyne

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.apollo.calypso.AndroidBiometric
import dev.apollo.ui.Action
import dev.apollo.ui.Row2
import dev.apollo.ui.Tone
import dev.apollo.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Backup: set up (the recovery code, once), back up now, the list on the server, and restore. FLAG_SECURE while open. */
@Composable
fun MnemosyneScreen() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        val w = (c as? Activity)?.window
        w?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    var ready by remember { mutableStateOf(Mnemosyne.ready(c)) }
    var code by remember { mutableStateOf<String?>(null) }
    var line by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var backups by remember { mutableStateOf(emptyList<BackupInfo>()) }
    var restoring by remember { mutableStateOf<BackupInfo?>(null) }
    var typed by remember { mutableStateOf("") }
    var typedCalypso by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    val day = SimpleDateFormat("EEE MMM d, HH:mm", Locale.US)
    fun work(label: String, block: () -> String) {
        busy = true; line = label
        scope.launch { line = withContext(Dispatchers.IO) { runCatching(block).getOrElse { "Failed: ${it.message ?: it.javaClass.simpleName}" } }; busy = false; tick++ }
    }
    LaunchedEffect(tick) { withContext(Dispatchers.IO) { runCatching { Mnemosyne.list(c) } }.onSuccess { backups = it } }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Spacer(Modifier.height(16.dp)); Text("Backup", style = Type.title) }
        item {
            Text("Apollo's databases, settings and the phone-only mini-app files, locked on this phone before they go to the server. " +
                "Calypso stays on this phone unless you include it below. Nightly while charging on Wi-Fi.", style = Type.small)
        }
        if (Mnemosyne.staged(c)) item { Text("A restore is ready. Close and reopen Apollo to finish it.", style = Type.body.copy(color = Tone.alarm)) }
        if (!ready) item {
            Action("Set up backup") {
                runCatching { Mnemosyne.setUp(c) }.onSuccess { code = it; ready = true }.onFailure { line = it.message }
            }
        } else item {
            val last = Mnemosyne.last(c)
            Text(if (last > 0) "Last backup ${day.format(Date(last))}" else "No backup yet", style = Type.body)
            Spacer(Modifier.height(8.dp))
            Action("Back up now", enabled = !busy) { work("Backing up") { "Backed up: ${Mnemosyne.backUp(c)}" } }
            Spacer(Modifier.height(8.dp))
            Action("Show recovery code", primary = false) {
                AndroidBiometric(c).authenticate("Show recovery code", "Backup", null, { code = Mnemosyne.code(c) }, { line = it })
            }
        }
        code?.let {
            item {
                Column(Modifier.fillMaxWidth().background(Tone.raised, RoundedCornerShape(20.dp)).padding(16.dp)) {
                    Text("RECOVERY CODE", style = Type.kicker)
                    Text(it, style = Type.dots.copy(color = Tone.ink))
                    Text("Write it down. Without it nobody can open these backups, not even you.", style = Type.small)
                }
            }
        }
        line?.let { item { Text(it, style = Type.small) } }
        if (backups.isNotEmpty()) item { Text("ON THE SERVER", style = Type.kicker) }
        items(backups, key = { it.id }) { b -> Row2(day.format(Date(b.created)), "%.1f MB".format(b.size / 1e6), "Restore") { restoring = b; typed = ""; typedCalypso = "" } }
        restoring?.let { b ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Restore ${day.format(Date(b.created))}: type its recovery code. Apollo's stores are replaced on the next start.", style = Type.small)
                    BasicTextField(typed, { typed = it }, textStyle = Type.body, cursorBrush = SolidColor(Tone.saffron),
                        modifier = Modifier.fillMaxWidth().background(Tone.soft, RoundedCornerShape(16.dp)).padding(14.dp))
                    Text("Its Calypso code too, if Calypso was included (otherwise leave it empty).", style = Type.small)
                    BasicTextField(typedCalypso, { typedCalypso = it }, textStyle = Type.body, cursorBrush = SolidColor(Tone.saffron),
                        modifier = Modifier.fillMaxWidth().background(Tone.soft, RoundedCornerShape(16.dp)).padding(14.dp))
                    Action("Restore", enabled = !busy && typed.isNotBlank()) {
                        val id = b.id; val t = typed; val k = typedCalypso.ifBlank { null }
                        work("Checking the backup") { val note = Mnemosyne.stage(c, id, t, k); restoring = null; "Ready. Close and reopen Apollo to finish." + (note?.let { " $it" } ?: "") }
                    }
                }
            }
        }
        if (ready) item { CalypsoBackupSection() }
        item { ZeusBackupsSection() }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
