package dev.coda.android.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import dev.coda.core.subsonic.ArtistCredits
import dev.coda.core.subsonic.ArtistReference
import kotlinx.coroutines.CancellationException

/**
 * The artist part of the "Artist · Album" line. A track with several credited artists (the song's OpenSubsonic
 * `artists`, found in the display text) shows the same text with each artist's name tappable, opening its own page. Each
 * tap target meets the 48 dp rule through Compose's minimum touch target, with no change to the line's size or spacing.
 * Without ids, or when the text does not name them, [single] draws today's line (the whole text opens the first artist).
 */
@Composable
internal fun ArtistCreditLine(
    display: String, albumId: String?, songId: String?, color: Color,
    artistsOf: suspend (albumId: String, songId: String) -> List<ArtistReference>,
    open: (artistId: String) -> Unit, single: @Composable () -> Unit,
) {
    val credited by produceState<List<ArtistReference>>(emptyList(), albumId, songId) {
        value = emptyList()
        if (albumId != null && songId != null) value = try { artistsOf(albumId, songId) }
            catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
    }
    val segments = remember(display, credited) { ArtistCredits.segments(display, credited) }
    if (segments == null) { single(); return }
    Row(Modifier.testTag("nowPlayingArtist")) {
        segments.forEach { segment ->
            val id = segment.artistId
            val modifier = if (id == null) Modifier else Modifier.testTag("nowPlayingArtist-$id")
                .clickable(onClickLabel = "Open artist ${segment.text}") { open(id) }
            Text(segment.text, if (id == null) modifier else modifier.then(Modifier.weight(segment.text.length.toFloat().coerceAtLeast(1f), fill = false)),
                color = color, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
    }
}
