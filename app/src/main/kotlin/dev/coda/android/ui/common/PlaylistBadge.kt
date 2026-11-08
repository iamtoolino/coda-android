package dev.coda.android.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import dev.coda.android.ui.theme.rememberViewAccent
import dev.coda.core.library.PlaylistTitle

internal const val AutoPlaylistDescription = "Auto-updating playlist"
internal const val SetlistPlaylistDescription = "Setlist playlist"

/** What TalkBack adds to a badged playlist's name; null for a plain playlist. */
internal val PlaylistTitle.badgeDescription: String? get() = when { auto -> AutoPlaylistDescription; setlist -> SetlistPlaylistDescription; else -> null }

/**
 * The "[Auto]" (refresh icon, "Auto") or "[Setlist]" (list icon, "Setlist") badge in a cover's top-right corner, on the rating badge's plate. [compact] is the 16 dp icon of the
 * Playlists list rows; otherwise a 22 dp pill with the label (Home cards, the playlist page). Draws nothing for a plain playlist. The plate takes the accent of the
 * view it is shown in, like the rating badge.
 *
 * [describe] true: the badge carries [badgeDescription] and merges into the clickable row or card around it, so it is
 * no stop of its own. False: it is silent and the caller names the playlist (the page title).
 */
@Composable
internal fun BoxScope.PlaylistBadge(title: PlaylistTitle, compact: Boolean, describe: Boolean = true) {
    val description = title.badgeDescription ?: return
    val icon: ImageVector = if (title.auto) Icons.Default.Autorenew else Icons.AutoMirrored.Filled.FormatListBulleted
    val label = if (title.auto) "Auto" else "Setlist"
    val accent = rememberViewAccent()
    val semantics = if (describe) Modifier.semantics { contentDescription = description } else Modifier.clearAndSetSemantics { }
    if (compact) Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(16.dp).badgePlate(accent, 8.dp).then(semantics),
        contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(11.dp), tint = Color.White)
    } else Row(Modifier.align(Alignment.TopEnd).wrapContentSize(Alignment.TopEnd, unbounded = true).width(IntrinsicSize.Max).padding(7.dp)
        .heightIn(min = 22.dp).badgePlate(accent, 11.dp).padding(start = 5.dp, end = 7.dp).then(semantics),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(icon, null, Modifier.size(13.dp), tint = Color.White)
        Text(label, fontSize = 11.5.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1,
            modifier = Modifier.clearAndSetSemantics { })
    }
}
