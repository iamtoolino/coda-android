package dev.coda.android.ui.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.coda.android.artwork.*
import dev.coda.android.ui.common.*
import dev.coda.android.ui.player.playbackTime
import dev.coda.android.ui.theme.*
import dev.coda.core.library.TrackListPresentation
import dev.coda.core.subsonic.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** P3 Album slabs. Group membership and occurrence order are exclusively the policy. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun PlaylistScreen(state: CollectionState, fetcher: CoverArtFetcher, accent: AccentState, ready: Boolean,
    back: () -> Unit, retry: () -> Unit, play: (List<Song>, Int) -> Unit, currentSongId: String? = null,
    playbackAccent: AccentState = accent, currentPlaylistIndex: Int? = null,
    /** Keeps an album page fetched for a header (the catalogue can then complete a minimal entry from it). */
    saveAlbum: (dev.coda.core.subsonic.AlbumPage) -> Unit = {},
    /** The saved album page a header shown from the phone takes its metadata from (no request offline). */
    phoneAlbum: suspend (String) -> AlbumPage? = { null }) {
    val page = state.playlist
    val songs = page?.songs.orEmpty()
    val groups = remember(songs) { TrackListPresentation.playlistGroups(songs) }
    val playingIndex = currentPlaylistIndex?.takeIf { currentSongId != null && songs.getOrNull(it)?.id == currentSongId }
        ?: songs.indexOfFirst { it.id == currentSongId }
    var playingRowHeight by remember { mutableIntStateOf(0) }
    val runs = groups.filterNot { it.isStandalone }
    var collapsed by rememberSaveable(page?.playlist?.id) { mutableStateOf(emptyList<Int>()) }
    // The way back's cross-fade keeps the same row in view.
    val list = dev.coda.android.ui.offline.rememberAnchoredListState()
    val scope = rememberCoroutineScope()
    val session = LocalLibrarySession.current
    // getPlaylist omits album totals. Resolve only visible headers, once per page/session.
    val albums = remember(session, page?.playlist?.id) { mutableStateMapOf<String, Album>() }
    // The phone's side of the cross-fade (offline, no requests).
    val fromPhone = dev.coda.android.ui.offline.LocalOnPhone.current != null
    val title = remember(page?.playlist?.name) { dev.coda.core.library.PlaylistName.parse(page?.playlist?.name.orEmpty()) }
    val covers = remember(songs, state.headerCovers) { state.headerCovers ?: songs.mapNotNull { it.albumArtworkId }.distinct().take(4) }
    val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 } }
    val menus = dev.coda.android.ui.menu.LocalItemMenus.current
    // Remove from playlist only on a playlist you can edit.
    val editable = page != null && session != null && dev.coda.android.ui.menu.editable(page.playlist, session.client.configuration.username)

    // The album page's collapsed bar surface; it also fills the status bar, which so takes the bar's colour.
    val barAlpha = collapsedBarAlpha(scrolled, "Playlist header surface")
    Box(Modifier.fillMaxSize()) {
        CollapsedBarSurface(accent, barAlpha, "playlistPinnedSurface")
        Column(Modifier.fillMaxSize().statusBarsPadding().then(dev.coda.android.ui.common.pageSemanticsOf("Playlist")).testTag("playlistScreen")) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                if (scrolled) {
                    PlaylistMosaic(fetcher, covers, Modifier.size(28.dp))
                    Text(title.display, Modifier.weight(1f).padding(start = 10.dp), fontSize = 18.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    PlaylistPlay(accent, ready && songs.isNotEmpty(), "pinnedPlayPlaylist", pinned = true) { play(songs, 0) }
                } else Spacer(Modifier.weight(1f))
                IconButton(onClick = { page?.let { menus?.playlist(it.playlist) } }, Modifier.testTag("playlistMenu"), enabled = page != null) {
                    Icon(Icons.Default.MoreVert, "Playlist actions")
                }
            }
            Box(Modifier.weight(1f)) {
                // The album header pinned under the top bar (a standalone run's empty header pins too, and then nothing is pinned).
                val pinnedKey by remember { derivedStateOf {
                    list.layoutInfo.visibleItemsInfo.lastOrNull { it.key.toString().startsWith("album-") && it.offset <= 0 }?.key
                } }
                val pinnedHeight by remember { derivedStateOf {
                    list.layoutInfo.visibleItemsInfo.filter { it.key.toString().startsWith("album-") && it.offset <= 0 }
                        .maxOfOrNull { it.size } ?: 0
                } }
                val playingItem by remember(playingIndex) { derivedStateOf {
                    list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "track-$playingIndex" }
                        ?.takeIf { it.offset + it.size > pinnedHeight && it.offset < list.layoutInfo.viewportEndOffset }
                        // LazyListItemInfo instances change on every layout. Observe only value coordinates,
                        // otherwise composing the visible playing wash can keep invalidating the list.
                        ?.let { it.offset to it.size }
                } }
                // A single wash moves between visible occurrences instead of flashing a separate row background.
                val washY by animateFloatAsState(playingItem?.let { (it.first + it.second - playingRowHeight).toFloat() } ?: 0f, tween(if (list.isScrollInProgress) 0 else 300), label = "Playing wash position")
                val washHeight by animateFloatAsState(if (playingItem != null) playingRowHeight.toFloat() else 0f, tween(300), label = "Playing wash height")
                LazyColumn(Modifier.fillMaxSize().testTag("playlistTracks").drawWithContent {
                    drawContent()
                    if (playingItem != null) clipRect(top = pinnedHeight.toFloat()) { drawRoundRect(Color.White.copy(alpha = .07f), Offset(8.dp.toPx(), washY),
                        Size(size.width - 16.dp.toPx(), washHeight), CornerRadius(10.dp.toPx())) }
                }, state = list, contentPadding = PaddingValues(bottom = dev.coda.android.ui.shell.LocalDockPadding.current + 16.dp)) {
                    item("hero") {
                        if (page != null) Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.testTag("playlistCover")) {
                                PlaylistMosaic(fetcher, covers, Modifier.size(128.dp))
                                // The title names the playlist (auto-updating, setlist), so the badge itself is silent.
                                PlaylistBadge(title, compact = false, describe = false)
                            }
                            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                                Text(title.display, fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.ExtraBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("playlistTitle").semantics {
                                        heading()
                                        title.badgeDescription?.let { contentDescription = "${title.display}, $it" }
                                    })
                                Text("${page?.playlist?.id?.let { dev.coda.android.ui.offline.LocalOnPhone.current?.playlist(it) } ?: "${songs.size} tracks"} · ${playlistDuration(songs)}", color = Color.White.copy(alpha = .66f), fontSize = 12.sp,
                                    modifier = Modifier.testTag("playlistMeta"))
                                Text(listOfNotNull(runs.size.takeIf { it > 0 }?.let { "$it albums" },
                                    groups.count { it.isStandalone }.takeIf { it > 0 }?.let { "$it singles" }).joinToString(" · "),
                                    color = Color.White.copy(alpha = .44f), fontSize = 12.sp)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    PlaylistPlay(accent, ready && songs.isNotEmpty(), "playPlaylist") { play(songs, 0) }
                                    if (TrackListPresentation.canShufflePlaylist(groups)) TextButton(onClick = { play(TrackListPresentation.shuffledPlaylist(songs), 0) }, enabled = ready,
                                        modifier = Modifier.testTag("shufflePlaylist")) { Icon(Icons.Default.Shuffle, null, Modifier.size(16.dp)); Text("Shuffle", fontSize = 12.sp) }
                                    if (runs.size >= 2) IconButton(onClick = {
                                        collapsed = if (runs.all { it.startIndex in collapsed }) emptyList() else runs.map { it.startIndex }
                                    }, modifier = Modifier.testTag("collapseAllAlbums")) {
                                        // The icon points the way a tap goes, with the matching label.
                                        val action = collapseAllAction(runs.all { it.startIndex in collapsed })
                                        Icon(action.icon, action.label)
                                    }
                                }
                            }
                        }
                        if (page != null) dev.coda.android.ui.common.KeepOfflineSync("playlist/${page.playlist.id}", songs,
                            dev.coda.android.ui.offline.keepSyncFresh(state.loading, state.failed, dev.coda.android.ui.offline.LocalOnPhone.current), title = page.playlist.name)
                        if (state.loading && page == null) { DelayedProgress(true); Skeleton(Modifier.padding(16.dp).size(128.dp).testTag("playlistSkeleton")) }
                        if (state.failed && page == null) dev.coda.android.ui.offline.CantLoadBlock("Couldn't load playlist", "Check your connection and try again.", retry)
                        if (page != null && songs.isEmpty()) StateBlock(Icons.Default.MusicOff, "No tracks", null, compact = true)
                    }
                    groups.forEachIndexed { groupIndex, group ->
                        val first = group.songs.first()
                        val closed = group.startIndex in collapsed
                        if (hasLeadingGap(groups, groupIndex)) item("lead-${group.startIndex}") { Spacer(Modifier.height(AlbumGap)) }
                        stickyHeader(key = "album-${group.startIndex}") {
                            if (group.isStandalone) Spacer(Modifier.height(0.dp)) else {
                            LaunchedEffect(session, first.albumId, fromPhone) {
                                val id = first.albumId ?: return@LaunchedEffect
                                if (id !in albums) try {
                                    playlistHeaderAlbum(id, fromPhone, phoneAlbum, session?.client?.let { c -> { c.getAlbum(it) } }, saveAlbum)?.let { albums[id] = it }
                                }
                                catch (e: CancellationException) { throw e } catch (_: Exception) { /* Rows remain playable offline. */ }
                            }
                            // A: no block. The pinned header takes the top bar's background, opaque so rows don't ghost through its text.
                            Column(Modifier.fillMaxWidth().background(if (pinnedKey == "album-${group.startIndex}") accent.page else Color.Transparent)) {
                                Row(Modifier.fillMaxWidth().testTag("playlist-album-${group.startIndex}").combinedClickable(
                                    onLongClick = first.albumId?.let { id -> { menus?.album(albums[id] ?: Album(id, first.album.orEmpty().ifEmpty { "Album" },
                                        coverArt = first.albumArtworkId)) } }, onLongClickLabel = "Album actions") {
                                    collapsed = if (closed) collapsed - group.startIndex else collapsed + group.startIndex
                                }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    PlaylistCover(fetcher, first.albumArtworkId, 52, closed && playingIndex in group.startIndex until group.startIndex + group.songs.size)
                                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                        Text(first.album.orEmpty().ifEmpty { "Unknown Album" }, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                            modifier = Modifier.semantics { heading() }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        val metadata = albums[first.albumId]
                                        val total = metadata?.songCount
                                        Text(listOfNotNull((metadata?.artistName?.takeIf { it.isNotEmpty() } ?: first.albumArtistName).takeIf { it.isNotEmpty() },
                                            (metadata?.releaseYear ?: first.year)?.toString(),
                                            total?.takeIf { it > group.songs.size }?.let { "${group.songs.size} of $it" }, playlistDuration(group.songs)).joinToString(" · "),
                                            fontSize = 12.sp, color = Color.White.copy(alpha = .66f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (closed) Text("${group.songs.size}", fontSize = 12.sp)
                                    Icon(if (closed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                                        if (closed) "Expand album" else "Collapse album", Modifier.size(24.dp))
                                }
                            }
                        }
                        }
                        group.songs.forEachIndexed { offset, song ->
                            val index = group.startIndex + offset
                            item("track-$index") {
                                val current = index == playingIndex
                                androidx.compose.animation.AnimatedVisibility(!closed || current || group.isStandalone,
                                    enter = expandVertically(tween(300, easing = CubicBezierEasing(.2f, 0f, 0f, 1f))) + fadeIn(tween(150, delayMillis = minOf(offset, 4) * 20)),
                                    exit = shrinkVertically(tween(300, easing = CubicBezierEasing(.2f, 0f, 0f, 1f))) + fadeOut(tween(150))) {
                                    Column(Modifier.padding(horizontal = 8.dp)) {
                                        if (!closed && !group.isStandalone && group.songs.map { it.discNumber ?: 1 }.distinct().size > 1 &&
                                            (offset == 0 || song.discNumber != group.songs[offset - 1].discNumber)) {
                                            Text("DISC ${song.discNumber ?: 1} · ${group.songs.count { it.discNumber == song.discNumber }} TRACKS",
                                                Modifier.padding(start = 68.dp, top = 10.dp, bottom = 4.dp), fontSize = 11.sp, color = Color.White.copy(alpha = .44f))
                                        }
                                        Box(Modifier.onSizeChanged { if (current) playingRowHeight = it.height }) {
                                            PlaylistTrack(fetcher, song, index, group.isStandalone, current, playbackAccent.text, ready,
                                                menu = { menus?.track(song, dev.coda.android.ui.menu.TrackMenuContext(removable = editable), page?.playlist?.id, index, songs.map { it.id }) }) { play(songs, index) }
                                        }
                                    }
                                }
                            }
                        }
                        if (!group.isStandalone) item("gap-${group.startIndex}") { Spacer(Modifier.height(AlbumGap)) }
                    }
                }
                val currentIndex = playingIndex
                if (currentIndex >= 0 && playingItem == null) FilledTonalButton(onClick = {
                    scope.launch {
                        val target = list.layoutInfo.totalItemsCount
                        // Keys include hidden rows, so collapse never changes the occurrence's address.
                        val position = playlistItemPosition(groups, currentIndex)
                        if (position in 0 until target) list.animateScrollToItem(position, -80)
                    }
                }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = dev.coda.android.ui.shell.LocalDockPadding.current).padding(8.dp).testTag("returnToPlaying")) { Text("↑ playing") }
            }
        }
    }
}

/**
 * An album header's metadata (album artist, year, total). [fromPhone]: the phone's side reads the saved
 * catalogue only (no requests offline); the live side asks the server ([live]) and keeps the page ([save]).
 */
internal suspend fun playlistHeaderAlbum(id: String, fromPhone: Boolean, saved: suspend (String) -> AlbumPage?,
    live: (suspend (String) -> AlbumPage)?, save: (AlbumPage) -> Unit): Album? =
    if (fromPhone) saved(id)?.album else live?.invoke(id)?.also(save)?.album

/** A: the space above an album header (below the previous album's last row, or the singles before it). */
private val AlbumGap = 16.dp

/** A run that follows singles gets its own gap item; one that follows an album already has that album's trailing gap. */
internal fun hasLeadingGap(groups: List<TrackListPresentation.PlaylistGroup>, index: Int) =
    index > 0 && groups[index - 1].isStandalone && !groups[index].isStandalone

/**
 * LazyColumn index of the row for [currentIndex]: the hero item, then per group an optional leading gap, the header, the
 * rows (hidden ones keep their items, so collapse never moves an address) and, for an album run, its trailing gap.
 * -1 when no group holds it.
 */
internal fun playlistItemPosition(groups: List<TrackListPresentation.PlaylistGroup>, currentIndex: Int): Int {
    var position = 1
    for ((groupIndex, group) in groups.withIndex()) {
        if (hasLeadingGap(groups, groupIndex)) position++
        position++
        if (currentIndex in group.startIndex until group.startIndex + group.songs.size) return position + currentIndex - group.startIndex
        position += group.songs.size + if (group.isStandalone) 0 else 1
    }
    return -1
}

/** The collapse/expand-all button: icon and label both name what a tap does. */
internal class CollapseAllAction(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String)

internal fun collapseAllAction(allCollapsed: Boolean) =
    if (allCollapsed) CollapseAllAction(Icons.Default.UnfoldMore, "Expand all albums") else CollapseAllAction(Icons.Default.UnfoldLess, "Collapse all albums")

/** The Playlists tab's formatter, so the list and the page say the same: "1 h 32 min", "45 min", "2 h". */
internal fun playlistDuration(songs: List<Song>): String =
    dev.coda.core.library.PlaylistOrder.duration(songs.sumOf { (it.duration ?: 0).toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) ?: "0 min"

@Composable
private fun PlaylistPlay(accent: AccentState, enabled: Boolean, tag: String, pinned: Boolean = false, play: () -> Unit) {
    val colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent.fill, contentColor = accent.onFill)
    // The bar's button is the album bar's (a 40 dp disc with a 24 dp icon in a 48 dp slot; the touch target stays 48 dp).
    if (pinned) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            FilledIconButton(onClick = play, enabled = enabled, modifier = Modifier.size(40.dp).testTag(tag), colors = colors) {
                Icon(Icons.Default.PlayArrow, "Play playlist", Modifier.size(24.dp))
            }
        }
    } else FilledIconButton(onClick = play, enabled = enabled, modifier = Modifier.size(48.dp).testTag(tag), colors = colors) {
        Icon(Icons.Default.PlayArrow, "Play playlist")
    }
}

@Composable
internal fun PlaylistMosaic(fetcher: CoverArtFetcher, covers: List<String>, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp))) {
        repeat(2) { row -> Row(Modifier.weight(1f)) {
            repeat(2) { col -> CoverImage(fetcher, covers.getOrNull((row * 2 + col) % covers.size.coerceAtLeast(1)), Modifier.weight(1f).fillMaxHeight()) }
        } }
    }
}

@Composable
private fun PlaylistCover(fetcher: CoverArtFetcher, cover: String?, size: Int, playing: Boolean) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(6.dp))) {
        CoverImage(fetcher, cover, Modifier.fillMaxSize())
        if (playing) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .4f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.VolumeUp, "Current track", Modifier.size(18.dp), tint = Color.White)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PlaylistTrack(fetcher: CoverArtFetcher, song: Song, index: Int, single: Boolean, playing: Boolean,
    accent: Color, enabled: Boolean, menu: () -> Unit, play: () -> Unit) {
    val titleColour by animateColorAsState(if (playing) accent else Color(0xFFF1F1EE), tween(150), label = "Playing title")
    // Offline, a track not on the phone is dimmed only; a tap says why instead of playing.
    val onPhone = dev.coda.android.ui.offline.LocalOnPhone.current
    val missing = onPhone != null && !onPhone.has(song.id)
    val notice = dev.coda.android.ui.offline.LocalPhoneNotice.current
    Row(Modifier.fillMaxWidth().testTag("playlist-track-$index").combinedClickable(enabled = enabled || missing,
        onClickLabel = "Play", onClick = { if (missing) notice(dev.coda.android.ui.offline.NOT_ON_PHONE) else play() }, onLongClick = menu,
        onLongClickLabel = "Track actions")
        .then(if (missing) Modifier.alpha(dev.coda.android.ui.offline.MISSING_ALPHA)
            .semantics { stateDescription = dev.coda.android.ui.offline.NOT_ON_PHONE } else Modifier)
        .heightIn(min = if (single) 60.dp else 48.dp).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (single) PlaylistCover(fetcher, song.albumArtworkId, 44, playing)
        else Box(Modifier.width(44.dp), contentAlignment = Alignment.CenterEnd) {
            if (playing) Icon(Icons.Default.VolumeUp, "Current track", Modifier.size(16.dp), tint = accent)
            else Text(song.track?.toString().orEmpty(), fontSize = 13.sp, color = Color.White.copy(alpha = .44f))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            Text(song.title, fontSize = 15.sp, color = titleColour, fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (single) Text(song.artistName, fontSize = 12.sp, color = Color.White.copy(alpha = .66f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = menu, Modifier.testTag("playlist-track-menu-$index").decorative()) { Icon(Icons.Default.MoreVert, "Actions for ${song.title}") }
        dev.coda.android.ui.common.TrackDuration(playbackTime((song.duration ?: 0).toLong() * 1000), Color.White.copy(alpha = .44f),
            seconds = song.duration?.toLong())
    }
}
