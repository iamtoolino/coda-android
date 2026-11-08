package dev.coda.android.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.coda.android.R
import dev.coda.android.artwork.CoverArtFetcher
import dev.coda.android.artwork.CoverImage
import dev.coda.android.ui.common.*
import dev.coda.android.ui.theme.AccentState
import dev.coda.core.accent.DisplayedAccent
import dev.coda.core.library.*
import dev.coda.core.subsonic.Album
import dev.coda.core.subsonic.Artist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import kotlin.math.roundToInt

/** Long-press actions on an artist row. */

/** Artist initials: first letters of the first two words (spec v1 §8). */
internal fun initials(name: String) = name.split(" ").map { word -> word.dropWhile { !it.isLetterOrDigit() } }
    .filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }.uppercase()

/** Photo (crop) or initials on accentContainer with an accentText 22 % ring (spec v1 §8 Avatar). */
@Composable
internal fun ArtistAvatar(artist: Artist, fetcher: CoverArtFetcher, size: androidx.compose.ui.unit.Dp, accent: AccentState, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(accent.container).border(1.dp, accent.text.copy(alpha = .22f), CircleShape),
        contentAlignment = Alignment.Center) {
        // Initials are artwork, not text: sized to the circle, not to the font scale. They also stand in for a photo that failed to load.
        val initialsText: @Composable BoxScope.() -> Unit = {
            // Never read before the name.
            Text(initials(artist.name), Modifier.align(Alignment.Center).decorative(), fontSize = with(LocalDensity.current) { (size * .35f).toSp() }, fontWeight = FontWeight(800), color = accent.text, maxLines = 1)
        }
        if (artist.coverArt != null) CoverImage(fetcher, artist.coverArt, Modifier.fillMaxSize(), missing = initialsText) else initialsText()
    }
}

private val LetterInset = 19.dp     // the gutter letter's top inside a 64 dp row
private val LetterHeight = 26.dp

/**
 * Artists tab (frames A1–A6): enter-always title, filter, Recently played, rows with a Contacts-style
 * sticky gutter letter (letter divider rows above 130 % font) and the fast-scroll thumb. The index features
 * switch on at 30 artists.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArtistsScreen(state: LibraryState, fetcher: CoverArtFetcher, retry: () -> Unit, open: (Artist) -> Unit,
    recent: List<Album> = emptyList(), accent: AccentState = remember { AccentState.of(DisplayedAccent.brandSelection) },
    backdrop: TabBackdrop? = null) {
    val index = state.index?.takeIf { it.artists.size == state.artists.size } ?: remember(state.artists) { ArtistIndex.of(state.artists) }
    val all = index.artists
    val sections = index.sections
    val density = LocalDensity.current
    val indexed = all.size >= ArtistIndexThreshold
    val dividers = indexed && density.fontScale > 1.3f
    var query by rememberSaveable { mutableStateOf("") }
    var debounced by remember { mutableStateOf(query) }
    LaunchedEffect(query) { if (query.isNotBlank()) delay(120); debounced = query }
    val filtering = indexed && debounced.isNotBlank()
    // Names are folded once per list (off the main thread), not again on every keystroke.
    val folded by produceState<ArtistFilter.Index?>(null, all, indexed) {
        value = if (!indexed) null else withContext(Dispatchers.Default) { ArtistFilter.Index(all) }
    }
    val matches by produceState<List<ArtistFilter.Match>?>(null, folded, debounced, filtering) {
        val names = folded
        value = if (!filtering || names == null) null else withContext(Dispatchers.Default) { names.matches(debounced) }
    }
    dev.coda.android.ui.shell.PageBackHandler(query.isNotEmpty()) { query = "" }
    val faces = remember(recent, all) { if (indexed) recentArtists(recent, all) else emptyList() }
    val showRecent = indexed && faces.isNotEmpty() && !filtering
    val showIndex = indexed && !filtering
    val lead = (if (indexed) 1 else 0) + (if (showRecent) 1 else 0)
    // The way back's cross-fade keeps the same artist in view (by its key: the phone's list is shorter).
    val list = dev.coda.android.ui.offline.rememberAnchoredListState { key ->
        when (key) {
            "filter" -> 0.takeIf { indexed }
            "recent" -> (if (indexed) 1 else 0).takeIf { showRecent }
            else -> all.indexOfFirst { it.id == key }.takeIf { it >= 0 }
                ?.let { dev.coda.android.ui.offline.sectionedIndex(it, lead, if (dividers) sections else emptyList()) }
        }
    }
    val header = remember { EnterAlwaysHeader() }
    // At the very top the title always shows (also after a programmatic scroll or jump to the start).
    LaunchedEffect(list, header) { snapshotFlow { list.canScrollBackward }.collect { if (!it) header.show() } }
    val scope = rememberCoroutineScope()
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val statusPx = with(density) { statusBar.toPx() }
    val dock = dev.coda.android.ui.shell.LocalDockPadding.current
    // Lazy index of each section's first item: its first row (gutter) or its divider row.
    val starts = remember(sections, lead, dividers) {
        IntArray(sections.size) { lead + sections[it].start + if (dividers) it else 0 }
    }
    // Gutter: the letter sticks at the status bar (or under the returning title); dividers pin like album sections.
    val gutterTop = { statusPx + header.bottomPx.coerceAtLeast(0f) + with(density) { 6.dp.toPx() } }
    val gutterLine = { gutterTop() - with(density) { LetterInset.toPx() } }
    val dividerLine = { statusPx + header.bottomPx.coerceAtLeast(0f) - with(density) { 4.dp.toPx() } }
    val pinned = remember(starts, showIndex) { derivedStateOf {
        if (!showIndex) -1 else pinnedSection(starts, list.visibleItems(), if (dividers) dividerLine() else gutterLine())
    } }
    val scrollTarget = remember(list) { list.scrollTarget() }
    // The A–Z letter sheet, only while an accessibility service is on; hidden while filtering.
    val letters = rememberAccessibilityOn() && indexed
    var lettersOpen by remember { mutableStateOf(false) }
    val view = androidx.compose.ui.platform.LocalView.current
    val jumpFocus = remember(view) { JumpFocus(view) }
    BoxWithConstraints(Modifier.fillMaxSize().pageSemantics("Artists").testTag("artistsTab")) {
        // Read during layout (the filter scrolls away, the title bar moves): no recomposition per frame.
        val thumbTop: androidx.compose.ui.unit.Density.() -> Float = {
            val filter = list.visibleItems().firstOrNull { it.key == "filter" }?.let { it.top + it.size }?.toFloat() ?: 0f
            maxOf(statusPx + 16.dp.toPx(), filter + 16.dp.toPx(), statusPx + header.bottomPx + 16.dp.toPx())
        }
        val headerHeight = with(density) { header.heightPx.toDp() }
        LazyColumn(Modifier.fillMaxSize().nestedScroll(header.connection).testTag("artists")
            .semantics {
                // Rows only: dividers are items in the large-font mode, and a filter shows just its matches.
                collectionInfo = CollectionInfo(rowCount = if (filtering) matches?.size ?: 0 else all.size + if (dividers) sections.size else 0, columnCount = 1)
                if (letters && showIndex) customActions = listOf(CustomAccessibilityAction("Jump to letter") { lettersOpen = true; true })
            }, state = list,
            contentPadding = PaddingValues(top = statusBar + headerHeight, bottom = dock + 16.dp)) {
            if (indexed) item(key = "filter", contentType = "filter") {
                ArtistFilterField(query, { query = it }, accent, Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp))
            }
            if (showRecent) item(key = "recent", contentType = "recent") { RecentlyPlayed(faces, fetcher, accent, open) }
            if (state.loading && all.isEmpty()) {
                item(key = "progress") { DelayedProgress(true) }
                items(8, key = { "skeleton-$it" }) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Skeleton(Modifier.size(48.dp).clip(CircleShape), 24)
                        SkeletonText(Modifier.weight(1f).padding(start = 14.dp))
                    }
                }
            }
            if (state.failed && all.isEmpty()) item(key = "failed") {
                dev.coda.android.ui.offline.CantLoadBlock("Couldn't load artists", "Check your connection and try again.", retry)
            }
            if (!state.loading && !state.failed && all.isEmpty()) item(key = "empty") {
                if (dev.coda.android.ui.offline.LocalOnPhone.current != null)
                    StateBlock(Icons.Default.Person, "No music on this phone yet", "Artists of the music you play or prefer offline show here.")
                else StateBlock(Icons.Default.Person, "No artists in your library", "Navidrome hasn't indexed any artists yet.")
            }
            val found = matches
            if (filtering && found != null) {
                if (found.isEmpty()) item(key = "noMatch") {
                    StateBlock(Icons.Default.Search, "No artists match “${debounced.trim()}”", compact = true)
                }
                items(found, key = { it.artist.id }, contentType = { "artist" }) { match ->
                    ArtistRow(match.artist, fetcher, accent, gutter = null, group = -1, pinned = null, highlight = match.range,
                        indexed = false, open = open)
                }
            } else if (dividers) sections.forEachIndexed { s, section ->
                item(key = "letter-${section.label}-$s", contentType = "letter") {
                    SectionHeader(section.label, section.count, accent, Modifier.padding(horizontal = 16.dp).then(jumpFocus.target(starts[s])))
                }
                items(all.subList(section.start, section.start + section.count), key = { it.id }, contentType = { "artist" }) { artist ->
                    ArtistRow(artist, fetcher, accent, null, s, null, null, indexed = true, open = open)
                }
            } else if (!filtering) items(all.size, key = { all[it].id }, contentType = { "artist" }) { i ->
                val s = if (showIndex) sectionIndexAt(sections, i) else -1
                val first = s >= 0 && sections[s].start == i
                ArtistRow(all[i], fetcher, accent, if (first) sections[s].label else null, s, pinned, null,
                    indexed = showIndex, open = open, extra = if (first) jumpFocus.target(lead + i) else Modifier)
            }
        }
        // Gutter mode: the sticky letter (spec §0.3 Artists), pushed up by the next group's letter.
        if (showIndex && !dividers) {
            val group = pinned.value
            if (group >= 0) Box(Modifier.offset { IntOffset(4.dp.roundToPx(), gutterTop().roundToInt()) }
                .graphicsLayer {
                    translationY = pinnedPush(starts, list.visibleItems(), group, gutterLine(), LetterHeight.toPx())
                }.size(40.dp, LetterHeight).testTag("stickyLetter").semantics { contentDescription = sections[group].label; hideFromAccessibility() },
                contentAlignment = Alignment.Center) {
                Text(sections[group].label, fontSize = 20.sp, fontWeight = FontWeight(750), color = accent.text, maxLines = 1)
            }
        }
        if (showIndex && dividers) {
            val s = pinned.value
            if (s >= 0) PinnedHeaderBand(backdrop, list, header.connection, dividerLine, { pinnedPush(starts, list.visibleItems(), s, dividerLine(), it) },
                Modifier.padding(horizontal = 16.dp)) {
                SectionHeader(sections[s].label, sections[s].count, accent)
            }
        }
        // The title bar returns over scrolled rows on the backdrop's own pixels.
        if (backdrop != null) Box(Modifier.fillMaxSize().backdropBand(backdrop) {
            if (header.offsetPx <= -header.heightPx || !list.canScrollBackward) 0f else statusPx + header.bottomPx
        })
        Box(Modifier.offset { IntOffset(0, header.drawOffset(statusPx).roundToInt()) }.statusBarsPadding().fillMaxWidth()
            .onSizeChanged { header.heightPx = it.height.toFloat() }.pageHeader()) {
            val count = NumberFormat.getIntegerInstance().format(all.size)
            val titleCount = if (all.isEmpty()) null else if (filtering) matches?.let { "${it.size} of $count" } ?: count else count
            if (letters) Row(Modifier.padding(start = 16.dp, end = 16.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                LibraryTitle("Artists", titleCount, Modifier.weight(1f))
                AzChipSlot(showIndex, lettersOpen, { lettersOpen = true })
            } else LibraryTitle("Artists", titleCount,
                Modifier.padding(start = 16.dp, end = 16.dp).heightIn(min = 56.dp).wrapContentHeight(Alignment.CenterVertically))
        }
        if (showIndex) FastScroller(sections, scrollTarget, { pinned.value }, header, thumbTop, maxHeight - dock - 10.dp, accent)
        if (letters && showIndex && lettersOpen) LetterSheet(sections, pinned.value, all.size, R.plurals.artists, accent,
            pick = { s -> jumpFocus.choose(starts[s]); scope.launch { header.hide(); scrollTarget.jump(starts[s]) } },
            dismiss = { lettersOpen = false; scope.launch { jumpFocus.focusChosen() } })
    }
}

@Composable
private fun ArtistFilterField(query: String, change: (String) -> Unit, accent: AccentState, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    BasicTextField(query, change, modifier.fillMaxWidth().testTag("artistFilter").onFocusChanged { focused = it.isFocused },
        singleLine = true, textStyle = TextStyle(fontSize = 15.sp, color = LibText1), cursorBrush = SolidColor(accent.text),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        decorationBox = { field ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(Color.White.copy(alpha = .075f))
                .then(if (focused) Modifier.border(1.5.dp, accent.text, RoundedCornerShape(24.dp)) else Modifier),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null, Modifier.padding(start = 14.dp, end = 10.dp).size(22.dp), tint = LibText2)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search artists", fontSize = 15.sp,
                        color = LibText2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    field()
                }
                if (query.isNotEmpty()) IconButton(onClick = { change("") }, Modifier.size(48.dp).testTag("artistFilterClear")) {
                    Icon(Icons.Default.Close, "Clear search", tint = LibText1)
                } else Spacer(Modifier.width(16.dp))
            }
        })
}

@Composable
private fun RecentlyPlayed(faces: List<Artist>, fetcher: CoverArtFetcher, accent: AccentState, open: (Artist) -> Unit) {
    Column(Modifier.padding(top = 16.dp, bottom = 12.dp).testTag("recentArtists")) {
        Text("RECENTLY PLAYED", Modifier.padding(start = 16.dp, bottom = 10.dp).semantics { heading() }, fontSize = 12.sp,
            fontWeight = FontWeight(650), letterSpacing = 1.5.sp, color = LibText2)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(faces, key = { it.id }) { artist ->
                val menus = dev.coda.android.ui.menu.LocalItemMenus.current
                Column(Modifier.width(64.dp).combinedClickable(onClick = { open(artist) }, onLongClick = { menus?.artist(artist) },
                    onLongClickLabel = "Artist actions").testTag("recent-${artist.id}"),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    ArtistAvatar(artist, fetcher, 64.dp, accent)
                    Text(artist.name, Modifier.padding(top = 6.dp), fontSize = 12.sp, fontWeight = FontWeight(600), color = LibText1,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** Spec §1 ArtistRow: 64 dp, gutter letter on a group's first row, 48 dp avatar, name and plural release count. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistRow(artist: Artist, fetcher: CoverArtFetcher, accent: AccentState, gutter: String?, group: Int, pinned: State<Int>?,
    highlight: IntRange?, indexed: Boolean, open: (Artist) -> Unit, extra: Modifier = Modifier) {
    val menus = dev.coda.android.ui.menu.LocalItemMenus.current
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("artist-${artist.id}").then(extra)
        // The first row of a letter group is the heading TalkBack jumps to (the gutter letter itself is hidden from it).
        .then(if (gutter != null) Modifier.semantics { heading() } else Modifier)
        .combinedClickable(onClick = { open(artist) }, onLongClick = { menus?.artist(artist) }, onLongClickLabel = "Artist actions")
        .padding(start = if (indexed && pinned != null) 4.dp else 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (indexed && pinned != null) Box(Modifier.width(40.dp).height(LetterHeight).drawWithContent {
            // The sticky copy takes over once this letter reaches it; never both at once.
            if (pinned.value != group) drawContent()
        }, contentAlignment = Alignment.Center) {
            if (gutter != null) Text(gutter, Modifier.clearAndSetSemantics { }, fontSize = 20.sp, fontWeight = FontWeight(750),
                color = accent.text, maxLines = 1)
        }
        ArtistAvatar(artist, fetcher, 48.dp, accent, Modifier.padding(vertical = 8.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp, top = 8.dp, bottom = 8.dp)) {
            Text(buildAnnotatedString {
                val range = highlight?.takeIf { !it.isEmpty() && it.last < artist.name.length }
                if (range == null) append(artist.name) else {
                    append(artist.name.substring(0, range.first))
                    withStyle(SpanStyle(color = accent.text, fontWeight = FontWeight(700))) { append(artist.name.substring(range.first, range.last + 1)) }
                    append(artist.name.substring(range.last + 1))
                }
            }, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium, color = LibText1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            artist.albumCount?.let { Text(pluralStringResource(R.plurals.releases, it, it), fontSize = 12.5.sp, color = LibText2, maxLines = 1) }
        }
    }
}
