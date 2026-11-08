package dev.coda.core.cache

import dev.coda.core.library.LibraryStore
import dev.coda.store.WaveformRow
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer

/**
 * Where [WaveformStore] keeps its rows (the account's `library.db`, table `waveforms`). Called on IO. Reads return
 * null and writes throw when the database cannot be used now.
 */
interface WaveformRows {
    /** Every stored song with its date. */
    fun dates(): Map<String, Long>?
    /** The stored blobs of these songs (missing ones left out). */
    fun read(songIds: Collection<String>): Map<String, ByteArray>?
    fun write(songId: String, blob: ByteArray, datedAt: Long)
    fun delete(songIds: Collection<String>)
    /**
     * One transaction: writes the [renewed] dates (never moving one back), deletes the rows dated at or before [cutoff],
     * then the oldest beyond [cap]. Returns the songs whose rows were deleted.
     */
    fun maintain(renewed: Map<String, Long>, cutoff: Long, cap: Int): Set<String>
}

/** [WaveformRows] in the account's `library.db`. [store] is asked for the open store at each use. */
class WaveformDb(private val store: () -> LibraryStore) : WaveformRows {
    private val failed = Any()

    @Suppress("UNCHECKED_CAST")
    private fun <T> read(block: suspend (dev.coda.store.WaveformDao) -> T): T? = runBlocking {
        store().use<Any?>(failed) { block(it.waveforms()) }.takeIf { it !== failed } as T?
    }

    override fun dates() = read { dao -> dao.dates().associate { it.songId to it.datedAt } }
    override fun read(songIds: Collection<String>) = read { dao ->
        songIds.chunked(CHUNK).flatMap { dao.rows(it) }.associate { it.songId to it.peaks }
    }
    override fun write(songId: String, blob: ByteArray, datedAt: Long) =
        runBlocking { store().transact { it.waveforms().upsert(WaveformRow(songId, blob, datedAt)) } }
    override fun delete(songIds: Collection<String>) =
        runBlocking { store().transact { db -> songIds.chunked(CHUNK).forEach { db.waveforms().delete(it) } } }

    override fun maintain(renewed: Map<String, Long>, cutoff: Long, cap: Int): Set<String> = runBlocking {
        store().transact { db ->
            val dao = db.waveforms()
            renewed.forEach { (songId, at) -> dao.renew(songId, at) }
            val expired = dao.expired(cutoff)
            if (expired.isNotEmpty()) dao.deleteExpired(cutoff)
            val overCap = dao.overCap(cap)
            if (overCap.isNotEmpty()) dao.deleteOverCap(cap)
            (expired + overCap).toSet()
        }
    }

    private companion object { const val CHUNK = 500 }
}

/**
 * One waveform per song (CA-2 "Waveforms"), separate from the audio and its space rules: it survives eviction,
 * "delete now" and Opus → original replacement. One row per song in `library.db` with its date (wall clock):
 * max(last played, computed). Aged out after 365 days and capped at 20 000 entries, oldest dropped first ([maintain]).
 *
 * Memory: the dates of all stored songs plus a small set of loaded peaks. [has], [peaks] and [ageDate] are memory-only
 * (main-thread safe); [load], [store] and [maintain] use the database: call them on IO.
 */
class WaveformStore(
    private val rows: WaveformRows,
    private val now: () -> Long,
    private val onChange: () -> Unit = {},
    private val cap: Int = CacheRules.WAVEFORM_CAP,
    /** The owning cache was retired before the account wipe; nothing is written any more. */
    private val retired: () -> Boolean = { false },
    /** Class names only. */
    private val log: (String) -> Unit = {},
) {
    companion object {
        const val VERSION = 2
        const val BARS = 200
        /** The blob: version, bar count, then the bars (big-endian ints and floats, the old `.waveform` file layout). */
        const val BLOB_BYTES = 8 + BARS * 4
        private const val LOADED = 48

        fun encode(peaks: List<Float>): ByteArray = ByteBuffer.allocate(BLOB_BYTES).apply {
            putInt(VERSION); putInt(BARS); peaks.forEach(::putFloat)
        }.array()

        /** Null when invalid. */
        fun decode(blob: ByteArray): List<Float>? = runCatching {
            require(blob.size == BLOB_BYTES)
            val buffer = ByteBuffer.wrap(blob)
            require(buffer.getInt() == VERSION && buffer.getInt() == BARS)
            List(BARS) { buffer.getFloat().also { require(it.isFinite() && it in 0f..1f) } }
        }.getOrNull()
    }

    private val ages = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val loaded = object : LinkedHashMap<String, List<Float>>(LOADED, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Float>>) = size > LOADED
    }
    private val touched = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    init {
        rows.dates()?.let(ages::putAll) ?: log("waveforms unavailable this session")
        // The newest few are what Now Playing asks for first after a restart.
        readInto(ages.entries.sortedByDescending { it.value }.take(LOADED / 2).map { it.key })
    }

    val size get() = ages.size
    /** Rows written by [store] in this session (a waveform computed again shows here; tests). */
    @Volatile var writes = 0
        private set
    fun has(songId: String) = ages.containsKey(songId)
    fun peaks(songId: String): List<Float>? = synchronized(loaded) { loaded[songId] }
    fun ageDate(songId: String): Long? = ages[songId]

    /**
     * Loads stored peaks for these songs into memory. Database: call on IO. Under the same monitor as the writes,
     * so [TrackCache.retire] waits for a running load and a later one never asks the retired account's database.
     */
    @Synchronized fun load(songIds: Collection<String>) {
        if (retired()) return
        val wanted = songIds.filter { ages.containsKey(it) && synchronized(loaded) { !loaded.containsKey(it) } }
        if (wanted.isNotEmpty() && readInto(wanted)) onChange()
    }

    private fun readInto(songIds: List<String>): Boolean {
        if (songIds.isEmpty()) return false
        val blobs = rows.read(songIds) ?: return false
        val bad = ArrayList<String>()
        var any = false
        for (id in songIds) {
            val peaks = blobs[id]?.let(::decode)
            if (peaks == null) { bad += id; continue }
            synchronized(loaded) { loaded[id] = peaks }
            any = true
        }
        // Missing or unreadable: dropped and computed again, as before.
        if (bad.isNotEmpty()) {
            bad.forEach(ages::remove)
            if (!retired()) runCatching { rows.delete(bad) }.onFailure { log("waveforms: ${it.javaClass.simpleName}") }
        }
        return any
    }

    /** One row. Database: call on IO. */
    @Synchronized fun store(songId: String, peaks: List<Float>): Boolean {
        require(peaks.size == BARS && peaks.all { it.isFinite() && it in 0f..1f })
        if (retired()) return false
        val age = maxOf(now(), ages[songId] ?: Long.MIN_VALUE)
        try { rows.write(songId, encode(peaks), age) } catch (e: Exception) { log("waveforms: ${e.javaClass.simpleName}"); return false }
        ages[songId] = age
        writes++
        synchronized(loaded) { loaded[songId] = peaks.toList() }
        onChange()
        return true
    }

    /** "Played" renews the waveform's age too. Memory only; [maintain] writes it. */
    fun touch(songId: String) {
        if (ages.containsKey(songId)) { ages[songId] = now(); touched += songId }
    }

    /** [maintain] has something to do (pending ages, over the cap, or an entry past its age limit). Memory only. */
    @Synchronized fun maintenanceDue(): Boolean {
        if (touched.isNotEmpty() || ages.size > cap) return true
        val effectiveNow = now()
        return ages.values.any { it + CacheRules.WAVEFORM_AGE_LIMIT_MS <= effectiveNow }
    }

    /** Writes pending ages and applies the 365-day / 20 000-entry rule, in one transaction. Database: call on IO. */
    @Synchronized fun maintain() {
        if (retired()) return
        val renewed = touched.toList().mapNotNull { id -> ages[id]?.let { id to it } }.toMap()
        val dropped = try { rows.maintain(renewed, now() - CacheRules.WAVEFORM_AGE_LIMIT_MS, cap) }
            catch (e: Exception) { log("waveforms: ${e.javaClass.simpleName}"); return }
        touched.removeAll(renewed.keys)
        dropped.forEach { id ->
            ages.remove(id); touched.remove(id)
            synchronized(loaded) { loaded.remove(id) }
        }
        if (dropped.isNotEmpty()) onChange()
    }
}
