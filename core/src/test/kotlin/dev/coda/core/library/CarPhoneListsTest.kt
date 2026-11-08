package dev.coda.core.library

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.coda.core.cache.CachedTrack
import dev.coda.core.cache.TrackMeta
import dev.coda.core.playback.StreamFormat
import dev.coda.core.subsonic.Album
import dev.coda.core.subsonic.AlbumPage
import dev.coda.core.subsonic.Song
import dev.coda.store.LibraryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * While the car's lists come from the phone, a catalogue pass that changed only which tracks are on the
 * phone (no album taken, fetched, removed or refreshed) reloads the car when a list's content changes: an album turns
 * partial (also with "Only complete albums"), a new (minimal) album appears. A cache change no list shows reloads nothing.
 */
class CarPhoneListsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val store by lazy {
        LibraryStore(File(tmp.root, "library/acct/library.db"), { path: String ->
            LibraryDatabase.open(Room.databaseBuilder<LibraryDatabase>(path), BundledSQLiteDriver(), Dispatchers.IO)
        })
    }
    @After fun close() = store.close()

    private val album = Album("al-1", "First Light", artist = "Nova Lights", artistId = "ar-1", songCount = 3)
    private val songs = (1..3).map { Song("s-$it", "Song $it", album = album.name, artist = "Nova Lights", albumId = "al-1", artistId = "ar-1", track = it, discNumber = 1) }
    private fun cached(id: String, albumId: String = "al-1") =
        CachedTrack(id, "$id.orig", StreamFormat.RAW, 10, 0, meta = TrackMeta("Song $id", "Nova Lights", "Album $albumId", albumId, albumId, 100, 1, 1))
    private val collector by lazy { CatalogueCollector(store, { id -> if (id == "al-1") AlbumPage(album, songs) else throw dev.coda.core.subsonic.SubsonicError.NotFound() }) }
    private val catalogue by lazy { OfflineCatalogue(store) }

    /** One catalogue pass over [onPhone] (offline: no request), and whether the car reloads after it. */
    private class Step(val reloads: Boolean, val lists: Map<String, Boolean>)
    private fun pass(before: Map<String, Boolean>?, onPhone: List<CachedTrack>, reachable: Boolean = false): Step = runBlocking {
        val result = collector.collect(onPhone, emptyList()) { reachable }
        val lists = CarReloads.phoneLists(catalogue, onPhone.mapTo(HashSet()) { it.songId })
        Step(CarReloads.catalogueReloads(onPhone = true, pass = result, keptChanged = false, listsChanged = lists != before), lists)
    }

    @Test fun aCompleteAlbumTurningPartialReloadsAlsoWithCompleteAlbumsOnly() = runBlocking {
        val full = pass(null, listOf(cached("s-1"), cached("s-2"), cached("s-3")), reachable = true).lists
        assertEquals(listOf("al-1"), catalogue.shelf(setOf("s-1", "s-2", "s-3")).completeOnly().albums.map { it.id })
        val partial = pass(full, listOf(cached("s-1"), cached("s-2")))
        // The pass itself changed nothing in the catalogue; only the on-phone set did.
        assertTrue("complete -> partial reloads", partial.reloads)
        assertEquals("with Only complete albums the album leaves the list", emptyList<String>(),
            catalogue.shelf(setOf("s-1", "s-2")).completeOnly().albums.map { it.id })
        assertTrue("partial -> complete reloads", pass(partial.lists, listOf(cached("s-1"), cached("s-2"), cached("s-3"))).reloads)
    }

    @Test fun aNewMinimalAlbumReloads() = runBlocking {
        val one = pass(null, listOf(cached("s-1")), reachable = true).lists
        // Offline: a track of an album never fetched gets a minimal entry; no album taken, fetched or refreshed.
        val step = pass(one, listOf(cached("s-1"), cached("m-1", albumId = "al-9")))
        assertTrue("new minimal album reloads", step.reloads)
        assertTrue("al-9" in step.lists)
    }

    @Test fun aCacheChangeNoListShowsDoesNotReload() = runBlocking {
        val one = pass(null, listOf(cached("s-1")), reachable = true).lists
        // A second track of a partial album that stays partial: the lists look the same.
        assertFalse(pass(one, listOf(cached("s-1"), cached("s-2"))).reloads)
    }
}
