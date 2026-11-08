package dev.coda.core.cache

import dev.coda.core.connectivity.NetworkState
import dev.coda.core.playback.*
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

private fun digest(algorithm: String, value: String) = MessageDigest.getInstance(algorithm)
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }

/** Stable, credential-free file base for a song: one cached copy per song, whatever its format. */
fun songFileBase(songId: String) = digest("SHA-1", "song:$songId")

/** A download request: what is asked of the server. What arrived is recorded in [CachedTrack.kind]. */
data class TrackKey(val songId: String, val format: StreamFormat) {
    /** Name of a complete file of this kind: `.orig` for an original, `.opus` for an Opus 128 transcode. */
    val fileName: String get() = songFileBase(songId) + extension(format)

    companion object {
        fun extension(format: StreamFormat) = if (format == StreamFormat.RAW) ".orig" else ".opus"
    }
}

object TrackCachePlanner {
    /** Read-ahead: current + next N, plus the previous song. It is supplied separately: shuffle/skip does not imply index - 1. */
    fun playbackWindow(queue: List<String>, current: Int?, ahead: Int, previous: String?): List<String> =
        ((current?.let { queue.drop(it.coerceAtLeast(0)).take(ahead.coerceIn(2, 10) + 1) }.orEmpty()) +
            listOfNotNull(previous)).distinct()
}

/** Free space as Android reports it to apps (`StorageManager.getAllocatableBytes`), and up-front reservation. */
interface FreeSpace {
    fun allocatableBytes(): Long
    /** Free space as the user sees it, compared with Keep free space. Tests: [allocatableBytes]. */
    fun freeBytes(): Long = allocatableBytes()
    /** Reserves [bytes] for the open file (`StorageManager.allocateBytes`); throws [IOException] when it can't. */
    fun allocate(fd: FileDescriptor, bytes: Long) {}

    companion object {
        /** JVM fallback (tests, tools): the volume's usable space, no reservation. */
        fun of(directory: File) = object : FreeSpace {
            override fun allocatableBytes() = directory.usableSpace
        }
    }
}

/** What a cached file is, and the dates its keep-until is computed from (CA-2 "Per-track record"). */
@Serializable
data class CachedTrack(
    val songId: String,
    val file: String,
    /** What arrived: the original as stored on the server ([StreamFormat.RAW]), or an Opus 128 transcode. */
    val kind: StreamFormat,
    val bytes: Long,
    /** Wall-clock ms (plain dates). Renewed by an explicit add. */
    val addedAt: Long,
    /** Wall-clock ms: when the track last became current. */
    val lastPlayedAt: Long? = null,
    val contentType: String? = null,
    val format: SongFormat = SongFormat(),
    val meta: TrackMeta? = null,
    /**
     * Plays since the file arrived, +1 each time the track becomes current. Absent in CA-2
     * records: one with a [lastPlayedAt] then reads as heard once ([CachePolicy.keepUntil]).
     */
    val playCount: Int = 0,
) {
    /** Least recently used first among equal keep-until dates. */
    val lastUsed get() = maxOf(addedAt, lastPlayedAt ?: Long.MIN_VALUE)
    val original get() = kind == StreamFormat.RAW
    /** Plays counted: an old record with a play date but no count was heard once. */
    val plays get() = maxOf(playCount, if (lastPlayedAt != null) 1 else 0)
    /** The original's bitrate against the 320 kbit/s cut; null when the server didn't say. */
    val above320: Boolean? get() = format.bitRateKbps?.takeIf { it > 0 }?.let { it > CacheRules.PASS_THROUGH_MAX_KBPS }
}

/**
 * An audio file found without an index (a new or recreated database), kept in `legacy/` until its song is next
 * seen and adopted; aged like any track from [datedAt] (wall-clock ms).
 */
@Serializable
data class LegacyFile(val name: String, val bytes: Long, val datedAt: Long)

private const val LEGACY = "legacy"
private const val ACTIVE_BITS = 24
private const val ACTIVE_MASK = (1L shl ACTIVE_BITS) - 1
private const val SETTLE_TRIES = 25
private const val SETTLE_MS = 4L
/** The longest [TrackCache.measureSettled] holds the cache lock. */
private const val SETTLE_BUDGET_MS = 100L
/** One maintenance tick: kept music is not removed within this long of Coda's own last delete (free space may return lazily). */
private const val OWN_DELETE_WAIT_MS = 60_000L
/** How long a kept set the user confirmed on the settings sheet, but that has not left yet, stays exempt from the notice. */
private const val CONFIRMED_MS = 10 * 60_000L
private const val EPOCH_BEGIN = (1L shl ACTIVE_BITS) + 1
private const val EPOCH_END = (1L shl ACTIVE_BITS) - 1
private const val STALE_PART_MS = 7 * CacheRules.DAY_MS
/** How often maintenance looks for stale partial files (besides at start). */
/** The least margin above the line that counts as near it: 500 MB (a few minutes of a camera or a download). */
const val NEAR_LINE_MIN_BYTES = 500L * 1_000_000
private const val STALE_SWEEP_MS = 6 * 60 * 60_000L
/** How far free space may move from a remembered idle pass below the line before the next tick runs in full. */
private const val IDLE_FREE_SLACK_BYTES = 50L * 1_000_000
private const val EARLY_PLAYS = 256
/** [TrackCache.removeTrack]'s "removed, but no file to delete now" (the player holds it). */
private val NOTHING = File("")
private val AUDIO_NAME = Regex("[a-f0-9]{40}\\.(orig|opus)")

/** Deletes files the player has released, off the main thread. */
private val backgroundDeletes: java.util.concurrent.Executor by lazy {
    java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task, "coda-cache-deletes").apply { isDaemon = true } }
}

/**
 * The one music cache: everything Coda plays or reads ahead, plus added albums, playlists, tracks and queues.
 * Each track stays until its keep-until under the [policy]; space is governed by the Keep free space line, with
 * kept music last and whole ([CachePlanner]). Dates are plain wall-clock dates. While the server is reachable,
 * expired music leaves at the next cleanup; while it isn't (or not known yet), nothing leaves for being old, only for
 * Keep free space (expired first). The [index] (`library.db`) is the truth: once it exists, files it doesn't list are
 * removed at start; without one (a new or recreated database) the audio found is adopted, never deleted.
 *
 * Construct, write, [makeRoom] and [housekeep] on IO. Lookups, [pin], [acquire], [markPlayed] and [status] touch only
 * memory under short locks, so they are safe on the main thread.
 */
class TrackCache(
    val directory: File,
    /** The persisted index (tracks, kept sets, pending, legacy files, queue download, cancelled replacements). */
    private val index: CacheIndexStore,
    private val freeSpace: FreeSpace = FreeSpace.of(directory),
    /** Runs deletions of files the player released ([retainLeases] is called on the main thread). */
    private val deletes: java.util.concurrent.Executor = backgroundDeletes,
    /** Music storage settings, read at each use so a change applies to the whole cache at once. */
    private val policy: () -> CachePolicy = { CachePolicy.DEFAULT },
    /** Class names and counts only (no paths, no library data). */
    private val log: (String) -> Unit = {},
    private val wall: () -> Long = System::currentTimeMillis,
) {
    companion object {
        private fun canonicalServer(serverUrl: String): String {
            val uri = java.net.URI(serverUrl)
            val port = uri.port.takeUnless { it == -1 ||
                (uri.scheme.equals("http", true) && it == 80) ||
                (uri.scheme.equals("https", true) && it == 443) }
            val host = requireNotNull(uri.host).lowercase(java.util.Locale.ROOT)
            return "https://$host" + (port?.let { ":$it" } ?: "") + uri.rawPath.orEmpty().trimEnd('/')
        }
        fun serverKey(serverUrl: String, username: String) = digest("SHA-256", "${canonicalServer(serverUrl)}\n$username").take(16)
    }

    private val legacyDir = File(directory, LEGACY)
    /**
     * The account's durable artwork store (`covers/`): never evicted, counted against Keep free space like kept
     * music (a write never pushes free space below the line, less maintenance's allowance; unkept songs give way, see
     * [putCover]).
     */
    val covers = CoverStore(File(directory, "covers"), index.covers(), { bytes -> coverShortfall(bytes) <= 0 }, { retired })
    private val tracks = java.util.concurrent.ConcurrentHashMap<String, CachedTrack>()
    private val legacy = java.util.concurrent.ConcurrentHashMap<String, LegacyFile>()
    @Volatile private var sets: List<KeptItem> = emptyList()
    /**
     * Set key → tracks still to download since its last add or re-sync. A track leaves once it is cached; one that later
     * ages out, is evicted or deleted is not fetched again until the set is added again. Written under the cache lock;
     * read lock-free.
     */
    private val pending = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()
    /**
     * Plays of songs not cached yet (streamed, or still downloading): merged into the record when it is committed.
     * Oldest first; guarded by [protection]. Past [EARLY_PLAYS] entries the oldest goes, but never one whose download
     * is outstanding (running, or pending in an added set).
     */
    private val earlyPlays = LinkedHashMap<String, EarlyPlay>()
    private data class EarlyPlay(val at: Long, val count: Int)
    /** Song → keys of the kept sets holding it, and song → the newest keep date among them; rebuilt with [sets]. */
    @Volatile private var holders: Map<String, Set<String>> = emptyMap()
    @Volatile private var keptAt: Map<String, Long> = emptyMap()
    /** The notice of kept sets removed for space. */
    @Volatile var removedForSpace: RemovedForSpace? = null
        private set
    /** Wall time of Coda's own last delete of music (any path); kept music waits [OWN_DELETE_WAIT_MS] after it. */
    @Volatile private var ownDeleteAt: Long? = null
    /** Kept sets a confirmed settings sheet named whose removal is deferred: key to the confirmation time. Memory only. Under the cache lock. */
    private val confirmedSets = HashMap<String, Long>()
    /** The running "Cache queue ahead" download (not an added set), persisted with the index; null when none runs. */
    @Volatile var queueDownload: KeptItem? = null
        private set
    /** Cancel: Opus copies whose replacement by the original the user cancelled, until their set is added again. */
    @Volatile var replacementsCancelled: Set<String> = emptySet()
        private set
    /** This instance's name in the index's state row, and what it last loaded or saved (the base of the next write). */
    private val writer = java.util.UUID.randomUUID().toString()
    private var persisted: CacheSnapshot? = null
    /**
     * Whether old music may leave for being old: only while the server is known to be reachable. Offline (or before
     * the first verdict) music leaves only for Keep free space.
     */
    @Volatile private var online: Boolean? = null
    /** Test hook: called after each durable step of a replacement or adoption; a throw simulates a crash there. */
    internal var crashPoint: (String) -> Unit = {}
    @Volatile var revision: Long = 0
        private set
    @Volatile private var dirty = false
    val waveforms: WaveformStore
    /**
     * True once [retire]d: this cache belongs to an account whose folder is being deleted (or, DB-1, whose index could not
     * be read). Declared before init, which may set it.
     */
    @Volatile var retired = false
        private set
    /** The index could not be read at start: this session keeps no music and writes nothing (see init). */
    @Volatile var indexUnavailable = false
        private set
    /**
     * The start's save failed (a full disk): the cache runs on what it read, deletes no music, and saves
     * again at the next flush or maintenance pass; the first save that works ends it. No music leaves meanwhile
     * (maintenance, room for a download or a cover, "Make room?").
     */
    @Volatile private var degraded = false
    /** The start's adoption of kept legacy files may have stopped at the failed save: run again once saves work. */
    private var startAdoptionDue = false
    /**
     * Each running write's admission, as the total size its file was admitted to reach ([makeRoom]); the
     * part not on disk yet ([outstanding]) is spent space for every other admission, so two downloads (each Prefetcher
     * has its own lock) never both count the same free bytes. Dropped when the write closes (done, failed, cancelled).
     */
    private val admitted = java.util.concurrent.ConcurrentHashMap<Write, Long>()
    // Guards pins, leases, doomed files and writers; never held during file I/O.
    private val protection = Any()
    private var pins = emptySet<String>()
    /**
     * The songs pinned or being written at any moment of the running maintenance pass (null: none runs): seeded with
     * those in flight when it starts, fed by every [pin] and [begin] until it ends, never shrinking. The window
     * allowance of every measurement in the pass (planning, protection, apply) is taken from it, so a song that was
     * pinned during planning and unpinned before apply keeps its allowance to the end. Guarded by [protection].
     */
    private var runWindow: MutableSet<String>? = null
    private val leases = mutableSetOf<String>()
    /** Deleted while the player held them: removed once released. */
    private val doomed = mutableSetOf<String>()
    private val writers = mutableSetOf<String>()
    /**
     * File bases (`songFileBase`) that may have a partial file on disk: found at start, begun since, not committed
     * since; refreshed from the folder whenever maintenance lists it. Guarded by [protection].
     */
    private val partBases = HashSet<String>()
    /**
     * A partial file may have become an orphan (start, a write ended without its commit, a song with a partial file
     * left the window or every kept set): the next pass must list the folder ([reclaimOrphanParts]).
     */
    @Volatile private var orphanCheckDue = true
    /** Wall time of the last sweep for stale partial files (null: none since start). Under the cache lock. */
    private var staleSweptAt: Long? = null

    /** The inputs of a maintenance pass (see [idleBelow]). */
    private data class PassInputs(val free: Long, val policy: CachePolicy, val revision: Long, val online: Boolean?,
        val guard: Triple<Set<String>, Set<String>, Set<String>>, val ownDeleteAt: Long?)
    /**
     * The inputs of the last full pass below the line that deleted nothing and planned nothing although space was
     * short (memory only, so a fresh start runs in full). With nothing removable for space, a bigger or smaller need does
     * not make anything removable; only a change of what is cached, kept, protected or allowed does. While the inputs are
     * the same, [due] does not count the line. Any change clears it. Under the cache lock.
     */
    private var idleBelow: PassInputs? = null
    /** The free space [due] read last (the pass's start inputs reuse it: no second query). Under the cache lock. */
    private var dueFree = 0L
    private fun passInputs(policy: CachePolicy, free: Long) = PassInputs(free, policy, revision, online, guard(), ownDeleteAt)
    private val events = kotlinx.coroutines.flow.MutableStateFlow(0L)
    /**
     * Bumps after each song written (commit) and each track that became current ([markPlayed]): the moments the
     * maintenance pass looks again (OfflineLibrary), instead of a fast timer.
     */
    val maintenanceEvents: kotlinx.coroutines.flow.StateFlow<Long> get() = events
    private fun event() = events.update { it + 1 }

    /**
     * The writer epoch (a seqlock over the partial files). Every change of on-disk partial or committed bytes that can
     * run outside the cache lock (reserve, write, truncate, restart, checkpoint, close, commit, begin) is bracketed by
     * [writerChange]: a begin and an end each add one to the count in the high bits, and the low [ACTIVE_BITS] hold the
     * changes running right now. A measurement is coherent only if no change was running when it started and the value
     * is the same when it ended; otherwise it is thrown away and kept music is not removed on it. No lock: playback and
     * downloads only do an atomic add.
     */
    private val writerEpoch = java.util.concurrent.atomic.AtomicLong()

    private inline fun <T> writerChange(block: () -> T): T {
        writerEpoch.addAndGet(EPOCH_BEGIN)
        try { return block() } finally { writerEpoch.addAndGet(EPOCH_END) }
    }

    init {
        directory.mkdirs()
        // Null is "no index" (a new, recreated or quarantined database): the audio found is kept as legacy files and
        // adopted again by song, never deleted.
        val saved = try { Result.success(index.load(writer)) } catch (error: Exception) { Result.failure(error) }
        waveforms = WaveformStore(index.waveforms, wall, { revision++ }, retired = { retired }, log = log)
        if (saved.isFailure) {
            // A database that cannot be read now (a missing migration, a full disk, ...) is not "no index": this
            // session runs without one, writes nothing and touches no file, so the music is there when it can be read.
            log("Music cache index unavailable this session: ${saved.exceptionOrNull()?.javaClass?.simpleName}")
            indexUnavailable = true
            retired = true
        } else open(saved.getOrNull())
    }

    /** The start with a readable index ([saved], or null for none). */
    private fun open(saved: CacheSnapshot?) {
        persisted = saved?.takeIf { it.claimed }
        removedForSpace = saved?.removedForSpace
        queueDownload = saved?.queueDownload
        replacementsCancelled = saved?.replacementsCancelled.orEmpty()
        // A row whose file has not arrived (a crash between the index write and the file move) takes the copy that is there.
        saved?.tracks?.values?.forEach { record -> restore(record)?.let { tracks[record.songId] = it } }
        saved?.legacy?.values?.forEach { if (File(legacyDir, it.name).isFile) legacy[it.name] = it }
        updateSets(saved?.sets.orEmpty())
        covers.open()
        // A row with no copy left was a download whose file never took its name (the row is written first): a set
        // holding it waits for it again.
        val lost = saved?.tracks?.keys.orEmpty() - tracks.keys
        sets.forEach { set ->
            pending[set.key] = (saved?.pending?.get(set.key).orEmpty() + set.trackIds.filter { it in lost })
                .filterNot { tracks.containsKey(it) }.toSet()
        }
        val referenced = tracks.values.map { it.file }.toSet()
        val today = wall()
        // Copies of songs that are cached under another name (an Opus copy an original replaced): superseded.
        val superseded = tracks.keys.flatMap(::legacyNames).toSet() - referenced
        directory.listFiles()?.forEach { file ->
            val name = file.name
            when {
                file.isDirectory -> Unit
                name.endsWith(".part") || name.endsWith(".part.at") -> partBases += name.take(40) // Originals and transcodes resume
                name in referenced -> Unit
                name in superseded -> file.delete()
                // A complete audio file without a row (no index, or a row lost with the last
                // commit) is kept as a legacy file, adopted when its song is seen again or aged out like any track.
                name.matches(AUDIO_NAME) -> {
                    legacyDir.mkdirs()
                    if (file.renameTo(File(legacyDir, name))) legacy[name] = LegacyFile(name, File(legacyDir, name).length(), today)
                }
                else -> file.delete() // not music: leftovers of an interrupted write
            }
        }
        val unlisted = legacyDir.listFiles().orEmpty().filter { it.name.matches(AUDIO_NAME) && !legacy.containsKey(it.name) }
        // Leftovers of an adoption interrupted after its move: the song is cached already.
        val adopted = if (unlisted.isEmpty()) emptySet() else tracks.keys.flatMap(::legacyNames).toSet()
        unlisted.forEach { file ->
            if (file.name in adopted) file.delete()
            else legacy[file.name] = LegacyFile(file.name, file.length(), today)
        }
        // A save that fails here (a full disk) does not keep the readable cache from opening.
        try {
            adoptKept()
            persist()
        } catch (error: Exception) {
            log("Music cache index not saved at start: ${error.javaClass.simpleName}; no music is deleted until it is")
            degraded = true; startAdoptionDue = true
        }
    }

    /** Kept tracks are known by ID: adopt their legacy files (each adoption saves first; a failed one stays legacy). */
    private fun adoptKept() {
        if (legacy.isNotEmpty()) sets.flatMap { it.tracks }.distinctBy { it.id }.forEach { adoptLegacy(it.id, it.format, it.meta) }
    }

    /**
     * A saved record as it is on disk after a crash. Commits and adoptions save the new row before the file takes
     * its name, and the old copy goes only after both, so an interruption leaves either copy: an Opus record next to a
     * complete original takes the original; an original's record whose file was never renamed into place takes the Opus
     * copy it was replacing; a record whose file never arrived from the legacy folder finishes that move. Null when no
     * copy of the song is left (a new download's `.part` stays and resumes).
     */
    private fun restore(record: CachedTrack): CachedTrack? {
        val base = record.file.substringBefore('.')
        val original = File(directory, "$base.orig")
        val own = File(directory, record.file)
        val opus = File(directory, "$base.opus")
        return when {
            record.kind == StreamFormat.OPUS_128 && original.isFile ->
                record.copy(file = original.name, kind = StreamFormat.RAW, bytes = original.length(), contentType = null)
            own.isFile -> record.copy(bytes = own.length())
            record.kind == StreamFormat.RAW && opus.isFile ->
                record.copy(file = opus.name, kind = StreamFormat.OPUS_128, bytes = opus.length(), contentType = null)
            else -> {
                val candidates = legacyCandidates(record.songId, record.format) { File(legacyDir, it).isFile }
                val chosen = candidates.firstOrNull() ?: return null
                val target = File(directory, TrackKey(record.songId, chosen.kind).fileName)
                if (!chosen.file.renameTo(target)) return null
                candidates.forEach { if (it !== chosen) it.file.delete() }
                record.copy(file = target.name, kind = chosen.kind, bytes = target.length())
            }
        }
    }

    // ---- Lookups (memory only) ----

    fun record(songId: String): CachedTrack? = tracks[songId]
    fun kind(songId: String): StreamFormat? = tracks[songId]?.kind
    fun contains(songId: String) = tracks.containsKey(songId)
    /** The complete file of exactly this kind, if that is what is cached. */
    fun find(key: TrackKey): File? = tracks[key.songId]?.takeIf { it.kind == key.format }?.let { File(directory, it.file) }
    /** Any complete copy. */
    fun resolve(songId: String): File? = tracks[songId]?.let { File(directory, it.file) }
    fun sizeBytes() = tracks.values.sumOf { it.bytes } + legacy.values.sumOf { it.bytes } + covers.sizeBytes()

    /** The cover ids the kept albums, playlists and tracks name (their tracks' album covers). */
    fun keptCoverIds(): Set<String> = coverIds(sets)
    private fun coverIds(sets: List<KeptItem>): Set<String> =
        sets.filter { it.key != DownloadLines.QUEUE_KEY }.flatMapTo(HashSet()) { set -> set.tracks.mapNotNull { it.meta?.coverArtId } + listOfNotNull(set.coverArt) }

    /**
     * Stores a cover copy in the durable store. Artwork counts like kept music: when it would push free space
     * below the Keep free space line, music that isn't kept makes room first ([makeRoom]); if nothing can, the copy is not
     * stored here (the fetcher keeps it in the old picture cache, Rex P2-1 on ART-1). The cache lock is taken only then. IO.
     */
    fun putCover(id: String, size: Int, bytes: ByteArray, stamp: String? = null): Boolean {
        // Rex R2-P3-1: a store write that throws counts like a refusal, so the stale copy is dropped below as well.
        fun put() = runCatching { covers.put(id, size, bytes, stamp) }.getOrDefault(false)
        if (put()) return true
        if (!retired && bytes.isNotEmpty() && roomForCover(bytes.size.toLong()) && put()) return true
        // A changed cover that cannot be stored must not leave its stale copy to be fetched again on every display.
        covers.dropStale(id, size, stamp)
        return false
    }

    /**
     * Bytes a cover of [incoming] bytes would leave short of the Keep free space line, with maintenance's allowance (the
     * read-ahead window's copies, partials, held files, see [measure]) taken out: those may sit below the line. IO.
     */
    private fun coverShortfall(incoming: Long): Long =
        policy().keepFreeBytes - doomedBytes() - partBytes() - windowBytes() - (freeSpace.freeBytes() - outstanding() - incoming)

    /**
     * On ART-1: room for one cover. Unkept music gives way only for the cover's own bytes: when the phone is short
     * by more than that, the shortfall is maintenance's, and the cover is refused (the old picture cache takes it), so
     * storing covers never clears the unkept songs. IO.
     */
    @Synchronized private fun roomForCover(incoming: Long): Boolean {
        val short = coverShortfall(incoming)
        if (short <= 0) return true
        if (short > incoming || degraded) return false
        val policy = policy()
        val plan = CachePlanner.plan(files(policy, emptySet()), keptSets(), wall(), policy, short, keptToo = false, expire = false)
        if (plan.evict.isNotEmpty()) remove(plan.evict)
        return coverShortfall(incoming) <= 0
    }

    /** A pull-to-refresh of a cover that succeeded (begun at [since], ms): the other sizes stored before it go. IO. */
    fun refreshCover(id: String, size: Int, bytes: ByteArray, since: Long, stamp: String? = null): Boolean =
        covers.refresh(id, size, bytes, since, stamp)
    fun records(): List<CachedTrack> = tracks.values.toList()
    val addedSets: List<KeptItem> get() = sets
    fun isAdded(key: String) = sets.any { it.key == key }

    // ---- Protection ----

    /** Read-ahead window songs (previous + current + next N); replaced as a whole by their single owner. */
    fun pin(songIds: Collection<String>) {
        val ids = songIds.toSet()
        synchronized(protection) {
            // A song with a partial file that left the window may now be an orphan.
            if (ids != pins && partBases.isNotEmpty() && pins.any { it !in ids && songFileBase(it) in partBases }) orphanCheckDue = true
            pins = ids; runWindow?.addAll(ids)
        }
    }

    /**
     * Resolve for playback hand-off and lease the file in the same step: nothing can delete it until [retainLeases]
     * drops it. With [kind], only a copy of that kind.
     */
    fun acquire(songId: String, kind: StreamFormat? = null): File? = synchronized(protection) {
        tracks[songId]?.takeIf { kind == null || it.kind == kind }?.let { leases += it.file; File(directory, it.file) }
    }

    /**
     * Keep leases only for files the engine may still open (paths of its current sources). Released doomed files go
     * on the background executor: this runs on the main thread and touches only memory.
     */
    fun retainLeases(paths: Collection<String>) {
        val names = paths.map { File(it).name }.toSet()
        val released = synchronized(protection) {
            leases.retainAll(names)
            doomed.filter { it !in leases }
        }
        if (released.isNotEmpty()) deletes.execute { deleteReleased(released) }
    }

    /**
     * Under the cache lock, so no commit or adoption runs in between: a file that a newer copy of the song has taken
     * the name of since it was released (the record points at it again), or that the player holds again, stays.
     */
    private fun deleteReleased(names: Collection<String>) = synchronized(this) {
        val referenced = tracks.values.mapTo(HashSet()) { it.file }
        val held = synchronized(protection) { leases.toSet() }
        val gone = names.filter { it !in referenced && it !in held }
        gone.forEach { File(directory, it).delete() }
        if (gone.isNotEmpty()) noteOwnDelete()
        // Still doomed (counted by [doomedBytes]) until the file is really gone, so a measurement never sees them in between.
        synchronized(protection) { doomed.removeAll(gone.toSet()) }
    }

    // ---- Played ----

    /**
     * The track became current: one more play (the first keeps it Heard once, the second makes it Heard again). A
     * song that isn't cached yet (streamed, or still downloading) keeps the date and the count until its download
     * commits. Memory only; [flush] persists it.
     */
    fun markPlayed(songId: String, meta: TrackMeta? = null) {
        // Under the protection lock (always taken before a record's own lock): a commit can't slip in between the
        // check and the early play, and a confirmed "Make room?" sees either the old or the new date.
        val played = synchronized(protection) {
            val now = wall()
            tracks.compute(songId) { _, record ->
                if (record == null) { rememberEarlyPlay(songId, now); null }
                else record.copy(lastPlayedAt = now, meta = meta?.over(record.meta) ?: record.meta, playCount = record.plays + 1)
            } != null
        }
        if (played) { dirty = true; revision++ }
        // Its album's 472 and 1440 copies are wanted (fetched later, on Wi-Fi with the screen on; never now).
        (meta ?: tracks[songId]?.meta)?.coverArtId?.let(covers::markPlayed)
        waveforms.touch(songId)
        event()
    }

    /** Under [protection]. */
    private fun rememberEarlyPlay(songId: String, at: Long) {
        val count = earlyPlays.remove(songId)?.count ?: 0
        earlyPlays[songId] = EarlyPlay(at, count + 1)
        if (earlyPlays.size <= EARLY_PLAYS) return
        earlyPlays.keys.firstOrNull { id -> id !in writers && pending.values.none { id in it } }?.let { earlyPlays.remove(it) }
    }

    /** Under [protection], inside tracks.compute for [songId]: its plays from before it was cached (taken out). */
    private fun takeEarlyPlay(songId: String): EarlyPlay? = earlyPlays.remove(songId)

    private fun later(a: Long?, b: Long?): Long? = listOfNotNull(a, b).maxOrNull()

    // ---- Space ----

    private fun guard() = synchronized(protection) { Triple(pins, leases.toSet(), writers.toSet()) }

    /** The keep-until of [record] under [policy]. */
    fun keepUntil(record: CachedTrack, policy: CachePolicy = this.policy()): Long = policy.keepUntil(record, keptAt[record.songId])

    private fun orderKey(record: CachedTrack): String = record.meta.let { meta ->
        "${meta?.albumId.orEmpty()}\u0000${"%06d".format(meta?.disc ?: 0)}\u0000${"%06d".format(meta?.track ?: 0)}\u0000${record.file}"
    }

    /** Every cached file with its keep-until under [policy]; [protect]ed songs, pins, leases and writes aren't removable. */
    private fun files(policy: CachePolicy, protect: Set<String>): List<Evictable> {
        val (pinned, leased, writing) = guard()
        return tracks.values.map { record ->
            Evictable(record.file, record.bytes, keepUntil(record, policy), record.lastUsed, record.songId, record.lastPlayedAt,
                orderKey(record), removable = record.songId !in protect && record.songId !in pinned &&
                    record.songId !in writing && record.file !in leased)
        } + legacy.values.map { Evictable("$LEGACY/${it.name}", it.bytes, plusSaturating(it.datedAt, policy.heardOnce.ms), it.datedAt) }
    }

    /**
     * The music maintenance could remove for space now: committed songs that are not pinned (the read-ahead
     * window), leased (the player's files) or being written, as in [files], plus legacy files. The status card's "To clean
     * up" and its state 3 versus state 4. Memory only, no lock (the maps are concurrent; a stale value lasts one poll).
     */
    fun removableMusicBytes(): Long {
        val (pinned, leased, writing) = guard()
        return tracks.values.filter { it.songId !in pinned && it.songId !in writing && it.file !in leased }.sumOf { it.bytes } +
            legacy.values.sumOf { it.bytes }
    }

    private fun keptSets(sets: List<KeptItem> = this.sets) = sets.map { CachePlanner.KeptSet(it.key, title(it), it.addedAt, it.trackIds.toSet()) }

    /** The name a kept set is listed by (sheet, "Make room?", the notice): its title, else its album's name. */
    private fun title(set: KeptItem): String = set.title ?: when {
        set.key.startsWith("playlist/") -> "Playlist"
        else -> set.tracks.firstNotNullOfOrNull { it.meta?.album?.takeIf(String::isNotBlank) } ?: "Album"
    }

    /**
     * The read-ahead window's committed copies: songs that are pinned or have an open writer, kept or not. The
     * window writes kept-set songs too (a kept album's missing members, the original that replaces an Opus copy), and
     * those bytes may sit below the line like any other window file: charging them to kept music would let a read-ahead
     * of one kept album delete another. They may sit below the line. Their partial files are not here: [partBytes]
     * counts every partial alike.
     * The songs are the union of every song pinned or written at any time since the running maintenance pass started
     * ([runWindow]) and those in flight now: a pin that changes while maintenance runs (pins take no lock) can then
     * only enlarge the allowance, never shrink it between the plan and the final measurement.
     */
    private fun windowBytes(): Long {
        val inFlight = synchronized(protection) { pins + writers + runWindow.orEmpty() }
        return inFlight.sumOf { id -> tracks[id]?.bytes ?: 0L }
    }

    /**
     * Files that lost their record while the player holds them (replaced or deleted now): they stay on disk until
     * released and nothing can free them, so they are allowance like partials. Read before the free space: a file that
     * goes meanwhile then only understates the deficit.
     */
    private fun doomedBytes(): Long = synchronized(protection) { doomed.toList() }.sumOf { File(directory, it).length() }

    /** One maintenance pass (under the cache lock): [runWindow] is collected from its start until [block] ends. */
    private inline fun <T> maintenanceRun(block: () -> T): T {
        synchronized(protection) { runWindow = (pins + writers).toMutableSet() }
        try { return block() } finally { synchronized(protection) { runWindow = null } }
    }

    /** The songs pinned (read-ahead window) or being written right now. */
    private fun inFlight(): Set<String> = synchronized(protection) { pins + writers }

    /**
     * Every partial download on the phone: partial files, up-front reservations and their `.at` markers, whoever owns
     * them (a running write, a pin, a kept set, nothing). They never count toward the deficit that selects kept music
     * for removal: that deficit knows committed cached music and free space only. Owned or orphaned is not asked at all,
     * so an unpin or a writer closing while maintenance runs cannot change it; the orphans are deleted separately and first.
     */
    private fun partBytes(): Long = directory.listFiles()?.filter { it.name.contains(".part") }?.sumOf { it.length() } ?: 0L

    /** Partial downloads that belong to nothing any more: no running write, no read-ahead pin, no kept set holding the song. */
    private fun orphanParts(parts: List<File>): List<File> {
        val live = inFlight().plus(holders.keys).map(::songFileBase).toSet()
        return parts.filter { it.name.take(40) !in live }
    }

    /** A partial a later download can continue: its `.at` marker records a confirmed length above zero. */
    private fun resumablePart(file: File): Boolean {
        val part = if (file.name.endsWith(".at")) File(directory, file.name.removeSuffix(".at")) else file
        if (!part.isFile || part.length() == 0L) return false
        val marker = File(directory, part.name + ".at")
        return (marker.takeIf { it.isFile }?.readText()?.lines()?.firstOrNull()?.trim()?.toLongOrNull() ?: 0L) > 0
    }

    /**
     * Maintenance's own cleanup of orphan partials, before the plan applies. An orphan nothing can continue (no marker,
     * zero confirmed bytes, a reservation) goes at once. A resumable one (a kept `.part` after a restart, an interrupted
     * read-ahead copy) is what BAT-1b resumes with Range, so it stays while the phone is above the line; it goes when the
     * phone is short of the line even with the window's and the doomed files' bytes taken out ([policy]), and after
     * [STALE_PART_MS] (see [housekeepIf]). Deleting an orphan frees what [partBytes] stops counting: the deficit does not change.
     */
    private fun reclaimOrphanParts(policy: CachePolicy) {
        // Cleared before the listing, so a change while it runs asks for the next look.
        orphanCheckDue = false
        val parts = directory.listFiles()?.filter { it.name.contains(".part") }.orEmpty()
        val bases = parts.mapTo(HashSet()) { it.name.take(40) }
        synchronized(protection) { partBases.retainAll(bases + writers.map(::songFileBase)); partBases += bases }
        val orphans = orphanParts(parts)
        if (orphans.isEmpty()) return
        val short = policy.keepFreeBytes - freeSpace.freeBytes() - windowBytes() - doomedBytes() > 0
        val gone = orphans.filter { short || !resumablePart(it) }
        // The 60 s hold after our own deletes covers orphan parts too, but only when a delete freed bytes.
        if (deleteFreeing(gone)) noteOwnDelete()
    }

    /** A coherent measurement: the [deficit] and the writer [epoch] it was taken under. */
    private class Measured(val deficit: Long, val epoch: Long)

    /**
     * Bytes below the line that maintenance must free under [policy]: the line minus the free space minus the partial
     * downloads minus the window's committed copies (see [windowBytes]), measured under the [writerEpoch] rule: null when
     * a writer change was running at the start or happened meanwhile (the free space and the partial files were then not
     * read from one state, and a partial that shrank and regrew cannot be told from a deficit). The caller skips the
     * removal of kept music and the next ticker pass measures again. IO.
     */
    private fun measure(policy: CachePolicy): Measured? {
        val before = writerEpoch.get()
        if (before and ACTIVE_MASK != 0L) return null
        val held = doomedBytes()
        val free = freeSpace.freeBytes()
        val partial = partBytes()
        val window = windowBytes()
        if (writerEpoch.get() != before) return null
        return Measured(policy.keepFreeBytes - free - partial - window - held, before)
    }

    /**
     * [measure] retried a bounded number of times for a change of settings (preview, Confirm): while a download streams,
     * most single tries overlap a write, and "no measurement" must not become "no need". Under the cache lock for at
     * most about [SETTLE_BUDGET_MS] ms (and [SETTLE_TRIES] tries).
     */
    private fun measureSettled(policy: CachePolicy): Measured? {
        val start = System.nanoTime()
        repeat(SETTLE_TRIES) { attempt ->
            measure(policy)?.let { return it }
            // The time spent, not just the count: each try reads the free space and lists the folder, so a big cache must not hold the lock longer.
            if ((System.nanoTime() - start) / 1_000_000 >= SETTLE_BUDGET_MS) return null
            if (attempt < SETTLE_TRIES - 1) try { Thread.sleep(SETTLE_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return null }
        }
        return null
    }

    /** Free space as the user sees it, compared with Keep free space. A syscall: call on IO. */
    fun freeBytes() = freeSpace.freeBytes()

    /** Keep free space under the current settings. */
    fun lineBytes() = policy().keepFreeBytes

    /**
     * Makes room for [incoming] bytes above the Keep free space line by deleting music that isn't kept, earliest
     * keep-until first, never kept music, never [protect]ed songs (the download's own add or window), pins
     * (the read-ahead window), leases (the player's files) or other downloads. The read-ahead [window] may then go
     * below the line, down to Android's own low-storage line, but only for [songId] music that no kept set holds: a
     * kept set's own songs (its missing members, the original that replaces its Opus copy) are written only with room
     * above the line, paid for by unkept music, otherwise they stream (§4): once the window has moved on, a
     * kept song's bytes below the line would be a deficit that only another kept set could cover. Silent. IO.
     */
    @Synchronized fun makeRoom(incoming: Long, protect: Set<String> = emptySet(), window: Boolean = false, songId: String? = null,
        writer: Write? = null): Boolean {
        val policy = policy()
        val line = policy.keepFreeBytes
        // What the other running writes were admitted for and have not written yet is spent already.
        val spent = outstanding(except = writer)
        var free = freeSpace.freeBytes() - spent
        if (free - incoming >= line) return admit(writer, incoming)
        if (!degraded) {
            val plan = CachePlanner.plan(files(policy, protect), keptSets(), wall(), policy, line - (free - incoming),
                keptToo = false, expire = false)
            if (plan.evict.isNotEmpty()) { remove(plan.evict); free = freeSpace.freeBytes() - spent }
        }
        val belowTheLine = window && (songId == null || !holders.containsKey(songId))
        val room = free - incoming >= line || (belowTheLine && freeSpace.allocatableBytes() - spent - incoming >= 0)
        return room && admit(writer, incoming)
    }

    /** Under the cache lock: [writer] may write [incoming] more bytes (its admission, see [admitted]). True. */
    private fun admit(writer: Write?, incoming: Long): Boolean {
        writer?.let { admitted[it] = it.onDisk + incoming }
        return true
    }

    /** Bytes the running writes (but [except]) were admitted for and have not put on disk yet. */
    private fun outstanding(except: Write? = null): Long =
        admitted.entries.sumOf { (write, total) -> if (write === except) 0L else (total - write.onDisk).coerceAtLeast(0) }

    /**
     * What maintenance under [policy] deletes now: expiry, then the line minus the window, kept sets last and
     * whole, and the kept sets whose time ran out. The settings sheet's preview. IO (free space).
     */
    @Synchronized fun preview(policy: CachePolicy = this.policy()): CachePlanner.Plan =
        if (degraded) heldPlan() else maintenanceRun { planFor(policy, settings = true) } // Confirm deletes nothing then

    /**
     * [settings]: the plan is for a change of settings (the sheet's preview, Confirm), which must never be decided on
     * "unknown". It retries the measurement; when writers still keep it from being coherent, the need is the upper bound
     * (the line minus the free space, no allowances), so the sheet names everything that could go and a change that could
     * delete kept music is never saved without it. A pass under the saved settings (the ticker) treats no measurement as
     * no space need: kept music is then simply not removed, and the next pass measures again.
     */
    private fun planFor(policy: CachePolicy, settings: Boolean): CachePlanner.Plan {
        val need = (if (settings) measureSettled(policy) else measure(policy))?.deficit
            ?: if (settings) maxOf(0L, policy.keepFreeBytes - freeSpace.freeBytes()) else 0L
        val expiring = online == true
        val plan = CachePlanner.plan(files(policy, emptySet()), keptSets(), wall(), policy, need, keptToo = true, expire = expiring,
            busy = guard().third)
        // Offline nothing leaves for being old, a kept set past its time included.
        return if (expiring) plan else plan.copy(deadSets = emptyList())
    }

    /**
     * Maintenance: runs [preview]'s plan under [policy]: expired and not-kept files one by one (each
     * re-checked), kept units all or nothing, kept sets past their time without tracks dropped; an automatic removal of
     * a kept set for space is recorded for the notice ([notice] false: the user confirmed a sheet naming them). Then
     * stale partial downloads and waveforms. Returns the keys of the kept sets that left. IO.
     */
    @Synchronized fun housekeep(policy: CachePolicy = this.policy(), notice: Boolean = true): List<String> {
        if (!saveWhileDegraded()) return emptyList()
        // Early-out: nothing the full pass could delete is due, so no folder walk and no plan. What the full pass
        // would still do without deleting music (lapsed marks, waveform ages, the index) runs as before.
        if (notice && policy == this.policy() && !due(policy)) {
            if (waveforms.maintenanceDue()) waveforms.maintain()
            return dropLapsedMarks().also { flush(); dueAfterPass = false }
        }
        idleBelow = null
        // Only after the early-out's due(), so its free-space reading is the pass's start.
        val start = if (notice && policy == this.policy()) passInputs(policy, dueFree) else null
        var nothingRemovable = false
        // A plan that names nothing while a coherent measurement is still short (an incoherent one plans a need of 0):
        // nothing is removable for space. A plan with kept units held back by the 60 s hold is not empty, so never remembered.
        return housekeepIf(policy, notice) { plan -> nothingRemovable = plan.isEmpty && plan.missingBytes > 0; true }!!.also {
            // Remembered with the inputs from before the pass: a delete during it (revision, own delete) clears it.
            if (start != null && nothingRemovable && start.free < policy.keepFreeBytes && revision == start.revision &&
                ownDeleteAt == start.ownDeleteAt) idleBelow = start
            refreshDue()
        }
    }

    /**
     * Whether the last [housekeep] left something due (below the line, the hold after its own deletes, a stale
     * sweep or a dead set): the caller looks again soon instead of waiting for its slow fallback. An expired file that is
     * pinned, leased or being written is not due (it is not removable); it is picked up by the next event or the fallback.
     * False until the first pass (the owner runs one at start anyway).
     */
    @Volatile var dueAfterPass: Boolean = false
        private set

    /**
     * Free space was below the Keep free space line plus a margin at the last check ([NEAR_LINE_MIN_BYTES] or 10 %
     * of the line, whichever is larger), so space taken by another app can push the phone below the line within minutes
     * and the owner keeps a short interval; far above the line only events and the slow fallback run.
     */
    @Volatile var nearLine: Boolean = false
        private set

    /** Whether a maintenance pass under the saved settings has anything to do now. Under the cache lock. IO (free space). */
    @Synchronized fun maintenanceDue(): Boolean = due(policy())

    /** Sets [dueAfterPass] after a pass that did not go through [housekeep] (the settings sheet's Confirm). IO. */
    @Synchronized fun refreshDue() { dueAfterPass = due(policy()) }

    /**
     * A full pass is due when an orphan partial may exist, the stale-part sweep is due, the phone is below the
     * line (only then can unkept or kept music go for space, or a resumable orphan), or, while the server is reachable, a
     * removable file is past its keep-until or a kept set's own time ran out with no track left (a dead set). Below
     * the line, not while [idleBelow] holds (the time-based checks still run). Memory plus one free-space query.
     */
    private fun due(policy: CachePolicy): Boolean {
        val free = freeSpace.freeBytes().also { dueFree = it }
        // While degraded nothing a full pass does can run, so nothing is due and no short interval is kept.
        nearLine = !degraded && free < policy.keepFreeBytes + maxOf(NEAR_LINE_MIN_BYTES, policy.keepFreeBytes / 10)
        if (degraded) return false
        if (orphanCheckDue || staleSweepDue()) return true
        if (free < policy.keepFreeBytes && !idleBelowHolds(free, policy)) return true
        // Offline (or not known yet) nothing is due for being old.
        if (online != true) return false
        val now = wall()
        val (pinned, leased, writing) = guard()
        if (tracks.values.any { record -> record.songId !in pinned && record.songId !in writing && record.file !in leased &&
                keepUntil(record, policy) <= now }) return true
        if (legacy.values.any { plusSaturating(it.datedAt, policy.heardOnce.ms) <= now }) return true
        return sets.any { set -> policy.setUntil(set.addedAt) <= now && set.trackIds.none { tracks.containsKey(it) } }
    }

    /** Whether [idleBelow] still describes now; clears it when not. */
    private fun idleBelowHolds(free: Long, policy: CachePolicy): Boolean {
        val idle = idleBelow ?: return false
        val same = kotlin.math.abs(free - idle.free) <= IDLE_FREE_SLACK_BYTES && idle == passInputs(policy, idle.free)
        if (!same) idleBelow = null
        return same
    }

    /** At start, then every [STALE_SWEEP_MS] (a wall clock that moved back counts as due). */
    private fun staleSweepDue(): Boolean = staleSweptAt?.let { wall() - it }?.let { it < 0 || it >= STALE_SWEEP_MS } ?: true

    /**
     * [housekeep] in one locked step with its own check: [accept] sees the plan first and may refuse it (null: nothing
     * saved or deleted). The settings sheet's Confirm: re-plan, compare with what was shown, save, delete. IO.
     */
    @Synchronized fun housekeepIf(policy: CachePolicy, notice: Boolean, shown: CachePlanner.Plan? = null,
        accept: (CachePlanner.Plan) -> Boolean): List<String>? {
        // A retired cache (or one without a readable index) deletes nothing.
        if (retired) return emptyList()
        // While the start's save still fails nothing leaves and nothing else runs, but a settings change is still
        // saved (the settings store is not the index): Confirm is asked with an empty plan.
        if (!saveWhileDegraded()) return if (accept(heldPlan())) emptyList() else null
        val settings = policy != this.policy()
        val left = maintenanceRun {
            val planned = planFor(policy, settings)
            if (!accept(planned)) return null
            // Confirm applies the plan the user confirmed, never more (the re-plan may be larger: an upper bound while a download streams).
            val plan = if (shown == null) planned else planned.within(shown)
            reclaimOrphanParts(policy)
            apply(plan, notice, policy, settings).also { if (!notice) rememberConfirmed(plan, it) }
        }
        // The stale-part sweep (7 days untouched) lists the folder only at start and every few hours.
        if (staleSweepDue()) {
            staleSweptAt = wall()
            val writing = guard().third.map { songFileBase(it) }.toSet()
            val stale = directory.listFiles()?.filter { it.name.contains(".part") && it.name.take(40) !in writing &&
                System.currentTimeMillis() - it.lastModified() > STALE_PART_MS }.orEmpty()
            if (deleteFreeing(stale)) noteOwnDelete()
        }
        waveforms.maintain()
        val dropped = dropLapsedMarks()
        flush()
        return left + dropped
    }

    /** Retries the save the start could not make; false while it still fails (the pass then does nothing). */
    private fun saveWhileDegraded(): Boolean {
        if (degraded) try { persist() } catch (_: Exception) { return false }
        if (startAdoptionDue) {
            startAdoptionDue = false
            try { adoptKept() } catch (_: Exception) { startAdoptionDue = true }
        }
        return true
    }

    /** The plan while no music may leave ([degraded]): nothing. */
    private fun heldPlan() = CachePlanner.Plan(emptyList(), emptyList(), emptyList(), emptyList(), 0, emptySet())

    /** The parts of this plan that [shown] also names: files by song and name, kept units by their sets, dead sets by key. */
    private fun CachePlanner.Plan.within(shown: CachePlanner.Plan): CachePlanner.Plan {
        val files = shown.evict.map { it.songId to it.name }.toSet()
        val keys = shown.removedSets.map { it.key }.toSet()
        val dead = shown.deadSets.map { it.key }.toSet()
        return copy(
            expired = expired.filter { (it.songId to it.name) in files }, notKept = notKept.filter { (it.songId to it.name) in files },
            units = units.filter { unit -> unit.sets.all { it.key in keys } }, deadSets = deadSets.filter { it.key in dead })
    }

    /** Under the cache lock: the sets a confirmed plan named that did not leave in the same step are removed later without a notice. */
    private fun rememberConfirmed(plan: CachePlanner.Plan, left: List<String>) {
        val now = wall()
        confirmedSets.values.removeAll { now - it > CONFIRMED_MS }
        plan.removedSets.forEach { set -> if (set.key !in left && sets.any { it.key == set.key }) confirmedSets[set.key] = now }
    }

    /** Deletes [files]; true when at least one existed with bytes and was deleted (an undeletable or empty file frees nothing, so it must not renew the hold). */
    private fun deleteFreeing(files: List<File>): Boolean {
        var freed = false
        for (file in files) { val size = file.length(); if (file.delete() && size > 0) freed = true }
        return freed
    }

    private fun noteOwnDelete() { ownDeleteAt = wall() }

    /** No delete of ours within the last tick (a clock that moved back counts as long ago). */
    private fun ownDeleteSettled(): Boolean = ownDeleteAt?.let { wall() - it }?.let { it < 0 || it >= OWN_DELETE_WAIT_MS } ?: true

    /** Under the cache lock. The write order of CA-3 §3.2: index first (once), then the files. */
    private fun apply(plan: CachePlanner.Plan, notice: Boolean, policy: CachePolicy, settings: Boolean): List<String> {
        remove(plan.expired + plan.notKept)
        // Kept music is the last resort: measured again after the files above are gone (partial files never count, see partBytes; no coherent measurement, no removal, see measure), a unit leaves only while the phone is still short by more than the units already taken and no writer changed anything since.
        val measured = if (plan.units.isEmpty()) null else if (settings) measureSettled(policy) else measure(policy)
        // Free space may return lazily after a delete (journal commit): within a tick of any delete of ours (this pass's own
        // included) the deficit may be overstated, so kept music waits for the next pass; unkept music above went already.
        val needed = if (ownDeleteSettled()) measured?.deficit ?: 0L else 0L
        crashPoint("housekeep.measured")
        // What this step changes, put back if its index save fails.
        val setsBefore = sets
        val pendingBefore = HashMap(pending)
        val noticeBefore = removedForSpace
        val confirmedBefore = HashMap(confirmedSets)
        val takenRecords = mutableListOf<CachedTrack>()
        val files = mutableListOf<File>()
        val removed = mutableListOf<CachePlanner.KeptSet>()
        var removedBytes = 0L
        val takenUnits = mutableListOf<CachePlanner.KeptUnit>()
        for (unit in plan.units) {
            if (removedBytes >= needed) break
            val taken = synchronized(protection) {
                val unchanged = unit.files.all { file -> tracks[file.songId]?.let { record -> record.file == file.name &&
                    record.bytes == file.bytes && file.songId !in pins && record.file !in leases && file.songId !in writers } == true }
                val keys = unit.sets.map { it.key }.toSet()
                // Planned as if every earlier unit left; one that was skipped still holds its tracks: a file
                // another remaining set holds, or a set with a member downloading right now, keeps this unit whole.
                val shared = unit.files.any { file -> holders[file.songId].orEmpty().any { it !in keys } }
                val downloading = unit.sets.any { set -> set.trackIds.any { it in writers } }
                // The planner took files it expected to expire as gone; one that survived (leased since) is still a
                // member, and a set never leaves with a member behind.
                val left = unit.sets.flatMap { it.trackIds }.any { id ->
                    tracks.containsKey(id) && holders[id].orEmpty().all { it in keys } && unit.files.none { it.songId == id } }
                // The last check before the decision: a writer change since the measurement withdraws it.
                val quiet = measured != null && writerEpoch.get() == measured.epoch
                if (!quiet || !unchanged || shared || downloading || left || !keys.all { key -> sets.any { it.key == key } }) null
                else { unit.files.forEach { file -> tracks.remove(file.songId)?.let { takenRecords += it } }; keys }
            } ?: continue
            // The unit's covers stay (artwork is never evicted), so only its audio counts.
            updateSets(sets.filterNot { it.key in taken }); taken.forEach { pending.remove(it) }
            files += unit.files.map { File(directory, it.name) }
            removed += unit.sets; removedBytes += unit.bytes; takenUnits += unit
        }
        val deadKeys = plan.deadSets.map { it.key }.toSet()
            .filter { key -> sets.firstOrNull { it.key == key }?.trackIds?.none { tracks.containsKey(it) } == true }.toSet()
        if (deadKeys.isNotEmpty()) { updateSets(sets.filterNot { it.key in deadKeys }); deadKeys.forEach { pending.remove(it) } }
        if (removed.isEmpty() && deadKeys.isEmpty()) return emptyList()
        val confirmed = removed.filter { confirmedSets.remove(it.key) != null }.map { it.key }.toSet()
        val noticed = if (notice) removed.filter { it.key !in confirmed } else emptyList()
        confirmedSets.keys.retainAll { key -> sets.any { it.key == key } }
        if (noticed.isNotEmpty()) {
            val old = removedForSpace
            val now = wall()
            val noticedKeys = noticed.map { it.key }.toSet()
            val bytes = takenUnits.filter { unit -> unit.sets.any { it.key in noticedKeys } }.sumOf { it.bytes }
            removedForSpace = if (old == null) RemovedForSpace(now, noticed.map { it.title.orEmpty() }, bytes)
                else old.copy(at = now, titles = old.titles + noticed.map { it.title.orEmpty() }, bytes = old.bytes + bytes)
        }
        revision++
        try { persist() } catch (error: Exception) {
            putBack(takenRecords, emptyList(), setsBefore, pendingBefore, noticeBefore)
            confirmedSets.clear(); confirmedSets.putAll(confirmedBefore)
            throw error
        }
        crashPoint("housekeep.persisted")
        files.forEach { file -> deleteFile(file.name) }
        return removed.map { it.key } + deadKeys
    }

    /** The snackbar for [removedForSpace] was shown (delivery only). IO. */
    @Synchronized fun snackbarShown() {
        val record = removedForSpace ?: return
        removedForSpace = record.copy(snackbarAt = wall()); revision++; persist()
    }

    /** OK on the Music storage notice line: the record goes and never returns. IO. */
    @Synchronized fun acknowledgeRemoved() {
        if (removedForSpace == null) return
        removedForSpace = null; revision++; persist()
    }

    private fun remove(victims: Collection<Evictable>) {
        val files = mutableListOf<File>()
        val taken = mutableListOf<CachedTrack>()
        val takenLegacy = mutableListOf<LegacyFile>()
        for (victim in victims) {
            if (victim.songId == null) {
                val name = victim.name.removePrefix("$LEGACY/")
                legacy.remove(name)?.let { takenLegacy += it; files += File(legacyDir, name) }
            } else removeTrack(victim.songId, victim.name, force = false, taken)?.let { files += it }
        }
        if (files.isEmpty()) return
        files.removeAll { it === NOTHING }
        // The index first, then the files (a crash in between leaves files without rows, kept as legacy files).
        revision++
        try { persist() } catch (error: Exception) { putBack(taken, takenLegacy); throw error }
        files.forEach { it.delete() }
        noteOwnDelete()
    }

    /**
     * Re-checks protection under the lock: a hand-off may have leased the file since planning. Removes the record from
     * memory and returns the file the caller deletes once the index is saved (a file the player holds is left to its
     * release, [doomed], and [NOTHING] is returned for it). Null when nothing was removed.
     */
    private fun removeTrack(songId: String, expectedFile: String?, force: Boolean): File? = removeTrack(songId, expectedFile, force, ArrayList())

    /** [removeTrack], adding the record it removed to [taken] (put back when the index save fails). */
    private fun removeTrack(songId: String, expectedFile: String?, force: Boolean, taken: MutableList<CachedTrack>): File? {
        val removed = synchronized(protection) {
            val record = tracks[songId] ?: return null
            if (expectedFile != null && record.file != expectedFile) return null
            if (!force && (songId in pins || record.file in leases || songId in writers)) return null
            tracks.remove(songId)
            taken += record
            if (record.file in leases) { writerChange { doomed += record.file }; null } else record
        }
        revision++; dirty = true
        return removed?.let { File(directory, it.file) } ?: NOTHING
    }

    /**
     * The index save of a removal failed, so its files stay: their records go back (with the plays made
     * meanwhile, which [markPlayed] kept as early plays) and the cache keeps finding and counting them. Under the cache lock.
     */
    private fun putBack(records: Collection<CachedTrack>, legacyFiles: Collection<LegacyFile> = emptyList()) {
        synchronized(protection) {
            records.forEach { record ->
                doomed.remove(record.file)
                tracks.compute(record.songId) { _, now -> now ?: takeEarlyPlay(record.songId)?.let { early ->
                    record.copy(lastPlayedAt = later(record.lastPlayedAt, early.at), playCount = record.plays + early.count)
                } ?: record }
            }
        }
        legacyFiles.forEach { legacy.putIfAbsent(it.name, it) }
        revision++
    }

    /** [putBack] plus the kept sets, their pending tracks and the notice as they were before the removal. */
    private fun putBack(records: Collection<CachedTrack>, legacyFiles: Collection<LegacyFile>, sets: List<KeptItem>,
        pending: Map<String, Set<String>>, removedForSpace: RemovedForSpace?) {
        putBack(records, legacyFiles)
        updateSets(sets)
        pending.forEach { (key, ids) -> this.pending.putIfAbsent(key, ids) }
        this.removedForSpace = removedForSpace
    }

    private fun deleteFile(name: String) {
        val held = synchronized(protection) { (name in leases).also { if (it) writerChange { doomed += name } } }
        if (!held) File(directory, name).delete()
        noteOwnDelete()
    }

    // ---- Added sets ----

    /** Tracks of an added set still to download: missing since its last add or re-sync, never fetched since. */
    fun pending(key: String): Set<String> = pending[key].orEmpty()

    /**
     * Cancel: no added set waits for its missing tracks any more. What is on the phone stays; a set that is then
     * incomplete loses its kept mark ([dropLapsedMarks]) and downloads again only when it is kept again. Returns the keys
     * of the marks dropped. IO.
     */
    @Synchronized fun clearPending(): List<String> = cancelDownloads(emptyList())

    /**
     * Notification Cancel, in one index write: no set waits for its missing tracks ([clearPending]), the queue
     * download ends, and the Opus copies in [replacements] are not replaced by their originals until their set is added
     * again. Returns the keys of the kept marks dropped. IO.
     */
    @Synchronized fun cancelDownloads(replacements: Collection<String>): List<String> {
        var changed = false
        if (pending.values.any { it.isNotEmpty() }) { pending.replaceAll { _, _ -> emptySet() }; changed = true }
        if (queueDownload != null) { queueDownload = null; changed = true }
        if (!replacementsCancelled.containsAll(replacements)) { replacementsCancelled = replacementsCancelled + replacements; changed = true }
        val dropped = dropLapsedMarksLocked()
        if (changed || dropped.isNotEmpty()) { revision++; persist() }
        return dropped
    }

    /**
     * "Cache queue ahead" starts (or replaces the running one) with [item]: every target already on the phone gets a
     * fresh Heard once time ([renewArrival]) in the same index write. IO.
     */
    @Synchronized fun startQueueDownload(item: KeptItem) {
        renewArrivalLocked(item.trackIds)
        queueDownload = item
        revision++; persist()
    }

    /** The queue download ended (done or stopped): what is on the phone stays. IO. */
    @Synchronized fun endQueueDownload() {
        if (queueDownload == null) return
        queueDownload = null
        revision++; persist()
    }

    /**
     * A set that looks not kept IS not kept. A kept mark with tracks missing, nothing left to download and
     * no member downloading right now is dropped, as if the user had switched it off, but no audio is deleted: the tracks
     * stay as ordinary cached music on the Heard once / Heard again clocks. A set still downloading keeps its mark.
     * Returns the keys dropped. IO.
     */
    @Synchronized fun dropLapsedMarks(): List<String> = dropLapsedMarksLocked().also { if (it.isNotEmpty()) { revision++; persist() } }

    /** [dropLapsedMarks] in memory; the caller persists. Under the cache lock. */
    private fun dropLapsedMarksLocked(): List<String> {
        val lapsed = synchronized(protection) {
            sets.filter { set ->
                set.trackIds.isNotEmpty() && pending(set.key).isEmpty() && set.trackIds.any { !tracks.containsKey(it) } &&
                    set.trackIds.none { it in writers }
            }.map { it.key }
        }
        if (lapsed.isEmpty()) return emptyList()
        val released = sets.filter { it.key in lapsed }.flatMap { it.trackIds }.toSet()
        updateSets(sets.filterNot { it.key in lapsed })
        lapsed.forEach { pending.remove(it); confirmedSets.remove(it) }
        // The tracks no remaining set holds start fresh as ordinary music: Heard once from now (never shortened).
        val now = wall()
        released.filter { it !in holders }.forEach { id -> tracks.computeIfPresent(id) { _, record ->
            if (record.addedAt >= now) record else record.copy(addedAt = now)
        } }
        return lapsed
    }

    /** The latest add or renewal date of the added sets holding [songId], or [Long.MIN_VALUE]. Under the cache lock. */
    private fun latestSetDate(songId: String): Long = keptAt[songId] ?: Long.MIN_VALUE

    /**
     * Replaces [sets] and rebuilds [holders] and [keptAt]. Under the cache lock (or in init). Covers stay
     * (artwork is never evicted; songs give way).
     */
    private fun updateSets(next: List<KeptItem>) {
        val byHolder = HashMap<String, MutableSet<String>>()
        val dates = HashMap<String, Long>()
        next.forEach { set -> set.trackIds.forEach { id ->
            byHolder.getOrPut(id) { HashSet() } += set.key
            dates[id] = maxOf(dates[id] ?: Long.MIN_VALUE, set.addedAt)
        } }
        // A song with a partial file that no kept set holds any more may now be an orphan.
        if (holders.keys.any { it !in byHolder }) synchronized(protection) {
            if (holders.keys.any { it !in byHolder && songFileBase(it) in partBases }) orphanCheckDue = true
        }
        sets = next; holders = byHolder; keptAt = dates
    }

    /**
     * "Load for offline": every target already on the phone gets a fresh Heard once time
     * (`addedAt = max(addedAt, now)`); missing ones get theirs when they commit. Play dates and counts stay. IO.
     */
    @Synchronized fun renewArrival(songIds: Collection<String>) { if (renewArrivalLocked(songIds)) { revision++; persist() } }

    /** [renewArrival] in memory; true when a date changed. Under the cache lock. */
    private fun renewArrivalLocked(songIds: Collection<String>): Boolean {
        val now = wall()
        var changed = false
        songIds.forEach { id -> tracks.computeIfPresent(id) { _, record ->
            if (record.addedAt >= now) record else { changed = true; record.copy(addedAt = now) }
        } }
        return changed
    }

    /** A track is cached: no set waits for it any more. Under the cache lock. */
    private fun settle(songId: String) {
        if (pending.values.none { songId in it }) return
        pending.replaceAll { _, ids -> if (songId in ids) ids - songId else ids }
        dirty = true
    }

    /**
     * Adds or renews a set: 30 days start again for every track in it, and every missing track downloads again, Opus
     * copies whose replacement was cancelled included (one index write). IO.
     */
    @Synchronized fun renew(item: KeptItem): KeptItem {
        val now = wall()
        val renewed = item.copy(addedAt = now)
        renewed.trackIds.forEach { id ->
            tracks.computeIfPresent(id) { _, record ->
                val meta = item.tracks.firstOrNull { it.id == id }?.meta
                record.copy(addedAt = maxOf(record.addedAt, now), meta = meta?.over(record.meta) ?: record.meta)
            }
        }
        updateSets(sets.filterNot { it.key == item.key } + renewed)
        pending[item.key] = renewed.trackIds.filterNot { tracks.containsKey(it) }.toSet()
        if (replacementsCancelled.any { it in renewed.trackIds }) replacementsCancelled = replacementsCancelled - renewed.trackIds.toSet()
        revision++; persist()
        return renewed
    }

    /**
     * A page re-sync: the new track list. False when the set isn't added. IO.
     *
     * With [renew] (an added playlist's page was opened) the set and its cached tracks get 30 days from
     * now, as an add gives them, and new tracks are dated with that renewal. Without it (albums) dates stay. Either
     * way only new tracks become pending: tracks that already left (aged out, evicted, deleted) stay gone until the
     * next add, so a page visit never refills the cache in the background.
     */
    @Synchronized fun resync(item: KeptItem, renew: Boolean = false): Boolean {
        val old = sets.firstOrNull { it.key == item.key } ?: return false
        if (!renew && old.tracks == item.tracks) return true
        val now = wall()
        // A set whose tracks have all aged out is over (housekeeping removes it); a visit doesn't bring it back.
        val policy = policy()
        if (renew && policy.setUntil(old.addedAt) <= now &&
            old.trackIds.none { id -> tracks[id].let { it != null && keepUntil(it, policy) > now } }) return false
        val addedAt = if (renew) maxOf(old.addedAt, now) else old.addedAt
        if (renew) item.trackIds.forEach { id ->
            tracks.computeIfPresent(id) { _, record -> record.copy(addedAt = maxOf(record.addedAt, addedAt)) }
        }
        updateSets(sets.map { if (it.key == item.key) item.copy(addedAt = addedAt, title = item.title ?: old.title, coverArt = item.coverArt ?: old.coverArt) else it })
        // New tracks are to download; tracks that already left (aged out, evicted) stay gone until the next add.
        val before = old.trackIds.toSet()
        val ids = item.trackIds
        pending[item.key] = pending(item.key).filter { it in ids }.toSet() +
            ids.filter { it !in before && !tracks.containsKey(it) }
        revision++; persist()
        return true
    }

    /** [affectedSets]: kept sets "Make room?" removes whole, named by [titles]. */
    data class AddPlan(val format: StreamFormat, val needBytes: Long, val evict: List<Evictable>, val missingBytes: Long,
        val affectedSets: List<String>, val otherTracks: Int, val titles: List<String> = emptyList()) {
        val evictBytes get() = evict.sumOf { it.bytes }
    }

    /**
     * What an add needs: its missing tracks in the format for [network], and what "Make room?" would delete: music not
     * kept first, then whole kept sets (the add itself counts as a holder that is never chosen). IO (free space).
     */
    fun planAdd(item: KeptItem, network: NetworkState): AddPlan {
        val policy = policy()
        val need = item.tracks.distinctBy { it.id }.sumOf { track ->
            CacheFormatPolicy.download(kind(track.id), network, track.format)?.let { track.format.estimate(it) } ?: 0L
        }
        val headline = CacheFormatPolicy.fetchFormat(network, SongFormat())
        val available = freeSpace.freeBytes() - policy.keepFreeBytes
        if (need <= available) return AddPlan(headline, need, emptyList(), 0, emptyList(), 0)
        // No "Make room?" while no music may leave: the add is too big for now.
        if (degraded) return AddPlan(headline, need, emptyList(), need - available, emptyList(), 0)
        val plan = synchronized(this) {
            CachePlanner.plan(files(policy, item.trackIds.toSet()), keptSets(sets.filterNot { it.key == item.key }), wall(),
                policy, need - available, keptToo = true, expire = false, holder = item.trackIds.toSet(), busy = guard().third)
        }
        if (!plan.enough) return AddPlan(headline, need, emptyList(), plan.missingBytes, emptyList(), 0)
        return AddPlan(headline, need, plan.evict, 0, plan.removedSets.map { it.key }, plan.notKeptFiles.size,
            plan.removedSets.mapNotNull { it.title })
    }

    /**
     * "Make room?" was confirmed: deletes exactly [victims] and removes the kept [setKeys] as [planAdd] listed them, or
     * nothing (false) when any of them changed since: gone, replaced, resized, played or renewed, or now pinned, held by
     * the player or being downloaded, or a set already gone. Checking and removing are one step under the cache and
     * protection locks; the index is written before the files go. IO.
     */
    @Synchronized fun evictExactly(victims: Collection<Evictable>, setKeys: Collection<String> = emptyList()): Boolean {
        if (degraded && (victims.isNotEmpty() || setKeys.isNotEmpty())) return false
        val taken = mutableListOf<CachedTrack>()
        val takenLegacy = mutableListOf<LegacyFile>()
        val files = synchronized(protection) {
            val unchanged = victims.all { victim ->
                if (victim.songId == null) legacy[victim.name.removePrefix("$LEGACY/")]
                    ?.let { it.bytes == victim.bytes && it.datedAt == victim.lastUsed } == true
                else tracks[victim.songId]?.let { record -> record.file == victim.name && record.bytes == victim.bytes &&
                    record.lastUsed == victim.lastUsed && victim.songId !in pins && record.file !in leases &&
                    victim.songId !in writers } == true
            } && setKeys.all { key -> sets.any { it.key == key } } &&
                // Nothing a kept set outside the plan holds, and no set with a member downloading right now.
                victims.none { victim -> victim.songId != null && holders[victim.songId].orEmpty().any { it !in setKeys } } &&
                sets.none { set -> set.key in setKeys && set.trackIds.any { it in writers } }
            if (!unchanged) return false
            victims.map { victim ->
                if (victim.songId == null) victim.name.removePrefix("$LEGACY/").let { name -> legacy.remove(name)?.let { takenLegacy += it }; File(legacyDir, name) }
                else { tracks.remove(victim.songId)?.let { taken += it }; File(directory, victim.name) }
            }
        }
        val setsBefore = sets
        val pendingBefore = HashMap(pending)
        if (setKeys.isNotEmpty()) { updateSets(sets.filterNot { it.key in setKeys }); setKeys.forEach { pending.remove(it) } }
        if (files.isEmpty() && setKeys.isEmpty()) return true
        revision++
        try { persist() } catch (error: Exception) { putBack(taken, takenLegacy, setsBefore, pendingBefore, removedForSpace); throw error }
        files.forEach { file -> file.delete() }
        return true
    }

    /**
     * Prefer offline switched off. The set [key] and its pending tracks go; no file is deleted. Its tracks that no
     * other kept set holds start fresh as ordinary music (gentle, as [dropLapsedMarks]): Heard once from now (never
     * shortened); play dates and counts stay, so a track heard again keeps its own later date. False when [key] wasn't
     * kept. When the index save fails, the set stays kept as before (in memory too) and the failure is
     * rethrown, so the switch can be turned off again. IO.
     */
    @Synchronized fun stopKeeping(key: String): Boolean {
        val set = sets.firstOrNull { it.key == key } ?: return false
        val setsBefore = sets
        val pendingBefore = pending[key]
        val confirmedBefore = confirmedSets[key]
        updateSets(sets - set); pending.remove(key); confirmedSets.remove(key)
        val now = wall()
        val freshened = HashMap<String, Long>()
        set.trackIds.filter { it !in holders }.forEach { id -> tracks.computeIfPresent(id) { _, record ->
            if (record.addedAt >= now) record else { freshened[id] = record.addedAt; record.copy(addedAt = now) }
        } }
        revision++
        try { persist() } catch (error: Exception) {
            updateSets(setsBefore)
            pendingBefore?.let { pending.putIfAbsent(key, it) }
            confirmedBefore?.let { confirmedSets[key] = it }
            freshened.forEach { (id, addedAt) -> tracks.computeIfPresent(id) { _, record -> record.copy(addedAt = addedAt) } }
            revision++
            throw error
        }
        return true
    }

    /**
     * Delete now: an album, playlist or track's cached tracks go at once and its added state clears. Tracks that belong
     * to another added item stay; tracks the player holds go when released; read-ahead tracks may be fetched again. IO.
     * Since KO-2 no app code calls it (the switch off deletes nothing); tests use it to delete files.
     */
    @Synchronized fun deleteNow(key: String?, trackIds: Collection<String>): Int {
        val set = sets.firstOrNull { it.key == key }
        val setsBefore = sets
        val pendingBefore = HashMap(pending)
        if (set != null) { updateSets(sets - set); pending.remove(set.key) }
        val others = sets.flatMap { it.trackIds }.toSet()
        val taken = mutableListOf<CachedTrack>()
        val files = (trackIds + set?.trackIds.orEmpty()).distinct().filterNot { it in others }.mapNotNull { removeTrack(it, null, force = true, taken) }
        revision++
        try { persist() } catch (error: Exception) { putBack(taken, emptyList(), setsBefore, pendingBefore, removedForSpace); throw error }
        // The files go only after the index no longer lists them.
        files.filter { it !== NOTHING }.forEach { it.delete() }
        if (files.isNotEmpty()) noteOwnDelete()
        return files.size
    }

    /**
     * Cached and total tracks, bytes, "available until" (wall ms: the earliest computed keep-until of its cached tracks,
     * CA-3 §8) and added state. A keep-until without a date is [ItemStatus.untilSpaceRunsLow]. Memory only.
     */
    fun status(key: String?, trackIds: Collection<String>): ItemStatus {
        val ids = trackIds.distinct()
        val cached = ids.mapNotNull { tracks[it] }
        val policy = policy()
        val earliest = cached.minOfOrNull { keepUntil(it, policy) }
        val unbounded = earliest == Long.MAX_VALUE
        val until = earliest?.takeIf { !unbounded }
        val added = key != null && sets.any { it.key == key }
        return ItemStatus(cached.size, ids.size, cached.sumOf { it.bytes }, until, added,
            pendingTracks = if (added) pending(key!!).size else 0, untilSpaceRunsLow = unbounded)
    }

    // ---- Writes ----


    /**
     * Called before the account wipe deletes [directory]. Invariant: once this returns, no writer of
     * this cache (read-ahead and kept downloads, index, waveforms, covers) writes or recreates anything under it. It
     * takes the cache's and the stores' locks, so a commit that is mid-way (renamed, not yet persisted) finishes first.
     */
    fun retire() = synchronized(this) { synchronized(waveforms) { synchronized(covers) { retired = true } } }

    /**
     * Starts a download of [key]. Null when a copy that needs no download is cached (an original, or the same kind), or
     * another download of the song runs. Storage errors propagate with the writer slot released.
     */
    @Synchronized fun begin(key: TrackKey): Write? {
        if (retired) return null
        val existing = tracks[key.songId]
        if (existing != null && (existing.kind == StreamFormat.RAW || existing.kind == key.format)) return null
        if (!synchronized(protection) { writers.add(key.songId).also { if (it) runWindow?.add(key.songId) } }) return null
        return try { directory.mkdirs(); writerChange { Write(key) }.also { synchronized(protection) { partBases += songFileBase(key.songId) } } } catch (error: Exception) { synchronized(protection) { writers.remove(key.songId) }; throw error }
    }

    /**
     * A partial file. It is kept on failure and resumes from the confirmed length ([resumeFrom], recorded in an `.at`
     * marker because an up-front reservation extends the file). Transcodes too; Navidrome finishes an
     * interrupted transcode into its cache and then serves it with Range, and the Prefetcher checks an overlap before
     * appending (a server that ignores the Range restarts the file from zero, as before).
     */
    inner class Write internal constructor(val key: TrackKey) : AutoCloseable {
        private val part = File(directory, songFileBase(key.songId) + TrackKey.extension(key.format) + ".part")
        private val marker = File(directory, part.name + ".at")
        private val resumable = true
        private val markerLines: List<String> = if (resumable && part.isFile) marker.takeIf { it.isFile }?.readText()?.lines().orEmpty() else emptyList()
        val resumeFrom: Long = if (resumable && part.isFile)
            (markerLines.firstOrNull()?.trim()?.toLongOrNull() ?: 0L).coerceIn(0, part.length()) else 0L
        /**
         * What the first response said about the file (validators and total length, opaque to the cache), kept in
         * the marker so a later resume can tell that the server's file is still the same one. Null: unknown.
         */
        var identity: String? = markerLines.getOrNull(1)?.takeIf { resumeFrom > 0 && it.isNotBlank() }
        private val file = RandomAccessFile(part, "rw")
        private val channel: java.nio.channels.FileChannel = file.channel
        private val sink = java.nio.channels.Channels.newOutputStream(channel)
        /** Writes at the channel position (the resume point), not at the end of a reserved file. Each write is a writer change. */
        val output: java.io.OutputStream = object : java.io.OutputStream() {
            override fun write(b: Int) = writerChange { checkLive(); sink.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) = writerChange { checkLive(); sink.write(b, off, len) }
            override fun flush() = sink.flush()
            override fun close() = writerChange { sink.close() }
        }
        private var finished = false
        /** The bytes this write holds on disk: what was written, or the whole file once [reserve]d. */
        @Volatile private var reserved = 0L
        internal val onDisk: Long get() = maxOf(runCatching { written }.getOrDefault(0L), reserved)
        /** A retired cache's download stops at its next write (its file lies in a deleted folder). */
        private fun checkLive() { if (retired) throw IOException("music cache retired") }
        init {
            if (!resumable) marker.delete()
            channel.truncate(resumeFrom); channel.position(resumeFrom)
        }
        val written: Long get() = channel.position()

        /** Whether what was written so far is one complete Ogg stream (reads only, no writer change). */
        fun isCompleteOgg(): Boolean = OggStream.isComplete(channel, written)

        /** The [count] bytes before [end] of what was written (an overlap check before appending). */
        fun readBack(end: Long, count: Int): ByteArray {
            val bytes = java.nio.ByteBuffer.allocate(count)
            var at = end - count
            while (bytes.hasRemaining()) { val n = channel.read(bytes, at); if (n < 0) break; at += n }
            return bytes.array()
        }

        /** The server ignored the range: start again from zero. */
        fun restart() = writerChange { channel.truncate(0); channel.position(0); marker.delete(); identity = null; reserved = 0 }

        /** Reserve the whole file up front (`StorageManager.allocateBytes`). False when the phone can't. */
        fun reserve(totalBytes: Long): Boolean =
            writerChange { try { freeSpace.allocate(file.fd, totalBytes); reserved = totalBytes; true } catch (_: IOException) { false } }

        /** Makes the bytes so far durable and resumable. */
        fun checkpoint() {
            // A retired cache writes no marker into the folder the account wipe is deleting.
            if (!resumable || retired) return
            writerChange {
                channel.force(false)
                marker.writeText(channel.position().toString() + (identity?.let { "\n" + it.replace('\n', ' ') } ?: ""))
            }
        }

        fun commit(contentType: String? = null, format: SongFormat = SongFormat(), meta: TrackMeta? = null, addedAt: Long? = null): Boolean =
            writerChange { commitLocked(contentType, format, meta, addedAt) }

        private fun commitLocked(contentType: String?, format: SongFormat, meta: TrackMeta?, addedAt: Long?): Boolean =
            synchronized(this@TrackCache) {
                if (finished || retired) return@synchronized false
                admitted.remove(this@Write)
                val length = channel.position()
                channel.truncate(length); channel.force(true); file.close()
                val head = ByteArray(minOf(length, 256L).toInt()).also { bytes -> RandomAccessFile(part, "r").use { it.readFully(bytes) } }
                val kind = ArrivalDetector.kind(key.format, contentType, head, format)
                val target = File(directory, TrackKey(key.songId, kind).fileName)
                val now = wall()
                // The newest date of an added set holding this song, read now under the cache lock: a set renewed while
                // this download ran (a playlist page opened) dates it, not the date the download started with.
                val setDate = latestSetDate(key.songId)
                fun record(old: CachedTrack?, early: EarlyPlay?) =
                    CachedTrack(key.songId, target.name, kind, length, maxOf(addedAt ?: now, old?.addedAt ?: Long.MIN_VALUE, setDate),
                        later(old?.lastPlayedAt, early?.at), contentType,
                        format.takeIf { it != SongFormat() } ?: old?.format ?: format, meta?.over(old?.meta) ?: old?.meta,
                        playCount = (old?.plays ?: 0) + (early?.count ?: 0))
                val staged = synchronized(protection) { record(tracks[key.songId], earlyPlays[key.songId]) }
                // The row is durable before the file takes its name. A crash in between leaves a row
                // without its file, which the next start resolves to the copy that is there (restore), plus the resumable
                // `.part`; never a complete file without a row, which the next start would delete as an orphan.
                try { persist(staged) } catch (error: Exception) { close(); throw error }
                crashPoint("commit.persisted")
                synchronized(protection) { doomed.remove(target.name) }
                if (!part.renameTo(target)) {
                    // The row goes again (best effort: a row without its file is resolved at the next start anyway).
                    runCatching { persist() }
                    close(); return@synchronized false
                }
                finished = true; marker.delete()
                synchronized(protection) { writers.remove(key.songId); partBases -= songFileBase(key.songId) }
                crashPoint("commit.renamed")
                var replaced: CachedTrack? = null
                // Only now is it a track: lookups and hand-off never see a path that doesn't exist yet.
                val published = synchronized(protection) {
                    tracks.compute(key.songId) { _, old -> replaced = old; record(old, takeEarlyPlay(key.songId)) }
                }
                // A play that arrived after the row was written is saved by the next flush.
                if (published != staged) dirty = true
                settle(key.songId)
                revision++
                event()
                // Opus → original: the Opus copy goes once the original is complete (when released, if the player holds it).
                replaced?.takeIf { it.file != target.name }?.let { deleteFile(it.file) }
                true
            }

        override fun close() = writerChange { closeLocked() }

        private fun closeLocked() = synchronized(this@TrackCache) {
            admitted.remove(this)
            if (!finished) {
                finished = true
                try {
                    if (resumable && channel.position() > 0) { channel.truncate(channel.position()); checkpoint() }
                } catch (_: IOException) { /* Resumes from the last good marker. */ }
                finally {
                    try { file.close() } catch (_: IOException) {}
                    val kept = !(!resumable || part.length() == 0L)
                    if (!kept) { part.delete(); marker.delete() }
                    synchronized(protection) {
                        writers.remove(key.songId)
                        // The partial file stays; with no pin or kept set it is an orphan now.
                        if (kept) orphanCheckDue = true else partBases -= songFileBase(key.songId)
                    }
                }
            }
        }
    }

    // ---- Legacy files (found without an index) ----

    /**
     * Adopts a legacy file (audio found without an index) for this song, if there is one: renamed by what it really
     * contains (the original first), dated as found. IO.
     */
    @Synchronized fun adoptLegacy(songId: String, format: SongFormat = SongFormat(), meta: TrackMeta? = null): Boolean {
        if (legacy.isEmpty()) return false
        val candidates = legacyCandidates(songId, format) { legacy.containsKey(it) }
        if (candidates.isEmpty()) return false
        val chosen = candidates.first().takeIf { !contains(songId) }
        val entries = candidates.associate { it.name to legacy.remove(it.name)!! }
        val record = chosen?.let { CachedTrack(songId, TrackKey(songId, it.kind).fileName, it.kind, it.file.length(),
            entries.getValue(it.name).datedAt, format = format, meta = meta) }
        // The adoption is durable before the file moves, but not yet a track: lookups and hand-off can't see a path
        // that doesn't exist. A crash after this finishes the move when the cache opens (restore).
        try { persist(record) } catch (error: Exception) { entries.forEach { (name, entry) -> legacy[name] = entry }; throw error }
        crashPoint("adopt.persisted")
        if (chosen != null && record != null) {
            if (!chosen.file.renameTo(File(directory, record.file))) {
                // Couldn't move it: it stays a legacy file.
                legacy[chosen.name] = entries.getValue(chosen.name); revision++; persist()
                return false
            }
            // Only now is it a track.
            val published = synchronized(protection) {
                tracks.compute(songId) { _, old ->
                    val early = takeEarlyPlay(songId)
                    record.copy(lastPlayedAt = later(old?.lastPlayedAt, early?.at), playCount = (old?.plays ?: 0) + (early?.count ?: 0))
                }
            }
            if (published != record) dirty = true
            settle(songId)
        }
        // After publication, like every other change: a reader that saw this revision also sees the new track.
        revision++
        crashPoint("adopt.renamed")
        candidates.forEach { if (it !== chosen) it.file.delete() }
        return chosen != null
    }

    private class LegacyCandidate(val name: String, val file: File, val kind: StreamFormat)

    /** The names a song's file may have in the legacy folder. */
    private fun legacyNames(songId: String) = StreamFormat.entries.map { TrackKey(songId, it).fileName }

    /** This song's legacy files that [present] accepts, by what they really contain, the original first. */
    private fun legacyCandidates(songId: String, format: SongFormat, present: (String) -> Boolean) =
        legacyNames(songId).filter(present).map { name ->
            val file = File(legacyDir, name)
            val head = runCatching { file.inputStream().use { it.readNBytes(256) } }.getOrDefault(ByteArray(0))
            val requested = if (name.endsWith(".orig")) StreamFormat.RAW else StreamFormat.OPUS_128
            LegacyCandidate(name, file, ArrivalDetector.kind(requested, null, head, format))
        }.sortedBy { it.kind.ordinal }

    // ---- Waveforms (per song) ----

    /** Memory only, safe for session publication. */
    fun waveform(songId: String): List<Float>? = waveforms.peaks(songId)
    fun waveform(key: TrackKey): List<Float>? = waveform(key.songId)
    /** Publishes only while a complete audio copy of the song exists. IO. */
    fun storeWaveform(songId: String, peaks: List<Float>): Boolean = contains(songId) && waveforms.store(songId, peaks)
    fun storeWaveform(key: TrackKey, peaks: List<Float>): Boolean = storeWaveform(key.songId, peaks)

    // ---- Reachability and persistence ----

    /**
     * The server's reachability decides whether old music may leave ([online]): while it is reachable expired
     * music leaves at the next cleanup; while it isn't, only Keep free space removes music (expired first).
     */
    @Synchronized fun serverReachable(reachable: Boolean) {
        if (online == reachable) return
        online = reachable
        revision++
    }

    /** Whether expired music may leave now (the server is known to be reachable). */
    val expiring: Boolean get() = online == true

    /** The cache's "now" (wall-clock ms). */
    fun now(): Long = wall()

    /**
     * Saves what changed in memory, and the whole index when the database was recreated during this session (the
     * fresh file gets its state row at once, not at the next change).
     */
    @Synchronized fun flush() { if (dirty || index.lost()) persist(); covers.save() }

    /**
     * Saves the index in one transaction. [staged] is a track row to save before its file is in place (a commit
     * or an adoption): written as if it were in [tracks], and no set waits for it any more.
     */
    @Synchronized private fun persist(staged: CachedTrack? = null) {
        if (retired) return
        dirty = false
        val next = CacheSnapshot(
            tracks = HashMap(tracks).also { map -> staged?.let { map[it.songId] = it } },
            sets = sets,
            pending = pending.mapValues { (_, ids) -> if (staged == null) ids else ids - staged.songId }.filterValues { it.isNotEmpty() },
            legacy = HashMap(legacy),
            removedForSpace = removedForSpace,
            queueDownload = queueDownload,
            replacementsCancelled = replacementsCancelled,
        )
        try { index.save(writer, persisted, next); persisted = next; degraded = false } catch (error: Exception) { dirty = true; throw error }
    }
}
