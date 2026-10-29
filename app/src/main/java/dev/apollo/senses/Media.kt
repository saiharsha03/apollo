package dev.apollo.senses

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import dev.apollo.approvals.ApolloDb
import dev.apollo.clio.Clio
import org.json.JSONObject

/** Which plays are worth a `media` fact. Pure. */
object MediaRules {
    /** Music players: Muse has its own sense. */
    val MUSIC = setOf("com.spotify.music", "com.apple.android.music", "com.google.android.apps.youtube.music", "com.amazon.mp3", "com.soundcloud.android")
    /** The same title in the same app again within this is the same sitting (Zeus's ShowsLog). */
    const val SITTING_MS = 6 * 3_600_000L

    fun kept(pkg: String, title: String, own: String, skip: Set<String>, sealed: Set<String>): Boolean =
        title.isNotBlank() && pkg != own && pkg !in MUSIC && pkg !in skip && pkg !in sealed

    /** A new sitting: this app and title weren't seen playing in the last [SITTING_MS]. */
    fun fresh(lastSeen: Long?, now: Long): Boolean = lastSeen == null || now - lastSeen >= SITTING_MS
}

/**
 * What he watches (Zeus's ShowsLog): the media sessions the notification listener can see. When a session starts
 * playing a title (a show and its episode, a film, a match) it is a `media` fact {pkg, app, title, series}, once per
 * sitting. Music players, Calypso's sealed apps and the skip list are left out.
 */
object MediaSense {
    private val followed = HashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private val seen = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var watcher: MediaSessionManager.OnActiveSessionsChangedListener? = null

    /** From the listener's connect: every session now and as they come and go. */
    fun start(c: Context, listener: ComponentName) {
        val msm = c.getSystemService(MediaSessionManager::class.java) ?: return
        val l = MediaSessionManager.OnActiveSessionsChangedListener { follow(c, it) }
        runCatching {
            msm.addOnActiveSessionsChangedListener(l, listener)
            watcher = l
            follow(c, msm.getActiveSessions(listener))
        }.onFailure { Clio.log(c, "media", "sessions not followed", JSONObject().put("error", it.javaClass.simpleName)) }
    }

    fun stop(c: Context) {
        watcher?.let { l -> runCatching { c.getSystemService(MediaSessionManager::class.java)?.removeOnActiveSessionsChangedListener(l) } }
        watcher = null
        follow(c, emptyList())
    }

    private fun follow(c: Context, list: List<MediaController>?) = synchronized(followed) {
        val now = list.orEmpty().associateBy { it.sessionToken }
        for ((t, p) in followed.toMap()) if (t !in now) { runCatching { p.first.unregisterCallback(p.second) }; followed.remove(t) }
        for ((t, m) in now) if (t !in followed) {
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = look(c, m)
                override fun onPlaybackStateChanged(state: PlaybackState?) = look(c, m)
            }
            runCatching { m.registerCallback(cb) }.onSuccess { followed[t] = m to cb }
            look(c, m)
        }
    }

    private fun look(c: Context, m: MediaController) {
        if (m.playbackState?.state != PlaybackState.STATE_PLAYING) return
        val md = m.metadata ?: return
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().trim().take(120)
        val series = (md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE))?.trim()?.take(120)?.takeIf { it.isNotEmpty() }
        val pkg = m.packageName
        // The callbacks run on the main thread; the skip list and Calypso's choices are disk reads.
        kotlin.concurrent.thread(name = "media") {
            runCatching {
                if (!MediaRules.kept(pkg, title, c.packageName, ApolloDb.get(c).skipApps(), CalypsoChoices.read(c).apps)) return@runCatching
                val key = "$pkg|$title"
                val now = System.currentTimeMillis()
                val fresh = MediaRules.fresh(seen[key], now)
                seen[key] = now
                if (!fresh) return@runCatching
                val app = runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                Senses.sink.post("media", JSONObject().put("pkg", pkg).put("app", app).put("title", title).put("series", series ?: JSONObject.NULL)
                    .put("state", "playing"), false)
            }
        }
    }
}
