package dev.apollo.apps

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import dev.apollo.Nav
import dev.apollo.Place
import dev.apollo.net.Facts
import dev.apollo.net.Server
import dev.apollo.net.Sync
import dev.apollo.ui.Row2
import dev.apollo.ui.Tone
import dev.apollo.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

/** The search box at the top of Apps. */
@Composable
fun SearchBox(query: String, onChange: (String) -> Unit) {
    BasicTextField(query, onChange, textStyle = Type.body, cursorBrush = SolidColor(Tone.saffron), singleLine = true,
        modifier = Modifier.fillMaxWidth().background(Tone.soft, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
        decorationBox = { inner -> if (query.isEmpty()) Text("Search apps, pages, settings and places", style = Type.body.copy(color = Tone.quiet)); inner() })
}

/** What one search finds: [pick] gets the hit's go. */
@Composable
fun SearchResults(query: String, apps: List<MiniApp>, pick: (String) -> Unit) {
    val c = LocalContext.current
    var all by remember { mutableStateOf(emptyList<Hit>()) }
    LaunchedEffect(apps) { all = withContext(Dispatchers.IO) { everything(c, apps) } }
    val hits = Search.find(query, all)
    if (hits.isEmpty()) Text("Nothing called that.", style = Type.small, modifier = Modifier.padding(vertical = 13.dp))
    hits.forEach { h -> Row2(h.word, h.detail, "Open") { pick(h.go) } }
}

/** Every hit: the apps and their pages, More's rows, the nav places and his saved places (drives.places). Call on IO. */
fun everything(c: Context, apps: List<MiniApp>): List<Hit> =
    Search.apps(apps) { MiniApps.home(c, it) } +
        Nav.MORE_ROWS.map { (word, line, to) -> Hit(word, line, to) } +
        Place.entries.map { Hit(it.word, "On the nav", "place:${it.name}") } +
        savedPlaces(c)

private fun savedPlaces(c: Context): List<Hit> = runCatching {
    val f = File(MiniApps.root(c), "drives/data.db").takeIf { it.isFile } ?: return emptyList()
    SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("SELECT name, lat, lng FROM places ORDER BY name", null).use { k ->
            buildList { while (k.moveToNext()) add(Hit(k.getString(0), "A saved place", "geo:${k.getDouble(1)},${k.getDouble(2)}")) }
        }
    }
}.getOrDefault(emptyList())

/** A saved place from search: his own tap, so Maps opens straight away. */
fun openPlace(c: Context, hit: String, name: String? = null) {
    val at = hit.removePrefix("geo:")
    runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$at?q=$at" + (name?.let { "(" + Uri.encode(it) + ")" } ?: ""))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** "Pin where I am": a name he types, the phone's position now, sent as a place_pin fact (drives.places). */
@Composable
fun PinHere() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var said by remember { mutableStateOf<String?>(null) }
    Row2("Pin where I am", said ?: "Save this spot as a place, with a name", "Pin") { asking = true; said = null }
    if (!asking) return
    AlertDialog(onDismissRequest = { asking = false }, containerColor = Tone.raised,
        title = { Text("Pin where I am", style = Type.heading) },
        text = {
            BasicTextField(name, { name = it.take(80) }, textStyle = Type.body, cursorBrush = SolidColor(Tone.saffron), singleLine = true,
                modifier = Modifier.fillMaxWidth().background(Tone.soft, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
                decorationBox = { inner -> if (name.isEmpty()) Text("Its name (Gym, Parking at work)", style = Type.body.copy(color = Tone.quiet)); inner() })
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                val n = name.trim()
                asking = false
                scope.launch {
                    val here = here(c)
                    said = if (here == null) "No location now. Is Location on, and allowed for Apollo?" else {
                        Facts.post(c, "place_pin", JSONObject().put("name", n).put("lat", here.latitude).put("lng", here.longitude))
                        name = ""
                        "Pinned $n"
                    }
                }
            }) { Text("Pin", style = Type.body.copy(color = Tone.saffron)) }
        },
        dismissButton = { TextButton({ asking = false }) { Text("Cancel", style = Type.body.copy(color = Tone.quiet)) } })
}

/** Where the phone is now: a fresh fix (15 s at most), else one from the last 5 minutes. Null without the permission or a fix. */
private suspend fun here(c: Context): Location? {
    if (c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
    val lm = c.getSystemService(LocationManager::class.java) ?: return null
    val fresh = withTimeoutOrNull(15_000) {
        suspendCancellableCoroutine<Location?> { k ->
            val stop = android.os.CancellationSignal()
            k.invokeOnCancellation { stop.cancel() }
            runCatching { lm.getCurrentLocation(LocationManager.FUSED_PROVIDER, stop, c.mainExecutor) { k.resume(it) } }.onFailure { k.resume(null) }
        }
    }
    return fresh ?: listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER)
        .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }.maxByOrNull { it.time }
        ?.takeIf { System.currentTimeMillis() - it.time < 5 * 60_000 }
}

/** The archived apps (Athena's app_archive or his own), each with Restore; [changed] after one came back. */
@Composable
fun Archived(changed: () -> Unit) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) {
        list = withContext(Dispatchers.IO) {
            runCatching { JSONArray(Server(c).getJson("/v1/apps/archived")).let { a -> List(a.length()) { a.getJSONObject(it).let { o -> o.optString("name") to o.optString("title", o.optString("name")) } } } }.getOrNull()
        }
    }
    val l = list ?: return
    if (l.isEmpty() && note == null) return
    Column(Modifier.padding(top = 20.dp)) {
        Text("ARCHIVED", style = Type.kicker)
        note?.let { Text(it, style = Type.small) }
        l.forEach { (name, title) ->
            Row2(title, "Comes back as it was", "Restore") {
                scope.launch {
                    note = withContext(Dispatchers.IO) { appCall(c, name, "restore").also { runCatching { Sync.run(c) } } }
                    reload++; changed()
                }
            }
        }
    }
}

/** His tap on restore or archive (POST /v1/apps/{name}/{what}); the line to show. Call on IO. */
fun appCall(c: Context, name: String, what: String): String = runCatching {
    JSONObject(Server(c).postJson("/v1/apps/$name/$what", JSONObject())).optString("detail", "Done.")
}.getOrElse { "Not done: ${it.message ?: it.javaClass.simpleName}" }
