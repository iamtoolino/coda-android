package dev.coda.core.library

import dev.coda.core.connectivity.Reachability
import dev.coda.core.subsonic.AlbumListPage
import dev.coda.core.subsonic.AlbumListType
import dev.coda.core.subsonic.AlbumPage
import dev.coda.core.subsonic.ArtistPage
import dev.coda.core.subsonic.Bookmark
import dev.coda.core.subsonic.Playlist
import dev.coda.core.subsonic.PlaylistPage
import dev.coda.core.subsonic.SubsonicError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The car's browse tree from the phone's offline catalogue ([OfflineCatalogue], the phone's offline views' own
 * queries). Only what's on the phone ([onPhone]: the cached song ids): albums with a track here, their album artists,
 * the playlists the phone shows offline ([OfflineCatalogue.playlistShown]); an album or playlist lists (and so queues) only its songs on the phone. No server bookmarks offline,
 * so Home leaves Continue Listening out (the phone's offline Home draws no empty shelf). One instance per car request;
 * the shelf is read once, on first use. AA-6 [completeOnly]: "Complete albums only" as on the phone ([OfflineCatalogue.Shelf.completeOnly]):
 * partial albums leave the lists, the artists and their pages, and Search finds their songs here as tracks only. Playlists
 * follow the phone's rule for the setting; a playlist's and a partial album's own page still open by id (no error screen while driving), as on the phone. Playback (taps, voice) uses an instance without it, so a track or a voice match still plays.
 */
class PhoneTreeSource(
    private val catalogue: OfflineCatalogue,
    private val onPhone: Set<String>,
    /** Song id to its last play on the phone (wall clock), for Recently Played. */
    private val playedAt: Map<String, Long> = emptyMap(),
    private val completeOnly: Boolean = false,
) : TreeSource {
    private val lock = Mutex()
    private var shelf: OfflineCatalogue.Shelf? = null
    private suspend fun shelf() = lock.withLock {
        shelf ?: catalogue.shelf(onPhone, playedAt).let { if (completeOnly) it.completeOnly() else it }.also { shelf = it }
    }

    override suspend fun getAlbumList2(size: Int, offset: Int, type: AlbumListType): AlbumListPage {
        val shelf = shelf()
        val all = if (type == AlbumListType.RECENTLY_PLAYED) shelf.recentlyPlayed(Int.MAX_VALUE) else shelf.ordered(type)
        return AlbumListPage(all.drop(offset.coerceAtLeast(0)).take(size.coerceAtLeast(0)), all.size)
    }

    override suspend fun getArtists() = catalogue.artists(shelf())

    override suspend fun getArtist(id: String): ArtistPage = catalogue.artist(id, shelf())?.first ?: throw SubsonicError.NotFound()

    override suspend fun getAlbum(id: String): AlbumPage = catalogue.album(id, onPhone)?.first
        ?.let { page -> page.copy(songs = page.songs.filter { it.id in onPhone }) } ?: throw SubsonicError.NotFound()

    @Volatile private var counts: Map<String, OfflineCatalogue.Count> = emptyMap()

    /** The phone's offline playlists (by [completeOnly]), with their songs on the phone counted for [playlistSongs]. */
    override suspend fun getPlaylists() = catalogue.playlists(onPhone, completeOnly).let { (lists, counts) ->
        this.counts = counts
        lists
    }

    override val continueListening get() = false

    /** The phone's offline Playlists rule: "n of N songs" while some are not on the phone, else "N songs". */
    override fun playlistSongs(playlist: Playlist): String? = counts[playlist.id]
        ?.let { if (it.partial) "${it.label} songs" else songsLine(it.total) } ?: super.playlistSongs(playlist)

    override suspend fun getPlaylist(id: String): PlaylistPage = catalogue.playlist(id, onPhone)?.first
        ?.let { page -> page.copy(songs = page.songs.filter { it.id in onPhone }) } ?: throw SubsonicError.NotFound()

    override suspend fun getBookmarks(): List<Bookmark> = emptyList()

    override suspend fun search3(query: String, artistCount: Int, albumCount: Int, songCount: Int) =
        catalogue.search(query, shelf(), onPhone, artistCount, albumCount, songCount)
}

/** Where a car browse or play request is served from. Per request, automatic, no switch and no indicator. */
object CarFallback {
    /**
     * How long the car waits for each server call before the phone answers. Below today's bound for one call (the
     * client's 30 s call timeout), so a dead zone never keeps the car waiting longer than before. Per call, not per
     * request: a slow but healthy server's paged album index (500 per page) still completes and fills the index.
     */
    const val LIVE_TIMEOUT_MS = 8_000L

    /** One server call didn't answer within its bound. */
    class NoAnswer : Exception("no answer within the bound")

    /** Offline, automatic or chosen: the phone answers without asking the server. */
    fun phoneFirst(state: Reachability.State) =
        state.link == Reachability.Link.OFFLINE || state.link == Reachability.Link.OFFLINE_CHOSEN

    /** The server didn't answer (a call past its bound, network error, 5xx, a proxy's answer): [Reachability.answered]'s own rule. */
    fun fallsBack(error: Throwable) = error is NoAnswer || Reachability.answered(error) == false

    private class Answer<T>(val value: T)

    private suspend fun <T> within(timeoutMs: Long, call: suspend () -> T): T =
        (withTimeoutOrNull(timeoutMs) { Answer(call()) } ?: throw NoAnswer()).value

    /** [source] with each call bounded by [timeoutMs]; a call past it throws [NoAnswer]. */
    fun bounded(source: TreeSource, timeoutMs: Long = LIVE_TIMEOUT_MS): TreeSource = object : TreeSource by source {
        override suspend fun getAlbumList2(size: Int, offset: Int, type: AlbumListType) = within(timeoutMs) { source.getAlbumList2(size, offset, type) }
        override suspend fun getArtists() = within(timeoutMs) { source.getArtists() }
        override suspend fun getArtist(id: String) = within(timeoutMs) { source.getArtist(id) }
        override suspend fun getAlbum(id: String) = within(timeoutMs) { source.getAlbum(id) }
        override suspend fun getPlaylists() = within(timeoutMs) { source.getPlaylists() }
        override suspend fun getPlaylist(id: String) = within(timeoutMs) { source.getPlaylist(id) }
        override suspend fun getBookmarks() = within(timeoutMs) { source.getBookmarks() }
        override suspend fun search3(query: String, artistCount: Int, albumCount: Int, songCount: Int) =
            within(timeoutMs) { source.search3(query, artistCount, albumCount, songCount) }
    }

    /**
     * [live] (on a [bounded] source) while the server answers, else [phone]. The server's own answers (wrong login,
     * not found) and cancellation (session ended) pass through unchanged.
     */
    suspend fun <T> serve(state: Reachability.State, live: suspend () -> T, phone: suspend () -> T,
                          onSource: (Source) -> Unit = {}): T {
        if (phoneFirst(state)) return phone().also { onSource(Source.PHONE) }
        val answer = try {
            Answer(live())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (fallsBack(e)) null else throw e
        }
        if (answer != null) return answer.value.also { onSource(Source.LIVE) }
        return phone().also { onSource(Source.PHONE) }
    }

    /** Which tree answered a car request ([serve]'s `onSource`). */
    enum class Source { LIVE, PHONE }

    /**
     * The server calls of one multi-step live request (the voice start chain), each within [timeoutMs]. Once one
     * went unanswered ([fallsBack]), every later call fails at once with that error, so [serve] reaches the phone after
     * one bound, not one per step the chain swallows.
     */
    class Calls(private val timeoutMs: Long = LIVE_TIMEOUT_MS) {
        @Volatile var unanswered: Exception? = null
            private set

        suspend fun <T> call(block: suspend () -> T): T {
            unanswered?.let { throw it }
            try {
                return within(timeoutMs, block)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (fallsBack(e)) unanswered = e
                throw e
            }
        }
    }
}

/**
 * The car's per-session [LibraryIndex] and the source of its last answer. The index is dropped when the server
 * stops being reachable and on every switch to the phone, so a dead server's albums never come back as "live" from
 * memory. [served] says whether the request switched the source (the car then reloads its lists).
 */
class CarIndex(private val newIndex: () -> LibraryIndex = { LibraryIndex() }) {
    private var current: Pair<Any, LibraryIndex>? = null
    private var source: CarFallback.Source? = null

    @Synchronized fun forSession(token: Any): LibraryIndex =
        current?.takeIf { it.first == token }?.second ?: newIndex().also { current = token to it }

    /** Reachability changed: unreachable or offline drops the index. */
    @Synchronized fun reachabilityChanged(state: Reachability.State) { if (!state.reachable) current = null }

    /**
     * The offline flag ([CarFallback.phoneFirst]) changed; its own reload follows. Offline: the phone answers from now on,
     * so the reload's phone answers are no switch. Back: the reload's first answer, live or phone, is no switch either.
     */
    @Synchronized fun offlineChanged(phoneFirst: Boolean) {
        source = if (phoneFirst) CarFallback.Source.PHONE else null
        if (phoneFirst) current = null
    }

    /** The car's lists come from the phone (the last answer was the phone's). */
    @get:Synchronized val onPhone: Boolean get() = source == CarFallback.Source.PHONE

    /** A request was answered by [answered]; true when that is a switch from the last answer's source. */
    @Synchronized fun served(answered: CarFallback.Source): Boolean {
        val switched = source != null && source != answered
        source = answered
        // Only towards the phone: a live index filled by the reload itself stays.
        if (switched && answered == CarFallback.Source.PHONE) current = null
        return switched
    }
}

/** When the car reloads its lists. */
object CarReloads {
    /** Source switches reach the car at most once per this; the last one in a window follows when it ends. */
    const val SWITCH_GAP_MS = 10_000L

    /**
     * The first value at once, then at most one per [gapMs]: values inside a window collapse into one that follows when
     * the window ends (so the final state still arrives).
     */
    fun <T> Flow<T>.atMostEvery(gapMs: Long): Flow<T> = channelFlow {
        val pending = Channel<T>(Channel.CONFLATED)
        launch { collect { pending.send(it) }; pending.close() }
        for (value in pending) { send(value); delay(gapMs) }
    }

    /**
     * A catalogue pass changes the car's lists only while they come from the phone ([onPhone]), and only when it changed
     * something: entries taken, fetched, removed or refreshed, the kept sets changed, or [listsChanged]: the
     * [phoneLists] differ from those after the last pass (an album turned partial or complete, a new minimal album).
     */
    fun catalogueReloads(onPhone: Boolean, pass: CatalogueCollector.Result?, keptChanged: Boolean, listsChanged: Boolean = false): Boolean =
        onPhone && (keptChanged || listsChanged || pass != null && pass.fromPages + pass.fetched + pass.removedAlbums + pass.refreshed > 0)

    /**
     * What of the car's phone lists the on-phone track set alone can change: the albums with a track here,
     * each with whether it is complete ("Only complete albums" keeps those). A track more or less of an album that stays
     * partial changes nothing here, so it reloads nothing. The same for the playlists (`playlist/<id>`): those
     * shown, each with whether "Only complete albums" shows it too ([OfflineCatalogue.playlistPlaces]).
     */
    suspend fun phoneLists(catalogue: OfflineCatalogue, onPhone: Set<String>): Map<String, Boolean> =
        catalogue.shelf(onPhone).counts.mapValues { it.value.complete } +
            catalogue.playlistPlaces(onPhone).mapKeys { "playlist/${it.key}" }
}
