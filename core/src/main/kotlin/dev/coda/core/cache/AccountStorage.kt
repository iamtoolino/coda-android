package dev.coda.core.cache

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The phone keeps the offline storage of one account (server + user, [TrackCache.serverKey]).
 *
 * - A verified sign-in as the same account reconnects to its own folders: nothing is deleted or downloaded again.
 * - A verified sign-in as another account first deletes everything account-bound of every other key (music cache with
 *   its parts, sets, pending state and waveforms; `library.db` with WAL/SHM (also the library pages); own-queue copies; the picture
 *   cache; plus the in-process stores via [InProcess]). Coda settings, recent servers, https history, plain-http
 *   consents and developer switches are not here and are never touched.
 * - Leftovers of other keys that already exist (from before OF-1e) go in the same pass.
 * - Sign-out and a failed or cancelled sign-in never call this.
 *
 * Crash safety: a marker naming the account to keep is written before anything is deleted and removed only after the
 * pass completed; [resume] finishes an interrupted pass at the next start (before the session is restored). An
 * unreadable marker deletes nothing (fail closed for data; the next sign-in finds the leftovers on disk anyway).
 *
 * Only names this app writes are touched: `<dir>/<16 hex>` folders, `queue/<16 hex>.json*` and `cache/artwork`.
 * Blocking file work runs on [io]; calls are serialised.
 */
class AccountStorage(
    private val noBackup: File,
    private val cache: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String) -> Unit = {},
) {
    /**
     * The account that stays: [key] is [TrackCache.serverKey], [scrobbleKey] the scrobble outbox's own account key.
     * [sameAs] are further keys that are this same account and are never deleted: the same login typed in another
     * letter case. [folded] is the key with the user name in
     * lower case; two logins with the same [folded] are one account (Navidrome ignores case). Hashes only.
     */
    data class Keep(val key: String, val scrobbleKey: String, val sameAs: Set<String> = emptySet(), val folded: String = key)

    /** Account-bound state held by objects in this process (detach caches of other keys, scrobble outbox, playback colour). */
    fun interface InProcess { suspend fun keepOnly(keep: Keep) }

    /** What a pass did (tests and the log; no names or addresses). */
    data class Result(val accountChanged: Boolean, val deleted: List<String>, val complete: Boolean)

    private val mutex = Mutex()
    private val marker get() = File(noBackup, MARKER)
    private val lastKeyFile get() = File(noBackup, LAST_KEY)

    /** After a verified sign-in as [keep] (before the session is published). Never throws for a file problem. */
    suspend fun signedIn(keep: Keep, inProcess: InProcess = InProcess {}): Result = mutex.withLock {
        withContext(io) {
            val last = runCatching { lastKeyFile.takeIf { it.isFile }?.readText()?.lines()?.map(String::trim) }.getOrNull().orEmpty()
            val previous = last.getOrNull(0)?.takeIf { KEY.matches(it) }
            val previousFolded = last.getOrNull(1)?.takeIf { KEY.matches(it) }
            val previousKnown = keys(last.getOrNull(2))
            val sameAccount = previous != null && (previous == keep.key || previous in keep.sameAs ||
                previousFolded == keep.folded || keep.key in previousKnown)
            // Invariant (Sol P1): every key ever recognised as this account stays in the remembered set, so no sequence
            // of same-account reconnects (in any letter case) can turn an earlier spelling's folder into a deletion target.
            val own = keep.sameAs + keep.key + (if (sameAccount) previousKnown + previous!! else emptySet())
            val accountChanged = previous != null && !sameAccount
            val legacy = keep.sameAs.filter { it != keep.key }
            if (legacy.any { name -> KEYED_FOLDERS.any { File(noBackup, "$it/$name").exists() } })
                log("OF-1e: folders of this account under an older spelling or letter case are kept")
            val changed = accountChanged || otherKeyEntries(own).isNotEmpty()
            if (!changed) {
                // Never leave a marker of an earlier pass: it would name another account.
                runCatching { marker.delete() }
                writeLastKey(keep, own)
                return@withContext Result(false, emptyList(), true)
            }
            // Before the first deletion, so an interrupted pass resumes with the same account.
            // The picture cache only goes for a real account change.
            runCatching { writeAtomically(marker, "${keep.key}\n${keep.scrobbleKey}\n${own.joinToString(",")}\n${if (accountChanged) 1 else 0}\n${keep.folded}\n") }
                .onFailure { log("OF-1e: wipe marker not written: ${it.javaClass.simpleName}") }
            wipe(keep, own, accountChanged, inProcess)
        }
    }

    /**
     * At start, before the session is restored: finish a pass that was interrupted. Null when none was pending.
     * [storedKey] is the account of the stored login: a marker that names another account is dropped without
     * deleting anything, as is any marker when no login is stored.
     */
    suspend fun resume(storedKey: String?, inProcess: InProcess = InProcess {}): Result? = mutex.withLock {
        withContext(io) {
            if (!marker.isFile) return@withContext null
            val lines = runCatching { marker.readText().lines().map(String::trim) }.getOrNull().orEmpty()
            val key = lines.getOrNull(0)?.takeIf { KEY.matches(it) }
            val scrobble = lines.getOrNull(1)?.takeIf { it.isNotEmpty() && SCROBBLE.matches(it) }
            val own = keys(lines.getOrNull(2)) + listOfNotNull(key)
            val folded = lines.getOrNull(4)?.takeIf { KEY.matches(it) } ?: key
            if (key == null || scrobble == null || folded == null) {
                // Unknown account to keep: delete nothing.
                log("OF-1e: unreadable wipe marker dropped")
                marker.delete()
                return@withContext Result(false, emptyList(), false)
            }
            if (storedKey == null || storedKey !in own) {
                // The signed-in account is not the one this pass was for: its data must stay.
                log("OF-1e: stale wipe marker dropped")
                marker.delete()
                return@withContext Result(false, emptyList(), false)
            }
            wipe(Keep(key, scrobble, own - key, folded), own, lines.getOrNull(3) == "1", inProcess)
        }
    }

    /** True while a pass is pending (an interrupted one, until [resume] completed it). */
    fun pending(): Boolean = marker.isFile

    private suspend fun wipe(keep: Keep, own: Set<String>, pictures: Boolean, inProcess: InProcess): Result {
        val deleted = mutableListOf<String>()
        var complete = true
        try { inProcess.keepOnly(keep.copy(sameAs = own - keep.key)) } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            complete = false
            log("OF-1e: in-process stores not cleared: ${error.javaClass.simpleName}")
        }
        val picture = if (pictures) listOf(File(cache, PICTURE_CACHE)).filter { it.exists() } else emptyList()
        for (entry in otherKeyEntries(own) + picture) {
            val ok = if (entry.isDirectory) entry.deleteRecursively() else entry.delete() || !entry.exists()
            if (ok) deleted += entry.relativeName() else { complete = false; log("OF-1e: could not delete ${entry.relativeName()}") }
        }
        if (complete) {
            writeLastKey(keep, own)
            marker.delete()
        }
        return Result(true, deleted, complete)
    }

    /** Folders and files of keys other than [key] (only names this app writes). */
    private fun otherKeyEntries(own: Set<String>): List<File> {
        val folders = KEYED_FOLDERS.flatMap { name ->
            File(noBackup, name).listFiles().orEmpty().filter { it.isDirectory && KEY.matches(it.name) && it.name !in own }
        }
        val queues = File(noBackup, QUEUE).listFiles().orEmpty().filter { file ->
            val owner = QUEUE_FILE.matchEntire(file.name)?.groupValues?.get(1)
            owner != null && owner !in own
        }
        return folders + queues
    }

    private fun keys(line: String?): Set<String> = line.orEmpty().split(",").filter { KEY.matches(it) }.toSet()

    /** Key, folded key, every key known as this account ([own]). */
    private fun writeLastKey(keep: Keep, own: Set<String>) {
        val text = "${keep.key}\n${keep.folded}\n${own.sorted().joinToString(",")}"
        runCatching { if (lastKeyFile.takeIf { it.isFile }?.readText() != text) writeAtomically(lastKeyFile, text) }
            .onFailure { log("OF-1e: account key not saved: ${it.javaClass.simpleName}") }
    }

    private fun writeAtomically(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { out -> out.write(text.toByteArray()); out.fd.sync() }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun File.relativeName(): String = (parentFile?.name?.let { "$it/" } ?: "") + name

    companion object {
        /** `no_backup/<name>/<key>/`: the music cache (with parts, sets, pending state, waveforms), `library.db` (with the library pages since DB-2). */
        val KEYED_FOLDERS = listOf("music", "library")
        const val QUEUE = "queue"
        /** `cache/artwork`: the picture cache, whose file names hash the server + user (CoverArtFetcher). */
        const val PICTURE_CACHE = "artwork"
        const val MARKER = "account-wipe"
        const val LAST_KEY = "account-key"
        private val KEY = Regex("[0-9a-f]{16}")
        private val SCROBBLE = Regex("[0-9a-f]{1,64}")
        /** Queue copy plus AtomicFile's `.bak` / `.new` siblings. */
        private val QUEUE_FILE = Regex("([0-9a-f]{16})\\.json(\\.bak|\\.new|\\.tmp)?")
    }
}
