package dev.coda.android.session

import android.content.Context
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
 * The plain-http servers the user agreed to sign in to without encryption ("Sign in anyway"),
 * as `host:port` of an `http://` address (no user info or path; the host as in [HttpsHistory.key]). The sign-in screen
 * asks once per server; an agreement is stored only when that sign-in succeeds. It is its own persistent set next to
 * the https history, never part of the five recent servers: evicting a recent entry or signing out keeps it; only
 * forgetting that server (× in the recents) removes it.
 *
 * Persistence follows the https history: a set migrated at upgrade (a saved http session counts as
 * agreed) is written at start and before it is used; a change whose write failed, was cancelled or was not committed
 * stays dirty and is written again on the next use; a set that cannot be read throws [Unavailable] (and is read again
 * next time), so the caller asks again (fail closed). A forget is never lost to that: it is kept as a pending removal
 * (in memory, independent of a successful load) and applied, and then written, as soon as the set can be read; it also
 * survives the caller's cancellation. A stored file is only read when complete (header, entries, `end`), so a truncated
 * file can never grant an agreement the user did not give.
 *
 * A forget also survives process death while the set is unreadable: it is written to its own small
 * revocation file ([Revocations], exact `host:port` keys and whole hosts) before anything else, independent of loading
 * the set. A set that is read applies the revocations first (so nothing revoked is ever granted), and the revocation
 * file is cleared once the set without them is committed. A revocation file that cannot be read or is damaged revokes
 * everything (ask again for everything). When a forget could not be made durable at all it still holds in this process
 * and [forget] throws [RevocationNotSaved], so the failure is not silent.
 */
class PlainHttpConsent internal constructor(
    scope: CoroutineScope,
    private val load: suspend () -> Stored,
    /** Writes the whole set; true only when it is committed. */
    private val save: suspend (Set<String>) -> Boolean,
    /** The revocation file: absent = [Revocations.NONE]; unreadable or damaged = throws (everything is then revoked). */
    private val loadRevocations: suspend () -> Revocations = { Revocations.NONE },
    /** Writes the whole revocation file; true only when it is committed. */
    private val saveRevocations: suspend (Revocations) -> Boolean = { true },
) {
    /** What the storage holds: [persisted] is false for a set migrated from the saved session and not written yet. */
    data class Stored(val keys: Set<String>, val persisted: Boolean)

    /** The stored agreements could not be read. */
    class Unavailable(cause: Throwable) : java.io.IOException("The plain-http agreements could not be read.", cause)

    /** A forget holds in this process but could not be written anywhere: after a restart the agreement may return. */
    class RevocationNotSaved : java.io.IOException("The forgotten plain-http agreement could not be saved.")

    private var pending: Deferred<Result<Stored>>? = scope.async { runCatching { load() } }
    private val mutex = Mutex()
    private var keys: MutableSet<String>? = null
    private var dirty = false

    /** The revocation file as stored (null = not read yet); [clearPending]: it holds revocations the set has absorbed. */
    private var storedRevocations: Revocations? = null
    private var clearPending = false

    /** Forgets not yet applied to a loaded set (the set was unreadable); applied at the next load, never dropped. */
    private val pendingForgets = ArrayList<Revocation>()

    /** Forgets not yet in the revocation file (its write failed, or has not run yet); kept until written. */
    private val unsavedForgets = ArrayList<Revocation>()

    init {
        // Write a migrated set right away: signing out before the first sign-in screen must not lose it.
        scope.launch { try { withKeys { } } catch (error: Exception) { if (error is CancellationException) throw error } }
    }

    private suspend fun <T> withKeys(block: suspend (MutableSet<String>) -> T): T = mutex.withLock {
        val current = keys ?: run {
            val result = pending?.await() ?: runCatching { load() }
            pending = null // a failed read is tried again on the next use
            val stored = result.getOrElse { if (it is CancellationException) throw it else throw Unavailable(it) }
            dirty = !stored.persisted
            val loaded = stored.keys.toMutableSet()
            // Revocations stored by earlier forgets (or all of them, when that file is damaged) come off before use.
            val revoked = storedRevocations()
            if (!revoked.isEmpty) { if (loaded.removeAll { revoked.matches(it) }) dirty = true; clearPending = true }
            loaded.also { keys = it }
        }
        applyPendingForgets(current)
        settle(current)
        block(current)
    }

    private suspend fun storedRevocations(): Revocations = storedRevocations ?: run {
        val read = try { loadRevocations() } catch (error: Exception) { if (error is CancellationException) throw error; Revocations.ALL }
        read.also { storedRevocations = it }
    }

    /** Writes [current] if needed; once the set is committed, the revocations it absorbed are cleared from their file. */
    private suspend fun settle(current: Set<String>) {
        writeIfDirty(current)
        if (dirty || !clearPending) return
        val cleared = try { saveRevocations(Revocations.NONE) } catch (error: Exception) { if (error is CancellationException) throw error; false }
        if (cleared) { storedRevocations = Revocations.NONE; clearPending = false }
    }

    /** Writes the stored revocations plus every forget not yet in the file (so an earlier failed write is retried); true when committed. */
    private suspend fun persistRevocations(): Boolean = mutex.withLock {
        val stored = storedRevocations()
        // A damaged file revokes everything, but the same state also stands for a transient read error over a valid file
        // that does not hold this forget, and nothing is written here: never durable on its own (the set must commit it).
        if (stored.all) { clearPending = true; return@withLock false }
        val unsaved = synchronized(unsavedForgets) { unsavedForgets.toList() }
        val merged = unsaved.fold(stored) { all, revocation -> all + revocation }
        val saved = try { saveRevocations(merged) } catch (error: Exception) { if (error is CancellationException) throw error; false }
        if (saved) {
            storedRevocations = merged
            clearPending = true
            synchronized(unsavedForgets) { unsavedForgets.removeAll(unsaved.toSet()) }
        }
        saved
    }

    private fun applyPendingForgets(current: MutableSet<String>) {
        val forgotten = synchronized(pendingForgets) { pendingForgets.toList().also { pendingForgets.clear() } }
        for (revocation in forgotten) if (current.removeAll { revocation.matches(it) }) dirty = true
    }

    /** Writes [current] if a change is not stored yet; it stays dirty until a write is committed. */
    private suspend fun writeIfDirty(current: Set<String>) {
        if (!dirty) return
        val committed = try { save(current.toSet()) } catch (error: Exception) { if (error is CancellationException) throw error; false }
        if (committed) dirty = false
    }

    /** Did the user agree to sign in to the http [url] without encryption? Throws [Unavailable] when unreadable. */
    suspend fun contains(url: String): Boolean {
        val key = key(url) ?: return false
        return withKeys { key in it }
    }

    /** A sign-in to the http [url] succeeded after the user agreed; https and other addresses are ignored. */
    suspend fun agree(url: String) {
        val key = key(url) ?: return
        withKeys { if (it.add(key)) { dirty = true; settle(it) } }
    }

    /**
     * The user forgot [url] (× in the recent servers): the next sign-in asks again. An http [url] revokes that
     * `host:port`; an https [url] (an http sign-in that upgraded to https is recorded as an https recent, possibly on
     * another port) revokes every port of that host. The removal is recorded first and is applied (and written) as
     * soon as the set is readable, so an unreadable set neither throws here nor brings the agreement back later; it
     * runs to the end even when the caller is cancelled.
     */
    suspend fun forget(url: String) {
        val revocation = Revocation.of(url) ?: return
        synchronized(pendingForgets) { pendingForgets.add(revocation) }
        synchronized(unsavedForgets) { unsavedForgets.add(revocation) }
        val durable = withContext(NonCancellable) {
            val saved = persistRevocations()
            // Durable either way: in the revocation file, or the set without it is committed.
            val absorbed = try { withKeys { !dirty } } catch (_: Unavailable) { false /* kept pending, applied when readable */ }
            saved || absorbed
        }
        if (!durable) throw RevocationNotSaved()
    }

    /** What a forget removes: one `host:port` (an http address) or every port of a host (an https recent). */
    internal class Revocation private constructor(internal val key: String?, internal val host: String?) {
        fun matches(entry: String): Boolean = if (key != null) entry == key else entry.substringBeforeLast(':') == host

        companion object {
            fun of(url: String): Revocation? {
                val parsed = runCatching { SubsonicConfig.normalizeServerURL(url.trim()) }.getOrNull()?.toHttpUrlOrNull() ?: return null
                val host = parsed.host.trimEnd('.')
                return if (parsed.isHttps) Revocation(null, host) else Revocation(host + ":" + parsed.port, null)
            }
        }
    }

    /**
     * The stored revocations: exact `host:port` keys (an http forget) and whole hosts (an https forget). [all] is the
     * fail-closed state of a revocation file that cannot be read: every agreement is revoked.
     */
    internal data class Revocations(val keys: Set<String> = emptySet(), val hosts: Set<String> = emptySet(), val all: Boolean = false) {
        val isEmpty get() = !all && keys.isEmpty() && hosts.isEmpty()
        fun matches(entry: String): Boolean = all || entry in keys || entry.substringBeforeLast(':') in hosts
        operator fun plus(revocation: Revocation): Revocations =
            copy(keys = keys + listOfNotNull(revocation.key), hosts = hosts + listOfNotNull(revocation.host))

        companion object {
            val NONE = Revocations()
            val ALL = Revocations(all = true)
        }
    }

    companion object {
        /** `host:port` of [url] when it is a plain `http://` address without user info; otherwise null. */
        fun key(url: String): String? {
            val trimmed = url.trim()
            if (!trimmed.startsWith("http://", ignoreCase = true)) return null
            val parsed = runCatching { SubsonicConfig.normalizeServerURL(trimmed) }.getOrNull()?.toHttpUrlOrNull() ?: return null
            if (parsed.isHttps || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
            return parsed.host.trimEnd('.') + ":" + parsed.port
        }

        /** True for an address that would sign in over plain http (the same test as the "http" chip). */
        fun isPlainHttp(url: String): Boolean = url.trim().startsWith("http://", ignoreCase = true)

        /**
         * The stored set, or (before it existed, i.e. at the upgrade) the saved session's server when it is http:
         * it counts as agreed and still has to be written.
         */
        internal fun stored(set: Set<String>?, savedServerUrl: String?): Stored = set?.let { Stored(it.toSet(), persisted = true) }
            ?: Stored(listOfNotNull(savedServerUrl?.let(::key)).toSet(), persisted = false)

        private const val HEADER = "coda-plain-http-consent v1"
        private const val END = "end"
        internal const val FILE = "plain-http-consent.txt"

        /** The stored file: a version header, one `host:port` per line, then an `end` line (a cut file is detected). */
        internal fun format(keys: Set<String>): String = (listOf(HEADER) + keys.sorted() + END).joinToString("\n", postfix = "\n")

        /**
         * Parses [format]'s output, strictly (as [HttpsHistoryFile]): valid UTF-8, the header, canonical entries, the
         * `end` line and the final newline, nothing else. Anything else throws [java.io.IOException], so a damaged or
         * truncated file is "unavailable" and the screen asks again (fail closed): never fewer or different entries
         * (a port cut from `:8080` to `:80` is no agreement), and never an empty set read as valid.
         */
        internal fun parse(bytes: ByteArray): Set<String> {
            val text = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (error: java.nio.charset.CharacterCodingException) {
                throw java.io.IOException("plain-http consent: not UTF-8", error)
            }
            return parse(text)
        }

        internal fun parse(text: String): Set<String> {
            val lines = text.split('\n')
            if (lines.size < 3 || lines.first() != HEADER) throw java.io.IOException("plain-http consent: bad header")
            if (lines.last() != "" || lines[lines.size - 2] != END) throw java.io.IOException("plain-http consent: truncated")
            return lines.subList(1, lines.size - 2).map { line ->
                if (!isCanonical(line)) throw java.io.IOException("plain-http consent: bad entry")
                line
            }.toSet()
        }

        /** Exactly what [key] writes: `host:port`, the port without leading zeros, 1..65535. */
        private fun isCanonical(line: String): Boolean {
            val split = line.lastIndexOf(':')
            if (split <= 0) return false
            val host = line.substring(0, split)
            if (line.substring(split + 1).any { it !in '0'..'9' }) return false
            return key("http://" + (if (':' in host) "[$host]" else host) + line.substring(split)) == line
        }

        private const val REVOKED_HEADER = "coda-plain-http-revoked v1"
        internal const val REVOKED_FILE = "plain-http-revoked.txt"

        /** The revocation file: a header, `k host:port` / `h host` lines, then `end`. [Revocations.ALL] is never written. */
        internal fun formatRevocations(revoked: Revocations): String =
            (listOf(REVOKED_HEADER) + revoked.keys.sorted().map { "k $it" } + revoked.hosts.sorted().map { "h $it" } + END)
                .joinToString("\n", postfix = "\n")

        /** Parses [formatRevocations]'s output as strictly as [parse]; anything else throws [java.io.IOException]. */
        internal fun parseRevocations(bytes: ByteArray): Revocations {
            val text = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (error: java.nio.charset.CharacterCodingException) {
                throw java.io.IOException("plain-http revocations: not UTF-8", error)
            }
            val lines = text.split('\n')
            if (lines.size < 3 || lines.first() != REVOKED_HEADER) throw java.io.IOException("plain-http revocations: bad header")
            if (lines.last() != "" || lines[lines.size - 2] != END) throw java.io.IOException("plain-http revocations: truncated")
            val keys = HashSet<String>()
            val hosts = HashSet<String>()
            for (line in lines.subList(1, lines.size - 2)) {
                val entry = line.drop(2)
                when {
                    line.startsWith("k ") && isCanonical(entry) -> keys.add(entry)
                    // A host is canonical when it is the host of an entry with a canonical port.
                    line.startsWith("h ") && entry.isNotEmpty() && isCanonical("$entry:80") -> hosts.add(entry)
                    else -> throw java.io.IOException("plain-http revocations: bad entry")
                }
            }
            return Revocations(keys, hosts)
        }

        /**
         * Kept in its own file (the [VerifiedAtomicFile]: a damaged SharedPreferences XML reads as empty on Android 16,
         * which would fail open; a write is synced and read back before it counts), next to the credentials in the
         * no-backup folder.
         */
        fun inFile(context: Context, scope: CoroutineScope, savedServerUrl: suspend () -> String?): PlainHttpConsent =
            inFile(scope, java.io.File(context.noBackupFilesDir, FILE), savedServerUrl)

        internal fun inFile(
            scope: CoroutineScope, path: java.io.File, savedServerUrl: suspend () -> String?,
            file: VerifiedAtomicFile = VerifiedAtomicFile(path),
            revocationFile: VerifiedAtomicFile = VerifiedAtomicFile(java.io.File(path.parentFile, REVOKED_FILE)),
        ): PlainHttpConsent = PlainHttpConsent(scope,
            load = {
                val set = withContext(Dispatchers.IO) {
                    // Not written yet: null (migrate). Any other read or parse failure throws: unavailable.
                    file.read()?.let { parse(it) }
                }
                stored(set, if (set == null) savedServerUrl() else null)
            },
            save = { value -> withContext(Dispatchers.IO) { file.write(format(value).toByteArray(Charsets.UTF_8)) } },
            loadRevocations = {
                withContext(Dispatchers.IO) { revocationFile.read()?.let { parseRevocations(it) } ?: Revocations.NONE }
            },
            saveRevocations = { value -> withContext(Dispatchers.IO) { revocationFile.write(formatRevocations(value).toByteArray(Charsets.UTF_8)) } },
        )
    }
}
