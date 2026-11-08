package dev.coda.android.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.coda.android.R
import dev.coda.android.artwork.CoverArtFetcher
import dev.coda.android.artwork.CoverImage
import dev.coda.android.ui.common.*
import dev.coda.android.ui.theme.AccentState
import dev.coda.android.ui.theme.Base
import dev.coda.android.ui.theme.DyeMiddleStop
import dev.coda.android.ui.theme.composeColor
import dev.coda.android.ui.theme.srgbLerp
import dev.coda.core.accent.ArtistBackdrop
import dev.coda.core.subsonic.Album
import dev.coda.core.subsonic.Artist
import dev.coda.core.subsonic.DiscographyLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
private fun ArtistName(name: String, max: Int, min: Int, modifier: Modifier = Modifier) {
    val large = LocalDensity.current.fontScale > 1.3f
    val style = TextStyle(color = LibText1, fontWeight = FontWeight(800), fontSize = max.sp, lineHeight = (max + 4).sp, letterSpacing = (-0.5).sp)
    // The block is always as tall as one line at the largest step (measured with the same style), so the
    // auto-size step the name lands on, and so its length, never changes the header height. A smaller step sits at the
    // bottom, next to the meta line.
    val oneLine = rememberTextMeasurer().measure("A", style, maxLines = 1).size.height
    Box(modifier.heightIn(min = with(LocalDensity.current) { oneLine.toDp() }), contentAlignment = Alignment.BottomStart) {
        // One line, stepped down to fit; above 130 % font the name may wrap to two lines instead.
        BasicText(name, Modifier.testTag("artistName").semantics { heading() }, style = style,
            maxLines = if (large) 2 else 1, overflow = TextOverflow.Ellipsis,
            autoSize = if (large) null else TextAutoSize.StepBased(min.sp, max.sp, 1.sp))
    }
}

/** The banner image's vertical alignment; the Photo-matched colour and the photo years sample the same crop. */
private const val BannerBiasY = -.33f
/** The banner's drawn height; the photo colours sample its visible window. */
private const val BannerHeightDp = 300
/** Neutral avatar (pinned bar, no photo): #26292E with white initials, no accent. */
private val NeutralAvatar = AccentState(LibText1, LibText1, LibText1, Base, Color(0xFF26292E), Color(0xFF26292E))
/** Timeline years without a photo colour (DS-11 grey): the on-surface text at 62 %. */
private val GreyYears = LibText1.copy(alpha = .62f)

/** Test hooks: the artist page's page colour (ARGB) as drawn at the top, and the year colour (ARGB with alpha). */
val ArtistPageColorKey = SemanticsPropertyKey<Int>("ArtistPageColor")
internal val ArtistYearColorKey = SemanticsPropertyKey<Int>("ArtistYearColor")

/** The colours the artist photo gives the page: DS-11 Photo-matched [page] and AR-3 [years] (null: no usable pixels). */
private data class PhotoLook(val page: Color, val years: Color?)

/**
 * Photo-matched colour and AR-3 photo years per decoded artist image (keyed by the fetcher's bitmap, so a
 * refreshed image or another server never reuses a stale colour), so a revisited artist opens in its colours without a
 * fade. Derived off the main thread.
 */
private object ArtistPhotoColors {
    private val looks = java.util.WeakHashMap<android.graphics.Bitmap, PhotoLook>()
    private fun art(id: String) = dev.coda.android.artwork.CoverArt(id, 1440)
    fun cached(fetcher: CoverArtFetcher, id: String): PhotoLook? =
        fetcher.cached(art(id))?.let { bitmap -> synchronized(looks) { looks[bitmap] } }
    /**
     * The banner's visible window (same 1440 px image, crop and bias as the banner): bottom edge and whole-photo accent.
     * Once the photo is in hand, the look derived earlier for it at this view size comes from `library.db`
     * ([store]; forgotten when the photo is refreshed), else it is derived and stored there.
     */
    suspend fun derive(fetcher: CoverArtFetcher, artistId: String, id: String, viewWidth: Int, viewHeight: Int,
                       store: dev.coda.core.library.LibraryStore?): PhotoLook = withContext(Dispatchers.Default) {
        val bitmap = fetcher.bitmap(art(id))
        synchronized(looks) { looks[bitmap] }?.let { return@withContext it }
        store?.let { it.awaitWrites(); it.look(id, viewWidth, viewHeight) }?.let { saved ->
            val look = PhotoLook(Color(saved.page), saved.years?.let { Color(it) })
            synchronized(looks) { looks[bitmap] = look }
            return@withContext look
        }
        val source = if (bitmap.config == android.graphics.Bitmap.Config.HARDWARE) bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else bitmap
        val width = minOf(64, source.width).coerceAtLeast(1)
        val height = (source.height.toLong() * width / source.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
        val small = android.graphics.Bitmap.createScaledBitmap(source, width, height, true)
        val pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        if (small !== bitmap && small !== source) small.recycle()
        if (source !== bitmap) source.recycle()
        val page = ArtistBackdrop.photoPage(pixels, width, height, viewWidth, viewHeight, BannerBiasY).composeColor()
        val years = ArtistBackdrop.photoYears(ArtistBackdrop.photoAccent(pixels, width, height, viewWidth, viewHeight, BannerBiasY))
        val look = PhotoLook(page, years?.composeColor())
        synchronized(looks) { looks[bitmap] = look }
        store?.saveLook(dev.coda.core.library.LibraryStore.PhotoLook(artistId, id, viewWidth, viewHeight, look.page.toArgb(), look.years?.toArgb()))
        look
    }
}

/**
 * The artist page's colours (AR-3 Artist-photo): the page colour (DS-11 Photo-matched, graphite without a photo) and the
 * Timeline year colour (the photo accent's text role at 72 %; grey without a photo colour). Animated like the accent, so a late photo fades in.
 */
@Composable
private fun rememberArtistColors(artist: Artist?, fetcher: CoverArtFetcher): Pair<State<Color>, State<Color>> {
    val graphite = ArtistBackdrop.Graphite.composeColor()
    val photoId = artist?.coverArt
    val screenWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val revision by fetcher.revision.collectAsState()
    val store = (androidx.compose.ui.platform.LocalContext.current.applicationContext as? dev.coda.android.CodaApp)?.library
    val photo by produceState(photoId?.let { ArtistPhotoColors.cached(fetcher, it) }, fetcher, photoId, screenWidth, revision) {
        if (photoId == null) { value = null; return@produceState }
        value = ArtistPhotoColors.cached(fetcher, photoId) ?: try { ArtistPhotoColors.derive(fetcher, artist?.id.orEmpty(), photoId, screenWidth, BannerHeightDp, store) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
    }
    val yearAlpha = ArtistBackdrop.YEAR_ALPHA.toFloat()
    val page = photo?.page ?: graphite
    val years = photo?.years?.copy(alpha = yearAlpha) ?: GreyYears
    val spec = tween<Color>(450, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
    return animateColorAsState(page, spec, label = "Artist page") to animateColorAsState(years, spec, label = "Artist years")
}

/**
 * The sheet opened from the artist page wears the artist colour, the page's view accent (the Timeline's [years] colour made
 * opaque, as the rating badges use it). Its fill is that colour; the text on it is dark or white by the fill's lightness.
 */
private fun artistMenuColors(years: Color): dev.coda.android.ui.menu.MenuColors {
    val colour = years.copy(alpha = 1f)
    val onFill = if (colour.luminance() > 0.46f) Color.Black.copy(alpha = 0.82f) else Color.White
    return dev.coda.android.ui.menu.MenuColors(colour, onFill, colour)
}

/** The page gradient of the mockup: [top] → lerp(base, top, [middle]) at the dye's middle stop → base by 640 dp. */
private fun Modifier.artistBackdrop(top: () -> Color, middle: Float) = drawBehind {
    drawRect(Base)
    val color = top()
    drawRect(Brush.verticalGradient(0f to color, DyeMiddleStop to srgbLerp(Base, color, middle), 1f to Base,
        endY = dev.coda.core.accent.OneVoice.PageFadeDp.dp.toPx()))
}

/**
 * The artist / discography page (DS-11 Stage, AR-3 Timeline): the Stage banner and name, then the
 * "Discography" heading with the sort control and one Timeline row per release (the same rows for 1, few and many
 * releases), then "Appears on" in the same rows. No divider line. The page colour and the year colour come from the
 * artist photo (AR-3 Artist-photo); the dock keeps the accent. Best of, Play newest (or Play for one release), Shuffle and
 * Add to queue live in ⋮.
 */
@Composable fun ArtistScreen(state: LibraryState, fetcher: CoverArtFetcher,
    newest: Boolean, sort: () -> Unit, back: () -> Unit, retry: () -> Unit,
    open: (Album) -> Unit) {
    val page = state.artist
    val layout = state.projection.layout(newest)
    val albums = layout.albums
    val appearances = state.projection.appearancesLayout(newest)
    val (pageColor, yearColor) = rememberArtistColors(page?.artist, fetcher)
    // From the 130 % font step on, the years move above the rows ("Years above"), so the title keeps the full
    // width; at 130 % a 4-digit year in the app font no longer fits the 46 dp column (Android's steps: 1.15, 1.3, 1.5…).
    val yearsAbove = LocalDensity.current.fontScale > 1.2f
    // The way back's cross-fade keeps the same release in view (by its key: the phone has fewer of them).
    val list = dev.coda.android.ui.offline.rememberAnchoredListState { key ->
        val appearance = (key as? String)?.removePrefix("appearance-")?.takeIf { it != key }
        when {
            key == "header" -> 0
            key == "appearsOn" -> (1 + albums.size).takeIf { appearances.albums.isNotEmpty() }
            appearance != null -> appearances.albums.indexOfFirst { it.id == appearance }.takeIf { it >= 0 }?.let { 2 + albums.size + it }
            else -> albums.indexOfFirst { it.id == key }.takeIf { it >= 0 }?.let { 1 + it }
        }
    }
    // The screen's view model doesn't outlive the activity (ScreenModels), so after a rotation or recreation the
    // discography loads again and the first, item-less layout clamps the restored scroll position. Keep the restored
    // position and apply it once the releases are back.
    var restore by remember { mutableStateOf(list.firstVisibleItemIndex.takeIf { it > 0 }?.let { it to list.firstVisibleItemScrollOffset }) }
    LaunchedEffect(restore, page != null) {
        val (index, offset) = restore ?: return@LaunchedEffect
        if (page == null) return@LaunchedEffect
        list.scrollToItem(index, offset); restore = null
    }
    val density = LocalDensity.current
    val statusPx = with(density) { WindowInsets.statusBars.asPaddingValues().calculateTopPadding().toPx() }
    // The name's bottom inside the header item. Pinning reads it against the list's scroll offset: the header's own
    // global position isn't reported when only the lazy list's placement moves it (a short list that never scrolls the
    // header out kept the bar pinned after scrolling back to the top).
    var nameBottom by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    val pinned by remember { derivedStateOf { list.firstVisibleItemIndex > 0 ||
        nameBottom - list.firstVisibleItemScrollOffset < statusPx + with(density) { 56.dp.toPx() } } }
    val menus = dev.coda.android.ui.menu.LocalItemMenus.current
    // The page's ⋮ opens the one artist sheet.
    val overflow: @Composable (Modifier) -> Unit = { modifier ->
        IconButton(onClick = { page?.let { menus?.artist(it.artist, { artistMenuColors(yearColor.value) }) } }, modifier.testTag("artistMenu"), enabled = page != null) {
            Icon(Icons.Default.MoreVert, "Artist actions")
        }
    }
    Box(Modifier.fillMaxSize().artistBackdrop({ pageColor.value }, .45f).pageSemantics(page?.artist?.name ?: "Artist").testTag("artistBackdrop").semantics {
        set(ArtistPageColorKey, pageColor.value.toArgb()); set(ArtistYearColorKey, yearColor.value.toArgb())
    }) {
        LazyColumn(Modifier.fillMaxSize().testTag("discography"), state = list,
            contentPadding = PaddingValues(bottom = dev.coda.android.ui.shell.LocalDockPadding.current + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "header") {
                Column {
                    StageHeader(page?.artist, state, albums, fetcher) { nameBottom = it }
                    if (page == null && state.failed) dev.coda.android.ui.offline.CantLoadBlock("Couldn't load artist",
                        if (state.offline) "You're offline and this artist isn't saved on this phone." else "Check your connection and try again.", retry)
                    if (page != null) {
                        if (albums.isEmpty()) Text("No albums by this artist in your library", Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                        // The heading row sits 26 dp under the meta line; the rows start 6 dp below it (12 dp row gap, so 10 + 12).
                        else DiscographyHeading("Discography", Modifier.padding(bottom = if (yearsAbove) 0.dp else 10.dp).testTag("artistDiscographyHeader")) {
                            TimelineSort(newest, sort)
                        }
                    }
                }
            }
            if (page == null && state.loading) items(4) { SkeletonRow(yearsAbove) }
            itemsIndexed(albums, key = { _, album -> album.id }) { index, album ->
                TimelineRow(album, layout.yearLabels[index], { yearColor.value }, yearsAbove, null, fetcher, open)
            }
            if (appearances.albums.isNotEmpty()) {
                item(key = "appearsOn") { DiscographyHeading("Appears on", Modifier.padding(top = if (yearsAbove) 12.dp else 16.dp).testTag("artistAppearsOn")) {} }
                itemsIndexed(appearances.albums, key = { _, album -> "appearance-${album.id}" }) { index, album ->
                    TimelineRow(album, appearances.yearLabels[index], { yearColor.value }, yearsAbove, album.artistName.takeIf { it.isNotBlank() },
                        fetcher, open, tag = "artist-appearance-${album.id}")
                }
            }
        }
        // Glass back and overflow over the banner until the pinned bar takes over.
        if (!pinned) {
            // 40 dp glass circles with 48 dp hit areas, as on the album page.
            // Back and the menu are read before the timeline they are drawn over.
            StageBackButton(back, Modifier.statusBarsPadding().pageHeader())
            Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(4.dp).size(48.dp).pageHeader(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(40.dp).background(Color.Black.copy(alpha = .36f), CircleShape))
                overflow(Modifier)
            }
        }
        AnimatedVisibility(pinned && page != null, Modifier.pageHeader(), enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
            Row(Modifier.fillMaxWidth().drawBehind { drawRect(pageColor.value) }.drawWithContent {
                drawContent(); drawRect(LibOutline, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx()))
            }.statusBarsPadding().height(56.dp).testTag("artistPinnedBar"), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                page?.artist?.let { ArtistAvatar(it, fetcher, 32.dp, NeutralAvatar) }
                Text(page?.artist?.name.orEmpty(), Modifier.weight(1f).padding(start = 12.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    color = LibText1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                overflow(Modifier)
            }
        }
    }
}

/** "Discography" / "Appears on" heading row on the 20 dp text edge (20 sp bold), with an optional trailing control. */
@Composable
private fun DiscographyHeading(title: String, modifier: Modifier, trailing: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 26.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = LibText1,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}

/** Sort control: "Newest first" / "Oldest first" with the sort glyph, white, no accent, 48 dp tall. */
@Composable
private fun TimelineSort(newest: Boolean, sort: () -> Unit) {
    val state = if (newest) "Sorted newest first" else "Sorted oldest first"
    val action = if (newest) "sort oldest first" else "sort newest first"
    Row(Modifier.testTag("artistSort").heightIn(min = 48.dp).clickable(onClick = sort)
        .clearAndSetSemantics { contentDescription = state; role = Role.Button; onClick(action) { sort(); true } }
        .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(18.dp), tint = LibText1)
        Text(if (newest) "Newest first" else "Oldest first", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = LibText1, maxLines = 1)
    }
}

/**
 * One AR-3 Timeline row (DS-15 "Compact years", PF-9b covers): a 46 dp year column ([year] only on the first row of a year, 15 sp, weight 800,
 * in [yearColor], read at draw time) on the cover's top line, a 112 dp cover with the rating badge (Medium, in the artist colour: the page's view
 * accent), the title in 16 sp on up to two lines and the meta line "[artist ·] type · N tracks". [yearsAbove] (font
 * above 130 %) puts the year on its own line instead.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimelineRow(album: Album, year: String?, yearColor: () -> Color, yearsAbove: Boolean, artist: String?,
    fetcher: CoverArtFetcher, open: (Album) -> Unit,
    tag: String = "artist-album-${album.id}") {
    val menus = dev.coda.android.ui.menu.LocalItemMenus.current
    @Composable fun Year(modifier: Modifier) = androidx.compose.foundation.text.BasicText(year.orEmpty(), modifier.testTag("artistYear-${album.id}"),
        style = Tabular.copy(fontSize = 15.sp, fontWeight = FontWeight(800), letterSpacing = (-0.15).sp),
        maxLines = 1, softWrap = false, color = yearColor)
    Column {
        if (yearsAbove && year != null) Year(Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp).semantics { heading() })
        val rating = currentRating(album.id, album.userRating)
        Row(Modifier.fillMaxWidth().testTag(tag).combinedClickable(onClick = { open(album) }, onClickLabel = "Open album", onLongClickLabel = "Album actions",
                // The album sheet; Go to artist is hidden on the artist's own releases (shown for appearances).
                onLongClick = { menus?.album(album, dev.coda.android.ui.menu.AlbumMenuContext(onArtistPage = artist == null)) })
            // The year (when the row does not show it) and the badge's rating, after the title.
            .semantics { listOfNotNull(if (year == null) album.year?.toString() else null, albumCellState(rating)).takeIf { it.isNotEmpty() }
                ?.let { stateDescription = it.joinToString(", ") } }
            .padding(horizontal = 20.dp).heightIn(min = TimelineCover), verticalAlignment = Alignment.CenterVertically) {
            // Year, cover and the title/meta column are centred on the cover (PO pick "Centred, 500").
            if (!yearsAbove) Year(Modifier.width(46.dp))
            Box(Modifier.testTag("artistCover-${album.id}")) {
                CoverImage(fetcher, album.artworkId, Modifier.size(TimelineCover).clip(RoundedCornerShape(10.dp)))
                TimelineBadge(rating, yearColor)
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(album.name, Modifier.testTag("artistTitle-${album.id}"), fontSize = 16.sp, lineHeight = 20.sp,
                    fontWeight = FontWeight(500), color = LibText1, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 2.dp).testTag("artistMeta-${album.id}"), verticalAlignment = Alignment.CenterVertically) {
                    val type = dev.coda.core.library.ReleaseTypeLabel.of(album.releaseTypes)
                    // Offline, a partial album says how much of it is here ("7 of 12").
                    val tracks = dev.coda.android.ui.offline.LocalOnPhone.current?.album(album.id)
                        ?: album.songCount?.let { pluralStringResource(R.plurals.tracks, it, it) }
                    Text(listOfNotNull(artist, type, tracks).joinToString(" · "), Modifier.weight(1f, fill = false), fontSize = 13.sp,
                        color = LibText2, maxLines = 1, overflow = TextOverflow.Ellipsis, style = Tabular)
                }
            }
        }
    }
}

/** The Timeline cover's size (AR-3 had 64 dp; the PO asked for bigger covers and accepts the longer scroll). */
private val TimelineCover = 112.dp

/**
 * The Timeline cover's rating badge (Medium) in the artist colour, the artist page's view accent ([yearColor] made opaque). Its own small
 * scope: while the artist colour fades in, only this badge recomposes, never the row.
 */
@Composable
private fun BoxScope.TimelineBadge(rating: Int, yearColor: () -> Color) {
    if (rating <= 0) return
    CompositionLocalProvider(dev.coda.android.ui.theme.LocalViewAccentOverride provides yearColor().copy(alpha = 1f)) {
        AlbumRatingBadge(rating, BadgeSize.Medium)
    }
}

/** A loading Timeline row: the year column, a 112 dp cover and two text lines. */
@Composable
private fun SkeletonRow(yearsAbove: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(TimelineCover), verticalAlignment = Alignment.CenterVertically) {
        if (!yearsAbove) Spacer(Modifier.width(46.dp))
        Skeleton(Modifier.size(TimelineCover), radius = 10)
        SkeletonText(Modifier.weight(1f).padding(start = 14.dp))
    }
}

/** Stage: a 272 dp banner from the artist image (or cropped initials), then the name block. */
@Composable
private fun StageHeader(artist: Artist?, state: LibraryState, albums: List<Album>, fetcher: CoverArtFetcher, nameBottom: (Float) -> Unit) {
    // Banner (300 dp image melting into the page) behind a name block at 196 dp; the header is 272 dp tall unless a
    // wrapped name (above 130 % font) needs more.
    androidx.compose.ui.layout.Layout(content = {
        Box(Modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
            drawContent()
            drawRect(Brush.verticalGradient(0f to Color.Black, .5f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
        }) {
            // No photo, or one that failed to load: initials in white at 7 % (no accent). A photo still loading is only the dark tile.
            val initialsBanner: @Composable BoxScope.() -> Unit = {
                if (artist != null) Box(Modifier.fillMaxWidth().height(272.dp).clipToBounds().testTag("artistBannerInitials")) {
                    Text(initials(artist.name), Modifier.align(Alignment.BottomEnd).offset(18.dp, 34.dp).wrapContentSize(unbounded = true).clearAndSetSemantics { },
                        fontSize = with(LocalDensity.current) { 190.dp.toSp() }, lineHeight = with(LocalDensity.current) { 190.dp.toSp() },
                        fontWeight = FontWeight(800), color = Color.White.copy(alpha = .07f), maxLines = 1, softWrap = false)
                }
            }
            if (artist?.coverArt != null) CoverImage(fetcher, artist.coverArt, Modifier.fillMaxSize().testTag("artistBanner"), 1440, BiasAlignment(0f, BannerBiasY), missing = initialsBanner)
            else initialsBanner()
            Box(Modifier.fillMaxWidth().height(104.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .55f), Color.Transparent))))
            Box(Modifier.fillMaxWidth().padding(top = 150.dp).height(150.dp).background(Brush.verticalGradient(
                0f to Color.Transparent, .5f to Color.Black.copy(alpha = .28f), 1f to Color.Transparent)))
        }
        Column(Modifier.padding(horizontal = 20.dp)) {
            if (artist == null && state.loading) { DelayedProgress(true); SkeletonText() }
            val top = with(LocalDensity.current) { 196.dp.toPx() }
            if (artist != null) ArtistIdentity(artist, albums, 32, 22) { nameBottom(top + it) }
        }
    }, modifier = Modifier.fillMaxWidth().testTag("artistStage")) { measurables, constraints ->
        val width = constraints.maxWidth
        val banner = measurables[0].measure(Constraints.fixed(width, 300.dp.roundToPx()))
        val text = measurables[1].measure(Constraints(maxWidth = width))
        val top = 196.dp.roundToPx()
        layout(width, maxOf(272.dp.roundToPx(), top + text.height + 6.dp.roundToPx())) {
            banner.place(0, 0); text.place(0, top)
        }
    }
}

/** Autosized name and meta (genre appended to the meta line). */
@Composable
private fun ArtistIdentity(artist: Artist, albums: List<Album>, max: Int, min: Int, nameBottom: (Float) -> Unit) {
    val years = albums.mapNotNull { it.releaseYear }
    val span = if (years.isEmpty()) null else if (years.min() == years.max()) "${years.min()}" else "${years.min()}–${years.max()}"
    val releases = pluralStringResource(R.plurals.releases, albums.size, albums.size)
    // The "ARTIST" kicker is gone; its line stays empty (measured with the kicker's old style, so it follows the
    // font scale) and the name and meta don't move.
    val kicker = rememberTextMeasurer().measure("ARTIST", LocalTextStyle.current.merge(
        TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp))).size.height
    Spacer(Modifier.height(with(LocalDensity.current) { kicker.toDp() }))
    ArtistName(artist.name, max, min, Modifier.onPlaced {
        nameBottom(it.positionInParent().y + it.size.height)
    })
    Text(listOfNotNull(releases, span, artist.genre?.takeIf { it.isNotBlank() }).joinToString(" · "), fontSize = 13.sp,
        color = LibText2, style = Tabular, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * Stage's glass back button: a 40 dp circle centred in a 48 dp slot (same place as the overflow button on the other
 * side). The whole circle is the ripple; the 48 dp slot is the touch target.
 */
@Composable
internal fun StageBackButton(back: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.padding(4.dp).size(48.dp).testTag("stageBackSlot"), contentAlignment = Alignment.Center) {
        GlassIconButton(back, size = 40.dp) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = LibText1)
        }
    }
}
