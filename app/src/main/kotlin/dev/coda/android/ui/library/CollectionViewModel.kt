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

/**
 * Albums, Playlists and playlist detail. Saved content stays on screen while loading or when [failed].
 * Album lists are paged: [albums] holds the pages loaded so far, and [freshCount] of them come from the
 * server in this visit (the rest, if any, is the saved copy until the pages reach it).
 */
data class CollectionState(val albums: List<Album> = emptyList(), val playlists: List<Playlist> = emptyList(),
    val playlist: PlaylistPage? = null, val loading: Boolean = true, val failed: Boolean = false,
    val freshCount: Int = 0, val complete: Boolean = false, val loadingMore: Boolean = false, val moreFailed: Boolean = false,
    /** Albums tab: the sections of [albums] for the current sort (empty below 60 albums or for Random). */
    val sections: List<dev.coda.core.library.LibrarySection> = emptyList(),
    /**
     * Whole fresh server answers so far (the way back waits for the next one). An Albums list counts once
     * all its pages are in, never on its first page, and a sort derived from the loaded library is no answer.
     */
    val answers: Int = 0,
    /** A playlist page's header covers from the phone (stored covers only); null: its songs' covers (online). */
    val headerCovers: List<String>? = null)

class CollectionViewModel internal constructor(
    private val isCurrent: () -> Boolean,
    private val unauthorized: suspend () -> Unit,
    private val albumPage: suspend (AlbumListType, Int, Int) -> AlbumListPage,
    private val playlistList: suspend () -> List<Playlist>,
    private val playlistPage: suspend (String) -> PlaylistPage,
    private val snapshots: SnapshotStore? = null,
    private val pageSize: Int = PAGE_SIZE,
    recovered: kotlinx.coroutines.flow.Flow<Unit> = emptyFlow(),
    private val computation: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    constructor(session: Session, sessions: SessionRepository, snapshots: SnapshotStore? = null,
        recovered: kotlinx.coroutines.flow.Flow<Unit> = emptyFlow()) : this(
        { sessions.isCurrent(session.token) }, { sessions.unauthorized(session.token) },
        { type, offset, size -> session.client.getAlbumList2(size, offset, type) },
        session.client::getPlaylists, session.client::getPlaylist, snapshots, PAGE_SIZE, recovered)

    private val mutable = MutableStateFlow(CollectionState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var moreJob: Job? = null
    private var kind: AlbumListType? = null
    /** Albums fetched from the server in this visit, in order; [stale] is the saved copy shown behind them. */
    private var fresh = emptyList<Album>()
    private var stale = emptyList<Album>()
    private var lastRequest: (() -> Unit)? = null
    /** The Albums tab: the whole list is paged in behind the first page, with sections of this kind. */
    private var sectionKind: dev.coda.core.library.AlbumSectionKind? = null
    /** When the list of [kind] last finished loading from the server (the freshness window of the Albums tab). */
    private var completedAt = 0L
    /** The last complete whole-library list from the server, in server order; every other sort is derived from it while it is fresh. */
    private var library: List<Album>? = null
    private var libraryAt = 0L

    init {
        // Back online: reload a failed list, or carry on with a page that failed.
        viewModelScope.retryWhenBack(recovered, { mutable.value.failed || mutable.value.moreFailed }, {
            if (mutable.value.failed) lastRequest?.invoke() else loadMore()
        })
    }

    /**
     * Starts (or refreshes) the paged list for [type]; the first page is one request of [pageSize] albums.
     * The Albums tab (non-null [sections]) reuses what it has loaded within [FRESH_MS] unless [refresh] is set
     * (pull-to-refresh, retry): a revisit costs nothing, a sort change is derived from the loaded library, and a visit
     * that [pause] cut short carries on from the albums it already has.
     */
    fun albums(type: AlbumListType = AlbumListType.ALPHABETICAL, sections: dev.coda.core.library.AlbumSectionKind? = null, refresh: Boolean = false) {
        lastRequest = { albums(type, sections, refresh = true) }
        val wholeList = sections != null && sections != dev.coda.core.library.AlbumSectionKind.NONE
        if (wholeList && !refresh) {
            val now = clock()
            val same = kind == type && sectionKind == sections
            val whole = library?.takeIf { now - libraryAt < FRESH_MS }
            when {
                same && mutable.value.complete && !mutable.value.failed && now - completedAt < FRESH_MS -> return
                same && !mutable.value.complete && fresh.isNotEmpty() && job?.isActive != true && moreJob?.isActive != true -> { resume(type); return }
                whole != null -> { derive(type, sections!!, whole); return }
            }
        }
        job?.cancel(); moreJob?.cancel()
        if (kind != type || sectionKind != sections) { fresh = emptyList(); stale = emptyList(); mutable.value = CollectionState(answers = mutable.value.answers) }
        // A whole list already on screen (Albums tab refresh) stays until the new pages have replaced it.
        else if (wholeList && mutable.value.complete) stale = mutable.value.albums
        kind = type
        sectionKind = sections
        job = viewModelScope.launch {
            mutable.value = mutable.value.copy(loading = true, failed = false, loadingMore = false, moreFailed = false)
            if (snapshots != null && type == AlbumListType.ALPHABETICAL && mutable.value.albums.isEmpty()) {
                // Stale-while-revalidate: the saved copy shows at once, also during an outage or an expired session.
                val saved = withContext(Dispatchers.IO) { try { snapshots.albums() } catch (e: CancellationException) { throw e } catch (_: Exception) { null } }
                if (saved != null) { stale = saved; mutable.value = mutable.value.copy(albums = saved) }
            }
            try {
                val page = albumPage(type, 0, pageSize)
                ensureActive()
                if (!isCurrent()) { mutable.value = mutable.value.copy(loading = false, failed = true); return@launch }
                exhausted = false
                fresh = page.albums.distinctBy { it.id }
                publish(page, loading = false)
                fill()
            } catch (e: CancellationException) { throw e }
            catch (_: SubsonicError.Unauthorized) { unauthorized(); mutable.value = mutable.value.copy(loading = false, failed = true) }
            catch (_: Exception) { if (isCurrent()) mutable.value = mutable.value.copy(loading = false, failed = true) }
        }
    }

    /** Leaves the Albums tab: paging in the rest stops (it resumes on the next visit) and nothing is left loading. */
    fun pause() {
        if (job?.isActive != true && moreJob?.isActive != true) return
        job?.cancel(); moreJob?.cancel()
        val now = mutable.value
        // Nothing arrived yet: the next visit starts over. Otherwise the pages so far stay and the rest follows later.
        mutable.value = now.copy(loading = false, loadingMore = false)
        if (fresh.isEmpty() && now.albums.isEmpty()) kind = null
    }

    private fun resume(type: AlbumListType) {
        job = viewModelScope.launch {
            try { fill() }
            catch (e: CancellationException) { throw e }
            catch (_: SubsonicError.Unauthorized) { unauthorized(); mutable.value = mutable.value.copy(loadingMore = false, moreFailed = true) }
            catch (_: Exception) { mutable.value = mutable.value.copy(loadingMore = false, moreFailed = true) }
        }
    }

    /** A sort change inside the freshness window: the order comes from the loaded library, no request is made. */
    private fun derive(type: AlbumListType, sections: dev.coda.core.library.AlbumSectionKind, whole: List<Album>) {
        job?.cancel(); moreJob?.cancel()
        kind = type; sectionKind = sections; stale = emptyList()
        job = viewModelScope.launch {
            val list = withContext(computation) { dev.coda.core.library.AlbumOrder.derive(type, whole) }
            fresh = list
            present(complete = true, loading = false)
            completedAt = libraryAt
        }
    }

    /** The next page, when the list has scrolled near the end of what is loaded. */
    fun loadMore() {
        val type = kind ?: return
        val now = mutable.value
        if (now.complete || now.loading || now.loadingMore || moreJob?.isActive == true) return
        moreJob = viewModelScope.launch {
            mutable.value = mutable.value.copy(loadingMore = true, moreFailed = false)
            try {
                val page = albumPage(type, fresh.size, pageSize)
                ensureActive()
                if (!isCurrent()) { mutable.value = mutable.value.copy(loadingMore = false, moreFailed = true); return@launch }
                append(page)
                publish(page, loading = false)
                fill()
            } catch (e: CancellationException) { throw e }
            catch (_: SubsonicError.Unauthorized) { unauthorized(); mutable.value = mutable.value.copy(loadingMore = false, moreFailed = true) }
            catch (_: Exception) { mutable.value = mutable.value.copy(loadingMore = false, moreFailed = true) }
        }
    }

    /** Re-requests the Random list from scratch (the reshuffle chip and pull-to-refresh). */
    fun reshuffle() { val type = kind ?: return; kind = null; albums(type, sectionKind) }

    /** Random is endless; every other sort of the Albums tab pages in the rest (500 at a time) for its sections and scroller. */
    private suspend fun fill() {
        val type = kind ?: return
        val kind = sectionKind ?: return
        if (kind == dev.coda.core.library.AlbumSectionKind.NONE) return
        try { fillPages(type) }
        catch (e: CancellationException) { throw e }
        catch (e: SubsonicError.Unauthorized) { throw e }
        // The pages so far stay usable; the network-back retry carries on with loadMore().
        catch (_: Exception) { mutable.value = mutable.value.copy(loadingMore = false, moreFailed = true) }
    }

    private suspend fun fillPages(type: AlbumListType) {
        while (!mutable.value.complete) {
            mutable.value = mutable.value.copy(loadingMore = true, moreFailed = false)
            val page = albumPage(type, fresh.size, FILL_PAGE_SIZE)
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) { mutable.value = mutable.value.copy(loadingMore = false, moreFailed = true); return }
            append(page)
            publish(page, loading = false, requested = FILL_PAGE_SIZE)
        }
    }

    /** Adds a page to [fresh]; a page with nothing new (a server that ignores the offset) ends the list rather than looping. */
    private fun append(page: AlbumListPage) {
        val before = fresh.size
        fresh = (fresh + page.albums).distinctBy { it.id }
        exhausted = fresh.size == before
    }
    private var exhausted = false

    /**
     * Complete when the server's total is reached, or, without a total, on a short page. A server that caps the page
     * size below the request still answers with a full cap-sized page, so with a known total a short page just carries on;
     * an empty page or one with nothing new ([exhausted]) always ends the list.
     */
    private suspend fun publish(page: AlbumListPage, loading: Boolean, requested: Int = pageSize) {
        val total = page.totalCount
        val complete = page.albums.isEmpty() || exhausted ||
            (if (total != null) fresh.size >= total else page.albums.size < requested)
        // Random and Home lists are one page; the Albums tab's other sorts are whole once the last page is in.
        val paged = sectionKind != null && sectionKind != dev.coda.core.library.AlbumSectionKind.NONE
        present(complete, loading, answered = complete || !paged)
        // Only a whole list is worth saving; a partial one would replace a complete saved copy.
        if (complete) {
            completedAt = clock()
            val type = kind
            if (sectionKind != null && sectionKind != dev.coda.core.library.AlbumSectionKind.NONE && type != null &&
                dev.coda.core.library.AlbumOrder.isWholeLibrary(type)) {
                library = fresh; libraryAt = completedAt
            }
            if (kind == AlbumListType.ALPHABETICAL && snapshots != null) {
                stale = fresh
                withContext(Dispatchers.IO) { try { snapshots.putAlbums(fresh) } catch (e: CancellationException) { throw e } catch (_: Exception) { } }
            }
        }
    }

    /** Shows [fresh] (behind it the saved copy until the pages reach it); Title lists are put in section order first. */
    private suspend fun present(complete: Boolean, loading: Boolean, answered: Boolean = false) {
        val raw = if (complete) fresh else (fresh + stale.drop(fresh.size)).distinctBy { it.id }
        val sectionKind = sectionKind
        // Other lists (Home, playlists) have no sections: no hop to the computation thread.
        val (shown, sections) = if (sectionKind == null) raw to emptyList() else withContext(computation) {
            val ordered = if (sectionKind == dev.coda.core.library.AlbumSectionKind.TITLE) dev.coda.core.library.AlbumSectioner.titleOrder(raw) else raw
            ordered to dev.coda.core.library.AlbumSectioner.sections(sectionKind, ordered)
        }
        mutable.value = mutable.value.copy(albums = shown, freshCount = fresh.size, complete = complete,
            loading = loading, loadingMore = false, failed = false, moreFailed = false, sections = sections,
            answers = mutable.value.answers + if (answered) 1 else 0)
    }

    fun playlists() { lastRequest = { playlists() }; fetchPlaylists() }
    private fun fetchPlaylists() = request(restore = { snapshots?.playlists()?.let { CollectionState(playlists = it) } }, persist = { snapshots?.putPlaylists(it.playlists) }) {
        // Sorted off Main.
        CollectionState(playlists = playlistList().let { list -> withContext(Dispatchers.Default) { list.sortedBy { it.name.lowercase() } } }, loading = false)
    }
    fun playlist(id: String) { lastRequest = { playlist(id) }; fetchPlaylist(id) }
    private fun fetchPlaylist(id: String) = request(restore = { snapshots?.playlist(id)?.let { CollectionState(playlist = it) } },
        persist = { state -> state.playlist?.let { snapshots?.putPlaylist(it) } }) {
        CollectionState(playlist = playlistPage(id), loading = false)
    }
    private fun request(restore: suspend () -> CollectionState?, persist: suspend (CollectionState) -> Unit, load: suspend () -> CollectionState) {
        job?.cancel()
        job = viewModelScope.launch {
            mutable.value = mutable.value.copy(loading = true, failed = false)
            if (snapshots != null && mutable.value.let { it.albums.isEmpty() && it.playlists.isEmpty() && it.playlist == null }) {
                val saved = withContext(Dispatchers.IO) { try { restore() } catch (e: CancellationException) { throw e } catch (_: Exception) { null } }
                if (saved != null) mutable.value = saved.copy(loading = true, answers = mutable.value.answers)
            }
            try {
                val result = load()
                ensureActive()
                if (isCurrent()) {
                    mutable.value = result.copy(answers = mutable.value.answers + 1)
                    if (snapshots != null) withContext(Dispatchers.IO) { persist(result) }
                } else mutable.value = mutable.value.copy(loading = false, failed = true)
            } catch (e: CancellationException) { throw e }
            catch (_: SubsonicError.Unauthorized) {
                unauthorized()
                mutable.value = mutable.value.copy(loading = false, failed = true)
            }
            catch (_: Exception) { if (isCurrent()) mutable.value = mutable.value.copy(loading = false, failed = true) }
        }
    }

    companion object { const val PAGE_SIZE = 100; const val FILL_PAGE_SIZE = 500
        /** How long a loaded Albums list is reused before a visit asks the server again (pull-to-refresh always does). */
        const val FRESH_MS = 5 * 60_000L }
}
