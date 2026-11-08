package dev.coda.android.session

import android.content.Context
import dev.coda.core.subsonic.ServerProbe
import dev.coda.core.subsonic.SubsonicConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The servers that have worked over https, as `host:port` (no scheme, user info or
 * path). A scheme-less address that matches one never falls back to http. It is separate from the five recent servers:
 * evicting a recent entry or signing out keeps it; only forgetting that server (× in the recents) removes it.
 *
 * How a change is never lost (re-review rounds 2-4):
 * - [add] and [forget] change the in-memory state first, synchronously under a plain lock, before any suspension
 *   point: the loaded set, or (while the stored set is not loaded or cannot be read) [unmerged], which is merged into
 *   the stored set when it loads (last change per server wins).
 * - Saving is a separate step that writes a snapshot of the current set. It runs in this history's own [scope] (a
 *   caller that is cancelled only stops waiting), one save at a time, and the write itself runs non-cancellable on
 *   the IO pool. The set stays dirty until a write returns true for a snapshot that is still current; any later use
 *   saves again.
 * - Reads wait for the stored set; a set not yet written (or merged) is saved before the first read
 *   answers. A set that cannot be read throws [Unavailable] (and is read again next time), so the caller refuses the
 *   http fallback (fail closed).
 */
class HttpsHistory internal constructor(
    private val scope: CoroutineScope,
    private val load: suspend () -> Stored,
    /** Writes the whole set; true only when it is committed. */
    private val save: suspend (Set<String>) -> Boolean,
) {
    /** What the storage holds: [persisted] is false when there is no file yet. */
    data class Stored(val keys: Set<String>, val persisted: Boolean)

    /** The stored history could not be read. */
    class Unavailable(cause: Throwable) : java.io.IOException("The https history could not be read.", cause)

    // In-memory state, guarded by [lock] (never held across a suspension).
    private val lock = Any()
    private var keys: MutableSet<String>? = null
    private val unmerged = LinkedHashMap<String, Boolean>() // true = add, false = forget
    private var dirty = false
    private var version = 0L

    private val loadMutex = Mutex()
    private var pending: Deferred<Result<Stored>>? = scope.async { runCatching { load() } }
    private val saveMutex = Mutex()

    /** Applies an add ([added]) or a forget of [key] to memory: no suspension, so it cannot be cancelled half-way. */
    private fun apply(key: String, added: Boolean) = synchronized(lock) {
        val current = keys
        if (current == null) unmerged[key] = added
        else if (if (added) current.add(key) else current.remove(key)) { dirty = true; version++ }
    }

    /** Loads (or reads again after a failure) the stored set and merges the changes seen meanwhile. */
    private suspend fun ensureLoaded() {
        if (synchronized(lock) { keys != null }) return
        loadMutex.withLock {
            if (synchronized(lock) { keys != null }) return
            val result = pending?.await() ?: runCatching { load() }
            pending = null // a failed read is tried again on the next use
            val stored = result.getOrElse { if (it is CancellationException) throw it else throw Unavailable(it) }
            synchronized(lock) {
                val merged = stored.keys.toMutableSet()
                unmerged.forEach { (key, added) -> if (added) merged.add(key) else merged.remove(key) }
                unmerged.clear()
                dirty = !stored.persisted || merged != stored.keys
                version++
                keys = merged
            }
        }
    }

    /** Saves the current snapshot if it is dirty; owned by [scope], so the caller's cancellation cannot stop it. */
    private suspend fun saveNow() {
        val job = scope.launch {
            saveMutex.withLock {
                val (snapshot, at) = synchronized(lock) {
                    val current = keys
                    if (!dirty || current == null) return@withLock
                    current.toSet() to version
                }
                val committed = withContext(NonCancellable + Dispatchers.IO) {
                    try { save(snapshot) } catch (_: Exception) { false }
                }
                if (committed) synchronized(lock) { if (version == at) dirty = false }
            }
        }
        job.join()
    }

    /**
     * Did [address] (a URL, or an address without a scheme, meaning https) work over https before? Throws [Unavailable]
     * when the stored history cannot be read.
     */
    suspend fun contains(address: String): Boolean {
        val key = key(address) ?: return false
        ensureLoaded()
        if (synchronized(lock) { dirty }) saveNow() // a new, merged or unsaved set is written before it answers
        return synchronized(lock) { keys?.contains(key) == true }
    }

    /** Records an https [url] that answered (a successful sign-in or a Subsonic answer to the check); http is ignored. */
    suspend fun add(url: String) = change(url, added = true)

    /** The user forgot [url] (× in the recent servers): its host and port may fall back again. */
    suspend fun forget(url: String) = change(url, added = false)

    private suspend fun change(url: String, added: Boolean) {
        val key = key(url) ?: return
        apply(key, added)
        try { ensureLoaded() } catch (_: Unavailable) { return } // kept in [unmerged] until the set can be read
        saveNow()
    }

    companion object {
        /**
         * `host:port` of [address] when it is https (no scheme counts as https). The host is OkHttp's canonical form
         * (lower case, IDN, IPv6) without the DNS root dot, so `music.example.org.` and `music.example.org` match.
         */
        fun key(address: String): String? {
            val trimmed = address.trim()
            if (trimmed.isEmpty()) return null
            val url = runCatching { SubsonicConfig.normalizeServerURL(trimmed) }.getOrNull()?.toHttpUrlOrNull() ?: return null
            if (!url.isHttps) return null
            val host = url.host.trimEnd('.')
            return (if (':' in host) "[$host]" else host) + ":" + url.port // IPv6 in brackets, so the key reads back as a URL
        }

        /** Same server for the https rule: same canonical host and port; [known] must be an https URL. */
        fun sameHttpsServer(input: String, known: String): Boolean =
            !ServerProbe.hasScheme(input) && key(input)?.let { it == key(known) } == true

        /** What the history starts from: the history file's keys ([fileKeys]); no file is an empty history, still to be written. */
        internal fun stored(fileKeys: Set<String>?): Stored = Stored(fileKeys ?: emptySet(), persisted = fileKeys != null)

        /**
         * A history kept in [file] ([HttpsHistoryFile], an `AtomicFile`). A missing file is an empty history; an unreadable
         * or damaged file makes the history [Unavailable] (fail closed).
         */
        internal fun inFile(scope: CoroutineScope, file: java.io.File, storage: HttpsHistoryFile = HttpsHistoryFile(file)): HttpsHistory =
            HttpsHistory(scope,
                load = { withContext(Dispatchers.IO) { stored(storage.read()) } },
                save = { value -> withContext(Dispatchers.IO) { storage.write(value) } },
            )

        /** The app's history: `files/https-history`. */
        fun forApp(context: Context, scope: CoroutineScope): HttpsHistory = inFile(scope, java.io.File(context.filesDir, "https-history"))
    }
}

/**
 * The https history file. Unlike SharedPreferences (which reads a damaged file as empty), a missing file
 * and a damaged one are told apart: [read] returns null only when there is no file, and throws for any read or format
 * error, so the history fails closed. Format (UTF-8): the header line, one `host:port` per line, then an `end` line, so
 * a truncated file is detected. Written atomically ([android.util.AtomicFile]: a write either completes or the old
 * file stays).
 */
internal class HttpsHistoryFile(
    file: java.io.File,
    /** Makes the written bytes durable; must throw on failure ([java.io.FileDescriptor.sync]). A seam for tests. */
    sync: (java.io.FileOutputStream) -> Unit = { it.fd.sync() },
    /** Replaces the file with the new one. `finishWrite` only logs its failures, so [write] verifies the result. */
    finish: (android.util.AtomicFile, java.io.FileOutputStream) -> Unit = { atomic, out -> atomic.finishWrite(out) },
) {
    private val storage = VerifiedAtomicFile(file, sync, finish)

    /** The stored keys, or null when there is no file. Throws [java.io.IOException] for unreadable or damaged files. */
    fun read(): Set<String>? = storage.read()?.let(::parse)

    /** Writes [keys] atomically; true only when committed (see [VerifiedAtomicFile.write]). */
    fun write(keys: Set<String>): Boolean = storage.write(encode(keys))

    companion object {
        const val HEADER = "coda-https-history 1"
        const val END = "end"

        fun encode(keys: Set<String>): ByteArray =
            (listOf(HEADER) + keys.sorted() + END).joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)

        /** Strict: anything but a complete file of this format throws. */
        fun parse(bytes: ByteArray): Set<String> {
            val text = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (error: java.nio.charset.CharacterCodingException) {
                throw java.io.IOException("https history: not UTF-8", error)
            }
            val lines = text.split('\n')
            if (lines.size < 3 || lines.first() != HEADER) throw java.io.IOException("https history: bad header")
            if (lines.last() != "" || lines[lines.size - 2] != END) throw java.io.IOException("https history: truncated")
            return lines.subList(1, lines.size - 2).map { line ->
                // Each line must already be a canonical key (what [HttpsHistory.key] writes).
                if (HttpsHistory.key("https://$line") != line) throw java.io.IOException("https history: bad entry")
                line
            }.toSet()
        }
    }
}

/**
 * An `AtomicFile` whose write is verified. [read] returns null only
 * when there is no file (and no backup of an interrupted write) and throws for any read error, so a damaged file is
 * never an absent one. [write] is true only when the bytes were synced (`fd.sync`, throwing), `finishWrite` ran, and
 * the replaced file reads back as written (`finishWrite` itself only logs sync, close and rename failures).
 */
internal class VerifiedAtomicFile(
    file: java.io.File,
    private val sync: (java.io.FileOutputStream) -> Unit = { it.fd.sync() },
    private val finish: (android.util.AtomicFile, java.io.FileOutputStream) -> Unit = { atomic, out -> atomic.finishWrite(out) },
) {
    private val atomic = android.util.AtomicFile(file)
    private val backup = java.io.File(file.path + ".bak") // AtomicFile's backup of an interrupted write

    /** The stored bytes, or null when there is no file. Throws [java.io.IOException] when it cannot be read. */
    fun read(): ByteArray? = try {
        atomic.readFully() // restores the backup of an interrupted write first
    } catch (missing: java.io.FileNotFoundException) {
        if (atomic.baseFile.exists()) throw missing // there is a file, it just cannot be opened
        if (backup.exists()) throw missing // a backup exists but could not be restored: not an absent file
        null
    }

    /** Writes [bytes] atomically; true only when committed and read back identical. */
    fun write(bytes: ByteArray): Boolean {
        val out = try { atomic.startWrite() } catch (_: java.io.IOException) { return false }
        try {
            out.write(bytes)
            out.flush()
            sync(out)
        } catch (_: Exception) {
            atomic.failWrite(out)
            return false
        }
        try { finish(atomic, out) } catch (_: Exception) { runCatching { out.close() }; return false }
        return try { atomic.readFully().contentEquals(bytes) } catch (_: Exception) { false }
    }
}
