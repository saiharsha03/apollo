package dev.apollo.muse

import dev.apollo.ui.Asks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyPlaylistsTest {
    @Test fun playlistIdsFromUrisLinksAndIds() {
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", SpotifyPlaylists.playlistId("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"))
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", SpotifyPlaylists.playlistId("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=x"))
        assertNull(SpotifyPlaylists.playlistId("../me/playlists"))
        assertNull(SpotifyPlaylists.playlistId(""))
    }

    @Test fun theCardNamesThePlaylistAndSongs() {
        val w = Asks.words("spotify_playlist", mapOf("op" to "add", "playlist" to "abc", "playlist_name" to "Drive", "track_names" to "Yellow, by Coldplay", "uris" to "[...]"))
        assertEquals("Athena wants to add songs to a Spotify playlist", w.title)
        assertEquals(listOf("Drive", "Yellow, by Coldplay"), w.fields.take(2).map { it.value })
        // The raw ids are still listed, so nothing the phone acts on is hidden.
        assertTrue(w.fields.any { it.label == "Playlist" && it.value == "abc" })
        assertEquals("Athena wants to change a Spotify playlist", Asks.words("spotify_playlist", mapOf("op" to "wipe")).title)
    }
}
