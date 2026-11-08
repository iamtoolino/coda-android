package dev.coda.core.testing

import dev.coda.core.library.PageStore

/**
 * A [PageStore] in memory, for tests whose classpath has the Android variant of `:store` (app unit tests). No
 * LRU and no kept sets: [pinned] is set by the test. The real `pages` table is covered by `:core`'s SnapshotStoreTest.
 */
class MemoryPageStore : PageStore {
    private val pages = LinkedHashMap<String, String>()
    private val stale = HashSet<String>()
    val pinned = HashSet<String>()
    @Volatile override var pageRevision = 0
        private set
    override suspend fun page(key: String) = synchronized(this) { pages[key] }
    override suspend fun putPage(key: String, json: String) = synchronized(this) { pages[key] = json; stale -= key; pageRevision++; Unit }
    override suspend fun pinnedPages() = synchronized(this) { pages.filterKeys { it in pinned }.values.toList() }
    override suspend fun pagesAfter(after: String, limit: Int) = synchronized(this) {
        pages.entries.filter { it.key > after }.sortedBy { it.key }.take(limit).map { it.key to it.value }
    }
    override suspend fun markStale(key: String) = synchronized(this) { if (key in pages) stale += key; Unit }
    override suspend fun isStale(key: String) = synchronized(this) { key in stale }
}
