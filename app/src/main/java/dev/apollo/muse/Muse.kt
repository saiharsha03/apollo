package dev.apollo.muse

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.client.CallResult
import com.spotify.protocol.client.Result
import dev.apollo.actions.ActionRegistry
import dev.apollo.calypso.KeystoreSealer
import dev.apollo.calypso.Sealer
import dev.apollo.net.Facts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Spotify's app registration (Zeus's docs/SPOTIFY.md): the client id is public; no client secret is used. */
object SpotifyConfig {
    const val CLIENT_ID = "YOUR_SPOTIFY_CLIENT_ID"
    const val REDIRECT = "zeus://spotify-callback"
}

/** Spotify's answer to a call (null: none in time) as the action's result. Pure. */
fun spotifyAnswer(r: Result<*>?, word: String): Pair<Boolean, String> = when {
    r == null -> false to "Spotify didn't answer in time"
    r.isSuccessful -> true to word
    else -> false to "Spotify refused: ${r.errorMessage ?: r.error?.javaClass?.simpleName ?: "no reason given"}"
}

/** The SDK's failure, in his words (as Zeus said them). Pure. */
fun connectReason(error: String): String = when (error) {
    "CouldNotFindSpotifyApp" -> "The Spotify app isn't installed"
    "NotLoggedInException" -> "The Spotify app isn't logged in"
    "UserNotAuthorizedException" -> "Spotify's Allow for Apollo was refused or closed; try again and tap Agree"
    "AuthenticationFailedException" -> "Spotify refused Apollo's sign-in (this build's signature isn't registered with Spotify)"
    "OfflineModeException" -> "The Spotify app is in offline mode"
    "UnsupportedFeatureVersionException" -> "The Spotify app is too old; update it"
    "SpotifyDisconnectedException", "SpotifyConnectionTerminatedException" -> "The Spotify app closed the link; try again"
    else -> "Spotify did not connect ($error)"
}

data class NowPlaying(val title: String, val artist: String, val playing: Boolean, val uri: String?)

/**
 * His Web API sign-in from Zeus, kept where Zeus left it (prefs "calypso", key "spotify.tokens", sealed with alias
 * zeus.calypso.spotify), so it carries over. App Remote needs no token; this is for the Web API later.
 */
class SpotifyTokens(context: Context, private val sealer: Sealer = KeystoreSealer("zeus.calypso.spotify")) {
    private val sp = context.getSharedPreferences("calypso", Context.MODE_PRIVATE)

    /** True when Zeus's (or a later) sign-in is still here and opens. */
    fun signedIn(): Boolean = sp.getString(TOKENS, null)?.let { sealer.open(it) }?.let { runCatching { JSONObject(it).has("refresh") }.getOrDefault(false) } ?: false

    /** The sign-in itself ({access, refresh, expiresAt, scope}), for the one handover to the server; null when there is none. */
    fun read(): JSONObject? = sp.getString(TOKENS, null)?.let { sealer.open(it) }?.let { runCatching { JSONObject(it) }.getOrNull() }?.takeIf { it.has("refresh") }

    private companion object { const val TOKENS = "spotify.tokens" }
}

/**
 * Muse: Spotify through App Remote (IPC to the Spotify app on the phone). Plays, pauses, skips, and posts a `music` fact
 * when the track changes, so Athena knows what he listens to. Athena's `phone_do spotify` lands in [act], behind his
 * approvals like every phone action.
 */
object Muse {
    @Volatile private var remote: SpotifyAppRemote? = null
    private val main = Handler(Looper.getMainLooper())
    private val _now = MutableStateFlow<NowPlaying?>(null)
    val now: StateFlow<NowPlaying?> = _now.asStateFlow()
    @Volatile var why: String = ""; private set
    @Volatile private var lastUri: String? = null

    /** At app start: Athena's `spotify` action. It connects on first use, so app start never binds to Spotify. */
    fun register() {
        ActionRegistry.handlers["spotify"] = { ctx, args -> act(ctx, args) }
    }

    fun installed(c: Context): Boolean = SpotifyAppRemote.isSpotifyInstalled(c)

    /** One connect: its latch counts down on Spotify's answer; [failure] is the SDK's reason when it said no. */
    private class Attempt { val done = CountDownLatch(1); @Volatile var failure: String? = null }

    /**
     * Connects (the SDK wants the main thread) and waits. The first time, Spotify must show its own Allow screen for
     * Apollo, and it can only do that in front: when it doesn't answer in [FIRST_MS] and Apollo is on screen, Spotify is
     * brought up and asked again, waiting [ALLOW_MS] for his tap. Called on main it can't wait, so it only starts.
     */
    @Synchronized fun connect(c: Context): SpotifyAppRemote? {
        remote?.takeIf { it.isConnected }?.let { return it }
        val app = c.applicationContext
        if (!installed(app)) { why = connectReason("CouldNotFindSpotifyApp"); return null }
        if (Looper.myLooper() == Looper.getMainLooper()) { attempt(app); why = "Spotify is still connecting; try again in a moment"; return null }
        val first = attempt(app)
        if (wait(first, FIRST_MS)) return done(first)
        if (!inFront() || !openSpotify(app)) { why = NO_ANSWER; return null }
        val second = attempt(app)
        if (wait(second, ALLOW_MS)) return done(second)
        why = NO_ANSWER
        return null
    }

    private fun attempt(app: Context): Attempt {
        val a = Attempt()
        main.post {
            val params = ConnectionParams.Builder(SpotifyConfig.CLIENT_ID).setRedirectUri(SpotifyConfig.REDIRECT).showAuthView(true).build()
            SpotifyAppRemote.connect(app, params, object : Connector.ConnectionListener {
                override fun onConnected(r: SpotifyAppRemote) {
                    // A late answer to an earlier attempt: keep the link already made.
                    if (remote?.isConnected == true && remote !== r) SpotifyAppRemote.disconnect(r) else { remote = r; follow(app, r) }
                    a.done.countDown()
                }

                // Also called when a made link drops later; every use checks isConnected, so remote stays as it is.
                override fun onFailure(error: Throwable) {
                    a.failure = connectReason(error.javaClass.simpleName)
                    a.done.countDown()
                }
            })
        }
        return a
    }

    /** True once [a] has an answer or any attempt has linked; false after [ms] without one. */
    private fun wait(a: Attempt, ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (a.done.await(250, TimeUnit.MILLISECONDS) || remote?.isConnected == true) return true
        }
        return false
    }

    private fun done(a: Attempt): SpotifyAppRemote? =
        remote?.takeIf { it.isConnected }?.also { why = "" } ?: run { why = a.failure ?: NO_ANSWER; null }

    private fun follow(app: Context, r: SpotifyAppRemote) {
        r.playerApi.subscribeToPlayerState().setEventCallback { s ->
            val t = s.track
            val np = t?.let { NowPlaying(it.name.orEmpty(), it.artist?.name.orEmpty(), !s.isPaused, it.uri) }
            _now.value = np
            if (np != null && np.uri != lastUri) {
                lastUri = np.uri
                Facts.post(app, "music", JSONObject().put("title", np.title).put("artist", np.artist).put("uri", np.uri).put("playing", np.playing))
            }
        }
    }

    /** Apollo is on screen, so it may bring another app up (Android blocks that from behind). */
    private fun inFront() = runCatching {
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
    }.getOrDefault(false)

    private fun openSpotify(app: Context): Boolean = runCatching {
        val i = app.packageManager.getLaunchIntentForPackage("com.spotify.music") ?: return false
        app.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    private const val FIRST_MS = 8_000L
    private const val ALLOW_MS = 45_000L
    const val NO_ANSWER = "Spotify never answered Apollo. Open Spotify, check you're logged in, tap Agree if it asks to allow Apollo, then try again"

    /**
     * `phone_do spotify` {do: play|pause|resume|next|previous|shuffle|queue|like|unlike|devices|device, uri?, on?, device?}.
     * Devices and moving playback go through the Web API (App Remote can't); the rest through App Remote. Blocking; never
     * on the main thread.
     */
    fun act(c: Context, args: JSONObject): Pair<Boolean, String> {
        val cmd = Command.of(args) ?: return false to "spotify: do must be play, pause, resume, next, previous, shuffle, queue, like, unlike, devices or device (with the right uri or device id)"
        when (cmd) {
            Command.Devices -> return SpotifyPlaylists.web(c, "GET", "/me/player/devices") { code, body ->
                if (code == 200) true to devices(body) else false to SpotifyPlaylists.refused(code, "see his devices")
            }
            is Command.Device -> return SpotifyPlaylists.web(c, "PUT", "/me/player", JSONObject().put("device_ids", org.json.JSONArray().put(cmd.id)).put("play", true)) { code, _ ->
                if (code in 200..299) true to cmd.word else false to SpotifyPlaylists.refused(code, "move playback")
            }
            else -> Unit
        }
        val r = connect(c) ?: return false to why
        val p = r.playerApi
        // Each call answers later; wait for Spotify's answer so a refusal comes back as one, not as done.
        val call: CallResult<*> = when (cmd) {
            is Command.Play -> if (cmd.uri != null) p.play(cmd.uri) else p.resume()
            Command.Pause -> p.pause()
            Command.Resume -> p.resume()
            Command.Next -> p.skipNext()
            Command.Previous -> p.skipPrevious()
            is Command.Shuffle -> if (cmd.on == null) p.toggleShuffle() else p.setShuffle(cmd.on)
            is Command.Queue -> p.queue(cmd.uri)
            is Command.Like -> {
                val uri = cmd.uri ?: _now.value?.uri?.takeIf { it.startsWith("spotify:track:") } ?: return false to "nothing playing to like"
                val res = runCatching { (if (cmd.on) r.userApi.addToLibrary(uri) else r.userApi.removeFromLibrary(uri)).await(ANSWER_S, TimeUnit.SECONDS) }.getOrNull()
                return spotifyAnswer(res, if (cmd.on) "liked $uri" else "unliked $uri")
            }
            Command.Devices, is Command.Device -> return false to "spotify: ${cmd.word} goes through the Web API"
        }
        return spotifyAnswer(runCatching { call.await(ANSWER_S, TimeUnit.SECONDS) }.getOrNull(), cmd.word)
    }

    private const val ANSWER_S = 5L

    /** Spotify's device list as [{id, name, type, active, volume}], the only fields kept. Pure. */
    fun devices(body: String): String {
        val arr = runCatching { JSONObject(body).optJSONArray("devices") }.getOrNull() ?: org.json.JSONArray()
        val out = org.json.JSONArray()
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            out.put(JSONObject().put("id", d.optString("id")).put("name", d.optString("name")).put("type", d.optString("type"))
                .put("active", d.optBoolean("is_active")).put("volume", d.opt("volume_percent") ?: JSONObject.NULL))
        }
        return out.toString()
    }

    /** What `phone_do spotify` asks for. Pure, so it is tested. */
    sealed class Command(val word: String) {
        data class Play(val uri: String?) : Command(if (uri != null) "playing $uri" else "playing")
        data object Pause : Command("paused")
        data object Resume : Command("playing")
        data object Next : Command("next track")
        data object Previous : Command("previous track")
        data class Shuffle(val on: Boolean?) : Command(when (on) { true -> "shuffle on"; false -> "shuffle off"; null -> "shuffle toggled" })
        data class Queue(val uri: String) : Command("queued $uri")
        data class Like(val uri: String?, val on: Boolean) : Command(if (on) "liked" else "unliked")
        data object Devices : Command("devices")
        data class Device(val id: String) : Command("playing on device $id")

        companion object {
            private val URI = Regex("^spotify:(track|album|playlist|artist|show|episode|user:[A-Za-z0-9._-]+:(collection|playlist:[A-Za-z0-9]+)):[A-Za-z0-9]+$|^spotify:user:[A-Za-z0-9._-]+:collection$")
            private val TRACK = Regex("^spotify:(track|episode):[A-Za-z0-9]{22}$")
            private val SONG = Regex("^spotify:track:[A-Za-z0-9]{22}$")
            private val DEVICE = Regex("^[A-Za-z0-9]{10,64}$")
            fun of(a: JSONObject): Command? =
                parse(a.optString("do"), a.optString("uri"), if (a.has("on")) a.optBoolean("on") else null, a.optString("device"))

            /** A uri that isn't a Spotify one is dropped, so play just resumes; queue needs a track or episode. */
            fun parse(cmd: String, uri: String?, on: Boolean? = null, device: String? = null): Command? {
                val u = uri?.trim()?.takeIf { it.isNotEmpty() }
                return when (cmd.lowercase()) {
                    "play" -> Play(u?.takeIf { URI.matches(it) })
                    "pause" -> Pause
                    "resume" -> Resume
                    "next", "skip" -> Next
                    "previous", "back" -> Previous
                    "shuffle" -> Shuffle(on)
                    "queue" -> u?.takeIf { TRACK.matches(it) }?.let(::Queue)
                    // Without a uri, the track playing now; a uri that isn't a track refuses rather than liking something else.
                    "like", "unlike" -> if (u != null && !SONG.matches(u)) null else Like(u, cmd.lowercase() == "like")
                    "devices" -> Devices
                    "device" -> device?.trim()?.takeIf { DEVICE.matches(it) }?.let(::Device)
                    else -> null
                }
            }
        }
    }
}
