package dev.coda.store

import androidx.room.Room
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** `library.db` on the JVM target with the bundled SQLite (real SQLite, plain JUnit). the FTS cases kept. */
class LibraryDatabaseTest {
    private val db = LibraryDatabase.open(Room.inMemoryDatabaseBuilder<LibraryDatabase>(), BundledSQLiteDriver(), Dispatchers.IO)
    private val dao = db.catalogue()

    @After fun close() = db.close()

    private fun song(id: String, title: String, artist: String) =
        SongRow(id, title, "al-$id", "ar-$id", "[]", 1, 1, 100, "$title $artist".lowercase(), "{}", false)

    private suspend fun ids(q: String) = dao.searchSongs(prefixMatch(q.lowercase()), 50).map { it.id }

    @Test fun ftsIsWordPrefix() = runBlocking {
        dao.upsertSongs(listOf(song("1", "Angel", "Massive Attack"), song("2", "Orange Crush", "R.E.M.")))
        assertEquals(listOf("1"), ids("ange"))
        assertEquals(listOf("1"), ids("mass ang"))
        assertEquals(listOf("2"), ids("crush"))
    }

    @Test fun upsertAndDeleteKeepFtsInSync() = runBlocking {
        dao.upsertSongs(listOf(song("1", "Angel", "Massive Attack")))
        dao.upsertSongs(listOf(song("1", "Teardrop", "Massive Attack")))
        assertEquals(emptyList<String>(), ids("angel"))
        assertEquals(listOf("1"), ids("tear"))
        dao.deleteSongs(listOf("1"))
        assertEquals(emptyList<String>(), ids("tear"))
    }

    @Test fun albumAndArtistIndexes() = runBlocking {
        dao.upsertAlbums(listOf(AlbumRow("a", "Mezzanine", null, "ar", "Massive Attack", "[]", "c", 1998, "[]", "mezzanine massive attack", "{}", 0, false)))
        dao.upsertArtists(listOf(ArtistRow("ar", "Massive Attack", null, "ar-1", 5, false, "massive attack", null)))
        assertEquals(listOf("a"), dao.searchAlbums(prefixMatch("mezz"), 10).map { it.id })
        assertEquals(listOf("ar"), dao.searchArtists(prefixMatch("mass"), 10).map { it.id })
    }

    @Test fun prefixMatchDropsOperators() {
        assertEquals("ange de", prefixMatch("ange de").replace("*", ""))
        assertEquals("a* or* b*", prefixMatch(" \"a\" or-b "))
        assertEquals("", prefixMatch("  -* "))
    }

    /** The query splits words where the FTS tokenizer does. */
    @Test fun searchFindsPunctuatedAndUnicodeText() = runBlocking {
        dao.upsertSongs(listOf(song("rem", "Orange Crush", "R.E.M."), song("acdc", "Highway to Hell", "AC/DC"),
            song("hyph", "Under-Pressure", "Queen"), song("dash", "Intro – Outro", "Band"), song("quote", "Don’t Stop", "Band")))
        assertEquals(listOf("rem"), ids("r.e.m."))
        assertEquals(listOf("acdc"), ids("ac dc"))
        assertEquals(listOf("hyph"), ids("under pressure"))
        assertEquals(listOf("dash"), ids("intro-outro"))
        assertEquals(listOf("quote"), ids("don't"))
    }

    @Test fun pagesTrimOrder() = runBlocking {
        val pages = db.pages()
        pages.put("b", "{}", 10, 2)
        pages.put("a", "{}", 20, 1)
        assertEquals(30L, pages.totalBytes())
        assertEquals(listOf("a", "b"), pages.byAge().map { it.key })
        pages.touch("a", 3)
        assertEquals(listOf("b", "a"), pages.byAge().map { it.key })
    }

    /** A page is pinned while a kept set has its key, and a rebuild keeps the pinned pages and every cache row. */
    @Test fun pinnedPagesFollowTheSetsAndSurviveARebuild() = runBlocking {
        val pages = db.pages()
        pages.put("album/a", "{}", 2, 1)
        pages.put("album/b", "{}", 2, 1)
        db.cache().upsertSets(listOf(CacheSetRow("album/a", 0, 0, "{}"), CacheSetRow("album/c", 1, 0, "{}")))
        pages.syncPins()
        pages.put("album/c", "{}", 2, 1)
        assertEquals(listOf("album/a", "album/c"), pages.byAge().filter { it.pinned }.map { it.key })
        db.covers().upsertStamp(CoverStampRow("x-472", "s"))
        db.useWriterConnection { c -> LibraryDatabase.CLEAR_REBUILDABLE.forEach { c.usePrepared(it) { s -> s.step() } } }
        assertEquals(listOf("album/a", "album/c"), pages.byAge().map { it.key })
        assertEquals(2, db.cache().sets().size)
        assertEquals("s", db.covers().stamps().single().stamp)
        db.cache().deleteSets(listOf("album/a")); pages.syncPins()
        assertEquals(listOf("album/c"), pages.byAge().filter { it.pinned }.map { it.key })
    }

    /** The schema of the current version is recorded in the repository. */
    @Test fun schemaOfCurrentVersionIsExported() {
        val file = File("schemas/dev.coda.store.LibraryDatabase/${LibraryDatabase.VERSION}.json")
        assertTrue("missing ${file.path}", file.isFile)
        assertTrue(file.readText().contains("\"version\": ${LibraryDatabase.VERSION}"))
    }
}
