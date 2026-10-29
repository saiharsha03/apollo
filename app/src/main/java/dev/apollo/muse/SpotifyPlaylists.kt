package dev.apollo.muse

import android.content.Context
import dev.apollo.actions.ActionRegistry
import dev.apollo.net.NetGate
import dev.apollo.net.Server
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Athena's playlist changes (`phone_do spotify_playlist`, tap level like every ask): add or remove songs, make a
 * playlist, reorder one. Before the ask shows, the phone looks up the playlist's and songs' names itself so the card
 * names them (never her text). After his tap the phone calls api.spotify.com through NetGate with a short-lived access
 * token from the server (GET /v1/spotify/access; the refresh token stays there).
 */
object SpotifyPlaylists {
    private const val API = "https://api.spotify.com/v1"
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val client by lazy { NetGate.builder().callTimeout(20, TimeUnit.SECONDS).build() }

    fun register() {
        ActionRegistry.handlers["spotify_playlist"] = { c, a -> perform(c, a) }
        ActionRegistry.describers["spotify_playlist"] = { c, a -> describe(c, a) }
    }

    /** One Web API call a change makes. */
    data class Call(val method: String, val path: String, val body: JSONObject, val word: String)

    /** The change from her args, checked; null when they don't make one. Pure. */
    fun change(a: JSONObject): Call? {
        val op = a.optString("op")
        if (op == "create") {
            val name = a.optString("name").trim().takeIf { it.length in 1..100 } ?: return null
            val body = JSONObject().put("name", name).put("public", a.optBoolean("public", false)).put("description", a.optString("description").take(300))
            return Call("POST", "/me/playlists", body, "made the playlist $name")
        }
        val id = playlistId(a.optString("playlist")) ?: return null
        val path = "/playlists/$id/items"
        return when (op) {
            "add", "remove" -> {
                val uris = uris(a) ?: return null
                if (op == "add") Call("POST", path, JSONObject().put("uris", JSONArray(uris)), "added ${uris.size} to the playlist")
                else Call("DELETE", path, JSONObject().put("items", JSONArray(uris.map { JSONObject().put("uri", it) })), "removed ${uris.size} from the playlist")
            }
            "reorder" -> {
                val start = a.optInt("range_start", -1)
                val before = a.optInt("insert_before", -1)
                val length = a.optInt("range_length", 1)
                if (start < 0 || before < 0 || length !in 1..100) return null
                Call("PUT", path, JSONObject().put("range_start", start).put("insert_before", before).put("range_length", length), "reordered the playlist")
            }
            else -> null
        }
    }

    /** A playlist's id from its URI, link or bare id. Pure. */
    fun playlistId(ref: String): String? {
        val r = ref.trim()
        val id = when {
            r.startsWith("spotify:playlist:") -> r.substringAfterLast(':')
            "open.spotify.com/playlist/" in r -> r.substringAfter("/playlist/").substringBefore('?')
            else -> r
        }
        return id.takeIf { Regex("^[A-Za-z0-9]{10,40}$").matches(it) }
    }

    private val TRACK = Regex("^spotify:(track|episode):[A-Za-z0-9]{22}$")

    private fun uris(a: JSONObject): List<String>? {
        val arr = a.optJSONArray("uris") ?: return null
        val out = (0 until arr.length()).map { arr.optString(it).trim() }
        return out.takeIf { it.size in 1..100 && it.all(TRACK::matches) }
    }

    private fun token(c: Context): String = JSONObject(Server(c).getJson("/v1/spotify/access")).getString("access")

    private fun call(token: String, method: String, path: String, body: JSONObject? = null): Pair<Int, String> {
        val rb = body?.toString()?.toRequestBody(JSON)
        val req = Request.Builder().url(API + path).header("Authorization", "Bearer $token").method(method, rb).build()
        return client.newCall(req).execute().use { it.code to it.body?.string().orEmpty() }
    }

    /** One Web API call with a fresh token from the server, its (code, body) handed to [then]; a failure says why. Blocking. */
    fun web(c: Context, method: String, path: String, body: JSONObject? = null, then: (Int, String) -> Pair<Boolean, String>): Pair<Boolean, String> {
        val t = runCatching { token(c) }.getOrElse { return false to "Spotify isn't connected on the server (${it.message ?: it.javaClass.simpleName})" }
        val (code, text) = runCatching { call(t, method, path, body ?: if (method == "GET") null else JSONObject()) }
            .getOrElse { return false to "Spotify didn't answer (${it.javaClass.simpleName})" }
        return then(code, text)
    }

    /** Why the Web API said no, in his words. Pure. */
    fun refused(code: Int, what: String): String = when (code) {
        401, 403 -> "Spotify refused to $what: his sign-in may lack the playback scopes (sign in to Spotify again)"
        404 -> "Spotify found no such device (or none is active)"
        429 -> "Spotify says too many calls; try later"
        else -> "Spotify answered $code"
    }

    /** The card's names, looked up by the phone; whatever she put in those keys is replaced. Blocking. */
    fun describe(c: Context, a: JSONObject) {
        a.remove("playlist_name"); a.remove("track_names")
        if (a.optString("op") == "create") return
        a.put("playlist_name", "(couldn't look up)")
        val id = playlistId(a.optString("playlist")) ?: return
        val t = runCatching { token(c) }.getOrNull() ?: return
        runCatching { call(t, "GET", "/playlists/$id?fields=name") }.getOrNull()?.takeIf { it.first == 200 }
            ?.let { a.put("playlist_name", JSONObject(it.second).optString("name")) }
        val names = when (a.optString("op")) {
            "add", "remove" -> uris(a)?.let { u -> u.take(NAMES).map { uri -> name(t, uri) } + more(u.size) }
            "reorder" -> {
                val start = a.optInt("range_start", 0)
                val n = a.optInt("range_length", 1).coerceIn(1, NAMES)
                runCatching { call(t, "GET", "/playlists/$id/items?offset=$start&limit=$n") }.getOrNull()?.takeIf { it.first == 200 }?.let { r ->
                    val items = JSONObject(r.second).optJSONArray("items") ?: JSONArray()
                    (0 until items.length()).map { i -> items.getJSONObject(i).let { it.optJSONObject("item") ?: it.optJSONObject("track") }?.let(::title) ?: "?" }
                }
            }
            else -> null
        }
        a.put("track_names", names?.filter { it.isNotEmpty() }?.joinToString("; ")?.ifEmpty { null } ?: "(couldn't look up)")
    }

    private const val NAMES = 20

    private fun more(n: Int) = if (n > NAMES) listOf("and ${n - NAMES} more") else emptyList()

    private fun name(token: String, uri: String): String {
        val kind = uri.split(':')[1]
        return runCatching { call(token, "GET", "/${kind}s/${uri.substringAfterLast(':')}") }.getOrNull()?.takeIf { it.first == 200 }
            ?.let { title(JSONObject(it.second)) } ?: uri
    }

    /** "Song, by Artist" from a track (or an episode's name and show). Pure. */
    fun title(o: JSONObject): String {
        val by = o.optJSONArray("artists")?.let { a -> (0 until a.length()).joinToString(", ") { a.getJSONObject(it).optString("name") } }
            ?: o.optJSONObject("show")?.optString("name")
        return o.optString("name") + (by?.takeIf { it.isNotBlank() }?.let { ", by $it" } ?: "")
    }

    /** After his tap. Blocking. */
    fun perform(c: Context, a: JSONObject): Pair<Boolean, String> {
        val ch = change(a) ?: return false to "spotify_playlist: op add|remove {playlist, uris}, create {name}, reorder {playlist, range_start, insert_before}"
        val t = runCatching { token(c) }.getOrElse { return false to "Spotify isn't connected on the server (${it.message ?: it.javaClass.simpleName})" }
        val (code, _) = runCatching { call(t, ch.method, ch.path, ch.body) }.getOrElse { return false to "Spotify didn't answer (${it.javaClass.simpleName})" }
        val where = a.optString("playlist_name").takeIf { it.isNotBlank() && !it.startsWith("(") }?.let { " ($it)" }.orEmpty()
        return when (code) {
            in 200..299 -> true to ch.word + where
            401, 403 -> false to "Spotify refused: his sign-in may not allow playlist changes (sign in to Spotify again with playlist access)"
            404 -> false to "Spotify found no such playlist"
            429 -> false to "Spotify says too many calls; try later"
            else -> false to "Spotify answered $code"
        }
    }
}
