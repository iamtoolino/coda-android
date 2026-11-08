package dev.coda.android.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// Spec 2.1 common states. surface2 / text2 / text3 from the spec's colour table.
private val Surface2 = Color(0xFF1A1C20)
private val Text2 = Color(0xFFA0A09D)
private val Text3 = Color(0xFF707479)

/**
 * Empty and error screens: a block centred at 40 % of the screen height with a 48 dp icon, a 16/600 title,
 * a one-line 13 sp body and an optional tonal action (Retry for errors).
 */
@Composable
fun StateBlock(icon: ImageVector, title: String, body: String? = null, action: String? = null,
    onAction: () -> Unit = {}, modifier: Modifier = Modifier, compact: Boolean = false) {
    val height = if (compact) Modifier.padding(vertical = 24.dp) else Modifier.height((LocalConfiguration.current.screenHeightDp * 0.6f).dp)
    Box(modifier.fillMaxWidth().then(height).testTag("stateBlock"), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(48.dp), tint = Text3)
            Text(title, Modifier.padding(top = 12.dp).semantics { heading() }, fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            if (body != null) Text(body, Modifier.padding(top = 4.dp), fontSize = 13.sp, color = Text2, textAlign = TextAlign.Center)
            if (action != null) FilledTonalButton(onClick = onAction, Modifier.padding(top = 16.dp).testTag("stateAction")) { Text(action) }
        }
    }
}

/**
 * A line with Retry at the end of a list about something else than the shown content (Albums' load more), kept in every
 * state. A failed refresh over content already shown has no line ("Couldn't update" removed).
 */
@Composable
fun RetryLine(text: String, retry: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().testTag("retryLine").padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.CloudOff, null, Modifier.size(16.dp), tint = Text2)
        Text(text, Modifier.weight(1f).padding(start = 8.dp), fontSize = 13.sp, color = Text2)
        TextButton(onClick = retry) { Text("Retry") }
    }
}

/** Static (no shimmer) placeholder square or bar. */
@Composable
fun Skeleton(modifier: Modifier, radius: Int = 10) {
    Box(modifier.clip(RoundedCornerShape(radius.dp)).background(Surface2).testTag("skeleton"))
}

/** Two 10 dp text bars at 60 % / 40 % width. */
@Composable
fun SkeletonText(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Skeleton(Modifier.fillMaxWidth(.6f).height(10.dp), 3)
        Skeleton(Modifier.fillMaxWidth(.4f).height(10.dp), 3)
    }
}

/** 2 dp indeterminate line that appears only if loading takes longer than 400 ms. */
@Composable
fun DelayedProgress(active: Boolean, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(active) { visible = false; if (active) { delay(400); visible = true } }
    if (active && visible) LinearProgressIndicator(modifier.fillMaxWidth().height(2.dp).testTag("delayedProgress"),
        trackColor = Color.White.copy(alpha = .08f))
}
