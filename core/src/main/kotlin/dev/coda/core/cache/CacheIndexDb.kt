package dev.coda.core.cache

import dev.coda.core.library.LibraryStore
import dev.coda.core.playback.StreamFormat
import dev.coda.store.CacheCancelledRow
import dev.coda.store.CacheDao
import dev.coda.store.CacheLegacyRow
import dev.coda.store.CachePendingRow
import dev.coda.store.CacheSetRow
import dev.coda.store.CacheStateRow
import dev.coda.store.CacheTrackRow
import dev.coda.store.CoverStampRow
import dev.coda.store.PlayedAlbumRow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Everything the music cache persists, as one consistent state: tracks, kept sets, their pending tracks, legacy
 * files, the "removed for space" notice, the running queue download and the cancelled replacements.
 */
data class CacheSnapshot(
    val tracks: Map<String, CachedTrack> = emptyMap(),
    val sets: List<KeptItem> = emptyList(),
    val pending: Map<String, Set<String>> = emptyMap(),
    val legacy: Map<String, LegacyFile> = emptyMap(),
    val removedForSpace: RemovedForSpace? = null,
    val queueDownload: KeptItem? = null,
    val replacementsCancelled: Set<String> = emptySet(),
    /** False when [CacheIndexStore.load] could not make its caller the writer: the next save then rewrites every row. */
    val claimed: Boolean = true,
)

/** Where [TrackCache] keeps its index. Called on IO, under the cache lock. */
interface CacheIndexStore {
    /**
     * The saved index, or null for "no index" (a new, recreated or quarantined database). Throws when it cannot be read.
     * Then [writer] becomes the saved state's writer, so the loaded snapshot is the base of its next [save]; when that
     * claim cannot be written (a full disk) the snapshot says so ([CacheSnapshot.claimed]) instead of failing.
     */
    fun load(writer: String): CacheSnapshot?

    /**
     * Makes [next] the saved index in one transaction, or throws (nothing changed then). [previous] is what [writer] last
     * loaded or saved: only the difference is written, unless the saved state was written by someone else since (another
     * cache instance, or the file was recreated), in which case every row is.
     */
    fun save(writer: String, previous: CacheSnapshot?, next: CacheSnapshot)

    /** True when the saved index may no longer be what this store last read or wrote (the file was recreated). Memory only. */
    fun lost(): Boolean = false

    /** Where the cover store keeps its stamps and played albums (asked once per cache). Memory only by default. */
    fun covers(): CoverIndex = CoverIndex.Memory()

    /** The waveform rows, in the same database. */
    val waveforms: WaveformRows
}

/**
 * [CacheIndexStore] in the account's `library.db` (`cache_*` tables). [open] is asked for the open store at each use.
 * A track row whose JSON cannot be read is left out of the snapshot, logged, and kept in the file (never deleted by a
 * rewrite). An unreadable kept-set row (with its pending rows) or
 * state column is wiped at load instead, logged; its tracks are ordinary cached music from then on.
 */
class CacheIndexDb(
    private val log: (String) -> Unit = {},
    /**
     * The owning cache was retired (its account is being wiped). From then on [open] is never asked: a closed
     * stand-in answers instead (reads fall back, writes fail), so nothing reopens or recreates the old `library.db`.
     */
    private val retired: () -> Boolean = { false },
    private val open: () -> LibraryStore,
) : CacheIndexStore {
    private val closed by lazy {
        LibraryStore(java.io.File("retired"), build = { throw java.io.IOException("music cache retired") }).apply { close() }
    }
    private val store: () -> LibraryStore = { if (retired()) closed else open() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    /** Track rows that could not be decoded at the last load: kept as they are. */
    @Volatile private var keptTracks: Set<String> = emptySet()
    /** The store and its rebuild count at the last load or save ([lost]). */
    @Volatile private var seen: Pair<LibraryStore, Int>? = null
    override val waveforms: WaveformRows = WaveformDb(store)

    private fun <T> encode(serializer: KSerializer<T>, value: T) = json.encodeToString(serializer, value)
    private fun <T> decode(serializer: KSerializer<T>, text: String): T? =
        runCatching { json.decodeFromString(serializer, text) }.getOrNull()

    override fun lost(): Boolean = seen?.let { (store, rebuilds) -> store.rebuilds != rebuilds } ?: false

    override fun load(writer: String): CacheSnapshot? = runBlocking {
        val current = store()
        current.transact { db ->
            val dao = db.cache()
            val state = dao.state() ?: return@transact null
            val badTracks = HashSet<String>()
            val tracks = dao.tracks().mapNotNull { row -> track(row) ?: null.also { badTracks += row.songId } }
            val badSets = HashSet<String>()
            val sets = dao.sets().mapNotNull { row ->
                decode(KeptItem.serializer(), row.item)?.copy(addedAt = row.addedAt) ?: null.also { badSets += row.key }
            }
            val removed = state.removedForSpace?.let { decode(RemovedForSpace.serializer(), it) }
            val queue = state.queueDownload?.let { decode(KeptItem.serializer(), it) }
            val badState = (state.removedForSpace != null && removed == null) || (state.queueDownload != null && queue == null)
            if (badTracks.isNotEmpty() || badSets.isNotEmpty() || badState)
                log("music cache index: ${badTracks.size} track rows unreadable, kept as they are; ${badSets.size} set rows" +
                    (if (badState) " and state" else "") + " unreadable, wiped")
            keptTracks = badTracks
            val pending = dao.pending().groupBy({ it.setKey }, { it.songId }).mapValues { it.value.toSet() }
            // The claim below wipes what could not be read: the unreadable sets with their pending rows, and the state columns.
            Wipe(state.copy(removedForSpace = state.removedForSpace.takeIf { removed != null },
                queueDownload = state.queueDownload.takeIf { queue != null }), badSets, pending.filterKeys { it in badSets }) to CacheSnapshot(
                tracks = tracks.associateBy { it.songId },
                sets = sets,
                pending = pending.filterKeys { it !in badSets },
                legacy = dao.legacy().associate { it.name to LegacyFile(it.name, it.bytes, it.datedAt) },
                removedForSpace = removed,
                queueDownload = queue,
                replacementsCancelled = dao.cancelled().toSet(),
            )
        }?.let { (wipe, snapshot) ->
            // A claim that fails (SQLITE_FULL, ...) is not a reason to go without the index: the next save writes it all
            // (a full rewrite, which also deletes the unreadable set rows and writes the state afresh).
            val claimed = try {
                current.transact { db ->
                    val dao = db.cache()
                    dao.upsertState(wipe.state.copy(writer = writer))
                    wipe.pending.forEach { (key, ids) -> ids.toList().chunked(CHUNK).forEach { dao.deletePending(key, it) } }
                    if (wipe.sets.isNotEmpty()) { wipe.sets.toList().chunked(CHUNK).forEach { dao.deleteSets(it) }; db.pages().syncPins() }
                }
                true
            } catch (e: Exception) { log("music cache index: writer not recorded: ${e.javaClass.simpleName}"); false }
            snapshot.copy(claimed = claimed)
        }.also { seen = current to current.rebuilds }
    }

    override fun save(writer: String, previous: CacheSnapshot?, next: CacheSnapshot) = runBlocking {
        val current = store()
        current.transact { db ->
            val dao = db.cache()
            val base = previous?.takeIf { dao.state()?.writer == writer }
            if (base == null) {
                // Every row: the rows of this snapshot replace the others, except track rows kept because they were unreadable.
                (dao.trackIds() - next.tracks.keys - keptTracks).chunked(CHUNK).forEach { dao.deleteTracks(it) }
                (dao.setKeys() - next.sets.map { it.key }.toSet()).chunked(CHUNK).forEach { dao.deleteSets(it) }
                dao.clearPending(); dao.clearLegacy(); dao.clearCancelled()
                write(dao, CacheSnapshot(), next, all = true)
            } else write(dao, base, next, all = false)
            // The pages of kept sets are pinned in this same transaction, so a set and its page never disagree.
            if (base == null || base.sets.map { it.key } != next.sets.map { it.key }) db.pages().syncPins()
            // Each column is this cache's own value (an unreadable one was wiped at load, never a fallback).
            dao.upsertState(CacheStateRow(0, writer,
                next.removedForSpace?.let { encode(RemovedForSpace.serializer(), it) },
                next.queueDownload?.let { encode(KeptItem.serializer(), it) }))
        }
        seen = current to current.rebuilds
    }

    private suspend fun write(dao: CacheDao, old: CacheSnapshot, next: CacheSnapshot, all: Boolean) {
        next.tracks.values.filter { all || old.tracks[it.songId] != it }.map(::row).chunked(CHUNK).forEach { dao.upsertTracks(it) }
        (old.tracks.keys - next.tracks.keys).toList().chunked(CHUNK).forEach { dao.deleteTracks(it) }

        next.sets.mapIndexedNotNull { position, set ->
            set.takeIf { all || old.sets.getOrNull(position) != it }?.let { CacheSetRow(it.key, position, it.addedAt, encode(KeptItem.serializer(), it)) }
        }.chunked(CHUNK).forEach { dao.upsertSets(it) }
        (old.sets.map { it.key }.toSet() - next.sets.map { it.key }.toSet()).toList().chunked(CHUNK).forEach { dao.deleteSets(it) }

        for (key in old.pending.keys + next.pending.keys) {
            val before = old.pending[key].orEmpty()
            val after = next.pending[key].orEmpty()
            (after - before).map { CachePendingRow(key, it) }.chunked(CHUNK).forEach { dao.upsertPending(it) }
            (before - after).toList().chunked(CHUNK).forEach { dao.deletePending(key, it) }
        }

        next.legacy.values.filter { old.legacy[it.name] != it }.map { CacheLegacyRow(it.name, it.bytes, it.datedAt) }
            .chunked(CHUNK).forEach { dao.upsertLegacy(it) }
        (old.legacy.keys - next.legacy.keys).toList().chunked(CHUNK).forEach { dao.deleteLegacy(it) }

        (next.replacementsCancelled - old.replacementsCancelled).map(::CacheCancelledRow).chunked(CHUNK).forEach { dao.upsertCancelled(it) }
        (old.replacementsCancelled - next.replacementsCancelled).toList().chunked(CHUNK).forEach { dao.deleteCancelled(it) }
    }

    override fun covers(): CoverIndex = object : CoverIndex {
        // A stamp or a played album is a hint (revalidation, which covers to complete): a failed write is logged by the
        // store and dropped, never a reason to keep a cover off the disk.
        override fun load(): CoverIndex.Saved = runBlocking {
            store().use(CoverIndex.Saved()) { db ->
                CoverIndex.Saved(db.covers().stamps().associate { it.name to it.stamp }, db.covers().played().toSet())
            }
        }
        override fun saveStamp(name: String, stamp: String) = runBlocking { store().use(Unit) { it.covers().upsertStamp(CoverStampRow(name, stamp)) } }
        override fun savePlayed(ids: Collection<String>) = runBlocking {
            store().use(Unit) { db -> ids.map(::PlayedAlbumRow).chunked(CHUNK).forEach { db.covers().upsertPlayed(it) } }
        }
    }

    /** What the load's claim wipes: the state row with its unreadable columns cleared, and the unreadable sets' pending rows. */
    private class Wipe(val state: CacheStateRow, val sets: Set<String>, val pending: Map<String, Set<String>>)

    private fun row(track: CachedTrack) = CacheTrackRow(track.songId, track.file, track.kind.name, track.bytes, track.addedAt,
        track.lastPlayedAt, track.playCount, track.contentType, encode(SongFormat.serializer(), track.format),
        track.meta?.let { encode(TrackMeta.serializer(), it) })

    /** Null when a column cannot be read (the row is then kept as it is, see the class comment). */
    private fun track(row: CacheTrackRow): CachedTrack? {
        val kind = StreamFormat.entries.firstOrNull { it.name == row.kind } ?: return null
        val format = decode(SongFormat.serializer(), row.format) ?: return null
        val meta = row.meta?.let { decode(TrackMeta.serializer(), it) ?: return null }
        return CachedTrack(row.songId, row.file, kind, row.bytes, row.addedAt, row.lastPlayedAt, row.contentType, format, meta, row.playCount)
    }

    private companion object {
        /** Rows per statement: well under SQLite's bound-variable limit. */
        const val CHUNK = 500
    }
}
