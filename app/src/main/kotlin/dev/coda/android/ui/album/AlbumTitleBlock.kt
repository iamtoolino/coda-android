package dev.coda.android.ui.album

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import kotlin.math.roundToInt
import dev.coda.android.ui.common.ratingSemantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import dev.coda.android.ui.library.RatingControl
import dev.coda.android.ui.player.LocalPageTextAlpha
import dev.coda.android.ui.theme.AccentState
import dev.coda.core.library.AlbumSections
import dev.coda.core.subsonic.Album
import dev.coda.core.subsonic.Song

/** The title's largest and smallest size (20 sp). It shrinks in 1 sp steps from [ALBUM_TITLE_MAX_SP] to [ALBUM_TITLE_MIN_SP], then ellipsizes. */
internal const val ALBUM_TITLE_MIN_SP = 20f
internal const val ALBUM_TITLE_MAX_SP = 26f

/** The sleeve fade's length (the plain "Like before" look, the PO's choice over the "Darker"). */
internal val AlbumSleeveFade = 72.dp

/** The Play button's overlap with the sleeve (it is 64 dp tall, centred on the sleeve's bottom edge). */
internal val PlayOverlap = 32.dp

/** Hard end padding of the text block: Play is 64 dp wide with a 16 dp end margin, and the text stops 16 dp before it. */
internal val AlbumBlockEnd = 96.dp
internal val AlbumBlockStart = 20.dp

/** The block's hit areas may reach this far below its text rows (the gap above the first track). */
internal val AlbumBlockGap = 8.dp

private const val TitleLineSp = 30f
private const val ArtistLineSp = 21f
private const val MetaLineSp = 16f
private val TitleArtistGap = 4.dp
private val ArtistMetaGap = 3.dp
private val StarSize = 14.dp
private val MinTouch = 48.dp
private val RatingTouchSide = 12.dp

/**
 * Height of the three text rows (each Text is forced to its row height: at large or non-linear font scales a font can be
 * taller than its line height, and the block must not grow): fixed (it only follows the font scale), so a long title or a missing rating never moves anything. */
@Composable
internal fun albumTextHeight(): Dp = with(LocalDensity.current) { TitleLineSp.sp.toDp() + ArtistLineSp.sp.toDp() + MetaLineSp.sp.toDp() } + TitleArtistGap + ArtistMetaGap

/** Which of the block's rows are fully scrolled under the pinned bar: they leave the TalkBack tree. */
@Immutable
internal data class AlbumRowCover(val artist: Boolean = false, val meta: Boolean = false)

/** Bottom edges of the artist row and of the meta/rating row inside the block, in px (they follow the font scale like the rows). */
internal fun Density.albumRowBottomsPx(): Pair<Float, Float> {
    // Each line converted on its own: with a non-linear font scale the sum of sp is not the sum of the dp.
    val artistBottom = TitleLineSp.sp.toPx() + ArtistLineSp.sp.toPx() + TitleArtistGap.toPx()
    return artistBottom to artistBottom + ArtistMetaGap.toPx() + MetaLineSp.sp.toPx()
}

/** The largest size from [maxSp] down to [minSp] in [stepSp] steps whose one-line width [widthPx] fits [availablePx]; [minSp] when none does (the caller then ellipsizes). */
internal fun fitTitleSp(availablePx: Float, maxSp: Float = ALBUM_TITLE_MAX_SP, minSp: Float = ALBUM_TITLE_MIN_SP, stepSp: Float = 1f,
    widthPx: (Float) -> Float): Float {
    var size = maxSp
    while (size > minSp) {
        if (widthPx(size) <= availablePx) return size
        size -= stepSp
    }
    return minSp
}

/** The hit rectangle's top edge above the artist line so that, with its 1.5 dp share of the gap below, it reaches 48 dp. */
internal fun artistHitUp(artistLine: Dp, artistTop: Dp): Dp = (MinTouch - artistLine - ArtistMetaGap / 2).coerceIn(0.dp, artistTop)


/**
 * Spec §7 / mockup 02: the 3-row block (title 26/30 800, artist link, then meta and album stars) sits at
 * the Play button's height, left of it. All three rows stop [AlbumBlockEnd] before the end. Hit areas are explicit and never
 * overlap: the artist link reaches up into the (non-tappable) title, the stars down into the gap above the first track;
 * neither reaches right of the block, so Play always wins.
 */
@Composable
internal fun AlbumInfo(detail: Album, songs: List<Song>, accent: AccentState, artistTarget: String?, artist: (String) -> Unit,
    titleModifier: Modifier, infoModifier: Modifier, covered: AlbumRowCover = AlbumRowCover()) {
    val density = LocalDensity.current
    val pageAlpha = LocalPageTextAlpha.current
    val titleLine = with(density) { TitleLineSp.sp.toDp() }
    val artistLine = with(density) { ArtistLineSp.sp.toDp() }
    val metaLine = with(density) { MetaLineSp.sp.toDp() }
    val artistTop = titleLine + TitleArtistGap
    val metaTop = artistTop + artistLine + ArtistMetaGap
    val share = ArtistMetaGap / 2
    val base = LocalViewConfiguration.current
    val viewConfiguration = remember(base) { object : ViewConfiguration by base { override val minimumTouchTargetSize = DpSize.Zero } }
    CompositionLocalProvider(LocalViewConfiguration provides viewConfiguration) {
        Box(infoModifier.fillMaxWidth().padding(start = AlbumBlockStart, end = AlbumBlockEnd).height(metaTop + metaLine + AlbumBlockGap)
            .graphicsLayer { alpha = pageAlpha() }
            .semantics { isTraversalGroup = true; traversalIndex = 1f }.testTag("albumInfo")) {
            // Title: one line; smaller in 1 sp steps, then an ellipsis. Its line height stays fixed.
            BoxWithConstraints(Modifier.height(titleLine)) {
                val measurer = rememberTextMeasurer()
                val base = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.4).sp, lineHeight = TitleLineSp.sp)
                val available = constraints.maxWidth.toFloat()
                val sp = remember(detail.name, available, density.density, density.fontScale, base) {
                    fitTitleSp(available) { size ->
                        measurer.measure(detail.name, base.copy(fontSize = size.sp), softWrap = false, maxLines = 1).size.width.toFloat()
                    }
                }
                Text(detail.name, Modifier.requiredHeight(titleLine).then(titleModifier).semantics { heading(); traversalIndex = 0f }.testTag("albumTitle"),
                    style = base.copy(fontSize = sp.sp), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
            // The stars' invisible 94 × 48 touch area: 12 dp beyond each side, from the block's bottom edge
            // up 48 dp (the artist line's top). Declared before the artist link, so where the two meet the link wins.
            var rate by remember(detail.id) { mutableStateOf(false) }
            var starsX by remember { mutableFloatStateOf(Float.NaN) }
            val blockHeight = metaTop + metaLine + AlbumBlockGap
            // It is also the rating's one TalkBack node (Dana: the node has the touch bounds), read after the meta line.
            val ratings = dev.coda.android.ui.library.LocalLibraryActions.current
            val busy = ratings?.ratings?.collectAsState()?.value?.busy == true
            val rating = dev.coda.android.ui.library.currentRating(detail.id, detail.userRating)
            if (!covered.meta && !starsX.isNaN()) Box(Modifier
                .offset { IntOffset((starsX - RatingTouchSide.toPx()).roundToInt(), (blockHeight - MinTouch).coerceAtLeast(0.dp).roundToPx()) }
                .size(StarSize * 5 + RatingTouchSide * 2, MinTouch)
                .testTag("albumRatingTouch").semantics { traversalIndex = 3f }
                .then(if (ratings == null) Modifier else Modifier.ratingSemantics("Album rating", rating, enabled = { !ratings.ratings.value.busy },
                    rate = { ratings.rate(detail.id, it) }, onClick = { if (!busy) rate = true }, clickLabel = "Rate album"))
                .pointerInput(Unit) { detectTapGestures { rate = true } })
            // Artist link: painted as before; its hit rectangle also covers the lower part of the title row and half of the gap below.
            val linked = artistTarget != null && detail.isCompilation != true
            val up = artistHitUp(artistLine, artistTop)
            Box(Modifier.offset(y = artistTop - up).height(up + artistLine + share).widthIn(min = MinTouch)
                .then(if (covered.artist) Modifier.clearAndSetSemantics { hideFromAccessibility() } else Modifier)
                .semantics { traversalIndex = 1f }
                .clickable(enabled = linked, role = Role.Button) { artistTarget?.let(artist) }.testTag("albumArtist")) {
                Text(detail.artistName, Modifier.padding(top = up).requiredHeight(artistLine), color = accent.text, fontSize = 16.sp, lineHeight = ArtistLineSp.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Meta line and album stars: one line (the stars keep their size). The stars' hit area reaches down into the gap above the first track.
            Row(Modifier.offset(y = metaTop - share).height(share + metaLine + AlbumBlockGap).fillMaxWidth(), verticalAlignment = Alignment.Top) {
                val discs = AlbumSections.discCount(songs)
                // Offline, a partial album says how much of it is here ("7 of 12"), in this line under the cover.
                val part = dev.coda.android.ui.offline.LocalOnPhone.current?.album(detail.id)
                Text(listOfNotNull(detail.releaseYear?.toString(), part ?: if (songs.size == 1) "1 track" else "${songs.size} tracks",
                    detail.duration?.let { if (discs > 1) AlbumSections.duration(it) else albumDuration(it) }).joinToString(" · "),
                    Modifier.weight(1f, fill = false).padding(top = share).requiredHeight(metaLine)
                        .then(if (covered.meta) Modifier.clearAndSetSemantics { hideFromAccessibility() } else Modifier.semantics { traversalIndex = 2f }.testTag("albumMeta")),
                    fontSize = 12.5.sp, lineHeight = MetaLineSp.sp, color = Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.width(StarSize * 5).fillMaxHeight().onPlaced { starsX = it.positionInParent().x }
                    .pointerInput(Unit) { detectTapGestures { rate = true } }
                    .then(if (covered.meta) Modifier.clearAndSetSemantics { hideFromAccessibility() } else Modifier.testTag("albumRatingHit"))) {
                    Column(Modifier.padding(top = share)) {
                        RatingControl(detail.id, detail.userRating ?: 0, "Album rating", accent.text, starSize = StarSize, compact = true,
                            requested = rate, onDismiss = { rate = false }, showError = false, semantics = false)
                    }
                }
            }
        }
    }
}

internal val Text2 = androidx.compose.ui.graphics.Color(0xFFF1F1EE).copy(alpha = .66f)
