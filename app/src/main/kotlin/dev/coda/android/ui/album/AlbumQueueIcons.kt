package dev.coda.android.ui.album

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black)).build()

/** Studio A's two queue icons: a list with a play triangle at the top, and a list with a plus at the bottom. */
internal val PlayNextIcon: ImageVector by lazy { icon("PlayNext", "M3 4l6 3.5L3 11zM11 6.5h10v2H11zM3 13h18v2H3zM3 18h18v2H3z") }
internal val AddToQueueIcon: ImageVector by lazy { icon("AddToQueue", "M3 4h18v2H3zM3 9h18v2H3zM3 14h9v2H3zM17 12h2v4h4v2h-4v4h-2v-4h-4v-2h4z") }
