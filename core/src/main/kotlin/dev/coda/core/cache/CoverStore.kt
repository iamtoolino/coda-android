package dev.coda.core.cache

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The account's one durable artwork store (`no_backup/music/<key>/covers/`), merging the picture cache and the
 * Kept-cover store. Every cover size the app asks the server for is kept as delivered, keyed by the stable cover
 * id plus size (`<sha1("cover:<id>")>-<size>`, never a URL), never in backups, **never evicted and without a cap**: it
 * sits on the same disk as the music, so it counts against Keep free space like kept music, and songs give way
 * ([TrackCache.putCover]). Only the account wipe (the whole `music/<key>` folder) removes it.
 *
 * Its index ([CoverIndex], the `cache_cover_stamps` and `cache_played_albums` tables of `library.db`) holds the
 * server's coverArt id (stamp) each copy was fetched under, for revalidation, and the albums played on this phone, whose
 * 472 and 1440 copies [fill] completes. A stamp row is written before its copy gets its final name. Reads never touch
 * the network.
 */
class CoverStore(
    val directory: File,
    private val index: CoverIndex = CoverIndex.Memory(),
    /** Whether [Long] more bytes fit above the Keep free space line: a write never pushes free space below it. */
    private val room: (Long) -> Boolean = { true },
    /** The owning cache was retired before the account wipe; nothing is written any more. */
    private val retired: () -> Boolean = { false },
) {
    companion object {
        /** Now Playing, the album header, the artist banner. */
        const val KEPT = 1440
        /** Grids, rows, the mini player; the accent's source (always the stored 472 copy). */
        const val SMALL = 472
        /** Browse tiles and rows (the artwork provider's default). */
        const val BROWSE = 288
        /** The sizes the app asks the server for, ascending: atmosphere 64, browse 288, grid 472, session 1024, 1440. */
        val SIZES = listOf(64, BROWSE, SMALL, 1024, KEPT)
        /** What a played or kept album gets. */
        val WANTED = listOf(SMALL, KEPT)
        fun base(id: String): String = MessageDigest.getInstance("SHA-1").digest("cover:$id".toByteArray())
            .joinToString("") { "%02x".format(it) }
        private fun name(id: String, size: Int) = "${base(id)}-$size"
        private fun recordable(value: String) = value.isNotEmpty() && value.none { it == '\t' || it == '\n' || it == '\r' }
    }

    /** Image bytes from somewhere other than the store. Implementations return only verified images, or null. */
    interface Source {
        /** The old picture cache's copy (written while the account had no store, or the store refused it), no network. */
        fun cached(id: String, size: Int): ByteArray?
        /** One network fetch. */
        suspend fun fetch(id: String, size: Int): ByteArray?
    }

    /** Bytes of the copies on disk, kept in memory once counted ([open] or the first [sizeBytes]), moved by every write. */
    @Volatile private var total: Long? = null
    private var loaded = false
    /** Copy name → the server stamp it was fetched under. */
    private val stamps = HashMap<String, String>()
    private val played = LinkedHashSet<String>()

    private fun file(id: String, size: Int): File? = File(directory, name(id, size)).takeIf { it.isFile && it.length() > 0 }
    private fun copies() = directory.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".tmp") }

    fun has(id: String, size: Int) = file(id, size) != null

    /** The stored copy of exactly [size]. */
    fun exact(id: String, size: Int): File? = file(id, size)

    /** The smallest stored copy above [size] (decoded down). */
    fun larger(id: String, size: Int): File? = SIZES.filter { it > size }.firstNotNullOfOrNull { file(id, it) }

    /**
     * A copy a browse tile (288) can be drawn from without the network: 288 or larger. A 64 atmosphere copy alone
     * is no cover (see [fallback]).
     */
    fun stored(id: String): Boolean = SIZES.any { it >= BROWSE && file(id, it) != null }

    /** The exact size, else the smallest stored copy above it. */
    fun lookup(id: String, size: Int): File? = exact(id, size) ?: larger(id, size)

    /**
     * The lookup before the network: the store's exact size, then the old picture cache's exact copy ([picture],
     * which the caller moves into the store), then the store's larger copy, so a small request never decodes a large file.
     */
    fun <T : Any> beforeNetwork(id: String, size: Int, read: (File) -> T?, picture: () -> T?): T? =
        exact(id, size)?.let(read) ?: picture() ?: larger(id, size)?.let(read)

    /** The offline fallback once nothing else answered: the largest stored copy below [size], at least a quarter of it. */
    fun fallback(id: String, size: Int): File? =
        SIZES.filter { it < size && it * 4 >= size }.sortedDescending().firstNotNullOfOrNull { file(id, it) }

    /** Reads the index and drops temp files of an interrupted write; counts the copies. The music cache's init (IO). */
    @Synchronized fun open() {
        directory.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }.forEach { it.delete() }
        ensureLoaded()
        total = copies().sumOf { it.length() }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val saved = runCatching { index.load() }.getOrNull() ?: return
        stamps.putAll(saved.stamps); played.addAll(saved.played)
    }

    /** Records [stamp] for the copy [name] (row first, see [put]). */
    private fun noteStamp(name: String, stamp: String?) {
        if (stamp == null || !recordable(stamp) || stamps[name] == stamp) return
        stamps[name] = stamp
        if (!retired()) runCatching { index.saveStamp(name, stamp) }
    }

    /**
     * Writes a copy (atomically, a unique temp file) when it fits above the line; false otherwise. [stamp] is the server's
     * coverArt id the bytes were fetched under (null: unknown, an older stamp stays).
     */
    @Synchronized fun put(id: String, size: Int, bytes: ByteArray, stamp: String? = null): Boolean {
        if (bytes.isEmpty() || size <= 0 || retired()) return false
        ensureLoaded()
        val target = File(directory, name(id, size))
        val old = if (target.isFile) target.length() else 0L
        val grows = bytes.size - old
        if (grows > 0 && !room(grows)) return false
        directory.mkdirs()
        val temp = File.createTempFile(target.name + "-", ".tmp", directory)
        try {
            temp.writeBytes(bytes)
            // the rule: the row before the file gets its final name. A crash in between leaves a stamp without its copy
            // (fetched again when shown) or, replacing a copy, the old bytes under the new stamp until the next refresh.
            val before = stamps[target.name]
            noteStamp(target.name, stamp)
            if (!temp.renameTo(target)) {
                // The old copy stays: so does its stamp (a new stamp without a copy before it is harmless).
                if (before != null && stamps[target.name] != before) { stamps[target.name] = before; runCatching { index.saveStamp(target.name, before) } }
                return false
            }
        } finally { temp.delete() }
        total = total?.let { it - old + bytes.size }
        return true
    }

    /**
     * A pull-to-refresh of [id] at [size] that succeeded: writes it, and (for [WANTED] sizes) drops the other sizes of
     * [id] stored before the refresh began ([since], ms), so a stale copy is never served after a refresh.
     */
    @Synchronized fun refresh(id: String, size: Int, bytes: ByteArray, since: Long, stamp: String? = null): Boolean {
        // A retired store writes nothing ([put] refuses), so it deletes nothing either.
        if (retired()) return false
        val stale = if (size in WANTED) SIZES.mapNotNull { file(id, it) }.filter { it.lastModified() < since } else emptyList()
        val written = put(id, size, bytes, stamp)
        val target = name(id, size)
        stale.filter { !(written && it.name == target) }.forEach { delete(it) }
        return written
    }

    /**
     * On ART-1: a copy of [id] at [size] fetched again under [current] that could not be stored ([put] refused):
     * the stale copy goes and [current] is recorded, so it is neither served nor fetched again on every display. The new
     * bytes sit in the old picture cache, which the fetcher moves in later ([adopt]) as fresh.
     */
    @Synchronized fun dropStale(id: String, size: Int, current: String?) {
        if (current == null || retired()) return
        ensureLoaded()
        val name = name(id, size)
        val copy = file(id, size)
        if (copy != null && stamps[name].let { it != null && it != current }) delete(copy)
        if (copy == null || !copy.exists()) noteStamp(name, current)
    }

    private fun delete(file: File) { val bytes = file.length(); if (file.delete()) total = total?.minus(bytes) }

    /**
     * Migration: moves an old picture-cache copy into the store as [id] at [size], without a download. When the
     * store already has that copy the old file just goes. Same disk, so no space is needed. True: the store has it.
     */
    @Synchronized fun adopt(id: String, size: Int, old: File): Boolean {
        if (retired() || !old.isFile) return false
        val target = File(directory, name(id, size))
        if (target.isFile && target.length() > 0) { old.delete(); return true }
        directory.mkdirs()
        val moved = old.renameTo(target) || runCatching { old.copyTo(target, overwrite = true); old.delete(); true }.getOrDefault(false)
        if (moved) total = total?.plus(target.length())
        return moved
    }


    /** The server stamp the copy of [id] at [size] was fetched under, or null (unknown: a migrated or kept copy). */
    @Synchronized fun stamp(id: String, size: Int): String? { ensureLoaded(); return stamps[name(id, size)] }

    /**
     * Whether the copy of [id] at [size] was fetched under another stamp than [current], the one the server reports now
     * (ART-1 revalidation). An unknown stored stamp takes [current] (a migrated copy is not downloaded again) and is fresh.
     */
    @Synchronized fun stale(id: String, size: Int, current: String?): Boolean {
        if (current == null || !has(id, size)) return false
        ensureLoaded()
        val name = name(id, size)
        val stored = stamps[name] ?: run { noteStamp(name, current); return false }
        return stored != current
    }

    /** Played albums not written to the index yet ([save]). */
    private val unsaved = ArrayList<String>()

    /**
     * An album of [id] was played on this phone (its 472 and 1440 copies are wanted). Memory only (the player calls it on
     * the main thread); [save] writes it. No fetch.
     */
    @Synchronized fun markPlayed(id: String) {
        if (!recordable(id) || id in played) return
        played += id; unsaved += id
    }

    /** Writes what [markPlayed] noted (the music cache's flush, IO). */
    @Synchronized fun save() {
        if (unsaved.isEmpty()) return
        ensureLoaded()
        if (!retired()) runCatching { index.savePlayed(unsaved.toList()) }
        unsaved.clear()
    }

    @Synchronized fun played(): Set<String> { ensureLoaded(); save(); return played.toSet() }

    /** Bytes of the copies on disk (Music storage's size counts them): memory only once counted. */
    fun sizeBytes(): Long = total ?: synchronized(this) { total ?: copies().sumOf { it.length() }.also { total = it } }

    /**
     * Completes the [WANTED] sizes of [kept] covers (each fetch only while [canFetchKept]: proper Wi-Fi and transfers
     * allowed, i.e. during a keep) and of [played] albums (only while [canFetchPlayed]: also the screen on). Old picture-cache
     * copies are taken first. Each missing copy is fetched once per entry in [tried]; at most [limit] fetches per call;
     * cancellable between and during fetches. Returns true when fetches are left for a later pass because [limit] was reached.
     */
    suspend fun fill(kept: Set<String>, played: Set<String>, source: Source, canFetchKept: () -> Boolean,
        canFetchPlayed: () -> Boolean, tried: MutableSet<String>, limit: Int = Int.MAX_VALUE,
        write: ((String, Int, ByteArray) -> Boolean)? = null): Boolean {
        val put = write ?: { id: String, size: Int, bytes: ByteArray -> put(id, size, bytes) }
        var fetched = 0
        var left = false
        val work = kept.map { it to canFetchKept } + (played - kept).map { it to canFetchPlayed }
        for ((id, allowed) in work) for (size in WANTED) {
            currentCoroutineContext().ensureActive()
            if (has(id, size)) continue
            source.cached(id, size)?.let { put(id, size, it) }
            val key = "$id@$size"
            if (has(id, size) || key in tried || !allowed()) continue
            if (fetched >= limit) { left = true; continue }
            tried.add(key); fetched++
            // A fetch cut short by a stop (cancelled, or failed once the network no longer allows it) is tried again later.
            val bytes = try { source.fetch(id, size).also { currentCoroutineContext().ensureActive() } }
                catch (e: CancellationException) { tried.remove(key); throw e }
            if (bytes == null && !allowed()) tried.remove(key)
            bytes?.let { put(id, size, it) }
        }
        return left
    }

    /** Whether [kept] covers still miss a [WANTED] size that [tried] has not tried yet (the job waits for them). */
    fun keptMissing(kept: Set<String>, tried: Set<String>): Boolean =
        kept.any { id -> WANTED.any { size -> "$id@$size" !in tried && !has(id, size) } }
}

/** Where [CoverStore] keeps its index (copy name → stamp, played albums). IO; the store calls it under its lock. */
interface CoverIndex {
    data class Saved(val stamps: Map<String, String> = emptyMap(), val played: Set<String> = emptySet())
    fun load(): Saved
    fun saveStamp(name: String, stamp: String)
    fun savePlayed(ids: Collection<String>)

    /** Kept in memory only (tests whose classpath has no Room JVM builder). */
    class Memory : CoverIndex {
        private val stamps = HashMap<String, String>()
        private val played = LinkedHashSet<String>()
        @Synchronized override fun load() = Saved(stamps.toMap(), played.toSet())
        @Synchronized override fun saveStamp(name: String, stamp: String) { stamps[name] = stamp }
        @Synchronized override fun savePlayed(ids: Collection<String>) { played += ids }
    }
}
