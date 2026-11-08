package dev.coda.android.ui.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.coda.android.ui.theme.AccentState
import dev.coda.core.library.QueueReorder
import dev.coda.core.library.TrackListPresentation
import dev.coda.core.playback.QueueEntry
import kotlin.math.abs
import kotlin.math.roundToInt

/** A laid-out list item: its index and key in the cell list and its top/bottom in the viewport (px, 0 = viewport top). */
internal class CellSpan(val index: Int, val key: Any, val top: Int, val bottom: Int)

/**
 * Where a held album lands. Only album boundaries are targets (a group start or the queue end), never a place
 * inside an album. [boundary] is that boundary's entry index in the queue; [y] the line in the viewport, clamped to its
 * edges; [edge] is -1/1 when the boundary is off screen above/below (the line pins to that edge and a chip names
 * [neighbour], the album the drop lands above/below); [block] the entries between the boundaries around the finger.
 */
internal data class AlbumDrop(val boundary: Int, val y: Float, val edge: Int, val neighbour: String, val block: IntRange) {
    val chip get() = "${if (edge < 0) "Above" else "Below"} “$neighbour”"
    val spoken get() = "Album lands ${if (edge < 0) "above" else "below"} $neighbour, off screen"
}

/**
 * The nearest album boundary to [finger] (viewport px). [entries] and [cells] must be one snapshot (the drag's original
 * queue and its cells) and [visible] its layout; anything inconsistent (a queue or layout change in between) gives null.
 * Off-screen cells are placed at the visible headers' or rows' mean height, by cell type.
 */
internal fun pickAlbumDrop(entries: List<QueueEntry>, cells: List<QueueCell>, visible: List<CellSpan>, viewport: Int, finger: Float): AlbumDrop? {
    if (entries.isEmpty() || visible.isEmpty() || cells.isEmpty()) return null
    if (visible.withIndex().any { (n, span) -> span.index != visible[0].index + n || span.index !in cells.indices || cells[span.index].key != span.key }) return null
    val position = entries.withIndex().associate { it.value.id to it.index }
    if (cells.any { it.entry.id !in position }) return null
    val groups = TrackListPresentation.queueGroups(entries).associateBy { it.startIndex }
    fun mean(spans: List<CellSpan>) = spans.takeIf { it.isNotEmpty() }?.let { s -> s.sumOf { it.bottom - it.top }.toFloat() / s.size }
    val (headerSpans, rowSpans) = visible.partition { cells[it.index].group != null }
    val all = mean(visible)!!
    val headerHeight = mean(headerSpans) ?: all; val rowHeight = mean(rowSpans) ?: all
    fun height(i: Int) = if (cells[i].group != null) headerHeight else rowHeight
    // tops[i]: top of cell i; tops[cells.size]: the bottom of the last cell (the queue end).
    val first = visible.first().index; val last = visible.last().index
    val tops = FloatArray(cells.size + 1)
    visible.forEach { tops[it.index] = it.top.toFloat() }
    tops[last + 1] = visible.last().bottom.toFloat()
    for (i in first - 1 downTo 0) tops[i] = tops[i + 1] - height(i)
    for (i in last + 2..cells.size) tops[i] = tops[i - 1] + height(i - 1)
    // The first cell of a group (its header, or a standalone row) starts it; the queue end follows the last cell.
    val boundaries = cells.indices.mapNotNull { i -> position.getValue(cells[i].entry.id).takeIf { it in groups }?.let { it to tops[i] } }
        .distinctBy { it.first } + (entries.size to tops[cells.size])
    if (boundaries.size != groups.size + 1) return null
    val (boundary, y) = boundaries.minBy { abs(it.second - finger) }
    val edge = if (y < 0f) -1 else if (y > viewport) 1 else 0
    val around = boundaries.indexOfLast { it.second <= finger }.coerceIn(0, boundaries.size - 2)
    val ordered = groups.values.sortedBy { it.startIndex }
    val named = if (edge < 0) groups[boundary] ?: ordered.last() else ordered.lastOrNull { it.startIndex < boundary } ?: ordered.first()
    val name = (if (named.isStandalone) named.entries.first().title else named.entries.first().album).ifEmpty { "Unknown Album" }
    return AlbumDrop(boundary, y.coerceIn(0f, viewport.toFloat()), edge, name, boundaries[around].first until boundaries[around + 1].first)
}

/** The reorder that drops a held album on queue [boundary] (an index of the original queue). */
internal fun QueueReorder.atBoundary(boundary: Int) = at(if (boundary > range.first) boundary - moving.size else boundary)

/** The accent drop line, pinned to an edge with a chip naming the target when its boundary is off screen. */
@Composable
internal fun BoxScope.AlbumDropMarker(drop: AlbumDrop, viewport: Int, accent: AccentState) {
    Box(Modifier.offset { IntOffset(0, (drop.y.roundToInt() - 1.dp.roundToPx()).coerceIn(0, (viewport - 2.dp.roundToPx()).coerceAtLeast(0))) }
        .zIndex(1f).fillMaxWidth().height(2.dp).testTag("queueDropLine").drawBehind {
            val mid = size.height / 2
            if (drop.edge != 0) drawRect(accent.text)
            else {
                drawLine(accent.text, Offset(14.dp.toPx(), mid), Offset(size.width - 20.dp.toPx(), mid), 2.dp.toPx())
                drawCircle(accent.text, 4.dp.toPx(), Offset(16.dp.toPx(), mid))
            }
        })
    if (drop.edge != 0) Row(Modifier.align(if (drop.edge < 0) Alignment.TopCenter else Alignment.BottomCenter).padding(16.dp, 8.dp).zIndex(3f)
        .shadow(6.dp, RoundedCornerShape(14.dp)).background(accent.container, RoundedCornerShape(14.dp)).height(28.dp)
        .padding(start = 8.dp, end = 12.dp).clearAndSetSemantics { contentDescription = drop.spoken; testTag = "queueDropChip" },
        verticalAlignment = Alignment.CenterVertically) {
        Icon(if (drop.edge < 0) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, null, Modifier.size(16.dp), tint = accent.text)
        Text(drop.chip, Modifier.padding(start = 4.dp), color = accent.text, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
