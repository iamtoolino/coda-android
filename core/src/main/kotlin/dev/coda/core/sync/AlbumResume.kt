package dev.coda.core.sync

import dev.coda.core.subsonic.*
import dev.coda.core.playback.AlbumSequenceResolver
import dev.coda.core.playback.CompletedPlayback
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import java.util.Locale

private class AlbumResumeMarker(val protocolName: String, val protocolVersion: Int, val resumeSongId: String)

data class AlbumResumeItem(val album: Album, val anchorSongId: String,
    val resumeSongId: String, val changed: String)

/** Port of AlbumResumeCoordinator.swift isEligibleAlbumResumeSong. */
fun isEligibleAlbumResumeSong(song: Song): Boolean {
    val type = AlbumSequenceResolver.normalizedAlbumId(song.type)?.lowercase(Locale.ROOT)
    val mediaType = AlbumSequenceResolver.normalizedAlbumId(song.mediaType)?.lowercase(Locale.ROOT)
    return (type.isNullOrEmpty() || type == "music") &&
        (mediaType.isNullOrEmpty() || mediaType == "song") && AlbumSequenceResolver.normalizedAlbumId(song.albumId) != null
}

/** Port of albumResumeRecordingAlbumID; use the occurrence's frozen context. */
fun albumResumeRecordingAlbumId(completion: CompletedPlayback): String? {
    val context = completion.albumSequence ?: return null
    return context.albumId?.takeIf { (context.isAlbumSequence || context.isExplicitContinuation) && it.isNotEmpty() }
}

/** Diagnostic writer metadata inside the bookmark comment (Swift AlbumResumeWriter); readers ignore it. */
data class AlbumResumeWriter(val client: String?, val platform: String?, val appVersion: String?)

sealed interface AlbumResumeCompletionAction {
    data class Upsert(val anchorSongId: String, val resumeSongId: String) : AlbumResumeCompletionAction
    data class Delete(val anchorSongId: String) : AlbumResumeCompletionAction
    data object None : AlbumResumeCompletionAction
}

private const val COMMENT_BYTE_LIMIT = 255

/**
 * Port of albumResumeComment: sorted-key JSON (protocol, protocolVersion, resumeSongId, writer), slashes escaped
 * like Swift's JSONEncoder, at most 255 UTF-8 bytes; the writer is dropped first if it does not fit.
 */
fun albumResumeComment(resumeSongId: String, writer: AlbumResumeWriter? = null): String? {
    val id = resumeSongId.trim()
    if (id.isEmpty()) return null
    for (candidate in listOf(writer, null)) {
        val body = buildJsonObject {
            put("protocol", "album-resume-bookmark")
            put("protocolVersion", 1)
            put("resumeSongId", id)
            if (candidate != null) put("writer", buildJsonObject {
                candidate.appVersion?.let { put("appVersion", it) }
                candidate.client?.let { put("client", it) }
                candidate.platform?.let { put("platform", it) }
            })
        }.toString().replace("/", "\\/")
        if (body.toByteArray(Charsets.UTF_8).size <= COMMENT_BYTE_LIMIT) return body
    }
    return null
}

/** Port of albumResumeCompletionAction: next track after the completed one, or delete after the last. */
fun albumResumeCompletionAction(completion: CompletedPlayback, album: AlbumPage): AlbumResumeCompletionAction {
    if (albumResumeRecordingAlbumId(completion) != album.album.id) return AlbumResumeCompletionAction.None
    val index = album.songs.indexOfFirst { it.id == completion.songId }
    val anchor = album.songs.firstOrNull()?.id?.takeIf { it.isNotEmpty() }
    if (index < 0 || anchor == null) return AlbumResumeCompletionAction.None
    return album.songs.getOrNull(index + 1)?.let { AlbumResumeCompletionAction.Upsert(anchor, it.id) }
        ?: AlbumResumeCompletionAction.Delete(anchor)
}

/**
 * Swift's typed Codable decode: `protocol` and `resumeSongId` must be JSON strings and `protocolVersion` a JSON
 * integer. kotlinx's typed decoder would coerce `"protocolVersion":"1"` (and a numeric `protocol`) and so claim
 * markers Swift rejects; retention must never delete those, so the field types are checked here. Other keys
 * (writer, unknown ones) are ignored, even when malformed.
 */
private fun decodeMarker(comment: String): AlbumResumeMarker? {
    val obj = try { Json.parseToJsonElement(comment) as? JsonObject } catch (_: IllegalArgumentException) { null } ?: return null
    fun string(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val version = (obj["protocolVersion"] as? JsonPrimitive)?.takeIf { !it.isString }
        ?.let { runCatching { it.int }.getOrNull() } ?: return null
    return AlbumResumeMarker(string("protocol") ?: return null, version, string("resumeSongId") ?: return null)
}

/** Read supported v1 markers only; writer metadata is deliberately ignored, even if malformed.
 * Raw timestamp ordering and descending anchor tie-break mirror albumResumeBuckets in Swift.
 * Readback does not reclassify old markers by media type or validate the resume target.
 */
fun albumResumeItems(bookmarks: List<Bookmark>, limit: Int = 20): List<AlbumResumeItem> =
    albumResumeBuckets(bookmarks).take(limit.coerceAtLeast(0)).map { it.item }

/** One album's compatible markers: the representative [item] (newest raw `changed`, then anchor id) and all bookmarks. */
class AlbumResumeBucket(val item: AlbumResumeItem, val bookmarks: List<Bookmark>)

/** Port of albumResumeBuckets: group recognised markers by album, newest first (raw-string order, anchor id tie-break). */
fun albumResumeBuckets(bookmarks: List<Bookmark>): List<AlbumResumeBucket> {
    val owned = bookmarks.mapNotNull { bookmark ->
        val marker = decodeMarker(bookmark.comment ?: return@mapNotNull null) ?: return@mapNotNull null
        if (marker.protocolName != "album-resume-bookmark" || marker.protocolVersion != 1 || marker.resumeSongId.isEmpty())
            return@mapNotNull null
        val song = bookmark.entry ?: return@mapNotNull null
        val albumId = AlbumSequenceResolver.normalizedAlbumId(song.albumId) ?: return@mapNotNull null
        val name = song.album?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        Triple(bookmark, AlbumResumeItem(Album(id = albumId, name = name, artist = song.albumArtistName.takeIf { it.isNotEmpty() },
            artists = song.albumArtists, isCompilation = song.isCompilation, coverArt = song.albumArtworkId,
            year = song.year), song.id, marker.resumeSongId, bookmark.changed ?: bookmark.created.orEmpty()), albumId)
    }
    val newestFirst = compareByDescending<AlbumResumeItem> { it.changed }.thenByDescending { it.anchorSongId }
    return owned.groupBy { it.third }.values.map { group ->
        AlbumResumeBucket(group.map { it.second }.minWith(newestFirst), group.map { it.first })
    }.sortedWith { l, r -> newestFirst.compare(l.item, r.item) }
}

const val ALBUM_RESUME_RETENTION_LIMIT = 20

/**
 * Recent-20 retention: the song ids of every recognised marker of the albums beyond the newest [keep]. Foreign,
 * malformed and unknown-version bookmarks are never candidates. Only call this with a fresh server snapshot.
 */
fun albumResumeRetentionDeletes(bookmarks: List<Bookmark>, keep: Int = ALBUM_RESUME_RETENTION_LIMIT): List<String> =
    retentionDeletesOf(albumResumeBuckets(bookmarks), keep)

/** Same, from buckets already computed (the coordinator groups the snapshot once for shelf and cleanup). */
fun retentionDeletesOf(buckets: List<AlbumResumeBucket>, keep: Int = ALBUM_RESUME_RETENTION_LIMIT): List<String> =
    buckets.drop(keep.coerceAtLeast(0)).flatMap { b -> b.bookmarks.mapNotNull { it.entry?.id } }

/** Where an unfinished album stands (spec 5.3 Continue Listening tile), measured to the start of the resume track. */
data class ResumeProgress(val trackNumber: Int, val trackCount: Int, val disc: Int?, val remainingSeconds: Int,
    val fraction: Float) {
    /** "Disc 1 · 31 min left" on multi-disc albums, "5 of 12 · 28 min left" otherwise. */
    val subtitle: String get() {
        val left = remainingSeconds.coerceAtLeast(0).let { if (it == 0) "Almost done" else "${(it / 60).coerceAtLeast(1)} min left" }
        return (if (disc != null) "Disc $disc" else "$trackNumber of $trackCount") + " · " + left
    }
}

/** Null when [resumeSongId] is not on the album (the album page decides that, never the bookmark). */
fun resumeProgress(songs: List<Song>, resumeSongId: String): ResumeProgress? {
    val index = songs.indexOfFirst { it.id == resumeSongId }
    if (index < 0) return null
    val total = songs.sumOf { it.duration ?: 0 }
    val remaining = songs.drop(index).sumOf { it.duration ?: 0 }
    val multiDisc = dev.coda.core.library.AlbumSections.discCount(songs) > 1
    return ResumeProgress(index + 1, songs.size, if (multiDisc) maxOf(1, songs[index].discNumber ?: 1) else null, remaining,
        if (total > 0) ((total - remaining).toFloat() / total).coerceIn(0f, 1f) else index.toFloat() / songs.size)
}
