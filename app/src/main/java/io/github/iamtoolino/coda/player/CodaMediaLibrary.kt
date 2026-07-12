package io.github.iamtoolino.coda.player

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import io.github.iamtoolino.coda.AppGraph
import io.github.iamtoolino.coda.NavidromeSession
import io.github.iamtoolino.coda.R
import io.github.iamtoolino.coda.data.Album
import io.github.iamtoolino.coda.data.AlbumListType
import io.github.iamtoolino.coda.data.Artist
import io.github.iamtoolino.coda.data.Playlist
import io.github.iamtoolino.coda.data.Song
import java.util.LinkedHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal object CodaMediaIds {
    const val ROOT = "coda:root"
    const val RECENTLY_ADDED = "coda:recently-added"
    const val RECENTLY_PLAYED = "coda:recently-played"
    const val ARTISTS = "coda:artists"
    const val PLAYLISTS = "coda:playlists"

    fun album(id: String) = "coda:album:$id"
    fun artist(id: String) = "coda:artist:$id"
    fun playlist(id: String) = "coda:playlist:$id"
    fun song(id: String) = "coda:song:$id"
    fun artistBucket(label: String) = "coda:artist-bucket:$label"

    fun albumId(mediaId: String) = mediaId.removePrefixOrNull("coda:album:")
    fun artistId(mediaId: String) = mediaId.removePrefixOrNull("coda:artist:")
    fun playlistId(mediaId: String) = mediaId.removePrefixOrNull("coda:playlist:")
    fun songId(mediaId: String) = mediaId.removePrefixOrNull("coda:song:")
    fun artistBucketLabel(mediaId: String) = mediaId.removePrefixOrNull("coda:artist-bucket:")

    private fun String.removePrefixOrNull(prefix: String): String? =
        takeIf { startsWith(prefix) }?.removePrefix(prefix)?.takeIf(String::isNotBlank)
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class CodaMediaLibraryCallback(
    private val context: Context,
    private val scope: CoroutineScope,
) : MediaLibrarySession.Callback {
    private val searchCache = BoundedSearchCache(MAX_CACHED_SEARCHES)

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
        LibraryResult.ofItem(rootItem(), params),
    )

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = itemListFuture(params) { account ->
        children(account, parentId)
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = itemFuture(null) { account ->
        item(account, mediaId) ?: error("Unknown media item: $mediaId")
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> {
        if (query.isBlank()) {
            return Futures.immediateFuture(
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE, params),
            )
        }
        val account = AppGraph.sessionSnapshot() ?: return Futures.immediateFuture(
            LibraryResult.ofError(SessionError.ERROR_IO, params),
        )
        val cacheKey = SearchCacheKey(account.cacheNamespace, query.normalized())
        scope.launch {
            val results = runCatching {
                searchItems(account, query).also { account.requireCurrent() }
            }.getOrElse { emptyList() }
            if (AppGraph.isCurrent(account)) {
                searchCache[cacheKey] = results
                session.notifySearchResultChanged(browser, query, results.size, params)
            }
        }
        return Futures.immediateFuture(LibraryResult.ofVoid(params))
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = itemListFuture(params) { account ->
        val cacheKey = SearchCacheKey(account.cacheNamespace, query.normalized())
        val all = searchCache[cacheKey] ?: searchItems(account, query).also {
            account.requireCurrent()
            searchCache[cacheKey] = it
        }
        all
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> {
        val account = AppGraph.sessionSnapshot()
            ?: return Futures.immediateFuture(emptyList())
        return asyncFuture {
            mediaItems.flatMap { requested -> resolvePlayableItems(account, requested) }.also {
                account.requireCurrent()
            }
        }
    }

    private suspend fun children(account: NavidromeSession, parentId: String): List<MediaItem> =
        when (parentId) {
            CodaMediaIds.ROOT -> rootChildren()
            CodaMediaIds.RECENTLY_ADDED -> account.client
                .albums(AlbumListType.NEWEST, CAR_ALBUM_LIMIT, 0)
                .map { albumItem(account, it) }
            CodaMediaIds.RECENTLY_PLAYED -> account.client
                .recentlyPlayedAlbums(CAR_ALBUM_LIMIT)
                .map { albumItem(account, it) }
            CodaMediaIds.ARTISTS -> artistBuckets(account.client.artists())
            CodaMediaIds.PLAYLISTS -> account.client.playlists().map { playlistItem(account, it) }
            else -> when {
                CodaMediaIds.artistBucketLabel(parentId) != null -> {
                    val bucket = requireNotNull(CodaMediaIds.artistBucketLabel(parentId))
                    account.client.artists()
                        .filter { artistBucket(it.name) == bucket }
                        .map { artistItem(account, it) }
                }
                CodaMediaIds.albumId(parentId) != null -> account.client
                    .album(requireNotNull(CodaMediaIds.albumId(parentId)))
                    .songs.map { songItem(account, it) }
                CodaMediaIds.artistId(parentId) != null -> account.client
                    .artistAlbums(requireNotNull(CodaMediaIds.artistId(parentId)))
                    .second.map { albumItem(account, it) }
                CodaMediaIds.playlistId(parentId) != null -> account.client
                    .playlist(requireNotNull(CodaMediaIds.playlistId(parentId)))
                    .entry.map { songItem(account, it) }
                else -> emptyList()
            }
        }

    private suspend fun item(account: NavidromeSession, mediaId: String): MediaItem? = when (mediaId) {
        CodaMediaIds.ROOT -> rootItem()
        CodaMediaIds.RECENTLY_ADDED -> categoryItem(
            CodaMediaIds.RECENTLY_ADDED,
            "Recently added",
            MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
            MediaMetadata.FOLDER_TYPE_ALBUMS,
            R.drawable.ic_car_added,
        )
        CodaMediaIds.RECENTLY_PLAYED -> categoryItem(
            CodaMediaIds.RECENTLY_PLAYED,
            "Recent",
            MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
            MediaMetadata.FOLDER_TYPE_ALBUMS,
            R.drawable.ic_car_recent,
        )
        CodaMediaIds.ARTISTS -> categoryItem(
            CodaMediaIds.ARTISTS,
            "Artists",
            MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
            MediaMetadata.FOLDER_TYPE_ARTISTS,
            R.drawable.ic_car_artists,
        )
        CodaMediaIds.PLAYLISTS -> categoryItem(
            CodaMediaIds.PLAYLISTS,
            "Playlists",
            MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
            MediaMetadata.FOLDER_TYPE_PLAYLISTS,
            R.drawable.ic_car_playlists,
        )
        else -> when {
            CodaMediaIds.artistBucketLabel(mediaId) != null -> artistBucketItem(
                requireNotNull(CodaMediaIds.artistBucketLabel(mediaId)),
            )
            CodaMediaIds.albumId(mediaId) != null -> account.client
                .album(requireNotNull(CodaMediaIds.albumId(mediaId))).album.let { albumItem(account, it) }
            CodaMediaIds.artistId(mediaId) != null -> account.client
                .artistAlbums(requireNotNull(CodaMediaIds.artistId(mediaId))).first.let {
                    artistItem(account, it)
                }
            CodaMediaIds.playlistId(mediaId) != null -> account.client
                .playlist(requireNotNull(CodaMediaIds.playlistId(mediaId))).let {
                    playlistItem(account, it)
                }
            CodaMediaIds.songId(mediaId) != null -> account.client
                .song(requireNotNull(CodaMediaIds.songId(mediaId))).let { songItem(account, it) }
            else -> null
        }
    }

    private suspend fun resolvePlayableItems(
        account: NavidromeSession,
        requested: MediaItem,
    ): List<MediaItem> {
        if (requested.localConfiguration?.uri != null) {
            val itemNamespace = requested.mediaMetadata.extras?.getString("cacheNamespace")
            return listOf(requested).takeIf {
                itemNamespace == null || itemNamespace == account.cacheNamespace
            }.orEmpty()
        }
        val mobile = isMobileNetwork(context)
        CodaMediaIds.songId(requested.mediaId)?.let { id ->
            return listOf(account.client.song(id).toPlayableMediaItem(context, mobile, account))
        }
        CodaMediaIds.albumId(requested.mediaId)?.let { id ->
            return account.client.album(id).songs.map { it.toPlayableMediaItem(context, mobile, account) }
        }
        CodaMediaIds.playlistId(requested.mediaId)?.let { id ->
            return account.client.playlist(id).entry.map {
                it.toPlayableMediaItem(context, mobile, account)
            }
        }
        return emptyList()
    }

    private suspend fun searchItems(account: NavidromeSession, query: String): List<MediaItem> {
        if (query.isBlank()) return emptyList()
        val result = account.client.search(query)
        return buildList {
            addAll(
                result.artist
                    .sortedBySearchRelevance(query) { it.name }
                    .take(20)
                    .map { artistItem(account, it).withSearchGroup("Artists") },
            )
            addAll(
                result.album
                    .sortedBySearchRelevance(query) { it.name }
                    .take(20)
                    .map { albumItem(account, it).withSearchGroup("Albums") },
            )
            addAll(
                result.song
                    .sortedBySearchRelevance(query) { it.title }
                    .take(30)
                    .map { songItem(account, it).withSearchGroup("Songs") },
            )
        }
    }

    private fun MediaItem.withSearchGroup(title: String): MediaItem {
        val extras = mediaMetadata.extras?.let(::Bundle) ?: Bundle()
        extras.putString(CONTENT_STYLE_GROUP_TITLE_HINT, title)
        return buildUpon()
            .setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build())
            .build()
    }

    private fun <T> List<T>.sortedBySearchRelevance(
        query: String,
        text: (T) -> String,
    ): List<T> = sortedWith(
        compareBy<T> { searchRank(text(it), query) }
            .thenBy { text(it).lowercase() },
    )

    private fun searchRank(text: String, query: String): Int {
        val normalizedText = text.normalized()
        val normalizedQuery = query.normalized()
        return when {
            normalizedText == normalizedQuery -> 0
            normalizedText.startsWith(normalizedQuery) -> 1
            normalizedText.split(SEARCH_WORD_SEPARATOR).any { it.startsWith(normalizedQuery) } -> 2
            normalizedText.contains(normalizedQuery) -> 3
            else -> 4
        }
    }

    private fun rootItem(): MediaItem = MediaItem.Builder()
        .setMediaId(CodaMediaIds.ROOT)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("Coda")
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                .build(),
        )
        .build()

    private fun rootChildren(): List<MediaItem> = listOf(
        categoryItem(
            CodaMediaIds.ARTISTS,
            "Artists",
            MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
            MediaMetadata.FOLDER_TYPE_ARTISTS,
            R.drawable.ic_car_artists,
        ),
        categoryItem(
            CodaMediaIds.RECENTLY_ADDED,
            "Added",
            MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
            MediaMetadata.FOLDER_TYPE_ALBUMS,
            R.drawable.ic_car_added,
        ),
        categoryItem(
            CodaMediaIds.RECENTLY_PLAYED,
            "Recent",
            MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
            MediaMetadata.FOLDER_TYPE_ALBUMS,
            R.drawable.ic_car_recent,
        ),
        categoryItem(
            CodaMediaIds.PLAYLISTS,
            "Playlists",
            MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
            MediaMetadata.FOLDER_TYPE_PLAYLISTS,
            R.drawable.ic_car_playlists,
        ),
    )

    private fun categoryItem(
        id: String,
        title: String,
        mediaType: Int,
        folderType: Int,
        @DrawableRes icon: Int? = null,
    ): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtworkUri(icon?.let(::resourceUri))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(mediaType)
                    .setFolderType(folderType)
                    .build(),
            )
            .build()

    private fun artistBuckets(artists: List<Artist>): List<MediaItem> = artists
        .map { artistBucket(it.name) }
        .distinct()
        .sortedWith(compareBy<String> { it != "#" }.thenBy { it })
        .map(::artistBucketItem)

    private fun artistBucketItem(label: String): MediaItem = categoryItem(
        CodaMediaIds.artistBucket(label),
        label,
        MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
        MediaMetadata.FOLDER_TYPE_ARTISTS,
    )

    private fun artistBucket(name: String): String = name.trim()
        .firstOrNull()
        ?.uppercaseChar()
        ?.takeIf { it in 'A'..'Z' }
        ?.toString()
        ?: "#"

    private fun resourceUri(@DrawableRes resourceId: Int): Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.packageName)
        .appendPath(resourceId.toString())
        .build()

    private fun albumItem(account: NavidromeSession, album: Album): MediaItem = MediaItem.Builder()
        .setMediaId(CodaMediaIds.album(album.id))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(album.name)
                .setArtist(album.artist)
                .setSubtitle(buildSubtitle(album.artist, trackCount(album.songCount)))
                .setArtworkUri(
                    CarArtwork.cover(
                        context,
                        album.coverArt ?: album.id,
                        800,
                        account.cacheNamespace,
                    ),
                )
                .setIsBrowsable(true)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM)
                .setTotalTrackCount(album.songCount.takeIf { it > 0 })
                .setReleaseYear(album.year)
                .build(),
        )
        .build()

    private fun artistItem(account: NavidromeSession, artist: Artist): MediaItem = MediaItem.Builder()
        .setMediaId(CodaMediaIds.artist(artist.id))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(artist.name)
                .setSubtitle(albumCount(artist.albumCount))
                .setArtworkUri(
                    CarArtwork.external(
                        context,
                        artist.artistImageUrl,
                        "artist:${artist.id}",
                        account.cacheNamespace,
                    ) ?: CarArtwork.cover(
                        context,
                        artist.coverArt,
                        800,
                        account.cacheNamespace,
                    ),
                )
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST)
                .build(),
        )
        .build()

    private fun playlistItem(account: NavidromeSession, playlist: Playlist): MediaItem = MediaItem.Builder()
        .setMediaId(CodaMediaIds.playlist(playlist.id))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(playlist.name)
                .setSubtitle(trackCount(playlist.songCount))
                .setArtworkUri(
                    CarArtwork.cover(context, playlist.coverArt, 800, account.cacheNamespace),
                )
                .setIsBrowsable(true)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                .build(),
        )
        .build()

    private fun songItem(account: NavidromeSession, song: Song): MediaItem = MediaItem.Builder()
        .setMediaId(CodaMediaIds.song(song.id))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(song.title)
                .setArtist(song.artist)
                .setAlbumTitle(song.album)
                .setSubtitle(buildSubtitle(song.artist, song.album))
                .setArtworkUri(
                    CarArtwork.cover(
                        context,
                        song.albumId ?: song.coverArt,
                        800,
                        account.cacheNamespace,
                    ),
                )
                .setDurationMs(song.duration.coerceAtLeast(0) * 1_000L)
                .setTrackNumber(song.track)
                .setDiscNumber(song.discNumber)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build(),
        )
        .build()

    private fun itemFuture(
        params: MediaLibraryService.LibraryParams?,
        loader: suspend (NavidromeSession) -> MediaItem,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val account = AppGraph.sessionSnapshot() ?: return Futures.immediateFuture(
            LibraryResult.ofError(SessionError.ERROR_IO, params),
        )
        return asyncFuture {
            try {
                val item = loader(account)
                account.requireCurrent()
                LibraryResult.ofItem(item, params)
            } catch (_: Throwable) {
                LibraryResult.ofError(SessionError.ERROR_IO, params)
            }
        }
    }

    private fun itemListFuture(
        params: MediaLibraryService.LibraryParams?,
        loader: suspend (NavidromeSession) -> List<MediaItem>,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val account = AppGraph.sessionSnapshot() ?: return Futures.immediateFuture(
            LibraryResult.ofError(SessionError.ERROR_IO, params),
        )
        return asyncFuture {
            try {
                val items = loader(account)
                account.requireCurrent()
                LibraryResult.ofItemList(items, params)
            } catch (_: Throwable) {
                LibraryResult.ofError(SessionError.ERROR_IO, params)
            }
        }
    }

    private fun NavidromeSession.requireCurrent() {
        check(AppGraph.isCurrent(this)) { "Navidrome account changed" }
    }

    private fun <T> asyncFuture(loader: suspend () -> T): ListenableFuture<T> =
        SettableFuture.create<T>().also { future ->
            scope.launch {
                runCatching { loader() }
                    .onSuccess(future::set)
                    .onFailure(future::setException)
            }
        }

    private fun String.normalized() = trim().lowercase()

    private fun trackCount(count: Int) = if (count == 1) "1 track" else "$count tracks"

    private fun albumCount(count: Int) = if (count == 1) "1 album" else "$count albums"

    private fun buildSubtitle(first: String, second: String): String =
        listOf(first, second).filter(String::isNotBlank).joinToString(" · ")

    private companion object {
        const val CAR_ALBUM_LIMIT = 40
        const val MAX_CACHED_SEARCHES = 32
        const val CONTENT_STYLE_GROUP_TITLE_HINT =
            "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT"
        val SEARCH_WORD_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")
    }

    private data class SearchCacheKey(val namespace: String, val query: String)

    private class BoundedSearchCache(private val maximumSize: Int) {
        private val entries = object : LinkedHashMap<SearchCacheKey, List<MediaItem>>(
            maximumSize + 1,
            0.75f,
            true,
        ) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<SearchCacheKey, List<MediaItem>>?,
            ): Boolean = size > maximumSize
        }

        @Synchronized
        operator fun get(key: SearchCacheKey): List<MediaItem>? = entries[key]

        @Synchronized
        operator fun set(key: SearchCacheKey, value: List<MediaItem>) {
            entries[key] = value
        }
    }
}
