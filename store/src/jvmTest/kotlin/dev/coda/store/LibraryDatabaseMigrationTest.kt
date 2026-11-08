package dev.coda.store

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * `library.db` is durable. v1 is the baseline; every later version adds a case here that creates the older version
 * from its exported schema, fills cache rows, runs the real migration and checks that every cache row survived.
 * V1 is frozen; add a Migration(1, 2) and a test for any change.
 */
class LibraryDatabaseMigrationTest {
    private val schemas = File("schemas/dev.coda.store.LibraryDatabase")
    private val path = File(java.nio.file.Files.createTempDirectory("migration").toFile(), "library.db").apply { deleteOnExit() }
    @get:Rule val helper = MigrationTestHelper(File("schemas").toPath(), path.toPath(), BundledSQLiteDriver(), LibraryDatabase::class)

    @Test fun theBaselineMatchesItsExportedSchemaAndOpensWithCacheRows() = runBlocking {
        helper.createDatabase(1).use { connection ->
            connection.prepare("INSERT INTO cache_state (id, writer, removedForSpace, queueDownload) VALUES (0, 'w', NULL, NULL)").use { it.step() }
            connection.prepare("INSERT INTO cache_tracks (songId, file, kind, bytes, addedAt, lastPlayedAt, playCount, contentType, format, meta) " +
                "VALUES ('s', 'f.orig', 'RAW', 4, 1, NULL, 0, NULL, '{}', NULL)").use { it.step() }
        }
        // Current version, no migrations: identical to the export (the scaffold later versions extend).
        helper.runMigrationsAndValidate(LibraryDatabase.VERSION, emptyList()).close()
        val db = LibraryDatabase.open(Room.databaseBuilder<LibraryDatabase>(path.path), BundledSQLiteDriver(), Dispatchers.IO)
        try {
            assertEquals("w", db.cache().state()?.writer)
            assertEquals(listOf("s"), db.cache().tracks().map { it.songId })
        } finally { db.close() }
    }

    @Test fun everyTableIsEitherRebuildableOrCacheData() {
        assertEquals("v${LibraryDatabase.VERSION} is exported", true, File(schemas, "${LibraryDatabase.VERSION}.json").isFile)
        val tables = Regex("\"tableName\":\\s*\"([^\"]+)\"").findAll(File(schemas, "${LibraryDatabase.VERSION}.json").readText())
            .map { it.groupValues[1] }.toSet()
        val rebuildable = LibraryDatabase.REBUILDABLE_TABLES.toSet()
        val cache = LibraryDatabase.CACHE_TABLES.toSet()
        assertTrue("no table in both lists", rebuildable.intersect(cache).isEmpty())
        assertEquals("each table has its rule", tables, rebuildable + cache)
        assertTrue(cache.all { it.startsWith("cache_") || it == "waveforms" }) // Waveforms are kept like cache data
    }
}
