package dev.coda.core.scrobble

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A completed play waiting for the server. [playedAtMs] is the original completion time. */
@Serializable
data class PendingScrobble(
    val id: String = UUID.randomUUID().toString(),
    /** Non-reversible account key: entries are only ever sent to the account that played them. */
    val serverKey: String,
    val songId: String,
    val playedAtMs: Long,
)

@Serializable
private data class OutboxFile(val schema: Int = 1, val entries: List<PendingScrobble> = emptyList())

/** Blocking persistence boundary, also used for storage fault injection in coordinator tests. */
interface ScrobbleStore {
    fun peek(serverKey: String): PendingScrobble?
    fun add(entry: PendingScrobble, nowMs: Long)
    fun remove(id: String)
    fun prune(nowMs: Long)
    /** Keeps only [serverKey]'s entries. */
    fun retainOnly(serverKey: String)
}

/**
 * Durable, ordered scrobble submissions in one JSON file, replaced atomically on every change.
 * Blocking file I/O: call off the main thread. A malformed file reads as empty; a read I/O error throws and is retried.
 */
class ScrobbleOutbox(private val file: File) : ScrobbleStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var entries: List<PendingScrobble>? = null

    @Synchronized
    fun all(): List<PendingScrobble> = loaded()

    /** The oldest entry for [serverKey], if any. */
    @Synchronized
    override fun peek(serverKey: String): PendingScrobble? = loaded().firstOrNull { it.serverKey == serverKey }

    @Synchronized
    override fun add(entry: PendingScrobble, nowMs: Long) {
        // A cancelled IO continuation may have committed already. Retrying must not duplicate it.
        if (loaded().none { it.id == entry.id }) save(ScrobblePolicy.prune(loaded() + entry, nowMs))
    }

    @Synchronized
    override fun remove(id: String) {
        val current = loaded()
        if (current.any { it.id == id }) save(current.filterNot { it.id == id })
    }

    /** A sign-in as another account drops every other account's unsent plays. */
    @Synchronized
    override fun retainOnly(serverKey: String) {
        val current = loaded()
        if (current.any { it.serverKey != serverKey }) save(current.filter { it.serverKey == serverKey })
    }

    @Synchronized
    override fun prune(nowMs: Long) {
        val current = loaded()
        val pruned = ScrobblePolicy.prune(current, nowMs)
        if (pruned.size != current.size) save(pruned)
    }

    private fun loaded(): List<PendingScrobble> = entries ?: read().also { entries = it }

    // A missing or malformed file reads as empty. Unreadable storage (an I/O error) is not empty: it throws and stays unloaded,
    // so the next call (the coordinator's storage retry) reads again instead of a later save replacing the unsent plays.
    private fun read(): List<PendingScrobble> {
        if (!file.isFile) return emptyList()
        val text = file.readText()
        return try { json.decodeFromString<OutboxFile>(text).entries } catch (_: Exception) { emptyList() }
    }

    private fun save(value: List<PendingScrobble>) {
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { out ->
            out.write(json.encodeToString(OutboxFile.serializer(), OutboxFile(entries = value)).toByteArray())
            out.fd.sync()
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        entries = value
    }
}
