package io.github.iamtoolino.coda.player

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.iamtoolino.coda.AppGraph
import io.github.iamtoolino.coda.CodaApplication
import io.github.iamtoolino.coda.NavidromeSession
import io.github.iamtoolino.coda.data.NavidromeClient
import io.github.iamtoolino.coda.data.PlayQueue
import io.github.iamtoolino.coda.data.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class HandoffDisposition {
    NONE,
    RESTORE_PAUSED,
    OFFER_EXTERNAL,
}

internal fun isAndroidQueue(changedBy: String?, ownClientName: String): Boolean =
    normalizeClientName(changedBy) == normalizeClientName(ownClientName) ||
        normalizeClientName(changedBy) == normalizeClientName(NavidromeClient.CLIENT_NAME) ||
        normalizeClientName(changedBy) == normalizeClientName("Coda")

internal data class SavedQueueStart(
    val index: Int,
    val positionMs: Long,
)

internal fun savedQueueStart(
    queue: PlayQueue?,
    ownClientName: String,
): SavedQueueStart? {
    if (queue == null || queue.entry.isEmpty() || !isAndroidQueue(queue.changedBy, ownClientName)) {
        return null
    }
    val index = queue.currentIndex
        ?: queue.entry.indexOfFirst { it.id == queue.current }.takeIf { it >= 0 }
        ?: 0
    return SavedQueueStart(
        index = index.coerceIn(queue.entry.indices),
        positionMs = queue.position?.coerceAtLeast(0) ?: 0,
    )
}

private fun normalizeClientName(value: String?): String? = value
    ?.trim()
    ?.replace(Regex("\\s+"), " ")
    ?.lowercase()

internal fun handoffDisposition(
    queue: PlayQueue?,
    coldStart: Boolean,
    localQueueEmpty: Boolean,
    ownClientName: String,
): HandoffDisposition = when {
    queue == null || queue.entry.isEmpty() -> HandoffDisposition.NONE
    isAndroidQueue(queue.changedBy, ownClientName) && coldStart && localQueueEmpty ->
        HandoffDisposition.RESTORE_PAUSED
    isAndroidQueue(queue.changedBy, ownClientName) -> HandoffDisposition.NONE
    else -> HandoffDisposition.OFFER_EXTERNAL
}

data class QueueEntry(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: String?,
    val artworkKey: String,
    val trackNumber: Int? = null,
    val durationMs: Long = 0,
)

data class PlaybackUiState(
    val connected: Boolean = false,
    val currentSongId: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumId: String? = null,
    val artistId: String? = null,
    val artworkUrl: String? = null,
    val artworkKey: String? = null,
    val discNumber: Int? = null,
    val codec: String? = null,
    val bitDepth: Int? = null,
    val samplingRate: Int? = null,
    val bitRate: Int? = null,
    val sourceCodec: String? = null,
    val sourceBitDepth: Int? = null,
    val sourceSamplingRate: Int? = null,
    val sourceBitRate: Int? = null,
    val error: String? = null,
    val currentIndex: Int = -1,
    val queue: List<QueueEntry> = emptyList(),
)

data class PlaybackProgressState(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isSeekable: Boolean = false,
)

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlaybackConnection(private val context: Context) : Player.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessionToken = SessionToken(
        context,
        ComponentName(context, PlaybackService::class.java),
    )
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var reconnectJob: Job? = null
    private var released = false
    private var progressJob: Job? = null
    private var handoffRefreshJob: Job? = null
    private var pendingPlayback: ((MediaController) -> Unit)? = null
    private var playbackError: String? = null
    private var queueSnapshot: List<QueueEntry> = emptyList()
    private var pendingHandoffRefresh = false
    private var pendingExternalHandoff: (() -> Unit)? = null
    private var coldStartRestorePending = true
    private var handoffSessionGeneration: Long? = null
    private var queueMutationGeneration = 0L
    private val queueMutationRequests = Channel<PreparedQueueMutation>(Channel.UNLIMITED)
    private val queueMutationWorker = launchQueueMutationWorker()

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()
    private val _progress = MutableStateFlow(PlaybackProgressState())
    val progress: StateFlow<PlaybackProgressState> = _progress.asStateFlow()
    private val _handoffQueue = MutableStateFlow<PlayQueue?>(null)
    val handoffQueue: StateFlow<PlayQueue?> = _handoffQueue.asStateFlow()

    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(disconnectedController: MediaController) {
            if (controller !== disconnectedController || released) return
            disconnectedController.removeListener(this@PlaybackConnection)
            controller = null
            progressJob?.cancel()
            refreshState(rebuildQueue = true)
            scheduleConnection(attempt = 0)
        }
    }

    init {
        connect(attempt = 0)
    }

    private fun connect(attempt: Int) {
        if (released || controller != null) return
        val future = MediaController.Builder(context, sessionToken)
            .setListener(controllerListener)
            .buildAsync()
        controllerFuture = future
        future.addListener(
            {
                if (released || controllerFuture !== future) {
                    MediaController.releaseFuture(future)
                    return@addListener
                }
                val connectedController = runCatching { future.get() }.getOrNull()
                if (connectedController == null) {
                    controllerFuture = null
                    MediaController.releaseFuture(future)
                    scheduleConnection(attempt + 1)
                    return@addListener
                }
                controllerFuture = null
                reconnectJob?.cancel()
                controller = connectedController.also { it.addListener(this) }
                pendingPlayback?.invoke(connectedController)
                pendingPlayback = null
                refreshState(rebuildQueue = true)
                startProgressUpdates()
                if (pendingHandoffRefresh) {
                    val onExternalQueue = pendingExternalHandoff
                    pendingExternalHandoff = null
                    refreshHandoffQueue(onExternalQueue)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    private fun scheduleConnection(attempt: Int) {
        if (released || controller != null || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val retryDelay = (1_000L * (1 shl attempt.coerceAtMost(5))).coerceAtMost(30_000L)
            delay(retryDelay)
            connect(attempt)
        }
    }

    fun playSongs(items: List<Song>, startIndex: Int = 0) {
        if (items.isEmpty()) return
        runWhenConnected {
            enqueueQueueMutation(
                QueueMutation.Replace(
                    songs = items,
                    startIndex = startIndex.coerceIn(items.indices),
                    positionMs = 0,
                    startPlayback = true,
                ),
            )
        }
    }

    fun restore(queue: PlayQueue) {
        if (queue.entry.isEmpty()) return
        _handoffQueue.value = null
        coldStartRestorePending = false
        runWhenConnected {
            enqueueRestore(queue, startPlayback = true)
        }
    }

    fun appendSongs(items: List<Song>) {
        if (items.isEmpty()) return
        runWhenConnected {
            enqueueQueueMutation(QueueMutation.Append(items))
        }
    }

    fun togglePlayPause() {
        controller?.let {
            if (playbackError != null) {
                playbackError = null
                it.prepare()
                it.play()
            } else if (it.isPlaying) {
                it.pause()
            } else {
                it.play()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val player = controller ?: return
        if (!player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) ||
            !player.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM) ||
            !player.isCurrentMediaItemSeekable
        ) {
            return
        }
        player.seekTo(positionMs)
        refreshState()
    }

    fun next() = controller?.seekToNextMediaItem() ?: Unit

    fun previous() = controller?.seekToPreviousMediaItem() ?: Unit

    fun skipTo(index: Int) {
        val player = controller ?: return
        if (index in 0 until player.mediaItemCount) {
            player.seekToDefaultPosition(index)
            player.play()
        }
    }

    fun removeAt(index: Int) {
        val player = controller ?: return
        if (index in 0 until player.mediaItemCount) {
            player.removeMediaItem(index)
            refreshState(rebuildQueue = true)
        }
    }

    fun clearQueue() {
        controller?.clearMediaItems()
        refreshState(rebuildQueue = true)
    }

    fun disconnect(oldNamespace: String = AppGraph.cacheNamespace) {
        queueMutationGeneration++
        while (queueMutationRequests.tryReceive().isSuccess) Unit
        handoffRefreshJob?.cancel()
        pendingHandoffRefresh = false
        pendingExternalHandoff = null
        coldStartRestorePending = true
        handoffSessionGeneration = null
        _handoffQueue.value = null
        pendingPlayback = null
        controller?.run {
            stop()
            clearMediaItems()
        }
        PlaybackService.invalidateAccountState()
        (context.applicationContext as CodaApplication).requestTransientAudioCleanup(oldNamespace)
        refreshState(rebuildQueue = true)
    }

    fun refreshHandoffQueue(onExternalQueue: (() -> Unit)? = null) {
        val player = controller
        if (player == null) {
            pendingHandoffRefresh = true
            if (onExternalQueue != null) pendingExternalHandoff = onExternalQueue
            return
        }
        pendingHandoffRefresh = false
        if (player.hasLocalPlaybackIntent()) {
            _handoffQueue.value = null
            return
        }
        val session = AppGraph.sessionSnapshot() ?: return
        if (handoffSessionGeneration != session.generation) {
            handoffRefreshJob?.cancel()
            handoffSessionGeneration = session.generation
            coldStartRestorePending = true
            _handoffQueue.value = null
        }
        handoffRefreshJob?.cancel()
        handoffRefreshJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { session.client.playQueue() }
            }
            if (result.isFailure || !AppGraph.isCurrent(session)) return@launch
            val currentPlayer = controller ?: return@launch
            if (currentPlayer.hasLocalPlaybackIntent()) {
                _handoffQueue.value = null
                return@launch
            }
            val queue = result.getOrNull()
            val disposition = handoffDisposition(
                queue = queue,
                coldStart = coldStartRestorePending,
                localQueueEmpty = currentPlayer.mediaItemCount == 0,
                ownClientName = session.client.queueClientName,
            )
            coldStartRestorePending = false
            when (disposition) {
                HandoffDisposition.NONE -> _handoffQueue.value = null
                HandoffDisposition.RESTORE_PAUSED -> {
                    _handoffQueue.value = null
                    enqueueRestore(requireNotNull(queue), startPlayback = false)
                }
                HandoffDisposition.OFFER_EXTERNAL -> {
                    _handoffQueue.value = queue
                    onExternalQueue?.invoke()
                }
            }
        }
    }

    private fun runWhenConnected(action: (MediaController) -> Unit) {
        val player = controller
        if (player != null) action(player) else pendingPlayback = action
    }

    private fun enqueueRestore(queue: PlayQueue, startPlayback: Boolean) {
        val currentIndex = queue.currentIndex
            ?: queue.entry.indexOfFirst { it.id == queue.current }.takeIf { it >= 0 }
            ?: 0
        enqueueQueueMutation(
            QueueMutation.Replace(
                songs = queue.entry,
                startIndex = currentIndex.coerceIn(queue.entry.indices),
                positionMs = queue.position ?: 0,
                startPlayback = startPlayback,
            ),
        )
    }

    private fun enqueueQueueMutation(mutation: QueueMutation) {
        val session = AppGraph.sessionSnapshot() ?: return
        queueMutationRequests.trySend(mutation.withSession(queueMutationGeneration, session))
    }

    private fun launchQueueMutationWorker(): Job = scope.launch {
        for (request in queueMutationRequests) {
            val mobile = isMobileNetwork(context)
            val mediaItems = withContext(Dispatchers.Default) {
                request.songs.map { it.toPlayableMediaItem(context, mobile, request.session) }
            }
            if (request.generation != queueMutationGeneration || !AppGraph.isCurrent(request.session)) {
                continue
            }
            val player = controller ?: continue
            playbackError = null
            when (request) {
                is PreparedQueueMutation.Replace -> {
                    player.pause()
                    player.setMediaItems(mediaItems, request.startIndex, request.positionMs)
                    player.prepare()
                    if (request.startPlayback) player.play()
                }
                is PreparedQueueMutation.Append -> {
                    if (player.mediaItemCount == 0) {
                        player.setMediaItems(mediaItems)
                        player.prepare()
                    } else {
                        player.addMediaItems(mediaItems)
                    }
                }
            }
        }
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) playbackError = null
        val rebuildQueue = events.contains(Player.EVENT_TIMELINE_CHANGED) ||
            events.contains(Player.EVENT_MEDIA_METADATA_CHANGED)
        refreshState(rebuildQueue = rebuildQueue)
        if (player.hasLocalPlaybackIntent()) _handoffQueue.value = null
    }

    override fun onPlayerError(error: PlaybackException) {
        playbackError = error.message ?: "Playback failed"
        refreshState()
    }

    private fun refreshState(rebuildQueue: Boolean = false) {
        val player = controller ?: run {
            queueSnapshot = emptyList()
            _state.value = PlaybackUiState()
            _progress.value = PlaybackProgressState()
            return
        }
        if (rebuildQueue) {
            queueSnapshot = player.queueEntries()
        }
        val item = player.currentMediaItem
        val metadata = item?.mediaMetadata
        val extras = metadata?.extras
        _state.value = PlaybackUiState(
            connected = true,
            currentSongId = item?.mediaId,
            title = metadata?.title?.toString().orEmpty(),
            artist = metadata?.artist?.toString().orEmpty(),
            album = metadata?.albumTitle?.toString().orEmpty(),
            albumId = extras?.getString("albumId"),
            artistId = extras?.getString("artistId"),
            artworkUrl = metadata?.artworkUri?.toString(),
            artworkKey = extras?.getString("coverArtId")
                ?: extras?.getString("albumId")
                ?: item?.mediaId,
            discNumber = extras?.takeIf { it.containsKey("discNumber") }?.getInt("discNumber"),
            codec = extras?.getString("codec"),
            bitDepth = extras?.takeIf { it.containsKey("bitDepth") }?.getInt("bitDepth"),
            samplingRate = extras?.takeIf { it.containsKey("samplingRate") }?.getInt("samplingRate"),
            bitRate = extras?.takeIf { it.containsKey("bitRate") }?.getInt("bitRate"),
            sourceCodec = extras?.getString("sourceCodec"),
            sourceBitDepth = extras?.takeIf { it.containsKey("sourceBitDepth") }
                ?.getInt("sourceBitDepth"),
            sourceSamplingRate = extras?.takeIf { it.containsKey("sourceSamplingRate") }
                ?.getInt("sourceSamplingRate"),
            sourceBitRate = extras?.takeIf { it.containsKey("sourceBitRate") }
                ?.getInt("sourceBitRate"),
            error = playbackError,
            currentIndex = player.currentMediaItemIndex,
            queue = queueSnapshot,
        )
        refreshProgress(player)
    }

    private fun refreshProgress(player: Player? = controller) {
        _progress.value = if (player == null) {
            PlaybackProgressState()
        } else {
            PlaybackProgressState(
                isPlaying = player.isPlaying,
                positionMs = player.currentPosition.coerceAtLeast(0),
                durationMs = player.effectiveDurationMs(),
                isSeekable = player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) &&
                    player.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM) &&
                    player.isCurrentMediaItemSeekable,
            )
        }
    }

    private fun Player.queueEntries(): List<QueueEntry> =
        (0 until mediaItemCount).map { index ->
            val mediaItem = getMediaItemAt(index)
            val itemExtras = mediaItem.mediaMetadata.extras
            QueueEntry(
                id = mediaItem.mediaId,
                title = mediaItem.mediaMetadata.title?.toString().orEmpty(),
                artist = mediaItem.mediaMetadata.artist?.toString().orEmpty(),
                album = mediaItem.mediaMetadata.albumTitle?.toString().orEmpty(),
                artworkUrl = mediaItem.mediaMetadata.artworkUri?.toString(),
                artworkKey = itemExtras?.getString("coverArtId")
                    ?: itemExtras?.getString("albumId")
                    ?: mediaItem.mediaId,
                trackNumber = itemExtras
                    ?.takeIf { it.containsKey("trackNumber") }
                    ?.getInt("trackNumber"),
                durationMs = itemExtras
                    ?.takeIf { it.containsKey("durationMs") }
                    ?.getLong("durationMs")
                    ?.coerceAtLeast(0)
                    ?: 0,
            )
        }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                refreshProgress()
                delay(if (controller?.isPlaying == true) 500 else 1_500)
            }
        }
    }

    fun release() {
        released = true
        reconnectJob?.cancel()
        progressJob?.cancel()
        handoffRefreshJob?.cancel()
        queueMutationRequests.close()
        queueMutationWorker.cancel()
        pendingPlayback = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller?.removeListener(this)
        controller?.release()
        controller = null
        scope.cancel()
    }

    private fun Player.effectiveDurationMs(): Long {
        val streamDuration = duration.takeIf { it != C.TIME_UNSET && it > 0 }
        if (streamDuration != null) return streamDuration
        return currentMediaItem?.mediaMetadata?.extras
            ?.takeIf { it.containsKey("durationMs") }
            ?.getLong("durationMs")
            ?.coerceAtLeast(0)
            ?: 0
    }

    private sealed interface QueueMutation {
        val songs: List<Song>

        data class Replace(
            override val songs: List<Song>,
            val startIndex: Int,
            val positionMs: Long,
            val startPlayback: Boolean,
        ) : QueueMutation

        data class Append(override val songs: List<Song>) : QueueMutation

        fun withSession(generation: Long, session: NavidromeSession): PreparedQueueMutation = when (this) {
            is Replace -> PreparedQueueMutation.Replace(
                songs,
                startIndex,
                positionMs,
                startPlayback,
                generation,
                session,
            )
            is Append -> PreparedQueueMutation.Append(songs, generation, session)
        }
    }

    private sealed interface PreparedQueueMutation {
        val songs: List<Song>
        val generation: Long
        val session: NavidromeSession

        data class Replace(
            override val songs: List<Song>,
            val startIndex: Int,
            val positionMs: Long,
            val startPlayback: Boolean,
            override val generation: Long,
            override val session: NavidromeSession,
        ) : PreparedQueueMutation

        data class Append(
            override val songs: List<Song>,
            override val generation: Long,
            override val session: NavidromeSession,
        ) : PreparedQueueMutation
    }
}
