package dev.coda.core.library

import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import dev.coda.core.accent.AccentSelection
import dev.coda.core.accent.AccentSelectionKind
import dev.coda.core.accent.ArtworkColor
import dev.coda.core.accent.ColourAlgorithms
import dev.coda.store.AccentRow
import dev.coda.store.ArtistLookRow
import dev.coda.store.LibraryDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * `library.db` of one account (server + user): library page copies (row 1), the offline catalogue (rows 2, 3, see
 * [CatalogueCollector]) and persisted colours (rows 4, 5). Opened lazily, off the main thread, on first use.
 *
 * The file also holds the music cache's index ([transact], `CacheIndexDb`), so it is durable. Only as a last
 * resort, a damaged file (SQLITE_CORRUPT, SQLITE_NOTADB or a failed integrity check, also later) is renamed aside
 * (`library.db.broken`, one kept) and a fresh one starts silently. Any other failure to open (a missing migration, a
 * downgrade, a full disk) leaves the file alone and only fails the current use. A fresh file has no cache state row, which the music
 * cache reads as "no index": it adopts the audio files it finds and deletes none. Errors of [use] never reach the
 * caller: reads return their fallback, writes are dropped (class names only in the log); [transact] rethrows.
 */
class LibraryStore(
    val file: File,
    /** Builds the Room database at a path (AndroidSQLiteDriver on the phone, BundledSQLiteDriver in JVM tests). */
    private val build: (path: String) -> LibraryDatabase,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    /** Ordinary pages are trimmed least-recently-written to this size; catalogue pages are never trimmed. */
    val pageLimitBytes: Long = 20L * 1024 * 1024,
) : PageStore {
    private val mutex = Mutex()
    private var db: LibraryDatabase? = null
    @Volatile private var closed = false
    val isClosed: Boolean get() = closed
    /** Fire-and-forget writes (page mirror, colours) run here in order, one at a time. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closedSignal = CompletableDeferred<Unit>()
    /** Page keys the catalogue refers to (see [CatalogueCollector.protectedPages]); read and written only under [use]. Null: not computed yet. */
    private var protectedKeys: MutableSet<String>? = null
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    /** Rebuilds after damage (tests). */
    @Volatile var rebuilds = 0
        private set

    init {
        scope.launch { for (write in writes) try { write() } catch (e: CancellationException) { throw e } catch (_: Exception) { } }
    }

    /** Runs [block] on the open database; on any failure returns [fallback], and rebuilds the file if it is damaged. */
    suspend fun <T> use(fallback: T, block: suspend (LibraryDatabase) -> T): T = mutex.withLock {
        if (closed) return fallback
        // Opening, renaming a damaged file and the queries all run on IO, whatever thread the caller is on.
        withContext(Dispatchers.IO) {
            try {
                block(openLocked())
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                log("library.db: ${e.javaClass.simpleName}")
                if (!healthyLocked()) rebuildLocked()
                fallback
            }
        }
    }

    /**
     * Runs [block] in one immediate write transaction and rethrows any failure: the music cache must know when its
     * write did not land (it then does not move or delete files). A damaged file is still set aside as in [use].
     */
    suspend fun <T> transact(block: suspend (LibraryDatabase) -> T): T = mutex.withLock {
        if (closed) throw java.io.IOException("library.db closed")
        withContext(Dispatchers.IO) {
            try {
                val database = openLocked()
                database.useWriterConnection { connection ->
                    // Room's WAL default (synchronous=NORMAL) may lose the last commit on power loss while a file
                    // rename after it survives; FULL syncs every commit. Only the writer connection writes.
                    connection.usePrepared("PRAGMA synchronous = FULL") { it.step() }
                    // Back to Room's NORMAL afterwards, so catalogue, page and colour writes keep their speed.
                    try { connection.immediateTransaction { block(database) } }
                    finally { runCatching { connection.usePrepared("PRAGMA synchronous = NORMAL") { it.step() } } }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                log("library.db cache write: ${e.javaClass.simpleName}")
                if (!healthyLocked()) rebuildLocked()
                throw e
            }
        }
    }

    /** Queues a write behind the earlier ones; never blocks or throws. */
    fun enqueue(block: suspend (LibraryDatabase) -> Unit) {
        if (!closed) writes.trySend { use(Unit) { block(it) } }
    }

    /** Waits until every write queued before this call ran (tests, and before a dependent read). */
    suspend fun awaitWrites() {
        val done = CompletableDeferred<Unit>()
        if (closed || writes.trySend { done.complete(Unit) }.isFailure) return
        // A close() after the send cancels the consumer: do not wait for a write that will never run.
        select<Unit> { done.onAwait { }; closedSignal.onAwait { } }
    }

    private suspend fun openLocked(): LibraryDatabase {
        db?.let { return it }
        file.parentFile?.mkdirs()
        val opened = try { buildProbed() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            // Only real damage sets the file aside. Anything else (a missing migration, a downgrade, an identity
            // mismatch, a full disk, a file that cannot be opened now) fails this use and keeps the file as it is.
            if (!isCorruption(e)) { log("library.db cannot be opened now: ${e.javaClass.simpleName}"); throw e }
            log("library.db damaged, starting fresh: ${e.javaClass.simpleName}")
            quarantine()
            buildProbed()
        }
        if (closed) { runCatching { opened.close() }; throw java.io.IOException("closed") }
        db = opened
        // Colours from an older algorithm are never shown: drop them once per open.
        opened.colours().dropOtherAccents(ColourAlgorithms.ACCENT)
        opened.colours().dropOtherLooks(ColourAlgorithms.ARTIST_PHOTO)
        return opened
    }

    private suspend fun buildProbed(): LibraryDatabase {
        val database = build(file.path)
        try { probe(database) } catch (e: Exception) { runCatching { database.close() }; throw e }
        return database
    }

    /** A failed `quick_check`: the file is damaged. */
    private class IntegrityFailure : IllegalStateException("integrity check failed")

    private suspend fun probe(database: LibraryDatabase) {
        val result = database.useReaderConnection { connection ->
            connection.usePrepared("PRAGMA quick_check") { statement -> if (statement.step()) statement.getText(0) else "" }
        }
        if (result != "ok") throw IntegrityFailure()
    }

    /** False only when the open file is damaged (corrupt, not a database, or a failed `quick_check`). */
    private suspend fun healthyLocked(): Boolean {
        val current = db ?: return true
        return try { probe(current); true } catch (e: CancellationException) { throw e } catch (e: Exception) { !isCorruption(e) }
    }

    private fun rebuildLocked() {
        log("library.db damaged, starting fresh")
        quarantine()
    }

    /** Moves the damaged file aside (one copy kept) with its journal files; the next use builds a fresh database. */
    private fun quarantine() {
        rebuilds++
        // Offline search forgets the lost file's pages at once.
        pageRevision++
        db?.let { runCatching { it.close() } }
        db = null
        protectedKeys = null
        val broken = File(file.path + ".broken")
        broken.delete()
        if (file.exists() && !file.renameTo(broken)) file.delete()
        for (suffix in listOf("-wal", "-shm", "-journal")) File(file.path + suffix).delete()
    }

    /** Closes the database file (may checkpoint the WAL): call it off the main thread. */
    fun close() {
        closed = true
        closedSignal.complete(Unit)
        writes.close()
        scope.cancel()
        db?.let { runCatching { it.close() } }
        db = null
    }

    // Row 1: page copies.

    /** The account's one [SnapshotStore] (screens and the offline library share it; risk 11 of the DB review). */
    val snapshots: SnapshotStore by lazy { SnapshotStore(this) }

    @Volatile override var pageRevision = 0
        private set

    private val cataloguePageSaves = kotlinx.coroutines.flow.MutableStateFlow(0)
    /**
     * NF-4 §3/§5: moves when a screen saves an album page the catalogue should take: the album's entry is minimal, or it is
     * full and the page differs from the saved one (a rename, tracks added or removed on the server). The catalogue then
     * takes it from that page without waiting for the next cache change. Not for the catalogue's own writes.
     */
    val catalogueAlbumPageSaves: kotlinx.coroutines.flow.StateFlow<Int> get() = cataloguePageSaves
    /** Full catalogue albums whose saved page changed since the last pass (this process only; see [takeChangedAlbums]). */
    private val changedAlbums = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    /** The full albums whose page a screen saved with new contents since the last call; the catalogue re-takes them. */
    fun takeChangedAlbums(): Set<String> = synchronized(changedAlbums) { changedAlbums.toSet().also { changedAlbums.clear() } }

    /**
     * [changedAlbums] is not kept across a process death, so the first pass of each opened store compares
     * every saved album page with its full entry instead ([takeReconcile] is true once).
     */
    @Volatile private var reconcile = true
    fun takeReconcile(): Boolean = synchronized(changedAlbums) { reconcile.also { reconcile = false } }

    /** A pass whose ingestion failed gives its work back for the next pass. */
    fun requeue(albums: Set<String>, reconcileAll: Boolean) = synchronized(changedAlbums) {
        changedAlbums += albums
        if (reconcileAll) reconcile = true
    }

    /** A page write from the catalogue: queued behind the earlier writes. */
    fun mirrorPage(key: String, json: String) = enqueue { db -> writePage(db, key, json, fromCatalogue = true) }

    override suspend fun putPage(key: String, json: String) = use(Unit) { db -> writePage(db, key, json) }

    /** Under [use]. Pinned when a kept set has [key]; trims ordinary pages past the limit. */
    private suspend fun writePage(db: LibraryDatabase, key: String, json: String, fromCatalogue: Boolean = false) {
        // A screen's album page for an album in the catalogue: one row read (and the old page only for a full entry).
        val albumId = key.takeIf { !fromCatalogue && it.startsWith("album/") }?.removePrefix("album/")
        val entry = albumId?.let { db.catalogue().album(it) }
        val changed = entry != null && !entry.minimal && db.pages().get(key)?.json.let { old -> old != json && catalogueView(old) != catalogueView(json) }
        db.pages().put(key, json, json.toByteArray().size, nowMs())
        pageRevision++
        if (changed) changedAlbums += albumId!!
        if (entry != null && (entry.minimal || changed)) cataloguePageSaves.value++
        // A catalogue page never counts against the limit, so writing one cannot make a trim necessary (and a first run
        // that mirrors thousands of album pages does no scan per page).
        if (key !in protectedKeys(db)) trimPages(db)
    }

    /**
     * O1: what the catalogue takes from an album page, without the play data (play count, last played) that every
     * play changes. Null when there is no page or it does not decode (then any new page counts as a change).
     */
    private fun catalogueView(text: String?): AlbumSnapshot? = text?.let {
        runCatching { CatalogueCollector.json.decodeFromString(AlbumSnapshot.serializer(), it) }.getOrNull()
    }?.let { it.copy(album = it.album.copy(playCount = null, played = null)) }

    /** The page's JSON; the read is noted for the LRU behind it (queued, never delays the screen). */
    override suspend fun page(key: String): String? = use(null) { it.pages().get(key)?.json }
        ?.also { val at = nowMs(); enqueue { db -> db.pages().touch(key, at) } }

    override suspend fun pinnedPages(): List<String> = use(emptyList()) { it.pages().pinnedJson() }
    /** Each chunk is its own [use]: cache writes get the lock between chunks. */
    override suspend fun pagesAfter(after: String, limit: Int): List<Pair<String, String>> =
        use(emptyList()) { db -> db.pages().after(after, limit).map { it.key to it.json } }
    override suspend fun markStale(key: String) = use(Unit) { it.pages().markStale(key) }
    override suspend fun isStale(key: String): Boolean = use(false) { it.pages().stale(key) == true }

    /** The protected page keys, computed once and kept up to date by [addProtected]; [refresh] recomputes them. Under [use]. */
    internal suspend fun protectedKeys(db: LibraryDatabase, refresh: Boolean = false): MutableSet<String> {
        if (!refresh) protectedKeys?.let { return it }
        return CatalogueCollector.protectedPages(db).toMutableSet().also { protectedKeys = it }
    }

    /** Notes pages that just became catalogue pages. Under [use]; does nothing before the set was computed (it then includes them). */
    internal fun addProtected(keys: Collection<String>) { protectedKeys?.addAll(keys) }

    /**
     * Deletes the oldest ordinary pages until the ordinary pages fit [pageLimitBytes]. Pages the catalogue refers to and
     * pinned pages (kept sets) stay and do not count: a big catalogue never empties the ordinary pages. Under [use].
     */
    internal suspend fun trimPages(db: LibraryDatabase, refresh: Boolean = false) {
        if (db.pages().totalBytes() <= pageLimitBytes) return
        val protected = protectedKeys(db, refresh)
        val pages = db.pages().byAge()
        var ordinary = pages.sumOf { if (it.pinned || it.key in protected) 0L else it.bytes.toLong() }
        val doomed = mutableListOf<String>()
        for (page in pages) {
            if (ordinary <= pageLimitBytes) break
            if (page.pinned || page.key in protected) continue
            doomed += page.key
            ordinary -= page.bytes
        }
        doomed.chunked(500).forEach { db.pages().delete(it) }
    }

    // Row 4: album accents (the memory cache in AccentRepository stays the front; this is its persistent copy).

    suspend fun accent(coverId: String, size: Int): AccentSelection? =
        use(null) { it.colours().accent(coverId, size, ColourAlgorithms.ACCENT)?.selection() }

    /** The [limit] most recently derived accents, for seeding the memory cache at start. */
    suspend fun recentAccents(limit: Int): List<Pair<Pair<String, Int>, AccentSelection>> =
        use(emptyList()) { db -> db.colours().recentAccents(ColourAlgorithms.ACCENT, limit).mapNotNull { row -> row.selection()?.let { (row.coverId to row.size) to it } } }

    fun saveAccent(coverId: String, size: Int, accent: AccentSelection) = enqueue {
        it.colours().upsertAccent(AccentRow(coverId, size, ColourAlgorithms.ACCENT, accent.color.red, accent.color.green, accent.color.blue, accent.kind.name, nowMs()))
    }

    /** A refreshed cover: its persisted accents go with the memory ones. */
    fun forgetAccents(coverIds: Collection<String>) {
        val ids = coverIds.toList()
        if (ids.isNotEmpty()) enqueue { db -> ids.chunked(500).forEach { db.colours().forgetAccents(it) } }
    }

    private fun AccentRow.selection(): AccentSelection? =
        AccentSelectionKind.entries.firstOrNull { it.name == kind }?.let { AccentSelection(ArtworkColor(red, green, blue), it) }

    // Row 5: artist photo colours.

    /** One persisted artist photo look; [page] and [years] are ARGB. */
    data class PhotoLook(val artistId: String, val imageId: String, val width: Int, val height: Int, val page: Int, val years: Int?)

    suspend fun recentLooks(limit: Int): List<PhotoLook> = use(emptyList()) { db ->
        db.colours().recentLooks(ColourAlgorithms.ARTIST_PHOTO, limit).map { PhotoLook(it.artistId, it.imageId, it.width, it.height, it.page, it.years) }
    }

    /** The look derived earlier from this photo at this view size, or null. */
    suspend fun look(imageId: String, width: Int, height: Int): PhotoLook? = use(null) { db ->
        db.colours().look(imageId, width, height, ColourAlgorithms.ARTIST_PHOTO)?.let { PhotoLook(it.artistId, it.imageId, it.width, it.height, it.page, it.years) }
    }

    /** A refreshed photo: its persisted looks go. */
    fun forgetLooks(imageIds: Collection<String>) {
        val ids = imageIds.toList()
        if (ids.isNotEmpty()) enqueue { db -> ids.chunked(500).forEach { db.colours().forgetLooks(it) } }
    }

    fun saveLook(look: PhotoLook) = enqueue {
        it.colours().upsertLook(ArtistLookRow(look.imageId, look.width, look.height, ColourAlgorithms.ARTIST_PHOTO, look.artistId, look.page, look.years, nowMs()))
    }
}

/**
 * Whether [error] (or a cause) is real damage: SQLITE_CORRUPT (11), SQLITE_NOTADB (26) or a failed `quick_check`.
 * The Android driver throws `SQLiteDatabaseCorruptException`; the bundled one an `SQLiteException` naming the code.
 */
internal fun isCorruption(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(8).any { e ->
    e.javaClass.simpleName == "IntegrityFailure" || e.javaClass.simpleName.contains("Corrupt") ||
        CORRUPTION.containsMatchIn(e.message.orEmpty())
}

private val CORRUPTION = Regex("SQLITE_CORRUPT|SQLITE_NOTADB|code:? ?(11|26)\\b|not a database|malformed", RegexOption.IGNORE_CASE)
