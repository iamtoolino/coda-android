package dev.coda.core.library

import androidx.room.Room
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.coda.core.cache.CacheIndexDb
import dev.coda.core.cache.CacheSnapshot
import dev.coda.core.cache.KeptItem
import dev.coda.core.cache.KeptTrack
import dev.coda.core.subsonic.*
import dev.coda.store.LibraryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Snapshots: the pages table of a real `library.db` (bundled SQLite), its pins, stale marks and LRU. */
class SnapshotStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val opened = mutableListOf<LibraryStore>()
    private val dbFile by lazy { File(tmp.root, "library/acct/library.db") }
    private fun library(limit: Long = 20L * 1024 * 1024) = LibraryStore(dbFile, { path ->
        LibraryDatabase.open(Room.databaseBuilder<LibraryDatabase>(path), BundledSQLiteDriver(), Dispatchers.IO)
    }, pageLimitBytes = limit).also { opened += it }
    private fun store(limit: Long = 20L * 1024 * 1024) = library(limit).snapshots
    @After fun closeAll() = opened.forEach { it.close() }

    private val album = Album("a1", "Blue Hours", artist = "Zoë Example", coverArt = "c1")
    private val songs = listOf(Song("s1", "Café Noir", album = "Blue Hours", artist = "Zoë Example", albumId = "a1"),
        Song("s2", "Dawn", album = "Blue Hours", artist = "Zoë Example", albumId = "a1"))

    @Test fun roundTripsAlbumArtistArtistsAndHome() = runBlocking {
        val s = store()
        assertNull(s.album("a1"))
        s.putAlbum(AlbumPage(album, songs))
        s.putArtist(ArtistPage(Artist("r1", "Zoë Example"), listOf(album)))
        s.putArtists(listOf(Artist("r1", "Zoë Example", 1)))
        s.putHome(HomeShelves(albums = listOf(album), playlists = listOf(Playlist("p1", "Mix"))))
        assertEquals(songs, s.album("a1")!!.songs)
        assertEquals(album, s.artist("r1")!!.albums.single())
        assertEquals("r1", s.artists()!!.single().id)
        assertEquals("Mix", s.home()!!.playlists.single().name)
    }

    @Test fun oneStorePerLibraryAndNoFilesBesideTheDatabase() = runBlocking {
        val library = library()
        assertSame("risk 11: a single owner", library.snapshots, library.snapshots)
        library.snapshots.putAlbum(AlbumPage(album, songs))
        assertTrue(tmp.root.walkTopDown().none { it.name.endsWith(".json") || it.name.endsWith(".pin") || it.name.endsWith(".stale") })
    }

    // The collection routes keep their content.
    @Test fun roundTripsAlbumsPlaylistsAndPlaylistDetailAndSearchesThem() = runBlocking {
        val s = store()
        assertNull(s.albums()); assertNull(s.playlists()); assertNull(s.playlist("p1"))
        s.putAlbums(listOf(album, Album("a2", "Absent Friends")))
        s.putPlaylists(listOf(Playlist("p1", "Mix", 2)))
        s.putPlaylist(PlaylistPage(Playlist("p1", "Mix", 2), songs))
        assertEquals(listOf("a1", "a2"), s.albums()!!.map { it.id })
        assertEquals("Mix", s.playlists()!!.single().name)
        assertEquals(songs, s.playlist("p1")!!.songs)
        assertNull(s.playlist("p2"))
        assertEquals(listOf("a2"), s.search("absent friends").albums.map { it.id })
        assertEquals(listOf("s1"), s.search("cafe").songs.map { it.id })
    }

    @Test fun searchIsAccentAndCaseInsensitiveAcrossSnapshots() = runBlocking {
        val library = library()
        val s = library.snapshots
        s.putAlbum(AlbumPage(album, songs))
        s.putArtists(listOf(Artist("r1", "Zoë Example")))
        assertEquals(listOf("s1"), s.search("cafe NOIR").songs.map { it.id })
        assertEquals(listOf("r1"), s.search("zoe").artists.map { it.id })
        assertEquals(listOf("a1"), s.search("blue zoe").albums.map { it.id })
        assertEquals(SearchResults(), s.search("   "))
        assertEquals(SearchResults(), s.search("absent"))
        // A new write invalidates the cached index, also one from the catalogue.
        s.putHome(HomeShelves(albums = listOf(Album("a2", "Absent Friends"))))
        assertEquals(listOf("a2"), s.search("absent").albums.map { it.id })
        library.mirrorPage("album/a3", CatalogueCollector.json.encodeToString(AlbumSnapshot.serializer(), AlbumSnapshot(Album("a3", "Third Ear"), emptyList())))
        library.awaitWrites()
        assertEquals(listOf("a3"), s.search("third").albums.map { it.id })
    }

    /** Offline search reads the pages in chunks of 100; every page is still searched, in key order. */
    @Test fun searchReadsEveryPageAcrossChunks() = runBlocking {
        val library = library()
        val s = library.snapshots
        repeat(250) { i -> s.putAlbum(AlbumPage(Album("x%03d".format(i), "Album %03d".format(i)), listOf(Song("t$i", "Tune $i")))) }
        s.putArtists(listOf(Artist("r1", "Zoë Example")))
        assertEquals(listOf("x249"), s.search("album 249").albums.map { it.id })
        assertEquals(listOf("x000"), s.search("album 000").albums.map { it.id })
        assertEquals(listOf("r1"), s.search("zoe").artists.map { it.id })
        assertEquals(24, s.search("album").albums.size)
        assertEquals((0 until 24).map { "x%03d".format(it) }, s.search("album").albums.map { it.id })
    }

    /** A damaged file set aside moves the page revision, so offline search forgets its pages at once. */
    @Test fun aQuarantineMovesThePageRevision() = runBlocking {
        dbFile.parentFile.mkdirs(); dbFile.writeBytes(ByteArray(8192) { (it * 31).toByte() })
        val library = library()
        assertEquals(0, library.pageRevision)
        assertNull(library.snapshots.album("a1"))
        assertTrue(library.rebuilds >= 1)
        assertTrue(library.pageRevision >= 1)
    }

    @Test fun anUnreadablePageIsNullAndTheLeastRecentlyUsedGoPastTheLimit() = runBlocking {
        val library = library(limit = 1500)
        val s = library.snapshots
        library.putPage("album/bad", "{not json")
        assertNull(s.album("bad"))
        repeat(20) { i -> s.putAlbum(AlbumPage(Album("x$i", "Album $i"), songs)) }
        library.use(Unit) { db -> assertTrue(db.pages().totalBytes() <= 1500) }
        assertNotNull(s.album("x19"))
        assertNull(s.album("x0"))
    }

    /** The LRU bound as before: a read keeps a page, the least recently read or written goes first. */
    @Test fun aReadKeepsAPageInTheLru() = runBlocking {
        var now = 0L
        val library = LibraryStore(dbFile, { path ->
            LibraryDatabase.open(Room.databaseBuilder<LibraryDatabase>(path), BundledSQLiteDriver(), Dispatchers.IO)
        }, nowMs = { ++now }, pageLimitBytes = 1_200).also { opened += it }
        library.putPage("album/old", "o".repeat(500))
        library.putPage("album/mid", "m".repeat(500))
        assertNotNull(library.page("album/old"))
        library.awaitWrites()
        library.putPage("album/new", "n".repeat(500))
        assertNotNull("read last, so kept", library.page("album/old"))
        assertNull("least recently used", library.page("album/mid"))
        assertNotNull(library.page("album/new"))
    }

    /** Review r2 P2: the stale mark of an edited playlist is persisted and ends when the server's contents are saved. */
    @Test fun aStalePlaylistMarkSurvivesARestartAndEndsWithTheNextSave() = runBlocking {
        val first = library()
        first.snapshots.putPlaylist(PlaylistPage(Playlist("p1", "Mix", 2), songs))
        assertFalse(first.snapshots.playlistIsStale("p1"))
        first.snapshots.markPlaylistStale("p1")
        first.close()
        val restarted = library().snapshots
        assertTrue(restarted.playlistIsStale("p1"))
        assertEquals("the old contents stay for offline use", songs, restarted.playlist("p1")!!.songs)
        restarted.putPlaylist(PlaylistPage(Playlist("p1", "Mix", 1), songs.take(1)))
        assertFalse(restarted.playlistIsStale("p1"))
    }

    private fun kept(key: String) = KeptItem(key, songs.map { KeptTrack(it.id) }, "Kept")
    private suspend fun pinned(library: LibraryStore) = library.use(emptySet<String>()) { db -> db.pages().byAge().filter { it.pinned }.map { it.key }.toSet() }
    private suspend fun setKeys(library: LibraryStore) = library.use(emptySet<String>()) { db -> db.cache().setKeys().toSet() }

    /** The pins follow the kept sets in the same transaction (CacheIndexDb), so a set and its page never disagree. */
    @Test fun pinsChangeInTheSameTransactionAsTheKeptSets() = runBlocking {
        val library = library()
        val s = library.snapshots
        val index = CacheIndexDb { library }
        s.putAlbum(AlbumPage(album, songs))
        s.putPlaylist(PlaylistPage(Playlist("p1", "Mix", 2), songs))
        index.save("w", null, CacheSnapshot(sets = listOf(kept("album/a1"))))
        assertEquals(setOf("album/a1"), pinned(library))
        // A page written after its set was kept is pinned at once.
        s.putAlbum(AlbumPage(Album("a2", "Second"), songs))
        val first = CacheSnapshot(sets = listOf(kept("album/a1"), kept("album/a2"), kept("playlist/p1")))
        index.save("w", CacheSnapshot(sets = listOf(kept("album/a1"))), first)
        assertEquals(setOf("album/a1", "album/a2", "playlist/p1"), pinned(library))
        assertEquals(setKeys(library), pinned(library))
        assertEquals(listOf("a1", "a2"), s.albums()!!.map { it.id }.sorted())
        // Unkept: unpinned in the same write.
        index.save("w", first, CacheSnapshot(sets = listOf(kept("playlist/p1"))))
        assertEquals(setOf("playlist/p1"), pinned(library))
        // Another writer (full rewrite) also brings the pins into line.
        library.use(Unit) { it.pages().put("album/a1", "{}", 2, 0) }
        library.use(Unit) { db -> db.useWriterConnection { it.usePrepared("UPDATE pages SET pinned = 1") { s -> s.step() } } }
        index.save("other", null, CacheSnapshot(sets = listOf(kept("album/a2"))))
        assertEquals(setOf("album/a2"), pinned(library))
    }

    /** A failed set write changes no pin either: both are one transaction. */
    @Test fun aSetWriteThatFailsLeavesThePinsAsTheyWere() = runBlocking {
        val library = library()
        library.snapshots.putAlbum(AlbumPage(album, songs))
        val index = CacheIndexDb { library }
        index.save("w", null, CacheSnapshot(sets = listOf(kept("album/a1"))))
        // A trigger makes the next pin change fail after the sets were written in the same transaction.
        library.use(Unit) { db -> db.useWriterConnection { it.usePrepared(
            "CREATE TRIGGER no_unpin BEFORE UPDATE OF pinned ON pages BEGIN SELECT RAISE(ABORT, 'test'); END") { s -> s.step() } } }
        assertThrows(Exception::class.java) {
            runBlocking { index.save("w", CacheSnapshot(sets = listOf(kept("album/a1"))), CacheSnapshot()) }
        }
        assertEquals(setOf("album/a1"), setKeys(library))
        assertEquals(setOf("album/a1"), pinned(library))
    }

    /** Pinned pages never trim and do not count against the limit. */
    @Test fun pinnedPagesStayPastTheLimit() = runBlocking {
        val library = library(limit = 1_500)
        val s = library.snapshots
        CacheIndexDb { library }.save("w", null, CacheSnapshot(sets = listOf(kept("album/a1"))))
        s.putAlbum(AlbumPage(album, songs))
        repeat(20) { i -> s.putAlbum(AlbumPage(Album("x$i", "Album $i"), songs)) }
        assertNotNull(s.album("a1"))
    }

    /** A catalogue rebuild (what a migration runs, [LibraryDatabase.CLEAR_REBUILDABLE]) keeps the pinned pages. */
    @Test fun aPinnedPageSurvivesACatalogueRebuild() = runBlocking {
        val library = library()
        val s = library.snapshots
        CacheIndexDb { library }.save("w", null, CacheSnapshot(sets = listOf(kept("album/a1"))))
        s.putAlbum(AlbumPage(album, songs))
        s.putArtists(listOf(Artist("r1", "Zoë Example")))
        library.use(Unit) { db -> db.useWriterConnection { c -> LibraryDatabase.CLEAR_REBUILDABLE.forEach { c.usePrepared(it) { s -> s.step() } } } }
        assertEquals(songs, s.album("a1")!!.songs)
        assertNull(s.artists())
        assertEquals(setOf("album/a1"), setKeys(library))
    }
}
