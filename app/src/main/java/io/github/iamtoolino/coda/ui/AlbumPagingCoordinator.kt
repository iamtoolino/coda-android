package io.github.iamtoolino.coda.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.iamtoolino.coda.data.Album
import io.github.iamtoolino.coda.data.AlbumListType
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal const val ALBUM_PAGE_SIZE = 90
internal const val ALBUM_PREFETCH_DISTANCE = 30
internal const val ALBUM_LOADING_PLACEHOLDERS = 6
private const val MAX_PAGED_ALBUMS = 500_000

internal data class AlbumPagingState(
    val type: AlbumListType,
    val albums: List<Album>? = null,
    val isRefreshing: Boolean = true,
    val isAppending: Boolean = false,
    val endReached: Boolean = false,
    val errorMessage: String? = null,
    val appendErrorMessage: String? = null,
)

@Stable
internal class AlbumPagingCoordinator(
    private val scope: CoroutineScope,
    initialType: AlbumListType,
    private val pageSize: Int = ALBUM_PAGE_SIZE,
    private val loader: suspend (type: AlbumListType, offset: Int, size: Int) -> List<Album>,
) {
    var state by mutableStateOf(AlbumPagingState(type = initialType))
        private set

    private var loadJob: Job? = null
    private var generation = 0L
    private var nextOffset = 0

    init {
        require(pageSize > 0)
    }

    fun load(type: AlbumListType = state.type): Job {
        val requestGeneration = ++generation
        loadJob?.cancel()
        val retainedAlbums = state.albums.takeIf { state.type == type }
        state = AlbumPagingState(
            type = type,
            albums = retainedAlbums,
            isRefreshing = true,
        )
        return scope.launch {
            try {
                val page = loader(type, 0, pageSize)
                if (generation == requestGeneration) {
                    nextOffset = page.size
                    state = AlbumPagingState(
                        type = type,
                        albums = page.distinctBy(Album::id),
                        isRefreshing = false,
                        endReached = page.size < pageSize,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (generation == requestGeneration) {
                    state = AlbumPagingState(
                        type = type,
                        albums = retainedAlbums,
                        isRefreshing = false,
                        errorMessage = error.message ?: "Network request failed",
                    )
                }
            }
        }.also { loadJob = it }
    }

    fun loadNext(): Job? {
        val current = state
        val currentAlbums = current.albums ?: return null
        if (current.isRefreshing || current.isAppending || current.endReached) return null

        val requestGeneration = ++generation
        loadJob?.cancel()
        val requestedOffset = nextOffset
        state = current.copy(isAppending = true, appendErrorMessage = null)
        return scope.launch {
            try {
                val page = loader(current.type, requestedOffset, pageSize)
                val knownIds = currentAlbums.asSequence().map(Album::id).toHashSet()
                val newAlbums = page.distinctBy(Album::id).filter { knownIds.add(it.id) }
                if (page.size == pageSize && newAlbums.isEmpty()) {
                    throw IOException("Album pagination made no progress at offset $requestedOffset")
                }
                if (requestedOffset > MAX_PAGED_ALBUMS - page.size) {
                    throw IOException("Album pagination exceeded $MAX_PAGED_ALBUMS items")
                }
                if (generation == requestGeneration) {
                    nextOffset = requestedOffset + page.size
                    state = current.copy(
                        albums = currentAlbums + newAlbums,
                        isAppending = false,
                        endReached = page.size < pageSize,
                        appendErrorMessage = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (generation == requestGeneration) {
                    state = current.copy(
                        isAppending = false,
                        appendErrorMessage = error.message ?: "Network request failed",
                    )
                }
            }
        }.also { loadJob = it }
    }
}

internal fun shouldLoadNextAlbumPage(
    lastVisibleAlbumIndex: Int,
    loadedAlbumCount: Int,
    isLoading: Boolean,
    endReached: Boolean,
    hasAppendError: Boolean,
    prefetchDistance: Int = ALBUM_PREFETCH_DISTANCE,
): Boolean = loadedAlbumCount > 0 &&
    !isLoading &&
    !endReached &&
    !hasAppendError &&
    lastVisibleAlbumIndex >= (loadedAlbumCount - prefetchDistance).coerceAtLeast(0)
