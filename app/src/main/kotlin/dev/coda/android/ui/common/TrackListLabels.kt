package dev.coda.android.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.coda.android.artwork.*

/** Shared queue/playlist contents; each surface owns identity, clicks and collection actions. */
@Composable
internal fun TrackListAlbumLabel(fetcher: CoverArtFetcher, cover: String?, title: String, artist: String,
    year: Int?, durationMs: Long, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        CoverImage(fetcher, cover, Modifier.size(48.dp).clip(RoundedCornerShape(7.dp)))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title.ifEmpty { "Unknown Album" }, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(artist.takeIf { it.isNotEmpty() }, year?.toString(), "${durationMs / 60000}m").joinToString(" · "),
                color = Color.White.copy(alpha = .6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun TrackListTrackLabel(fetcher: CoverArtFetcher, cover: String?, title: String, artist: String,
    number: Int?, disc: Int?, multiDisc: Boolean, standalone: Boolean, current: Boolean, accent: Color,
    modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (standalone) Box {
            CoverImage(fetcher, cover, Modifier.size(48.dp).clip(RoundedCornerShape(7.dp)))
            if (current) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .4f), RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.VolumeUp, "Current track", tint = Color.White)
            }
        } else Box(Modifier.width(48.dp), contentAlignment = Alignment.CenterEnd) {
            if (current) Icon(Icons.Default.VolumeUp, "Current track", Modifier.size(18.dp), tint = accent)
            else Text(listOfNotNull(if (multiDisc) (disc ?: 1).toString() else null, number?.toString()).joinToString("·"),
                color = Color.White.copy(alpha = .4f), fontSize = 12.sp, textAlign = TextAlign.End)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, fontSize = 14.sp, color = if (current) accent else Color(0xFFF3F1EA), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (standalone) Text(artist, color = Color.White.copy(alpha = .6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
