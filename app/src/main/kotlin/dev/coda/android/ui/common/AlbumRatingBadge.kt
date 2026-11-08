package dev.coda.android.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

/** The badge plate (shared with the Auto badge): the accent at 35 % over near-black at 86 %, with a 1 dp inner hairline. */
internal fun Modifier.badgePlate(accent: Color?, radius: Dp): Modifier = clip(RoundedCornerShape(radius))
    .background(Color(0xFF0E0E10).copy(alpha = .86f)).background(accent?.copy(alpha = .35f) ?: Color.Transparent)
    .border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(radius))

/**
 * The rating badge's three sizes, by the cover it sits on (one badge, one rule): [Large] on covers of 140 dp and more (Home, Albums tab;
 * spec 5.3, unchanged), [Medium] on 72-139 dp (the artist page Timeline), [Small] on 64 dp and less (Search album rows, about 24 x 14 dp).
 */
enum class BadgeSize(val height: Dp, val textSp: Float, val lineSp: Float, val star: Dp, val inset: Dp, val radius: Dp, val padding: Dp, val gap: Dp) {
    Large(22.dp, 12f, 14f, 10.dp, 7.dp, 11.dp, 6.dp, 2.dp),
    Medium(18.dp, 10.5f, 12f, 9.dp, 5.dp, 9.dp, 5.dp, 1.5.dp),
    Small(14.dp, 9f, 10f, 7.dp, 3.dp, 7.dp, 4.dp, 1.dp);

    /** The badge text in sp at [fontScale]: grows with the font up to 1.3x, then stays (a bigger badge would cover the art). */
    fun cappedTextSp(fontScale: Float): Float = if (fontScale > MaxFontScale) textSp * MaxFontScale / fontScale else textSp
    fun cappedLineSp(fontScale: Float): Float = if (fontScale > MaxFontScale) lineSp * MaxFontScale / fontScale else lineSp

    companion object { const val MaxFontScale = 1.3f }
}

/** Test hook: the accent the badge plate was drawn with (ARGB), so tests can pin "badge colour = the view's accent". */
internal val BadgeAccentKey = SemanticsPropertyKey<Int>("BadgeAccent")

/**
 * The album cover badge, one rule for every screen: the plate takes the accent of the view it is shown
 * in ([dev.coda.android.ui.theme.rememberViewAccent]), never the tile's own cover colour. No special handling per screen. [size] follows the
 * cover ([BadgeSize]).
 */
/** What an album cell adds after its title and artist: the rating its badge shows (null when unrated). */
internal fun albumCellState(rating: Int): String? = if (rating > 0) "Rated $rating of 5" else null

@Composable
internal fun BoxScope.AlbumRatingBadge(rating: Int, size: BadgeSize) {
    if (rating <= 0) return
    AlbumRatingPlate(rating, dev.coda.android.ui.theme.rememberViewAccent(), size)
}

@Composable
private fun BoxScope.AlbumRatingPlate(rating: Int, accent: Color, size: BadgeSize) {
    val fontScale = LocalDensity.current.fontScale
    // Allow enlarged text to measure beyond a small cover's inset constraints. The overlay never participates in the title or cover dimensions.
    Row(Modifier.align(Alignment.TopEnd).wrapContentSize(Alignment.TopEnd, unbounded = true)
        .width(IntrinsicSize.Max).padding(size.inset).heightIn(min = size.height).badgePlate(accent, size.radius).padding(horizontal = size.padding)
        // H1: drawn, not a TalkBack stop; the cell says its rating ([albumCellState]).
        .semantics(mergeDescendants = true) { contentDescription = "Rated $rating of 5"; hideFromAccessibility(); set(BadgeAccentKey, accent.toArgb()) },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(size.gap)) {
        Icon(Icons.Default.Star, null, Modifier.size(size.star), tint = Color.White)
        Text("$rating", fontSize = size.cappedTextSp(fontScale).sp, lineHeight = size.cappedLineSp(fontScale).sp, fontWeight = FontWeight.Bold,
            color = Color.White)
    }
}
