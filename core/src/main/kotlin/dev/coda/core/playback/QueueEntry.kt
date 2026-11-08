package dev.coda.core.playback

import java.util.UUID

/** A queue is scoped to one server session; IDs and sources never contain authenticated URLs. */
data class QueueEntry(
    val songId: String,
    val id: UUID = UUID.randomUUID(),
    val source: TrackSource = TrackSource.Remote(songId, StreamFormat.RAW),
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumId: String? = null,
    val coverArtId: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val durationMs: Long = 0,
    val albumRating: Int? = null,
    val formatName: String? = null,
    /** The original's bitrate in kbit/s (`getSong` bitRate): picks the cache format. */
    val bitRate: Int? = null,
    val replayGain: ReplayGainMetadata? = null,
    val albumContinuationId: String? = null,
    val year: Int? = null,
    val albumArtist: String? = null,
    /** The token of the Auto start request that installed this entry (QueueHandoffCoordinator.expectStart); null for every other entry. Never persisted. */
    val startToken: Long? = null,
)

/** Swift reorderedQueueIDs: preserve queue order within a multi-selection. Null means no move. */
fun reorderedQueueIds(currentIds: List<UUID>, movingIds: List<UUID>, before: UUID?): List<UUID>? {
    val moving = movingIds.toSet()
    if (moving.isEmpty() || moving.size != movingIds.size ||
        !currentIds.containsAll(moving) || before in moving
    ) return null
    val orderedMoving = currentIds.filter { it in moving }
    val reordered = currentIds.filterNot { it in moving }.toMutableList()
    val insertionIndex = if (before == null) reordered.size else reordered.indexOf(before)
    if (insertionIndex < 0) return null
    reordered.addAll(insertionIndex, orderedMoving)
    return reordered.takeUnless { it == currentIds }
}
