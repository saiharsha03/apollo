package dev.apollo.mnemosyne

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.apollo.calypso.AndroidBiometric
import dev.apollo.calypso.CalypsoCore
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

/**
 * "Include Calypso" (the escrow, Escrow.kt): on or off behind his fingerprint, how many items are in it, and "Add older
 * items", which opens each missing one once (a fingerprint each) so the key tap escrows it.
 */
@Composable
fun CalypsoBackupSection() {
    val c = LocalContext.current
    val bio = remember { AndroidBiometric(c) }
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var on by remember { mutableStateOf(CalypsoEscrow.on(c)) }
    var counts by remember { mutableStateOf(0 to 0) }
    var line by remember { mutableStateOf<String?>(null) }
    // The new code, shown once. Inclusion turns on only when he confirms he wrote it down; if this is lost first (the
    // screen closed, the Fold folded), nothing was kept and Calypso simply stays out.
    var pending by remember { mutableStateOf<CalypsoEscrow.Pending?>(null) }
    LaunchedEffect(tick) {
        counts = withContext(Dispatchers.IO) { runCatching { CalypsoCore.get(c).calypso.items().size.let { all -> all to (all - CalypsoEscrow.missing(c).size) } }.getOrDefault(0 to 0) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("CALYPSO IN BACKUPS", style = Type.kicker)
        Text(if (on) "On: ${counts.second} of ${counts.first} items can come back on a new phone with your Calypso code. On this phone each still opens only with your fingerprint."
            else "Off: Calypso stays on this phone only. On: each item's key is also locked under a Calypso code, shown to you once and never kept here, so a new phone can open them.", style = Type.small)
        if (on) Action("Leave Calypso out", primary = false) {
            bio.authenticate("Leave Calypso out of backups", "Backup", null, {
                scope.launch {
                    line = withContext(Dispatchers.IO) { runCatching { CalypsoEscrow.set(c, null); null }.getOrElse { "Not changed: ${it.message}" } }
                    on = CalypsoEscrow.on(c); tick++
                }
            }, { line = it })
        } else if (pending == null) Action("Include Calypso", enabled = Mnemosyne.ready(c)) {
            scope.launch {
                pending = withContext(Dispatchers.IO) { runCatching { CalypsoEscrow.prepare() } }.onFailure { line = "Not changed: ${it.message}" }.getOrNull()
            }
        }
        if (on && counts.second < counts.first) Action("Add older items (${counts.first - counts.second})", primary = false) {
            scope.launch {
                val ids = withContext(Dispatchers.IO) { CalypsoEscrow.missing(c) }
                // One at a time: each needs its own fingerprint, and the tap escrows the key as it opens.
                fun next(i: Int) {
                    if (i >= ids.size) { tick++; line = "Done."; return }
                    CalypsoCore.get(c).calypso.open(ids[i], bio, { _, _ -> next(i + 1) }, { line = it; tick++ })
                }
                next(0)
            }
        }
        pending?.let { p ->
            Column(Modifier.fillMaxWidth().background(Tone.raised, RoundedCornerShape(20.dp)).padding(16.dp)) {
                Text("CALYPSO CODE", style = Type.kicker)
                Text(p.code, style = Type.dots.copy(color = Tone.ink))
                Text("Write it down beside the recovery code. It is shown only now: Apollo doesn't keep it, and without it Calypso can't come back. Calypso goes into backups once you confirm.", style = Type.small)
                Action("I wrote it down") {
                    bio.authenticate("Include Calypso in backups", "Backup", null, {
                        scope.launch {
                            line = withContext(Dispatchers.IO) { runCatching { CalypsoEscrow.set(c, p); null }.getOrElse { "Not changed: ${it.message}" } }
                            pending = null; on = CalypsoEscrow.on(c); tick++
                        }
                    }, { line = it })
                }
                Action("Cancel", primary = false) { pending = null }
            }
        }
        line?.let { Text(it, style = Type.small) }
    }
}

/**
 * Zeus's old backups on the server (copied there by deploy/zeus-backups.sh): restore with Zeus's 24-word phrase, which
 * this phone can still show (Zeus left it wrapped to a Keystore key, behind his fingerprint).
 */
@Composable
fun ZeusBackupsSection() {
    val c = LocalContext.current
    val bio = remember { AndroidBiometric(c) }
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf(emptyList<BackupInfo>()) }
    var picked by remember { mutableStateOf<BackupInfo?>(null) }
    var typed by remember { mutableStateOf("") }
    var words by remember { mutableStateOf<List<String>?>(null) }
    var line by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val day = SimpleDateFormat("EEE MMM d, HH:mm", Locale.US)
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { ZeusBackup.list(c) } }.onSuccess { list = it } }
    if (list.isEmpty() && !ZeusBackup.hasPhrase(c)) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("ZEUS'S BACKUPS", style = Type.kicker)
        Text("Calypso, Aegis, Calliope's files and the closet come back from these; Zeus's other stores have no place in Apollo. Nothing already here is replaced.", style = Type.small)
        if (ZeusBackup.hasPhrase(c)) Action("Show Zeus's phrase", primary = false) {
            val u = runCatching { ZeusBackup.phraseUnwrapper() }.getOrElse { line = "Zeus's phrase key can't be used here."; return@Action }
            bio.authenticate("Show Zeus's recovery phrase", "Backup", u, { words = runCatching { ZeusBackup.phraseWords(c, u) }.getOrNull(); if (words == null) line = "It didn't open." }, { line = it })
        }
        words?.let { w ->
            Column(Modifier.fillMaxWidth().background(Tone.raised, RoundedCornerShape(20.dp)).padding(16.dp)) {
                Text("ZEUS'S PHRASE", style = Type.kicker)
                Text(w.chunked(6).joinToString("\n") { it.joinToString(" ") }, style = Type.body)
                Action("Hide", primary = false) { words = null }
            }
        }
        list.forEach { b -> Row2(day.format(Date(b.created)), "%.1f MB".format(b.size / 1e6), "Restore") { picked = b; typed = "" } }
        picked?.let { b ->
            Text("Restore Zeus's backup of ${day.format(Date(b.created))}: type Zeus's 24 words.", style = Type.small)
            BasicTextField(typed, { typed = it }, textStyle = Type.body, cursorBrush = SolidColor(Tone.saffron),
                modifier = Modifier.fillMaxWidth().background(Tone.soft, RoundedCornerShape(16.dp)).padding(14.dp))
            Action("Restore", enabled = !busy && typed.isNotBlank()) {
                busy = true; line = "Opening it"
                val id = b.id; val t = typed
                scope.launch {
                    line = withContext(Dispatchers.IO) {
                        runCatching { ZeusBackup.restore(c, id, t) }.fold({ r ->
                            "Back: ${r.calypso} Calypso items" + (if (r.calypsoLost > 0) " (${r.calypsoLost} weren't in Zeus's escrow and can't be opened here)" else "") +
                                ", ${r.aegis} Aegis items, ${r.files} files."
                        }, { "Failed: ${it.message ?: it.javaClass.simpleName}" })
                    }
                    busy = false; typed = ""; picked = null
                }
            }
        }
        line?.let { Text(it, style = Type.small) }
    }
}
