package dev.coda.core.library

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.coda.core.cache.CachedTrack
import dev.coda.core.cache.KeptItem
import dev.coda.core.cache.KeptTrack
import dev.coda.core.cache.TrackMeta
import dev.coda.core.playback.StreamFormat
import dev.coda.core.subsonic.Album
import dev.coda.core.subsonic.Playlist
import dev.coda.core.subsonic.Song
import dev.coda.store.CacheSetRow
import dev.coda.store.LibraryDatabase
import kotlinx.coroutines.Dispatchers
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

/**
 * Offline, kept playlists always show; another playlist shows when the phone knows its song list
 * (its page copy, saved when it was opened online) and, without "Only complete albums", at least one of its songs is here;
 * with it, at least half of them and at least 3. Synthetic library: album al-1 with 30 songs, bundled SQLite.
 */
class OfflinePlaylistsTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var store: LibraryStore
    @After fun close() { if (::store.isInitialized) store.close() }

    private val album = Album("al-1", "Long Player", artist = "Nova Lights", artistId = "ar-1", songCount = 30)
    private val songs = (1..30).map { Song("s-$it", "Song $it", album = album.name, artist = "Nova Lights", albumId = "al-1", artistId = "ar-1", track = it, discNumber = 1) }
    private val byId = songs.associateBy { it.id }
    private fun ids(range: IntRange) = range.map { "s-$it" }
    private fun cached(id: String) = CachedTrack(id, "$id.orig", StreamFormat.RAW, 10, 0, meta = TrackMeta.from(byId.getValue(id)))
    private fun kept(id: String, members: List<String>) = KeptItem("playlist/$id", members.map { KeptTrack.from(byId.getValue(it)) }, "Kept $id")

    private fun open(limit: Long = 20L * 1024 * 1024) {
        store = LibraryStore(File(tmp.root, "library.db"), { path: String ->
            LibraryDatabase.open(Room.databaseBuilder<LibraryDatabase>(path), BundledSQLiteDriver(), Dispatchers.IO)
        }, pageLimitBytes = limit)
        store.mirrorPage("album/al-1", CatalogueCollector.json.encodeToString(AlbumSnapshot.serializer(), AlbumSnapshot(album, songs)))
    }

    private fun pageJson(id: String, members: List<String>) = CatalogueCollector.json.encodeToString(PlaylistSnapshot.serializer(),
        PlaylistSnapshot(Playlist(id, "List $id", songCount = members.size), members.map { byId.getValue(it) }))

    /** The playlist was opened online once: its page copy is saved (a screen's page save). */
    private fun saved(id: String, members: List<String>) = runBlocking { store.putPage("playlist/$id", pageJson(id, members)) }

    /** The kept sets as the music cache persists them (`cache_sets`, same library.db). */
    private fun persistKept(sets: List<KeptItem>) = runBlocking {
        store.use(Unit) { db -> db.cache().upsertSets(sets.mapIndexed { i, s -> CacheSetRow(s.key, i, 0, CatalogueCollector.json.encodeToString(KeptItem.serializer(), s)) }) }
    }

    private fun collect(onPhone: Collection<String>, sets: List<KeptItem> = emptyList()) = runBlocking {
        store.awaitWrites()
        CatalogueCollector(store, { error("no server") }).collect(onPhone.map(::cached), sets) { false }
    }

    private fun shown(onPhone: Set<String>, completeOnly: Boolean) = runBlocking { OfflineCatalogue(store).playlists(onPhone, completeOnly) }
    private fun names(onPhone: Set<String>, completeOnly: Boolean) = shown(onPhone, completeOnly).first.map { it.id }.toSet()

    @Test fun ruleEdges() {
        fun c(here: Int, total: Int) = OfflineCatalogue.Count(here, total)
        // Without "Only complete albums": one song is enough, none is not.
        assertTrue(OfflineCatalogue.playlistShown(kept = false, known = true, c(1, 20), completeOnly = false))
        assertFalse(OfflineCatalogue.playlistShown(kept = false, known = true, c(0, 20), completeOnly = false))
        // With it: half and at least 3.
        assertFalse(OfflineCatalogue.playlistShown(kept = false, known = true, c(2, 2), completeOnly = true))
        assertTrue(OfflineCatalogue.playlistShown(kept = false, known = true, c(3, 6), completeOnly = true))
        assertFalse(OfflineCatalogue.playlistShown(kept = false, known = true, c(3, 7), completeOnly = true))
        assertTrue(OfflineCatalogue.playlistShown(kept = false, known = true, c(4, 7), completeOnly = true))
        // Never opened online: not shown, whatever is here.
        assertFalse(OfflineCatalogue.playlistShown(kept = false, known = false, c(20, 20), completeOnly = false))
        // Kept: always.
        for (mode in listOf(false, true)) for (known in listOf(false, true))
            assertTrue(OfflineCatalogue.playlistShown(kept = true, known = known, c(0, 5), completeOnly = mode))
    }

    @Test fun withoutCompleteOnlyOneSongIsEnough() {
        open()
        saved("one", ids(1..20))        // 1 of 20 here
        saved("none", ids(11..30))      // 0 of 20 here
        collect(listOf("s-1"))
        val (list, counts) = shown(setOf("s-1"), completeOnly = false)
        assertEquals(listOf("one"), list.map { it.id })
        assertEquals("1 of 20", counts.getValue("one").label)
        assertEquals(20, list.single().songCount)
    }

    @Test fun withCompleteOnlyHalfAndAtLeastThree() {
        open()
        saved("two", ids(1..2))         // 2 of 2: complete but fewer than 3
        saved("half", ids(1..6))        // 3 of 6
        saved("under", ids(1..3) + ids(11..14)) // 3 of 7
        val here = ids(1..3).toSet()
        collect(here)
        assertEquals(setOf("half"), names(here, completeOnly = true))
        assertEquals(setOf("two", "half", "under"), names(here, completeOnly = false))
        assertEquals("3 of 6", shown(here, completeOnly = true).second.getValue("half").label)
    }

    @Test fun keptPlaylistsShowInBothModes() {
        open()
        val withPage = kept("k-page", ids(21..22))
        val noPage = kept("k-set", ids(23..24))
        saved("k-page", ids(21..22))
        persistKept(listOf(withPage, noPage))
        collect(listOf("s-1"), listOf(withPage, noPage))
        for (mode in listOf(false, true)) {
            val (list, counts) = shown(setOf("s-1"), mode)
            assertEquals("mode $mode", setOf("k-page", "k-set"), list.map { it.id }.toSet())
            assertEquals("0 of 2", counts.getValue("k-page").label)
            assertNotNull(runBlocking { OfflineCatalogue(store).playlist("k-page", setOf("s-1"), mode) })
        }
    }

    @Test fun aPlaylistNeverOpenedOnlineIsNotShown() {
        open()
        // Its songs are here, but the phone doesn't know its song list (no page copy): no background fetching.
        collect(ids(1..5))
        assertTrue(names(ids(1..5).toSet(), completeOnly = false).isEmpty())
        runBlocking { store.use(Unit) { assertTrue(it.catalogue().playlists().isEmpty()) } }
    }

    @Test fun aPlaylistLeavesWhenItsSongsGo() {
        open()
        saved("mix", ids(1..10))
        collect(listOf("s-1", "s-2"))
        assertEquals(setOf("mix"), names(setOf("s-1", "s-2"), completeOnly = false))
        // The songs left the phone (cleanup): the next pass drops the playlist, and it is not shown before either.
        assertTrue(names(emptySet(), completeOnly = false).isEmpty())
        collect(listOf("s-20"))
        runBlocking { store.use(Unit) { assertNull(it.catalogue().playlist("mix")) } }
        assertNull(runBlocking { OfflineCatalogue(store).playlist("mix", setOf("s-20")) })
    }

    @Test fun aPlaylistLeavesWhenItsPageCopyIsTrimmed() {
        // Room for one playlist page among the ordinary pages (the album page is the catalogue's: not counted).
        val size = pageJson("old", ids(1..10)).toByteArray().size.toLong()
        open(limit = size + size / 2)
        saved("old", ids(1..10))
        collect(listOf("s-1"))
        assertEquals(setOf("old"), names(setOf("s-1"), completeOnly = false))
        // A newer page pushes the old copy out: the trim is unchanged (the catalogue's row does not protect it).
        saved("new", ids(11..20))
        assertNull(runBlocking { store.page("playlist/old") })
        // Before the next pass the reader already leaves it out; the pass then drops the row.
        assertTrue("old" !in names(setOf("s-1", "s-11"), completeOnly = false))
        collect(listOf("s-1", "s-11"))
        runBlocking { store.use(Unit) { assertNull(it.catalogue().playlist("old")) } }
        assertEquals(setOf("new"), names(setOf("s-1", "s-11"), completeOnly = false))
    }

    @Test fun onlyKeptPlaylistPagesAreProtectedFromTheTrim() {
        open()
        val keep = kept("k", ids(1..2))
        saved("k", ids(1..2))
        saved("other", ids(1..3))
        persistKept(listOf(keep))
        collect(listOf("s-1"), listOf(keep))
        val protected = runBlocking { store.use(emptySet<String>()) { CatalogueCollector.protectedPages(it) } }
        assertTrue("playlist/k" in protected)
        assertFalse("playlist/other" in protected)
    }

    @Test fun thePlaylistPageListsEveryMemberAndOpensByIdAsAPartialAlbum() {
        open()
        saved("under", ids(1..3) + ids(11..14))
        val here = ids(1..3).toSet()
        collect(here)
        val catalogue = OfflineCatalogue(store)
        val (page, count) = runBlocking { catalogue.playlist("under", here) }!!
        assertEquals(ids(1..3) + ids(11..14), page.songs.map { it.id })
        assertEquals("3 of 7", count.label)
        // The strict rule is there for a caller that wants it; the phone and the car open the page by id.
        assertNull(runBlocking { catalogue.playlist("under", here, completeOnly = true) })
    }

    @Test fun theCarListsTheSamePlaylistsAndQueuesOnlyWhatIsHere() {
        open()
        val keep = kept("k", ids(21..22))
        saved("half", ids(1..6))
        saved("under", ids(1..3) + ids(11..14))
        persistKept(listOf(keep))
        val here = ids(1..3).toSet()
        collect(here, listOf(keep))
        for (mode in listOf(false, true)) {
            val phone = shown(here, mode).first.map { it.id }
            val car = runBlocking { PhoneTreeSource(OfflineCatalogue(store), here, completeOnly = mode).getPlaylists() }.map { it.id }
            assertEquals("mode $mode", phone, car)
        }
        val tree = LibraryTree(PhoneTreeSource(OfflineCatalogue(store), here, completeOnly = true))
        assertEquals(setOf("playlist/half", "playlist/k"), runBlocking { tree.children("playlists", 0, 50) }!!.map { it.id }.toSet())
        assertEquals("3 of 6 songs", runBlocking { tree.item("playlist/half") }!!.subtitle)
        // Offline play skips the missing songs: the queue is the songs on the phone, in playlist order.
        val play = runBlocking { LibraryTree(PhoneTreeSource(OfflineCatalogue(store), here)).playback("playlist/under") }!!
        assertEquals(ids(1..3), play.songs.map { it.id })
    }

    @Test fun theCarReloadsWhenAPlaylistCrossesTheThresholdOnly() {
        open()
        saved("six", ids(1..6))
        collect(emptyList())
        val catalogue = OfflineCatalogue(store)
        fun lists(here: Collection<String>) = runBlocking { collect(here); CarReloads.phoneLists(catalogue, here.toSet()) }
        val none = lists(listOf("s-20"))
        val one = lists(listOf("s-20", "s-1"))
        assertTrue("a playlist appears: reload", one != none)
        assertEquals(false, one["playlist/six"])
        assertEquals("a second song moves no threshold", one.filterKeys { it.startsWith("playlist/") },
            lists(listOf("s-20", "s-1", "s-2")).filterKeys { it.startsWith("playlist/") })
        val three = lists(listOf("s-20", "s-1", "s-2", "s-3"))
        assertEquals("half and 3: shown with Only complete albums too", true, three["playlist/six"])
        assertEquals(three.filterKeys { it.startsWith("playlist/") }, lists(listOf("s-20", "s-1", "s-2", "s-3", "s-4")).filterKeys { it.startsWith("playlist/") })
    }

    @Test fun theSettingChangesTheListsFromTheSameCatalogue() {
        open()
        saved("two", ids(1..2))
        saved("half", ids(1..6))
        val here = ids(1..3).toSet()
        collect(here)
        // The phone reloads with the setting (LibraryShell keys PhoneLibrary.reload on it), the car via completeAlbumsOnly.
        val off = names(here, completeOnly = false)
        val on = names(here, completeOnly = true)
        assertEquals(setOf("two", "half"), off)
        assertEquals(setOf("half"), on)
        assertEquals(off, names(here, completeOnly = false))
    }
}
