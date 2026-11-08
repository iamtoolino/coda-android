package dev.coda.android.ui.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.coda.android.artwork.CoverArtFetcher
import dev.coda.android.artwork.CoverImage
import dev.coda.android.ui.common.*
import dev.coda.android.ui.theme.AccentState
import dev.coda.core.subsonic.Playlist
import dev.coda.core.library.LibrarySection
import dev.coda.core.library.PlaylistOrder
import dev.coda.core.library.PlaylistSort
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Playlists tab: the Albums/Artists chrome (backdrop, enter-always header with the count, sort chips) over the unchanged
 * playlist rows, which gain a "24 tracks · 1 h 32 min" line. Name sorts get letter sections and the fast-scroll thumb
 * from 60 playlists up.
 */
@Composable
internal fun PlaylistsTab(state: CollectionState, sort: PlaylistSort, fetcher: CoverArtFetcher, accent: AccentState,
    backdrop: TabBackdrop?, retry: () -> Unit, openPlaylist: (String) -> Unit, selectSort: (PlaylistSort) -> Unit) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val statusPx = with(density) { statusBar.toPx() }
    val dock = dev.coda.android.ui.shell.LocalDockPadding.current
    // Local sort: the list is complete in one response. The order, sections and lines are made once per list and sort,
    // off Main.
    val onPhone = dev.coda.android.ui.offline.LocalOnPhone.current
    val shown = rememberPlaylistsShown(state.playlists, sort, onPhone)
    val playlists = shown.playlists
    val sections = shown.sections
    val lines = shown.lines
    // The way back's cross-fade keeps the same playlist in view (by its key: the phone shows a subset of the live list).
    val list = dev.coda.android.ui.offline.rememberAnchoredListState { key ->
        playlists.indexOfFirst { it.id == key }.takeIf { it >= 0 }?.let { dev.coda.android.ui.offline.sectionedIndex(it, 0, sections) }
    }
    val header = remember { EnterAlwaysHeader() }
    // At the very top the title always shows (also after a programmatic scroll or jump to the start).
    LaunchedEffect(list, header) { snapshotFlow { list.canScrollBackward }.collect { if (!it) header.show() } }
    val scroller = sections.isNotEmpty()
    val headers = remember(sections) { IntArray(sections.size) { sections[it].start + it } }
    val line = { statusPx + header.bottomPx.coerceAtLeast(0f) - with(density) { 4.dp.toPx() } }
    val pinned = remember(headers) { derivedStateOf { pinnedSection(headers, list.visibleItems(), line()) } }
    // Chip change: the list crossfades in 150 ms from the top, as on Albums.
    val fade = remember { Animatable(1f) }
    // Keyed on the sort shown, not the chip's, so it runs once the new order is made (no flash of the old one).
    var shownSort by remember { mutableStateOf(shown.sort) }
    LaunchedEffect(shown.sort) {
        if (sortFadeDue(shownSort, shown)) { shownSort = shown.sort; fade.snapTo(0f); list.scrollToItem(0); header.show(); fade.animateTo(1f, tween(150)) }
    }
    val scrollTarget = remember(list) { list.scrollTarget() }
    BoxWithConstraints(Modifier.fillMaxSize().pageSemantics("Playlists").testTag("playlistsTab")) {
        val headerHeight = with(density) { header.heightPx.toDp() }
        LazyColumn(Modifier.fillMaxSize().nestedScroll(header.connection).graphicsLayer { alpha = fade.value }.testTag("playlistsList")
            .semantics { collectionInfo = CollectionInfo(rowCount = playlists.size, columnCount = 1) },
            state = list, contentPadding = PaddingValues(top = statusBar + headerHeight, bottom = dock + 16.dp)) {
            when {
                playlists.isEmpty() && state.failed -> item(key = "failed") {
                    dev.coda.android.ui.offline.CantLoadBlock("Couldn't load playlists", "Check your connection and try again.", retry)
                }
                playlists.isEmpty() && state.loading -> item(key = "skeleton") {
                    Column(Modifier.testTag("playlistsSkeleton")) {
                        DelayedProgress(true)
                        repeat(6) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Skeleton(Modifier.size(56.dp), 8)
                                SkeletonText(Modifier.padding(start = 16.dp).weight(1f))
                            }
                        }
                    }
                }
                playlists.isEmpty() && onPhone != null -> item(key = "empty") {
                    StateBlock(Icons.Default.QueueMusic, "No playlists on this phone", "Playlists you prefer offline show here.")
                }
                playlists.isEmpty() -> item(key = "empty") {
                    StateBlock(Icons.Default.QueueMusic, "No playlists", "Save the queue as a playlist to see it here.")
                }
                sections.isEmpty() -> items(playlists.size, key = { playlists[it].id }, contentType = { "playlist" }) { i ->
                    PlaylistRow(playlists[i], lines[i], fetcher, openPlaylist)
                }
                else -> sections.forEach { section ->
                    item(key = "section-${section.label}", contentType = "section") {
                        SectionHeader(section.label, section.count, accent, Modifier.padding(horizontal = 16.dp).testTag("section-${section.label}"),
                            countText = NumberFormat.getIntegerInstance().format(section.count))
                    }
                    items(section.count, key = { playlists[section.start + it].id }, contentType = { "playlist" }) { i ->
                        PlaylistRow(playlists[section.start + i], lines[section.start + i], fetcher, openPlaylist)
                    }
                }
            }
        }
        // Spec §0.3: one pinned header under the title bar, pushed up by the next one; On its own backdrop band.
        val s = pinned.value
        if (s >= 0 && sections.isNotEmpty()) PinnedHeaderBand(backdrop, list, header.connection, line, { pinnedPush(headers, list.visibleItems(), s, line(), it) },
            Modifier.padding(horizontal = 16.dp)) {
            val section = sections[s]
            SectionHeader(section.label, section.count, accent, countText = NumberFormat.getIntegerInstance().format(section.count))
        }
        if (backdrop != null) Box(Modifier.fillMaxSize().backdropBand(backdrop) {
            if (header.offsetPx <= -header.heightPx || !list.canScrollBackward) 0f else statusPx + header.bottomPx
        })
        Column(Modifier.offset { IntOffset(0, header.drawOffset(statusPx).roundToInt()) }.statusBarsPadding().fillMaxWidth()
            .onSizeChanged { header.heightPx = it.height.toFloat() }.pageHeader()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                LibraryTitle("Playlists", if (playlists.isEmpty()) null else NumberFormat.getIntegerInstance().format(playlists.size), Modifier.weight(1f))
            }
            SortChips(PlaylistSort.entries, sort, { it.label }, { it.name }, "playlistSortChips", "playlistSort-", selectSort, accent)
        }
        if (scroller) FastScroller(sections, scrollTarget, { pinned.value }, header,
            { statusPx + 16.dp.toPx() + header.bottomPx.coerceAtLeast(0f) }, maxHeight - dock - 10.dp, accent)
    }
}

/** The tab's presentation of [source] for [sort] (and the phone's counts, [onPhone]): the order, its sections, each row's line. */
internal class PlaylistsShown(val source: List<Playlist>, val sort: PlaylistSort, val onPhone: dev.coda.android.ui.offline.OnPhone?,
    val playlists: List<Playlist>, val sections: List<LibrarySection>, val lines: List<String>) {
    fun isFor(source: List<Playlist>, sort: PlaylistSort, onPhone: dev.coda.android.ui.offline.OnPhone?) =
        this.source === source && this.sort == sort && this.onPhone === onPhone
}

internal fun playlistsShown(source: List<Playlist>, sort: PlaylistSort, onPhone: dev.coda.android.ui.offline.OnPhone?,
    now: Instant = Instant.now()): PlaylistsShown {
    val playlists = PlaylistOrder.sort(sort, source)
    // Offline, a playlist with tracks not here says how many are here ("21 of 23": kept or not); "1 of 2 tracks".
    val lines = playlists.map { p -> PlaylistOrder.metadata(p, sort, now, tracks = onPhone?.playlist(p.id)?.let { "$it tracks" }) }
    return PlaylistsShown(source, sort, onPhone, playlists, PlaylistOrder.sections(sort, playlists), lines)
}

/** [playlistsShown] on Dispatchers.Default (a large collection's sort and lines stay off Main). */
internal suspend fun presentPlaylists(source: List<Playlist>, sort: PlaylistSort, onPhone: dev.coda.android.ui.offline.OnPhone?,
    now: () -> Instant = Instant::now): PlaylistsShown =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { playlistsShown(source, sort, onPhone, now()) }

/**
 * The presentation made at once, in composition: the tab's first one, and the first one with playlists after an
 * empty one (a first load without a saved copy, the phone's list arriving), so the tab never shows "No playlists" or
 * loses its count while rows are being ordered. Null: the previous one stays until [presentPlaylists] answers.
 */
internal fun playlistsShownAtOnce(previous: PlaylistsShown?, source: List<Playlist>, sort: PlaylistSort,
    onPhone: dev.coda.android.ui.offline.OnPhone?): PlaylistsShown? =
    if (previous == null || (previous.playlists.isEmpty() && source.isNotEmpty())) playlistsShown(source, sort, onPhone) else null

/** The sort change's fade and jump to the top are due once the presentation [shown] has the new sort. */
internal fun sortFadeDue(faded: PlaylistSort, shown: PlaylistsShown) = shown.sort != faded

/**
 * The presentation, remade off Main for a new list, sort or phone count (a newer one cancels the older
 * work); until it is ready the previous one stays. Made at once only as [playlistsShownAtOnce] says (no empty frame).
 */
@Composable
private fun rememberPlaylistsShown(source: List<Playlist>, sort: PlaylistSort, onPhone: dev.coda.android.ui.offline.OnPhone?): PlaylistsShown {
    val made = remember { mutableStateOf<PlaylistsShown?>(null) }
    val atOnce = remember(source, sort, onPhone) { playlistsShownAtOnce(made.value, source, sort, onPhone) }
    LaunchedEffect(source, sort, onPhone) {
        if (atOnce != null) made.value = atOnce
        else if (made.value?.isFor(source, sort, onPhone) != true) made.value = presentPlaylists(source, sort, onPhone)
    }
    return atOnce ?: made.value!!
}

/** The row as it was (cover, name, supporting line), now on the tab's backdrop and with the metadata line. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PlaylistRow(playlist: dev.coda.core.subsonic.Playlist, meta: String, fetcher: CoverArtFetcher, open: (String) -> Unit) {
    val menus = dev.coda.android.ui.menu.LocalItemMenus.current
    val title = remember(playlist.name) { dev.coda.core.library.PlaylistName.parse(playlist.name) }
    ListItem(headlineContent = { Text(title.display, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(meta, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Box {
            CoverImage(fetcher, playlist.coverArt, Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)))
            PlaylistBadge(title, compact = true)
        } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.testTag("list-playlist-${playlist.id}").combinedClickable(onClick = { open(playlist.id) },
            onLongClick = { menus?.playlist(playlist) }, onLongClickLabel = "Playlist actions"))
}
