package dev.coda.android.ui.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import kotlinx.coroutines.CoroutineScope
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import dev.coda.android.ui.common.NoticeAction
import dev.coda.android.ui.common.showNotice
import dev.coda.android.ui.common.decorative
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.zIndex
import dev.coda.android.ui.common.TrackListTrackLabel
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import dev.coda.android.artwork.*
import dev.coda.android.ui.player.playbackTime
import dev.coda.android.ui.theme.AccentState
import dev.coda.core.library.TrackListPresentation
import dev.coda.android.playback.readReplayGain
import dev.coda.core.playback.QueueEntry
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import java.util.UUID

import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import dev.coda.core.library.QueueReorder
import kotlin.math.abs

private val QueueSurface = Color(0xFF131518)
/** Every way out of the sheet (back, scrim, TalkBack, header drag, "Go to album") ends here; [action] runs at most once. */
internal class DismissOnce(private val action: () -> Unit) {
    private var done = false
    operator fun invoke() { if (!done) { done = true; action() } }
}

internal const val DismissDistanceDp = 56f
internal const val DismissFlingDpPerSecond = 125f

/** [velocity] is positive downward, in px/s. A fling decides by its direction; a slower release by the travel. */
internal fun shouldDismissQueueSheet(offset: Float, velocity: Float, density: Float) = when {
    velocity > DismissFlingDpPerSecond * density -> true
    velocity < -DismissFlingDpPerSecond * density -> false
    else -> offset > DismissDistanceDp * density
}

/**
 * The queue sheet is hidden only by dragging its handle or its "Queue" header. [offset] is the sheet's downward
 * translation; a release past [DismissDistanceDp], or a downward fling faster than [DismissFlingDpPerSecond] (Material's
 * ModalBottomSheet norms), slides it off and dismisses it, any other release springs it back. An upward fling never dismisses. A gesture is accepted or ignored as a whole at its start: one that begins while
 * the sheet animates stays ignored until it ends, even after the animation has finished.
 */
internal class HeaderDismissDrag(private val scope: CoroutineScope, private val screenHeight: Float, private val density: Float) {
    var offset by mutableFloatStateOf(0f); private set
    private var animating = false
    private var accepted = false
    val progress get() = (offset / screenHeight).coerceIn(0f, 1f)

    fun begin() { accepted = !animating }

    fun drag(delta: Float) { if (accepted && !animating) offset = (offset + delta).coerceAtLeast(0f) }

    fun release(velocity: Float, dismiss: () -> Unit) {
        if (!accepted || animating) { accepted = false; return }
        accepted = false
        animating = true
        scope.launch {
            val hide = shouldDismissQueueSheet(offset, velocity, density)
            val from = offset
            try {
                animate(from, if (hide) screenHeight else 0f, velocity.coerceAtLeast(0f), spring(stiffness = Spring.StiffnessMediumLow)) { v, _ -> offset = v }
            } finally { animating = false }
            if (hide) dismiss()
        }
    }
}

@Composable
internal fun Modifier.headerDismissDrag(drag: HeaderDismissDrag, dismiss: () -> Unit): Modifier {
    val latestDismiss by rememberUpdatedState(dismiss)
    // Acceptance is decided when the finger goes down (draggable only starts after touch slop, possibly after the animation).
    return pointerInput(drag) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            drag.begin()
            do { val event = awaitPointerEvent(PointerEventPass.Initial) } while (event.changes.any { it.pressed })
        }
    }.draggable(rememberDraggableState { drag.drag(it) }, Orientation.Vertical, onDragStopped = { drag.release(it) { latestDismiss() } })
}

/** An album move waiting to be revealed: the header's entry, the queue before and the queue the move should produce. */
internal class PendingReveal(val id: UUID, val original: List<UUID>, val expected: List<UUID>)
internal enum class RevealOutcome { WAITING, LANDED, SUPERSEDED }
internal const val RevealTimeoutMs = 2_000L

/** Still the order before the move: waiting. The expected order: landed. Anything else: another change got in first. */
internal fun revealOutcome(current: List<UUID>, move: PendingReveal) = when (current) {
    move.expected -> RevealOutcome.LANDED
    move.original -> RevealOutcome.WAITING
    else -> RevealOutcome.SUPERSEDED
}

/** True when an item at [offset] with [size] is not fully inside the viewport [start]..[end]. */
internal fun partlyOutside(offset: Int, size: Int, start: Int, end: Int) = offset < start || offset + size > end

internal enum class RevealResult { SCROLLED, NOT_NEEDED, SUPERSEDED, TIMED_OUT }

/** The cell key of the header (or standalone track) of the group that contains [id] in [entries]; merged runs of one album share one. */
internal fun revealCellKey(entries: List<QueueEntry>, id: UUID): String? =
    TrackListPresentation.queueGroups(entries).firstOrNull { group -> group.entries.any { it.id == id } }
        ?.let { group -> if (group.isStandalone) group.id.toString() else "header-${group.id}" }

/**
 * Reveals a moved album once the move has landed and the list has laid out the new order. Supersession (the queue became
 * some other order) is watched through the layout wait, the scroll decision and the scroll itself, which is cancelled when it
 * happens; a move that never lands times out after [timeoutMs]. Everything it reads is snapshot state, so a test can drive it.
 */
internal suspend fun revealMovedAlbum(move: PendingReveal, ids: () -> List<UUID>, layoutReady: () -> Boolean, headerIndex: () -> Int,
    needsScroll: (Int) -> Boolean, scrollTo: suspend (Int) -> Unit, timeoutMs: Long = RevealTimeoutMs): RevealResult {
    withTimeoutOrNull(timeoutMs) {
        snapshotFlow { val outcome = revealOutcome(ids(), move); outcome == RevealOutcome.SUPERSEDED || outcome == RevealOutcome.LANDED && layoutReady() }
            .first { it }
    } ?: return RevealResult.TIMED_OUT
    // The state at the moment of the decision; superseded wins over a layout that happens to match a newer order.
    if (revealOutcome(ids(), move) == RevealOutcome.SUPERSEDED) return RevealResult.SUPERSEDED
    val index = headerIndex()
    if (index < 0 || !needsScroll(index)) return RevealResult.NOT_NEEDED
    return coroutineScope {
        val scroll = launch { scrollTo(index) }
        val watcher = launch { snapshotFlow { revealOutcome(ids(), move) != RevealOutcome.LANDED }.first { it }; scroll.cancel() }
        scroll.join()
        watcher.cancel()
        if (scroll.isCancelled) RevealResult.SUPERSEDED else RevealResult.SCROLLED
    }
}

internal data class QueueCell(val key: String, val entry: QueueEntry, val group: TrackListPresentation.QueueGroup? = null, val multiDisc: Boolean = false)
internal fun initiallyExpandedQueueEntries(entries: List<QueueEntry>, currentId: UUID?): Set<UUID> =
    TrackListPresentation.queueGroups(entries).firstOrNull { group ->
        !group.isStandalone && group.entries.any { it.id == currentId }
    }?.entries?.map { it.id }?.toSet().orEmpty()

internal fun queueCells(entries: List<QueueEntry>, expanded: Set<UUID> = emptySet()): List<QueueCell> = buildList {
    TrackListPresentation.queueGroups(entries).forEach { group ->
        if (!group.isStandalone) add(QueueCell("header-${group.id}", group.entries.first(), group))
        val multi = group.entries.map { it.discNumber ?: 1 }.distinct().size > 1
        if (group.isStandalone || group.entries.any { it.id in expanded }) {
            group.entries.forEach { add(QueueCell(it.id.toString(), it, multiDisc = multi)) }
        }
    }
}

/** A held album leaves the list as it is (no slot, no fold); only a single-track drag previews its new place. */
internal fun queueDragCells(drag: QueueReorder, expanded: Set<UUID>): List<QueueCell> =
    queueCells(if (drag.album) drag.original else drag.preview, expanded)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(controller: MediaController, revision: Int, fetcher: CoverArtFetcher, accent: AccentState, onDismissRequest: () -> Unit) {
    val latestDismissRequest by rememberUpdatedState(onDismissRequest)
    val onDismiss = remember { DismissOnce { latestDismissRequest() } }
    var saving by remember { mutableStateOf<List<String>?>(null) }
    var savedId by remember { mutableStateOf<String?>(null) }
    val openPlaylist = dev.coda.android.ui.library.LocalOpenPlaylist.current
    val openAlbum = dev.coda.android.ui.library.LocalOpenAlbum.current
    saving?.let { ids -> dev.coda.android.ui.library.SavePlaylistDialog(ids, { saving = null }, { id -> saving = null; savedId = id }) }
    // "Load for offline" (replaces "Keep queue offline"): a one-shot read-ahead of the
    // whole queue, its progress from the shared download state.
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as dev.coda.android.CodaApp
    val library by app.offlineLibrary.collectAsState()
    val downloads by (library?.downloads ?: remember { kotlinx.coroutines.flow.MutableStateFlow(dev.coda.core.cache.DownloadState()) }).collectAsState()
    val queueScope = rememberCoroutineScope()
    val askNotifications = dev.coda.android.ui.common.rememberNotificationPermissionAsk()
    val items = remember(controller, revision) { List(controller.mediaItemCount, controller::getMediaItemAt) }
    val entries = remember(items) { items.mapIndexed { index, item ->
        val m = item.mediaMetadata
        QueueEntry(item.mediaId.removePrefix("song/"), id = m.extras?.getString("queueEntryId")?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: UUID.nameUUIDFromBytes("$index:${item.mediaId}".toByteArray()),
            albumId = m.extras?.getString("albumId"), album = m.albumTitle?.toString().orEmpty(), title = m.title?.toString().orEmpty(),
            artist = m.artist?.toString().orEmpty(), trackNumber = m.trackNumber, discNumber = m.discNumber,
            formatName = m.extras?.getString("formatName"), bitRate = m.extras?.takeIf { it.containsKey("bitRate") }?.getInt("bitRate"),
            year = m.releaseYear, albumArtist = m.albumArtist?.toString(), coverArtId = m.extras?.getString("coverArtId"), durationMs = m.durationMs ?: 0,
            // "Keep queue offline" stores these entries' metadata, gain included.
            replayGain = m.extras?.readReplayGain())
    } }
    val latestEntries by rememberUpdatedState(entries)
    val current = controller.currentMediaItemIndex.coerceAtLeast(0)
    val list = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    var drag by remember { mutableStateOf<QueueReorder?>(null) }
    var fingerY by remember { mutableFloatStateOf(0f) }
    var grabY by remember { mutableFloatStateOf(0f) }
    var listBounds by remember { mutableStateOf(Rect.Zero) }
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    // Seed once per sheet opening; playback changes must not override manual toggles.
    var expanded by remember { mutableStateOf(initiallyExpandedQueueEntries(entries, entries.getOrNull(current)?.id)) }
    val originalCells = remember(entries, expanded) { queueCells(entries, expanded) }
    val displayCells = remember(entries, drag, expanded) { drag?.let { queueDragCells(it, expanded) } ?: originalCells }
    val latestCells by rememberUpdatedState(displayCells)
    val latestOriginalCells by rememberUpdatedState(originalCells)
    // A held album's target is the album boundary nearest to the finger, recomputed as the finger or the list moves.
    // Queue and cells both come from the drag's own snapshot; the picker rejects a layout that doesn't match them.
    val albumCells by remember { derivedStateOf { drag?.takeIf { it.album }?.let { queueDragCells(it, expanded) } } }
    val albumDrop by remember { derivedStateOf {
        val d = drag?.takeIf { it.album }; val cells = albumCells
        if (d == null || cells == null) null else pickAlbumDrop(d.original, cells,
            list.layoutInfo.visibleItemsInfo.map { CellSpan(it.index, it.key, it.offset, it.offset + it.size) }, list.layoutInfo.viewportSize.height, fingerY - listBounds.top)
    } }
    // Another controller replaced, removed or reordered entries during a drag: the held indices are stale, drop the drag.
    LaunchedEffect(entries) { drag?.let { d -> if (d.original.map { it.id } != entries.map { it.id }) drag = null } }
    val albumBlock by remember { derivedStateOf { albumDrop?.block } } // rows recompose only when the block changes
    // First visible cell at pickup. A cancelled drag puts the viewport back on it: the anchor pin of a
    // single-track drag may have moved the viewport onto the first non-held cell.
    val pickup = remember { arrayOfNulls<Pair<Any, Int>>(1) }
    // Once per opening, after the viewport exists. Playback updates never re-snap browsing.
    LaunchedEffect(Unit) {
        val height = snapshotFlow { list.layoutInfo.viewportSize.height }.first { it > 0 }
        if (entries.isNotEmpty()) {
            val id = entries[current.coerceAtMost(entries.lastIndex)].id
            val cell = originalCells.indexOfFirst { it.key == id.toString() }
            list.scrollToItem(cell.coerceAtLeast(0), -height / 3)
        }
    }
    LaunchedEffect(savedId) {
        savedId?.let { id ->
            if (snackbar.showNotice("Saved as playlist", NoticeAction.Open) == SnackbarResult.ActionPerformed) { onDismiss(); openPlaylist(id) }
            savedId = null
        }
    }
    // U3: moves are said by a polite live region (announceForAccessibility is deprecated on API 36).
    var moved by remember { mutableStateOf<String?>(null) }
    fun announce(position: Int, album: Boolean = false) {
        val text = "${if (album) "Album" else "Track"} moved to position ${position + 1}"
        // The same words twice must still change the region: a trailing full stop is not spoken.
        moved = if (moved == text) "$text." else text
    }
    // After an album move, scroll the queue so the moved album's header is fully visible. The pending reveal is
    // state keyed on the move: it ends when the move lands (the queue equals the expected order and the list has laid it out),
    // is superseded (the queue became some other order), or times out (rejected move). A later list change never scrolls.
    var pending by remember { mutableStateOf<PendingReveal?>(null) }
    fun commit(d: QueueReorder) {
        // Do not apply stale indices if another controller changed the queue during a gesture.
        if (latestEntries.map { it.id } != d.original.map { it.id } || d.destination == d.range.first) return
        if (d.album) {
            pending = PendingReveal(d.moving.first().id, d.original.map { it.id }, d.preview.map { it.id })
            controller.moveMediaItems(d.range.first, d.range.last + 1, d.destination)
        }
        else controller.moveMediaItem(d.range.first, d.destination)
        announce(d.destination, d.album)
    }
    LaunchedEffect(pending) {
        val move = pending ?: return@LaunchedEffect
        try {
            revealMovedAlbum(move, { latestEntries.map { it.id } },
                layoutReady = { list.layoutInfo.let { info -> info.visibleItemsInfo.isNotEmpty() && info.totalItemsCount == latestCells.size &&
                    info.visibleItemsInfo.all { latestCells.getOrNull(it.index)?.key == it.key } } },
                headerIndex = { revealCellKey(latestEntries, move.id)?.let { key -> latestCells.indexOfFirst { it.key == key } } ?: -1 },
                needsScroll = { index -> val info = list.layoutInfo; val shown = info.visibleItemsInfo.firstOrNull { it.index == index }
                    shown == null || partlyOutside(shown.offset, shown.size, info.viewportStartOffset, info.viewportEndOffset) },
                scrollTo = { list.animateScrollToItem(it) })
        } finally {
            if (pending === move) pending = null
        }
    }
    fun move(id: UUID, target: Int) {
        val from = latestEntries.indexOfFirst { it.id == id }
        if (from >= 0 && target in latestEntries.indices && from != target) commit(QueueReorder(latestEntries, from..from, false).at(target))
    }
    fun removeRange(range: IntRange, message: String) {
        if (range.isEmpty() || range.last >= controller.mediaItemCount) return
        val removed = range.map { controller.getMediaItemAt(it) }
        controller.removeMediaItems(range.first, range.last + 1)
        scope.launch {
            if (snackbar.showNotice(message, NoticeAction.Undo) == SnackbarResult.ActionPerformed) {
                controller.addMediaItems(range.first.coerceAtMost(controller.mediaItemCount), removed)
            }
        }
    }
    fun clearQueue() {
        if (items.isEmpty()) return
        val cleared = ClearedQueue(items, controller.currentMediaItemIndex.coerceAtLeast(0), controller.currentPosition, controller.playWhenReady)
        controller.clearMediaItems()
        scope.launch {
            if (snackbar.showNotice("Queue cleared", NoticeAction.Undo) == SnackbarResult.ActionPerformed) {
                cleared.restore(controller::setMediaItems, controller::play)
            }
        }
    }
    fun updateTarget() {
        val d = drag?.takeUnless { it.album } ?: return
        val movingIds = d.moving.map { it.id }.toSet()
        val candidate = latestCells.filter { it.entry.id !in movingIds }.mapNotNull { cell ->
            bounds[cell.key]?.takeIf { it.bottom > listBounds.top && it.top < listBounds.bottom }?.let { cell to it }
        }.minByOrNull { abs(it.second.center.y - fingerY) } ?: return
        val cell = candidate.first
        val at = d.remaining.indexOfFirst { it.id == cell.entry.id }
        val after = fingerY > candidate.second.center.y
        val target = at + if (after && cell.group == null) 1 else 0
        val next = d.at(target)
        if (next.destination == d.destination) return
        // LazyColumn keeps its first visible *key* in place. When the held cell is (or passes) that key,
        // moving its slot dragged the viewport along, which re-targeted the slot again: a runaway scroll with
        // the finger outside the edge zones. Pin the first visible cell that is not held instead.
        val held = queueDragCells(d, expanded).filter { it.entry.id in movingIds }.map { it.key }.toSet()
        val anchor = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key !in held }
        drag = next
        if (anchor != null) {
            val index = queueDragCells(next, expanded).indexOfFirst { it.key == anchor.key }
            if (index >= 0) list.requestScrollToItem(index, -anchor.offset)
        }
    }
    // A stationary finger keeps scrolling: velocity is integrated on frames, not pointer deltas.
    LaunchedEffect(drag != null) {
        if (drag == null) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (drag != null) {
            val now = withFrameNanos { it }
            val dt = ((now - previous) / 1_000_000_000f).coerceAtMost(.05f)
            previous = now
            val edge = with(density) { 64.dp.toPx() }
            val velocity = when {
                fingerY < listBounds.top + edge -> -((listBounds.top + edge - fingerY) / edge).coerceIn(0f, 1f)
                fingerY > listBounds.bottom - edge -> ((fingerY - listBounds.bottom + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (velocity != 0f && list.scrollBy(velocity * with(density) { 600.dp.toPx() } * dt) != 0f) updateTarget()
        }
    }
    var queueMenu by remember { mutableStateOf(false) }
    val offline = controller.sessionExtras.getBoolean("offline")
    val cachedSongs = controller.sessionExtras.getStringArrayList("cachedSongs").orEmpty().toSet()
    val queueSongs = remember(entries) { entries.map { it.songId }.distinct() }
    val queueOnPhone = queueSongs.count { it in cachedSongs }
    // "All 40 ready offline until Sat": the earliest keep-until of the queue's tracks, only once everything is on the phone.
    val queueUntil = remember(queueSongs, queueOnPhone) { if (queueSongs.isNotEmpty() && queueOnPhone >= queueSongs.size) library?.status(null, queueSongs)?.let {
        if (it.untilSpaceRunsLow) "space runs low" else it.availableUntil?.let { wall -> dev.coda.core.cache.DownloadLines.day(wall) } } else null }
    // The counts the lines show are the queue's entries, as in "40 tracks" (a duplicate counts twice); the load itself takes distinct tracks.
    val entriesOnPhone = entries.count { it.songId in cachedSongs }
    // The sheet's own gestures are off, so no touch or scroll on the body can ever move it in any phase. Only the
    // handle and the "Queue" header drive this offset: they drag the sheet down, and a far or fast release hides it.
    val sheetDrag = remember { HeaderDismissDrag(scope, view.height.toFloat(), density.density) }
    ModalBottomSheet(onDismissRequest = { onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false,
        scrimColor = Color.Black.copy(alpha = .32f * (1f - sheetDrag.progress)),
        containerColor = QueueSurface, contentColor = Color(0xFFF1F1EE),
        // The handle bar sits inside the one drag area below, together with the gap and the "Queue" header.
        dragHandle = null,
        // M3 only exposes the TalkBack dismiss action together with its drag gestures, which are off here.
        modifier = Modifier.statusBarsPadding().padding(top = 80.dp).testTag("queueSheet").semantics { dismiss { onDismiss(); true } }
            .graphicsLayer { translationY = sheetDrag.offset }) {
        // Keep the viewport fixed when an album run becomes a compact drag card.
        // Resizing the modal under the held pointer can cancel the drag or dismiss the sheet.
        Column(Modifier.fillMaxHeight()) {
            Column(Modifier.fillMaxWidth().weight(1f)) {
                // One drag area for dismissing: the whole header block, i.e. the handle bar, the gap under it, the
                // "Queue" header row (title, subtitle, options), the strip down to the list and the notices above the list.
                Column(Modifier.fillMaxWidth().headerDismissDrag(sheetDrag) { onDismiss() }.testTag("queueDragArea")) {
                Box(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth().testTag("queueHandle").decorative(), Alignment.Center) { BottomSheetDefaults.DragHandle() }
                    dev.coda.android.ui.common.PoliteAnnouncement(moved, "queueMoveAnnouncement")
                }
                Row(Modifier.fillMaxWidth().background(QueueSurface).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Queue", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                        val minutes = entries.sumOf { it.durationMs } / 60000
                        val cached = entries.count { it.songId in cachedSongs }
                        val length = if (entries.isEmpty()) "0 min" else if (minutes < 60) "${minutes.coerceAtLeast(1)} min" else "${minutes / 60} h ${minutes % 60} min"
                        // While the queue downloads its header says so, with a 3 dp bar under it.
                        val running = downloads.queue
                        val line = dev.coda.core.cache.DownloadLines.queueHeader(queueSongs.size, queueOnPhone, downloads, cached)
                        // While the queue loads, the length makes room: "40 tracks · Caching 26 of 40".
                        Text(listOf("${entries.size} ${if (entries.size == 1) "track" else "tracks"}", if (running != null) "" else length, line).filter { it.isNotEmpty() }.joinToString(" · "), Modifier.testTag("queueHeaderLine"), fontSize = 12.sp,
                            color = if (running != null) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .65f))
                        if (running != null) LinearProgressIndicator({ running.onPhone / running.total.coerceAtLeast(1).toFloat() },
                            Modifier.padding(top = 6.dp).fillMaxWidth().height(3.dp).testTag("queueDownloadBar"), color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.White.copy(alpha = .12f), drawStopIndicator = {}, gapSize = 0.dp)
                    }
                    Box {
                        IconButton(onClick = { queueMenu = true }, Modifier.testTag("queueOptions")) { Icon(Icons.Default.MoreVert, "Queue options") }
                        // "Popover: Studio compact": Collapse/Expand all stay (fixed fact), then Save as playlist and
                        // Load for offline, then Clear queue in red (no Undo).
                        val grouped = TrackListPresentation.queueGroups(entries).any { !it.isStandalone }
                        dev.coda.android.ui.menu.CodaPopover(queueMenu, { queueMenu = false }) {
                            dev.coda.android.ui.menu.PopoverItem("Collapse all albums", Icons.Default.UnfoldLess, {
                                queueMenu = false
                                expanded = emptySet()
                            }, Modifier.testTag("collapseAllQueue"), enabled = grouped)
                            dev.coda.android.ui.menu.PopoverItem("Expand all albums", Icons.Default.UnfoldMore, {
                                queueMenu = false
                                expanded = TrackListPresentation.queueGroups(entries).filterNot { it.isStandalone }.flatMap { it.entries }.map { it.id }.toSet()
                            }, Modifier.testTag("expandAllQueue"), enabled = grouped)
                            dev.coda.android.ui.menu.PopoverDivider()
                            dev.coda.android.ui.menu.PopoverItem("Save as playlist", Icons.AutoMirrored.Filled.PlaylistAdd,
                                { queueMenu = false; saving = items.map { it.mediaId.removePrefix("song/") } }, enabled = items.isNotEmpty())
                            val entry = dev.coda.core.cache.DownloadLines.queueEntry(entries.size, entriesOnPhone, downloads, queueUntil, queueSongs.size - queueOnPhone)
                            if (entry.running) dev.coda.android.ui.menu.PopoverProgressItem(entry.label, Icons.Default.Download,
                                downloads.queue?.let { it.onPhone / it.total.coerceAtLeast(1).toFloat() }, entry.line, "Stop caching the queue",
                                { queueScope.launch { library?.stopQueueDownload() } }, Modifier.testTag("downloadQueue"))
                            else dev.coda.android.ui.menu.PopoverItem(entry.label, Icons.Default.Download, {
                                queueMenu = false
                                val tracks = entries.map(dev.coda.core.cache.KeptTrack::from)
                                askNotifications()
                                // Once, how long the loaded tracks stay (the Heard once time).
                                val stays = app.cacheSettings.policy.value.heardOnce.let { if (it.hours == null) null else it.label }
                                val missing = queueSongs.size - queueOnPhone
                                queueScope.launch {
                                    library?.downloadQueue(tracks)
                                    snackbar.showNotice(dev.coda.core.cache.DownloadLines.queueLoadMessage(missing, stays))
                                }
                            }, Modifier.testTag("downloadQueue"), enabled = entry.enabled && library != null && entries.isNotEmpty(),
                                line = entry.line.takeIf { entries.isNotEmpty() })
                            dev.coda.android.ui.menu.PopoverDivider()
                            dev.coda.android.ui.menu.PopoverItem("Clear queue", Icons.Default.Delete, { queueMenu = false; clearQueue() },
                                Modifier.testTag("clearQueue"), enabled = items.isNotEmpty(), danger = true)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                // The playback service publishes the hint from the shared download state, like the Offline notice.
                if (controller.sessionExtras.getBoolean(dev.coda.android.playback.QUEUE_WAITS_FOR_WIFI))
                    WaitingForWifiHint(controller.sessionExtras.getString("cacheProgress").orEmpty())
                if (offline) Text("Offline · ${entries.count { it.songId in cachedSongs }} tracks on this phone will play", Modifier.background(QueueSurface).padding(horizontal = 20.dp, vertical = 8.dp), fontSize = 12.sp)
                }
                if (entries.isEmpty()) Text("Queue is empty\nPlay an album or a song to start", Modifier.padding(24.dp))
                Box(Modifier.fillMaxWidth().weight(1f).background(QueueSurface).onGloballyPositioned { listBounds = it.boundsInRoot() }
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(onDragStart = { point ->
                            val y = point.y + listBounds.top
                            val hit = latestCells.firstOrNull { bounds[it.key]?.let { b -> y >= b.top && y < b.bottom } == true }
                            if (hit != null) {
                                val at = latestEntries.indexOfFirst { it.id == hit.entry.id }
                                if (at >= 0) {
                                    val range = at until at + (hit.group?.entries?.size ?: 1)
                                    drag = QueueReorder(latestEntries, range, hit.group != null)
                                    pickup[0] = list.layoutInfo.visibleItemsInfo.firstOrNull()?.let { it.key to it.offset }
                                    fingerY = y; grabY = y - (bounds[hit.key]?.top ?: y)
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            }
                        }, onDrag = { change, amount -> change.consume(); fingerY += amount.y; updateTarget() },
                            onDragEnd = { drag?.let { d -> commit(if (d.album) albumDrop?.let { d.atBoundary(it.boundary) } ?: d else d) }; drag = null },
                            onDragCancel = {
                                drag = null
                                pickup[0]?.let { (key, offset) ->
                                    val index = latestOriginalCells.indexOfFirst { it.key == key }
                                    if (index >= 0) list.requestScrollToItem(index, -offset)
                                }
                            })
                    }) {
                    LazyColumn(state = list, userScrollEnabled = drag == null, modifier = Modifier.testTag("queueList").fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
                        displayCells.forEach { cell ->
                            item(cell.key) {
                                DisposableEffect(cell.key) { onDispose { bounds.remove(cell.key) } }
                                val at = entries.indexOfFirst { it.id == cell.entry.id }
                                val lifted = drag?.let { d -> if (d.album) cell.entry.id in d.moving.map { it.id } else cell.key == d.moving.first().id.toString() } == true
                                // A held album stays in place as a ghost; an expanded album under the finger moves as one block.
                                val ghost = lifted && drag?.album == true
                                val washed = !lifted && albumBlock?.let { at in it && it.count() > 1 && entries[it.first].id in expanded } == true
                                val group = cell.group
                                val actions = if (group != null) listOf(
                                    CustomAccessibilityAction("Move album up") {
                                        val groups = TrackListPresentation.queueGroups(latestEntries)
                                        val previous = groups.lastOrNull { it.startIndex < at }
                                        if (previous != null) commit(QueueReorder(latestEntries, at until at + group.entries.size, true).at(previous.startIndex)); true
                                    },
                                    CustomAccessibilityAction("Move album down") {
                                        val next = TrackListPresentation.queueGroups(latestEntries).firstOrNull { it.startIndex > at }
                                        if (next != null) commit(QueueReorder(latestEntries, at until at + group.entries.size, true).at(at + next.entries.size)); true
                                    },
                                    CustomAccessibilityAction("Remove album") { removeRange(at until at + group.entries.size, "Removed album from queue"); true },
                                    CustomAccessibilityAction(if (group.entries.any { it.id in expanded }) "Collapse album" else "Expand album") {
                                        expanded = if (group.entries.any { it.id in expanded }) expanded - group.entries.map { it.id }.toSet() else expanded + group.entries.map { it.id }; true },
                                    CustomAccessibilityAction("Go to album") { cell.entry.albumId?.let { onDismiss(); openAlbum(it) }; true })
                                else listOf(
                                    CustomAccessibilityAction("Move up") { move(cell.entry.id, at - 1); true },
                                    CustomAccessibilityAction("Move down") { move(cell.entry.id, at + 1); true },
                                    CustomAccessibilityAction("Play next") { val now = controller.currentMediaItemIndex; if (at != now) move(cell.entry.id, if (at < now) now else (now + 1).coerceAtMost(entries.lastIndex)); true },
                                    CustomAccessibilityAction("Remove") { removeRange(at..at, "Removed from queue"); true })
                                val modifier = Modifier.animateItem().background(QueueSurface).onGloballyPositioned { bounds[cell.key] = it.boundsInRoot() }
                                // U1: the row's one TalkBack stop (the node that names the track) carries its actions and state.
                                val rowSemantics = Modifier.testTag(if (group == null) "queueRow-${cell.entry.id}" else "queueGroup-${cell.entry.id}")
                                    .semantics { customActions = actions; if (group != null) heading() else stateDescription = if (at < current) "Played" else if (at == current) "Current track" else "Queued" }
                                QueueSwipe(modifier, drag == null, { removeRange(at until at + (group?.entries?.size ?: 1), if (group == null) "Removed from queue" else "Removed album from queue") }) {
                                    Box(Modifier.background(QueueSurface).then(if (ghost) Modifier.alpha(.35f) else Modifier).drawWithContent {
                                        if (!lifted || ghost) { drawContent(); if (washed) drawRect(Color.White.copy(alpha = .04f)) }
                                        else drawLine(accent.text, Offset(20.dp.toPx(), 1.dp.toPx()), Offset(size.width - 20.dp.toPx(), 1.dp.toPx()), 2.dp.toPx())
                                    }) {
                                        if (group != null) QueueAlbum(cell.entry, group.entries, fetcher, rowSemantics,
                                            closed = group.entries.none { it.id in expanded },
                                            playing = group.entries.any { it.id == entries.getOrNull(current)?.id }, accent = accent.text,
                                            toggle = { expanded = if (group.entries.any { it.id in expanded }) expanded - group.entries.map { it.id }.toSet() else expanded + group.entries.map { it.id } })
                                        else QueueTrack(cell.entry, at < current, at == current, TrackListPresentation.queueGroups(if (drag?.album == true) drag!!.original else drag?.preview ?: entries).first { g -> g.entries.any { it.id == cell.entry.id } }.isStandalone,
                                            cell.multiDisc, fetcher, offline && cell.entry.songId !in cachedSongs, accent, rowSemantics) { controller.seekTo(at, 0); controller.play() }
                                    }
                                }
                            }
                        }
                    }
                    albumDrop?.let { AlbumDropMarker(it, list.layoutInfo.viewportSize.height, accent) }
                    drag?.let { d ->
                        // The lifted card is independent of LazyColumn recycling, so holding at an edge
                        // keeps both the gesture and opaque artwork alive after its source scrolls away.
                        Box(Modifier.zIndex(2f).graphicsLayer {
                            translationY = fingerY - listBounds.top - grabY
                            scaleX = 1.03f; scaleY = 1.03f; shadowElevation = 8.dp.toPx()
                        }.background(QueueSurface).clearAndSetSemantics { testTag = "queueLifted" }) {
                            if (d.album) QueueAlbum(d.moving.first(), d.moving, fetcher,
                                playing = d.moving.any { it.id == entries.getOrNull(current)?.id }, accent = accent.text)
                            else QueueTrack(d.moving.first(), false, d.moving.first().id == entries.getOrNull(current)?.id,
                                TrackListPresentation.queueGroups(d.original).first { it.entries.any { e -> e.id == d.moving.first().id } }.isStandalone,
                                d.original.map { it.discNumber ?: 1 }.distinct().size > 1, fetcher, false, accent) { }
                        }
                    }
                }
            }
            dev.coda.android.ui.common.CodaSnackbarHost(snackbar, Modifier.fillMaxWidth().testTag("queueSnackbar"))
        }
    }
}

@Composable
private fun QueueAlbum(entry: QueueEntry, tracks: List<QueueEntry>, fetcher: CoverArtFetcher, rowSemantics: Modifier = Modifier, closed: Boolean = true,
    playing: Boolean = false, accent: Color = Color.White, toggle: (() -> Unit)? = null) {
    var pressed by remember { mutableStateOf(false) }
    val latestToggle by rememberUpdatedState(toggle)
    Row(Modifier.fillMaxWidth().background(if (pressed) Color(0xFF34373C) else Color(0xFF1D1F22))
        .then(rowSemantics)
        .semantics(mergeDescendants = true) {
            stateDescription = if (closed) "Collapsed" else "Expanded"
            if (toggle != null) onClick(label = if (closed) "Expand album" else "Collapse album") { latestToggle?.invoke(); true }
        }
        .pointerInput(Unit) {
            // The list owns long-press dragging. Do not install a competing clickable/long-click
            // recognizer here. Reject near-long presses too, even if released before drag starts.
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                pressed = true
                try {
                    val up = waitForUpOrCancellation()
                    if (up != null && QueueTapGuard.isAlbumTap(up.uptimeMillis - down.uptimeMillis, viewConfiguration.longPressTimeoutMillis)) {
                        up.consume()
                        latestToggle?.invoke()
                    }
                } finally { pressed = false }
            }
        }.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        CoverImage(fetcher, entry.coverArtId, Modifier.size(40.dp).clip(RoundedCornerShape(7.dp)))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(entry.album.ifEmpty { "Unknown Album" }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val artist = entry.albumArtist ?: entry.artist
            val year = albumYear(tracks)
            val totalMs = tracks.sumOf { it.durationMs }
            val tail = albumSubtitleTail(year, totalMs)
            val subtitleColor = Color.White.copy(alpha = .6f)
            // Only the artist truncates; year and duration always stay visible.
            Row(Modifier.clearAndSetSemantics {
                contentDescription = albumSubtitleSpoken(artist, year, totalMs)
                text = androidx.compose.ui.text.AnnotatedString(artist + tail)
            }) {
                Text(artist, Modifier.weight(1f, fill = false), fontSize = 12.sp, color = subtitleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tail, fontSize = 12.sp, color = subtitleColor, maxLines = 1, softWrap = false)
            }
        }
        if (playing) Icon(Icons.Default.VolumeUp, "Current album", Modifier.size(18.dp), tint = accent)
        // The header row itself expands (its click and the Expand/Collapse action): the button is for touch.
        if (toggle != null) IconButton(onClick = toggle, Modifier.decorative()) {
            Icon(if (closed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                if (closed) "Expand album" else "Collapse album")
        }
    }
}

@Composable
private fun QueueTrack(entry: QueueEntry, played: Boolean, current: Boolean, standalone: Boolean, multiDisc: Boolean, fetcher: CoverArtFetcher, unavailable: Boolean, accent: AccentState,
    rowSemantics: Modifier = Modifier, play: () -> Unit) {
    // Dim contents only: the opaque backing must never fade and reveal the moving backdrop.
    val viewConfiguration = LocalViewConfiguration.current
    val guard = remember(viewConfiguration) { QueueTapGuard { viewConfiguration.longPressTimeoutMillis } }
    val latestPlay by rememberUpdatedState(play)
    // The guard watches the raw pointer events on the Initial pass (before clickable sees the release), so a release
    // that ends a long press never reaches the tap action (seek + play). Taps and drags are unchanged.
    Row(Modifier.fillMaxWidth().background(if (current) Color(0xFF242528) else QueueSurface).then(rowSemantics)
        .pointerInput(guard) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                guard.onDown(down.uptimeMillis)
                val up = waitForUpOrCancellation(PointerEventPass.Initial)
                if (up != null) {
                    guard.onUp(up.uptimeMillis)
                    // clickable runs on the Main pass of this same release; forget the press on the Final pass so a
                    // drag-ended long press cannot suppress a later accessibility click.
                    awaitPointerEvent(PointerEventPass.Final)
                }
                guard.onCancel()
            }
        }
        .clickable(onClickLabel = "Play track") { if (guard.shouldRunTap()) latestPlay() }
        .heightIn(min = 52.dp).padding(horizontal = 20.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        TrackListTrackLabel(fetcher, entry.coverArtId, entry.title, entry.artist, entry.trackNumber, entry.discNumber, multiDisc, standalone, current, accent.text,
            Modifier.weight(1f).alpha(if (unavailable || played) .45f else 1f))
        if (unavailable) Icon(Icons.Default.RadioButtonUnchecked, "Not on this phone", Modifier.size(14.dp), tint = Color.White.copy(alpha = .4f))
        dev.coda.android.ui.common.TrackDuration(playbackTime(entry.durationMs), Color.White.copy(alpha = if (unavailable || played) .25f else .5f), Modifier.padding(start = 8.dp), fontSize = 12.sp,
            seconds = entry.durationMs.takeIf { it > 0 }?.let { (it + 500) / 1000 })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSwipe(modifier: Modifier, enabled: Boolean, remove: () -> Unit, content: @Composable () -> Unit) {
    val dismiss = rememberSwipeToDismissBoxState(positionalThreshold = { it * .4f })
    val latestRemove by rememberUpdatedState(remove)
    LaunchedEffect(dismiss) {
        snapshotFlow { dismiss.settledValue }.collect { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                dismiss.snapTo(SwipeToDismissBoxValue.Settled)
                latestRemove()
            }
        }
    }
    SwipeToDismissBox(state = dismiss, enableDismissFromStartToEnd = false, enableDismissFromEndToStart = enabled, modifier = modifier,
        backgroundContent = {
            Row(Modifier.fillMaxSize().background(Color(0xFFE5675E)).padding(end = 20.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Delete, null, tint = Color(0xFF2A0B08)); Text("Remove", color = Color(0xFF2A0B08))
            }
        }) { content() }
}

/**
 * "Load for offline" runs on any network; it only waits with no network at all, with nothing to confirm.
 * Minimal hint until the design studio's UI. Amber: an app-level moment, not accent.
 */
@Composable
internal fun WaitingForWifiHint(progress: String) {
    val amber = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).background(amber.copy(alpha = .10f), RoundedCornerShape(12.dp))
        .heightIn(min = 48.dp).padding(horizontal = 12.dp).testTag("waitingForWifiHint"), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Pause, null, Modifier.size(18.dp), tint = amber)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(dev.coda.core.cache.DownloadLines.WAITING_FOR_NETWORK, color = amber, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(if (progress.isEmpty()) "Downloads when you're online" else "$progress · downloads when you're online",
                fontSize = 12.sp, color = Color.White.copy(alpha = .7f))
        }
    }
}

/**
 * What Clear queue removed, for its Undo. Also the play intent, as Swift's QueueSnapshot.wasPlaying
 * (PlayerController.swift:404): clearing resets it, so a queue that was playing plays again after Undo.
 */
internal class ClearedQueue<T>(private val items: List<T>, private val index: Int, private val position: Long, private val wasPlaying: Boolean) {
    fun restore(setItems: (List<T>, Int, Long) -> Unit, play: () -> Unit) {
        setItems(items, index.coerceAtMost(items.lastIndex), position)
        if (wasPlaying) play()
    }
}
