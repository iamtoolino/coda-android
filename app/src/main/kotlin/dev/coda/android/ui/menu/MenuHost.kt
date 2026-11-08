package dev.coda.android.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import dev.coda.android.ui.common.NoticeAction
import dev.coda.android.ui.common.showNotice
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.coda.android.CodaApp
import dev.coda.android.artwork.CoverArtFetcher
import dev.coda.android.artwork.CoverImage
import dev.coda.android.ui.album.albumResumeIndex
import dev.coda.android.ui.common.AddPrompt
import dev.coda.android.ui.common.AddPromptDialogs
import dev.coda.android.ui.common.rememberAddPrompt
import dev.coda.core.cache.DownloadLines
import dev.coda.core.cache.KeptItem
import dev.coda.core.cache.KeptTrack
import dev.coda.core.library.TrackListPresentation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dev.coda.core.subsonic.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** What a long-press or ⋮ opened: the item and the screen's context. */
sealed interface MenuTarget {
    data class AlbumTarget(val album: Album, val context: AlbumMenuContext = AlbumMenuContext()) : MenuTarget
    /**
     * [playlistId]/[index]/[snapshot]: the track's entry on a playlist page (for Remove from playlist) and the song ids of
     * the whole page when the menu opened; the removal is positional, so it runs only if the playlist is still exactly that.
     */
    data class TrackTarget(val song: Song, val context: TrackMenuContext = TrackMenuContext(),
        val playlistId: String? = null, val index: Int? = null, val snapshot: List<String> = emptyList()) : MenuTarget
    data class PlaylistTarget(val playlist: Playlist) : MenuTarget
    class ArtistTarget(val artist: Artist,
        /** Opened from the artist page, the sheet wears the artist colour (the page's view accent), read live so it follows the page as it settles. */
        val colors: (() -> MenuColors)? = null) : MenuTarget
}

/** Opens the one shared item sheet; every screen reaches it through [LocalItemMenus]. */
@Stable
class ItemMenuState {
    var target by mutableStateOf<MenuTarget?>(null)
    fun album(album: Album, context: AlbumMenuContext = AlbumMenuContext()) {
        target = MenuTarget.AlbumTarget(album, context)
    }
    fun track(song: Song, context: TrackMenuContext = TrackMenuContext(), playlistId: String? = null, index: Int? = null, snapshot: List<String> = emptyList()) {
        target = MenuTarget.TrackTarget(song, context, playlistId, index, snapshot)
    }
    fun playlist(playlist: Playlist) { target = MenuTarget.PlaylistTarget(playlist) }
    fun artist(artist: Artist, colors: (() -> MenuColors)? = null) {
        target = MenuTarget.ArtistTarget(artist, colors)
    }
}

val LocalItemMenus = staticCompositionLocalOf<ItemMenuState?> { null }

/** The shell's actions the menus use (LibraryShell owns the controller, navigation and playback preparation). */
class MenuActions(
    val client: SubsonicClient,
    val username: String,
    val loadAlbum: suspend (String) -> AlbumPage?,
    val loadPlaylist: suspend (String) -> PlaylistPage?,
    val loadArtist: suspend (String) -> ArtistPage?,
    /** The playlists saved on this phone (Playlists tab or Home), shown at once while the server's list loads. */
    val savedPlaylists: suspend () -> List<Playlist>?,
    val play: (List<Song>, Int) -> Unit,
    /** Inserts and returns the entry ids for Undo. */
    val enqueue: (List<Song>, Boolean) -> List<String>,
    val undo: (List<String>) -> Unit,
    val playAlbums: (List<Album>, shuffle: Boolean) -> Unit,
    /** Prepares the albums' tracks, then [then] (null: append them to the queue as they are). */
    val enqueueAlbums: (List<Album>, then: ((List<Song>) -> Unit)?) -> Unit,
    val openAlbum: (Album) -> Unit,
    val openArtist: (String) -> Unit,
    val rate: (String, Int) -> Unit,
    val playlistChanged: (String) -> Unit,
    val playlistDeleted: (String) -> Unit,
    val unauthorized: suspend () -> Unit,
    /** Offline, the songs on the phone (Add to queue and Play next take only those); online all of them. */
    val phoneOnly: (List<Song>) -> List<Song> = { it },
    /** The actions take the tracks on the phone right now (offline, or a view still shows the phone's content). */
    val phoneMode: () -> Boolean = { false },
    /** The album's resume track from Home's Continue Listening (by album id), or null. */
    val resumePoint: (albumId: String) -> String? = { null },
    /** The player's current media id ("song/<id>"), so the album playing now isn't offered Resume. */
    val currentMediaId: () -> String? = { null },
    /** The track is on this phone (the cache's index); asked only while [phoneMode]. */
    val onPhone: (String) -> Boolean = { true },
    /** Resume album: the album from [index], as an album continuation (the album page's card does the same). */
    val resumeAlbum: (Album, List<Song>, index: Int) -> Unit = { _, _, _ -> },
    /** Add remaining to queue: appends [songs] (already the phone's, offline) as an album continuation; entry ids for Undo. */
    val appendRemaining: (Album, List<Song>) -> List<String> = { _, _ -> emptyList() },
)

/** Album songs in the album's order, with the album's year and artist (as the album page queues them). */
internal fun albumSongs(page: AlbumPage): List<Song> = AlbumOrdering.canonical(page.songs).map { song ->
    song.copy(year = song.year ?: page.album.releaseYear, displayAlbumArtist = page.album.artistName)
}

/**
 * Remove from playlist is positional on the server. It is safe only when the playlist read again is exactly the list the
 * menu was opened on (same songs in the same order, so a duplicate song cannot be mistaken for its twin) and [index] is
 * still the chosen song.
 */
internal fun removalStillValid(snapshot: List<String>, fresh: List<String>, index: Int, songId: String): Boolean =
    snapshot.isNotEmpty() && fresh == snapshot && fresh.getOrNull(index) == songId

/** A playlist you can edit: yours, or the server didn't say. */
internal fun editable(playlist: Playlist, username: String) = playlist.owner.isNullOrBlank() || playlist.owner == username

/**
 * The one host of the item sheets and their follow-ups (Add to playlist…, Rename, Delete).
 * One sheet at a time; queue additions confirm with an Undo snackbar in [snackbar].
 */
@Composable
fun ItemMenuHost(state: ItemMenuState, actions: MenuActions, fetcher: CoverArtFetcher, colors: MenuColors, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    var picker by remember { mutableStateOf<List<String>?>(null) }
    var newPlaylist by remember { mutableStateOf<List<String>?>(null) }
    var playlistEdit by remember { mutableStateOf<Pair<Playlist, Boolean>?>(null) }
    val prompt = rememberAddPrompt()
    // The add starts from the Keep offline switch of an open album/playlist sheet, which is its own window above the shell:
    // while a sheet is open its notice ("Saved for offline · downloads on Wi-Fi") shows in the sheet.
    val sheetSnackbar = remember { SnackbarHostState() }
    AddPromptDialogs(prompt, if (state.target != null) sheetSnackbar else snackbar)
    // The sheet's host leaves with the sheet, but a notice still showing would wait on and reappear in the next sheet
    // (Material's timeout runs in the host): it goes with the sheet.
    LaunchedEffect(state.target == null) { if (state.target == null) sheetSnackbar.currentSnackbarData?.dismiss() }
    // One playlist edit at a time, run in the host's scope: it outlives the sheet that started it (closing a sheet must
    // not cancel its request) and a second edit starts only after the first one has refreshed the page.
    val edits = remember { kotlinx.coroutines.sync.Mutex() }
    fun close() { state.target = null }
    fun message(text: String, undo: (() -> Unit)? = null) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showNotice(text, if (undo != null) NoticeAction.Undo else null)
            if (result == SnackbarResult.ActionPerformed) undo?.invoke()
        }
    }
    fun queue(songs: List<Song>, next: Boolean, name: String, insert: (List<Song>) -> List<String> = { actions.enqueue(it, next) }) {
        val here = actions.phoneOnly(songs)
        if (here.isEmpty() && songs.isNotEmpty()) { message(dev.coda.android.ui.offline.NOT_ON_PHONE); return }
        val ids = insert(here)
        val line = (if (next) "Playing next: " else "Added to queue: ") + name
        message(if (here.size < songs.size) dev.coda.android.ui.offline.queuedOnPhone(line, here.size) else line) { actions.undo(ids) }
    }
    /**
     * Remove from playlist. The server removes by position, so the entry is looked up again first: if the playlist
     * changed since the menu opened (an earlier entry was removed or added elsewhere), nothing is removed.
     */
    fun removeEntry(playlistId: String, song: Song, index: Int, snapshot: List<String>) {
        scope.launch {
            edits.withLock {
                try {
                    val fresh = actions.client.getPlaylist(playlistId).songs
                    if (!removalStillValid(snapshot, fresh.map { it.id }, index, song.id)) {
                        actions.playlistChanged(playlistId)
                        message("The playlist changed. Open the menu again.")
                        return@withLock
                    }
                    actions.client.removeFromPlaylist(playlistId, index)
                    actions.playlistChanged(playlistId)
                    message("Removed from playlist")
                } catch (e: CancellationException) { throw e }
                catch (_: SubsonicError.Unauthorized) { actions.unauthorized() }
                catch (_: Exception) { message("Couldn't update the playlist. Try again.") }
            }
        }
    }
    val context = Context(actions, colors, fetcher, prompt, sheetSnackbar, close = ::close, queue = { songs, next, name -> queue(songs, next, name) },
        append = { songs, name, insert -> queue(songs, false, name, insert) }, message = ::message,
        addToPlaylist = { ids -> picker = ids }, editPlaylist = { playlist, delete -> playlistEdit = playlist to delete },
        // On the host's scope (the prompt is the host's), so closing the sheet while it waits for the library can't
        // cancel it. A failed save shows the prompt's "Offline music" message.
        stopKeeping = prompt::stop, removeEntry = ::removeEntry)
    when (val target = state.target) {
        is MenuTarget.AlbumTarget -> AlbumSheet(target, context)
        is MenuTarget.TrackTarget -> TrackSheet(target, context)
        is MenuTarget.PlaylistTarget -> PlaylistSheet(target, context)
        is MenuTarget.ArtistTarget -> ArtistSheet(target, context)
        null -> Unit
    }
    picker?.let { ids -> AddToPlaylistSheet(ids, actions, colors, edits, { picker = null }, newPlaylist = { picker = null; newPlaylist = ids }) { name ->
        picker = null; message("Added to $name") } }
    newPlaylist?.let { ids -> dev.coda.android.ui.library.SavePlaylistDialog(ids, { newPlaylist = null }, { id ->
        newPlaylist = null; actions.playlistChanged(id); message("Saved as a new playlist") }, title = "New playlist", initialName = "") }
    playlistEdit?.let { (playlist, delete) -> PlaylistEditDialog(playlist, delete, actions, edits) { done ->
        playlistEdit = null
        if (done && delete) actions.playlistDeleted(playlist.id) else if (done) actions.playlistChanged(playlist.id)
    } }
}

private class Context(
    val actions: MenuActions, val colors: MenuColors, val fetcher: CoverArtFetcher, val prompt: AddPrompt,
    /** The open sheet's own snackbar (the sheet is a window above the shell's snackbar). */
    val sheetSnackbar: SnackbarHostState,
    val close: () -> Unit, val queue: (List<Song>, Boolean, String) -> Unit,
    /** [queue] at the end of the queue through its own [insert] (Add remaining to queue). */
    val append: (List<Song>, String, insert: (List<Song>) -> List<String>) -> Unit, val message: (String, (() -> Unit)?) -> Unit,
    val addToPlaylist: (List<String>) -> Unit, val editPlaylist: (Playlist, Boolean) -> Unit,
    val removeEntry: (playlistId: String, song: Song, index: Int, snapshot: List<String>) -> Unit,
    val stopKeeping: (String) -> Unit,
)

/** Loads once per sheet (the saved page first, then the server); null while loading or after a failure. */
@Composable
private fun <T : Any> rememberLoaded(key: String, load: suspend () -> T?): State<T?> {
    val value = remember(key) { mutableStateOf<T?>(null) }
    LaunchedEffect(key) {
        val loaded = try { load() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        withContext(Dispatchers.Main.immediate) { value.value = loaded }
    }
    return value
}

/** The Keep offline switch row from the shared download state and the item's cache status. */
@Composable
private fun keepRow(key: String, title: String, songs: List<Song>, context: Context, coverArt: String? = null): KeepOfflineRow? {
    val app = LocalContext.current.applicationContext as? CodaApp ?: return null
    val library by app.offlineLibrary.collectAsState()
    val host = library ?: return null
    val items by host.items.collectAsState()
    val revision by host.revision.collectAsState()
    val downloads by host.downloads.collectAsState()
    val ids = remember(songs) { songs.map { it.id } }
    val status = remember(items, revision, downloads, ids) { host.status(key, ids) }
    val item = remember(key, songs, title, coverArt) { KeptItem(key, songs.map(KeptTrack::from), title, coverArt = coverArt) }
    val line = if (ids.isEmpty()) null
        else DownloadLines.keepSheet(status, downloads.phase(key), status.availableUntil?.let { DownloadLines.day(it) })
    return KeepOfflineRow(status.looksKept, line, enabled = ids.isNotEmpty() && !context.prompt.busy,
        // Off removes the set at once (no dialog, nothing deleted); released tracks start a fresh Heard once.
        toggle = { on -> if (on) context.prompt.start(item) else context.stopKeeping(key) })
}

@Composable
private fun AlbumSheet(target: MenuTarget.AlbumTarget, context: Context) {
    val actions = context.actions
    val page by rememberLoaded("album-${target.album.id}") { actions.loadAlbum(target.album.id) }
    val album = page?.album ?: target.album
    val songs = remember(page) { page?.let(::albumSongs).orEmpty() }
    val artistId = album.artistId ?: album.artists?.firstOrNull()?.id
    // The sheet finds the resume point itself, from any screen, with the album page's rule.
    val resumeIndex = albumResumeIndex(songs.map { it.id }, actions.resumePoint(album.id), actions.currentMediaId(),
        actions.onPhone.takeIf { actions.phoneMode() })
    val spec = ItemMenus.album(target.context.copy(compilation = target.context.compilation || album.isCompilation == true || artistId == null),
        resumable = resumeIndex >= 0)
    val rating = dev.coda.android.ui.library.currentRating(album.id, album.userRating)
    val meta = listOfNotNull(album.artistName.ifBlank { null }, album.releaseYear?.toString(),
        (album.songCount ?: songs.size.takeIf { it > 0 })?.let { "$it track" + if (it == 1) "" else "s" }).joinToString(" · ")
    ItemSheet(spec, context.colors, context.close, tag = "albumSheet", keepOffline = keepRow("album/${album.id}", album.name, songs, context, album.artworkId), snackbar = context.sheetSnackbar,
        enabled = { it == MenuAction.GO_TO_ARTIST || songs.isNotEmpty() },
        detail = { action -> if (action == MenuAction.RESUME) "Track ${resumeIndex + 1} · ${songs[resumeIndex].title}" else null },
        header = { SheetHeader(album.name, meta) { CoverImage(context.fetcher, album.artworkId, it) } },
        rating = { RatingRow(rating, context.colors) { actions.rate(album.id, it) } },
        onAction = { action ->
            when (action) {
                MenuAction.PLAY -> actions.play(songs, 0)
                MenuAction.PLAY_NEXT, MenuAction.ADD_TO_QUEUE -> context.queue(songs, action == MenuAction.PLAY_NEXT, album.name)
                MenuAction.ADD_TO_PLAYLIST -> context.addToPlaylist(songs.map { it.id })
                MenuAction.GO_TO_ARTIST -> artistId?.let(actions.openArtist)
                MenuAction.RESUME -> actions.resumeAlbum(album, songs, resumeIndex)
                MenuAction.APPEND_REMAINING -> context.append(songs.drop(resumeIndex), album.name) { actions.appendRemaining(album, it) }
                else -> Unit
            }
            if (action != MenuAction.KEEP_OFFLINE) context.close()
        })
}

@Composable
private fun TrackSheet(target: MenuTarget.TrackTarget, context: Context) {
    val actions = context.actions
    val song = target.song
    val artistId = song.artistId ?: song.artists?.firstOrNull()?.id
    val spec = ItemMenus.track(target.context.copy(onAlbumPage = target.context.onAlbumPage || song.albumId == null,
        hasArtist = artistId != null, removable = target.context.removable && target.playlistId != null && target.index != null))
    ItemSheet(spec, context.colors, context.close, tag = "trackSheet",
        header = { SheetHeader(song.title, listOfNotNull(song.artistName.ifBlank { null }, song.album).joinToString(" · ")) {
            CoverImage(context.fetcher, song.albumArtworkId, it) } },
        onAction = { action ->
            when (action) {
                MenuAction.PLAY_NEXT, MenuAction.ADD_TO_QUEUE -> context.queue(listOf(song), action == MenuAction.PLAY_NEXT, song.title)
                MenuAction.ADD_TO_PLAYLIST -> context.addToPlaylist(listOf(song.id))
                MenuAction.GO_TO_ALBUM -> song.albumId?.let { actions.openAlbum(Album(it, song.album ?: "Album", coverArt = song.albumArtworkId)) }
                MenuAction.GO_TO_ARTIST -> artistId?.let(actions.openArtist)
                MenuAction.REMOVE_FROM_PLAYLIST -> context.removeEntry(target.playlistId!!, song, target.index!!, target.snapshot)
                else -> Unit
            }
            context.close()
        })
}

@Composable
private fun PlaylistSheet(target: MenuTarget.PlaylistTarget, context: Context) {
    val actions = context.actions
    val page by rememberLoaded("playlist-${target.playlist.id}") { actions.loadPlaylist(target.playlist.id) }
    val playlist = page?.playlist ?: target.playlist
    val songs = page?.songs.orEmpty()
    // Shuffle as the playlist page offers it: in album-run units, and only with two or more of them.
    val canShuffle = page == null || TrackListPresentation.canShufflePlaylist(TrackListPresentation.playlistGroups(songs))
    val spec = ItemMenus.playlist(PlaylistMenuContext(editable(playlist, actions.username), shuffle = canShuffle))
    val count = playlist.songCount ?: songs.size.takeIf { it > 0 }
    ItemSheet(spec, context.colors, context.close, tag = "playlistSheet", keepOffline = keepRow("playlist/${playlist.id}", playlist.name, songs, context, playlist.coverArt), snackbar = context.sheetSnackbar,
        enabled = { it == MenuAction.RENAME || it == MenuAction.DELETE || songs.isNotEmpty() },
        header = { SheetHeader(playlist.name, count?.let { "$it track" + if (it == 1) "" else "s" }) { CoverImage(context.fetcher, playlist.coverArt, it) } },
        onAction = { action ->
            when (action) {
                MenuAction.PLAY -> actions.play(songs, 0)
                MenuAction.SHUFFLE -> actions.play(TrackListPresentation.shuffledPlaylist(songs), 0)
                MenuAction.PLAY_NEXT, MenuAction.ADD_TO_QUEUE -> context.queue(songs, action == MenuAction.PLAY_NEXT, playlist.name)
                MenuAction.RENAME, MenuAction.DELETE -> context.editPlaylist(playlist, action == MenuAction.DELETE)
                else -> Unit
            }
            if (action != MenuAction.KEEP_OFFLINE) context.close()
        })
}

@Composable
private fun ArtistSheet(target: MenuTarget.ArtistTarget, context: Context) {
    val actions = context.actions
    val artist = target.artist
    val page by rememberLoaded("artist-${artist.id}") { actions.loadArtist(artist.id) }
    val projection = remember(page) { page?.let { ArtistProjection.from(it) } }
    // Play and Add to queue go oldest release first; Play newest and Play best pick their own.
    val albums = projection?.playOrder.orEmpty()
    val spec = ItemMenus.artist()
    // Newest first, as before PF-10a: releases tied on rating, plays and year keep playing latest first.
    val bestOf = dev.coda.core.library.artistBestOf(projection?.newest.orEmpty().map { it.copy(userRating = dev.coda.android.ui.library.currentRating(it.id, it.userRating)) })
    val count = page?.albums?.size ?: artist.albumCount
    val photo = page?.artist?.coverArt ?: artist.coverArt
    val sheetColors = target.colors?.invoke() ?: context.colors
    ItemSheet(spec, sheetColors, context.close, tag = "artistSheet",
        enabled = { ItemMenus.artistEnabled(it, albums.size, bestOf.isNotEmpty()) },
        header = { SheetHeader(artist.name, listOfNotNull("Artist", count?.let { "$it album" + if (it == 1) "" else "s" }).joinToString(" · ")) { modifier ->
            Box(modifier.clip(CircleShape).background(sheetColors.fill.copy(alpha = .35f)), contentAlignment = Alignment.Center) {
                // The artist's image as the artist page shows it; initials only without one (or when it fails to load).
                val initials: @Composable BoxScope.() -> Unit = {
                    Text(dev.coda.android.ui.library.initials(artist.name), Modifier.align(Alignment.Center), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MenuText1)
                }
                if (photo != null) CoverImage(context.fetcher, photo, Modifier.fillMaxSize().testTag("artistSheetImage"), missing = initials) else initials()
            } } },
        onAction = { action ->
            when (action) {
                MenuAction.PLAY -> actions.playAlbums(albums, false)
                MenuAction.BEST_OF -> actions.playAlbums(bestOf, false)
                MenuAction.NEWEST -> actions.playAlbums(listOfNotNull(projection?.newestRelease), false)
                MenuAction.SHUFFLE -> actions.playAlbums(albums, true)
                // Offline: one message once the tracks here are known, with the name and Undo
                // ("Added to queue: Name · the N tracks on this phone", or "Not on this phone"), as the album menu says it.
                MenuAction.ADD_TO_QUEUE -> if (actions.phoneMode()) actions.enqueueAlbums(albums) { songs ->
                    if (songs.isEmpty()) context.message(dev.coda.android.ui.offline.NOT_ON_PHONE, null)
                    else context.queue(songs, false, artist.name)
                } else { actions.enqueueAlbums(albums, null); context.message("Added to queue: ${artist.name}", null) }
                else -> Unit
            }
            context.close()
        })
}

/** "Add to playlist…": the playlists you can edit, newest change first, and "New playlist…". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddToPlaylistSheet(songIds: List<String>, actions: MenuActions, colors: MenuColors, edits: Mutex, dismiss: () -> Unit,
    newPlaylist: () -> Unit, added: (String) -> Unit) {
    var playlists by remember { mutableStateOf<List<Playlist>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var addFailed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // While the add runs the sheet stays (back, scrim and swipe do nothing): closing it would cancel the request and skip
    // the refresh of the playlist after the server already took the songs.
    val busyNow = rememberUpdatedState(busy)
    val sheetState = rememberModalBottomSheetState(confirmValueChange = { target -> target != SheetValue.Hidden || !busyNow.value })
    fun mine(list: List<Playlist>) = list.filter { editable(it, actions.username) }.sortedByDescending { it.changed.orEmpty() }
    LaunchedEffect(Unit) {
        // The saved list first, then the server's (it also has playlists made elsewhere since). The state is written on
        // the main thread only: a write from the thread that finished a request can interleave with the sheet's first
        // composition (the window composes on its own) and is then never seen (device runs showed a spinner forever
        // although the answer had arrived).
        val saved = try { actions.savedPlaylists()?.let(::mine) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        withContext(Dispatchers.Main.immediate) { playlists = saved }
        val fresh = try { withTimeoutOrNull(20_000) { actions.client.getPlaylists() } }
            catch (e: Exception) {
                ensureActive()
                android.util.Log.w("CodaMenus", "Playlists not loaded: ${e.javaClass.simpleName}")
                null
            }
        withContext(Dispatchers.Main.immediate) {
            if (fresh != null) playlists = mine(fresh)
            else if (playlists == null) { failed = true; playlists = emptyList() }
        }
    }
    ModalBottomSheet(onDismissRequest = { if (!busyNow.value) dismiss() }, sheetState = sheetState, containerColor = Color(0xFF131518), contentColor = MenuText1, modifier = Modifier.testTag("addToPlaylistSheet")) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
            Text("Add to playlist", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !busy, onClick = newPlaylist).padding(horizontal = 20.dp)
                .testTag("newPlaylist"), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Add, null, Modifier.size(24.dp), tint = colors.text)
                Spacer(Modifier.width(20.dp)); Text("New playlist…", fontSize = 15.sp)
            }
            HorizontalDivider(color = Color.White.copy(alpha = .06f))
            when {
                playlists == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp))
                failed -> Text("Couldn't load your playlists.", Modifier.padding(20.dp), color = MenuText2)
                else -> androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    // A failed add keeps the list: tap a playlist to try again.
                    if (addFailed) item { Text("Couldn't add to the playlist. Try again.", Modifier.padding(20.dp, 8.dp).testTag("addToPlaylistFailed"),
                        color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                    items(playlists!!.size) { index ->
                        val playlist = playlists!![index]
                        Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = !busy) {
                            busy = true; addFailed = false
                            scope.launch {
                                try {
                                    edits.withLock { actions.client.addToPlaylist(playlist.id, songIds) }
                                    actions.playlistChanged(playlist.id); added(playlist.name)
                                }
                                catch (e: CancellationException) { throw e }
                                catch (_: SubsonicError.Unauthorized) { actions.unauthorized(); dismiss() }
                                catch (_: Exception) { addFailed = true }
                                finally { busy = false }
                            }
                        }.padding(horizontal = 20.dp, vertical = 8.dp).testTag("addTo-${playlist.id}"), verticalArrangement = Arrangement.Center) {
                            Text(playlist.name, fontSize = 15.sp)
                            playlist.songCount?.let { Text("$it track" + if (it == 1) "" else "s", fontSize = 12.5.sp, color = MenuText2) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistEditDialog(playlist: Playlist, delete: Boolean, actions: MenuActions, edits: Mutex, done: (Boolean) -> Unit) {
    var name by remember { mutableStateOf(playlist.name) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) done(false) },
        title = { Text(if (delete) "Delete playlist?" else "Rename playlist") },
        text = { Column {
            if (delete) Text("Delete this playlist from the server?")
            else OutlinedTextField(name, { name = it }, label = { Text("Playlist name") }, singleLine = true)
            if (failed) Text("Couldn't update the playlist. Try again.", color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(onClick = {
            busy = true; failed = false
            scope.launch {
                try {
                    edits.withLock {
                        if (delete) actions.client.deletePlaylist(playlist.id)
                        else actions.client.renamePlaylist(playlist.id, name)
                    }
                    done(true)
                } catch (e: CancellationException) { throw e }
                catch (_: SubsonicError.Unauthorized) { actions.unauthorized(); done(false) }
                catch (_: Exception) { failed = true }
                finally { busy = false }
            }
        }, enabled = !busy && (delete || name.isNotBlank()), modifier = Modifier.testTag("playlistEditConfirm")) { Text(if (delete) "Delete" else "Save") } },
        dismissButton = { TextButton(onClick = { done(false) }, enabled = !busy) { Text("Cancel") } })
}
