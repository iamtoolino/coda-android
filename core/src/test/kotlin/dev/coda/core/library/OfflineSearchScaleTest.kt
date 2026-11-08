package dev.coda.core.library

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.coda.core.testing.OfflineSearchScale
import dev.coda.store.LibraryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Offline Search on a synthetic 20,000-song cache (JVM numbers; the device numbers are Of1dSearchScaleDeviceTest's). */
class OfflineSearchScaleTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun searchOnALargeCache() = runBlocking {
        val store = LibraryStore(File(tmp.root, "library.db"), { path ->
            LibraryDatabase.open(Room.databaseBuilder<LibraryDatabase>(path), BundledSQLiteDriver(), Dispatchers.IO)
        })
        try {
            val library = OfflineSearchScale.fill(store)
            val rows = OfflineSearchScale.measure(store, library)
            rows.forEach { println("OF-1d jvm $it") }
            assertTrue(rows.all { it.songs > 0 })
        } finally { store.close() }
    }
}
