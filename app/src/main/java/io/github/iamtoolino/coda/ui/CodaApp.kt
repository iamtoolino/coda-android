@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.iamtoolino.coda.ui

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.github.iamtoolino.coda.AppGraph
import io.github.iamtoolino.coda.CodaApplication
import io.github.iamtoolino.coda.data.Album
import io.github.iamtoolino.coda.data.AlbumListType
import io.github.iamtoolino.coda.data.AlbumPage
import io.github.iamtoolino.coda.data.Artist
import io.github.iamtoolino.coda.data.NavidromeClient
import io.github.iamtoolino.coda.data.PlayQueue
import io.github.iamtoolino.coda.data.Playlist
import io.github.iamtoolino.coda.data.SearchResult
import io.github.iamtoolino.coda.data.Song
import io.github.iamtoolino.coda.player.PlaybackConnection
import io.github.iamtoolino.coda.player.PlaybackUiState
import io.github.iamtoolino.coda.player.QueueEntry
import io.github.iamtoolino.coda.ui.theme.AdaptiveBackground
import io.github.iamtoolino.coda.ui.theme.CodaThemeRequest
import io.github.iamtoolino.coda.ui.theme.CodaThemeRouter
import io.github.iamtoolino.coda.ui.theme.RegisterForegroundTheme
import io.github.iamtoolino.coda.ui.theme.RoutedCodaTheme
import io.github.iamtoolino.coda.ui.theme.rememberCodaThemeRouter
import io.github.iamtoolino.coda.ui.theme.resolveThemeRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val OverlayButtonBackground = Color.Black.copy(alpha = 0.46f)

private fun accountCacheKey(value: String): String = "${AppGraph.cacheNamespace}:$value"

private enum class AlbumViewMode(
    val routeValue: String,
    val title: String,
    val listType: AlbumListType,
) {
    RECENTLY_ADDED("added", "Recently added", AlbumListType.NEWEST),
    RECENT_RELEASES("released", "Recent releases", AlbumListType.RELEASE_YEAR),
    MOST_PLAYED("played", "Most played", AlbumListType.MOST_PLAYED),
    RECENTLY_PLAYED("recent", "Recently played", AlbumListType.RECENTLY_PLAYED),
    ALPHABETICAL("az", "Albums A–Z", AlbumListType.ALPHABETICAL),
    ;

    companion object {
        fun fromRoute(value: String?): AlbumViewMode = entries.firstOrNull {
            it.routeValue == value
        } ?: RECENTLY_ADDED
    }
}

private fun heroFadeBrush(background: Color): Brush = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.14f),
    0.46f to Color.Transparent,
    0.68f to background.copy(alpha = 0f),
    0.72f to background.copy(alpha = 0.04f),
    0.76f to background.copy(alpha = 0.12f),
    0.80f to background.copy(alpha = 0.21f),
    0.84f to background.copy(alpha = 0.33f),
    0.88f to background.copy(alpha = 0.47f),
    0.92f to background.copy(alpha = 0.62f),
    0.96f to background.copy(alpha = 0.80f),
    1f to background,
)

@Composable
private fun rememberRestorableLazyListState(
    contentReady: Boolean,
    maxIndex: Int,
): LazyListState {
    var savedIndex by rememberSaveable { mutableIntStateOf(0) }
    var savedOffset by rememberSaveable { mutableIntStateOf(0) }
    val state = rememberLazyListState()
    LaunchedEffect(contentReady, maxIndex) {
        if (!contentReady) return@LaunchedEffect
        state.scrollToItem(savedIndex.coerceIn(0, maxIndex.coerceAtLeast(0)), savedOffset)
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                savedIndex = index
                savedOffset = offset
            }
    }
    return state
}

@Composable
private fun rememberRestorableLazyGridState(
    contentReady: Boolean,
    maxIndex: Int,
    stateKey: String = "default",
): LazyGridState {
    var savedIndex by rememberSaveable(stateKey) { mutableIntStateOf(0) }
    var savedOffset by rememberSaveable(stateKey) { mutableIntStateOf(0) }
    val state = remember(stateKey) { LazyGridState() }
    LaunchedEffect(contentReady, maxIndex) {
        if (!contentReady) return@LaunchedEffect
        state.scrollToItem(savedIndex.coerceIn(0, maxIndex.coerceAtLeast(0)), savedOffset)
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                savedIndex = index
                savedOffset = offset
            }
    }
    return state
}

@Composable
fun CodaApp() {
    val application = LocalContext.current.applicationContext as CodaApplication
    val playback = remember(application) { application.playback }
    val playbackState by playback.state.collectAsStateWithLifecycle()
    val credentials by AppGraph.credentials.collectAsStateWithLifecycle()
    if (credentials == null) {
        RoutedCodaTheme(CodaThemeRequest.Brand) {
            AdaptiveBackground { LoginScreen() }
        }
        return
    }
    val activeCredentials = requireNotNull(credentials)
    val ratingScope = rememberCoroutineScope()
    val ratings = remember(activeCredentials, ratingScope) {
        AlbumRatingCoordinator(ratingScope) { albumId, rating ->
            AppGraph.withCurrentSession { setAlbumRating(albumId, rating) }
        }
    }
    DisposableEffect(ratings) { onDispose(ratings::close) }
    LaunchedEffect(ratings, application) {
        ratings.failures.collect {
            Toast.makeText(application, "Could not update album rating", Toast.LENGTH_SHORT).show()
        }
    }
    val themeRouter = rememberCodaThemeRouter()
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route.orEmpty()
    val fullScreenPlayer = route == "now-playing" || route == "queue"
    val playbackThemeRequest = playbackState.artworkUrl?.let { artworkUrl ->
        CodaThemeRequest.Artwork(
            identity = "album:${playbackState.artworkKey ?: playbackState.currentSongId}",
            artworkUrl = artworkUrl,
        )
    }
    val themeRequest = resolveThemeRequest(
        foreground = themeRouter.foregroundRequest,
        playback = playbackThemeRequest,
    )
    RoutedCodaTheme(themeRequest) {
        CompositionLocalProvider(LocalAlbumRatingCoordinator provides ratings) {
            AdaptiveBackground {
                Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    if (!fullScreenPlayer && playbackState.currentSongId != null) {
                        MiniPlayer(
                            state = playbackState,
                            playback = playback,
                            onOpen = { navController.navigate("now-playing") },
                            onToggle = playback::togglePlayPause,
                        )
                    }
                },
            ) { padding ->
                NavHost(
                    navController = navController,
                    startDestination = "home",
                    modifier = Modifier.padding(padding),
                ) {
                    composable("home") { HomeScreen(navController, playback) }
                    composable("artists") { ArtistsScreen(navController) }
                    composable("albums") {
                        AlbumsScreen(navController, AlbumViewMode.RECENTLY_ADDED)
                    }
                    composable(
                        route = "albums/{mode}",
                        arguments = listOf(navArgument("mode") { type = NavType.StringType }),
                    ) {
                        AlbumsScreen(
                            navController,
                            AlbumViewMode.fromRoute(it.arguments?.getString("mode")),
                        )
                    }
                    composable("playlists") { PlaylistsScreen(navController) }
                    composable("search") { SearchScreen(navController, playback, playbackState) }
                    composable("connection") {
                        ConnectionScreen(
                            credentials = activeCredentials,
                            onBack = { navController.popBackStack() },
                            onDisconnect = {
                                val oldNamespace = AppGraph.cacheNamespace
                                AppGraph.logout()
                                playback.disconnect(oldNamespace)
                                application.clearArtworkCaches(oldNamespace)
                            },
                        )
                    }
                    composable(
                        route = "artist/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.StringType }),
                    ) { ArtistScreen(navController, Uri.decode(it.arguments?.getString("id").orEmpty())) }
                    composable(
                        route = "album/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.StringType }),
                    ) { backStackEntry ->
                        AlbumScreen(
                            navController = navController,
                            id = Uri.decode(backStackEntry.arguments?.getString("id").orEmpty()),
                            playback = playback,
                            playbackState = playbackState,
                            themeRouter = themeRouter,
                            themeOwner = backStackEntry.id,
                        )
                    }
                    composable(
                        route = "playlist/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.StringType }),
                    ) { backStackEntry ->
                        PlaylistScreen(
                            navController = navController,
                            id = Uri.decode(backStackEntry.arguments?.getString("id").orEmpty()),
                            playback = playback,
                            playbackState = playbackState,
                            themeRouter = themeRouter,
                            themeOwner = backStackEntry.id,
                        )
                    }
                    composable("now-playing") {
                        NowPlayingScreen(navController, playback, playbackState)
                    }
                    composable("queue") {
                        QueueScreen(navController, playback, playbackState)
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(navController: NavHostController, playback: PlaybackConnection) {
    val scope = rememberCoroutineScope()
    val home = remember(scope) { HomeCoordinator(scope) }
    val handoffQueue by playback.handoffQueue.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val listState = rememberRestorableLazyListState(
        contentReady = true,
        maxIndex = 6,
    )

    LaunchedEffect(home) {
        home.refreshAll()
        playback.refreshHandoffQueue()
    }
    DisposableEffect(playback) {
        playback.setHomeVisible(true)
        onDispose { playback.setHomeVisible(false) }
    }
    DisposableEffect(lifecycleOwner, playback) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) playback.refreshHandoffQueue()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    PullToRefreshBox(
        isRefreshing = home.isRefreshing,
        onRefresh = {
            home.refreshAll()
            playback.refreshHandoffQueue()
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                ScreenHeader(
                    title = "Coda",
                    onSearch = { navController.navigate("search") },
                    trailing = {
                        IconButton(onClick = { navController.navigate("connection") }) {
                            Icon(Icons.Default.AccountCircle, "Connection")
                        }
                    },
                )
            }
            handoffQueue?.takeIf { it.entry.isNotEmpty() }?.let { queue ->
                item(key = "continue") { ContinueCard(queue) { playback.restore(queue) } }
            }
            item(key = "artists") {
                HomeRemoteSection(
                    title = "Artists",
                    state = home.artists,
                    onMore = { navController.navigate("artists") },
                    onRetry = { home.retry(HomeSection.ARTISTS) },
                ) { artists ->
                    ArtistShelf(
                        title = "Artists",
                        artists = artists,
                        onArtist = {
                            navController.navigate("artist/${Uri.encode(it.id)}")
                        },
                        onMore = { navController.navigate("artists") },
                    )
                }
            }
            item(key = "newest") {
                HomeRemoteSection(
                    title = "Recently added albums",
                    state = home.newest,
                    onMore = {
                        navController.navigate(
                            "albums/${AlbumViewMode.RECENTLY_ADDED.routeValue}",
                        )
                    },
                    onRetry = { home.retry(HomeSection.NEWEST) },
                ) { albums ->
                    AlbumShelf(
                        title = "Recently added albums",
                        albums = albums,
                        onAlbum = { navController.navigate("album/${Uri.encode(it.id)}") },
                        onMore = {
                            navController.navigate(
                                "albums/${AlbumViewMode.RECENTLY_ADDED.routeValue}",
                            )
                        },
                    )
                }
            }
            item(key = "recent-releases") {
                HomeRemoteSection(
                    title = "Recent releases",
                    state = home.recentReleases,
                    onMore = {
                        navController.navigate(
                            "albums/${AlbumViewMode.RECENT_RELEASES.routeValue}",
                        )
                    },
                    onRetry = { home.retry(HomeSection.RECENT_RELEASES) },
                ) { albums ->
                    AlbumShelf(
                        title = "Recent releases",
                        albums = albums,
                        onAlbum = { navController.navigate("album/${Uri.encode(it.id)}") },
                        onMore = {
                            navController.navigate(
                                "albums/${AlbumViewMode.RECENT_RELEASES.routeValue}",
                            )
                        },
                    )
                }
            }
            item(key = "recently-played") {
                HomeRemoteSection(
                    title = "Recently played",
                    state = home.recentlyPlayed,
                    onMore = {
                        navController.navigate(
                            "albums/${AlbumViewMode.RECENTLY_PLAYED.routeValue}",
                        )
                    },
                    onRetry = { home.retry(HomeSection.RECENTLY_PLAYED) },
                ) { albums ->
                    AlbumShelf(
                        title = "Recently played",
                        albums = albums,
                        onAlbum = { navController.navigate("album/${Uri.encode(it.id)}") },
                        onMore = {
                            navController.navigate(
                                "albums/${AlbumViewMode.RECENTLY_PLAYED.routeValue}",
                            )
                        },
                    )
                }
            }
            item(key = "playlists") {
                HomeRemoteSection(
                    title = "Playlists",
                    state = home.playlists,
                    onMore = { navController.navigate("playlists") },
                    onRetry = { home.retry(HomeSection.PLAYLISTS) },
                ) { playlists ->
                    Column {
                        HomeSectionHeader(
                            title = "Playlists",
                            onClick = { navController.navigate("playlists") },
                        )
                        playlists.forEach { playlist ->
                            PlaylistRow(playlist) {
                                navController.navigate("playlist/${Uri.encode(playlist.id)}")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> HomeRemoteSection(
    title: String,
    state: HomeSectionState<T>,
    onMore: (() -> Unit)?,
    onRetry: () -> Unit,
    content: @Composable (T) -> Unit,
) {
    val value = state.value
    if (value == null) {
        Column {
            HomeSectionHeader(title, onMore)
            HomeSectionStatus(title, state, hasContent = false, onRetry)
        }
    } else {
        Column {
            content(value)
            HomeSectionStatus(title, state, hasContent = true, onRetry)
        }
    }
}

@Composable
private fun <T> HomeSectionStatus(
    title: String,
    state: HomeSectionState<T>,
    hasContent: Boolean,
    onRetry: () -> Unit,
) {
    when {
        state.isLoading && hasContent -> LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
        state.isLoading -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(112.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 3.dp)
        }
        state.errorMessage != null -> Row(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Could not load $title", fontWeight = FontWeight.Bold)
                Text(
                    state.errorMessage,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRetry) {
                Icon(Icons.Default.Refresh, "Retry $title")
            }
        }
    }
}

@Composable
private fun HomeSectionHeader(title: String, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 27.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (onClick != null) Icon(Icons.Default.ChevronRight, "See all")
    }
}

@Composable
private fun ArtistsScreen(navController: NavHostController) {
    var activeLetter by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val coordinator = remember(scope) {
        RemoteCollectionCoordinator(scope, Unit) {
            AppGraph.withCurrentSession { artists() }
        }
    }
    val state = coordinator.state
    val artists = state.value
    val listState = rememberRestorableLazyListState(
        contentReady = artists != null || state.errorMessage != null,
        maxIndex = artists?.size ?: 0,
    )
    LaunchedEffect(coordinator) { coordinator.load() }
    PullToRefreshBox(
        isRefreshing = state.isLoading && artists != null,
        onRefresh = { coordinator.refresh() },
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 28.dp),
            ) {
                item {
                    ScreenHeader(
                        title = "Artists",
                    )
                }
                if (artists == null && state.errorMessage == null) item { LoadingBlock() }
                state.errorMessage?.let { error ->
                    item { MessageCard("Could not load Artists", error) }
                }
                artists?.let { list ->
                    items(list, key = { it.id }) { artist ->
                        ArtistRow(artist) {
                            navController.navigate("artist/${Uri.encode(artist.id)}")
                        }
                    }
                }
            }
            artists?.takeIf { it.isNotEmpty() }?.let { list ->
                AlphabetFastScroller(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .statusBarsPadding()
                        .padding(top = 64.dp, bottom = 16.dp),
                    activeLetter = activeLetter,
                    onActiveLetterChange = { activeLetter = it },
                    onLetter = { letter ->
                        val targetIndex = artistIndexForLetter(list, letter)
                        if (targetIndex >= 0) {
                            scope.launch { listState.scrollToItem(targetIndex + 1) }
                        }
                    },
                )
                val visibleArtistIndex = (listState.firstVisibleItemIndex - 1).coerceAtLeast(0)
                val visibleLetter = list.getOrNull(visibleArtistIndex)?.let { artistInitial(it.name) }
                val displayedLetter = activeLetter
                    ?: visibleLetter.takeIf { listState.isScrollInProgress }
                displayedLetter?.let {
                    LetterBubble(it, Modifier.align(Alignment.Center))
                }
            }
        }
    }
}

private fun artistInitial(name: String): String {
    val initial = name.trim().firstOrNull()?.uppercaseChar() ?: return "#"
    return if (initial in 'A'..'Z') initial.toString() else "#"
}

private fun artistIndexForLetter(artists: List<Artist>, requested: String): Int {
    val alphabet = listOf("#") + ('A'..'Z').map(Char::toString)
    val requestedIndex = alphabet.indexOf(requested).coerceAtLeast(0)
    return artists.indexOfFirst { artist ->
        alphabet.indexOf(artistInitial(artist.name)).coerceAtLeast(0) >= requestedIndex
    }.takeIf { it >= 0 } ?: artists.lastIndex
}

@Composable
private fun AlphabetFastScroller(
    modifier: Modifier = Modifier,
    activeLetter: String?,
    onActiveLetterChange: (String?) -> Unit,
    onLetter: (String) -> Unit,
) {
    val letters = remember { listOf("#") + ('A'..'Z').map(Char::toString) }
    fun selectLetter(y: Float, height: Int) {
        if (height <= 0) return
        val index = ((y / height) * letters.size).toInt().coerceIn(letters.indices)
        val letter = letters[index]
        onActiveLetterChange(letter)
        onLetter(letter)
    }
    Column(
        modifier = modifier
            .width(28.dp)
            .fillMaxHeight()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    selectLetter(down.position.y, size.height)
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.pressed) {
                            selectLetter(change.position.y, size.height)
                            change.consume()
                        }
                    } while (change.pressed)
                    onActiveLetterChange(null)
                }
            },
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        letters.forEach { letter ->
            Text(
                letter,
                color = if (letter == activeLetter) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontSize = 9.sp,
                fontWeight = if (letter == activeLetter) FontWeight.Bold else FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun LetterBubble(letter: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(82.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            letter,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontSize = 38.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun AlbumsScreen(
    navController: NavHostController,
    initialMode: AlbumViewMode,
) {
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    val scope = rememberCoroutineScope()
    val coordinator = remember(scope) {
        RemoteCollectionCoordinator(scope, initialMode) { requestedMode ->
            AppGraph.withCurrentSession { allAlbums(requestedMode.listType) }
        }
    }
    val state = coordinator.state
    val albums = state.value.takeIf { state.key == mode }
    val error = state.errorMessage.takeIf { state.key == mode }
    val gridState = rememberRestorableLazyGridState(
        contentReady = albums != null || error != null,
        maxIndex = albums?.size ?: 0,
        stateKey = mode.routeValue,
    )
    LaunchedEffect(coordinator, mode) { coordinator.load(mode) }
    PullToRefreshBox(
        isRefreshing = state.isLoading && albums != null,
        onRefresh = { coordinator.load(mode) },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ScreenHeader(
                    title = mode.title,
                    horizontalPadding = 0.dp,
                    trailing = {
                        AlbumSortMenu(
                            selected = mode,
                            onSelected = { mode = it },
                        )
                    },
                )
            }
            if (albums == null && error == null) {
                item(span = { GridItemSpan(maxLineSpan) }) { LoadingBlock() }
            }
            if (error != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    MessageCard("Could not load albums", error.orEmpty())
                }
            }
            albums?.let { list ->
                items(list, key = { it.id }) { album ->
                    AlbumCard(album) { navController.navigate("album/${Uri.encode(album.id)}") }
                }
            }
        }
    }
}

@Composable
private fun AlbumSortMenu(
    selected: AlbumViewMode,
    onSelected: (AlbumViewMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, "Sort albums")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            AlbumViewMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Text(
                            mode.title,
                            fontWeight = if (mode == selected) {
                                FontWeight.Bold
                            } else {
                                FontWeight.Normal
                            },
                        )
                    },
                    onClick = {
                        expanded = false
                        if (mode != selected) onSelected(mode)
                    },
                )
            }
        }
    }
}

@Composable
private fun PlaylistsScreen(navController: NavHostController) {
    RemoteListScreen(
        title = "Playlists",
        onBack = { navController.popBackStack() },
        loader = { playlists() },
    ) { playlists ->
        items(playlists, key = { it.id }) { playlist ->
            PlaylistRow(playlist) {
                navController.navigate("playlist/${Uri.encode(playlist.id)}")
            }
        }
    }
}

@Composable
private fun PlaylistRow(playlist: Playlist, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (playlist.coverArt != null) {
                Artwork(
                    url = AppGraph.navidrome.coverArtUrl(playlist.coverArt, size = 240),
                    description = playlist.name,
                    cacheKey = "playlist:${playlist.id}:${playlist.coverArt}:240",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, null)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(playlist.name, style = MaterialTheme.typography.titleMedium)
            Text(
                "${playlist.songCount} tracks",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        }
        Icon(Icons.Default.ChevronRight, null)
    }
}

@Composable
private fun SearchScreen(
    navController: NavHostController,
    playback: PlaybackConnection,
    playbackState: PlaybackUiState,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    val scope = rememberCoroutineScope()
    val coordinator = remember(scope) {
        SearchCoordinator(scope) { requestedQuery ->
            AppGraph.withCurrentSession { search(requestedQuery) }
        }
    }
    val state = coordinator.state
    val queryText = query.text
    val result = state.result ?: SearchResult()
    val resultItemCount = 1 +
        result.artist.size + (if (result.artist.isNotEmpty()) 1 else 0) +
        (if (result.album.isNotEmpty()) 1 else 0) +
        result.song.size + (if (result.song.isNotEmpty()) 1 else 0)
    val listState = rememberRestorableLazyListState(
        contentReady = queryText.length < 2 ||
            (state.loadedQuery == queryText && !state.isLoading) ||
            state.errorMessage != null,
        maxIndex = (resultItemCount - 1).coerceAtLeast(0),
    )

    LaunchedEffect(Unit) {
        query = query.copy(selection = TextRange(query.text.length))
        focusRequester.requestFocus()
        delay(100)
        keyboardController?.show()
    }

    LaunchedEffect(coordinator, queryText) { coordinator.updateQuery(queryText) }

    PullToRefreshBox(
        isRefreshing = state.isLoading && queryText == state.loadedQuery,
        onRefresh = { coordinator.refresh() },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item {
                Column(
                    Modifier
                        .statusBarsPadding()
                        .padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                        Text("Search", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        placeholder = { Text("Albums, artists or tracks") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    )
                }
            }
            if (state.isLoading && result == SearchResult()) item { LoadingBlock() }
            state.errorMessage?.let { error -> item { MessageCard("Search failed", error) } }
            if (result.artist.isNotEmpty()) {
                item { SectionTitle("Artists") }
                items(result.artist, key = { "artist-${it.id}" }) { artist ->
                    ArtistRow(artist) { navController.navigate("artist/${Uri.encode(artist.id)}") }
                }
            }
            if (result.album.isNotEmpty()) {
                item {
                    AlbumShelf(
                        title = "Albums",
                        albums = result.album,
                        onAlbum = { navController.navigate("album/${Uri.encode(it.id)}") },
                    )
                }
            }
            if (result.song.isNotEmpty()) {
                item { SectionTitle("Tracks") }
                items(result.song, key = { "song-${it.id}" }) { song ->
                    SongRow(
                        song,
                        isPlaying = playbackState.currentSongId == song.id,
                        onClick = { playback.playSongs(listOf(song)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtistScreen(navController: NavHostController, id: String) {
    val scope = rememberCoroutineScope()
    val coordinator = remember(scope) {
        RemoteDetailCoordinator(scope, id) { requestedId ->
            AppGraph.withCurrentSession { artistAlbums(requestedId) }
        }
    }
    val state = coordinator.state
    val data = state.value.takeIf { state.key == id }
    val error = state.errorMessage.takeIf { state.key == id }
    val albumRows = data?.second?.chunked(3).orEmpty()
    val listState = rememberRestorableLazyListState(
        contentReady = data != null || error != null,
        maxIndex = albumRows.size + 1,
    )
    LaunchedEffect(coordinator, id) { coordinator.load(id) }
    AdaptiveBackground {
            PullToRefreshBox(
                isRefreshing = state.isLoading && data != null,
                onRefresh = { coordinator.load(id) },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item {
                        data?.let { (loadedArtist, albums) ->
                            ArtistHero(
                                loadedArtist,
                                albums,
                            )
                        } ?: Spacer(Modifier.statusBarsPadding().height(16.dp))
                    }
                    if (data == null && error == null) item { LoadingBlock() }
                    if (error != null) item {
                        MessageCard("Could not load artist", error.orEmpty())
                    }
                    data?.let { (_, albums) ->
                        item {
                            Text(
                                "Albums",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        items(
                            items = albumRows,
                            key = { row -> row.joinToString(separator = "|") { it.id } },
                        ) { rowAlbums ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                rowAlbums.forEach { album ->
                                    AlbumCard(
                                        album = album,
                                        modifier = Modifier.weight(1f),
                                        subtitle = album.releaseYearLabel(),
                                    ) {
                                        navController.navigate("album/${Uri.encode(album.id)}")
                                    }
                                }
                                repeat(3 - rowAlbums.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
    }
}

@Composable
private fun AlbumScreen(
    navController: NavHostController,
    id: String,
    playback: PlaybackConnection,
    playbackState: PlaybackUiState,
    themeRouter: CodaThemeRouter,
    themeOwner: String,
) {
    val context = LocalContext.current
    val ratings = LocalAlbumRatingCoordinator.current
    var page by remember(id) { mutableStateOf<AlbumPage?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var generation by rememberSaveable(id) { mutableIntStateOf(0) }
    var loading by remember(id) { mutableStateOf(false) }
    val listState = rememberRestorableLazyListState(
        contentReady = page != null || error != null,
        maxIndex = page?.songs?.size ?: 0,
    )
    LaunchedEffect(id, generation) {
        loading = true
        runCatching { AppGraph.withCurrentSession { album(id) } }
            .onSuccess { page = it; error = null }
            .onFailureUnlessCancelled { error = it.message }
        loading = false
    }
    val album = page?.album
    val coverKey = album?.coverArt ?: album?.id
    val artworkUrl = coverKey?.let { AppGraph.navidrome.coverArtUrl(it, size = 1_200) }
    val themeRequest = when {
        page == null && error == null -> CodaThemeRequest.Pending
        album == null || artworkUrl == null -> CodaThemeRequest.Brand
        else -> CodaThemeRequest.Artwork(
            identity = "album:${album.id}",
            artworkUrl = artworkUrl,
        )
    }
    RegisterForegroundTheme(themeRouter, themeOwner, themeRequest)
    AdaptiveBackground {
            PullToRefreshBox(
                isRefreshing = loading && page != null,
                onRefresh = { generation++ },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    if (page == null) {
                        item { Spacer(Modifier.statusBarsPadding().height(16.dp)) }
                    }
                    if (page == null && error == null) item { LoadingBlock() }
                    if (error != null) item { MessageCard("Could not load album", error.orEmpty()) }
                    page?.let { loaded ->
                        item {
                            val ratingState = ratings.state(
                                loaded.album.id,
                                loaded.album.userRating,
                            )
                            AlbumHero(
                                page = loaded,
                                onArtist = loaded.album.artistId?.let { artistId ->
                                    {
                                        navController.navigate(
                                            "artist/${Uri.encode(artistId)}",
                                        )
                                    }
                                },
                                onPlay = { playback.playSongs(loaded.songs) },
                                onAppend = {
                                    playback.appendSongs(loaded.songs)
                                    Toast.makeText(
                                        context,
                                        "Album appended to queue",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                                rating = ratingState.rating,
                                onRate = { selectedRating ->
                                    ratings.select(
                                        albumId = loaded.album.id,
                                        serverRating = loaded.album.userRating,
                                        selectedRating = selectedRating,
                                    )
                                },
                            )
                        }
                        itemsIndexed(loaded.songs, key = { _, song -> song.id }) { index, song ->
                            SongRow(
                                song,
                                isPlaying = playbackState.currentSongId == song.id,
                                showArtist = false,
                                onClick = { playback.playSongs(loaded.songs, index) },
                            )
                        }
                    }
                }
            }
    }
}

@Composable
private fun PlaylistScreen(
    navController: NavHostController,
    id: String,
    playback: PlaybackConnection,
    playbackState: PlaybackUiState,
    themeRouter: CodaThemeRouter,
    themeOwner: String,
) {
    val context = LocalContext.current
    var playlist by remember(id) { mutableStateOf<Playlist?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var generation by rememberSaveable(id) { mutableIntStateOf(0) }
    var loading by remember(id) { mutableStateOf(false) }
    val groups = remember(playlist?.entry) { playlistAlbumGroups(playlist?.entry.orEmpty()) }
    val listState = rememberRestorableLazyListState(
        contentReady = playlist != null || error != null,
        maxIndex = groups.sumOf { it.songs.size + 1 } + 1,
    )
    LaunchedEffect(id, generation) {
        loading = true
        runCatching { AppGraph.withCurrentSession { playlist(id) } }
            .onSuccess { playlist = it; error = null }
            .onFailureUnlessCancelled { error = it.message }
        loading = false
    }
    val playlistCoverKey = playlist?.coverArt
    val playlistArtworkUrl = playlistCoverKey?.let {
        AppGraph.navidrome.coverArtUrl(it, size = 1_200)
    }
    val themeRequest = when {
        playlist == null && error == null -> CodaThemeRequest.Pending
        playlistCoverKey == null || playlistArtworkUrl == null ->
            CodaThemeRequest.InheritPlayback
        else -> CodaThemeRequest.Artwork(
            identity = "playlist:$id:$playlistCoverKey",
            artworkUrl = playlistArtworkUrl,
        )
    }
    RegisterForegroundTheme(themeRouter, themeOwner, themeRequest)
    AdaptiveBackground {
            PullToRefreshBox(
                isRefreshing = loading && playlist != null,
                onRefresh = { generation++ },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    item { DetailHeader(playlist?.name ?: "Playlist", navController) { generation++ } }
                    if (playlist == null && error == null) item { LoadingBlock() }
                    if (error != null) item { MessageCard("Could not load playlist", error.orEmpty()) }
                    playlist?.let { loaded ->
                        item {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(loaded.name, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    "${loaded.songCount} tracks",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(14.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    RoundActionButton(
                                        icon = Icons.Default.PlayArrow,
                                        description = "Play playlist",
                                        primary = true,
                                        onClick = { playback.playSongs(loaded.entry) },
                                    )
                                    RoundActionButton(
                                        icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                                        description = "Append playlist to queue",
                                        onClick = {
                                            playback.appendSongs(loaded.entry)
                                            Toast.makeText(
                                                context,
                                                "Playlist appended to queue",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        },
                                    )
                                }
                            }
                        }
                        groups.forEachIndexed { groupIndex, group ->
                            item(key = "album-${group.key}-$groupIndex") {
                                PlaylistAlbumHeader(group.songs.map { it.value })
                            }
                            group.songs.forEach { indexedSong ->
                                val song = indexedSong.value
                                item(key = "song-${song.id}-${indexedSong.index}") {
                                    SongRow(
                                        song,
                                        isPlaying = playbackState.currentSongId == song.id,
                                        showArtist = false,
                                        onClick = {
                                            playback.playSongs(loaded.entry, indexedSong.index)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
    }
}

private data class PlaylistAlbumGroup(
    val key: String,
    val songs: List<IndexedValue<Song>>,
)

private fun playlistAlbumGroups(songs: List<Song>): List<PlaylistAlbumGroup> {
    if (songs.isEmpty()) return emptyList()
    val groups = mutableListOf<PlaylistAlbumGroup>()
    var currentKey = songs.first().albumId ?: songs.first().album
    var currentSongs = mutableListOf(IndexedValue(0, songs.first()))
    songs.drop(1).forEachIndexed { offset, song ->
        val index = offset + 1
        val key = song.albumId ?: song.album
        if (key == currentKey) {
            currentSongs += IndexedValue(index, song)
        } else {
            groups += PlaylistAlbumGroup(currentKey, currentSongs)
            currentKey = key
            currentSongs = mutableListOf(IndexedValue(index, song))
        }
    }
    groups += PlaylistAlbumGroup(currentKey, currentSongs)
    return groups
}

@Composable
private fun PlaylistAlbumHeader(songs: List<Song>) {
    val first = songs.firstOrNull() ?: return
    val coverKey = first.albumId ?: first.coverArt
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            url = coverKey?.let { AppGraph.navidrome.coverArtUrl(it, size = 360) },
            description = first.album,
            cacheKey = "playlist-album:${coverKey ?: first.album}:360",
            modifier = Modifier
                .size(82.dp)
                .clip(RoundedCornerShape(13.dp)),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                first.album.ifBlank { "Unknown album" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                first.artist,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${songs.size} ${if (songs.size == 1) "track" else "tracks"} • " +
                    formatCollectionDuration(songs.sumOf { it.duration }),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun <T> RemoteListScreen(
    title: String,
    onBack: (() -> Unit)? = null,
    loader: suspend NavidromeClient.() -> List<T>,
    content: androidx.compose.foundation.lazy.LazyListScope.(List<T>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val coordinator = remember(scope) {
        RemoteCollectionCoordinator(scope, Unit) {
            AppGraph.withCurrentSession(loader)
        }
    }
    val state = coordinator.state
    val data = state.value
    val listState = rememberRestorableLazyListState(
        contentReady = data != null || state.errorMessage != null,
        maxIndex = data?.size ?: 0,
    )
    LaunchedEffect(coordinator) { coordinator.load() }
    PullToRefreshBox(
        isRefreshing = state.isLoading && data != null,
        onRefresh = { coordinator.refresh() },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item {
                ScreenHeader(
                    title = title,
                    onBack = onBack,
                    onRefresh = { coordinator.refresh() },
                )
            }
            if (data == null && state.errorMessage == null) item { LoadingBlock() }
            state.errorMessage?.let { error ->
                item { MessageCard("Could not load $title", error) }
            }
            data?.let { content(it) }
        }
    }
}

@Composable
private fun ScreenHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    horizontalPadding: androidx.compose.ui.unit.Dp = 16.dp,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(
                start = horizontalPadding,
                end = if (horizontalPadding > 0.dp) 8.dp else 0.dp,
                top = 16.dp,
                bottom = 8.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        onBack?.let {
            IconButton(onClick = it) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
        }
        Text(
            title,
            modifier = Modifier.weight(1f),
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        onSearch?.let {
            IconButton(onClick = it) { Icon(Icons.Default.Search, "Search") }
        }
        onRefresh?.let {
            IconButton(onClick = it) { Icon(Icons.Default.Refresh, "Refresh") }
        }
        trailing()
    }
}

@Composable
private fun DetailHeader(title: String, navController: NavHostController, onRefresh: () -> Unit) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
        },
        actions = { IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh") } },
    )
}

@Composable
private fun ArtistHero(
    artist: Artist,
    albums: List<Album>,
) {
    val imageUrl = artist.artistImageUrl
        ?: artist.coverArt?.let { AppGraph.navidrome.coverArtUrl(it, size = 1_200) }
    val trackCount = albums.sumOf { it.songCount }
    val duration = albums.sumOf { it.duration }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.45f),
    ) {
        Artwork(
            url = imageUrl,
            description = artist.name,
            cacheKey = "artist-hero:${artist.id}",
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    heroFadeBrush(MaterialTheme.colorScheme.background),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            Text(artist.name, fontSize = 38.sp, fontWeight = FontWeight.Bold)
            Text(
                buildList {
                    add("${albums.size} albums")
                    if (trackCount > 0) add("$trackCount tracks")
                    if (duration > 0) add(formatCollectionDuration(duration))
                }.joinToString(" • "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AlbumHero(
    page: AlbumPage,
    onArtist: (() -> Unit)?,
    onPlay: () -> Unit,
    onAppend: () -> Unit,
    rating: Int,
    onRate: (Int) -> Unit,
) {
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            Cover(
                page.album,
                Modifier.fillMaxSize(),
                size = 1_200,
                showRatingBadge = false,
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        heroFadeBrush(MaterialTheme.colorScheme.background),
                    ),
            )
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RoundActionButton(
                    icon = Icons.Default.PlayArrow,
                    description = "Play album",
                    primary = true,
                    onClick = onPlay,
                )
                RoundActionButton(
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    description = "Append album to queue",
                    translucent = true,
                    onClick = onAppend,
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(18.dp)
                    .height(54.dp),
            ) {
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .clip(RoundedCornerShape(20.dp))
                        .background(OverlayButtonBackground)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    (1..5).forEach { star ->
                        IconButton(
                            onClick = { onRate(star) },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                imageVector = if (star <= rating) {
                                    Icons.Default.Star
                                } else {
                                    Icons.Default.StarBorder
                                },
                                contentDescription = "Rate $star stars",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(23.dp),
                            )
                        }
                    }
                }
            }
        }
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
        ) {
            Text(
                page.album.name,
                fontSize = 30.sp,
                lineHeight = 35.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                page.album.artist,
                modifier = Modifier.clickable(
                    enabled = onArtist != null,
                    onClick = { onArtist?.invoke() },
                ),
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                listOfNotNull(
                    page.album.releaseYearLabel().takeIf { it.isNotBlank() },
                    "${page.songs.size} tracks",
                    page.album.duration.takeIf { it > 0 }?.let(::formatCollectionDuration),
                ).joinToString(" • "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun RoundActionButton(
    icon: ImageVector,
    description: String,
    primary: Boolean = false,
    translucent: Boolean = false,
    onClick: () -> Unit,
) {
    val background = when {
        primary -> MaterialTheme.colorScheme.primary
        translucent -> OverlayButtonBackground
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = when {
        primary -> MaterialTheme.colorScheme.onPrimary
        translucent -> Color.White
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(54.dp)
            .clip(CircleShape)
            .background(background),
    ) {
        Icon(icon, description, tint = foreground, modifier = Modifier.size(30.dp))
    }
}

@Composable
private fun ArtistShelf(
    title: String,
    artists: List<Artist>,
    onArtist: (Artist) -> Unit,
    onMore: () -> Unit,
) {
    Column {
        HomeSectionHeader(title, onMore)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(artists, key = { it.id }) { artist ->
                ArtistCard(
                    artist = artist,
                    onClick = { onArtist(artist) },
                    modifier = Modifier.width(124.dp),
                )
            }
        }
    }
}

@Composable
private fun ArtistCard(
    artist: Artist,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(modifier = modifier.clickable(onClick = onClick)) {
        Artwork(
            url = artist.coverArt?.let { AppGraph.navidrome.coverArtUrl(it, size = 500) }
                ?: artist.artistImageUrl,
            description = artist.name,
            cacheKey = "artist-card:${artist.id}",
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(14.dp)),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            artist.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun AlbumShelf(
    title: String,
    albums: List<Album>,
    onAlbum: (Album) -> Unit,
    onMore: (() -> Unit)? = null,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = onMore != null) { onMore?.invoke() }
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, fontSize = 27.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (onMore != null) Icon(Icons.Default.ChevronRight, "See all")
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(albums, key = { it.id }) { album ->
                AlbumCard(album, onClick = { onAlbum(album) }, modifier = Modifier.width(144.dp))
            }
        }
    }
}

@Composable
private fun AlbumCard(
    album: Album,
    modifier: Modifier = Modifier,
    subtitle: String = album.artist,
    onClick: () -> Unit,
) {
    Column(modifier = modifier.clickable(onClick = onClick)) {
        Cover(
            album,
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(14.dp)),
        )
        Spacer(Modifier.height(8.dp))
        Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        Text(
            subtitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun Cover(
    album: Album,
    modifier: Modifier,
    size: Int = 600,
    showRatingBadge: Boolean = true,
) {
    val url = AppGraph.navidrome.coverArtUrl(album.coverArt ?: album.id, size = size)
    val rating = LocalAlbumRatingCoordinator.current
        .state(album.id, album.userRating)
        .rating
    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (url == null) {
            Icon(
                Icons.Default.Album,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp),
            )
        } else {
            val request = ImageRequest.Builder(LocalContext.current)
                .data(url)
                .memoryCacheKey(accountCacheKey("album:${album.coverArt ?: album.id}:$size"))
                .diskCacheKey(accountCacheKey("album:${album.coverArt ?: album.id}:$size"))
                .build()
            AsyncImage(
                model = request,
                contentDescription = album.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (showRatingBadge && rating in 1..5) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(7.dp)
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    rating.toString(),
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                )
            }
        }
    }
}

@Composable
private fun ArtistRow(artist: Artist, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            url = artist.coverArt?.let { AppGraph.navidrome.coverArtUrl(it) }
                ?: artist.artistImageUrl,
            description = artist.name,
            cacheKey = "artist:${artist.id}",
            modifier = Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(13.dp)),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(artist.name, fontSize = 21.sp)
            artist.genre?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SongRow(
    song: Song,
    isPlaying: Boolean = false,
    showArtist: Boolean = true,
    onClick: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isPlaying) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) {
            if (isPlaying) Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
            else Text((song.track ?: "–").toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f)) {
            Text(song.title, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (showArtist && song.artist.isNotBlank()) {
                Text(
                    song.artist,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    fontSize = 13.sp,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            formatDuration(song.duration),
            modifier = Modifier.width(52.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun ContinueCard(queue: PlayQueue, onClick: () -> Unit) {
    val song = queue.entry.firstOrNull { it.id == queue.current } ?: queue.entry.first()
    val coverKey = song.albumId ?: song.coverArt ?: song.id
    val coverUrl = AppGraph.navidrome.coverArtUrl(coverKey, size = 900)
    Box(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(126.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick),
    ) {
        if (coverUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(coverUrl)
                    .memoryCacheKey(accountCacheKey("continue:$coverKey"))
                    .diskCacheKey(accountCacheKey("continue:$coverKey"))
                    .build(),
                contentDescription = song.album,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.88f),
                        0.62f to Color.Black.copy(alpha = 0.48f),
                        1f to Color.Black.copy(alpha = 0.72f),
                    ),
                ),
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    queue.changedBy?.takeIf { it.isNotBlank() }?.let {
                        "Continue from ${handoffClientDisplayName(it)}"
                    }
                        ?: "Continue playing",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    song.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White,
                    fontSize = 19.sp,
                )
                Text(song.artist, color = Color.White.copy(alpha = 0.78f))
            }
        }
    }
}

private fun handoffClientDisplayName(client: String): String = when {
    client.equals("CodaMac", ignoreCase = true) -> "Coda Mac"
    client.equals(NavidromeClient.CLIENT_NAME, ignoreCase = true) -> "Coda Android"
    else -> client
}

@Composable
private fun MiniPlayer(
    state: PlaybackUiState,
    playback: PlaybackConnection,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    val progress by playback.progress.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(
                url = state.artworkUrl,
                description = state.album,
                cacheKey = "playback:${state.artworkKey ?: state.currentSongId}",
                modifier = Modifier.size(68.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp),
            ) {
                Text(
                    state.title,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    state.error ?: state.artist,
                    color = if (state.error != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onToggle, modifier = Modifier.size(60.dp)) {
                Icon(
                    if (progress.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (progress.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(34.dp),
                )
            }
        }
        LinearProgressIndicator(
            progress = {
                if (progress.durationMs > 0) {
                    (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f)
                } else {
                    0f
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun NowPlayingScreen(
    navController: NavHostController,
    playback: PlaybackConnection,
    state: PlaybackUiState,
) {
    val progress by playback.progress.collectAsStateWithLifecycle()
    AdaptiveBackground {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
            ) {
                val screenRatio = maxHeight.value / maxWidth.value.coerceAtLeast(1f)
                val veryCompact = screenRatio < 1.86f
                val compact = screenRatio < 2.05f
                val artworkHeight = when {
                    veryCompact -> maxWidth * 0.74f
                    compact -> maxWidth * 0.86f
                    else -> maxWidth
                }
                val upcomingCount = when {
                    veryCompact -> 1
                    compact -> 2
                    else -> 3
                }
                val primaryControlSize = if (veryCompact) 64.dp else 72.dp
                val primaryControlIconSize = if (veryCompact) 36.dp else 40.dp
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(artworkHeight),
                    ) {
                        Artwork(
                            url = state.artworkUrl,
                            description = state.album,
                            cacheKey = "playback:${state.artworkKey ?: state.currentSongId}",
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    heroFadeBrush(MaterialTheme.colorScheme.background),
                                ),
                        )
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        SeekBar(
                            positionMs = progress.positionMs,
                            durationMs = progress.durationMs,
                            enabled = progress.isSeekable,
                            onSeek = playback::seekTo,
                        )
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                formatDurationMs(progress.positionMs),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "-${formatDurationMs((progress.durationMs - progress.positionMs).coerceAtLeast(0))}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp,
                            )
                        }
                        Spacer(Modifier.height(if (compact) 7.dp else 12.dp))
                        Text(
                            trackTitle(state),
                            modifier = Modifier.fillMaxWidth(),
                            fontSize = if (veryCompact) 24.sp else 26.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(if (compact) 3.dp else 6.dp))
                        Text(
                            state.artist,
                            modifier = Modifier.fillMaxWidth(),
                            fontSize = 19.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            state.album,
                            modifier = Modifier.fillMaxWidth(),
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                        state.error?.let {
                            Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
                            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        } ?: qualityLabel(state)?.let {
                            Spacer(Modifier.height(if (compact) 5.dp else 9.dp))
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                                fontSize = 11.sp,
                            )
                        }
                        val upcoming = state.queue.withIndex()
                            .drop((state.currentIndex + 1).coerceAtLeast(0))
                            .take(upcomingCount)
                        if (state.queue.isNotEmpty()) {
                            Spacer(Modifier.height(if (compact) 7.dp else 14.dp))
                            UpNextList(
                                entries = upcoming,
                                onSelect = playback::skipTo,
                                onOpenQueue = { navController.navigate("queue") },
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = playback::previous, modifier = Modifier.size(56.dp)) {
                                Icon(
                                    Icons.Default.SkipPrevious,
                                    "Previous",
                                    modifier = Modifier.size(38.dp),
                                )
                            }
                            IconButton(
                                onClick = playback::togglePlayPause,
                                modifier = Modifier
                                    .size(primaryControlSize)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                            ) {
                                Icon(
                                    if (progress.isPlaying) {
                                        Icons.Default.Pause
                                    } else {
                                        Icons.Default.PlayArrow
                                    },
                                    if (progress.isPlaying) "Pause" else "Play",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(primaryControlIconSize),
                                )
                            }
                            IconButton(onClick = playback::next, modifier = Modifier.size(56.dp)) {
                                Icon(
                                    Icons.Default.SkipNext,
                                    "Next",
                                    modifier = Modifier.size(38.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(if (compact) 8.dp else 20.dp))
                    }
                }
            }
    }
}

@Composable
private fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
) {
    val safeDuration = durationMs.coerceAtLeast(0)
    val fraction = if (safeDuration > 0) {
        (positionMs.toFloat() / safeDuration).coerceIn(0f, 1f)
    } else {
        0f
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(safeDuration, enabled) {
                if (!enabled || safeDuration <= 0) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()

                    fun seekAt(x: Float) {
                        val targetFraction = (x / size.width).coerceIn(0f, 1f)
                        onSeek((targetFraction * safeDuration).toLong())
                    }

                    seekAt(down.position.x)
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.pressed) {
                            seekAt(change.position.x)
                            change.consume()
                        }
                    } while (change.pressed)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)),
        )
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(
                        if (enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
                        },
                    ),
            )
        }
    }
}

@Composable
private fun UpNextList(
    entries: List<IndexedValue<QueueEntry>>,
    onSelect: (Int) -> Unit,
    onOpenQueue: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onOpenQueue)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Up next",
                modifier = Modifier.weight(1f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = "Open queue",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        entries.forEach { indexedEntry ->
            val entry = indexedEntry.value
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onSelect(indexedEntry.index) }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    entry.trackNumber?.toString()?.padStart(2, '0') ?: "–",
                    modifier = Modifier.width(34.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
                Text(
                    entry.title,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 14.sp,
                )
                if (entry.durationMs > 0) {
                    Text(
                        formatDurationMs(entry.durationMs),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueScreen(
    navController: NavHostController,
    playback: PlaybackConnection,
    state: PlaybackUiState,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
    ) {
        item {
            TopAppBar(
                title = { Text("Queue") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = playback::clearQueue) {
                        Icon(Icons.Default.ClearAll, "Clear queue")
                    }
                },
            )
        }
        itemsIndexed(state.queue, key = { index, item -> "${item.id}-$index" }) { index, item ->
            QueueRow(
                item = item,
                isPlaying = index == state.currentIndex,
                onClick = { playback.skipTo(index) },
                onRemove = { playback.removeAt(index) },
            )
        }
    }
}

@Composable
private fun QueueRow(
    item: QueueEntry,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isPlaying) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            url = item.artworkUrl,
            description = item.album,
            cacheKey = "playback:${item.artworkKey}",
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${item.artist} • ${item.album}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Close, "Remove")
        }
    }
}

@Composable
private fun Artwork(
    url: String?,
    description: String,
    cacheKey: String,
    modifier: Modifier,
) {
    if (url == null) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Album, null, modifier = Modifier.size(48.dp))
        }
        return
    }
    val request = ImageRequest.Builder(LocalContext.current)
        .data(url)
        .memoryCacheKey(accountCacheKey(cacheKey))
        .diskCacheKey(accountCacheKey(cacheKey))
        .build()
    AsyncImage(
        model = request,
        contentDescription = description,
        contentScale = ContentScale.Crop,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, fontSize = 27.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
}

@Composable
private fun LoadingBlock() {
    Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun MessageCard(title: String, message: String) {
    Column(
        Modifier
            .padding(16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(18.dp),
    ) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 19.sp)
        Spacer(Modifier.height(6.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatDuration(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

private fun formatCollectionDuration(seconds: Int): String {
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    val remaining = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remaining)
    else "%d:%02d".format(minutes, remaining)
}

private fun Album.releaseYearLabel(): String =
    (originalReleaseDate?.year ?: releaseDate?.year ?: year)?.toString().orEmpty()

private fun formatDurationMs(milliseconds: Long): String {
    val seconds = milliseconds / 1_000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private fun trackTitle(state: PlaybackUiState): String = state.trackNumber?.let { track ->
    "${track.toString().padStart(2, '0')}. ${state.title}"
} ?: state.title

private fun qualityLabel(state: PlaybackUiState): String? {
    val codec = state.codec?.uppercase() ?: return null
    val parts = mutableListOf(codec)
    if (state.bitDepth != null && state.samplingRate != null) {
        val khz = state.samplingRate / 1_000.0
        parts += "${state.bitDepth}/${if (khz % 1.0 == 0.0) khz.toInt() else khz} kHz"
    }
    state.bitRate?.let { parts += "$it kb/s" }
    return parts.joinToString(" • ")
}

private fun <T> Result<T>.onFailureUnlessCancelled(
    action: (Throwable) -> Unit,
): Result<T> = onFailure { error ->
    if (error is CancellationException) throw error
    action(error)
}
