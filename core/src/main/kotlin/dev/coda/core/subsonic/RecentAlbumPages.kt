package dev.coda.core.subsonic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * `getAlbum` pages for the background readers of one session (album-resume bookmarks after every completed
 * track, the offline catalogue after a track is cached). Playing an album asked for the same page at every track change;
 * here a page answers again for [ttlMs], and concurrent requests for one album share a single call. Pages the user
 * opens keep fetching fresh (not routed through here).
 */
class RecentAlbumPages(
    private val fetch: suspend (String) -> AlbumPage,
    private val ttlMs: Long = 10 * 60_000L,
    private val capacity: Int = 8,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private class Entry(val page: AlbumPage, val atMs: Long)
    /** The owning call was cancelled: a waiter fetches by itself instead of failing. */
    private class Abandoned : Exception()
    private val lock = Any()
    private val pages = LinkedHashMap<String, Entry>(capacity, 0.75f, true)
    private val inFlight = HashMap<String, CompletableDeferred<AlbumPage>>()

    suspend fun get(id: String): AlbumPage {
        while (true) {
            var owner = false
            val pending = synchronized(lock) {
                pages[id]?.let { if (nowMs() - it.atMs < ttlMs) return it.page else pages.remove(id) }
                inFlight[id] ?: CompletableDeferred<AlbumPage>().also { inFlight[id] = it; owner = true }
            }
            if (!owner) {
                try { return pending.await() } catch (_: Abandoned) { continue }
            }
            try {
                val page = fetch(id)
                synchronized(lock) {
                    inFlight.remove(id)
                    pages[id] = Entry(page, nowMs())
                    while (pages.size > capacity) pages.remove(pages.keys.first())
                }
                pending.complete(page)
                return page
            } catch (e: Throwable) {
                synchronized(lock) { inFlight.remove(id) }
                pending.completeExceptionally(if (e is CancellationException) Abandoned() else e)
                throw e
            }
        }
    }
}
