package dev.coda.android.ui.player

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun retryLabel(seconds: Long) = if (seconds > 0) "Retrying in $seconds s" else "Retrying now"

/**
 * One line while offline so the controls stay on screen; the explanation opens on tap. [retrySeconds] is a plain
 * parameter on purpose: read inside the banner from the controller, it would not be re-read as the countdown moves.
 */
@Composable
internal fun ConnectionBanner(title: String, retrySeconds: Long) {
    var details by remember { mutableStateOf(false) }
    // The first line is said once when the banner appears (an overlay beside the banner, so nothing moves and the
    // row stays one stop); the ticking countdown is not a live region.
    Box {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = .07f)).animateContentSize().testTag("connectionBanner")) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClickLabel = if (details) "Hide details" else "Show details") { details = !details }
                .padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Waiting for connection · ${retryLabel(retrySeconds)}", Modifier.weight(1f).testTag("retryCountdown"),
                    color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"))
            }
            if (details) Text("“$title” isn't on this phone yet. Coda keeps the current track and the next 4 cached; this one wasn't downloaded before the connection dropped. Playback resumes on its own when the server answers.",
                Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp).testTag("connectionDetails"), fontSize = 12.sp, color = Color.White.copy(alpha = .75f))
        }
        dev.coda.android.ui.common.PoliteAnnouncement("Waiting for connection", "connectionAnnouncement")
    }
}
