package dev.coda.android.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.coda.android.session.Session
import dev.coda.android.session.SessionRepository
import dev.coda.core.library.SnapshotStore
import dev.coda.core.subsonic.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import dev.coda.android.ui.common.retryWhenBack

data class LibraryState(
    val artists: List<Artist> = emptyList(), val artist: ArtistPage? = null,
    val results: SearchResults = SearchResults(), val query: String = "",
    val loading: Boolean = false, val failed: Boolean = false, val offline: Boolean = false,
    /** The shown search results came from snapshots on this phone (the server was unavailable). */
    val fromPhone: Boolean = false,
    val projection: ArtistProjection = ArtistProjection(),
    val pending: Boolean = false,
    /** Artists tab: [artists] in index order with their letter sections, computed off the main thread. */
    val index: dev.coda.core.library.ArtistIndex? = null,
    /** Live answers so far (the way back waits for the next one). */
    val answers: Int = 0,
)

// Search shows at most this many of each kind; a section that reaches its limit shows no "more" affordance.
private const val ARTIST_LIMIT = 16
private const val ALBUM_LIMIT = 24
private const val SONG_LIMIT = 40

/** Each tab/detail owns a model; cancelling its job prevents stale requests committing. */
class LibraryViewModel(private val session: Session, private val sessions: SessionRepository,
    private val snapshots: SnapshotStore? = null,
    recovered: Flow<Unit> = emptyFlow(),
    private val isOnline: () -> Boolean = { true }) : ViewModel() {
    private val mutable = MutableStateFlow(LibraryState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var lastRequest: (() -> Unit)? = null
    init { viewModelScope.retryWhenBack(recovered, { mutable.value.failed }, { lastRequest?.invoke() }) }
    fun artists() {
        lastRequest = { artists() }
        if (snapshots != null && mutable.value.artists.isEmpty()) viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { snapshots.artists() }
            val index = saved?.let { withContext(Dispatchers.Default) { dev.coda.core.library.ArtistIndex.of(it) } }
            if (saved != null && mutable.value.artists.isEmpty()) mutable.value = mutable.value.copy(artists = saved, index = index)
        }
        request {
            val list = session.client.getArtists()
            val index = withContext(Dispatchers.Default) { dev.coda.core.library.ArtistIndex.of(list) }
            snapshots?.let { withContext(Dispatchers.IO) { it.putArtists(list) } }
            mutable.value.copy(artists = list, index = index)
        }
    }
    fun artist(id: String) {
        lastRequest = { artist(id) }
        job?.cancel()
        job = viewModelScope.launch {
            mutable.value = mutable.value.copy(loading = true, failed = false, offline = false)
            // Stale-while-revalidate: a saved page stays visible whatever the server does next.
            val saved = snapshots?.let { withContext(Dispatchers.IO) { it.artist(id) } }
            val savedProjection = saved?.let { withContext(Dispatchers.Default) { ArtistProjection.from(it) } }
            mutable.value = mutable.value.copy(artist = saved, projection = savedProjection ?: ArtistProjection(),
                loading = true, failed = false, offline = false)
            try {
                if (!isOnline()) {
                    mutable.value = mutable.value.copy(loading = false, failed = true, offline = true)
                    return@launch
                }
                val page = session.client.getArtist(id)
                val projection = withContext(Dispatchers.Default) { ArtistProjection.from(page) }
                ensureActive()
                if (!sessions.isCurrent(session.token)) return@launch
                mutable.value = mutable.value.copy(artist = page, projection = projection,
                    loading = false, answers = mutable.value.answers + 1)
                snapshots?.let { withContext(Dispatchers.IO) { it.putArtist(page) } }
                // Optional credits must never hold the usable discography behind a spinner.
                try {
                    val appearances = discoverAppearances(session.client, page)
                    val enriched = withContext(Dispatchers.Default) { ArtistProjection.from(page, appearances) }
                    ensureActive()
                    if (sessions.isCurrent(session.token)) mutable.value = mutable.value.copy(projection = enriched)
                } catch (e: CancellationException) { throw e }
                catch (e: SubsonicError.Unauthorized) { throw e }
                catch (_: Exception) { /* Retain the usable artist page. */ }
            } catch (e: CancellationException) { throw e }
            catch (_: SubsonicError.Unauthorized) {
                sessions.unauthorized(session.token)
                mutable.value = mutable.value.copy(loading = false, failed = true)
            }
            catch (_: Exception) {
                if (sessions.isCurrent(session.token)) mutable.value = mutable.value.copy(loading = false, failed = true, offline = !isOnline())
            }
        }
    }
    /**
     * Offline the shell searches the phone's catalogue itself; the model only keeps the query (no request, no
     * fallback), so the way back can ask the server for it.
     */
    fun hold(query: String) {
        lastRequest = { search(query) }
        job?.cancel()
        mutable.value = mutable.value.copy(query = query, pending = false, failed = false, offline = false, loading = false)
    }
    fun search(query: String) {
        lastRequest = { search(query) }
        job?.cancel()
        mutable.value = mutable.value.copy(query = query, pending = query.isNotBlank(), failed = false, offline = false, loading = false,
            fromPhone = mutable.value.fromPhone && query.isNotBlank(),
            results = if (query.isBlank()) SearchResults() else mutable.value.results)
        // A blank query is answered at once (nothing to ask): it counts as a live answer, so the way back ends.
        if (query.isBlank()) { mutable.value = mutable.value.copy(answers = mutable.value.answers + 1); return }
        // No server (or it fails): fall back to what is already on this phone, labelled as such.
        val local: (suspend () -> LibraryState)? = snapshots?.let { store -> {
            val found = withContext(Dispatchers.IO) { store.search(query, ARTIST_LIMIT, ALBUM_LIMIT, SONG_LIMIT) }
            mutable.value.copy(results = found, fromPhone = true, loading = false, pending = false, failed = false, offline = false)
        } }
        request(debounce = true, fallback = local) {
            mutable.value.copy(results = session.client.search3(query,
                artistCount = ARTIST_LIMIT, albumCount = ALBUM_LIMIT, songCount = SONG_LIMIT))
        }
    }
    private fun request(debounce: Boolean = false, fallback: (suspend () -> LibraryState)? = null, block: suspend () -> LibraryState) {
        job?.cancel()
        job = viewModelScope.launch {
            mutable.value = mutable.value.copy(failed = false, offline = false)
            try {
                if (debounce) delay(250)
                if (!isOnline()) {
                    mutable.value = fallback?.invoke() ?: mutable.value.copy(loading = false, pending = false, failed = true, offline = true)
                    return@launch
                }
                coroutineScope {
                    val indicator = launch { if (debounce) delay(300); mutable.value = mutable.value.copy(loading = true) }
                    val result = block()
                    indicator.cancel()
                    if (sessions.isCurrent(session.token)) mutable.value = result.copy(loading = false, failed = false, pending = false, fromPhone = false,
                        answers = mutable.value.answers + 1)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: SubsonicError.Unauthorized) {
                sessions.unauthorized(session.token)
                mutable.value = fallback?.invoke() ?: mutable.value.copy(loading = false, failed = true, pending = false)
            }
            catch (e: Exception) {
                mutable.value = (if (sessions.isCurrent(session.token)) fallback?.invoke() else null)
                    ?: mutable.value.copy(loading = false, failed = true, pending = false, offline = !isOnline())
            }
        }
    }
}

/** Offset paging avoids losing guest credits behind the artist's own catalogue. */
internal suspend fun discoverAppearances(client: SubsonicClient, page: ArtistPage): List<Album> {
    val known = page.albums.mapTo(HashSet()) { it.id }
    val albums = linkedMapOf<String, Album>()
    var offset = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        val result = client.search3(page.artist.name, 0, 100, 100, songOffset = offset)
        val ids = withContext(Dispatchers.Default) {
            result.songs.filter { song -> song.artistId == page.artist.id ||
                song.artists.orEmpty().any { it.id == page.artist.id } || song.artistName.equals(page.artist.name, true)
            }.mapNotNull { it.albumId }.distinct().filter { it !in known && it !in albums }
        }
        for (id in ids) albums[id] = result.albums.firstOrNull { it.id == id } ?: client.getAlbum(id).album
        if (result.songs.size < 100) break
        offset += result.songs.size
    }
    return albums.values.toList()
}
