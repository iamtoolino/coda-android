package dev.coda.android.ui.library

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.coda.android.artwork.*
import dev.coda.android.ui.theme.AccentState
import dev.coda.android.ui.theme.Base
import dev.coda.android.ui.theme.pageBackdrop
import dev.coda.core.subsonic.*
import dev.coda.android.ui.common.*
import kotlinx.coroutines.launch

@Composable internal fun Heading(text: String) {
    Text(text, Modifier.padding(vertical = 12.dp).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
}

@Composable private fun Avatar(artist: Artist, fetcher: CoverArtFetcher, modifier: Modifier = Modifier, accent: AccentState? = null) {
    Box(modifier.size(80.dp).clip(CircleShape).background(accent?.container ?: MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        val initialsText: @Composable BoxScope.() -> Unit = {
            Text(artist.name.split(" ").filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }, Modifier.align(Alignment.Center).decorative(), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = accent?.text ?: MaterialTheme.colorScheme.onSecondaryContainer)
        }
        if (artist.coverArt != null) CoverImage(fetcher, artist.coverArt, Modifier.fillMaxSize(), missing = initialsText) else initialsText()
    }
}

@Composable private fun LoadState(state: LibraryState, retry: () -> Unit, message: String = "Couldn't load artists") {
    DelayedProgress(state.loading)
    if (state.failed) Row(verticalAlignment = Alignment.CenterVertically) {
        Text(message, Modifier.weight(1f)); TextButton(onClick = retry) { Text("Retry") }
    }
}

@Composable private fun Match(text: String, query: String, accent: AccentState, fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null, lineHeight: TextUnit = TextUnit.Unspecified, modifier: Modifier = Modifier) {
    val start = text.indexOf(query.trim(), ignoreCase = true)
    Text(buildAnnotatedString {
        if (start < 0 || query.isBlank()) append(text) else {
            append(text.take(start)); withStyle(SpanStyle(color = accent.text, fontWeight = FontWeight.Bold)) { append(text.substring(start, start + query.trim().length)) }; append(text.substring(start + query.trim().length))
        }
    }, modifier, fontSize = fontSize, fontWeight = fontWeight, lineHeight = lineHeight, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * A Search section heading that stays pinned at the top of the results while its section scrolls under it. It sits on a band of Home's
 * calm backdrop ([backdrop], the shell's shared layer replayed at this heading's position), so rows never show through and no new colour appears.
 */
@Composable private fun SearchHeading(text: String, backdrop: TabBackdrop?, origin: () -> LayoutCoordinates?) {
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxWidth().testTag("searchHeading-$text")
        .onPlaced { coordinates -> origin()?.takeIf { it.isAttached }?.let { offset = it.localPositionOf(coordinates, Offset.Zero) } }
        .drawBehind {
            clipRect {
                drawRect(Base)
                if (backdrop != null) translate(-offset.x, -offset.y) { drawLayer(backdrop.layer) }
            }
        }) { Heading(text) }
}

/** What TalkBack says when a search finishes: the number of results, or that there are none; null while typing or loading. */
internal fun searchAnnouncement(state: LibraryState): String? {
    if (state.query.isBlank() || state.pending || state.failed) return null
    val n = state.results.artists.size + state.results.albums.size + state.results.songs.size
    return if (n == 0) "No results" else if (n == 1) "1 result" else "$n results"
}

/**
 * The text to search after a field edit, or null when only the cursor or the IME's composition changed (no new
 * search3 and no second "N results" for the same query).
 */
internal fun searchedText(old: TextFieldValue, new: TextFieldValue): String? = new.text.takeIf { it != old.text }

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable fun SearchScreen(state: LibraryState, fetcher: CoverArtFetcher, query: (String) -> Unit,
    openArtist: (Artist) -> Unit, openAlbum: (Album) -> Unit, play: (Song) -> Unit, accent: AccentState, backdrop: TabBackdrop? = null) {
    // Every result type has its item sheet on long-press (the song dialog became the track sheet).
    val menus = dev.coda.android.ui.menu.LocalItemMenus.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var field by remember { mutableStateOf(TextFieldValue(state.query, TextRange(0, state.query.length))) }
    // Focus and the keyboard wait until this page is the active one and visible (not while it fades out, or before its reveal).
    val shown = dev.coda.android.ui.shell.LocalPageOwnership.current.shown
    LaunchedEffect(shown) { if (shown) { focus.requestFocus(); withFrameNanos { }; keyboard?.show() } }
    LaunchedEffect(state.query) { if (field.text != state.query) field = TextFieldValue(state.query) }
    // The screen fills the shell's backdrop bounds, so a heading's position in it is its position in the shared calm layer.
    val origin = remember { arrayOfNulls<LayoutCoordinates>(1) }
    Column(Modifier.fillMaxSize().onPlaced { origin[0] = it }.pageSemantics("Search").statusBarsPadding().padding(horizontal = 16.dp)) {
        Box {
            OutlinedTextField(value = field, onValueChange = { edited -> val old = field; field = edited; searchedText(old, edited)?.let(query) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).focusRequester(focus).testTag("search-query"),
                shape = RoundedCornerShape(28.dp),
                placeholder = { Text("Search artists, albums and songs") },
                leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = {
                    if (field.text.isNotEmpty()) IconButton(onClick = { field = TextFieldValue(""); query("") }) { Icon(Icons.Default.Close, "Clear search") }
                })
            // Q1: arriving results are said ("12 results"), politely, once per finished search.
            PoliteAnnouncement(searchAnnouncement(state), "searchCount")
        }
        LoadState(state, { query(state.query) }, if (state.offline) "Search needs the server. You’re offline." else "Search failed")
        // The shell's offline line says why; the results only say where they come from.
        if (state.fromPhone && state.query.isNotBlank()) {
            Row(Modifier.padding(bottom = 8.dp).testTag("fromThisPhone"), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PhoneAndroid, null, Modifier.size(14.dp), tint = Color(0xFFA0A09D))
                Text("On this phone", Modifier.padding(start = 6.dp), fontSize = 13.sp, color = Color(0xFFA0A09D))
            }
        }
        LazyColumn(Modifier.testTag("search-results"), contentPadding = PaddingValues(bottom = dev.coda.android.ui.shell.LocalDockPadding.current + 16.dp)) {
            if (state.query.isBlank()) item { Text("Search artists, albums and songs") }
            else if (!state.pending && !state.failed && state.results == SearchResults()) item {
                Column {
                    Text(if (state.fromPhone) "Nothing on this phone matches “${state.query.trim()}”" else "No results for “${state.query.trim()}”")
                    // Offline, say why.
                    if (state.fromPhone) Text("Offline, Search finds only the music on this phone.",
                        Modifier.padding(top = 4.dp), fontSize = 13.sp, color = Color(0xFFA0A09D))
                }
            }
            if (state.results.artists.isNotEmpty()) {
                stickyHeader(key = "heading-artists") { SearchHeading("Artists", backdrop) { origin[0] } }
                item { LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(state.results.artists, key = { it.id }) { artist -> Column(Modifier.width(88.dp).testTag("result-artist-${artist.id}").combinedClickable(onClick = { openArtist(artist) },
                        onLongClick = { menus?.artist(artist) }, onLongClickLabel = "Artist actions"), horizontalAlignment = Alignment.CenterHorizontally) {
                        Avatar(artist, fetcher, accent = accent); Match(artist.name, state.query, accent)
                    } }
                } }
            }
            if (state.results.albums.isNotEmpty()) stickyHeader(key = "heading-albums") { SearchHeading("Albums", backdrop) { origin[0] } }
            // Albums and tracks read apart at a glance. Albums: 72 dp rows, 56 dp cover with the Small rating badge,
            // 16 sp / 600 title, "Album · artist · year".
            items(state.results.albums, key = { "album-${it.id}" }) { album ->
                Row(Modifier.fillMaxWidth().testTag("result-album-${album.id}").albumCell(currentRating(album.id, album.userRating))
                    .combinedClickable(onClick = { openAlbum(album) }, onClickLabel = "Open album",
                    onLongClick = { menus?.album(album) }, onLongClickLabel = "Album actions").heightIn(min = 72.dp).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        CoverImage(fetcher, album.artworkId, Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)))
                        AlbumRatingBadge(currentRating(album.id, album.userRating), BadgeSize.Small)
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Match(album.name, state.query, accent, 16.sp, FontWeight.SemiBold, 22.sp)
                        // Offline, a partial album says how much of it is here ("7 of 12"), as on Home, Albums and artist pages.
                        Text(listOfNotNull(dev.coda.core.library.ReleaseTypeLabel.of(album.releaseTypes), album.artistName.takeIf { it.isNotBlank() },
                            album.releaseYear?.toString(), dev.coda.android.ui.offline.LocalOnPhone.current?.album(album.id)).joinToString(" · "), Modifier.padding(top = 2.dp), fontSize = 13.sp, lineHeight = 17.sp, color = LibText2,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (state.results.songs.isNotEmpty()) stickyHeader(key = "heading-songs") { SearchHeading("Songs", backdrop) { origin[0] } }
            // Tracks: 56 dp rows, 40 dp cover (radius 4) and never a badge, 15 sp regular title, "artist · album", the duration at the right edge.
            items(state.results.songs, key = { "song-${it.id}" }) { song ->
                Row(Modifier.fillMaxWidth().testTag("result-song-${song.id}").combinedClickable(onClick = { play(song) }, onLongClick = { menus?.track(song) },
                    onLongClickLabel = "Track actions").heightIn(min = 56.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CoverImage(fetcher, song.albumArtworkId, Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Match(song.title, state.query, accent, 15.sp, FontWeight.Normal, 20.sp)
                        Text(listOfNotNull(song.artistName.takeIf { it.isNotBlank() }, song.album?.takeIf { it.isNotBlank() }).joinToString(" · "),
                            fontSize = 13.sp, lineHeight = 17.sp, color = LibText2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    song.duration?.takeIf { it > 0 }?.let { seconds ->
                        TrackDuration(dev.coda.android.ui.player.playbackTime(seconds * 1000L), LibText2, Modifier.padding(start = 8.dp).testTag("result-song-duration-${song.id}"),
                            seconds = seconds.toLong())
                    }
                }
            }
        }
    }
}
