package dev.coda.android.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.coda.android.session.Session
import dev.coda.android.session.SessionRepository
import dev.coda.core.library.HomeShelves
import dev.coda.core.library.SnapshotStore
import dev.coda.core.subsonic.*
import dev.coda.core.sync.*
import dev.coda.android.ui.common.retryWhenBack
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withPermit

data class HomeState(
    val albums: List<Album> = emptyList(),
    val recentReleases: List<Album> = emptyList(),
    val recentlyPlayed: List<Album> = emptyList(),
    val continueListening: List<AlbumResumeItem> = emptyList(),
    /** Album ID to progress of its Continue Listening tile; absent until the album's tracks are known. */
    val resumeProgress: Map<String, ResumeProgress> = emptyMap(),
    val playlists: List<Playlist> = emptyList(),
    val handoff: PlayQueue? = null,
    val loading: Boolean = true,
    val failed: Boolean = false,
    /** The server has answered Continue Listening in this session, so an empty [continueListening] really is empty. */
    val resumeKnown: Boolean = false,
    /** Live shelf answers so far (Home's way back from the phone waits for the next one). */
    val answers: Int = 0,
)
class HomeViewModel internal constructor(
    private val isCurrent: () -> Boolean,
    private val unauthorized: suspend () -> Unit,
    private val loadShelves: suspend () -> HomeState,
    private val loadBookmarks: suspend () -> List<Bookmark>,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val loadSnapshot: suspend () -> HomeShelves? = { null },
    private val saveSnapshot: suspend (HomeShelves) -> Unit = {},
    private val loadAlbum: suspend (String) -> AlbumPage? = { null },
    private val recovered: Flow<Unit> = emptyFlow(),
    /** Album-resume coordinator: serialises the refresh behind pending writes, prunes to 20, edits the shelf after a write. */
    private val resume: AlbumResumeCoordinator? = null,
    private val resumeToken: Any = Unit,
) : ViewModel() {
    constructor(session: Session, sessions: SessionRepository, snapshots: SnapshotStore? = null, recovered: Flow<Unit> = emptyFlow(),
        resume: AlbumResumeCoordinator? = null) : this(
        isCurrent = { sessions.isCurrent(session.token) },
        unauthorized = { sessions.unauthorized(session.token) },
        loadShelves = {
            coroutineScope {
                val newest = async { session.client.getAlbumList2(size = 24).albums }
                val releases = async { session.client.getAlbumList2(size = 24, type = AlbumListType.RECENT_RELEASES).albums }
                val recent = async { session.client.getAlbumList2(size = 20, type = AlbumListType.RECENTLY_PLAYED).albums }
                val playlists = async { session.client.getPlaylists() }
                HomeState(albums = newest.await(), recentReleases = releases.await(),
                    recentlyPlayed = recent.await(), playlists = playlists.await(), loading = false)
            }
        },
        loadBookmarks = { session.client.getBookmarks() },
        loadSnapshot = { snapshots?.let { withContext(Dispatchers.IO) { it.home() } } },
        saveSnapshot = { shelves -> snapshots?.let { withContext(Dispatchers.IO) { it.putHome(shelves) } } },
        // The saved copy first: track lists and durations rarely change, so a Home refresh costs no request per tile.
        loadAlbum = { id ->
            snapshots?.let { withContext(Dispatchers.IO) { it.album(id) } }
                ?: session.client.getAlbum(id).also { page -> snapshots?.let { withContext(Dispatchers.IO) { it.putAlbum(page) } } }
        },
        recovered = recovered,
        resume = resume,
        resumeToken = session.token,
    )

    private val mutable = MutableStateFlow(HomeState())
    val state = mutable.asStateFlow()
    private var shelvesJob: Job? = null
    private var bookmarksJob: Job? = null
    private var progressJob: Job? = null
    /** The resume marker (resumeSongId) each shown progress was resolved for; progress belongs to (album, marker). */
    private val progressMarkers = mutableMapOf<String, String>()
    private var showingLive = false
    init {
        resume?.let { coordinator ->
            viewModelScope.launch {
                coordinator.items.collect { items ->
                    ensureCurrent { mutable.value = mutable.value.copy(continueListening = items) }
                    resolveProgress(items)
                }
            }
        }
        restoreSnapshot(); refresh()
        viewModelScope.retryWhenBack(recovered, { mutable.value.failed }, ::refresh)
    }

    /** Stale-while-revalidate (spec 2.1): saved shelves show at once, also when the session needs a login. */
    private fun restoreSnapshot() {
        viewModelScope.launch {
            val saved = try { loadSnapshot() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (saved != null && !showingLive) mutable.value = mutable.value.copy(albums = saved.albums,
                recentReleases = saved.recentReleases, recentlyPlayed = saved.recentlyPlayed, playlists = saved.playlists)
            if (!isCurrent()) mutable.value = mutable.value.copy(loading = false, failed = saved == null)
        }
    }

    fun refresh() {
        bookmarksJob?.cancel()
        // With the coordinator the shelf flow restarts progress; an equal list would not re-emit, so keep a running one.
        if (resume == null) progressJob?.cancel()
        refreshShelves()
        refreshBookmarks()
    }

    private fun refreshShelves() {
        shelvesJob?.cancel()
        shelvesJob = request(onFailure = {
            mutable.value = mutable.value.copy(loading = false, failed = true)
        }) {
            mutable.value = mutable.value.copy(loading = true, failed = false)
            val shelves = loadShelves()
            ensureCurrent {
                showingLive = true
                mutable.value = mutable.value.copy(albums = shelves.albums, recentReleases = shelves.recentReleases,
                    recentlyPlayed = shelves.recentlyPlayed, playlists = shelves.playlists, loading = false, failed = false,
                    answers = mutable.value.answers + 1)
            }
            if (isCurrent()) saveSnapshot(HomeShelves(shelves.albums, shelves.recentReleases, shelves.recentlyPlayed, shelves.playlists))
        }
    }

    /** Process foreground events share an in-flight initial/manual refresh, including its 250 ms delay. */
    fun foreground() = refreshBookmarks(delayMs = 250)

    private fun refreshBookmarks(delayMs: Long = 0) {
        if (bookmarksJob?.isActive == true || !isCurrent()) return
        bookmarksJob = request {
            delay(delayMs)
            if (!isCurrent()) return@request
            if (resume != null) {
                // Fresh answer -> shelf (newest 20) -> best-effort cleanup, all in the coordinator's write queue.
                val fresh = resume.refresh(resumeToken)
                if (fresh) ensureCurrent { mutable.value = mutable.value.copy(resumeKnown = true) }
                if (fresh && mutable.value.failed && shelvesJob?.isActive != true) refreshShelves()
                return@request
            }
            val bookmarks = loadBookmarks()
            val items = withContext(computationDispatcher) { albumResumeItems(bookmarks) }
            ensureCurrent { mutable.value = mutable.value.copy(continueListening = items, resumeKnown = true) }
            // The server just answered: a shelves failure still in the state is stale, so reload it (PF-2 item 7;
            // An automatic retry, no line over the shown shelves).
            if (mutable.value.failed && shelvesJob?.isActive != true) refreshShelves()
            resolveProgress(items)
        }
    }

    /** Progress bar and "5 of 12 · 28 min left" for each Continue Listening tile (spec 5.3); a tile without it keeps its artist line. */
    private fun resolveProgress(items: List<AlbumResumeItem>) {
        progressJob?.cancel()
        // A tile whose bookmark moved drops the old marker's progress now; an unchanged one keeps it meanwhile.
        val stale = items.filter { progressMarkers[it.album.id].let { marker -> marker != null && marker != it.resumeSongId } }.map { it.album.id }.toSet()
        if (stale.isNotEmpty()) {
            progressMarkers -= stale
            mutable.value = mutable.value.copy(resumeProgress = mutable.value.resumeProgress - stale)
        }
        progressJob = viewModelScope.launch {
            val gate = kotlinx.coroutines.sync.Semaphore(4)
            items.map { item ->
                launch {
                    var failed = false
                    val progress = try { gate.withPermit { loadAlbum(item.album.id) }?.let { resumeProgress(it.songs, item.resumeSongId) } }
                        catch (e: CancellationException) { throw e } catch (e: SubsonicError.Unauthorized) { failed = true; null } catch (_: Exception) { failed = true; null }
                    // Progress for an unchanged marker survives a failed refresh; a marker without an earlier
                    // success loses it on failure, and any marker whose song the loaded album lacks loses it.
                    ensureCurrent {
                        val id = item.album.id
                        if (progress != null) {
                            progressMarkers[id] = item.resumeSongId
                            mutable.value = mutable.value.copy(resumeProgress = mutable.value.resumeProgress + (id to progress))
                        } else if (!failed || progressMarkers[id] != item.resumeSongId) {
                            progressMarkers -= id
                            mutable.value = mutable.value.copy(resumeProgress = mutable.value.resumeProgress - id)
                        }
                    }
                }
            }.joinAll()
        }
    }

    private suspend fun ensureCurrent(publish: () -> Unit) {
        currentCoroutineContext().ensureActive()
        if (isCurrent()) publish()
    }

    private fun request(onFailure: () -> Unit = {}, block: suspend CoroutineScope.() -> Unit): Job =
        viewModelScope.launch {
            if (!isCurrent()) return@launch
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: SubsonicError.Unauthorized) { if (isCurrent()) unauthorized() }
            catch (_: Exception) { ensureCurrent(onFailure) }
        }
}
