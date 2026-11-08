package dev.coda.android.ui.queue

import dev.coda.core.playback.QueueEntry

private const val NBSP = " "

/** macOS `formatCollectionDuration` (ContentView.swift): "1h 12m" from one hour up, else "53m"; minutes truncate. */
internal fun formatCollectionDuration(totalMs: Long): String {
    val minutes = (totalMs.coerceAtLeast(0) / 60_000).toInt()
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}

/** The part of the album header subtitle after the artist: " · 2026 · 53m" (year and duration, each with its separator, are omitted when unknown or zero, like macOS). Non-breaking spaces keep the dots attached. */
internal fun albumSubtitleTail(year: Int?, totalMs: Long): String =
    (if (year != null && year > 0) "$NBSP·$NBSP$year" else "") + (if (totalMs > 0) "$NBSP·$NBSP${formatCollectionDuration(totalMs)}" else "")

internal fun albumSubtitle(artist: String, year: Int?, totalMs: Long): String = artist + albumSubtitleTail(year, totalMs)

internal fun albumSubtitleSpoken(artist: String, year: Int?, totalMs: Long): String {
    val minutes = (totalMs.coerceAtLeast(0) / 60_000).toInt()
    val length = if (minutes >= 60) "${minutes / 60} ${if (minutes / 60 == 1) "hour" else "hours"} ${minutes % 60} minutes" else "$minutes minutes"
    return listOfNotNull(artist, year?.takeIf { it > 0 }?.toString(), length.takeIf { totalMs > 0 }).joinToString(", ")
}

internal fun albumYear(entries: List<QueueEntry>): Int? = entries.firstNotNullOfOrNull { it.year?.takeIf { y -> y > 0 } }
