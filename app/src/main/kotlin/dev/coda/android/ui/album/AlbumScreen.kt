package dev.coda.android.ui.album

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import dev.coda.android.artwork.*
import dev.coda.android.ui.theme.AccentState
import dev.coda.android.ui.theme.SleeveFade
import dev.coda.android.ui.theme.pageBackdrop
import dev.coda.core.library.AlbumRow
import dev.coda.core.library.AlbumSections
import dev.coda.core.subsonic.Album
import dev.coda.core.subsonic.Song
import dev.coda.core.sync.AlbumResumeItem
import dev.coda.android.ui.common.*
import kotlinx.coroutines.launch

/** Applied (not state-read inside semantics) so lazy-item semantics invalidation never walks an unattached child. */
private val HideFromTalkBack = Modifier.semantics { hideFromAccessibility() }

/**
 * Hides a whole covered container: hideFromAccessibility only hides the node it is set on, so the controls inside a
 * container (pills, artist link, rating, Keep offline) would stay reachable. clearAndSetSemantics drops the subtree.
 */
private fun Modifier.hideSubtreeFromTalkBack(covered: Boolean, tag: String? = null): Modifier =
    if (covered) clearAndSetSemantics { tag?.let { testTag = it }; hideFromAccessibility() }
    else if (tag != null) testTag(tag) else this

val PlayFillKey = SemanticsPropertyKey<Int>("PlayFillArgb")
var SemanticsPropertyReceiver.playFillArgb by PlayFillKey

/**
 * A page opened from a list that never held the album starts with a stub that has no cover id. Until the page has
 * loaded (or failed) that "no cover" is only "not known yet", so the header shows the loading tile, not the logo.
 */
internal fun albumCoverPending(coverArt: String?, state: AlbumState) = coverArt == null && state.page == null && !state.failed

@Composable
fun AlbumScreen(album: Album, state: AlbumState, fetcher: CoverArtFetcher, accent: AccentState,
    currentId: String?, playing: Boolean, ready: Boolean, back: () -> Unit, retry: () -> Unit, play: (Int) -> Unit, toggle: () -> Unit,
    artist: (String) -> Unit = {},
    resume: AlbumResumeItem? = null, resumePlay: (Int) -> Unit = {},
    ) {
    val detail = state.page?.album ?: album
    val songs = state.page?.songs.orEmpty()
    val rows = remember(songs) { AlbumSections.rows(songs) }
    val ownsPlayback = songs.any { "song/${it.id}" == currentId }
    val artistTarget = detail.artistId ?: detail.artists?.firstOrNull()?.id
    val onPhone = dev.coda.android.ui.offline.LocalOnPhone.current
    // Offline, Resume album is offered only when the track it resumes is on the phone (one rule with the ⋮ sheet).
    val resumeIndex = albumResumeIndex(songs.map { it.id }, resume?.resumeSongId, currentId, onPhone?.let { it::has })
    val menus = dev.coda.android.ui.menu.LocalItemMenus.current
    val notice = dev.coda.android.ui.offline.LocalPhoneNotice.current
    // The way back's cross-fade keeps the same row in view.
    // By key, as the Albums grid, since the Resume row and the tracks here differ by side.
    val list = dev.coda.android.ui.offline.rememberAnchoredListState { key -> albumListIndex(rows, resumeIndex >= 0, key) }
    val scope = rememberCoroutineScope()
    // Publish the sleeve and play button, so Now Playing can open and close in place over this page.
    val stage = dev.coda.android.ui.player.LocalAlbumStage.current
    val stagePage = remember(album.id) { dev.coda.android.ui.player.AlbumStage.Page(album.id) }
    stagePage.scrolled = { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0 }
    stagePage.scrollToTop = { list.scrollToItem(0) }
    if (stage != null) DisposableEffect(stage, stagePage) { stage.register(stagePage); onDispose { stage.unregister(stagePage) } }
    fun rootRect(coordinates: androidx.compose.ui.layout.LayoutCoordinates) =
        androidx.compose.ui.geometry.Rect(coordinates.positionInRoot(), androidx.compose.ui.geometry.Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
    val actionsEnabled = ready && songs.isNotEmpty()
    val snackbar = remember { SnackbarHostState() }
    // The header has no room for the rating's error line, so a failed save is told through the snackbar.
    val ratingState = dev.coda.android.ui.library.LocalLibraryActions.current?.ratings?.collectAsState()?.value
    val ratingFailed = ratingState?.failed == true && ratingState.failedId == detail.id
    LaunchedEffect(ratingFailed, ratingState) {
        if (ratingFailed) { snackbar.currentSnackbarData?.dismiss(); snackbar.showNotice("Couldn't save rating. Try again.") }
    }
    var pageTop by remember { mutableFloatStateOf(0f) }
    var playTop by remember(detail.id) { mutableFloatStateOf(Float.POSITIVE_INFINITY) }
    var titleTop by remember(detail.id) { mutableFloatStateOf(Float.POSITIVE_INFINITY) }
    // Document-space bottoms of the hero controls the opaque bar can cover (for TalkBack, see below).
    var playBottom by remember(detail.id) { mutableFloatStateOf(Float.POSITIVE_INFINITY) }
    var titleBottom by remember(detail.id) { mutableFloatStateOf(Float.POSITIVE_INFINITY) }
    var infoBottom by remember(detail.id) { mutableFloatStateOf(Float.POSITIVE_INFINITY) }
    var infoTop by remember(detail.id) { mutableFloatStateOf(Float.POSITIVE_INFINITY) }
    val barHeight = dev.coda.android.ui.common.collapsedBarHeight()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val barBottom = with(density) { barHeight.toPx() }
    // Store document coordinates while the hero is composed; they survive lazy-item disposal.
    fun documentTop(coordinates: androidx.compose.ui.layout.LayoutCoordinates): Float =
        coordinates.positionInRoot().y - pageTop + list.firstVisibleItemScrollOffset
    val surfaceVisible by remember(barBottom) { derivedStateOf {
        list.firstVisibleItemIndex > 0 || (list.firstVisibleItemScrollOffset > 0 &&
            list.firstVisibleItemScrollOffset >= minOf(playTop, titleTop) - barBottom)
    } }
    // Header controls scrolled fully under the opaque bar must not stay focusable for TalkBack.
    fun covered(bottom: Float) = list.firstVisibleItemIndex > 0 ||
        (list.firstVisibleItemScrollOffset > 0 && list.firstVisibleItemScrollOffset >= bottom - barBottom)
    val playCovered by remember(barBottom) { derivedStateOf { covered(playBottom) } }
    // The title sits at Play's height. The pinned title fades in once the real title (measured) is fully under the bar,
    // so the page never shows two titles; the pinned Play follows once the real Play is fully under it.
    val titleVisible by remember(barBottom) { derivedStateOf { covered(titleBottom) } }
    val playVisible by remember(barBottom) { derivedStateOf { covered(playBottom) } }
    val titleCovered by remember(barBottom) { derivedStateOf { covered(titleBottom) } }
    val infoCovered by remember(barBottom) { derivedStateOf { covered(infoBottom) } }
    // Rows of the block go under the bar one by one: the artist link first, then the meta line and stars.
    val (artistRowBottom, metaRowBottom) = with(density) { albumRowBottomsPx() }
    val rowCover by remember(barBottom, artistRowBottom, metaRowBottom) { derivedStateOf {
        AlbumRowCover(covered(infoTop + artistRowBottom), covered(infoTop + metaRowBottom))
    } }
    val reducedMotion = !android.animation.ValueAnimator.areAnimatorsEnabled()
    val surfaceAlpha = dev.coda.android.ui.common.collapsedBarAlpha(surfaceVisible, "Album header surface")
    val titleAlpha by animateFloatAsState(if (titleVisible) 1f else 0f,
        tween(if (reducedMotion) 0 else 150, easing = LinearEasing), label = "Album header title")
    val playAlpha by animateFloatAsState(if (playVisible) 1f else 0f,
        tween(if (reducedMotion) 0 else 150, easing = LinearEasing), label = "Album header play")
    // One backdrop: the Now Playing page dye and glow, fixed behind the scrolling list.
    BoxWithConstraints(Modifier.fillMaxSize().pageBackdrop(accent).onGloballyPositioned { pageTop = it.positionInRoot().y }
        .then(dev.coda.android.ui.common.pageSemanticsOf(detail.name))) {
        val pageHeight = maxHeight
        LazyColumn(Modifier.fillMaxSize().testTag("albumScreen"), state = list, contentPadding = PaddingValues(bottom = dev.coda.android.ui.shell.LocalDockPadding.current + 16.dp)) {
            item(key = "header") {
                // TalkBack order: title, artist link, meta, rating (the block, a traversal group), then Play.
                BoxWithConstraints(Modifier.fillMaxWidth().semantics { isTraversalGroup = true }) {
                    val width = maxWidth
                    val sleeveBottom = width
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).onGloballyPositioned { stagePage.sleeve = rootRect(it) }) {
                        // Like the artist banner, no logo while the cover id is still unknown; the tile, then the cover's fade.
                        if (albumCoverPending(detail.coverArt, state)) Box(Modifier.fillMaxSize().testTag("albumHeaderFullBleed")) { CoverPlaceholder(missing = false) }
                        else CoverImage(fetcher, detail.artworkId, Modifier.fillMaxSize().testTag("albumHeaderFullBleed"), 1440)
                        Box(Modifier.fillMaxWidth().height(104.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .55f), Color.Transparent))))
                        // Fade into the backdrop colour at the sleeve's current edge, like Now Playing.
                        SleeveFade(accent, edge = {
                            if (list.firstVisibleItemIndex == 0) width.toPx() - list.firstVisibleItemScrollOffset else 0f
                        }, height = { pageHeight.toPx() }, fade = AlbumSleeveFade)
                    }
                    // The title block is centred on the Play button's centre (the sleeve's bottom edge), left of it;
                    // the track list starts right below. The block has a fixed height (see AlbumInfo).
                    Column(Modifier.padding(top = sleeveBottom - albumTextHeight() / 2)) {
                        AlbumInfo(detail, songs, accent, artistTarget, artist, Modifier.onGloballyPositioned { titleTop = documentTop(it); titleBottom = titleTop + it.size.height }
                                .then(if (titleCovered) HideFromTalkBack else Modifier),
                            Modifier.onGloballyPositioned { infoTop = documentTop(it); infoBottom = infoTop + it.size.height }
                                .hideSubtreeFromTalkBack(infoCovered), rowCover)
                        // No download chip on the page; the ⋮ menu holds the state and the actions.
                        // Never from the phone's copy, only from a live answer.
                        dev.coda.android.ui.common.KeepOfflineSync("album/${detail.id}", songs, dev.coda.android.ui.offline.keepSyncFresh(state.loading, state.failed, onPhone), title = detail.name)
                    }
                    FilledIconButton(onClick = { if (ownsPlayback) toggle() else play(0) },
                        enabled = actionsEnabled, shape = RoundedCornerShape(if (ownsPlayback && playing) 22.dp else 32.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent.fill, contentColor = accent.onFill),
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = sleeveBottom - PlayOverlap, end = 16.dp)
                            // Hidden while Now Playing's play button stands in for it (in place).
                            .graphicsLayer { alpha = if (stage?.covers(stagePage) == true) 0f else 1f }
                            .shadow(12.dp, RoundedCornerShape(if (ownsPlayback && playing) 22.dp else 32.dp)).size(64.dp)
                            .onGloballyPositioned { playTop = documentTop(it); playBottom = playTop + it.size.height; stagePage.play = rootRect(it) }
                            .testTag("playAlbum").semantics { playFillArgb = accent.fill.toArgb(); traversalIndex = 2f }.then(if (playCovered) HideFromTalkBack else Modifier)) {
                        Icon(if (ownsPlayback && playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (ownsPlayback && playing) "Pause album" else "Play album", Modifier.size(30.dp))
                    }
                }
            }
            if (resumeIndex >= 0) item(key = "resume") {
                ResumeRow(resumeIndex, songs, accent, ready) { resumePlay(resumeIndex) }
            }
            if (state.failed && state.page == null) item {
                dev.coda.android.ui.offline.CantLoadBlock("Couldn't load tracks", "Check your connection and try again.", retry)
            }
            if (state.loading && state.page == null) items(6) {
                SkeletonText(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 20.dp, vertical = 8.dp))
            }
            if (!state.loading && !state.failed && songs.isEmpty()) item {
                StateBlock(Icons.Default.MusicOff, "No tracks", null)
            }
            items(rows, key = ::albumRowKey,
                contentType = { it::class }) { row ->
                if (row is AlbumRow.Disc) { DiscHeader(row, accent); return@items }
                val index = (row as AlbumRow.Track).index
                val song = row.song
                val current = currentId == "song/${song.id}"
                // Offline, a track not on the phone is dimmed only; a tap says why instead of playing.
                val missing = onPhone != null && !onPhone.has(song.id)
                Row(Modifier.padding(horizontal = 8.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(if (current) Color.White.copy(alpha = .07f) else Color.Transparent)
                    .combinedClickable(enabled = ready || missing, onClickLabel = "Play", onClick = { if (missing) notice(dev.coda.android.ui.offline.NOT_ON_PHONE) else play(index) },
                        onLongClick = { menus?.track(song, dev.coda.android.ui.menu.TrackMenuContext(onAlbumPage = true)) },
                        onLongClickLabel = "Track actions").heightIn(min = 48.dp).padding(vertical = 8.dp, horizontal = 12.dp)
                    .then(if (missing) Modifier.alpha(dev.coda.android.ui.offline.MISSING_ALPHA).semantics { stateDescription = dev.coda.android.ui.offline.NOT_ON_PHONE } else Modifier)
                    .testTag("track-${song.id}"), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterEnd) {
                        if (current) Icon(Icons.AutoMirrored.Filled.VolumeUp, "Playing", Modifier.size(16.dp), tint = accent.text)
                        else Text("${song.track ?: index + 1}", color = Text3, fontSize = 13.sp)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(song.title, fontSize = 15.sp, lineHeight = 19.sp, color = if (current) accent.text else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (song.artistName != detail.artistName) Text(song.artistName, fontSize = 12.5.sp, lineHeight = 16.sp, color = Text2,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    // One stop per row; the row's long-press action ("Track actions") opens the same sheet.
                    IconButton(onClick = { menus?.track(song, dev.coda.android.ui.menu.TrackMenuContext(onAlbumPage = true)) },
                        Modifier.testTag("track-menu-${song.id}").decorative()) { Icon(Icons.Default.MoreVert, "Actions for ${song.title}") }
                    TrackDuration(song.duration?.let { "${it/60}:${(it%60).toString().padStart(2, '0')}" }.orEmpty(), Text3, seconds = song.duration?.toLong())
                }
            }
        }
        // A fixed copy takes over from the scrolling sleeve scrim from the first pixel.
        Box(Modifier.fillMaxWidth().height(104.dp).graphicsLayer {
            alpha = if (list.firstVisibleItemIndex > 0) 1f else
                (list.firstVisibleItemScrollOffset / with(density) { 104.dp.toPx() }).coerceIn(0f, 1f)
        }.background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .55f), Color.Transparent))))
        dev.coda.android.ui.common.CollapsedBarSurface(accent, surfaceAlpha, "albumPinnedSurface")
        if (titleAlpha > 0f || playAlpha > 0f) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(start = 56.dp, end = 56.dp).then(dev.coda.android.ui.common.PageHeader),
                verticalAlignment = Alignment.CenterVertically) {
                if (titleAlpha > 0f) Text(detail.name, Modifier.weight(1f).padding(end = 8.dp).graphicsLayer { alpha = titleAlpha }
                    .then(if (titleVisible) Modifier else Modifier.clearAndSetSemantics { }).testTag("pinnedAlbumTitle"),
                    fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                else Spacer(Modifier.weight(1f))
                if (playAlpha > 0f) Box(Modifier.size(48.dp).graphicsLayer { alpha = playAlpha }
                    .then(if (playVisible) Modifier else Modifier.clearAndSetSemantics { }), contentAlignment = Alignment.Center) {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                        FilledIconButton(onClick = { if (ownsPlayback) toggle() else play(0) },
                            enabled = actionsEnabled && playVisible,
                            shape = RoundedCornerShape(if (ownsPlayback && playing) 14.dp else 20.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent.fill, contentColor = accent.onFill),
                            modifier = Modifier.size(40.dp).testTag("pinnedPlayAlbum")) {
                            Icon(if (ownsPlayback && playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                if (ownsPlayback && playing) "Pause album" else "Play album", Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
        // Back, the pinned title and the menu are read before the track list they are drawn over.
        Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().then(dev.coda.android.ui.common.PageHeader)) {
            // Same 48 dp glass circle (fill and ripple) as the back button.
            // The album's ⋮ opens the one album sheet, as everywhere else.
            GlassIconButton({ menus?.album(detail) }, Modifier.padding(4.dp).testTag("albumMenu"), Color.Black.copy(alpha = .36f * (1f - surfaceAlpha))) { Icon(Icons.Default.MoreVert, "Album actions") }
        }
        dev.coda.android.ui.common.CodaSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = dev.coda.android.ui.shell.LocalDockPadding.current))
        if (state.loading && state.page == null) DelayedProgress(true, Modifier.statusBarsPadding())
        GlassIconButton(back, Modifier.statusBarsPadding().padding(4.dp).then(dev.coda.android.ui.common.PageHeader), Color.Black.copy(alpha = .36f * (1f - surfaceAlpha))) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
        }
    }
}

private val Text3 = Color(0xFFF1F1EE).copy(alpha = .44f)

/** The track list's LazyColumn key of [row]. */
internal fun albumRowKey(row: AlbumRow): Any = when (row) { is AlbumRow.Disc -> "disc-${row.disc}"; is AlbumRow.Track -> row.song.id }

/**
 * This album list's index of a [key] seen on the other side of the phone/live cross-fade ([ScrollAnchor]).
 * The Resume row comes and goes and the phone may have fewer tracks, so a row keeps its place by key, not index.
 * The list: the header, Resume ([resume]), then [rows]. Null: not in this list.
 */
internal fun albumListIndex(rows: List<AlbumRow>, resume: Boolean, key: Any): Int? = when (key) {
    "header" -> 0
    "resume" -> 1.takeIf { resume }
    else -> rows.indexOfFirst { albumRowKey(it) == key }.takeIf { it >= 0 }
        ?.let { 1 + (if (resume) 1 else 0) + it }
}

/** The accent resume card (one tap that resumes; Add remaining to queue is in the page's ⋮). */
@Composable
private fun ResumeRow(index: Int, songs: List<Song>, accent: AccentState, ready: Boolean, resume: () -> Unit) {
    val song = songs[index]
    Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(accent.container).testTag("albumResume")
        .clickable(enabled = ready, role = Role.Button, onClick = resume)
        .semantics { contentDescription = "Resume whole album from track ${index + 1}, ${song.title}" }
        .heightIn(min = 64.dp).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.History, null, tint = accent.text)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("Resume album", fontWeight = FontWeight.SemiBold)
            Text("Track ${index + 1} · ${song.title}", color = Text2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal fun albumDuration(seconds: Int): String = if (seconds in 1..59) "1 min" else "${seconds / 60} min"

/** Spec 5.2: 12 sp caps heading with a disc icon ("DISC 1 · 7 TRACKS · 43 MIN"), 14 dp above, plus the disc's own title. */
@Composable
private fun DiscHeader(row: AlbumRow.Disc, accent: AccentState) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp).testTag("disc-${row.disc}")
        .semantics(mergeDescendants = true) { heading() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Album, null, Modifier.size(16.dp), tint = accent.text)
            Text(row.label, Modifier.padding(start = 8.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        row.title?.let { Text(it, Modifier.padding(start = 24.dp, top = 2.dp), fontSize = 13.sp, color = accent.text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}
