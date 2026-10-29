package dev.apollo.muse

import com.spotify.protocol.client.Result
import dev.apollo.apps.AppWeb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MuseCommandTest {
    @Test fun commands() {
        assertEquals(Muse.Command.Play("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"), Muse.Command.parse("play", "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"))
        // A uri that isn't Spotify's is dropped: play just resumes.
        assertEquals(Muse.Command.Play(null), Muse.Command.parse("play", "https://evil.example/x"))
        assertEquals(Muse.Command.Next, Muse.Command.parse("skip", null))
        assertNull(Muse.Command.parse("delete", null))
    }

    @Test fun shuffleQueueLikeAndDevices() {
        val song = "spotify:track:4uLU6hMCjMI75M1A2tKUQC"
        assertEquals(Muse.Command.Shuffle(true), Muse.Command.parse("shuffle", null, on = true))
        assertEquals(Muse.Command.Shuffle(null), Muse.Command.parse("shuffle", null))
        assertEquals(Muse.Command.Queue(song), Muse.Command.parse("queue", song))
        assertNull(Muse.Command.parse("queue", "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"))   // queue takes a track
        assertEquals(Muse.Command.Like(null, true), Muse.Command.parse("like", ""))           // the song playing
        assertEquals(Muse.Command.Like(song, false), Muse.Command.parse("unlike", song))
        assertNull(Muse.Command.parse("like", "spotify:album:4uLU6hMCjMI75M1A2tKUQC"))
        assertEquals(Muse.Command.Devices, Muse.Command.parse("devices", null))
        assertEquals(Muse.Command.Device("abc123def4567"), Muse.Command.parse("device", null, device = "abc123def4567"))
        assertNull(Muse.Command.parse("device", null, device = "../me"))
    }

    @Test fun onlyMusesSpotifyTapIsCarriedOut() {
        val pause = Muse.Command.parse("pause", null)
        assertTrue(AppWeb.museTap("muse", "spotify", pause))
        assertFalse(AppWeb.museTap("weather", "spotify", pause))
        assertFalse(AppWeb.museTap("muse", "play", pause))
        assertFalse(AppWeb.museTap("muse", "spotify", Muse.Command.parse("rm", null)))
    }

    @Test fun onlyMusesPageReadsSpotifyAndOnlyTheReadKinds() {
        val (path, q) = AppWeb.museRead("muse", "search", mapOf("query" to " arijit ", "limit" to 50))!!
        assertEquals("/v1/spotify/search", path)
        assertEquals(mapOf("q" to "arijit", "type" to "track", "limit" to "10"), q)
        assertEquals("/v1/spotify/playlists" to emptyMap<String, String>(), AppWeb.museRead("muse", "playlists", emptyMap()))
        assertEquals("0", AppWeb.museRead("muse", "playlist_tracks", mapOf("offset" to -5))!!.second["offset"])
        assertNull(AppWeb.museRead("weather", "playlists", emptyMap()))
        assertNull(AppWeb.museRead("muse", "access", emptyMap()))
    }

    @Test fun connectFailuresSayWhatToFix() {
        assertEquals("The Spotify app isn't logged in", connectReason("NotLoggedInException"))
        assertTrue(connectReason("UserNotAuthorizedException").contains("Agree"))
        assertTrue(connectReason("Weird").contains("Weird"))
    }

    @Test fun spotifysRefusalIsNotDone() {
        fun res(ok: Boolean, msg: String?) = object : Result<Any> {
            override fun getData(): Any? = null
            override fun isSuccessful() = ok
            override fun getErrorMessage() = msg
            override fun getError(): Throwable? = null
        }
        assertEquals(true to "queued x", spotifyAnswer(res(true, null), "queued x"))
        assertEquals(false to "Spotify refused: Premium required", spotifyAnswer(res(false, "Premium required"), "queued x"))
        assertFalse(spotifyAnswer(null, "liked").first)                                    // no answer in time
    }
}
