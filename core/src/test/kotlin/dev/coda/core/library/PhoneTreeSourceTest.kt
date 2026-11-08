package dev.coda.core.library

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.coda.core.cache.CachedTrack
import dev.coda.core.cache.KeptItem
import dev.coda.core.cache.KeptTrack
import dev.coda.core.cache.TrackMeta
import dev.coda.core.playback.StreamFormat
import dev.coda.core.subsonic.Album
import dev.coda.core.subsonic.AlbumPage
import dev.coda.core.subsonic.Artist
import dev.coda.core.subsonic.ArtistReference
import dev.coda.core.subsonic.Song
import dev.coda.core.subsonic.SubsonicError
import dev.coda.store.LibraryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The car's browse tree on the phone's offline catalogue (same rules as the phone's offline views). */
class PhoneTreeSourceTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var store: LibraryStore

    @After fun close() = store.close()

    private fun build(path: String): LibraryDatabase =
        LibraryDatabase.open(Room.databaseBuilder<LibraryDatabase>(path), BundledSQLiteDriver(), Dispatchers.IO)

    private val nova = Artist("ar-1", "Nova Lights", albumCount = 9)
    private val zed = Artist("ar-2", "Zed Ensemble", albumCount = 4)
    private fun song(id: String, album: Album, track: Int, artist: ArtistReference = ArtistReference(album.artistId!!, album.artistName)) =
        Song(id, "Song $id", album = album.name, artist = artist.name, albumId = album.id, artistId = artist.id, track = track, discNumber = 1,
            artists = listOf(artist))

    // al-1: 3 tracks, 2 here (partial). al-2: 1 of 1. al-3 (Zed): 1 of 2. al-4: none here.
    private val a1 = Album("al-1", "Bright", artist = "Nova Lights", artistId = "ar-1", songCount = 3, created = "2020-01-01T00:00:00Z", year = 2001)
    private val a2 = Album("al-2", "Arc", artist = "Nova Lights", artistId = "ar-1", songCount = 1, created = "2022-01-01T00:00:00Z", year = 2011)
    private val a3 = Album("al-3", "Cold", artist = "Zed Ensemble", artistId = "ar-2", songCount = 2, created = "2021-01-01T00:00:00Z", year = 1999)
    private val a4 = Album("al-4", "Dust", artist = "Zed Ensemble", artistId = "ar-2", songCount = 1)
    private val pages = listOf(
        AlbumPage(a1, listOf(song("s-1", a1, 1), song("s-2", a1, 2), song("s-3", a1, 3))),
        AlbumPage(a2, listOf(song("s-4", a2, 1))),
        AlbumPage(a3, listOf(song("s-5", a3, 1), song("s-6", a3, 2, ArtistReference("ar-1", "Nova Lights")))),
        AlbumPage(a4, listOf(song("s-7", a4, 1))),
    )
    private val all = pages.flatMap { it.songs }.associateBy { it.id }
    private val onPhone = setOf("s-1", "s-3", "s-4", "s-6")

    private fun cached(song: Song) = CachedTrack(song.id, "${song.id}.orig", StreamFormat.RAW, 10, 0, meta = TrackMeta.from(song))

    private fun tree(limit: Int = AlphaBuckets.DEFAULT_LIMIT, playedAt: Map<String, Long> = emptyMap(), moreKept: List<KeptItem> = emptyList(),
        completeOnly: Boolean = false, onPhone: Set<String> = this.onPhone) = runBlocking {
        store = LibraryStore(File(tmp.root, "library.db"), ::build)
        val json = CatalogueCollector.json
        for (page in pages) store.mirrorPage("album/${page.album.id}", json.encodeToString(AlbumSnapshot.serializer(), AlbumSnapshot(page.album, page.songs)))
        store.mirrorPage("artists", json.encodeToString(ArtistsSnapshot.serializer(), ArtistsSnapshot(listOf(nova, zed))))
        store.awaitWrites()
        val kept = KeptItem("playlist/pl-1", listOf("s-2", "s-1", "s-6").map { KeptTrack.from(all.getValue(it)) }, "Road trip")
        CatalogueCollector(store, { error("no server") }).collect(onPhone.map { cached(all.getValue(it)) }, listOf(kept) + moreKept) { false }
        LibraryTree(PhoneTreeSource(OfflineCatalogue(store), onPhone, playedAt, completeOnly), limit)
    }

    private suspend fun LibraryTree.ids(id: String) = children(id, 0, 50)!!.map { it.id }

    @Test fun rootsAreTheSameAsLive() = runBlocking {
        val tree = tree()
        assertEquals(listOf("home", "albums", "artists", "playlists"), tree.ids("root"))
        // No server bookmarks offline: Home leaves Continue Listening out, as the phone's offline Home does.
        assertEquals(listOf("home/newest", "home/byYear", "home/recent", "playlists"), tree.ids("home"))
    }

    @Test fun homeShelvesComeFromThePhone() = runBlocking {
        val tree = tree(playedAt = mapOf("s-6" to 5L, "s-1" to 9L))
        assertEquals(listOf("album/al-2", "album/al-3", "album/al-1"), tree.ids("home/newest"))
        assertEquals(listOf("album/al-2", "album/al-1", "album/al-3"), tree.ids("home/byYear"))
        // Recently played: the phone's own play dates, latest first.
        assertEquals(listOf("album/al-1", "album/al-3"), tree.ids("home/recent"))
        // A car still holding the shelf's id gets an empty list, not an error.
        assertEquals(emptyList<String>(), tree.ids("home/continue"))
    }

    @Test fun albumsAndArtistsListOnlyWhatIsOnThePhone() = runBlocking {
        val tree = tree()
        assertEquals(listOf("album/al-2", "album/al-1", "album/al-3"), tree.ids("albums"))
        assertEquals(listOf("artist/ar-1", "artist/ar-2"), tree.ids("artists"))
        assertEquals(setOf("album/al-1", "album/al-2"), tree.ids("artist/ar-1").toSet())
        val nova = tree.children("artists", 0, 50)!!.first()
        assertEquals("2 albums", nova.subtitle)
    }

    @Test fun aPartialAlbumListsItsTracksOnThePhone() = runBlocking {
        val tree = tree()
        assertEquals(listOf("track/s-1@album/al-1", "track/s-3@album/al-1"), tree.ids("album/al-1"))
        assertThrows(SubsonicError.NotFound::class.java) { runBlocking { tree.children("album/al-4", 0, 50) } }
        Unit
    }

    @Test fun keptPlaylistsWithTheirSongsOnThePhone() = runBlocking {
        val tree = tree()
        val list = tree.children("playlists", 0, 50)!!
        assertEquals(listOf("playlist/pl-1"), list.map { it.id })
        // The phone's offline Playlists rule: "n of N" while some are not here.
        assertEquals("2 of 3 songs", list.single().subtitle)
        assertEquals("2 of 3 songs", tree.item("playlist/pl-1")!!.subtitle)
        assertEquals(listOf("track/s-1@playlist/pl-1", "track/s-6@playlist/pl-1"), tree.ids("playlist/pl-1"))
    }

    private fun kept(id: String, name: String, vararg songs: String) =
        KeptItem("playlist/$id", songs.map { KeptTrack.from(all.getValue(it)) }, name)

    @Test fun playlistSubtitlesFollowThePhonesOfflineRule() = runBlocking {
        val tree = tree(moreKept = listOf(kept("pl-2", "All here", "s-1", "s-4"), kept("pl-3", "None here", "s-2", "s-7")))
        val lines = tree.children("playlists", 0, 50)!!.associate { it.id to it.subtitle }
        assertEquals("2 of 3 songs", lines["playlist/pl-1"])
        assertEquals("2 songs", lines["playlist/pl-2"])
        assertEquals("0 of 2 songs", lines["playlist/pl-3"])
    }

    @Test fun playingQueuesOnlyTheSongsOnThePhone() = runBlocking {
        val tree = tree()
        val track = tree.playback("track/s-3@album/al-1")!!
        assertEquals(listOf("s-1", "s-3"), track.songs.map { it.id })
        assertEquals(1, track.startIndex)
        assertEquals(listOf("s-1", "s-3"), tree.playback("album/al-1")!!.songs.map { it.id })
        val list = tree.playback("track/s-6@playlist/pl-1")!!
        assertEquals(listOf("s-1", "s-6"), list.songs.map { it.id })
        assertEquals(1, list.startIndex)
        // A track that isn't on the phone can't start a queue.
        assertEquals(null, tree.playback("track/s-2@album/al-1"))
    }

    @Test fun searchFindsOnlyWhatIsOnThePhone() = runBlocking {
        val tree = tree()
        val ids = tree.search("song", 0, 50).map { it.id }
        assertTrue(ids.containsAll(listOf("track/s-1@album/al-1", "track/s-3@album/al-1")))
        assertTrue(ids.none { it.startsWith("track/s-2@") || it.startsWith("track/s-7@") })
    }

    // "Complete albums only" in the car, the phone's rule (Shelf.completeOnly). al-2 is the only complete album.
    @Test fun completeOnlyHidesPartialAlbumsFromTheLists() = runBlocking {
        val tree = tree(completeOnly = true, playedAt = mapOf("s-6" to 5L, "s-1" to 9L, "s-4" to 1L))
        assertEquals(listOf("album/al-2"), tree.ids("albums"))
        assertEquals(listOf("album/al-2"), tree.ids("home/newest"))
        assertEquals(listOf("album/al-2"), tree.ids("home/byYear"))
        assertEquals(listOf("album/al-2"), tree.ids("home/recent"))
        // Zed has only a partial album here: no artist row, no page.
        assertEquals(listOf("artist/ar-1"), tree.ids("artists"))
        assertEquals("1 album", tree.children("artists", 0, 50)!!.single().subtitle)
        // The artist page: own partial al-1 and "Appears on" partial al-3 are gone.
        assertEquals(listOf("album/al-2"), tree.ids("artist/ar-1"))
        assertThrows(SubsonicError.NotFound::class.java) { runBlocking { tree.children("artist/ar-2", 0, 50) } }
        // A partial album's page still opens by id (an id the car still holds, a search track), as on the phone.
        assertEquals(listOf("track/s-1@album/al-1", "track/s-3@album/al-1"), tree.ids("album/al-1"))
        assertEquals("album/al-1", tree.item("album/al-1")!!.id)
        assertEquals(listOf("track/s-4@album/al-2"), tree.ids("album/al-2"))
        // Playlists are not filtered (the phone's Playlists tab neither).
        assertEquals(listOf("track/s-1@playlist/pl-1", "track/s-6@playlist/pl-1"), tree.ids("playlist/pl-1"))
    }

    @Test fun withoutCompleteOnlyPartialAlbumsShow() = runBlocking {
        val tree = tree(completeOnly = false)
        assertEquals(listOf("album/al-2", "album/al-1", "album/al-3"), tree.ids("albums"))
        assertEquals(listOf("artist/ar-1", "artist/ar-2"), tree.ids("artists"))
        assertEquals(setOf("album/al-1", "album/al-2"), tree.ids("artist/ar-1").toSet())
        assertEquals(listOf("album/al-3"), tree.ids("artist/ar-2"))
    }

    /** Car search = phone search: a partial album is no album result, its songs here are still found as tracks. */
    @Test fun completeOnlySearchFindsAPartialArtistsTracksNotItsAlbums() = runBlocking {
        // Zed's only album al-3 is partial, with its own track s-5 here.
        val here = setOf("s-1", "s-3", "s-4", "s-5")
        val on = tree(completeOnly = true, onPhone = here).search("zed", 0, 50).map { it.id }
        assertEquals(listOf("track/s-5@album/al-3"), on)
        val catalogue = OfflineCatalogue(store)
        val phone = catalogue.search("zed", catalogue.shelf(here).completeOnly(), here)
        assertEquals(on, phone.albums.map { "album/${it.id}" } + phone.songs.map { "track/${it.id}@album/${it.albumId}" } +
            phone.artists.map { "artist/${it.id}" })
        store.close()
        val off = tree(completeOnly = false, onPhone = here).search("zed", 0, 50).map { it.id }
        assertEquals(listOf("album/al-3", "track/s-5@album/al-3", "artist/ar-2"), off)
    }

    @Test fun completeOnlySearchKeepsTracksOfPartialAlbums() = runBlocking {
        val tree = tree(completeOnly = true)
        val ids = tree.search("song", 0, 50).map { it.id }
        assertTrue(ids.containsAll(listOf("track/s-1@album/al-1", "track/s-3@album/al-1", "track/s-6@album/al-3", "track/s-4@album/al-2")))
        val bright = tree.search("bright", 0, 50).map { it.id }
        assertEquals(listOf("track/s-1@album/al-1", "track/s-3@album/al-1"), bright)
    }
}
