package dev.apollo.calypso

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.apollo.approvals.ApolloDb
import dev.apollo.senses.CalypsoChoices
import dev.apollo.senses.NoticeRules
import dev.apollo.ui.Action
import dev.apollo.ui.Row2
import dev.apollo.ui.Tone
import dev.apollo.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The rules for the key slots he edits here. Pure. */
object Slots {
    private val NAME = Regex("^[a-z0-9_]{2,40}$")
    /** Not editable here: the device token (pairing makes it) and the backup key (its recovery code is the only copy elsewhere). */
    val RESERVED = setOf("apollo_device", "mnemosyne")

    fun ok(slot: String) = NAME.matches(slot) && slot !in RESERVED
}

/**
 * His saved keys, masked, with add and replace. Saving asks for a fresh fingerprint even though the screen is already
 * open; the value goes straight into Keystore-sealed prefs ([SealedSecret]) and is never logged or shown again.
 */
@Composable
fun KeySlotsSection() {
    val c = LocalContext.current
    val bio = remember { AndroidBiometric(c) }
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var keys by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var editing by remember { mutableStateOf<String?>(null) }
    var slot by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reload) {
        keys = withContext(Dispatchers.IO) { SealedSecret.slots(c).map { it to (SealedSecret(c, it).read()?.let(SealedSecret::mask) ?: "can't be opened") } }
    }
    Column {
        Text("Keys", style = Type.heading.copy(color = Tone.saffron), modifier = Modifier.padding(top = 20.dp))
        if (keys.isEmpty()) Text("No saved keys.", style = Type.small)
        keys.forEach { (s, masked) ->
            Row2(s, masked, if (s in Slots.RESERVED) null else "Replace", if (s in Slots.RESERVED) null else ({ editing = s; slot = s; value = "" }))
        }
        if (editing == null) Action("Add a key", primary = false) { editing = ""; slot = ""; value = "" }
        editing?.let { was ->
            if (was.isEmpty()) OutlinedTextField(slot, { slot = it.trim().lowercase() }, label = { Text("Slot (anthropic, tomtom, github...)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value, { value = it }, label = { Text(if (was.isEmpty()) "Key" else "New key for $was") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 10.dp)) {
                Action("Save", enabled = Slots.ok(slot) && value.isNotBlank()) {
                    val s = slot
                    val replacing = SealedSecret(c, s).has()
                    bio.authenticate(if (replacing) "Replace $s" else "Save $s", "Sealed on this phone", null, {
                        scope.launch {
                            withContext(Dispatchers.IO) { SealedSecret(c, s).save(value) }
                            CalypsoCore.get(c).calypso.note(s, if (replacing) "replaced key" else "added key")
                            value = ""; editing = null; reload++
                            note = if (replacing) "Replaced. Send it to the server again if the server uses it (More > Server)." else "Saved."
                        }
                    }, { note = it })
                }
                Action("Cancel", primary = false) { value = ""; editing = null }
            }
        }
        note?.let { Text(it, style = Type.small) }
    }
}

/**
 * The apps and people he seals: their notifications never become facts. Adding is stricter, so it needs nothing more;
 * removing one loosens a rule, so it asks for his fingerprint (docs/PLAN.md, Approvals).
 */
@Composable
fun SealedChoicesSection() {
    val c = LocalContext.current
    val db = remember { ApolloDb.get(c) }
    val bio = remember { AndroidBiometric(c) }
    val names = remember { KeystoreSealer(NAMES_ALIAS) }
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var people by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var installed by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var appQuery by remember { mutableStateOf("") }
    var person by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val pm = c.packageManager
    fun label(pkg: String) = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    LaunchedEffect(reload) {
        withContext(Dispatchers.IO) {
            apps = db.calypsoApps().map { it to label(it) }.sortedBy { it.second.lowercase() }
            people = db.calypsoPeople().map { (k, n) -> k to (n?.let { names.open(it) } ?: "Someone (sealed in Zeus)") }.sortedBy { it.second.lowercase() }
            installed = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
        }
    }
    fun changed() { CalypsoChoices.forget(); reload++ }
    Column {
        Text("Sealed apps and people", style = Type.heading.copy(color = Tone.saffron), modifier = Modifier.padding(top = 20.dp))
        Text("Their notifications stay on the phone: Athena never hears of them.", style = Type.small)
        apps.forEach { (pkg, name) ->
            Row2(name, "App", "Remove") {
                bio.authenticate("Unseal $name", "Its notifications will reach Athena again", null, {
                    scope.launch { withContext(Dispatchers.IO) { db.setCalypsoApp(pkg, false) }; changed() }
                }, { note = it })
            }
        }
        people.forEach { (key, name) ->
            Row2(name, "Person", "Remove") {
                bio.authenticate("Unseal $name", "Their messages will reach Athena again", null, {
                    scope.launch { withContext(Dispatchers.IO) { db.setCalypsoPerson(key, null, false) }; changed() }
                }, { note = it })
            }
        }
        OutlinedTextField(appQuery, { appQuery = it }, label = { Text("Seal an app") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        if (appQuery.length >= 2) installed.filter { (pkg, n) -> n.contains(appQuery, true) && apps.none { it.first == pkg } }.take(6).forEach { (pkg, n) ->
            Row2(n, pkg, "Seal") { scope.launch { withContext(Dispatchers.IO) { db.setCalypsoApp(pkg, true) }; appQuery = ""; changed() } }
        }
        OutlinedTextField(person, { person = it }, label = { Text("Seal a person (name as it shows, or number)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Action("Seal person", enabled = person.isNotBlank()) {
            val n = person.trim()
            scope.launch { withContext(Dispatchers.IO) { db.setCalypsoPerson(NoticeRules.personKey(n), names.seal(n), true) }; person = ""; changed() }
        }
        note?.let { Text(it, style = Type.small) }
    }
}

/** The key Zeus sealed people's names with in senses.db; names he adds here are sealed the same way. */
private const val NAMES_ALIAS = "zeus.aegis.senses"
