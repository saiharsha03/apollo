package dev.apollo.net

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.apollo.calypso.AndroidBiometric
import dev.apollo.calypso.SealedSecret
import dev.apollo.ui.Action
import dev.apollo.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pairing and Athena's key. Pair: he types the one-time code the server printed; the phone makes its own token and keeps it
 * in Calypso. Key: after his fingerprint, the Anthropic key Zeus kept in Calypso goes to the server once (he never pastes it).
 */
@Composable
fun PairScreen() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val paired = remember(note) { SealedSecret(c, Server.TOKEN_SLOT).has() }
    var pairing by remember { mutableStateOf(false) }
    var pairNote by remember { mutableStateOf<String?>(null) }
    // Scrolls: the notes at the bottom were off screen on the cover display.
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp)) {
        Text("Server", style = Type.title)
        Text(if (paired) "Paired with ${Server.baseUrl(c)}" else "Not paired yet", style = Type.small)
        val cert = remember(note) { DeviceCert.paired() }
        Text(if (cert) "Client certificate in this phone's Keystore (mTLS)." else "No client certificate yet: pairing gets one once the server has its device CA.", style = Type.small)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(code, { code = it }, label = { Text("Pairing code (four words)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        // One tap at a time, and the answer right under the button: a second tap would spend the code and hide "Paired".
        Action(if (pairing) "Pairing..." else "Pair", enabled = code.isNotBlank() && !pairing) {
            pairing = true
            pairNote = null
            scope.launch {
                val ok = withContext(Dispatchers.IO) { Server(c).pair(code) }
                pairNote = if (ok) "Paired." + (if (DeviceCert.paired()) " Client certificate kept." else "") else "That code didn't work (wrong, used, or older than 30 minutes)."
                if (ok) { code = ""; note = pairNote; runCatching { Push.register(c) } }
                pairing = false
            }
        }
        pairNote?.let { Spacer(Modifier.height(8.dp)); Text(it, style = Type.body) }
        Spacer(Modifier.height(28.dp))
        Text("Athena's key", style = Type.heading)
        Text("Sends the Anthropic key, the admin key, the TomTom and 511NY keys and your Spotify sign-in in Calypso to the server, once. Your fingerprint first.", style = Type.small)
        Spacer(Modifier.height(12.dp))
        Action("Send key", enabled = paired) {
            AndroidBiometric(c).authenticate("Send Athena's key", "To your Apollo server, once", null, {
                scope.launch {
                    note = withContext(Dispatchers.IO) {
                        val key = SealedSecret(c, "anthropic").read() ?: return@withContext "There's no Anthropic key in Calypso."
                        val admin = SealedSecret(c, "anthropic_admin").read()
                        val tomtom = SealedSecret(c, "tomtom").read()
                        val ny511 = SealedSecret(c, "ny511").read()
                        val spotify = dev.apollo.muse.SpotifyTokens(c).read()
                        runCatching {
                            Server(c).sendBrainKey(key)
                            admin?.let { Server(c).sendAdminKey(it) }
                            tomtom?.let { Server(c).sendTomTomKey(it) }
                            ny511?.let { Server(c).sendNy511Key(it) }
                            spotify?.let { Server(c).sendSpotifyTokens(it) }
                            (if (admin != null) "Sent. Athena can start; Usage shows real costs." else "Sent. (No admin key in Calypso, so Usage shows Apollo's own tally.)") +
                                (if (tomtom != null) " TomTom key sent too." else " No TomTom key in Calypso, so no traffic or place names.") +
                                (if (ny511 != null) " 511NY key sent too." else " No 511NY key in Calypso, so no NY road events.") +
                                (if (spotify != null) " Spotify sign-in sent too." else " No Spotify sign-in from Zeus, so Athena can't search your Spotify.")
                        }.getOrElse { "Not sent: ${it.message}" }
                    }
                }
            }, { note = it })
        }
        Spacer(Modifier.height(28.dp))
        Text("Athena's box key", style = Type.heading)
        Text("Console > Managed Agents > Environments > apollo-box > Generate key. Paste it here once.", style = Type.small)
        var boxKey by remember { mutableStateOf("") }
        OutlinedTextField(boxKey, { boxKey = it }, label = { Text("Environment key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        Spacer(Modifier.height(12.dp))
        Action("Send box key", enabled = paired && boxKey.isNotBlank()) {
            scope.launch {
                note = withContext(Dispatchers.IO) { runCatching { Server(c).sendBoxKey(boxKey.trim()); "Box key sent." }.getOrElse { "Not sent: ${it.message}" } }
                boxKey = ""
            }
        }
        Spacer(Modifier.height(28.dp))
        Text("Zeus's data", style = Type.heading)
        Text("Your drives, places, money, tasks, people, memory, sleep, music and Athena chats from Zeus, opened on the phone and sent to " +
            "the server once. Calypso's people and apps stay out. Safe to tap again: nothing comes in twice.", style = Type.small)
        ZeusData.last(c)?.let { Text("Last time: $it", style = Type.small) }
        Spacer(Modifier.height(12.dp))
        var moving by remember { mutableStateOf(false) }
        Action(if (moving) "Sending..." else "Bring Zeus's data over", enabled = paired && !moving) {
            moving = true
            scope.launch {
                note = withContext(Dispatchers.IO) { runCatching { ZeusData.send(c) }.getOrElse { "Not sent: ${it.message}" } }
                moving = false
            }
        }
        note?.let { Spacer(Modifier.height(16.dp)); Text(it, style = Type.body) }
    }
}
