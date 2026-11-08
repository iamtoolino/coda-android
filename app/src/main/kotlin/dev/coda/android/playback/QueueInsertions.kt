package dev.coda.android.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import java.util.UUID

/**
 * Tracked queue inserts (AL-3 Undo). Every inserted item carries its own entry id in its metadata extras, so Undo
 * removes exactly those queue entries: not "the songs with these IDs" (duplicates, a replaced queue) and not an index
 * recomputed from the current queue (playback advanced, empty queue).
 */
internal object QueueInsertions {
    const val ENTRY_ID = "queueEntryId"

    fun entryId(item: MediaItem): String? = item.mediaMetadata.extras?.getString(ENTRY_ID)

    private fun tagged(item: MediaItem, id: String): MediaItem = item.buildUpon().setMediaMetadata(
        item.mediaMetadata.buildUpon().setExtras(Bundle(item.mediaMetadata.extras ?: Bundle()).apply { putString(ENTRY_ID, id) }).build()).build()

    /** Inserts [items] after the current item ([next]) or at the end, and returns their entry ids. */
    fun insert(player: Player, items: List<MediaItem>, next: Boolean): List<String> {
        val ids = items.map { UUID.randomUUID().toString() }
        val tagged = items.mapIndexed { i, item -> tagged(item, ids[i]) }
        val count = player.mediaItemCount
        if (next) player.addMediaItems((player.currentMediaItemIndex + 1).coerceIn(0, count), tagged) else player.addMediaItems(tagged)
        return ids
    }

    /** Queue positions to remove for [ids], from the given entry ids of the current queue; highest range first. */
    fun removalRanges(queue: List<String?>, ids: Set<String>): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var start = -1
        for (i in queue.indices + queue.size) {
            val hit = i < queue.size && queue[i] != null && queue[i] in ids
            if (hit && start < 0) start = i
            if (!hit && start >= 0) { ranges += start until i; start = -1 }
        }
        return ranges.reversed()
    }

    /** Removes the entries still in the queue that were inserted by [insert]; whatever is gone stays gone. */
    fun undo(player: Player, ids: List<String>) {
        if (ids.isEmpty()) return
        val queue = (0 until player.mediaItemCount).map { entryId(player.getMediaItemAt(it)) }
        removalRanges(queue, ids.toSet()).forEach { player.removeMediaItems(it.first, it.last + 1) }
    }
}
