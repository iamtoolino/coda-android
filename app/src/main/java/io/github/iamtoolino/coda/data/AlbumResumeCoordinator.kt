package io.github.iamtoolino.coda.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class AlbumResumeWriter(
    val client: String? = null,
    val platform: String? = null,
    val appVersion: String? = null,
)

@Serializable
internal data class AlbumResumeMarker(
    val protocol: String,
    val protocolVersion: Int,
    val resumeSongId: String,
)

@Serializable
private data class AlbumResumeComment(
    val protocol: String,
    val protocolVersion: Int,
    val resumeSongId: String,
    val writer: AlbumResumeWriter? = null,
)

private val markerJson = Json { ignoreUnknownKeys = true }
private const val PROTOCOL = "album-resume-bookmark"
private const val RETENTION = 20

internal fun parseAlbumResumeMarker(comment: String?): AlbumResumeMarker? =
    comment?.let {
        runCatching { markerJson.decodeFromString<AlbumResumeMarker>(it) }.getOrNull()
    }?.takeIf {
        it.protocol == PROTOCOL && it.protocolVersion == 1 && it.resumeSongId.isNotEmpty()
    }

internal fun albumResumeComment(songId: String, writer: AlbumResumeWriter? = null): String? {
    val id = songId.trim().takeIf(String::isNotEmpty) ?: return null
    for (metadata in listOf(writer, null)) {
        val comment = markerJson.encodeToString(AlbumResumeComment(PROTOCOL, 1, id, metadata))
        if (comment.toByteArray(Charsets.UTF_8).size <= 255) return comment
    }
    return null
}

internal fun Song.isAlbumResumeEligible(): Boolean =
    !albumId.isNullOrBlank() &&
        (type.isNullOrBlank() || type.trim().equals("music", ignoreCase = true)) &&
        (mediaType.isNullOrBlank() || mediaType.trim().equals("song", ignoreCase = true))

internal data class AlbumResumeItem(
    val album: Album,
    val anchorSongId: String,
    val resumeSongId: String,
    val changed: String,
)

internal data class AlbumResumeBucket(val item: AlbumResumeItem, val anchorIds: List<String>)

internal fun albumResumeBuckets(bookmarks: List<Bookmark>): List<AlbumResumeBucket> {
    val items = bookmarks.mapNotNull { bookmark ->
        val marker = parseAlbumResumeMarker(bookmark.comment) ?: return@mapNotNull null
        val song = bookmark.entry ?: return@mapNotNull null
        val albumId = song.albumId?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
        if (song.album.isEmpty()) return@mapNotNull null
        AlbumResumeItem(
            album = Album(albumId, song.album, song.artistName, coverArt = song.albumArtworkId),
            anchorSongId = song.id,
            resumeSongId = marker.resumeSongId,
            changed = bookmark.changed ?: bookmark.created ?: "",
        )
    }
    // Deliberately raw lexical ordering, matching macOS v1 (including malformed timestamps).
    val order = compareBy<AlbumResumeItem> { it.changed }.thenBy { it.anchorSongId }
    return items.groupBy { it.album.id }.values.map { group ->
        AlbumResumeBucket(group.maxWith(order), group.map { it.anchorSongId }.distinct())
    }.sortedWith { left, right -> order.compare(right.item, left.item) }
}

internal sealed interface AlbumResumeAction {
    data class Upsert(val anchor: String, val next: String) : AlbumResumeAction
    data class Delete(val anchor: String) : AlbumResumeAction
}

internal fun albumResumeAction(song: Song, page: AlbumPage): AlbumResumeAction? {
    if (!song.isAlbumResumeEligible() || song.albumId != page.album.id) return null
    val index = page.songs.indexOfFirst { it.id == song.id }
    val anchor = page.songs.firstOrNull()?.id?.takeIf(String::isNotBlank) ?: return null
    if (index < 0) return null
    return page.songs.getOrNull(index + 1)?.let { AlbumResumeAction.Upsert(anchor, it.id) }
        ?: AlbumResumeAction.Delete(anchor)
}

/** One authenticated account's in-memory bonus feature. All entry points run on Main. */
internal class AlbumResumeCoordinator(
    private val scope: CoroutineScope,
    private val loadBookmarks: suspend () -> List<Bookmark>,
    private val loadSong: suspend (String) -> Song,
    private val loadAlbum: suspend (String) -> AlbumPage,
    private val upsert: suspend (String, String) -> Unit,
    private val delete: suspend (String) -> Unit,
    private val writer: AlbumResumeWriter? = null,
) {
    private val _items = MutableStateFlow<List<AlbumResumeItem>>(emptyList())
    val items = _items.asStateFlow()
    private var refreshJob: Job? = null
    private var mutationJob: Job? = null
    private var continuationJob: Job? = null
    private val writes = Mutex()
    private var revision = 0L

    fun refresh(): Job {
        refreshJob?.takeIf { it.isActive }?.let { return it }
        val expected = revision
        refreshJob = scope.launch {
            bestEffort {
                val buckets = albumResumeBuckets(loadBookmarks())
                currentCoroutineContext().ensureActive()
                if (revision != expected) return@bestEffort
                _items.value = buckets.take(RETENTION).map { it.item }
                for (bucket in buckets.drop(RETENTION)) {
                    for (anchor in bucket.anchorIds) {
                        writes.withLock {
                            currentCoroutineContext().ensureActive()
                            if (revision == expected) bestEffort { delete(anchor) }
                        }
                    }
                }
            }
        }
        return requireNotNull(refreshJob)
    }

    fun completed(songId: String): Job {
        val previous = mutationJob
        mutationJob = scope.launch {
            previous?.join()
            bestEffort {
                val song = loadSong(songId)
                if (!song.isAlbumResumeEligible()) return@bestEffort
                val page = loadAlbum(requireNotNull(song.albumId))
                val action = albumResumeAction(song, page) ?: return@bestEffort
                writes.withLock {
                    currentCoroutineContext().ensureActive()
                    // Invalidate any refresh/cleanup snapshot before submitting a progress write.
                    revision++
                    when (action) {
                        is AlbumResumeAction.Upsert -> {
                            val comment = albumResumeComment(action.next, writer) ?: return@withLock
                            upsert(action.anchor, comment)
                            currentCoroutineContext().ensureActive()
                            revision++
                            _items.value = (listOf(
                                AlbumResumeItem(page.album, action.anchor, action.next, ""),
                            ) + _items.value.filter { it.album.id != page.album.id })
                                .take(RETENTION)
                        }
                        is AlbumResumeAction.Delete -> {
                            delete(action.anchor)
                            currentCoroutineContext().ensureActive()
                            revision++
                            _items.value = _items.value.filter { it.album.id != page.album.id }
                        }
                    }
                }
            }
        }
        return requireNotNull(mutationJob)
    }

    fun continueListening(item: AlbumResumeItem, play: (List<Song>, Int) -> Unit): Job {
        continuationJob?.cancel()
        continuationJob = scope.launch {
            bestEffort {
                val page = loadAlbum(item.album.id)
                currentCoroutineContext().ensureActive()
                val index = page.songs.indexOfFirst { it.id == item.resumeSongId }
                if (index >= 0) {
                    play(page.songs.map { it.copy(coverArt = page.album.artworkId) }, index)
                }
            }
        }
        return requireNotNull(continuationJob)
    }

    private suspend fun bestEffort(action: suspend () -> Unit) {
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Bonus metadata must never interrupt playback or clear good Home content.
        }
    }
}
