package dev.coda.core.library

import dev.coda.core.playback.AlbumSequenceResolver
import dev.coda.core.playback.QueueEntry
import dev.coda.core.subsonic.Song
import kotlin.random.Random

/** Swift album identity semantics; BF-2 presents only runs of ≥3 as album groups. */
object TrackListPresentation {
    data class QueueGroup(val startIndex: Int, val entries: List<QueueEntry>) {
        val id get() = entries.first().id
        val isStandalone get() = entries.size == 1
        /** All occurrences belong to the visible group, including played tracks. */
        fun removableRange(): IntRange = startIndex until startIndex + entries.size
    }
    data class PlaylistGroup(val startIndex: Int, val songs: List<Song>) {
        val isStandalone get() = songs.size == 1
    }
    private fun presentationRuns(albumIds: List<String?>) = AlbumSequenceResolver.groups(albumIds).flatMap { run ->
        if (run.range.count() >= 3) listOf(run)
        else run.range.map { dev.coda.core.playback.AlbumSequenceGroup(it..it, run.albumId) }
    }

    fun canShufflePlaylist(groups: List<PlaylistGroup>): Boolean = groups.size >= 2

    fun playlistGroups(songs: List<Song>): List<PlaylistGroup> =
        presentationRuns(songs.map { it.albumId }).map {
            PlaylistGroup(it.range.first, songs.slice(it.range))
        }

    /** Shuffle occurrences in group units; preserve the supplied order inside each album run. */
    fun shuffledPlaylist(songs: List<Song>, random: Random = Random.Default): List<Song> =
        shuffledGroups(songs, { it.albumId }, random)

    fun <T> shuffledGroups(items: List<T>, albumId: (T) -> String?, random: Random = Random.Default): List<T> =
        presentationRuns(items.map(albumId)).shuffled(random).flatMap { items.slice(it.range) }

    fun queueGroups(entries: List<QueueEntry>): List<QueueGroup> =
        presentationRuns(entries.map { it.albumId }).map {
            QueueGroup(it.range.first, entries.slice(it.range))
        }
}
