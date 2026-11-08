package dev.coda.android.ui.home

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import dev.coda.android.artwork.*
import dev.coda.core.subsonic.*
import dev.coda.android.ui.common.*
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.LibraryMusic

/** Home shelves whose heading opens the full list, in the shelf's own sort (as on macOS). Continue Listening has none. */
enum class HomeList(val title: String, val type: AlbumListType?) {
    NEWEST("Recently Added Albums", AlbumListType.NEWEST), RECENT_RELEASES("Recent Releases", AlbumListType.RECENT_RELEASES),
    RECENTLY_PLAYED("Recently Played", AlbumListType.RECENTLY_PLAYED), PLAYLISTS("Playlists", null);
    companion object { fun forTitle(title: String) = entries.first { it.title == title } }
}

/** A shelf heading. With a [list] it opens that list (chevron, "Show all"); without one (Continue Listening) it is a plain heading. */
@Composable
private fun ShelfHeading(title: String, list: HomeList?, open: (HomeList) -> Unit, padding: PaddingValues) {
    Row(Modifier.fillMaxWidth().then(if (list != null) Modifier.clickable(onClickLabel = "Show all $title") { open(list) } else Modifier)
        .padding(padding).testTag("shelfHeading-$title").semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (list != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.padding(start = 2.dp).size(22.dp).testTag("shelfChevron-$title"),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Home's list keys in order, as the LazyColumn below adds them (keep both in step); for the scroll anchor. */
internal fun homeKeys(state: HomeState): List<String> {
    val shelvesEmpty = state.albums.isEmpty() && state.recentReleases.isEmpty() && state.recentlyPlayed.isEmpty() && state.playlists.isEmpty()
    val shelves = listOf("Recently Added Albums" to state.albums, "Recent Releases" to state.recentReleases,
        "Recently Played" to state.recentlyPlayed, "Continue Listening" to state.continueListening)
    return buildList {
        add("title")
        if (state.loading && shelvesEmpty) add("progress")
        add("handoff")
        for ((title, items) in shelves) if (items.isNotEmpty() || state.loading && shelvesEmpty && title != "Continue Listening") add(title)
        if (state.playlists.isNotEmpty()) add("Playlists")
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(state: HomeState, fetcher: CoverArtFetcher, retry: () -> Unit, resumeHandoff: (String) -> Unit, dismissHandoff: (String) -> Unit, undoDismissHandoff: (String) -> Unit = {}, handoffKeepsMine: String? = null, openSettings: () -> Unit = {}, openPlaylist: (Playlist) -> Unit = {}, openList: (HomeList) -> Unit = {}, open: (Album) -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var lastOffer by remember { mutableStateOf(state.handoff) }
    if (state.handoff != null) lastOffer = state.handoff
    val queue = state.handoff ?: lastOffer
    fun hide(identity: String) {
        // In a reconnect conflict the dismiss is "keep mine" (the phone's queue is saved over the other device's): no Undo.
        val keepsMine = handoffKeepsMine == identity
        dismissHandoff(identity)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            if (keepsMine) snackbar.showNotice("Kept the queue on this phone")
            else if (snackbar.showNotice("Hidden", NoticeAction.Undo) == SnackbarResult.ActionPerformed) {
                undoDismissHandoff(identity)
            }
        }
    }
    val menus = dev.coda.android.ui.menu.LocalItemMenus.current
    // Home drawn from the phone (offline): partial albums say "7 of 12" under the cover; its own empty state.
    val onPhone = dev.coda.android.ui.offline.LocalOnPhone.current
    // The way back's cross-fade keeps the same shelf in view (by its key: the phone may lack a shelf).
    val list = dev.coda.android.ui.offline.rememberAnchoredListState { homeKeys(state).indexOf(it).takeIf { i -> i >= 0 } }
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().pageSemantics("Home").testTag("home"), state = list,
        contentPadding = PaddingValues(bottom = dev.coda.android.ui.shell.LocalDockPadding.current + 16.dp)) {
        item(key = "title") { Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Home", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            // A plain Settings gear; Sign out lives in Settings' Account card.
            IconButton(onClick = openSettings, Modifier.testTag("homeSettings")) { Icon(Icons.Outlined.Settings, "Settings") }
        } }
        // The shelves come from one request; Continue Listening and the handoff card arrive separately.
        val shelvesEmpty = state.albums.isEmpty() && state.recentReleases.isEmpty() && state.recentlyPlayed.isEmpty() && state.playlists.isEmpty()
        val hasContent = !shelvesEmpty || state.continueListening.isNotEmpty()
        if (state.loading && shelvesEmpty) item(key = "progress") { DelayedProgress(true) }
        // A handoff offers an album: its own artwork drives the card.
        item(key = "handoff") {
            AnimatedVisibility(state.handoff != null, enter = expandVertically(tween(250)) + fadeIn(),
                exit = shrinkVertically(tween(200)) + fadeOut()) {
                if (queue != null) key(queue.handoffIdentity) {
                    val offered = dev.coda.android.ui.theme.rememberTileAccent(queue.currentSong?.albumArtworkId)
                    val resolver = dev.coda.android.ui.theme.LocalAccentResolver.current
                    val held = resolver?.page?.collectAsState()?.value?.selection ?: dev.coda.core.accent.DisplayedAccent.brandSelection
                    val handoffAccent = offered ?: dev.coda.android.ui.theme.rememberAccentState(held)
                    dev.coda.android.ui.theme.AccentTheme(handoffAccent) {
                        HandoffSwipe(visible = state.handoff != null, onDismiss = { hide(queue.handoffIdentity) }) {
                            Card(onClick = { resumeHandoff(queue.handoffIdentity) }, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth().testTag("handoff").semantics {
                                    customActions = listOf(CustomAccessibilityAction("Not now") { hide(queue.handoffIdentity); true })
                                },
                                shape = RoundedCornerShape(18.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .22f)),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = .16f))) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CoverImage(fetcher, queue.currentSong?.albumArtworkId, Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)))
                                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                        Text("Continue from " + queue.changedBy.orEmpty().removePrefix("Coda on ").ifEmpty { "another device" },
                                            fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(queue.currentSong?.title.orEmpty(), fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        val remainingMs = (queue.entry.drop(queue.resolvedCurrentIndex).sumOf { (it.duration ?: 0).toLong() } * 1000 -
                                            (queue.position ?: 0).coerceAtLeast(0)).coerceAtLeast(0)
                                        val meta = listOfNotNull(queue.currentSong?.artistName?.takeIf { it.isNotEmpty() },
                                            remainingMs.takeIf { it > 0 }?.let { "${(it + 59_999) / 60_000} min left" }).joinToString(" · ")
                                        Text(meta, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    // H2: the card is the one stop (its click resumes); the button is for touch.
                                    Button(onClick = { resumeHandoff(queue.handoffIdentity) }, Modifier.decorative(), colors = ButtonDefaults.buttonColors(containerColor = handoffAccent.fill, contentColor = handoffAccent.onFill)) { Text("Resume") }
                                    // Preserve the former menu's slot: removing it must not reflow the card.
                                    Spacer(Modifier.size(48.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        val shelves = listOf("Recently Added Albums" to state.albums, "Recent Releases" to state.recentReleases,
            "Recently Played" to state.recentlyPlayed, "Continue Listening" to state.continueListening.map { it.album })
        shelves.forEach { (title, albums) ->
            if (albums.isNotEmpty() || state.loading && shelvesEmpty && title != "Continue Listening") item(key = title) {
                Column(Modifier.padding(bottom = 22.dp).testTag("shelf-$title")) {
                    ShelfHeading(title, HomeList.entries.firstOrNull { it.title == title }, openList, PaddingValues(horizontal = 16.dp, vertical = 10.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.loading && albums.isEmpty()) items(4) {
                            Column(Modifier.width(140.dp)) {
                                Skeleton(Modifier.size(140.dp))
                                SkeletonText(Modifier.padding(top = 10.dp))
                            }
                        }
                        items(albums, key = { it.id }) { album ->
                            Column(Modifier.width(140.dp).testTag(if (title == "Recently Added Albums") "album-${album.id}" else "album-$title-${album.id}")
                                .albumCell(dev.coda.android.ui.library.currentRating(album.id, album.userRating))
                                .combinedClickable(onClick = { open(album) }, onClickLabel = "Open album", onLongClickLabel = "Album actions", onLongClick = {
                                    // The album sheet (it finds Resume album itself).
                                    menus?.album(album)
                                })) {
                                Box {
                                    CoverImage(fetcher, album.artworkId, Modifier.size(140.dp).clip(RoundedCornerShape(10.dp)))
                                    dev.coda.android.ui.library.currentRating(album.id, album.userRating).takeIf { it > 0 }?.let { rating ->
                                        AlbumRatingBadge(rating, dev.coda.android.ui.common.BadgeSize.Large)
                                    }
                                }
                                val progress = if (title == "Continue Listening") state.resumeProgress[album.id] else null
                                // Every Continue Listening tile reserves the bar slot, so a tile without progress keeps the row height.
                                if (title == "Continue Listening") Box(Modifier.padding(top = 7.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))
                                    .background(if (progress != null) Color.White.copy(alpha = .14f) else Color.Transparent)
                                    .then(if (progress != null) Modifier.testTag("resumeBar-${album.id}") else Modifier)) {
                                    // The resume bar and the badges wear the view's accent (the playing colour on Home).
                                    if (progress != null) Box(Modifier.fillMaxWidth(progress.fraction).fillMaxHeight()
                                        .background(dev.coda.android.ui.theme.rememberViewAccentText()))
                                }
                                TileTitle(album.name, if (title == "Continue Listening") 6.dp else 8.dp)
                                val part = onPhone?.album(album.id)
                                Text(progress?.subtitle ?: if (part == null) album.artistName else "${album.artistName} · $part", if (progress != null) Modifier.testTag("resumeSubtitle-${album.id}") else Modifier,
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        if (state.playlists.isNotEmpty()) item(key = "Playlists") {
            Column(Modifier.padding(bottom = 22.dp).testTag("shelf-Playlists")) {
                ShelfHeading(HomeList.PLAYLISTS.title, HomeList.PLAYLISTS, openList, PaddingValues(16.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.playlists, key = { it.id }) { playlist ->
                        val title = remember(playlist.name) { dev.coda.core.library.PlaylistName.parse(playlist.name) }
                        Column(Modifier.width(140.dp).testTag("playlist-${playlist.id}")
                            .combinedClickable(onClick = { openPlaylist(playlist) }, onLongClick = { menus?.playlist(playlist) }, onLongClickLabel = "Playlist actions")) {
                            Box {
                                CoverImage(fetcher, playlist.coverArt, Modifier.size(140.dp).clip(RoundedCornerShape(10.dp)))
                                PlaylistBadge(title, compact = false)
                            }
                            TileTitle(title.display, 8.dp)
                            Text("${playlist.songCount ?: 0} songs", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (state.failed && shelvesEmpty) item(key = "error") {
            dev.coda.android.ui.offline.CantLoadBlock("Couldn't load Home", "Check your connection and try again.", retry)
        }
        if (!state.loading && !state.failed && !hasContent) item(key = "empty") {
            if (onPhone != null) StateBlock(Icons.Default.LibraryMusic, "No music on this phone yet", "Albums you play or prefer offline show here.")
            else StateBlock(Icons.Default.LibraryMusic, "Your library is empty", "Navidrome hasn't indexed any albums yet.")
        }
    }
    dev.coda.android.ui.common.CodaSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter)
        .padding(bottom = dev.coda.android.ui.shell.LocalDockPadding.current))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HandoffSwipe(visible: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val swipe = rememberSwipeToDismissBoxState(positionalThreshold = { it * .4f })
    val latestDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(visible) {
        if (visible) swipe.snapTo(SwipeToDismissBoxValue.Settled)
    }
    LaunchedEffect(swipe) {
        snapshotFlow { swipe.settledValue }.collect {
            if (it != SwipeToDismissBoxValue.Settled) latestDismiss()
        }
    }
    SwipeToDismissBox(swipe, backgroundContent = {}) {
        Box(Modifier.graphicsLayer {
            // This layer can run before the swipe anchors are set (e.g. for semantics); requireOffset() throws then.
            val offset = runCatching { swipe.requireOffset() }.getOrDefault(0f)
            alpha = (1f - abs(offset) / size.width.coerceAtLeast(1f)).coerceIn(0f, 1f)
        }) { content() }
    }
}

/**
 * Shelf tile title (spec §1 / §5.3): one line keeps the card stable and the artist directly below it.
 */
@Composable
private fun TileTitle(text: String, top: Dp) {
    Text(text, Modifier.padding(top = top), fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}
