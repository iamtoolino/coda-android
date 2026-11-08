package dev.coda.core.subsonic

import dev.coda.core.testing.FakeSubsonicServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Only real cover ids are ever requested; an album without `coverArt` gets our own picture, never the server's placeholder. */
class ArtworkIdTest {
    @Test fun albumHasACoverKeyOnlyWhenItHasCoverArt() {
        assertEquals("OF-1c3: the album id is the key", "a1", Album("a1", "A", coverArt = "al-a1_5f1a").artworkId)
        assertEquals("al-a1_5f1a", Album("a1", "A", coverArt = "al-a1_5f1a").serverCoverArt)
        assertNull("no coverArt: our own picture, never the server's placeholder", Album("a1", "A").artworkId)
        assertNull(Album("a1", "A", coverArt = "").artworkId)
    }

    /** An album row and the rows of its songs ask for the same picture with the same key. */
    @Test fun albumRowAndSongRowShareOneKey() {
        val album = Album("a1", "A", coverArt = "al-a1_5f1a")
        val song = Song("s1", "S", albumId = "a1", coverArt = "mf-s1_5f1a").applyingAlbum(album)
        assertEquals(album.artworkId, song.albumArtworkId)
        assertEquals(album.artworkId, CoverIds.song("a1", "mf-s1_5f1a"))
        assertEquals("an album made from a song (Go to album, resume) keeps the key",
            album.artworkId, Album("a1", "A", coverArt = song.albumArtworkId).artworkId)
    }


    @Test fun theClientRemembersTheServerCoverIdOfAlbumsItSaw() = runBlocking {
        FakeSubsonicServer().use { server ->
            server.navidromeCoverIds = true
            val client = SubsonicClient(SubsonicConfig(server.baseUrl, "synthetic", "synthetic-password"))
            assertNull(client.serverCoverArt("album-1"))
            val albums = client.getAlbumList2(type = AlbumListType.NEWEST).albums
            assertEquals("album-1", albums.first { it.id == "album-1" }.artworkId)
            assertEquals("al-album-1_5f1a", client.serverCoverArt("album-1"))
            val songs = client.getAlbum("album-1").songs
            assertTrue(songs.all { it.coverArt!!.startsWith("mf-") && it.albumArtworkId == "album-1" })
        }
    }

    @Test fun songOfAnAlbumWithoutArtHasNoId() {
        val song = Song("s1", "S", albumId = "a1", coverArt = "mf-s1")
        assertEquals("one cache entry per album (ADR-006)", "a1", song.albumArtworkId)
        assertNull(song.copy(albumHasNoCover = true).albumArtworkId)
        assertNull("not the song's own id either", song.copy(albumHasNoCover = true, albumId = null).albumArtworkId)
    }

    @Test fun looseSongFallsBackToItsOwnCoverArtOnly() {
        assertEquals("mf-s1", Song("s1", "S", coverArt = "mf-s1").albumArtworkId)
        assertNull(Song("s1", "S").albumArtworkId)
        assertNull(Song("s1", "S", albumId = "", coverArt = "").albumArtworkId)
    }

    @Test fun applyingAnAlbumMarksOrClears() {
        val song = Song("s1", "S", albumId = "a1")
        assertTrue(song.applyingAlbum(Album("a1", "A")).albumHasNoCover)
        assertFalse(song.applyingAlbum(Album("a1", "A", coverArt = "c")).albumHasNoCover)
    }

    private fun <T> withClient(block: suspend (SubsonicClient, FakeSubsonicServer) -> T): T = runBlocking {
        FakeSubsonicServer().use { server ->
            server.coverlessAlbumIds = setOf("album-2")
            block(SubsonicClient(SubsonicConfig(server.baseUrl, "synthetic", "synthetic-password")), server)
        }
    }

    @Test fun getAlbumMarksItsSongs() = withClient { client, _ ->
        assertNull(client.getAlbum("album-2").album.artworkId)
        val coverless = client.getAlbum("album-2").songs
        val covered = client.getAlbum("album-1").songs
        assertTrue(coverless.isNotEmpty() && covered.isNotEmpty())
        assertTrue(coverless.all { it.albumArtworkId == null })
        assertTrue(covered.all { it.albumArtworkId == "album-1" })
    }

    @Test fun playlistSongsAreMarkedOnceTheirAlbumHasBeenSeen() = withClient { client, _ ->
        val before = client.getPlaylist("playlist-1").songs.associate { it.id to it.albumArtworkId }
        assertEquals("album unknown yet: the album id", "album-2", before["track-3"])
        client.getAlbumList2(type = AlbumListType.NEWEST)
        val after = client.getPlaylist("playlist-1").songs.associate { it.id to it.albumArtworkId }
        assertEquals("album-1", after["track-1"])
        assertNull(after["track-3"])
    }

    @Test fun searchMarksSongsFromTheSameResponse() = withClient { client, server ->
        server.searchSongs = listOf(Song("track-1", "Signal One", albumId = "album-1", coverArt = "album-1"),
            Song("track-3", "Colour One", albumId = "album-2", coverArt = "dc-album-2:1"))
        val results = client.search3("synthetic")
        assertTrue(results.albums.any { it.id == "album-2" && it.artworkId == null })
        val coverless = results.songs.filter { it.albumId == "album-2" }
        val covered = results.songs.filter { it.albumId == "album-1" }
        assertEquals(1, coverless.size)
        assertEquals(1, covered.size)
        assertTrue(coverless.all { it.albumArtworkId == null })
        assertTrue(covered.all { it.albumArtworkId == "album-1" })
    }

    @Test fun anAlbumThatGainsArtIsUnmarked() = withClient { client, server ->
        client.getAlbumList2()
        assertNull(client.getPlaylist("playlist-1").songs.first { it.id == "track-3" }.albumArtworkId)
        server.coverlessAlbumIds = emptySet()
        client.getAlbumList2()
        assertEquals("album-2", client.getPlaylist("playlist-1").songs.first { it.id == "track-3" }.albumArtworkId)
    }
}
