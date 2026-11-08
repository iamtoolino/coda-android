package dev.coda.android.ui.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.coda.android.artwork.CoverArtFetcher
import dev.coda.android.artwork.CoverImage
import dev.coda.android.ui.common.*
import dev.coda.android.ui.theme.AccentState
import dev.coda.core.library.AlbumSectionThreshold
import dev.coda.core.subsonic.Album
import kotlinx.coroutines.launch
import java.text.NumberFormat
import kotlin.math.roundToInt

/** Two-finger pinch (spec §2 Grid): zoom below 0.85 → 3 per row, above 1.15 → 2 per row. One finger passes through. */
private fun Modifier.pinchDensity(dense: Boolean, set: (Boolean) -> Unit) = pointerInput(dense) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var zoom = 1f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.none { it.pressed }) break
            if (event.changes.count { it.pressed } >= 2) {
                zoom *= event.calculateZoom()
                if (zoom < .85f && !dense) { set(true); zoom = 1f } else if (zoom > 1.15f && dense) { set(false); zoom = 1f }
                event.changes.forEach { it.consume() }
            }
        }
    }
}

/** The grid index of the album with [key] (an album id) after one header per section; else null (a header key too). */
internal fun albumGridIndex(albums: List<Album>, sections: List<dev.coda.core.library.LibrarySection>, key: Any): Int? =
    albums.indexOfFirst { it.id == key }.takeIf { it >= 0 }?.let { i ->
        i + if (sections.isEmpty()) 0 else sections.indexOfLast { it.start <= i } + 1 }

/**
 * Albums tab (frames B1–B10): enter-always header with count and density toggle, the seven sorts as chips,
 * per-sort pinned sections on a backdrop band and the fast-scroll thumb (none for Random).
 */
@Composable
internal fun AlbumsTab(state: CollectionState, sort: AlbumSort, dense: Boolean, fetcher: CoverArtFetcher, accent: AccentState,
    backdrop: TabBackdrop?, retry: () -> Unit, openAlbum: (Album) -> Unit, loadMore: () -> Unit, selectSort: (AlbumSort) -> Unit,
    setDense: (Boolean) -> Unit, reshuffle: () -> Unit) {
    val albums = state.albums
    val sections = state.sections
    val density = LocalDensity.current
    // The way back's cross-fade keeps the same album in view (by its key: the phone's list is shorter).
    val grid = dev.coda.android.ui.offline.rememberAnchoredGridState { albumGridIndex(albums, sections, it) }
    val header = remember { EnterAlwaysHeader() }
    // At the very top the title always shows (also after a programmatic scroll or jump to the start).
    LaunchedEffect(grid, header) { snapshotFlow { grid.canScrollBackward }.collect { if (!it) header.show() } }
    val scope = rememberCoroutineScope()
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val statusPx = with(density) { statusBar.toPx() }
    val dock = dev.coda.android.ui.shell.LocalDockPadding.current
    val fromPhone = dev.coda.android.ui.offline.LocalOnPhone.current != null
    val scroller = sections.isNotEmpty() && sort != AlbumSort.RANDOM && albums.size >= AlbumSectionThreshold
    val headers = remember(sections) { IntArray(sections.size) { sections[it].start + it } }
    val line = { statusPx + header.bottomPx.coerceAtLeast(0f) - with(density) { 4.dp.toPx() } }
    val pinned = remember(headers) { derivedStateOf { pinnedSection(headers, grid.visibleItems(), line()) } }
    // Chip change: the grid crossfades in 150 ms from the top.
    val fade = remember { Animatable(1f) }
    var shownSort by remember { mutableStateOf(sort) }
    LaunchedEffect(sort) {
        if (shownSort != sort) { shownSort = sort; fade.snapTo(0f); grid.scrollToItem(0); header.show(); fade.animateTo(1f, tween(150)) }
    }
    // Density change keeps the anchor item.
    var shownDense by remember { mutableStateOf(dense) }
    LaunchedEffect(dense) {
        if (shownDense != dense) { shownDense = dense; grid.requestScrollToItem(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset) }
    }
    val latest by rememberUpdatedState(state)
    LaunchedEffect(grid, state.freshCount, state.complete, state.moreFailed) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (!latest.complete && !latest.moreFailed && !latest.loadingMore && last >= latest.freshCount - 20) loadMore()
        }
    }
    val scrollTarget = remember(grid) { grid.scrollTarget() }
    // The A–Z letter sheet, only while an accessibility service is on and for the Title sort.
    val letters = rememberAccessibilityOn() && scroller && sort == AlbumSort.TITLE
    var lettersOpen by remember { mutableStateOf(false) }
    val view = androidx.compose.ui.platform.LocalView.current
    val jumpFocus = remember(view) { JumpFocus(view) }
    val columns = if (dense) 3 else 2
    BoxWithConstraints(Modifier.fillMaxSize().pageSemantics("Albums").testTag("albumsTab")) {
        val headerHeight = with(density) { header.heightPx.toDp() }
        LazyVerticalGrid(GridCells.Fixed(columns), Modifier.fillMaxSize().nestedScroll(header.connection).pinchDensity(dense, setDense)
            .graphicsLayer { alpha = fade.value }.testTag("albumsGrid")
            .semantics {
                collectionInfo = CollectionInfo(rowCount = (albums.size + columns - 1) / columns, columnCount = columns)
                if (letters) customActions = listOf(CustomAccessibilityAction("Jump to letter") { lettersOpen = true; true })
            },
            state = grid, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = statusBar + headerHeight,
                bottom = dock + 16.dp),
            horizontalArrangement = Arrangement.spacedBy(if (dense) 8.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (dense) 14.dp else 18.dp)) {
            val full: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }
            when {
                albums.isEmpty() && state.failed -> item(key = "failed", span = full) {
                    dev.coda.android.ui.offline.CantLoadBlock("Couldn't load albums", "Check your connection and try again.", retry)
                }
                albums.isEmpty() && state.loading -> {
                    item(key = "progress", span = full) { DelayedProgress(true) }
                    items(6, key = { "skeleton-$it" }) {
                        Column(Modifier.testTag("albumsSkeleton")) { Skeleton(Modifier.fillMaxWidth().aspectRatio(1f)); SkeletonText(Modifier.padding(top = 10.dp)) }
                    }
                }
                albums.isEmpty() && fromPhone -> item(key = "empty", span = full) {
                    // Recently Played, Most Played and Top Rated list only some albums: their empty list is not an empty phone.
                    if (sort == AlbumSort.PLAYED || sort == AlbumSort.FREQUENT || sort == AlbumSort.RATED)
                        StateBlock(Icons.Default.Album, "No albums on this phone in “${sort.label}”", "Other sorts show every album here.")
                    else StateBlock(Icons.Default.Album, "No music on this phone yet", "Albums you play or prefer offline show here.")
                }
                albums.isEmpty() -> item(key = "empty", span = full) { StateBlock(Icons.Default.Album, "No albums", "Navidrome hasn't indexed any albums yet.") }
                sections.isEmpty() -> items(albums, key = { it.id }, contentType = { "album" }) { album ->
                    AlbumGridTile(album, fetcher, dense, openAlbum, Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = spring(stiffness = 600f)))
                }
                else -> sections.forEachIndexed { s, section ->
                    item(key = "section-$s-${section.label}", span = full, contentType = "section") {
                        SectionHeader(section.label, section.count, accent, Modifier.testTag("section-${section.label}").then(jumpFocus.target(headers[s])), section.stars,
                            NumberFormat.getIntegerInstance().format(section.count))
                    }
                    items(section.count, key = { albums[section.start + it].id }, contentType = { "album" }) { i ->
                        AlbumGridTile(albums[section.start + i], fetcher, dense, openAlbum,
                            Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = spring(stiffness = 600f)))
                    }
                }
            }
            if (albums.isNotEmpty() && !state.complete && state.moreFailed) item(span = full, key = "moreFailed") {
                RetryLine("Couldn't load more albums", loadMore)
            } else if (albums.isNotEmpty() && !state.complete && state.loadingMore) item(span = full, key = "loadingMore") {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp).testTag("loadingMore"))
            }
        }
        // Spec §0.3: one pinned header under the title bar, pushed up by the next one; On its own backdrop band.
        val s = pinned.value
        if (s >= 0 && sections.isNotEmpty()) PinnedHeaderBand(backdrop, grid, header.connection, line, { pinnedPush(headers, grid.visibleItems(), s, line(), it) },
            Modifier.padding(horizontal = 16.dp)) {
            val section = sections[s]
            SectionHeader(section.label, section.count, accent, stars = section.stars, countText = NumberFormat.getIntegerInstance().format(section.count))
        }
        if (backdrop != null) Box(Modifier.fillMaxSize().backdropBand(backdrop) {
            if (header.offsetPx <= -header.heightPx || !grid.canScrollBackward) 0f else statusPx + header.bottomPx
        })
        Column(Modifier.offset { IntOffset(0, header.drawOffset(statusPx).roundToInt()) }.statusBarsPadding().fillMaxWidth()
            .onSizeChanged { header.heightPx = it.height.toFloat() }.pageHeader()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                LibraryTitle("Albums", if (albums.isEmpty()) null else NumberFormat.getIntegerInstance().format(albums.size), Modifier.weight(1f))
                if (letters) { AzChip(lettersOpen, { lettersOpen = true }); Spacer(Modifier.width(4.dp)) }
                DensityToggle(dense, setDense, accent)
            }
            AlbumSortChips(sort, selectSort, reshuffle, accent)
        }
        if (scroller) FastScroller(sections, scrollTarget, { pinned.value }, header,
            { statusPx + 16.dp.toPx() + header.bottomPx.coerceAtLeast(0f) }, maxHeight - dock - 10.dp, accent)
        if (letters && lettersOpen) LetterSheet(sections, pinned.value, albums.size, dev.coda.android.R.plurals.albums, accent,
            pick = { s -> jumpFocus.choose(headers[s]); scope.launch { header.hide(); scrollTarget.jump(headers[s]) } },
            dismiss = { lettersOpen = false; scope.launch { jumpFocus.focusChosen() } })
    }
}

/**
 * Spec §2 density toggle: a connected 2 × 44 × 32 dp group; the selected segment wears accentContainer. * each segment's touch box is 48 × 48 (4 dp beyond the pill's outer side, 8 dp above and below, invisible); the pill is drawn
 * as before and the ripple stays inside the visible half.
 */
@Composable
private fun DensityToggle(dense: Boolean, set: (Boolean) -> Unit, accent: AccentState) {
    val segments = listOf(Triple(true, Icons.Default.GridOn, "3 per row"), Triple(false, Icons.Default.GridView, "2 per row"))
    val sources = remember { List(2) { androidx.compose.foundation.interaction.MutableInteractionSource() } }
    Box(Modifier.size(96.dp, 48.dp)) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, LibOutline, RoundedCornerShape(16.dp))) {
            segments.forEachIndexed { i, (value, icon, _) ->
                val selected = dense == value
                Box(Modifier.size(44.dp, 32.dp).background(if (selected) accent.container else Color.Transparent)
                    .indication(sources[i], androidx.compose.material3.ripple()), contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(20.dp), tint = if (selected) accent.text else LibText2)
                }
            }
        }
        Row(Modifier.selectableGroup()) {
            segments.forEachIndexed { i, (value, _, label) ->
                Box(Modifier.size(48.dp).selectable(dense == value, sources[i], indication = null, role = Role.RadioButton) { set(value) }
                    .semantics { contentDescription = label }.testTag(if (value) "density3" else "density2"))
            }
        }
    }
}

/** Albums: the shared sort chips plus, for Random, the reshuffle chip. */
@Composable
private fun AlbumSortChips(sort: AlbumSort, select: (AlbumSort) -> Unit, reshuffle: () -> Unit, accent: AccentState) {
    SortChips(AlbumSort.entries, sort, { it.label }, { it.name }, "albumSortChips", "albumSort-", select, accent) {
        if (sort == AlbumSort.RANDOM) item(key = "reshuffle") {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(accent.container)
                .clickable(onClickLabel = "Reshuffle", role = Role.Button) { reshuffle() }.testTag("albumReshuffle")
                .semantics { contentDescription = "Reshuffle" }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Refresh, null, Modifier.size(18.dp), tint = accent.text)
            }
        }
    }
}

/** Spec §2 tiles: 3 per row 13 / 17 title and 12 artist, 2 per row 14 / 18 and 12.5; both one line so rows align. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AlbumGridTile(album: Album, fetcher: CoverArtFetcher, dense: Boolean, open: (Album) -> Unit, modifier: Modifier) {
    val menus = dev.coda.android.ui.menu.LocalItemMenus.current
    Column(modifier.testTag("grid-album-${album.id}").albumCell(currentRating(album.id, album.userRating))
        .combinedClickable(onClick = { open(album) }, onClickLabel = "Open album", onLongClick = { menus?.album(album) },
        onLongClickLabel = "Album actions")) {
        Box {
            CoverImage(fetcher, album.artworkId, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(if (dense) 8.dp else 10.dp)))
            AlbumRatingBadge(currentRating(album.id, album.userRating), BadgeSize.Large)
        }
        Text(album.name, Modifier.padding(top = if (dense) 6.dp else 8.dp), fontSize = if (dense) 13.sp else 14.sp,
            lineHeight = if (dense) 17.sp else 18.sp, fontWeight = FontWeight.SemiBold, color = LibText1, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // Offline, a partial album's line under the cover ends with "7 of 12" (never on the artwork).
        val part = dev.coda.android.ui.offline.LocalOnPhone.current?.album(album.id)
        Text(if (part == null) album.artistName else "${album.artistName} · $part", fontSize = if (dense) 12.sp else 12.5.sp, lineHeight = 16.sp,
            color = LibText2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
