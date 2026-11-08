package dev.coda.core.library

import dev.coda.core.playback.QueueEntry

/** A drag is a local preview. Only release sends this range and destination to Media3. */
data class QueueReorder(val original: List<QueueEntry>, val range: IntRange, val album: Boolean, val destination: Int = range.first) {
    val moving = original.slice(range)
    val remaining = original.filterIndexed { index, _ -> index !in range }
    val preview get() = remaining.toMutableList().apply { addAll(destination, moving) }.toList()
    fun at(index: Int): QueueReorder {
        val bounded = index.coerceIn(0, remaining.size)
        val allowed = if (album) TrackListPresentation.queueGroups(original).map { group ->
            val start = group.startIndex
            if (start <= range.first) start else (start - moving.size).coerceAtLeast(range.first)
        } + remaining.size else (0..remaining.size).toList()
        return copy(destination = allowed.minByOrNull { kotlin.math.abs(it - bounded) } ?: 0)
    }
    fun cancel(): List<QueueEntry> = original
}
