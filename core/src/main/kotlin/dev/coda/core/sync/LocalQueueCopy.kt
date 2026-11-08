package dev.coda.core.sync

import dev.coda.core.playback.PlaybackController
import dev.coda.core.playback.QueueEntry
import dev.coda.core.subsonic.PlayQueue
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Our own queue as the player holds it (or held it): what "resume" plays. */
data class OwnQueue(val entries: List<QueueEntry>, val currentIndex: Int, val positionMs: Long)

/**
 * A local copy of our last saved queue (queue, index, position). The server keeps one saved
 * queue and forgets ours once another device saves; this copy is what the empty voice request and Android's
 * playback resumption play, also offline. Written where the server queue is saved ([QueueHandoffCoordinator]).
 * Library metadata only: no URLs, credentials or stream locations.
 */
@Serializable
data class LocalQueueCopy(
    val entries: List<Entry>, val currentIndex: Int, val positionMs: Long,
    /**
     * The server's `changed` (server clock) of the last server queue the phone had seen when this copy was written. Only ever
     * compared with another server `changed`, never with the phone's clock. Missing in older files: unknown.
     */
    val serverChanged: String? = null,
) {
    @Serializable
    data class Entry(
        val songId: String, val title: String = "", val artist: String = "", val album: String = "",
        val albumId: String? = null, val coverArtId: String? = null, val trackNumber: Int? = null,
        val discNumber: Int? = null, val durationMs: Long = 0, val albumRating: Int? = null,
        val formatName: String? = null, val bitRate: Int? = null,
        val trackGain: Double? = null, val albumGain: Double? = null, val trackPeak: Double? = null, val albumPeak: Double? = null,
        val albumContinuationId: String? = null, val year: Int? = null, val albumArtist: String? = null,
    ) {
        fun toQueueEntry() = QueueEntry(songId, title = title, artist = artist, album = album, albumId = albumId,
            coverArtId = coverArtId, trackNumber = trackNumber, discNumber = discNumber, durationMs = durationMs,
            albumRating = albumRating, formatName = formatName, bitRate = bitRate,
            replayGain = if (listOf(trackGain, albumGain, trackPeak, albumPeak).all { it == null }) null
                else dev.coda.core.playback.ReplayGainMetadata(trackGain, albumGain, trackPeak, albumPeak),
            albumContinuationId = albumContinuationId, year = year, albumArtist = albumArtist)

        companion object {
            fun of(e: QueueEntry) = Entry(e.songId, e.title, e.artist, e.album, e.albumId, e.coverArtId, e.trackNumber,
                e.discNumber, e.durationMs, e.albumRating, e.formatName, e.bitRate, e.replayGain?.trackGain,
                e.replayGain?.albumGain, e.replayGain?.trackPeak, e.replayGain?.albumPeak, e.albumContinuationId, e.year, e.albumArtist)
        }
    }

    val isEmpty: Boolean get() = entries.isEmpty()

    /**
     * After a restart, is this copy newer than the server's [server] queue? Only server values decide, by equality,
     * never the phone's clock (`changed` is server time): the server's `changed` must be exactly the one the phone knew when it
     * wrote this copy ([serverChanged]: a load, or the read-back after our own save), so nobody saved since; `changedBy` must be
     * this device as an extra guard; and the copy must differ (offline edits, position). Otherwise false: today's handoff path.
     */
    fun isNewerThan(server: PlayQueue?, clientName: String): Boolean {
        if (isEmpty || server == null || server.entry.isEmpty()) return false
        if (server.changed.isNullOrEmpty() || server.changed != serverChanged) return false
        if (!server.changedBy.equals(clientName, ignoreCase = true)) return false
        return !(entries.map { it.songId } == server.entry.map { it.id } && currentIndex == server.resolvedCurrentIndex &&
            positionMs == (server.position ?: 0).coerceAtLeast(0))
    }

    /** The queue to resume, or null when there is nothing to resume. A stale index is clamped. */
    fun toOwnQueue(): OwnQueue? = entries.takeIf { it.isNotEmpty() }?.let { list ->
        OwnQueue(list.map(Entry::toQueueEntry), currentIndex.coerceIn(list.indices), positionMs.coerceAtLeast(0))
    }

    companion object {
        val EMPTY = LocalQueueCopy(emptyList(), 0, 0)

        fun of(queue: OwnQueue) = LocalQueueCopy(queue.entries.map(Entry::of),
            queue.currentIndex.coerceIn(0, (queue.entries.size - 1).coerceAtLeast(0)), queue.positionMs.coerceAtLeast(0))

        /** Same rules as [QueueHandoffSnapshot.capture]: no current item means an empty queue. */
        fun capture(player: PlaybackController): LocalQueueCopy {
            val index = player.currentIndex
            return if (player.queue.isEmpty() || index == null) EMPTY
            else of(OwnQueue(player.queue, index.coerceIn(player.queue.indices), player.positionMs.coerceAtLeast(0)))
        }
    }
}

/**
 * One account's local queue copy in one JSON file (the caller picks a per-account path in app-private, no-backup
 * storage). Blocking file I/O: call from an I/O dispatcher. A write replaces the file atomically through [io] (the app
 * passes Android's `AtomicFile`); an empty copy deletes it, and [delete] removes it explicitly (sign-out).
 */
class LocalQueueStore(val file: File, private val io: FileIo = FileIo.Default) {
    /** The byte-level file operations, so the app can use `AtomicFile` (fsync, backup recovery) and tests the JVM version. */
    interface FileIo {
        fun exists(file: File): Boolean
        fun read(file: File): ByteArray
        /** Replace the file's content atomically: a reader sees the old or the new content, never a mix. */
        fun write(file: File, bytes: ByteArray)
        fun delete(file: File)

        object Default : FileIo {
            override fun exists(file: File) = file.isFile
            override fun read(file: File) = file.readBytes()
            override fun write(file: File, bytes: ByteArray) {
                val temp = File(file.parentFile, file.name + ".tmp")
                java.io.FileOutputStream(temp).use { it.write(bytes); it.flush(); it.fd.sync() }
                java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            override fun delete(file: File) { file.delete(); File(file.parentFile, file.name + ".tmp").delete() }
        }
    }

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun read(): LocalQueueCopy? = synchronized(LOCK) {
        if (!io.exists(file)) return null
        try { json.decodeFromString(LocalQueueCopy.serializer(), io.read(file).decodeToString()).takeUnless { it.isEmpty } }
        catch (_: Exception) { null } // A damaged copy is no copy.
    }

    @Volatile private var retired = false

    fun write(copy: LocalQueueCopy) = synchronized(LOCK) {
        if (retired) return // sign-out fence: a late writer (an exit save, a worker that was mid-write) cannot bring the copy back
        if (copy.isEmpty) { io.delete(file); return }
        file.parentFile?.mkdirs()
        io.write(file, json.encodeToString(LocalQueueCopy.serializer(), copy).encodeToByteArray())
    }

    /** Remove the copy (sign-out): the next [read] finds none. */
    fun delete() = synchronized(LOCK) { io.delete(file) }

    /**
     * Sign-out: wait for a write in flight (they share [LOCK]), delete the copy and refuse every later [write] of this
     * instance. A later sign-in uses a new instance.
     */
    fun retire() = synchronized(LOCK) { retired = true; io.delete(file) }

    private companion object { val LOCK = Any() }
}
