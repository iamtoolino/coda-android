package io.github.iamtoolino.coda.player

import android.app.PendingIntent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import io.github.iamtoolino.coda.AppGraph
import io.github.iamtoolino.coda.CodaApplication
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlaybackService : MediaLibraryService(), Player.Listener {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var libraryCallback: CodaMediaLibraryCallback
    private lateinit var snapshotStore: PlaybackSnapshotStore
    private lateinit var cache: SimpleCache
    private lateinit var cacheFactory: CacheDataSource.Factory
    private lateinit var scrobbler: ScrobbleCoordinator
    private lateinit var queueSync: SharedQueueSyncCoordinator
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scrobbleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var prefetchJob: Job? = null
    private var cleanupJob: Job? = null
    private var snapshotJob: Job? = null
    private lateinit var snapshotWriteWorker: Job
    private val snapshotWrites = Channel<PlaybackSnapshot?>(Channel.CONFLATED)
    private var snapshotTemplate: PlaybackSnapshot? = null
    private var variantRefreshJob: Job? = null
    private var restorationJob: Job? = null
    private var variantRefreshGeneration = 0L
    @Volatile
    private var audioCacheGeneration = 0L
    @Volatile
    private var activeCacheWriter: CacheWriter? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshUpcomingStreamVariants()
        override fun onLost(network: Network) = refreshUpcomingStreamVariants()
        override fun onCapabilitiesChanged(network: Network, capabilities: android.net.NetworkCapabilities) =
            refreshUpcomingStreamVariants()
    }

    override fun onCreate() {
        super.onCreate()
        val databaseProvider = StandaloneDatabaseProvider(this)
        val transientCacheDirectory = File(cacheDir, "transient-audio")
        cache = SimpleCache(transientCacheDirectory, NoOpCacheEvictor(), databaseProvider)
        val upstreamFactory = DefaultDataSource.Factory(
            this,
            DefaultHttpDataSource.Factory()
                .setUserAgent("Coda/0.1")
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(30_000)
                .setAllowCrossProtocolRedirects(true),
        )
        cacheFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        snapshotStore = PlaybackSnapshotStore(this)
        snapshotWriteWorker = scope.launch {
            for (snapshot in snapshotWrites) {
                if (snapshot == null) snapshotStore.clear() else snapshotStore.save(snapshot)
            }
        }
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        scrobbler = ScrobbleCoordinator(
            scrobbleScope,
            onCompleted = {
                if (player.timelineBelongsToCurrentAccount()) AppGraph.albumResume?.completed(it)
            },
        ) { songId, submission, eventTime ->
            val account = AppGraph.sessionSnapshot() ?: return@ScrobbleCoordinator null
            suspend {
                if (!AppGraph.isCurrent(account)) throw CancellationException("Account changed")
                account.client.scrobble(
                    songId = songId,
                    submission = submission,
                    time = eventTime,
                )
            }
        }
        queueSync = SharedQueueSyncCoordinator(
            scope = scrobbleScope,
            snapshot = player::sharedQueueSnapshot,
            sessionSnapshot = AppGraph::sessionSnapshot,
            isCurrentSession = AppGraph::isCurrent,
            save = { account, queue ->
                account.client.savePlayQueue(
                    songIds = queue.songIds,
                    currentIndex = queue.currentIndex,
                    positionMs = queue.positionMs,
                )
            },
        )
        player.setWakeMode(C.WAKE_MODE_NETWORK)
        player.addListener(this)
        restoreLocalSnapshot()
        libraryCallback = CodaMediaLibraryCallback(this, scope)
        val sessionBuilder = MediaLibrarySession.Builder(
            this,
            player,
            libraryCallback,
        ).setPeriodicPositionUpdateEnabled(false)
        packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            sessionBuilder.setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        session = sessionBuilder.build()
        activeInstance = this
        (application as CodaApplication).transientAudioCleanup.resumePending()
        connectivity.registerDefaultNetworkCallback(networkCallback)
        restoreSavedQueue()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    private fun restoreSavedQueue() {
        restorationJob?.cancel()
        restorationJob = scope.launch {
            val restoration = runCatching { libraryCallback.loadPlaybackRestoration() }
                .getOrNull() ?: return@launch
            withContext(Dispatchers.Main.immediate) {
                val currentGeneration = AppGraph.sessionSnapshot()?.generation
                if (!shouldApplyPlaybackRestoration(
                        restorationGeneration = restoration.session.generation,
                        currentGeneration = currentGeneration,
                        playerItemCount = player.mediaItemCount,
                        playWhenReady = player.playWhenReady,
                    ) || !AppGraph.isCurrent(restoration.session)
                ) {
                    return@withContext
                }
                player.setMediaItems(
                    restoration.mediaItems,
                    restoration.startIndex,
                    restoration.startPositionMs,
                )
                player.prepare()
            }
        }
    }

    private fun restoreLocalSnapshot() {
        val account = AppGraph.sessionSnapshot() ?: return
        val snapshot = snapshotStore.load(account.cacheNamespace) ?: return
        snapshotTemplate = snapshot
        val mobile = isMobileNetwork(this)
        player.setMediaItems(
            snapshot.songs.map { it.toPlayableMediaItem(this, mobile, account) },
            snapshot.currentIndex.coerceIn(snapshot.songs.indices),
            snapshot.positionMs.coerceAtLeast(0),
        )
        player.prepare()
    }

    private fun persistLocalSnapshot(rebuildQueue: Boolean = false) {
        val account = AppGraph.sessionSnapshot()
        if (account == null || player.mediaItemCount == 0 ||
            player.currentMediaItemIndex !in 0 until player.mediaItemCount
        ) {
            snapshotTemplate = null
            snapshotWrites.trySend(null)
            return
        }
        if (rebuildQueue || snapshotTemplate?.cacheNamespace != account.cacheNamespace) {
            snapshotTemplate = player.playbackSnapshot(account)
        }
        val template = snapshotTemplate ?: return
        snapshotWrites.trySend(
            template.copy(
                currentIndex = player.currentMediaItemIndex,
                positionMs = player.currentPosition.coerceAtLeast(0),
            ),
        )
    }

    private fun updateSnapshotTimer() {
        snapshotJob?.cancel()
        snapshotJob = null
        if (!player.isPlaying) return
        snapshotJob = scope.launch {
            while (true) {
                delay(15_000)
                withContext(Dispatchers.Main.immediate) { persistLocalSnapshot() }
            }
        }
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (!player.timelineBelongsToCurrentAccount()) {
            queueSync.invalidate()
            scrobbler.invalidate()
            snapshotTemplate = null
            snapshotWrites.trySend(null)
            player.stop()
            player.clearMediaItems()
            return
        }
        queueSync.onPlayerEvents(
            hasPlaybackIntent = player.hasLocalPlaybackIntent(),
            isPlaying = player.isPlaying,
            mediaItemTransition = events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION),
        )
        if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
            events.contains(Player.EVENT_TIMELINE_CHANGED) ||
            events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)
        ) {
            maintainFourTrackWindow()
        }
        if (events.contains(Player.EVENT_TIMELINE_CHANGED)) refreshUpcomingStreamVariants()
        if (events.contains(Player.EVENT_TIMELINE_CHANGED) ||
            events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
            events.contains(Player.EVENT_POSITION_DISCONTINUITY) ||
            events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) ||
            events.contains(Player.EVENT_IS_PLAYING_CHANGED)
        ) {
            persistLocalSnapshot(rebuildQueue = events.contains(Player.EVENT_TIMELINE_CHANGED))
        }
        if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) updateSnapshotTimer()
    }

    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
        scrobbler.transition(
            songId = mediaItem?.mediaId,
            transition = when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> PlaybackTransition.AUTOMATIC
                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> PlaybackTransition.REPEAT
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED ->
                    PlaybackTransition.PLAYLIST_CHANGED
                else -> PlaybackTransition.MANUAL
            },
        )
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) scrobbler.playbackEnded()
    }

    private fun maintainFourTrackWindow() {
        if (!::player.isInitialized) return
        val window = audioCacheWindowIndices(
            itemCount = player.mediaItemCount,
            currentIndex = player.currentMediaItemIndex,
        ).mapNotNull { index ->
            player.getMediaItemAt(index).localConfiguration
        }
        val keepKeys = window.mapNotNull { it.customCacheKey }.toSet()
        val targets = window.map { local -> local.uri to local.customCacheKey }

        val generation = ++audioCacheGeneration
        activeCacheWriter?.cancel()
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            for (cacheKey in cache.keys.toList()) {
                currentCoroutineContext().ensureActive()
                if (generation != audioCacheGeneration) return@launch
                if (cacheKey !in keepKeys) runCatching { cache.removeResource(cacheKey) }
            }
            for ((uri, cacheKey) in targets) {
                currentCoroutineContext().ensureActive()
                if (generation != audioCacheGeneration) return@launch
                val dataSpec = DataSpec.Builder()
                    .setUri(uri)
                    .setKey(cacheKey)
                    .build()
                val writer = CacheWriter(cacheFactory.createDataSource(), dataSpec, null, null)
                activeCacheWriter = writer
                try {
                    currentCoroutineContext().ensureActive()
                    runCatching { writer.cache() }
                } finally {
                    if (activeCacheWriter === writer) activeCacheWriter = null
                }
            }
        }
    }

    private fun refreshUpcomingStreamVariants() {
        mainHandler.post {
            val account = AppGraph.sessionSnapshot() ?: return@post
            val mobile = isMobileNetwork(this)
            if (!::player.isInitialized || player.mediaItemCount == 0) return@post
            val start = (player.currentMediaItemIndex + 1).coerceAtLeast(0)
            val variant = if (mobile) "opus" else "raw"
            val originals = (start until player.mediaItemCount).map(player::getMediaItemAt)
            val generation = ++variantRefreshGeneration
            variantRefreshJob?.cancel()
            variantRefreshJob = scope.launch {
                var changed = false
                val replacements = originals.map { item ->
                    val namespace = item.mediaMetadata.extras?.getString("cacheNamespace")
                    val currentKey = item.localConfiguration?.customCacheKey
                    val shouldReplace = namespace == account.cacheNamespace &&
                        currentKey != streamCacheKey(namespace, item.mediaId, variant) &&
                        !(mobile && currentKey == streamCacheKey(namespace, item.mediaId, "raw") &&
                            isFullyCached(currentKey))
                    if (!shouldReplace) return@map item
                    changed = true
                    val extras = item.mediaMetadata.extras?.let(::Bundle) ?: Bundle()
                    if (mobile) {
                        extras.putString("codec", "opus")
                        extras.remove("bitDepth")
                        extras.remove("samplingRate")
                        extras.remove("bitRate")
                    } else {
                        extras.putString("codec", extras.getString("sourceCodec"))
                        copyInt(extras, "sourceBitDepth", "bitDepth")
                        copyInt(extras, "sourceSamplingRate", "samplingRate")
                        copyInt(extras, "sourceBitRate", "bitRate")
                    }
                    item.buildUpon()
                        .setUri(account.client.streamUrl(item.mediaId, mobile))
                        .setCustomCacheKey(streamCacheKey(requireNotNull(namespace), item.mediaId, variant))
                        .setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build())
                        .build()
                }
                if (!changed) return@launch
                withContext(Dispatchers.Main.immediate) {
                    if (generation != variantRefreshGeneration || !AppGraph.isCurrent(account) ||
                        player.mediaItemCount < start + originals.size ||
                        originals.indices.any { player.getMediaItemAt(start + it).mediaId != originals[it].mediaId }
                    ) {
                        return@withContext
                    }
                    player.replaceMediaItems(start, start + originals.size, replacements)
                }
            }
        }
    }

    private fun isFullyCached(cacheKey: String): Boolean {
        val contentLength = ContentMetadata.getContentLength(cache.getContentMetadata(cacheKey))
        return contentLength != C.LENGTH_UNSET.toLong() && contentLength > 0 &&
            cache.isCached(cacheKey, 0, contentLength)
    }

    private fun copyInt(extras: Bundle, source: String, target: String) {
        if (extras.containsKey(source)) extras.putInt(target, extras.getInt(source)) else extras.remove(target)
    }

    private fun clearTransientAudioCaches(namespaces: Set<String>) {
        val requested = namespaces.filter(String::isNotBlank).toSet()
        if (requested.isEmpty() || !::cache.isInitialized) return
        ++audioCacheGeneration
        activeCacheWriter?.cancel()
        prefetchJob?.cancel()
        cleanupJob?.cancel()
        cleanupJob = scope.launch {
            for (namespace in requested) {
                var succeeded = true
                val prefix = "stream:$namespace:"
                for (cacheKey in cache.keys.filter { it.startsWith(prefix) }) {
                    currentCoroutineContext().ensureActive()
                    if (runCatching { cache.removeResource(cacheKey) }.isFailure) succeeded = false
                }
                if (succeeded) {
                    (application as CodaApplication).transientAudioCleanup.complete(namespace)
                }
            }
        }
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        activeCacheWriter?.cancel()
        prefetchJob?.cancel()
        cleanupJob?.cancel()
        snapshotJob?.cancel()
        variantRefreshJob?.cancel()
        restorationJob?.cancel()
        snapshotWrites.close()
        if (::snapshotWriteWorker.isInitialized) snapshotWriteWorker.cancel()
        queueSync.close()
        scrobbler.close()
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        session.release()
        player.release()
        cache.release()
        scope.cancel()
        scrobbleScope.cancel()
        super.onDestroy()
    }

    internal companion object {
        @Volatile
        var activeInstance: PlaybackService? = null

        fun clearTransientAudioCachesFor(namespaces: Set<String>) {
            activeInstance?.clearTransientAudioCaches(namespaces)
        }

        fun invalidateAccountState() {
            activeInstance?.run {
                restorationJob?.cancel()
                libraryCallback.invalidatePlaybackRestoration()
                queueSync.invalidate()
                scrobbler.invalidate()
            }
        }
    }
}

internal fun audioCacheWindowIndices(
    itemCount: Int,
    currentIndex: Int,
    maximumSize: Int = 4,
): List<Int> {
    if (itemCount <= 0 || maximumSize <= 0) return emptyList()
    val first = currentIndex.coerceIn(0, itemCount - 1)
    val lastExclusive = (first + maximumSize).coerceAtMost(itemCount)
    return (first until lastExclusive).toList()
}

internal fun generationsBelongToCurrentAccount(
    itemGenerations: List<Long?>,
    currentGeneration: Long?,
): Boolean = itemGenerations.isEmpty() ||
    currentGeneration != null && itemGenerations.all { it == currentGeneration }

private fun Player.timelineBelongsToCurrentAccount(): Boolean {
    val generations = (0 until mediaItemCount).map { index ->
        getMediaItemAt(index).mediaMetadata.extras?.let { extras ->
            extras.getLong("accountGeneration").takeIf { extras.containsKey("accountGeneration") }
        }
    }
    return generationsBelongToCurrentAccount(
        itemGenerations = generations,
        currentGeneration = AppGraph.sessionSnapshot()?.generation,
    )
}
