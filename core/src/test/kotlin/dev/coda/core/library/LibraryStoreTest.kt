package dev.coda.core.library

import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.coda.core.accent.AccentSelection
import dev.coda.core.accent.AccentSelectionKind
import dev.coda.core.accent.ArtworkColor
import dev.coda.core.accent.ColourAlgorithms
import dev.coda.core.cache.CachedTrack
import dev.coda.core.cache.KeptItem
import dev.coda.core.cache.KeptTrack
import dev.coda.core.cache.TrackMeta
import dev.coda.core.playback.StreamFormat
import dev.coda.core.subsonic.Album
import dev.coda.core.subsonic.AlbumPage
import dev.coda.core.subsonic.Artist
import dev.coda.core.subsonic.ArtistReference
import dev.coda.core.subsonic.Playlist
import dev.coda.core.subsonic.PlaylistPage
import dev.coda.core.subsonic.Song
import dev.coda.store.AccentRow
import dev.coda.store.LibraryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** `library.db` on the JVM (bundled SQLite, real files): the catalogue collector, page copies, colours, damage. */
class LibraryStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val opened = mutableListOf<LibraryStore>()
    private val dbFile by lazy { File(tmp.root, "library/acct/library.db") }

    private fun build(path: String): LibraryDatabase =
        LibraryDatabase.open(Room.databaseBuilder<LibraryDatabase>(path), BundledSQLiteDriver(), Dispatchers.IO)

    private fun store(limit: Long = 20L * 1024 * 1024) = LibraryStore(dbFile, ::build, pageLimitBytes = limit).also { opened += it }

    @After fun closeAll() = opened.forEach { it.close() }

    // Synthetic library.
    private val artistA = Artist("ar-1", "Nova Lights", albumCount = 2, coverArt = "ar-1-img", sortName = "nova lights")
    private val feat = ArtistReference("ar-9", "Guest Singer")
    private val albumA = Album("al-1", "First Light", artist = "Nova Lights", artistId = "ar-1", artists = listOf(ArtistReference("ar-1", "Nova Lights")),
        coverArt = "al-1-cover", songCount = 2, year = 2001)
    private val songs = listOf(
        Song("s-1", "Dawn", album = "First Light", artist = "Nova Lights", albumId = "al-1", artistId = "ar-1", track = 1, discNumber = 1),
        Song("s-2", "Dusk", album = "First Light", artist = "Nova Lights", albumId = "al-1", artistId = "ar-1", track = 2, discNumber = 1,
            artists = listOf(ArtistReference("ar-1", "Nova Lights"), feat)),
    )
    private val pageA = AlbumPage(albumA, songs)
    private val albumB = Album("al-2", "Søvnløs Natt", artist = "Nova Lights", artistId = "ar-1", coverArt = "al-2-cover")
    private val pageB = AlbumPage(albumB, listOf(Song("s-3", "Straße", album = albumB.name, albumId = "al-2", artistId = "ar-1", artist = "Nova Lights")))

    private fun cached(id: String, albumId: String?, title: String = id) =
        CachedTrack(id, "$id.orig", StreamFormat.RAW, 10, 0, meta = TrackMeta(title, "Nova Lights", "Album $albumId", albumId, albumId, 100, 1, 1))

    private fun json(value: AlbumPage) = CatalogueCollector.json.encodeToString(AlbumSnapshot.serializer(), AlbumSnapshot(value.album, value.songs))
    private fun artistsJson() = CatalogueCollector.json.encodeToString(ArtistsSnapshot.serializer(), ArtistsSnapshot(listOf(artistA)))

    private class Server(vararg pages: AlbumPage) {
        val pages = pages.associateBy { it.album.id }
        val asked = mutableListOf<String>()
        var up = true
        /** Ids the server answers "not found" (Subsonic error 70) for. */
        val gone = mutableSetOf<String>()
        suspend fun album(id: String): AlbumPage {
            asked += id
            if (id in gone) throw dev.coda.core.subsonic.SubsonicError.NotFound()
            if (!up) throw java.io.IOException()
            return pages.getValue(id)
        }
    }

    @Test fun albumFromSavedPageNeedsNoRequest() = runBlocking {
        val store = store()
        store.mirrorPage("album/al-1", json(pageA))
        store.mirrorPage("artists", artistsJson())
        store.awaitWrites()
        val server = Server()
        val result = CatalogueCollector(store, server::album).collect(listOf(cached("s-1", "al-1")), emptyList()) { true }
        assertEquals(1, result.fromPages)
        assertEquals(emptyList<String>(), server.asked)
        store.use(Unit) { db ->
            val c = db.catalogue()
            val album = c.album("al-1")!!
            assertFalse(album.minimal)
            assertEquals("ar-1", album.artistId)
            assertEquals("[\"s-1\",\"s-2\"]", album.songIds)
            // Every track carries its path: album id and artist id.
            assertEquals("al-1", c.song("s-2")!!.albumId)
            assertEquals("ar-1", c.song("s-2")!!.artistId)
            val artist = c.artist("ar-1")!!
            assertFalse(artist.trackOnly)
            assertEquals("ar-1-img", artist.imageId)
            // The "feat." artist is known by id and name only.
            assertEquals("Guest Singer", c.artist("ar-9")!!.name)
            assertTrue(c.artist("ar-9")!!.trackOnly)
            val images = c.images().map { "${it.kind}:${it.ownerId}:${it.artId}" }
            assertEquals(listOf("album:al-1:al-1-cover", "artist:ar-1:ar-1-img"), images)
        }
    }

    @Test fun missingAlbumIsFetchedOnceAndItsPageSaved() = runBlocking {
        val store = store()
        val server = Server(pageA)
        val collector = CatalogueCollector(store, server::album)
        assertEquals(1, collector.collect(listOf(cached("s-1", "al-1"), cached("s-2", "al-1")), emptyList()) { true }.fetched)
        assertEquals(1, collector.collect(listOf(cached("s-1", "al-1"), cached("s-2", "al-1")), emptyList()) { true }.let { server.asked.size })
        assertNotNull(store.page("album/al-1"))
    }

    /** A screen saving a minimal entry's album page (play from a list, search) signals the catalogue; the next pass upgrades it without a request. */
    @Test fun savingTheAlbumPageOfAMinimalEntrySignalsAndThePassUpgradesIt() = runBlocking {
        val store = store()
        val server = Server(pageA)
        val collector = CatalogueCollector(store, server::album)
        val records = listOf(cached("s-1", "al-1"))
        collector.collect(records, emptyList()) { false }
        val before = store.catalogueAlbumPageSaves.value
        store.snapshots.putAlbum(pageB)
        assertEquals("no catalogue entry: no signal", before, store.catalogueAlbumPageSaves.value)
        store.snapshots.putAlbum(pageA)
        assertEquals("minimal entry: signal", before + 1, store.catalogueAlbumPageSaves.value)
        val result = collector.collect(records, emptyList()) { false }
        assertEquals(1, result.fromPages)
        assertEquals(0, server.asked.size)
        store.use(Unit) { db -> assertFalse(db.catalogue().album("al-1")!!.minimal) }
        store.snapshots.putAlbum(pageA)
        assertEquals("full entry: no signal", before + 1, store.catalogueAlbumPageSaves.value)
    }

    /** A full entry follows a newly saved page that differs (rename, a track added, one removed) without leaving the catalogue. */
    @Test fun aChangedAlbumPageUpdatesTheFullEntryTitleSongsAndTotal() = runBlocking {
        val store = store()
        val server = Server(pageA)
        val collector = CatalogueCollector(store, server::album)
        val records = listOf(cached("s-1", "al-1"))
        assertEquals(1, collector.collect(records, emptyList()) { true }.fetched)
        val before = store.catalogueAlbumPageSaves.value
        val extra = Song("s-4", "Noon", album = "First Light (Deluxe)", artist = "Nova Lights", albumId = "al-1", artistId = "ar-1", track = 3, discNumber = 1)
        store.snapshots.putAlbum(AlbumPage(albumA.copy(name = "First Light (Deluxe)", songCount = 3), songs + extra))
        assertEquals("changed full entry: signal", before + 1, store.catalogueAlbumPageSaves.value)
        val result = collector.collect(records, emptyList()) { true }
        assertEquals(1, result.refreshed)
        assertEquals("no request", 1, server.asked.size)
        store.use(Unit) { db -> db.catalogue().album("al-1")!!.let { row ->
            assertFalse(row.minimal)
            assertEquals("First Light (Deluxe)", row.name)
            assertEquals(listOf("s-1", "s-2", "s-4"), CatalogueCollector.json.decodeFromString(kotlinx.serialization.serializer<List<String>>(), row.songIds))
        } }
        assertEquals(OfflineCatalogue.Count(1, 3), OfflineCatalogue(store).album("al-1", setOf("s-1"))!!.second)
        // A track removed on the server (and not on the phone) leaves; nothing changed: no signal, no work.
        store.snapshots.putAlbum(AlbumPage(albumA.copy(songCount = 2), listOf(songs[0], extra)))
        assertEquals(1, collector.collect(records, emptyList()) { true }.refreshed)
        store.use(Unit) { db -> assertNull(db.catalogue().song("s-2")); assertNotNull(db.catalogue().song("s-4")) }
        val settled = store.catalogueAlbumPageSaves.value
        store.snapshots.putAlbum(AlbumPage(albumA.copy(songCount = 2), listOf(songs[0], extra)))
        assertEquals(settled, store.catalogueAlbumPageSaves.value)
        assertEquals(0, collector.collect(records, emptyList()) { true }.refreshed)
    }

    /** R1/O2: a track the server dropped that is still on the phone stays in its album (row, member list, page, count). */
    @Test fun aRefreshKeepsADroppedTrackThatIsStillOnThePhone() = runBlocking {
        val store = store()
        val collector = CatalogueCollector(store, Server(pageA)::album)
        val records = listOf(cached("s-1", "al-1"), cached("s-2", "al-1"))
        assertEquals(1, collector.collect(records, emptyList()) { true }.fetched)
        store.snapshots.putAlbum(AlbumPage(albumA.copy(name = "First Light (Edit)", songCount = 1), listOf(songs[0])))
        assertEquals(1, collector.collect(records, emptyList()) { true }.refreshed)
        store.use(Unit) { db ->
            assertNotNull("song row kept", db.catalogue().song("s-2"))
            assertEquals(listOf("s-1", "s-2"), CatalogueCollector.json.decodeFromString(kotlinx.serialization.serializer<List<String>>(), db.catalogue().album("al-1")!!.songIds))
        }
        val (page, count) = OfflineCatalogue(store).album("al-1", setOf("s-1", "s-2"))!!
        assertEquals("First Light (Edit)", page.album.name)
        assertEquals(listOf("s-1", "s-2"), page.songs.map { it.id })
        assertEquals(OfflineCatalogue.Count(2, 2), count)
        assertEquals(listOf("al-1"), OfflineCatalogue(store).shelf(setOf("s-1", "s-2")).completeOnly().albums.map { it.id })
    }

    /**
     * A changed page saved just before the process died (no collection in between) still reaches the
     * catalogue: the first pass of the reopened store compares the saved album pages with their full entries.
     */
    @Test fun aChangedPageSavedBeforeTheProcessDiedRefreshesTheEntryAfterReopening() = runBlocking {
        val records = listOf(cached("s-1", "al-1"))
        val first = store()
        assertEquals(1, CatalogueCollector(first, Server(pageA)::album).collect(records, emptyList()) { true }.fetched)
        val extra = Song("s-4", "Noon", album = "First Light (Deluxe)", artist = "Nova Lights", albumId = "al-1", artistId = "ar-1", track = 3, discNumber = 1)
        first.snapshots.putAlbum(AlbumPage(albumA.copy(name = "First Light (Deluxe)", songCount = 3), songs + extra))
        first.awaitWrites()
        first.close()
        val reopened = store()
        val server = Server(pageA)
        val collector = CatalogueCollector(reopened, server::album)
        assertEquals(1, collector.collect(records, emptyList()) { true }.refreshed)
        assertEquals("no request", 0, server.asked.size)
        reopened.use(Unit) { db -> assertEquals("First Light (Deluxe)", db.catalogue().album("al-1")!!.name) }
        assertEquals(OfflineCatalogue.Count(1, 3), OfflineCatalogue(reopened).album("al-1", setOf("s-1"))!!.second)
        // Settled: the next pass does no work, and a reopen with nothing changed neither.
        assertEquals(0, collector.collect(records, emptyList()) { true }.refreshed)
        reopened.close()
        assertEquals(0, CatalogueCollector(store(), server::album).collect(records, emptyList()) { true }.refreshed)
    }

    /** A refresh whose ingestion failed is taken again at the next pass, not dropped. */
    @Test fun aFailedRefreshIsTakenAgainAtTheNextPass() = runBlocking {
        val store = store()
        var failing = false
        val collector = CatalogueCollector(store, Server(pageA)::album, beforeAlbumRow = { if (failing) throw java.io.IOException("disk full") })
        val records = listOf(cached("s-1", "al-1"))
        assertEquals(1, collector.collect(records, emptyList()) { true }.fetched)
        store.snapshots.putAlbum(AlbumPage(albumA.copy(name = "First Light (Deluxe)", songCount = 3), songs))
        failing = true
        assertEquals(0, collector.collect(records, emptyList()) { true }.refreshed)
        store.use(Unit) { db -> assertEquals("First Light", db.catalogue().album("al-1")!!.name) }
        failing = false
        assertEquals(1, collector.collect(records, emptyList()) { true }.refreshed)
        store.use(Unit) { db -> assertEquals("First Light (Deluxe)", db.catalogue().album("al-1")!!.name) }
    }

    /**
     * The reopened store's compare walks the albums in chunks, so a screen's read (Home's first
     * paint, the car's offline browse) gets the store between two chunks instead of waiting for the whole library.
     */
    @Test fun theReconcileAfterReopeningLetsOtherReadsInBetweenChunks() = runBlocking {
        val pages = (0..100).map { i -> AlbumPage(Album("al-$i", "Album $i", artist = "Nova Lights", artistId = "ar-1", songCount = 1),
            listOf(Song("t-$i", "Track $i", album = "Album $i", artist = "Nova Lights", albumId = "al-$i", artistId = "ar-1", track = 1, discNumber = 1))) }
        val records = pages.map { cached(it.songs[0].id, it.album.id) }
        val first = store()
        assertEquals(101, CatalogueCollector(first, Server(*pages.toTypedArray())::album).collect(records, emptyList()) { true }.fetched)
        for (i in listOf(0, 100)) first.snapshots.putAlbum(pages[i].copy(album = pages[i].album.copy(name = "Album $i (Remaster)")))
        first.awaitWrites()
        first.close()
        val reopened = store()
        var ingests = 0
        var readDone = false
        var readBetween: Boolean? = null
        val reader = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
        val collector = CatalogueCollector(reopened, Server()::album, beforeAlbumRow = {
            ingests++
            // The first changed album: a screen asks for a page now (it waits for the store); at the last one, it must have had it.
            if (ingests == 1) reader.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { reopened.use(Unit) { readDone = true } }
            else readBetween = readDone
        })
        assertEquals(2, collector.collect(records, emptyList()) { false }.refreshed)
        assertEquals("the read got the store between two chunks", true, readBetween)
        reopened.use(Unit) { db -> assertEquals("Album 100 (Remaster)", db.catalogue().album("al-100")!!.name) }
    }

    /** A song whose own album id is another album's still matches by id, so nothing is re-taken at every start. */
    @Test fun aSongWithAnotherAlbumIdIsNoDifferenceAfterReopening() = runBlocking {
        val moved = AlbumPage(albumA, listOf(songs[0].copy(albumId = "al-other"), songs[1]))
        val records = listOf(cached("s-1", "al-1"))
        val first = store()
        assertEquals(1, CatalogueCollector(first, Server(moved)::album).collect(records, emptyList()) { true }.fetched)
        first.awaitWrites()
        first.close()
        assertEquals(0, CatalogueCollector(store(), Server(moved)::album).collect(records, emptyList()) { true }.refreshed)
    }

    /** A pass cancelled while it takes a changed page gives the work back, as a failed one does. */
    @Test fun aCancelledRefreshIsTakenAgainAtTheNextPass() = runBlocking {
        val store = store()
        var cancel = false
        val collector = CatalogueCollector(store, Server(pageA)::album, beforeAlbumRow = { if (cancel) throw kotlinx.coroutines.CancellationException("stopped") })
        val records = listOf(cached("s-1", "al-1"))
        assertEquals(1, collector.collect(records, emptyList()) { true }.fetched)
        store.snapshots.putAlbum(AlbumPage(albumA.copy(name = "First Light (Deluxe)", songCount = 3), songs))
        cancel = true
        val stopped = try { collector.collect(records, emptyList()) { true }; false } catch (_: kotlinx.coroutines.CancellationException) { true }
        assertTrue("cancellation passes through", stopped)
        cancel = false
        assertEquals(1, collector.collect(records, emptyList()) { true }.refreshed)
        store.use(Unit) { db -> assertEquals("First Light (Deluxe)", db.catalogue().album("al-1")!!.name) }
    }

    /** O1: a page that differs only in play data (a play) is not a change. */
    @Test fun aPageThatDiffersOnlyInPlayDataIsNoChange() = runBlocking {
        val store = store()
        val collector = CatalogueCollector(store, Server(pageA)::album)
        val records = listOf(cached("s-1", "al-1"))
        collector.collect(records, emptyList()) { true }
        val before = store.catalogueAlbumPageSaves.value
        store.snapshots.putAlbum(AlbumPage(albumA.copy(playCount = 7, played = "2026-11-06T10:00:00Z"), songs))
        assertEquals(before, store.catalogueAlbumPageSaves.value)
        assertEquals(0, collector.collect(records, emptyList()) { true }.refreshed)
    }

    @Test fun offlineGivesMinimalEntriesThenTheServerReplacesThem() = runBlocking {
        val store = store()
        val server = Server(pageA)
        var now = 0L
        val collector = CatalogueCollector(store, server::album, nowMs = { now }, retryMs = 1_000)
        val result = collector.collect(listOf(cached("s-1", "al-1", "Dawn")), emptyList()) { false }
        assertEquals(0, server.asked.size)
        assertEquals(1, result.minimal)
        assertEquals(1, result.pending)
        store.use(Unit) { db ->
            assertTrue(db.catalogue().album("al-1")!!.minimal)
            assertEquals("Dawn", db.catalogue().song("s-1")!!.title)
        }
        // Reachable but failing: still minimal, never thrown.
        server.up = false
        assertEquals(1, collector.collect(listOf(cached("s-1", "al-1")), emptyList()) { true }.failed)
        server.up = true
        // Not asked again before the retry time; still pending.
        assertEquals(1, collector.collect(listOf(cached("s-1", "al-1")), emptyList()) { true }.pending)
        assertEquals(1, server.asked.size)
        now = 1_000
        collector.collect(listOf(cached("s-1", "al-1")), emptyList()) { true }
        store.use(Unit) { db ->
            assertFalse(db.catalogue().album("al-1")!!.minimal)
            assertFalse(db.catalogue().song("s-1")!!.minimal)
            assertEquals("ar-1", db.catalogue().song("s-1")!!.artistId)
        }
    }

    @Test fun keptAlbumSetWithPendingTracksIsCollected() = runBlocking {
        val store = store()
        val server = Server(pageA)
        val set = KeptItem("album/al-1", songs.map { KeptTrack(it.id) }, "First Light")
        CatalogueCollector(store, server::album).collect(emptyList(), listOf(set)) { true }
        assertEquals(listOf("al-1"), server.asked)
        store.use(Unit) { assertEquals(2, it.catalogue().songsOf("al-1").size) }
    }

    @Test fun keptPlaylistFromItsSetOrItsPage() = runBlocking {
        val store = store()
        val server = Server(pageA)
        val set = KeptItem("playlist/pl-1", songs.map { KeptTrack(it.id, meta = TrackMeta.from(it)) }, "Road trip")
        val collector = CatalogueCollector(store, server::album)
        collector.collect(emptyList(), listOf(set)) { true }
        store.use(Unit) { assertEquals("Road trip", it.catalogue().playlist("pl-1")!!.name) }
        val page = PlaylistPage(Playlist("pl-1", "Road Trip 2", owner = "user", coverArt = "pl-1-cover"), songs.reversed())
        store.mirrorPage("playlist/pl-1", CatalogueCollector.json.encodeToString(PlaylistSnapshot.serializer(), PlaylistSnapshot(page.playlist, page.songs)))
        collector.collect(emptyList(), listOf(set)) { true }
        store.use(Unit) { db ->
            val row = db.catalogue().playlist("pl-1")!!
            assertEquals("Road Trip 2", row.name)
            assertEquals("[\"s-2\",\"s-1\"]", row.memberIds)
            assertTrue(db.catalogue().images().any { it.kind == "playlist" && it.artId == "pl-1-cover" })
        }
        // Un-kept: the playlist leaves the catalogue.
        collector.collect(emptyList(), emptyList()) { true }
        store.use(Unit) { assertNull(it.catalogue().playlist("pl-1")) }
    }

    @Test fun lastReferenceGoneLeavesTheCatalogueButKeepsThePage() = runBlocking {
        val store = store()
        val server = Server(pageA, pageB)
        val collector = CatalogueCollector(store, server::album)
        collector.collect(listOf(cached("s-1", "al-1"), cached("s-3", "al-2")), emptyList()) { true }
        val result = collector.collect(listOf(cached("s-3", "al-2")), emptyList()) { true }
        assertEquals(1, result.removedAlbums)
        store.use(Unit) { db ->
            val c = db.catalogue()
            assertNull(c.album("al-1"))
            assertNull(c.song("s-1"))
            assertNull(c.artist("ar-9")) // only named by a song of the album that left
            assertNotNull(c.artist("ar-1"))
            assertFalse(c.images().any { it.ownerId == "al-1" })
        }
        assertNotNull(store.page("album/al-1"))
    }

    @Test fun ordinaryPagesTrimButCataloguePagesStay() = runBlocking {
        val store = store(limit = 3_000)
        val server = Server(pageA)
        CatalogueCollector(store, server::album).collect(listOf(cached("s-1", "al-1")), emptyList()) { true }
        repeat(10) { store.mirrorPage("artist/x$it", "x".repeat(500)) }
        store.awaitWrites()
        assertNotNull(store.page("album/al-1"))
        assertNull(store.page("artist/x0"))
        assertNotNull(store.page("artist/x9"))
        store.use(Unit) { db -> assertTrue(ordinaryBytes(db, store) <= 3_000) }
    }

    private suspend fun ordinaryBytes(db: LibraryDatabase, store: LibraryStore): Long {
        val protected = store.protectedKeys(db, refresh = true)
        return db.pages().byAge().filter { it.key !in protected }.sumOf { it.bytes.toLong() }
    }

    /** Catalogue pages over the whole budget do not push every ordinary page out. */
    @Test fun bigCatalogueDoesNotEmptyTheOrdinaryPages() = runBlocking {
        val store = store(limit = 3_000)
        val server = Server(pageA, pageB)
        // The two album pages alone are over the limit once padded; ordinary pages still get their own 3,000 bytes.
        val records = listOf(cached("s-1", "al-1"), cached("s-3", "al-2"))
        CatalogueCollector(store, server::album).collect(records, emptyList()) { true }
        store.mirrorPage("album/al-1", json(pageA) + " ".repeat(4_000))
        store.mirrorPage("album/al-2", json(pageB) + " ".repeat(4_000))
        repeat(4) { store.mirrorPage("artist/x$it", "x".repeat(500)) }
        store.awaitWrites()
        store.use(Unit) { assertTrue(it.pages().totalBytes() > 8_000) }
        assertNotNull(store.page("album/al-1"))
        assertNotNull(store.page("album/al-2"))
        for (i in 0 until 4) assertNotNull("artist/x$i", store.page("artist/x$i"))
        // Past its own budget the ordinary side trims, oldest first.
        repeat(4) { store.mirrorPage("artist/y$it", "y".repeat(500)) }
        store.awaitWrites()
        assertNull(store.page("artist/x0"))
        assertNotNull(store.page("artist/y3"))
        assertNotNull(store.page("album/al-1"))
    }

    /** A failure inside an album's ingest leaves neither a full album nor orphan songs. */
    @Test fun failedAlbumIngestLeavesNothingHalfWritten() = runBlocking {
        val store = store()
        val server = Server(pageA)
        val collector = CatalogueCollector(store, server::album, beforeAlbumRow = { throw java.io.IOException("disk full") })
        collector.collect(listOf(cached("s-1", "al-1")), emptyList()) { true }
        store.use(Unit) { db ->
            val c = db.catalogue()
            assertTrue("the album is not full", c.fullAlbumIds().isEmpty())
            assertNull("no song of the failed ingest is full", c.song("s-2"))
        }
        // The next run (no failure) completes it.
        val ok = CatalogueCollector(store, server::album)
        ok.collect(listOf(cached("s-1", "al-1")), emptyList()) { true }
        store.use(Unit) { db -> assertEquals(listOf("al-1"), db.catalogue().fullAlbumIds()) }
    }

    /** Three failures in a row end the run; a run asks at most its cap. */
    @Test fun runStopsAfterThreeFailuresAndIsCapped() = runBlocking {
        val store = store()
        var now = 1_000L
        val ids = (1..10).map { "x$it" }
        val server = Server()
        server.up = false
        val records = ids.map { cached("s-$it", it) }
        val collector = CatalogueCollector(store, server::album, nowMs = { now }, retryMs = 5_000)
        val r1 = collector.collect(records, emptyList()) { true }
        assertEquals(3, server.asked.size)
        assertEquals(3, r1.failed)
        assertEquals(6_000L, r1.retryAtMs)
        assertFalse(r1.more)
        // Back-off over: never-tried albums are asked before the retries; three first-time failures end the run.
        now = 7_000
        collector.collect(records, emptyList()) { true }
        assertEquals(listOf("x1", "x2", "x3", "x4", "x5", "x6"), server.asked)

        val capped = Server(*ids.map { id -> AlbumPage(Album(id, id, artist = "A"), emptyList()) }.toTypedArray())
        val c2 = CatalogueCollector(store, capped::album, maxFetchesPerRun = 4)
        val r2 = c2.collect(records, emptyList()) { true }
        assertEquals(4, r2.fetched)
        assertTrue(r2.more)
        assertEquals(6, r2.pending)
        val r3 = c2.collect(records, emptyList()) { true }
        assertEquals(4, r3.fetched)
        assertEquals(8, capped.asked.size)
    }

    /** Retries have their own budget of three, so a server that fails every getAlbum costs at most three requests per run. */
    @Test fun aRunWhereEveryMissingAlbumIsARetryStopsAfterThreeFailures() = runBlocking {
        val store = store()
        var now = 1_000L
        val ids = (1..4).map { "x$it" }
        val server = Server()
        server.up = false
        val records = ids.map { cached("s-$it", it) }
        val collector = CatalogueCollector(store, server::album, nowMs = { now }, retryMs = 5_000)
        collector.collect(records, emptyList()) { true }   // x1..x3 fail
        now = 7_000
        collector.collect(records, emptyList()) { true }   // x4 is new and fails; then three retries fail
        now = 20_000
        server.asked.clear()
        val r = collector.collect(records, emptyList()) { true }   // every album is a retry now
        assertEquals(3, server.asked.size)
        assertEquals(3, r.failed)
        assertEquals(4, r.pending)
    }

    @Test fun neverTriedAlbumsAreAskedBeforeRetries() = runBlocking {
        val store = store()
        var now = 1_000L
        val server = Server(pageA, pageB)
        server.up = false
        val collector = CatalogueCollector(store, server::album, nowMs = { now }, retryMs = 5_000)
        collector.collect(listOf(cached("s-1", "al-1")), emptyList()) { true }   // al-1 fails
        now = 7_000
        server.up = true; server.asked.clear()
        val r = collector.collect(listOf(cached("s-1", "al-1"), cached("s-3", "al-2")), emptyList()) { true }
        assertEquals(listOf("al-2", "al-1"), server.asked)
        assertEquals(2, r.fetched)
    }

    /** Albums the server says are not found (error 70) are gone, not a half-down server: they never stop a run. */
    @Test fun notFoundAlbumsNeverStopTheRunAndTheRestIsFetched() = runBlocking {
        val store = store()
        val server = Server(pageA)
        server.gone += listOf("g1", "g2", "g3", "g4", "g5")
        val records = listOf("g1", "g2", "g3", "g4", "g5", "al-1").map { cached("s-$it", it) }
        val r = CatalogueCollector(store, server::album).collect(records, emptyList()) { true }
        assertEquals(6, server.asked.size)
        assertEquals(5, r.failed)
        assertEquals(1, r.fetched)
    }

    /** Failed or unreachable albums are not "due again" until their back-off ends or the server is back. */
    @Test fun pendingAlbumsDoNotKeepPassesBusy() = runBlocking {
        val store = store()
        var now = 0L
        val server = Server()
        server.up = false
        val collector = CatalogueCollector(store, server::album, nowMs = { now }, retryMs = 10_000)
        val records = listOf(cached("s-1", "gone"))
        val failed = collector.collect(records, emptyList()) { true }
        assertEquals(1, failed.pending)
        assertFalse(failed.dueAgain(cacheChanged = false, usable = true, now = 5_000))
        assertTrue(failed.dueAgain(cacheChanged = false, usable = true, now = 10_000))
        assertTrue(failed.dueAgain(cacheChanged = true, usable = true, now = 5_000))

        val offline = collector.collect(listOf(cached("s-2", "other")), emptyList()) { false }
        assertTrue(offline.needsServer)
        assertFalse(offline.dueAgain(cacheChanged = false, usable = false, now = 5_000))
        assertTrue(offline.dueAgain(cacheChanged = false, usable = true, now = 5_000))
        // A fully collected cache is never due.
        val done = Server(pageA)
        val clean = CatalogueCollector(store, done::album).collect(listOf(cached("s-1", "al-1")), emptyList()) { true }
        assertFalse(clean.dueAgain(cacheChanged = false, usable = true, now = Long.MAX_VALUE))
    }

    /** A close() with a waiter does not leave awaitWrites hanging. */
    @Test(timeout = 10_000) fun awaitWritesReturnsAfterClose() = runBlocking {
        val store = store()
        store.close()
        store.awaitWrites()
    }

    @Test fun searchUsesCoreFoldAndWordPrefix() = runBlocking {
        val store = store()
        val collector = CatalogueCollector(store, Server(pageA, pageB)::album)
        collector.collect(listOf(cached("s-1", "al-1"), cached("s-3", "al-2")), emptyList()) { true }
        assertEquals(listOf("al-2"), collector.search("SOVN").albums.map { it.id })
        assertEquals(listOf("s-3"), collector.search("stra").songs.map { it.id })
        assertEquals(listOf("ar-1"), collector.search("nova").artists.map { it.id }.filter { it == "ar-1" })
        assertEquals(0, collector.search("ight").albums.size) // word prefix, not substring
    }

    @Test fun catalogueSurvivesReopening() = runBlocking {
        val first = store()
        CatalogueCollector(first, Server(pageA)::album).collect(listOf(cached("s-1", "al-1")), emptyList()) { true }
        first.close()
        val second = store()
        val server = Server(pageA)
        CatalogueCollector(second, server::album).collect(listOf(cached("s-1", "al-1")), emptyList()) { true }
        assertEquals(emptyList<String>(), server.asked)
        second.use(Unit) { assertEquals("First Light", it.catalogue().album("al-1")!!.name) }
    }

    @Test fun damagedFileStartsFreshAndLeavesMusicAlone() = runBlocking {
        val music = File(tmp.root, "music/acct/abc.orig").apply { parentFile.mkdirs(); writeBytes(ByteArray(64) { 7 }) }
        dbFile.parentFile.mkdirs()
        dbFile.writeBytes(ByteArray(8192) { (it * 31).toByte() })
        val store = store()
        CatalogueCollector(store, Server(pageA)::album).collect(listOf(cached("s-1", "al-1")), emptyList()) { true }
        assertEquals(1, store.rebuilds)
        assertTrue(File(dbFile.path + ".broken").isFile)
        store.use(Unit) { assertNotNull(it.catalogue().album("al-1")) }
        assertTrue(music.isFile)
        assertEquals(64L, music.length())
    }

    @Test fun corruptionFoundLaterRebuildsOnTheNextUse() = runBlocking {
        val store = store()
        CatalogueCollector(store, Server(pageA)::album).collect(listOf(cached("s-1", "al-1")), emptyList()) { true }
        store.close()
        // Overwrite the middle of the file (the header stays valid, the pages do not).
        java.io.RandomAccessFile(dbFile, "rw").use { f -> f.seek(4096); f.write(ByteArray((f.length() - 4096).toInt()) { 0x55 }) }
        val again = store()
        val server = Server(pageA)
        CatalogueCollector(again, server::album).collect(listOf(cached("s-1", "al-1")), emptyList()) { true }
        again.use(Unit) { assertNotNull(it.catalogue().album("al-1")) }
        assertTrue(again.rebuilds >= 1)
    }

    /**
     * The file holds the music cache's index, so only real damage sets it aside. A downgrade (or a missing
     * migration) fails each use and keeps the file as it is; the transaction rethrows.
     */
    @Test fun anotherSchemaVersionIsKeptAndOnlyFailsItsUses() = runBlocking {
        val first = store()
        first.mirrorPage("home", "{}"); first.awaitWrites()
        first.close()
        BundledSQLiteDriver().open(dbFile.path).let { c -> c.execSQL("PRAGMA user_version = ${LibraryDatabase.VERSION + 1}"); c.close() }
        val second = store()
        assertNull(second.page("home"))
        assertTrue(runCatching { second.transact { } }.isFailure)
        assertEquals(0, second.rebuilds)
        assertFalse(File(dbFile.path + ".broken").exists())
        second.close()
        // The data is still there for a build that has the migration (here: the version put back).
        BundledSQLiteDriver().open(dbFile.path).let { c -> c.execSQL("PRAGMA user_version = ${LibraryDatabase.VERSION}"); c.close() }
        assertEquals("{}", store().page("home"))
    }

    /**
     * A file with the current version number but another schema (Room's identity check fails) is
     * kept like a missing migration: no quarantine, no rebuild, the file untouched, and each use fails without a crash.
     */
    @Test fun sameVersionOtherSchemaIsKeptAndOnlyFailsItsUses() = runBlocking {
        dbFile.parentFile.mkdirs()
        BundledSQLiteDriver().open(dbFile.path).let { c ->
            c.execSQL("CREATE TABLE pages (`key` TEXT PRIMARY KEY, body BLOB)")
            c.execSQL("INSERT INTO pages VALUES ('home', x'00')")
            c.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            c.execSQL("INSERT INTO room_master_table VALUES (42, 'not-this-schema')")
            c.execSQL("PRAGMA user_version = ${LibraryDatabase.VERSION}")
            c.close()
        }
        val store = store()
        assertNull(store.page("home"))
        assertTrue(runCatching { store.transact { } }.isFailure)
        store.mirrorPage("home", "{}"); store.awaitWrites()
        assertNull(store.page("home"))
        assertEquals(0, store.rebuilds)
        store.close()
        assertFalse(File(dbFile.path + ".broken").exists())
        // The rows are untouched (opening only switches the journal to WAL), ready for a build with the migration.
        BundledSQLiteDriver().open(dbFile.path).let { c ->
            assertEquals("not-this-schema", c.prepare("SELECT identity_hash FROM room_master_table").use { it.step(); it.getText(0) })
            assertEquals(1L, c.prepare("SELECT count(*) FROM pages WHERE `key` = 'home'").use { it.step(); it.getLong(0) })
            c.close()
        }
    }

    @Test fun cacheWritesSyncEveryCommit() = runBlocking {
        val level = store().transact { db -> db.useReaderConnection { c -> c.usePrepared("PRAGMA synchronous") { it.step(); it.getLong(0) } } }
        assertEquals("FULL", 2L, level)
        val after = store().use(-1L) { db -> db.useWriterConnection { c -> c.usePrepared("PRAGMA synchronous") { it.step(); it.getLong(0) } } }
        assertEquals("NORMAL again for the other writes", 1L, after)
    }

    @Test fun accentsRoundTripForgetAndAlgorithmVersion() = runBlocking {
        val store = store()
        val accent = AccentSelection(ArtworkColor(0.1, 0.2, 0.3), AccentSelectionKind.chromatic)
        store.saveAccent("c-1", 472, accent)
        store.saveAccent("c-2", 472, accent.copy(kind = AccentSelectionKind.neutral))
        store.awaitWrites()
        assertEquals(accent, store.accent("c-1", 472))
        assertNull(store.accent("c-1", 1440))
        assertEquals(setOf("c-1", "c-2"), store.recentAccents(10).map { it.first.first }.toSet())
        store.forgetAccents(listOf("c-1")); store.awaitWrites()
        assertNull(store.accent("c-1", 472))
        // A row of another algorithm version is dropped when the database opens.
        store.use(Unit) { it.colours().upsertAccent(AccentRow("old", 472, ColourAlgorithms.ACCENT + 1, 0.0, 0.0, 0.0, "neutral", 0)) }
        store.close()
        val again = store()
        assertEquals(listOf("c-2"), again.recentAccents(10).map { it.first.first })
        again.use(Unit) { assertNull(it.colours().accent("old", 472, ColourAlgorithms.ACCENT + 1)) }
    }

    @Test fun artistLooksRoundTrip() = runBlocking {
        val store = store()
        store.saveLook(LibraryStore.PhotoLook("ar-1", "ar-1-img", 411, 280, 0xFF112233.toInt(), null))
        store.awaitWrites()
        assertEquals(listOf(LibraryStore.PhotoLook("ar-1", "ar-1-img", 411, 280, 0xFF112233.toInt(), null)), store.recentLooks(10))
        assertEquals(0xFF112233.toInt(), store.look("ar-1-img", 411, 280)?.page)
        assertNull(store.look("ar-1-img", 412, 280))
        store.forgetLooks(listOf("ar-1-img")); store.awaitWrites()
        assertNull(store.look("ar-1-img", 411, 280))
    }
}
