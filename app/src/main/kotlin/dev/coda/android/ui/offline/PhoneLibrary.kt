package dev.coda.android.ui.offline

import androidx.compose.runtime.*
import dev.coda.android.ui.library.CollectionState
import dev.coda.android.ui.library.LibraryState
import dev.coda.core.library.AlbumSectionKind
import dev.coda.core.library.AlbumSectioner
import dev.coda.core.library.ArtistIndex
import dev.coda.core.library.OfflineCatalogue
import dev.coda.core.subsonic.AlbumListType
import dev.coda.core.subsonic.ArtistProjection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest

/**
 * The shell's views from the phone, read from `library.db`'s catalogue and the music cache ([cached]: the song
 * ids on the phone). [reload] runs while the views show the phone and whenever the cache or the catalogue changed; the
 * last result stays for the way back. Pages are read on demand ([album], [artist], [playlist]). All reads off the main thread.
 */
@Stable
class PhoneLibrary(private val catalogue: OfflineCatalogue, private val cached: () -> List<dev.coda.core.cache.CachedTrack>) {
    var onPhone by mutableStateOf<OnPhone?>(null)
        private set
    private var shelf by mutableStateOf<OfflineCatalogue.Shelf?>(null)
    var artists by mutableStateOf<LibraryState?>(null)
        private set
    var playlists by mutableStateOf<CollectionState?>(null)
        private set
    /** Home from the phone: the shelves on the catalogue; Continue Listening is filtered per view ([home]). */
    var homeShelves by mutableStateOf<dev.coda.android.ui.home.HomeState?>(null)
        private set
    /** Bumped by each [reload]: open pages read themselves again. */
    var revision by mutableIntStateOf(0)
        private set

    /**
     * [completeOnly]: DS-16b "Only complete albums", the partial albums leave every list here ([OfflineCatalogue.Shelf.completeOnly]);
     * The playlists follow it too ([OfflineCatalogue.playlistShown]).
     */
    suspend fun reload(completeOnly: Boolean = false) {
        val records = withContext(Dispatchers.Default) { cached() }
        val songs = records.mapTo(HashSet()) { it.songId }
        val played = records.mapNotNull { r -> r.lastPlayedAt?.let { r.songId to it } }.toMap()
        val newShelf = catalogue.shelf(songs, played).let { if (completeOnly) it.completeOnly() else it }
        val (lists, listCounts) = catalogue.playlists(songs, completeOnly)
        val artistList = catalogue.artists(newShelf)
        val index = withContext(Dispatchers.Default) { ArtistIndex.of(artistList) }
        val counts = newShelf.counts.mapKeys { "album/${it.key}" } + listCounts.mapKeys { "playlist/${it.key}" }
        shelf = newShelf
        artists = LibraryState(artists = artistList, index = index)
        playlists = CollectionState(playlists = lists, loading = false, complete = true)
        homeShelves = withContext(Dispatchers.Default) {
            dev.coda.android.ui.home.HomeState(albums = newShelf.ordered(AlbumListType.NEWEST).take(HOME_SHELF),
                recentReleases = newShelf.ordered(AlbumListType.RECENT_RELEASES).take(HOME_SHELF),
                recentlyPlayed = newShelf.recentlyPlayed(RECENTLY_PLAYED), playlists = lists, loading = false, resumeKnown = true)
        }
        onPhone = OnPhone(songs, counts)
        revision++
    }

    /** The Albums tab from the phone in [type]'s order, with [sections] as the live tab computes them. */
    suspend fun albums(type: AlbumListType, sections: AlbumSectionKind): CollectionState? {
        val current = shelf ?: return null
        return withContext(Dispatchers.Default) {
            val ordered = current.ordered(type)
            val split = if (sections == AlbumSectionKind.NONE) emptyList() else AlbumSectioner.sections(sections, ordered)
            CollectionState(albums = ordered, loading = false, complete = true, freshCount = ordered.size, sections = split)
        }
    }

    /** An album page from the phone; with nothing of it here, the "not on this phone" block (failed, no page). */
    suspend fun album(id: String): dev.coda.android.ui.album.AlbumState {
        val songs = onPhone?.songs ?: return dev.coda.android.ui.album.AlbumState(loading = true)
        val found = catalogue.album(id, songs)
        return dev.coda.android.ui.album.AlbumState(found?.first, loading = false, failed = found == null)
    }

    /**
     * Home from the phone: the shelves on the catalogue (Recently Added, Recent Releases, Recently Played from the phone's
     * play dates, the offline playlists), Continue Listening where the album's next track is here, and no handoff offer.
     */
    fun home(live: dev.coda.android.ui.home.HomeState): dev.coda.android.ui.home.HomeState? {
        val shelves = homeShelves ?: return null
        val here = onPhone ?: return null
        // The shelf's albums (not [OnPhone.counts]): "Only complete albums" hides a partial one here too.
        val ids = shelf?.ids.orEmpty()
        val resume = live.continueListening.filter { here.has(it.resumeSongId) && it.album.id in ids }
        return shelves.copy(continueListening = resume, resumeProgress = live.resumeProgress, handoff = null)
    }

    /** Search offline: the catalogue's FTS, only what's on the phone. */
    suspend fun search(query: String): dev.coda.core.subsonic.SearchResults {
        val current = shelf ?: return dev.coda.core.subsonic.SearchResults()
        val songs = onPhone?.songs ?: return dev.coda.core.subsonic.SearchResults()
        return catalogue.search(query, current, songs)
    }

    /**
     * An album page from the phone for an action (menus, Search, Go to artist offline); null when nothing of it is here.
     * A tap before the phone's first read waits for it (the action is not lost), at most [FIRST_READ_MS].
     */
    suspend fun albumPage(id: String): dev.coda.core.subsonic.AlbumPage? = firstRead()?.songs?.let { catalogue.album(id, it)?.first }
    suspend fun artistPage(id: String): dev.coda.core.subsonic.ArtistPage? { firstRead(); return shelf?.let { catalogue.artist(id, it)?.first } }
    suspend fun playlistPage(id: String): dev.coda.core.subsonic.PlaylistPage? = firstRead()?.songs?.let { catalogue.playlist(id, it)?.first }

    /** What's on the phone, once read ([reload] sets the shelf first, then this). */
    suspend fun firstRead(): OnPhone? = onPhone ?: kotlinx.coroutines.withTimeoutOrNull(FIRST_READ_MS) {
        snapshotFlow { onPhone }.filterNotNull().first()
    }

    suspend fun artist(id: String): LibraryState {
        val current = shelf ?: return LibraryState(loading = true)
        val found = catalogue.artist(id, current) ?: return LibraryState(failed = true, offline = true)
        val projection = withContext(Dispatchers.Default) { ArtistProjection.from(found.first, found.second) }
        return LibraryState(artist = found.first, projection = projection)
    }

    suspend fun playlist(id: String): CollectionState {
        val songs = onPhone?.songs ?: return CollectionState(loading = true)
        val found = catalogue.playlist(id, songs) ?: return CollectionState(loading = false, failed = true)
        // The stored-cover checks stat files: off Main.
        val covers = withContext(Dispatchers.IO) { catalogue.headerCovers(found.first) }
        return CollectionState(playlist = found.first, loading = false, headerCovers = covers)
    }
}

private const val HOME_SHELF = 24
/** How long an action waits for the phone's first read (a catalogue read: well under a second on a phone). */
internal const val FIRST_READ_MS = 5_000L
private const val RECENTLY_PLAYED = 20

/** What makes the phone's content change: a catalogue run (after the cache changed) or the offline library's own changes. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun phoneChanges(app: dev.coda.android.CodaApp): kotlinx.coroutines.flow.Flow<Any> =
    kotlinx.coroutines.flow.combine(app.catalogueRuns,
        app.offlineLibrary.flatMapLatest { it?.revision ?: kotlinx.coroutines.flow.flowOf(0L) }) { runs, revision -> runs to revision }
