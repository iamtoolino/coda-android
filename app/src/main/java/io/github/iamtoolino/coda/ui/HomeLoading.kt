package io.github.iamtoolino.coda.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.iamtoolino.coda.AppGraph
import io.github.iamtoolino.coda.data.Album
import io.github.iamtoolino.coda.data.AlbumListType
import io.github.iamtoolino.coda.data.Artist
import io.github.iamtoolino.coda.data.NavidromeClient
import io.github.iamtoolino.coda.data.Playlist
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

internal data class HomeSectionState<T>(
    val value: T? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
) {
    fun loading(): HomeSectionState<T> = copy(isLoading = true, errorMessage = null)

    fun loaded(value: T): HomeSectionState<T> = HomeSectionState(value = value, isLoading = false)

    fun failed(error: Throwable): HomeSectionState<T> = copy(
        isLoading = false,
        errorMessage = error.message ?: "Network request failed",
    )
}

internal suspend fun <T> retryNetworkRequest(
    retryDelaysMillis: List<Long> = listOf(500L, 1_500L),
    delayBlock: suspend (Long) -> Unit = { delay(it) },
    request: suspend () -> T,
): T {
    retryDelaysMillis.forEach { retryDelay ->
        try {
            return request()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            delayBlock(retryDelay)
        }
    }
    return request()
}

internal enum class HomeSection {
    ARTISTS,
    NEWEST,
    RECENT_RELEASES,
    RECENTLY_PLAYED,
    PLAYLISTS,
}

internal interface HomeDataSource {
    suspend fun newestAlbums(): List<Album>
    suspend fun artists(): List<Artist>
    suspend fun recentReleases(): List<Album>
    suspend fun recentlyPlayed(): List<Album>
    suspend fun playlists(): List<Playlist>
}

private object LiveHomeDataSource : HomeDataSource {
    override suspend fun newestAlbums(): List<Album> = request { newestAlbums(24) }

    override suspend fun artists(): List<Artist> = request { artists() }

    override suspend fun recentReleases(): List<Album> = request {
        albums(AlbumListType.RELEASE_YEAR, 24)
    }

    override suspend fun recentlyPlayed(): List<Album> = request { recentlyPlayedAlbums(20) }

    override suspend fun playlists(): List<Playlist> = request { playlists() }

    private suspend fun <T> request(block: suspend NavidromeClient.() -> T): T =
        AppGraph.withCurrentSession {
            retryNetworkRequest { block(this) }
        }
}

@Stable
internal class HomeCoordinator(
    private val scope: CoroutineScope,
    private val dataSource: HomeDataSource = LiveHomeDataSource,
) {
    var artists by mutableStateOf(HomeSectionState<List<Artist>>())
        private set
    var newest by mutableStateOf(HomeSectionState<List<Album>>())
        private set
    var recentReleases by mutableStateOf(HomeSectionState<List<Album>>())
        private set
    var recentlyPlayed by mutableStateOf(HomeSectionState<List<Album>>())
        private set
    var playlists by mutableStateOf(HomeSectionState<List<Playlist>>())
        private set

    private var refreshJob: Job? = null
    private val sectionJobs = mutableMapOf<HomeSection, Job>()

    val isRefreshing: Boolean
        get() = listOf(artists, newest, recentReleases, recentlyPlayed, playlists)
            .any { it.isLoading && it.value != null }

    fun refreshAll(): Job {
        refreshJob?.cancel()
        sectionJobs.values.forEach(Job::cancel)
        sectionJobs.clear()
        artists = artists.loading()
        newest = newest.loading()
        recentReleases = recentReleases.loading()
        recentlyPlayed = recentlyPlayed.loading()
        playlists = playlists.loading()

        val job = scope.launch {
            supervisorScope {
                val newestRequest = async { requestResult(dataSource::newestAlbums) }
                sectionJobs[HomeSection.NEWEST] = launch {
                    newest = newestRequest.await().fold(newest::loaded, newest::failed)
                }
                sectionJobs[HomeSection.ARTISTS] = launch {
                    val artistsRequest = async { requestResult(dataSource::artists) }
                    artists = combineArtists(
                        newestResult = newestRequest.await(),
                        artistsResult = artistsRequest.await(),
                        previous = artists,
                    )
                }
                sectionJobs[HomeSection.RECENT_RELEASES] = launch {
                    recentReleases = requestResult(dataSource::recentReleases)
                        .fold(recentReleases::loaded, recentReleases::failed)
                }
                sectionJobs[HomeSection.RECENTLY_PLAYED] = launch {
                    recentlyPlayed = requestResult(dataSource::recentlyPlayed)
                        .fold(recentlyPlayed::loaded, recentlyPlayed::failed)
                }
                sectionJobs[HomeSection.PLAYLISTS] = launch {
                    playlists = requestResult(dataSource::playlists)
                        .fold(playlists::loaded, playlists::failed)
                }
            }
        }
        refreshJob = job
        return job
    }

    fun retry(section: HomeSection) {
        sectionJobs.remove(section)?.cancel()
        when (section) {
            HomeSection.ARTISTS -> {
                artists = artists.loading()
                sectionJobs[section] = scope.launch {
                    supervisorScope {
                        val newestRequest = async { requestResult(dataSource::newestAlbums) }
                        val artistsRequest = async { requestResult(dataSource::artists) }
                        artists = combineArtists(
                            newestResult = newestRequest.await(),
                            artistsResult = artistsRequest.await(),
                            previous = artists,
                        )
                    }
                }
            }
            HomeSection.NEWEST -> startSection(
                section = section,
                loading = { newest = newest.loading() },
                request = dataSource::newestAlbums,
                publish = { newest = it.fold(newest::loaded, newest::failed) },
            )
            HomeSection.RECENT_RELEASES -> startSection(
                section = section,
                loading = { recentReleases = recentReleases.loading() },
                request = dataSource::recentReleases,
                publish = {
                    recentReleases = it.fold(recentReleases::loaded, recentReleases::failed)
                },
            )
            HomeSection.RECENTLY_PLAYED -> startSection(
                section = section,
                loading = { recentlyPlayed = recentlyPlayed.loading() },
                request = dataSource::recentlyPlayed,
                publish = {
                    recentlyPlayed = it.fold(recentlyPlayed::loaded, recentlyPlayed::failed)
                },
            )
            HomeSection.PLAYLISTS -> startSection(
                section = section,
                loading = { playlists = playlists.loading() },
                request = dataSource::playlists,
                publish = { playlists = it.fold(playlists::loaded, playlists::failed) },
            )
        }
    }

    private fun <T> startSection(
        section: HomeSection,
        loading: () -> Unit,
        request: suspend () -> T,
        publish: (Result<T>) -> Unit,
    ) {
        loading()
        sectionJobs[section] = scope.launch { publish(requestResult(request)) }
    }

    private suspend fun <T> requestResult(
        request: suspend () -> T,
    ): Result<T> = try {
        Result.success(request())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }
}

private fun combineArtists(
    newestResult: Result<List<Album>>,
    artistsResult: Result<List<Artist>>,
    previous: HomeSectionState<List<Artist>>,
): HomeSectionState<List<Artist>> {
    val newest = newestResult.getOrElse { return previous.failed(it) }
    val artists = artistsResult.getOrElse { return previous.failed(it) }
    return previous.loaded(recentArtists(newest, artists))
}

internal fun recentArtists(newestAlbums: List<Album>, artists: List<Artist>): List<Artist> {
    val artistsById = artists.associateBy { it.id }
    val artistsByName = artists.associateBy { it.name.lowercase() }
    return newestAlbums.asSequence()
        .mapNotNull { album ->
            album.artistId?.let(artistsById::get)
                ?: artistsByName[album.artist.lowercase()]
        }
        .distinctBy { it.id }
        .take(20)
        .toList()
}
