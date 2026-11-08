package dev.coda.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import dev.coda.android.ui.common.NoticeAction
import dev.coda.android.ui.common.showNotice
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.withStarted
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.*
import androidx.media3.session.MediaController
import dev.coda.android.artwork.*
import dev.coda.android.playback.togglePlayback
import dev.coda.android.playback.PlaybackConnection
import dev.coda.android.session.Session
import dev.coda.android.ui.album.*
import dev.coda.android.ui.home.*
import dev.coda.android.ui.player.PlayerLayer
import dev.coda.android.ui.theme.rememberAccentState
import dev.coda.core.subsonic.*
import dev.coda.core.library.PlaylistSort
import dev.coda.android.settings.appSettings
import dev.coda.android.ui.library.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import androidx.compose.animation.AnimatedContent
import dev.coda.android.ui.shell.NavigationClock
import dev.coda.android.ui.shell.PageMotion
import dev.coda.android.ui.shell.RevealedPage
import dev.coda.android.ui.shell.stamp

@Composable
fun LibraryShell(session: Session,
    /** App info → Manage space; opens Settings → Music storage, then [musicStorageOpened] clears the request. */
    openMusicStorage: Boolean = false, musicStorageOpened: () -> Unit = {},
    banner: @Composable () -> Unit = {}) = ScreenModels(session.token) {
    LibraryContent(session, banner, openMusicStorage, musicStorageOpened)
}

/** A store is cleared as soon as its screen leaves composition, cancelling viewModelScope. */
@Composable
internal fun ScreenModels(identity: Any, content: @Composable () -> Unit) {
    val owner = remember(identity) { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}

/**
 * The page the shell shows. Each value is one page of the shell's fade-through; [key] also keys its saved state
 * (scroll positions), so two pages composed at once during a transition never share one.
 */
internal sealed interface ShellScreen {
    val tab: Int
    val key: String
    /** Pages without a backdrop of their own sit on Home's calm backdrop. */
    val calm: Boolean get() = this !is AlbumPage && this !is ArtistPage
    data class Root(override val tab: Int) : ShellScreen { override val key get() = "$tab-root" }
    data class Settings(override val tab: Int) : ShellScreen { override val key get() = "$tab-settings" }
    data class PlaylistPage(override val tab: Int, val id: String) : ShellScreen { override val key get() = "$tab-playlist-$id" }
    data class AlbumPage(override val tab: Int, val id: String) : ShellScreen { override val key get() = "$tab-album-$id" }
    data class ArtistPage(override val tab: Int, val id: String) : ShellScreen { override val key get() = "$tab-artist-$id" }
}

/** What drives the playback accent: the queue item's artwork identity (macOS `artworkThemeIdentity`) and its cover. */
internal data class PlaybackSubject(val identity: String?, val coverId: String?)

/** Never depends on play/pause or position: a paused track keeps its accent. */
internal fun playbackSubject(mediaId: String?, albumId: String?, coverId: String?): PlaybackSubject =
    PlaybackSubject(if (mediaId == null) null else albumId?.takeIf { it.isNotEmpty() }?.let { "album:$it" }
        ?: coverId?.let { "cover:$it" } ?: mediaId, coverId?.takeIf { mediaId != null })

private fun playbackSubject(item: MediaItem?): PlaybackSubject? = item?.mediaMetadata?.extras.let { extras ->
    // An item whose metadata has not loaded yet has no cover to judge: keep the previous accent instead of a sticky fallback.
    if (item != null && extras == null) null
    else playbackSubject(item?.mediaId, extras?.getString("albumId"), extras?.getString("coverArtId"))
}

@Composable
private fun LibraryContent(session: Session, banner: @Composable () -> Unit, openMusicStorage: Boolean, musicStorageOpened: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as dev.coda.android.CodaApp
    val scopeForNotices = rememberCoroutineScope()
    val sessions = app.sessions
    // "a network at all" from the shared state (instant, as the system's active network was).
    val isOnline = remember { { app.reachability.hasNetwork } }
    // the one state for the offline line, the offer and the pages' blocks.
    val reachabilityState = app.reachability.state.collectAsState()
    val offlineViews = remember { dev.coda.android.ui.offline.OfflineViewsState(reachabilityState) { app.reachability.chooseOffline() } }
    // Moving between two pages that can't load holds the offer block, so the line doesn't bounce.
    LaunchedEffect(offlineViews) { offlineViews.holdOfferBlocks() }
    // "Back online · updating" stays long enough to read.
    LaunchedEffect(offlineViews) { offlineViews.holdBackOnline() }
    // The account's library.db (also its library pages, see [snapshots]).
    val library = remember(session.token) {
        val config = session.client.configuration
        app.libraryStore(dev.coda.core.cache.TrackCache.serverKey(config.serverUrl, config.username))
    }
    // Offline (automatic or chosen) every view shows what's on the phone, from the catalogue and the cache.
    val phoneLibrary = remember(library) {
        // Playlist covers from the artwork store only (PlaylistCover).
        dev.coda.android.ui.offline.PhoneLibrary(dev.coda.core.library.OfflineCatalogue(library) { id ->
            app.offlineLibrary.value?.cache?.covers?.stored(id) == true }) {
            app.offlineLibrary.value?.cache?.records().orEmpty()
        }
    }
    val phoneMode = offlineViews.phone
    // "Only complete albums": read by the phone's views only, so it applies offline and never to the live lists.
    val completeOnly by app.settings.completeAlbumsOnly.collectAsState()
    LaunchedEffect(phoneMode, phoneLibrary, completeOnly) {
        if (phoneMode) dev.coda.android.ui.offline.phoneChanges(app).collectLatest { phoneLibrary.reload(completeOnly) }
    }
    // Read at the time of an action (menus and callbacks remembered before the state changed).
    val phoneModeNow = rememberUpdatedState(phoneMode)
    // The actions follow what the views show (a view keeps the phone's content on the way back).
    val phoneShown = offlineViews.shown
    fun phoneActionsNow() = dev.coda.android.ui.offline.phoneActions(phoneModeNow.value, phoneShown)
    val menuSnackbar = remember { SnackbarHostState() }
    val phoneNotice: (String) -> Unit = remember(menuSnackbar) { { message ->
        scopeForNotices.launch { menuSnackbar.currentSnackbarData?.dismiss(); menuSnackbar.showNotice(message) }
    } }
    /** The cache's own index (in memory): what plays offline right now. */
    fun hereNow(songId: String) = app.offlineLibrary.value?.cache?.contains(songId) == true
    /**
     * Offline, Play and Shuffle take the tracks on the phone only, in the view's order, and say so when
     * some were left out ("Playing the 7 tracks on this phone"); with none here, "Not on this phone" and nothing changes.
     * Online everything as before.
     */
    fun <T> pickForPlay(items: List<T>, start: Int, id: (T) -> String): dev.coda.android.ui.offline.PhonePick<T>? {
        if (!phoneActionsNow()) return dev.coda.android.ui.offline.PhonePick(items, start, 0)
        val pick = dev.coda.android.ui.offline.pickOnPhone(items, start) { hereNow(id(it)) }
        if (pick.items.isEmpty()) { if (items.isNotEmpty()) phoneNotice(dev.coda.android.ui.offline.NOT_ON_PHONE); return null }
        if (pick.leftOut > 0) phoneNotice(dev.coda.android.ui.offline.playingOnPhone(pick.items.size))
        return pick
    }
    /** As [pickForPlay] for Add to queue and Play next: silent, the caller's confirmation says how many ([queuedOnPhone]). */
    fun phoneOnly(songs: List<Song>): List<Song> = if (!phoneActionsNow()) songs else songs.filter { hereNow(it.id) }
    // The account's one snapshot store, in its library.db (pages table).
    val snapshots = library.snapshots
    // Account namespaces stay isolated, but changing login spelling must not erase kept music.
    val fetcher = remember(session.token) { CoverArtFetcher(context, session) }
    // The accent is always derived from the 472 copy itself, never from a larger or smaller stored one.
    val accents = remember(fetcher) { AccentRepository(library) { id, size -> fetcher.bytes(CoverArt(id, size), exact = true) } }
    // A cover the server changed (another stamp) was fetched again: its colours are derived again, as after a pull.
    LaunchedEffect(fetcher) {
        fetcher.renewed.collect { art ->
            if (art.size == 472) { accents.invalidate(setOf(art.id)); app.accents.artworkChanged(setOf(art.id)) }
            if (art.size == 1440) library.forgetLooks(setOf(art.id))
        }
    }
    // The accents derived before (library.db) are in the memory cache from the start.
    LaunchedEffect(accents) { accents.preload() }
    // A stopped Activity can keep its composition while another Activity replaces/detaches the loader.
    // Rebind on resume even when this composition and playback identity have not changed.
    var resumeRevision by remember { mutableIntStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(accents, lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                // The session's memory cache is read at the tap (AccentRepository.cached), on every attach and rebind.
                app.accents.attach(accents, accents::cached, session.token) { cover -> accents.accent(cover) }
                resumeRevision++
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); app.accents.detach(accents) }
    }
    val home: HomeViewModel = viewModel(key = "home-${session.token}", factory = viewModelFactory { initializer { HomeViewModel(session, sessions, snapshots, app.networkRecovered, app.albumResume.coordinator) } })
    val homeState by home.state.collectAsState()
    // Read at the time of an action (the menu actions are remembered once per session).
    val homeStateNow = rememberUpdatedState(homeState)
    val handoffOffer by app.handoffOffer.collectAsState()
    val handoffKeepsMine by app.handoffKeepsMine.collectAsState()
    DisposableEffect(home) {
        val lifecycle = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) home.foreground()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Every navigation write stamps this clock in the event handler, so a page's 150 ms deadline starts at the tap.
    val navClock = remember { NavigationClock() }
    var albumId by navClock.stamp(rememberSaveable(session.token) { mutableStateOf<String?>(null) })
    var playlistId by navClock.stamp(rememberSaveable(session.token) { mutableStateOf<String?>(null) })
    var artistId by navClock.stamp(rememberSaveable(session.token) { mutableStateOf<String?>(null) })
    val scope = rememberCoroutineScope()
    val savedScreens = rememberSaveableStateHolder()
    var newest by remember { mutableStateOf(false) }
    // App-wide, in the settings DataStore; writes run in the app scope.
    val libraryPreferences = remember { dev.coda.android.ui.library.LibraryPreferences(app.appSettings, app.scope) }
    var albumSortName by rememberSaveable { mutableStateOf<String?>(null) }
    var albumsDense by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var playlistSortName by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val loaded = libraryPreferences.read()
        newest = loaded.artistNewestFirst
        // The Albums sort and density persist app-wide.
        if (albumSortName == null) albumSortName = loaded.albumSort.name
        if (albumsDense == null) albumsDense = loaded.albumsDense
        // The Playlists sort persists app-wide like albumSort.
        if (playlistSortName == null) playlistSortName = loaded.playlistSort.name
    }
    fun selectAlbumSort(sort: AlbumSort) { albumSortName = sort.name; libraryPreferences.setAlbumSort(sort) }
    fun selectPlaylistSort(sort: PlaylistSort) { playlistSortName = sort.name; libraryPreferences.setPlaylistSort(sort) }
    fun setAlbumsDense(dense: Boolean) { albumsDense = dense; libraryPreferences.setAlbumsDense(dense) }
    val artists: LibraryViewModel = viewModel(key = "artists", factory = viewModelFactory { initializer { LibraryViewModel(session, sessions, snapshots, app.networkRecovered, isOnline) } })
    val search: LibraryViewModel = viewModel(key = "search", factory = viewModelFactory { initializer { LibraryViewModel(session, sessions, snapshots, app.networkRecovered, isOnline) } })
    val actions: LibraryActions = viewModel(factory = viewModelFactory { initializer { LibraryActions(session, sessions) } })
    val albums: CollectionViewModel = viewModel(key = "albums", factory = viewModelFactory { initializer { CollectionViewModel(session, sessions, snapshots, app.networkRecovered) } })
    val playlists: CollectionViewModel = viewModel(key = "playlists", factory = viewModelFactory { initializer { CollectionViewModel(session, sessions, snapshots, app.networkRecovered) } })
    val albumsState by albums.state.collectAsState()
    val playlistsState by playlists.state.collectAsState()
    val artistsState by artists.state.collectAsState()
    val searchState by search.state.collectAsState()
    // The view models are per session, so a re-login (new token, shell still composed) must ask them to load again.
    LaunchedEffect(session.token) { artists.artists() }
    var preparationJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var preparationFailed by remember { mutableStateOf(false) }
    var tab by navClock.stamp(rememberSaveable { mutableStateOf(0) })
    val albumSort = AlbumSort.valueOf(albumSortName ?: AlbumSort.TITLE.name)
    val playlistSort = PlaylistSort.valueOf(playlistSortName ?: PlaylistSort.NAME.name)
    LaunchedEffect(session.token, tab, albumSort, albumSortName != null) {
        // A revisit or sort change reuses the loaded library within the freshness window; leaving the tab stops paging.
        if (tab == 1) { if (albumSortName != null) albums.albums(albumSort.type, albumSort.sections) } else albums.pause()
        if (tab == 3) playlists.playlists()
    }
    var settingsOpen by navClock.stamp(rememberSaveable { mutableStateOf(false) })
    /** The removal snackbar's Details opens Music storage. */
    var storageRequested by remember { mutableStateOf(false) }
    LaunchedEffect(openMusicStorage) { if (openMusicStorage) { storageRequested = true; settingsOpen = true; musicStorageOpened() } }
    LaunchedEffect(artistId, albumId, playlistId, tab) {
        preparationJob?.cancel(); preparing = false; preparationFailed = false
    }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(session.token) {
        val connection = PlaybackConnection(context) { revision++ }
        var active = true
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { revision++ }
        }
        connection.controller.addListener({
            if (active) runCatching { connection.controller.get() }.onSuccess { controller = it; it.addListener(listener) }
        }, context.mainExecutor)
        onDispose { active = false; controller?.removeListener(listener); controller = null; connection.close() }
    }
    // revision makes the MediaController's event-driven getters observable to Compose.
    val currentId = remember(controller, revision) { controller?.currentMediaItem?.mediaId }
    // A controller that is (re)connecting reports nothing: the last playback identity stays.
    val subject = remember(controller, revision) { controller?.let { playbackSubject(it.currentMediaItem) } }
    // With nothing queued, the most recent Continue Listening album is the playback subject (same identity as when it
    // plays, so starting it never recolours). Nothing queued and no history: the brand theme, but only once Continue Listening is known to
    // be empty; until then the previous colour stays (after a cold start: the one the last process committed, AccentResolver.restore).
    val seed = homeState.continueListening.firstOrNull()?.album
    var seedCover by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(subject, resumeRevision, seed?.id, seed?.artworkId, homeState.resumeKnown) {
        val current = subject ?: return@LaunchedEffect
        if (current.identity != null) { seedCover = null; app.accents.setPlayback(current.identity, current.coverId); return@LaunchedEffect }
        // Replacing the queue passes through "no item" for a moment; only a queue that stays empty shows the seed or the brand theme.
        kotlinx.coroutines.delay(300)
        val empty = playbackSubject(null, null, null)
        val next = seed?.let { playbackSubject(it.id, it.id, it.artworkId) } ?: empty.takeIf { homeState.resumeKnown } ?: return@LaunchedEffect
        seedCover = next.coverId
        app.accents.setPlayback(next.identity, next.coverId)
    }
    LaunchedEffect(albumId, resumeRevision) {
        if (albumId == null) app.accents.showAlbum(null, owner = accents)
    }
    val pageDisplayed by app.accents.page.collectAsState()
    val dockTint by app.accents.dockTint.collectAsState()
    val playbackDisplayed by app.accents.playback.collectAsState()
    // The dock and the Material defaults outside the pages keep following the resolver's page accent; each page below
    // draws its own accent.
    // The page accent's cross-fade is read only where it is drawn (the dock, the offline line, menus and snackbars, Search),
    // never by the shell itself; the shell-wide Material theme takes the committed accent. Pages and the player wear their own.
    val pageAccent = dev.coda.android.ui.theme.rememberAccentStateOf(pageDisplayed.selection)
    val pageTheme = remember(pageDisplayed.selection) { dev.coda.android.ui.theme.AccentState.of(pageDisplayed.selection) }
    val playbackAccent = rememberAccentState(playbackDisplayed.selection, playerBackdrop = true)
    fun homeAlbum(id: String) = (homeState.albums + homeState.recentReleases + homeState.recentlyPlayed + homeState.continueListening.map { it.album }).firstOrNull { it.id == id }
    /** The cover id the tapped tile showed, until the album page knows its own. */
    var albumHint by remember { mutableStateOf<Pair<String, String?>?>(null) }
    /**
     * Opens an album page. [coverKnown]: [coverId] is the album's cover (null = the album has none), as a tile knows it.
     * The resolver looks the colour up in memory right here, so a cached album page has its colour before its first frame.
     */
    /** A page opened from Now Playing or the queue appears in place; the player's own morph is its transition. */
    var inPlaceKey by remember { mutableStateOf<String?>(null) }
    fun openAlbum(id: String, coverId: String?, coverKnown: Boolean, keepArtist: Boolean = false) {
        inPlaceKey = null
        // Reopening the shown album without a cover (Now Playing's album link) must not forget the cover the page knows.
        if (coverKnown || id != albumId) {
            albumHint = if (coverKnown) id to coverId else null
            app.accents.showAlbum(id, coverId, coverKnown, owner = accents)
        }
        albumId = id; playlistId = null; settingsOpen = false
        if (!keepArtist) artistId = null
    }
    // Back on a non-Home tab's root goes to Home; only Back on Home leaves the app. The handler is enabled only
    // on a tab root (no page and no settings open), so it never depends on being declared before the page handlers below: the
    // last enabled handler wins, and a handler added later (or always composed) cannot be shadowed by this one. Search, queue,
    // licences and Now Playing register their own handlers in their screens. Pf7DeviceTest walks every state.
    BackHandler(tab != 0 && albumId == null && artistId == null && playlistId == null && !settingsOpen) {
        tab = 0
    }
    BackHandler(playlistId != null) { playlistId = null }
    BackHandler(settingsOpen) { settingsOpen = false }
    BackHandler(artistId != null && albumId == null) { artistId = null }
    BackHandler(albumId != null) { albumId = null }
    /** Every list and page screen reloads from the server on a pull, through its existing reload path. */
    @Composable fun Refreshable(loading: Boolean, reload: () -> Unit, content: @Composable () -> Unit) {
        val visible = remember { mutableMapOf<CoverArt, Int>() }
        var artworkLoading by remember { mutableStateOf(false) }
        val refreshScope = rememberCoroutineScope()
        dev.coda.android.ui.common.PullRefresh(loading || artworkLoading, isOnline, {
            val targets = visible.keys.toList().let { arts -> arts.flatMap { listOf(it.copy(size = 472), it.copy(size = 1440)) }.distinct() }
            reload()
            refreshScope.launch {
                artworkLoading = true
                try {
                    val gate = kotlinx.coroutines.sync.Semaphore(3)
                    kotlinx.coroutines.coroutineScope {
                        targets.map { art -> launch {
                            gate.acquire()
                            try {
                                fetcher.refresh(art)
                                if (art.size == 472) {
                                    accents.invalidate(setOf(art.id))
                                    app.accents.artworkChanged(setOf(art.id))
                                }
                                // A refreshed artist photo's persisted colours are derived again.
                                if (art.size == 1440) library.forgetLooks(setOf(art.id))
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (_: Exception) { /* Retain the previous cover and accent on a failed refresh. */ }
                            finally { gate.release() }
                        } }.forEach { it.join() }
                    }
                } finally { artworkLoading = false }
            }
        }, MaterialTheme.colorScheme.primary /* the page's own accent text (AccentTheme per page) */) {
            CompositionLocalProvider(LocalPageArtwork provides visible) { content() }
        }
    }
    fun mediaItems(songs: List<Song>) = songs.map { dev.coda.android.playback.QueueMediaItems.from(it) }
    fun playSongs(songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        val pick = pickForPlay(songs, startIndex) { it.id } ?: return
        controller?.apply { setMediaItems(mediaItems(pick.items), pick.start, 0); prepare(); play() }
    }
    fun enqueueSongs(songs: List<Song>, next: Boolean) {
        val here = phoneOnly(songs)
        if (here.size < songs.size) phoneNotice(if (here.isEmpty()) dev.coda.android.ui.offline.NOT_ON_PHONE
            else dev.coda.android.ui.offline.queuedOnPhone(if (next) "Playing next" else "Added to queue", here.size))
        if (here.isEmpty()) return
        controller?.apply {
            val items = mediaItems(here)
            if (next) addMediaItems((currentMediaItemIndex + 1).coerceIn(0, mediaItemCount), items) else addMediaItems(items)
        }
    }
    /** Resume album items carry the album as their continuation (the album page's card and the album ⋮ sheet). */
    fun resumeItems(album: Album, songs: List<Song>) = songs.map { dev.coda.android.playback.QueueMediaItems.from(it, album, album.id) }
    fun resumeAlbum(album: Album, songs: List<Song>, index: Int) {
        val pick = pickForPlay(songs, index) { it.id } ?: return
        controller?.apply { setMediaItems(resumeItems(album, pick.items), pick.start, 0); prepare(); play() }
    }
    /** Album-page enqueue: returns the entry ids of the inserted queue items for Undo. */
    fun enqueueTracked(songs: List<Song>, next: Boolean): List<String> =
        controller?.let { dev.coda.android.playback.QueueInsertions.insert(it, mediaItems(songs), next) }.orEmpty()
    /** Undo removes exactly the entries that insert added, if they are still in the queue. */
    fun undoEnqueued(ids: List<String>) { controller?.let { dev.coda.android.playback.QueueInsertions.undo(it, ids) } }
    /**
     * An album page fetched without opening the album (play from a list, search, menus, playlist headers) is saved
     * like the album screen saves it, in the background (playback never waits for the write).
     */
    fun saveAlbumPage(page: dev.coda.core.subsonic.AlbumPage) { scope.launch(Dispatchers.IO) { snapshots.putAlbum(page) } }
    fun prepareAlbums(albums: List<Album>, action: (List<Song>) -> Unit) {
        if (!preparing) preparationJob = scope.launch(Dispatchers.Main.immediate) {
            preparing = true; preparationFailed = false
            try { action(albums.flatMap {
                // Offline the albums come from the phone's catalogue (no request); the action then takes the tracks here.
                val page = if (phoneActionsNow()) phoneLibrary.albumPage(it.id) ?: return@flatMap emptyList()
                    else session.client.getAlbum(it.id).also(::saveAlbumPage)
                dev.coda.core.subsonic.AlbumOrdering.canonical(page.songs).map { song ->
                    song.copy(year = song.year ?: page.album.releaseYear, displayAlbumArtist = page.album.artistName)
                }
            }) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: SubsonicError.Unauthorized) { sessions.unauthorized(session.token) }
            catch (_: Exception) { preparationFailed = true }
            finally { preparing = false }
        }
    }
    // The one shared item sheet (album, track, playlist, artist) for every screen, with this shell's actions.
    val menus = remember(session.token) { dev.coda.android.ui.menu.ItemMenuState() }
    var playlistRevision by remember { mutableIntStateOf(0) }
    // Playlists edited from a menu: their saved contents are out of date until the server's are loaded again (a menu
    // would otherwise play, queue or keep the old list).
    val stalePlaylists = remember(session.token) { java.util.concurrent.ConcurrentHashMap.newKeySet<String>() }
    val menuActions = remember(session.token, controller) { dev.coda.android.ui.menu.MenuActions(
        client = session.client, username = session.client.configuration.username,
        loadAlbum = { id -> if (phoneActionsNow()) phoneLibrary.albumPage(id)
            else kotlinx.coroutines.withContext(Dispatchers.IO) { snapshots.album(id) } ?: session.client.getAlbum(id).also(::saveAlbumPage) },
        loadPlaylist = { id ->
            val saved = if (id in stalePlaylists) null else kotlinx.coroutines.withContext(Dispatchers.IO) { snapshots.playlist(id)?.takeIf { !snapshots.playlistIsStale(id) } }
            // Offline: the kept playlist from the phone (no request).
            if (phoneActionsNow()) phoneLibrary.playlistPage(id) else saved ?: try {
                session.client.getPlaylist(id).also { page ->
                    stalePlaylists.remove(id)
                    kotlinx.coroutines.withContext(Dispatchers.IO) { snapshots.putPlaylist(page) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                // Offline or unreachable: the saved (possibly older) contents still beat nothing.
                kotlinx.coroutines.withContext(Dispatchers.IO) { snapshots.playlist(id) } ?: throw e
            }
        },
        loadArtist = { id -> if (phoneActionsNow()) phoneLibrary.artistPage(id)
            else kotlinx.coroutines.withContext(Dispatchers.IO) { snapshots.artist(id) } ?: session.client.getArtist(id) },
        savedPlaylists = { kotlinx.coroutines.withContext(Dispatchers.IO) { snapshots.playlists() ?: snapshots.home()?.playlists?.takeIf { it.isNotEmpty() } } },
        play = { songs, index -> playSongs(songs, index) },
        enqueue = { songs, next -> enqueueTracked(songs, next) },
        undo = { ids -> undoEnqueued(ids) },
        playAlbums = { albums, shuffle -> prepareAlbums(albums) { playSongs(if (shuffle) it.shuffled() else it) } },
        enqueueAlbums = { albums, then -> prepareAlbums(albums) { songs -> if (then != null) then(songs) else enqueueSongs(songs, false) } },
        openAlbum = { album -> openAlbum(album.id, album.artworkId, coverKnown = album.coverArt != null) },
        openArtist = { id -> artistId = id; albumId = null; playlistId = null; settingsOpen = false },
        rate = { id, value -> actions.rate(id, value) },
        playlistChanged = { id -> stalePlaylists.add(id); scope.launch(Dispatchers.IO) { snapshots.markPlaylistStale(id) }; playlistRevision++; playlists.playlists() },
        playlistDeleted = { id -> stalePlaylists.remove(id); if (playlistId == id) playlistId = null; playlists.playlists() },
        unauthorized = { sessions.unauthorized(session.token) },
        phoneOnly = ::phoneOnly,
        phoneMode = ::phoneActionsNow,
        resumePoint = { id -> homeStateNow.value.continueListening.firstOrNull { it.album.id == id }?.resumeSongId },
        currentMediaId = { controller?.currentMediaItem?.mediaId },
        onPhone = ::hereNow,
        resumeAlbum = ::resumeAlbum,
        appendRemaining = { album, songs -> controller?.let { dev.coda.android.playback.QueueInsertions.insert(it, resumeItems(album, songs), false) }.orEmpty() },
    ) }
    suspend fun trackAlbumPage(album: String): AlbumPage? = (if (phoneActionsNow()) phoneLibrary.albumPage(album) else null)
        ?: kotlinx.coroutines.withContext(Dispatchers.IO) { snapshots.album(album) }
        ?: if (phoneActionsNow()) null else try { session.client.getAlbum(album) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
    CompositionLocalProvider(LocalLibraryActions provides actions, LocalLibrarySession provides session,
        dev.coda.android.ui.offline.LocalPhoneShown provides phoneShown,
        dev.coda.android.ui.menu.LocalItemMenus provides menus,
        dev.coda.android.ui.theme.LocalAccentResolver provides app.accents,
        LocalOpenPlaylist provides { id -> playlistId = id; albumId = null; artistId = null },
        // The queue and Now Playing know the album id only; a page seen before keeps its colour (AccentResolver).
        LocalOpenAlbum provides { id -> openAlbum(id, null, coverKnown = false); inPlaceKey = ShellScreen.AlbumPage(tab, id).key },
        LocalOpenTrackArtist provides { album, song ->
            scope.launch {
                // The queue item has no artist ID: its album page (snapshot first, then the server) names it.
                val page = trackAlbumPage(album)
                val track = page?.songs?.firstOrNull { it.id == song }
                val target = track?.artistId ?: track?.artists?.firstOrNull()?.id ?: page?.album?.artistId ?: page?.album?.artists?.firstOrNull()?.id
                if (target != null) { artistId = target; albumId = null; playlistId = null; settingsOpen = false }
            }
        },
        dev.coda.android.ui.library.LocalOpenArtist provides { id -> artistId = id; albumId = null; playlistId = null; settingsOpen = false },
        dev.coda.android.ui.library.LocalTrackArtists provides { album, song ->
            trackAlbumPage(album)?.songs?.firstOrNull { it.id == song }?.artists.orEmpty()
        }) {
    // Material defaults (buttons, switches, progress bars, fields) follow the page accent, never brand gold.
    dev.coda.android.ui.theme.AccentTheme(pageTheme) {
    val dockState = dev.coda.android.ui.shell.rememberDockState(tab, controller?.currentMediaItem != null)
    val dockStack = dev.coda.android.ui.shell.expandedStack(controller?.currentMediaItem != null)
    // The shell recomposes only when the line changes (a derived value).
    val lineKind = offlineViews.lineKind
    // The lists' bottom padding grows and shrinks with the line, in the same 200 ms, so nothing jumps.
    val lineSlot by androidx.compose.animation.core.animateDpAsState(
        if (lineKind != dev.coda.android.ui.offline.OfflineLineKind.NONE) dev.coda.android.ui.offline.OfflineLineSlot else 0.dp,
        androidx.compose.animation.core.tween(200), label = "Offline line slot")
    CompositionLocalProvider(dev.coda.android.ui.shell.LocalDockPadding provides dockStack + lineSlot,
        dev.coda.android.ui.offline.LocalOfflineViews provides offlineViews,
        dev.coda.android.ui.offline.LocalPhoneNotice provides phoneNotice) {
    val playerAccent = rememberAccentState(playbackDisplayed.selection, playerBackdrop = true)
    PlayerLayer(controller, revision, fetcher, playerAccent, dockTint, dockLayer = { mini, playerDock ->
        Box(Modifier.fillMaxSize()) {
            // P3 (slice a): when the dock folds the line follows it down (no gap above the folded dock). Read inside the dock
            // layer, so a fold recomposes only this layer, never the shell.
            val dockFold = dev.coda.android.ui.shell.dockFold(dockState, controller?.currentMediaItem != null)
            dev.coda.android.ui.shell.BottomDock(tab, { index ->
                tab = index; albumId = null; artistId = null; playlistId = null; settingsOpen = false
            }, dockState, controller?.currentMediaItem != null, pageTheme, playbackAccent,
                Modifier.align(Alignment.BottomCenter).then(playerDock.modifier), dockTint, mini)
            // Above the dock's shade (drawn by BottomDock over the page), under the player when it opens.
            dev.coda.android.ui.theme.AccentTheme(pageAccent) {
                dev.coda.android.ui.offline.OfflineLine(lineKind, offlineViews.choose,
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = dockStack + 2.dp)
                        .offset { androidx.compose.ui.unit.IntOffset(0, (dev.coda.android.ui.shell.FoldedRowHeight * dockFold.value).roundToPx()) })
            }
        }
    }) { mini, playerDock ->
    Box(Modifier.fillMaxSize().background(Color(0xFF090B0D))) {
        Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).then(dockState.modifier)) {
            // Home's calm backdrop (the playing album's faint cover and one corner light) sits behind every screen that
            // has no backdrop of its own: Home and its lists, Search, Settings with Licences, the playlist page and the Albums, Playlists and
            // Artists tabs. One call site, so moving between them keeps the same layer (no flash, the cover cross-fades). Album and artist
            // pages draw their own. The layer is also recorded, so the tabs' pinned header bands replay exactly these pixels.
            val screen: ShellScreen = when {
                settingsOpen -> ShellScreen.Settings(tab)
                playlistId != null -> ShellScreen.PlaylistPage(tab, playlistId!!)
                albumId != null -> ShellScreen.AlbumPage(tab, albumId!!)
                artistId != null -> ShellScreen.ArtistPage(tab, artistId!!)
                else -> ShellScreen.Root(tab)
            }
            // The "Not on this phone" notice belongs to the page it was tapped on.
            LaunchedEffect(screen.key) {
                if (menuSnackbar.currentSnackbarData?.visuals?.message == dev.coda.android.ui.offline.NOT_ON_PHONE) menuSnackbar.currentSnackbarData?.dismiss()
            }
            val pages = androidx.compose.animation.core.updateTransition(screen, label = "Page")
            val tappedAt = remember(screen) { navClock.at }
            // The calm layer stays while a page that sits on it is still fading out or already fading in.
            val calmBackdrop = pages.currentState.calm || pages.targetState.calm
            val calmLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
            if (calmBackdrop) {
                val atmosphere by androidx.compose.animation.core.animateFloatAsState(if (pageDisplayed.identity != null) 1f else 0f,
                    androidx.compose.animation.core.tween(450), label = "Home backdrop")
                Box(Modifier.fillMaxSize().drawWithContent {
                    val content = this
                    calmLayer.record { content.drawContent() }
                    drawLayer(calmLayer)
                }) { dev.coda.android.ui.theme.HomeAtmosphere(fetcher, subject?.coverId ?: seedCover, playbackAccent, atmosphere) }
            }
            // One page per screen. The outgoing page fades out in 90 ms and keeps its own
            // accent; the incoming page reveals itself (RevealedPage: when its colour is ready, 90 to 150 ms, then 210 ms
            // fade and scale). The dock is outside, so it stays put; saved state per page keeps scroll positions.
            pages.AnimatedContent(Modifier.fillMaxSize(), transitionSpec = {
                if (targetState.key == inPlaceKey) androidx.compose.animation.ContentTransform(androidx.compose.animation.EnterTransition.None,
                    androidx.compose.animation.ExitTransition.None, sizeTransform = null)
                else androidx.compose.animation.ContentTransform(androidx.compose.animation.EnterTransition.None,
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(PageMotion.OUT_MS.toInt(), easing = PageMotion.Accelerate)),
                    sizeTransform = null)
            }, contentKey = { it.key }) { page ->
            val entering = remember { transition.currentState == androidx.compose.animation.EnterExitState.PreEnter && page.key != inPlaceKey }
            val active = transition.targetState == androidx.compose.animation.EnterExitState.Visible
            // Every page except an album page wears the playing colour, which is always ready.
            val own = if (page is ShellScreen.AlbumPage) PageMotion.albumOwn(pageDisplayed.identity, pageDisplayed.selection, page.id,
                    playbackDisplayed.identity, playbackDisplayed.selection)
                else playbackDisplayed.selection
            savedScreens.SaveableStateProvider(page.key) {
            RevealedPage(own, entering, active, tappedAt) { accent ->
            dev.coda.android.ui.theme.AccentTheme(accent) {
            when (page) {
            is ShellScreen.Settings -> {
                val replayGain by app.settings.replayGainEnabled.collectAsState()
                dev.coda.android.ui.settings.SettingsScreen(replayGain, app.settings::setReplayGainEnabled,
                    offline = dev.coda.android.ui.settings.OfflineSwitches(completeOnly, app.settings::setCompleteAlbumsOnly),
                    signOut = { app.scope.launch { sessions.signOut() } }, back = { settingsOpen = false },
                    storageRequested = storageRequested, storageOpened = { storageRequested = false },
                    account = remember(session.token) {
                        val config = session.client.configuration
                        // Never the raw address: one with user info (refused at login anyway) shows only its host.
                        val shown = dev.coda.android.session.credentialFreeServerUrl(config.serverUrl)
                            ?: runCatching { java.net.URI(config.serverUrl).host }.getOrNull().orEmpty()
                        dev.coda.android.ui.settings.SettingsAccount(shown, config.username)
                    })
            }
            is ShellScreen.PlaylistPage -> {
                val id = page.id
                ScreenModels("playlist-$id") {
                    val model: CollectionViewModel = viewModel(factory = viewModelFactory { initializer { CollectionViewModel(session, sessions, snapshots, app.networkRecovered) } })
                    val live by model.state.collectAsState()
                    LaunchedEffect(id, playlistRevision) { model.playlist(id) }
                    // Offline, a kept playlist from the phone, its missing tracks dimmed.
                    val fromPhone = dev.coda.android.ui.offline.rememberFromPhone(phoneMode, live.answers, { live.loading }) { model.playlist(id) }
                    val phonePage by produceState<CollectionState?>(null, id, fromPhone, phoneLibrary.revision) { if (fromPhone) value = phoneLibrary.playlist(id) }
                    // A: Home's toned-down corner glow over the playing album's faint cover (the shared layer above).
                    dev.coda.android.ui.offline.PhoneOrLive(fromPhone, phonePage, live, phoneLibrary.onPhone, live.loading, live.failed) { state ->
                    Box(Modifier.fillMaxSize()) {
                        Refreshable(state.loading, { model.playlist(id) }) {
                            PlaylistScreen(state, fetcher, accent, controller != null, { playlistId = null }, { model.playlist(id) }, ::playSongs, currentId?.removePrefix("song/"),
                                playbackAccent,
                                currentPlaylistIndex = controller?.currentMediaItemIndex, saveAlbum = ::saveAlbumPage, phoneAlbum = phoneLibrary::albumPage)
                        }
                    }
                    }
                }
            }
            is ShellScreen.AlbumPage -> {
                val id = page.id
                ScreenModels(id) {
                    val album: AlbumViewModel = viewModel(key = "album-${session.token}-$id", factory = viewModelFactory { initializer { AlbumViewModel(session, id, sessions, snapshots, app.networkRecovered) } })
                    val live by album.state.collectAsState()
                    // Offline, the album from the phone: every track, the missing ones dimmed, "7 of 12" under the cover.
                    val fromPhone = dev.coda.android.ui.offline.rememberFromPhone(phoneMode, live.answers, { live.loading }, album::refresh)
                    val phonePage by produceState<AlbumState?>(null, id, fromPhone, phoneLibrary.revision) { if (fromPhone) value = phoneLibrary.album(id) }
                    val state = phonePage?.takeIf { fromPhone } ?: live
                    val displayedAlbum = homeAlbum(id)
                    // The album context: the artwork the sleeve shows, once known; no coverArt is an explicit fallback.
                    // Until the page or Home knows the album, the tapped tile's cover stands in.
                    val hint = albumHint?.takeIf { it.first == id }
                    val coverKnown = state.page != null || displayedAlbum != null || hint != null
                    val cover = when { state.page != null -> state.page?.album?.artworkId; displayedAlbum != null -> displayedAlbum.artworkId; else -> hint?.second }
                    // Only the shown page drives the context: a leaving album must not take it back from the next one.
                    LaunchedEffect(id, cover, coverKnown, resumeRevision, active) { if (active) app.accents.showAlbum(id, cover, coverKnown, owner = accents) }
                    DisposableEffect(id) { onDispose { app.accents.leaveAlbum(id, owner = accents) } }
                    dev.coda.android.ui.offline.PhoneOrLive(fromPhone, phonePage, live, phoneLibrary.onPhone, live.loading, live.failed) { state ->
                    Refreshable(state.loading, album::refresh) {
                    AlbumScreen(displayedAlbum ?: state.page?.album ?: Album(id, "Album"), state, fetcher, accent,
                        currentId, controller?.isPlaying == true, controller != null, { albumId = null }, album::refresh,
                        play = { index ->
                            state.page?.let { page ->
                                // Offline: the tracks on the phone, from the tapped one.
                                val pick = pickForPlay(page.songs, index) { it.id } ?: return@let
                                controller?.apply {
                                    val items = pick.items.map { song ->
                                        dev.coda.android.playback.QueueMediaItems.from(song, page.album)
                                    }
                                    setMediaItems(items, pick.start, 0); prepare(); play()
                                }
                            }
                        }, toggle = { controller?.togglePlayback() },
                        artist = { target -> albumId = null; artistId = target },
                        resume = homeState.continueListening.firstOrNull { it.album.id == id },
                        resumePlay = { index -> state.page?.let { page -> resumeAlbum(page.album, page.songs, index) } })
                    }
                    }
                }
            }
            is ShellScreen.ArtistPage -> {
                val id = page.id
                ScreenModels("artist-$id") {
                    val model: LibraryViewModel = viewModel(factory = viewModelFactory { initializer { LibraryViewModel(session, sessions, snapshots, app.networkRecovered, isOnline) } })
                    val live by model.state.collectAsState()
                    LaunchedEffect(id) { model.artist(id) }
                    // Offline, own albums plus "Appears on" from the phone.
                    val fromPhone = dev.coda.android.ui.offline.rememberFromPhone(phoneMode, live.answers, { live.loading }) { model.artist(id) }
                    val phonePage by produceState<LibraryState?>(null, id, fromPhone, phoneLibrary.revision) { if (fromPhone) value = phoneLibrary.artist(id) }
                    dev.coda.android.ui.offline.PhoneOrLive(fromPhone, phonePage, live, phoneLibrary.onPhone, live.loading, live.failed) { state ->
                    // The artist page is a standard context: the playing item's accent, not one from the discography.
                        Refreshable(state.loading, { model.artist(id) }) {
                    ArtistScreen(state, fetcher, newest,
                        sort = {
                            val selected = !newest
                            newest = selected
                            libraryPreferences.setArtistNewestFirst(selected)
                        },
                        back = { artistId = null }, retry = { model.artist(id) }, open = { openAlbum(it.id, it.artworkId, coverKnown = true, keepArtist = true) })
                    }
                    }
                }
            }
            is ShellScreen.Root -> when (page.tab) {
            1 -> {
                // The library tabs sit on Home's calm backdrop (the shared layer above, fixed behind the list).
                val reload = { if (albumSort == AlbumSort.RANDOM) albums.reshuffle() else albums.albums(albumSort.type, albumSort.sections, refresh = true) }
                // Offline, the albums on the phone in the chosen sort; back online, they stay until the live list is in
                // (all its pages, so a retry never cuts the paging short).
                val fromPhone = dev.coda.android.ui.offline.rememberFromPhone(phoneMode, albumsState.answers, { albumsState.loading || albumsState.loadingMore }) { albums.albums(albumSort.type, albumSort.sections, refresh = true) }
                var phoneShuffle by remember { mutableIntStateOf(0) }
                val phoneAlbums by produceState<CollectionState?>(null, fromPhone, phoneLibrary.revision, albumSort, phoneShuffle) {
                    if (fromPhone) phoneLibrary.albums(albumSort.type, albumSort.sections)?.let { value = it }
                }
                dev.coda.android.ui.offline.PhoneOrLive(fromPhone, phoneAlbums, albumsState, phoneLibrary.onPhone,
                    albumsState.loading || albumsState.loadingMore, albumsState.failed || albumsState.moreFailed) { shown ->
                    val phone = shown !== albumsState
                    Refreshable(shown.loading, reload) {
                        AlbumsTab(shown, albumSort, albumsDense == true, fetcher, accent, dev.coda.android.ui.library.TabBackdrop(calmLayer),
                            reload, { openAlbum(it.id, it.artworkId, coverKnown = true) }, { if (!phone) albums.loadMore() }, ::selectAlbumSort, ::setAlbumsDense,
                            { if (phone) phoneShuffle++ else albums.reshuffle() })
                    }
                }
            }
            3 -> {
                // Until the saved sort is read the tab shows only the backdrop, so a saved sort never flashes the default one.
                // Offline, the kept playlists only.
                val fromPhone = dev.coda.android.ui.offline.rememberFromPhone(phoneMode, playlistsState.answers, { playlistsState.loading }, playlists::playlists)
                if (playlistSortName != null) dev.coda.android.ui.offline.PhoneOrLive(fromPhone, phoneLibrary.playlists, playlistsState, phoneLibrary.onPhone,
                    playlistsState.loading, playlistsState.failed) { shown ->
                    Refreshable(shown.loading, playlists::playlists) {
                        PlaylistsTab(shown, playlistSort, fetcher, accent, dev.coda.android.ui.library.TabBackdrop(calmLayer),
                            playlists::playlists, { playlistId = it }, ::selectPlaylistSort)
                    }
                }
            }
            2 -> {
                // Offline, the album artists of the music on the phone.
                val fromPhone = dev.coda.android.ui.offline.rememberFromPhone(phoneMode, artistsState.answers, { artistsState.loading }, artists::artists)
                dev.coda.android.ui.offline.PhoneOrLive(fromPhone, phoneLibrary.artists, artistsState, phoneLibrary.onPhone,
                    artistsState.loading, artistsState.failed) { shown ->
                    Refreshable(shown.loading, artists::artists) {
                        // Offline, the recently played row is the phone's (its play dates, only albums here).
                        val recent = if (shown !== artistsState) phoneLibrary.homeShelves?.recentlyPlayed.orEmpty() else homeState.recentlyPlayed
                        ArtistsScreen(shown, fetcher, artists::artists, { artistId = it.id }, recent, accent,
                            dev.coda.android.ui.library.TabBackdrop(calmLayer))
                    }
                }
            }
            4 -> {
                // Offline, Search reads the catalogue's FTS index, only what's on the phone; the model keeps the query
                // (no request) for the way back, which asks the server for it and cross-fades when the answer is in.
                val fromPhone = dev.coda.android.ui.offline.rememberFromPhone(phoneMode, searchState.answers, { searchState.loading }) { search.search(searchState.query) }
                // Typing is debounced as online (250 ms); the first read and a cache change are not.
                var phoneSearched by remember { mutableStateOf<String?>(null) }
                val phoneResults by produceState<LibraryState?>(null, fromPhone, searchState.query, phoneLibrary.revision) {
                    if (!fromPhone) return@produceState
                    val query = searchState.query
                    if (phoneSearched != null && phoneSearched != query) kotlinx.coroutines.delay(250)
                    val found = phoneLibrary.search(query)
                    phoneSearched = query
                    value = searchState.copy(query = query, results = found, fromPhone = true,
                        loading = false, pending = false, failed = false, offline = false)
                }
                val searchQuery: (String) -> Unit = { query -> if (phoneModeNow.value) search.hold(query) else search.search(query) }
                dev.coda.android.ui.offline.PhoneOrLive(fromPhone, phoneResults?.copy(query = searchState.query), searchState, phoneLibrary.onPhone,
                    searchState.loading, searchState.failed) { shownSearch ->
                Refreshable(shownSearch.loading, { searchQuery(searchState.query) }) {
                SearchScreen(shownSearch, fetcher, searchQuery, { artistId = it.id }, { openAlbum(it.id, it.artworkId, coverKnown = true) },
                    { song ->
                        if (!preparing) preparationJob = scope.launch(Dispatchers.Main.immediate) {
                            preparing = true; preparationFailed = false
                            try {
                                // Offline: the result's album from the phone (no request); Play then takes the tracks here.
                                val queue = if (phoneActionsNow()) phoneLibrary.albumPage(song.albumId.orEmpty())?.songs
                                    ?.let { songs -> dev.coda.android.ui.library.SearchPlayback(songs, songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)) }
                                    ?: dev.coda.android.ui.library.SearchPlayback(listOf(song), 0)
                                    else resolveSearchPlayback(session.client, song, ::saveAlbumPage) { albumId -> kotlinx.coroutines.withContext(Dispatchers.IO) { snapshots.album(albumId) } }
                                ensureActive()
                                if (sessions.isCurrent(session.token)) playSongs(queue.songs, queue.startIndex)
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (_: SubsonicError.Unauthorized) { sessions.unauthorized(session.token) }
                            catch (_: Exception) { preparationFailed = true }
                            finally { preparing = false }
                        }
                    }, pageAccent.value, dev.coda.android.ui.library.TabBackdrop(calmLayer))
                }
                }
            }
            else -> {
                // The backdrop is the shared layer above; with nothing queued (the brand theme), Home stays plain base.
                val reloadHome = {
                    home.refresh(); controller?.sendCustomCommand(androidx.media3.session.SessionCommand(dev.coda.android.playback.HANDOFF_REFRESH, android.os.Bundle.EMPTY), android.os.Bundle.EMPTY)
                    Unit
                }
                // Offline, Home from the phone (shelves on the catalogue, Continue Listening where the next track is here,
                // no handoff offer); back online it stays until the live shelves are in.
                val fromPhone = dev.coda.android.ui.offline.rememberFromPhone(phoneMode, homeState.answers, { homeState.loading }) { home.refresh() }
                val liveHome = remember(homeState, handoffOffer) { homeState.copy(handoff = handoffOffer) }
                val phoneHome = remember(fromPhone, homeState.continueListening, homeState.resumeProgress, phoneLibrary.revision) {
                    if (fromPhone) phoneLibrary.home(homeState) else null }
                dev.coda.android.ui.offline.PhoneOrLive(fromPhone, phoneHome, liveHome, phoneLibrary.onPhone, homeState.loading, homeState.failed) { shownHome ->
                Refreshable(shownHome.loading, reloadHome) {
                HomeScreen(shownHome, fetcher, reloadHome,
                    resumeHandoff = { identity -> controller?.sendCustomCommand(androidx.media3.session.SessionCommand(dev.coda.android.playback.HANDOFF_CONTINUE, android.os.Bundle.EMPTY), android.os.Bundle().apply { putString("identity", identity) }) },
                    dismissHandoff = { identity -> controller?.sendCustomCommand(androidx.media3.session.SessionCommand(dev.coda.android.playback.HANDOFF_DISMISS, android.os.Bundle.EMPTY), android.os.Bundle().apply { putString("identity", identity) }) },
                    undoDismissHandoff = { identity -> controller?.sendCustomCommand(androidx.media3.session.SessionCommand(dev.coda.android.playback.HANDOFF_UNDO_DISMISS, android.os.Bundle.EMPTY), android.os.Bundle().apply { putString("identity", identity) }) },
                    handoffKeepsMine = handoffKeepsMine,
                    openSettings = { settingsOpen = true }, openPlaylist = { playlistId = it.id },
                    openList = { list -> if (list == HomeList.PLAYLISTS) { tab = 3 } else if (list.type != null) { selectAlbumSort(AlbumSort.from(list.type)); tab = 1 } }) { openAlbum(it.id, it.artworkId, coverKnown = true) }
                }
                }
            }
            }
            }
            }
            }
            }
            }
            dev.coda.android.ui.theme.AccentTheme(pageAccent) {
                val menuAccent = pageAccent.value
                dev.coda.android.ui.menu.ItemMenuHost(menus, menuActions, fetcher,
                    dev.coda.android.ui.menu.MenuColors(menuAccent.fill, menuAccent.onFill, menuAccent.text), menuSnackbar)
                dev.coda.android.ui.common.CodaSnackbarHost(menuSnackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = dev.coda.android.ui.shell.LocalDockPadding.current))
            }
            // Kept music removed for space. Showing the snackbar is delivery only (snackbarShown); the record
            // stays on Music storage until OK there.
            val offline by app.offlineLibrary.collectAsState()
            val removedForSpace = offline?.removedForSpace?.collectAsState()?.value
            // Keyed by the removal date: showing it updates snackbarAt, which must not restart (and cancel) this effect.
            val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
            LaunchedEffect(removedForSpace?.at, offline, lifecycle) {
                val library = offline ?: return@LaunchedEffect
                if (removedForSpace == null) return@LaunchedEffect
                // "The next time Coda is in the foreground" (§3.1): every time the app comes to the front the record is read
                // again and checked (a removal while away, the 24 h suppression over). Leaving the front cancels a snackbar
                // still queued behind another, and nothing is marked delivered until the snackbar is really on screen.
                lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                    val notice = library.removedForSpace.value?.takeIf { it.snackbarDue(System.currentTimeMillis()) } ?: return@repeatOnLifecycle
                    val count = notice.titles.size
                    val message = "Removed $count ${if (count == 1) "album" else "albums"} you preferred offline to keep " +
                        "${app.cacheSettings.policy.value.keepFreeBytes / 1_000_000_000L} GB free"
                    val result = coroutineScope {
                        val delivered = launch {
                            androidx.compose.runtime.snapshotFlow { menuSnackbar.currentSnackbarData?.visuals?.message }.first { it == message }
                            library.snackbarShown()
                        }
                        menuSnackbar.showNotice(message, NoticeAction.Details).also { delivered.cancel() }
                    }
                    if (result == SnackbarResult.ActionPerformed) { storageRequested = true; settingsOpen = true }
                }
            }
            if (preparing) LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth())
            if (preparationFailed) Surface(Modifier.align(Alignment.BottomCenter)) { Text("Couldn't prepare playback", Modifier.padding(16.dp)) }
        }
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = dev.coda.android.ui.shell.LocalDockPadding.current)) { banner() }

    }
}
}
}
}

}
