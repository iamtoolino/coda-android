package dev.coda.android.ui.common

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*

/**
 * Reserve the same trailing column even for missing durations. Scale its width with the text. [seconds] makes
 * TalkBack say "3 minutes 46 seconds" instead of "3:46" ([spokenDuration]).
 */
@Composable
internal fun TrackDuration(text: String, color: Color, modifier: Modifier = Modifier, fontSize: TextUnit = 13.sp, seconds: Long? = null) {
    val width = with(LocalDensity.current) { 48.sp.toDp() }
    val spoken = spokenDuration(seconds)?.takeIf { text.isNotEmpty() }
    // The spoken words replace the drawn text in semantics (the text layout stays available to tests).
    Text(text, modifier.width(width).then(if (spoken != null) Modifier.semantics {
            this.text = androidx.compose.ui.text.AnnotatedString(spoken) } else Modifier), color = color, fontSize = fontSize, maxLines = 1, textAlign = TextAlign.End,
        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"))
}
