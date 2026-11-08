package dev.coda.core.cache

import dev.coda.core.playback.QueueEntry
import dev.coda.core.playback.ReplayGainMetadata
import dev.coda.core.playback.StreamFormat
import dev.coda.core.subsonic.LossyReplayGainSerializer
import dev.coda.core.subsonic.Song
import kotlinx.serialization.Serializable

/**
 * The song's display metadata, so music that was only played can be listed offline, and its ReplayGain, so offline
 * playback never depends on album pages or catalogue rows. Every field is optional: rows written before it still decode.
 */
@Serializable
data class TrackMeta(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumId: String? = null,
    val coverArtId: String? = null,
    val durationSeconds: Int? = null,
    val track: Int? = null,
    val disc: Int? = null,
    val year: Int? = null,
    val albumArtist: String? = null,
    @Serializable(with = LossyReplayGainSerializer::class)
    val replayGain: ReplayGainMetadata? = null,
) {
    /** This newer metadata over [old]: one without gain (an entry built without it) keeps the gain stored before. */
    fun over(old: TrackMeta?): TrackMeta = if (replayGain != null || old?.replayGain == null) this else copy(replayGain = old.replayGain)

    companion object {
        fun from(song: Song) = TrackMeta(song.title, song.artistName, song.album.orEmpty(), song.albumId, song.albumArtworkId,
            song.duration, song.track, song.discNumber, song.year, song.albumArtistName, song.replayGain)
        fun from(entry: QueueEntry) = TrackMeta(entry.title, entry.artist, entry.album, entry.albumId, entry.coverArtId,
            (entry.durationMs / 1000).toInt().takeIf { it > 0 }, entry.trackNumber, entry.discNumber, entry.year, entry.albumArtist,
            entry.replayGain)
    }
}

fun QueueEntry.songFormat() = SongFormat(formatName?.lowercase(), bitRate, (durationMs / 1000).toInt().takeIf { it > 0 })

/** One track of an added album, playlist, track or queue. */
@Serializable
data class KeptTrack(val id: String, val format: SongFormat = SongFormat(), val meta: TrackMeta? = null) {
    companion object {
        fun from(song: Song) = KeptTrack(song.id, SongFormat(song.suffix?.lowercase(), song.bitRate, song.duration, song.size), TrackMeta.from(song))
        fun from(entry: QueueEntry) = KeptTrack(entry.songId, entry.songFormat(), TrackMeta.from(entry))
    }
}

/**
 * An added set ("Keep offline" on an album, playlist or track). Keys: `album/<id>`, `playlist/<id>`, `track/<id>`; sets
 * `queue/<hash>` from the former "Keep queue offline" are no longer made and simply expire ("Load for offline" is a
 * one-shot read-ahead). [addedAt] is the last renewal, in wall-clock ms. The queue download reuses this
 * shape (key [DownloadLines.QUEUE_KEY]) without being an added set.
 */
@Serializable
data class KeptItem(val key: String, val tracks: List<KeptTrack>, val title: String? = null, val addedAt: Long = 0,
    /** The album's or playlist's own cover id (the page header's), kept in the cover store with the tracks' album covers. */
    val coverArt: String? = null) {
    val trackIds get() = tracks.map { it.id }.distinct()
}

/** The outcome of an add, for the UI (CA-2 "Add result"). */
sealed interface AddResult {
    /**
     * Renewed and downloading. [format] is the headline choice now (each track still follows mode and network as it
     * downloads); [bytes] are planned for Wi-Fi. [waitsForWifi]: not on proper Wi-Fi now (mobile data, a hotspot or
     * offline), so the downloads stay pending and start by themselves on Wi-Fi.
     */
    data class Started(val format: StreamFormat, val bytes: Long, val waitsForWifi: Boolean = false) : AddResult
    /**
     * "Make room?": [bytes] would be deleted in the deletion order; [items] are the keys of the kept sets that
     * leave whole and [titles] their names, [otherTracks] the rest. [victims] are exactly the files
     * presented: confirming passes this result back, and the add deletes only files among them, at most [bytes], and
     * only the sets listed, or asks again.
     */
    data class NeedsRoom(val bytes: Long, val needed: Long, val items: List<String>, val otherTracks: Int,
        val victims: List<Evictable> = emptyList(), val titles: List<String> = emptyList()) : AddResult
    /** Doesn't fit even after deleting everything else that may go; nothing is downloaded. [lineBytes]: Keep free space. */
    data class TooBig(val missingBytes: Long, val lineBytes: Long = 0) : AddResult
}

/** Observable state of an album, playlist or track (CA-2 "Item status"). */
data class ItemStatus(
    val cachedTracks: Int,
    val totalTracks: Int,
    val bytes: Long,
    /**
     * Wall-clock ms: the earliest keep-until among its cached tracks; moves later while the server is unreachable. Null
     * when nothing is cached or when that keep-until has no date ([untilSpaceRunsLow]).
     */
    val availableUntil: Long?,
    val added: Boolean,
    val downloading: Boolean = false,
    /** Tracks of an added set still to download. Tracks that aged out or were deleted don't count until it is added again. */
    val pendingTracks: Int = 0,
    /** Its cached tracks have no date ("As long as possible"); they stay until space runs low. */
    val untilSpaceRunsLow: Boolean = false,
) {
    val complete get() = totalTracks > 0 && cachedTracks == totalTracks

    /**
     * What the user sees as "kept offline". An added set with tracks missing (aged out, evicted, cancelled) and nothing
     * downloading looks not kept: switch off, no line. The stored mark stays (CA-3 deletion rules are unchanged); keeping again
     * adds the whole set, renewing the tracks on the phone and downloading the missing ones. Progress of a running download shows.
     */
    val looksKept get() = added && (pendingTracks > 0 || complete)
}

/**
 * Status card and Settings row: music on this phone, free space as the user sees it, and the Keep free space line.
 * The bar and legend in one order, Artwork, Music (with the part Coda will clean up),
 * Available to Coda, Keep free, Other apps and files; the four states are normal, tight, over the line with music to clean
 * up (state 3) and over the line with nothing of Coda's left to remove (state 4, [nothingToCleanUp]).
 */
data class CacheSummary(val totalBytes: Long, val freeBytes: Long, val lineBytes: Long,
    /** The volume's size, for the storage bar (0: unknown). */
    val capacityBytes: Long = 0,
    /** The artwork store's part of [totalBytes] (shown as its own figure in Music storage). */
    val artworkBytes: Long = 0,
    /**
     * The music maintenance could remove now ([TrackCache.removableMusicBytes]): not the read-ahead window, the
     * player's files or downloads in progress. Null: all music.
     */
    val removableMusicBytes: Long? = null) {
    /** False for the placeholder before the first measurement (free space unknown): no bar and no legend yet. */
    val measured get() = freeBytes != Long.MAX_VALUE
    val belowLine get() = freeBytes < lineBytes
    private val free get() = freeBytes.coerceAtLeast(0)
    /** The music part of [totalBytes] (the Music row; the headline is music plus artwork). */
    val musicBytes get() = (totalBytes - artworkBytes).coerceAtLeast(0)
    /** What Coda may still fill, free space minus Keep free space, never below 0. */
    val availableBytes get() = (freeBytes - lineBytes).coerceAtLeast(0)
    /**
     * The bar's grey part and the "Other apps and files" row: the volume minus Coda and free space, never below 0
     * (0 when the volume's size is unknown: no grey part and no row).
     */
    val otherBytes get() = if (capacityBytes <= 0) 0L else (capacityBytes - totalBytes - free).coerceAtLeast(0)
    /** How far free space is under Keep free, 0 at or above the line. */
    val deficitBytes get() = (lineBytes - free).coerceAtLeast(0)
    /**
     * What Coda can give back towards the line: its removable music only (artwork, the read-ahead window and the
     * player's files stay), at most the deficit.
     */
    val cleanUpBytes get() = minOf(deficitBytes, musicBytes, removableMusicBytes ?: musicBytes).coerceAtLeast(0)
    /** State 4: under the line with no music of Coda's left to remove (none, or only songs in use). */
    val nothingToCleanUp get() = belowLine && cleanUpBytes == 0L
    /** The Keep free part as drawn: only as wide as the free space really left (the rest is taken by others). */
    val keepFreeShownBytes get() = minOf(free, lineBytes.coerceAtLeast(0))

    /** The storage bar's parts in order, zero-sized ones left out. Their sum is the bar's capacity. */
    val segments: List<Pair<StorageSegment, Long>> get() = listOf(
        StorageSegment.ARTWORK to artworkBytes, StorageSegment.MUSIC to musicBytes - cleanUpBytes, StorageSegment.CLEAN_UP to cleanUpBytes,
        StorageSegment.AVAILABLE to availableBytes, StorageSegment.KEEP_FREE to keepFreeShownBytes, StorageSegment.OTHER to otherBytes,
    ).filter { it.second > 0 }

    /** Keep free as set: whole gigabytes, as the slider shows them. */
    private val lineText get() = if (lineBytes % CachePolicy.GB == 0L) "${lineBytes / CachePolicy.GB} GB" else size(lineBytes)
    val musicText get() = if (musicBytes == 0L) NONE else size(musicBytes)
    /** Under 1 GB there is no room for more music. */
    val availableText get() = if (availableBytes < CachePolicy.GB) NONE else size(availableBytes)
    /** Over the line: what is really left of it ("5.0 GB of 8 GB"). */
    val keepFreeText get() = if (belowLine) "${size(free)} of $lineText" else lineText
    /** Over the line, the caution under the rows (state 3 or 4); null otherwise. */
    val caution: String? get() = when {
        !belowLine -> null
        nothingToCleanUp -> "Only ${size(free)} free, under your $lineText Keep free, and Coda has no music left to remove. " +
            "Songs stream; only the next few are saved while you listen."
        else -> "Only ${size(free)} free, under your $lineText Keep free. Coda will clean up about ${size(cleanUpBytes)} of music."
    }
    /** State 4: the dim line under the caution. */
    val cautionHint: String? get() = if (nothingToCleanUp) "To keep music offline, free up space on your phone or lower Keep free." else null

    /** TalkBack: the status card as one stop: the legend in bar order, then the caution lines (the mark is not described). */
    val spoken: String get() {
        fun spokenValue(text: String) = if (text == NONE) "none" else text
        val rows = listOfNotNull("artwork ${size(artworkBytes)}", "music ${spokenValue(musicText)}",
            "available to Coda ${spokenValue(availableText)}", "Keep free $keepFreeText",
            if (otherBytes > 0) "${OTHER.lowercase()} ${size(otherBytes)}" else null)
        return listOfNotNull("Coda uses ${size(totalBytes)}: ${rows.joinToString(", ")}.", caution, cautionHint).joinToString(" ")
    }

    companion object {
        const val NONE = "None"
        const val OTHER = "Other apps and files"

        fun size(bytes: Long): String {
            val gb = bytes / 1e9
            return when {
                gb >= 10 -> "${Math.round(gb)} GB"
                gb >= 1 -> String.format(java.util.Locale.ROOT, "%.1f GB", gb)
                else -> "${(bytes + 999_999) / 1_000_000} MB"
            }
        }
    }
}

/** The storage bar's parts, in bar and legend order. */
enum class StorageSegment { ARTWORK, MUSIC, CLEAN_UP, AVAILABLE, KEEP_FREE, OTHER }

/**
 * Kept sets maintenance removed for space. [snackbarAt] is when the snackbar was last shown (delivery); the
 * record itself stays until the user taps OK on Music storage (dismissal). Wall-clock ms, as shown to the user.
 */
@Serializable
data class RemovedForSpace(val at: Long, val titles: List<String>, val bytes: Long, val snackbarAt: Long? = null) {
    /** The snackbar shows when it never showed, or when a removal is newer than the last showing and that was ≥ 24 h ago. */
    fun snackbarDue(now: Long) = snackbarAt == null || (at > snackbarAt && now - snackbarAt >= CacheRules.DAY_MS)
}
