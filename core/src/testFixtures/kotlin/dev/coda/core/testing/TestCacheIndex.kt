package dev.coda.core.testing

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.coda.core.cache.CacheIndexDb
import dev.coda.core.cache.CacheIndexStore
import dev.coda.core.cache.CachePolicy
import dev.coda.core.cache.FreeSpace
import dev.coda.core.cache.TrackCache
import dev.coda.core.cache.WaveformRows
import dev.coda.core.library.LibraryStore
import dev.coda.store.LibraryDatabase
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * For JVM tests: the music cache's index in a real `library.db` (bundled SQLite). The file sits in a subfolder of
 * the cache directory (`.index/`, which the cache never lists as music), so deleting the folder deletes the index with it,
 * like the old `index.json`. Every cache opened on the same folder shares one store, as one process does; a new
 * [TrackCache] on it is a restart (memory gone, the database kept).
 */
object TestCacheIndex {
    private val stores = HashMap<String, LibraryStore>()

    fun file(directory: File) = File(directory, ".index/library.db")

    @Synchronized fun store(directory: File): LibraryStore {
        val file = file(directory).absoluteFile
        stores[file.path]?.takeIf { !it.isClosed && file.isFile }?.let { return it }
        stores.remove(file.path)?.let { runCatching { it.close() } }
        return LibraryStore(file, { path -> LibraryDatabase.open(Room.databaseBuilder<LibraryDatabase>(path), BundledSQLiteDriver(), Dispatchers.IO) })
            .also { stores[file.path] = it }
    }

    fun at(directory: File): CacheIndexStore = CacheIndexDb { store(directory) }

    /** The database is lost (deleted, or recreated after damage): the next cache on [directory] finds no index. */
    @Synchronized fun wipe(directory: File) {
        val file = file(directory).absoluteFile
        stores.remove(file.path)?.let { runCatching { it.close() } }
        file.parentFile.deleteRecursively()
    }
}

/** A [TrackCache] on [directory] with its index in [TestCacheIndex] (the old constructor shape, for the tests). */
fun testTrackCache(
    directory: File,
    freeSpace: FreeSpace = FreeSpace.of(directory),
    deletes: java.util.concurrent.Executor = java.util.concurrent.Executor { it.run() },
    policy: () -> CachePolicy = { CachePolicy.DEFAULT },
    wall: () -> Long = System::currentTimeMillis,
): TrackCache = TrackCache(directory, TestCacheIndex.at(directory), freeSpace, deletes, policy, wall = wall)

/**
 * A [CacheIndexStore] in memory, for tests whose classpath has the Android variant of `:store` (app unit tests: Room's
 * JVM builder isn't there). Same contract: [save] replaces the whole saved state, [load] is null until the first save or
 * after the folder's `.index/` marker is gone (the folder was deleted). The real database is covered by `:core` tests.
 */
object MemoryCacheIndex {
    private val saved = HashMap<String, dev.coda.core.cache.CacheSnapshot>()
    private val waveformRows = HashMap<String, MemoryWaveformRows>()

    private fun marker(directory: File) = File(directory, ".index/memory")

    fun at(directory: File): CacheIndexStore = object : CacheIndexStore {
        override fun load(writer: String) = synchronized(saved) {
            saved[directory.absolutePath]?.takeIf { marker(directory).isFile }
        }
        override val waveforms: WaveformRows = synchronized(saved) { waveformRows.getOrPut(directory.absolutePath, ::MemoryWaveformRows) }
        override fun save(writer: String, previous: dev.coda.core.cache.CacheSnapshot?, next: dev.coda.core.cache.CacheSnapshot) {
            synchronized(saved) {
                marker(directory).apply { parentFile.mkdirs(); writeText("") }
                saved[directory.absolutePath] = next
            }
        }
    }
}

/** [testTrackCache] with its index in [MemoryCacheIndex] (app unit tests). */
fun memoryTrackCache(
    directory: File,
    freeSpace: FreeSpace = FreeSpace.of(directory),
    deletes: java.util.concurrent.Executor = java.util.concurrent.Executor { it.run() },
    policy: () -> CachePolicy = { CachePolicy.DEFAULT },
    wall: () -> Long = System::currentTimeMillis,
): TrackCache = TrackCache(directory, MemoryCacheIndex.at(directory), freeSpace, deletes, policy, wall = wall)

/** [WaveformRows] in memory (tests). */
class MemoryWaveformRows : WaveformRows {
    private val rows = HashMap<String, Pair<ByteArray, Long>>()
    @Volatile var maintainCalls = 0
        private set

    @Synchronized override fun dates() = rows.mapValues { it.value.second }
    @Synchronized override fun read(songIds: Collection<String>) = songIds.mapNotNull { id -> rows[id]?.let { id to it.first } }.toMap()
    @Synchronized override fun write(songId: String, blob: ByteArray, datedAt: Long) { rows[songId] = blob.copyOf() to datedAt }
    @Synchronized override fun delete(songIds: Collection<String>) { songIds.forEach { rows.remove(it) } }
    @Synchronized override fun maintain(renewed: Map<String, Long>, cutoff: Long, cap: Int): Set<String> {
        maintainCalls++
        renewed.forEach { (id, at) -> rows[id]?.let { rows[id] = it.first to maxOf(it.second, at) } }
        val expired = rows.filterValues { it.second <= cutoff }.keys.toSet()
        expired.forEach { rows.remove(it) }
        val overCap = rows.entries.sortedWith(compareBy({ it.value.second }, { it.key })).take((rows.size - cap).coerceAtLeast(0)).map { it.key }
        overCap.forEach { rows.remove(it) }
        return expired + overCap
    }
}
