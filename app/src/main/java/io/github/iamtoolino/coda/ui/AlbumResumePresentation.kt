package io.github.iamtoolino.coda.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.iamtoolino.coda.data.AlbumPage
import io.github.iamtoolino.coda.data.AlbumResumeItem
import io.github.iamtoolino.coda.data.Song

/** Presentation only: the current entry suppresses the marker, even when paused/restored. */
internal fun albumResumeIndex(
    page: AlbumPage?,
    items: List<AlbumResumeItem>,
    currentAlbumId: String?,
): Int? {
    if (page == null || page.album.id == currentAlbumId) return null
    val target = items.firstOrNull { it.album.id == page.album.id }?.resumeSongId ?: return null
    return page.songs.indexOfFirst { it.id == target }.takeIf { it >= 0 }
}

internal fun LazyListScope.albumTrackItems(
    page: AlbumPage,
    sections: List<AlbumDiscSection>,
    resumeIndex: Int?,
    currentSongId: String?,
    onResumePlay: (List<Song>) -> Unit,
    onResumeAppend: (List<Song>) -> Unit,
    onSong: (Int) -> Unit,
) {
    // The already-loaded page is canonical. No click-time fetch or bookmark mutation is needed.
    val remaining = resumeIndex?.let { index ->
        page.songs.drop(index).map { it.copy(coverArt = page.album.artworkId) }
    }.orEmpty()
    sections.forEach { section ->
        if (shouldShowDiscHeaders(sections)) {
            item(key = "disc:${section.number}:${section.songs.first().index}") {
                ResumeBackground(active = resumeIndex != null && section.songs.first().index > resumeIndex) {
                    AlbumDiscHeader(section, horizontalPadding = 10.dp)
                }
            }
        }
        items(section.songs, key = { it.value.id }) { indexed ->
            val inRemainder = resumeIndex != null && indexed.index >= resumeIndex
            ResumeBackground(
                active = inRemainder,
                start = indexed.index == resumeIndex,
                end = indexed.index == page.songs.lastIndex,
            ) {
                if (indexed.index == resumeIndex) {
                    AlbumResumeMarker(
                        onPlay = { onResumePlay(remaining) },
                        onAppend = { onResumeAppend(remaining) },
                    )
                }
                SongRow(
                    song = indexed.value,
                    isPlaying = currentSongId == indexed.value.id,
                    showArtist = false,
                    horizontalPadding = 8.dp,
                    onClick = { onSong(indexed.index) },
                )
            }
        }
    }
}

@Composable
private fun ResumeBackground(
    active: Boolean,
    start: Boolean = false,
    end: Boolean = false,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(
        topStart = if (start) 8.dp else 0.dp,
        topEnd = if (start) 8.dp else 0.dp,
        bottomStart = if (end) 8.dp else 0.dp,
        bottomEnd = if (end) 8.dp else 0.dp,
    )
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).then(
            if (active) Modifier.clip(shape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            else Modifier,
        ),
    ) { content() }
}

@Composable
internal fun AlbumResumeMarker(onPlay: () -> Unit, onAppend: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Bookmark, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text("RESUME", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onPlay, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.PlayArrow, "Play remaining tracks", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onAppend, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Append remaining tracks to queue",
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
