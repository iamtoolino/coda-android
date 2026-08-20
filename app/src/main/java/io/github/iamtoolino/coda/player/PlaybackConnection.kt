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
import io.github.iamtoolino.coda.AppGraph
import io.github.iamtoolino.coda.NavidromeSession
import io.github.iamtoolino.coda.data.NavidromeClient
import io.github.iamtoolino.coda.data.PlayQueue
import io.github.iamtoolino.coda.data.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
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
    val artworkUrl: String? = null,
    val artworkKey: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val codec: String? = null,
    val bitDepth: Int? = null,
    val samplingRate: Int? = null,
    val bitRate: Int? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isSeekable: Boolean = false,
    val error: String? = null,
    val currentIndex: Int = -1,
    val queue: List<QueueEntry> = emptyList(),
)

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlaybackConnection(private val context: Context) : Player.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controllerFuture = MediaController.Builder(
        context,
        SessionToken(context, ComponentName(context, PlaybackService::class.java)),
    ).buildAsync()
    private var controller: MediaController? = null
    private var progressJob: Job? = null
    private var saveScheduleJob: Job? = null
    private var handoffRefreshJob: Job? = null
    private var pendingPlayback: ((MediaController) -> Unit)? = null
    private var progressTicks = 0
    private var playbackError: String? = null
    private var queueSnapshot: List<QueueEntry> = emptyList()
    private var localPlaybackActive = false
    private var pendingHandoffRefresh = false
    private var coldStartRestorePending = true
    private var handoffSessionGeneration: Long? = null
    private var homeVisible = false
    @Volatile
    private var latestQueueSaveSequence = 0L
    private val queueSaveRequests = Channel<QueueSaveRequest>(Channel.CONFLATED)
    private var queueSaveWorker = launchQueueSaveWorker()

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()
    private val _handoffQueue = MutableStateFlow<PlayQueue?>(null)
    val handoffQueue: StateFlow<PlayQueue?> = _handoffQueue.asStateFlow()

    init {
        controllerFuture.addListener(
            {
                val connectedController = runCatching { controllerFuture.get() }.getOrNull()
                    ?: return@addListener
                controller = connectedController.also { it.addListener(this) }
                pendingPlayback?.invoke(connectedController)
                pendingPlayback = null
                refreshState(rebuildQueue = true)
                localPlaybackActive = connectedController.hasLocalPlaybackIntent()
                startProgressUpdates()
                if (pendingHandoffRefresh) refreshHandoffQueue()
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun playSongs(items: List<Song>, startIndex: Int = 0) {
        if (items.isEmpty()) return
        runWhenConnected { player ->
            playbackError = null
            val mobile = isMobileNetwork(context)
            player.setMediaItems(
                items.map { it.toPlayableMediaItem(context, mobile) },
                startIndex.coerceIn(items.indices),
                0,
            )
            player.prepare()
            player.play()
        }
    }

    fun restore(queue: PlayQueue) {
        if (queue.entry.isEmpty()) return
        _handoffQueue.value = null
        coldStartRestorePending = false
        runWhenConnected { player ->
            player.restoreQueue(queue, startPlayback = true)
        }
    }

    fun appendSongs(items: List<Song>) {
        if (items.isEmpty()) return
        runWhenConnected { player ->
            val mobile = isMobileNetwork(context)
            val mediaItems = items.map { it.toPlayableMediaItem(context, mobile) }
            if (player.mediaItemCount == 0) {
                player.setMediaItems(mediaItems)
                player.prepare()
            } else {
                player.addMediaItems(mediaItems)
            }
            refreshState(rebuildQueue = true)
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
        cancelPendingQueueSaves()
        handoffRefreshJob?.cancel()
        pendingHandoffRefresh = false
        coldStartRestorePending = true
        handoffSessionGeneration = null
        _handoffQueue.value = null
        pendingPlayback = null
        controller?.run {
            stop()
            clearMediaItems()
        }
        PlaybackService.invalidateScrobbling()
        PlaybackService.clearTransientAudioCacheFor(oldNamespace)
        refreshState(rebuildQueue = true)
    }

    fun refreshHandoffQueue() {
        val player = controller
        if (player == null) {
            pendingHandoffRefresh = true
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
                    currentPlayer.restoreQueue(requireNotNull(queue), startPlayback = false)
                }
                HandoffDisposition.OFFER_EXTERNAL -> _handoffQueue.value = queue
            }
        }
    }

    fun setHomeVisible(visible: Boolean) {
        homeVisible = visible
    }

    fun onAppForegrounded() {
        if (homeVisible) refreshHandoffQueue()
    }

    private fun runWhenConnected(action: (MediaController) -> Unit) {
        val player = controller
        if (player != null) action(player) else pendingPlayback = action
    }

    private fun saveQueueToServer(delayMs: Long = 0) {
        if (controller?.hasLocalPlaybackIntent() != true) return
        val sequence = ++latestQueueSaveSequence
        saveScheduleJob?.cancel()
        saveScheduleJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            val player = controller ?: return@launch
            if (!player.hasLocalPlaybackIntent()) return@launch
            val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
            if (ids.isNotEmpty() && player.currentMediaItemIndex !in ids.indices) return@launch
            val session = AppGraph.sessionSnapshot() ?: return@launch
            queueSaveRequests.trySend(
                QueueSaveRequest(
                    sequence = sequence,
                    session = session,
                    songIds = ids,
                    currentIndex = player.currentMediaItemIndex.coerceAtLeast(0),
                    positionMs = player.currentPosition.coerceAtLeast(0),
                ),
            )
        }
    }

    private fun cancelPendingQueueSaves() {
        saveScheduleJob?.cancel()
        saveScheduleJob = null
        latestQueueSaveSequence++
        queueSaveWorker.cancel()
        while (queueSaveRequests.tryReceive().isSuccess) Unit
        queueSaveWorker = launchQueueSaveWorker()
    }

    private suspend fun saveQueueRequest(request: QueueSaveRequest) {
        var retryDelayMs = 1_000L
        repeat(3) { attempt ->
            if (request.sequence != latestQueueSaveSequence || !AppGraph.isCurrent(request.session)) return
            try {
                request.session.client.savePlayQueue(
                    songIds = request.songIds,
                    currentIndex = request.currentIndex,
                    positionMs = request.positionMs,
                )
                return
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                if (attempt == 2) return
            }
            delay(retryDelayMs)
            retryDelayMs *= 2
        }
    }

    private fun launchQueueSaveWorker(): Job = scope.launch(Dispatchers.IO) {
        for (request in queueSaveRequests) saveQueueRequest(request)
    }

    private fun MediaController.restoreQueue(queue: PlayQueue, startPlayback: Boolean) {
        val currentIndex = queue.currentIndex
            ?: queue.entry.indexOfFirst { it.id == queue.current }.takeIf { it >= 0 }
            ?: 0
        playbackError = null
        val mobile = isMobileNetwork(context)
        pause()
        setMediaItems(
            queue.entry.map { it.toPlayableMediaItem(context, mobile) },
            currentIndex.coerceIn(queue.entry.indices),
            queue.position ?: 0,
        )
        prepare()
        if (startPlayback) play()
    }

    private fun Player.hasLocalPlaybackIntent(): Boolean =
        mediaItemCount > 0 && playWhenReady && playbackState != Player.STATE_ENDED

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) playbackError = null
        val rebuildQueue = events.contains(Player.EVENT_TIMELINE_CHANGED) ||
            events.contains(Player.EVENT_MEDIA_METADATA_CHANGED)
        refreshState(rebuildQueue = rebuildQueue)
        val playbackActive = player.hasLocalPlaybackIntent()
        if (playbackActive) _handoffQueue.value = null
        if (playbackActive != localPlaybackActive) {
            localPlaybackActive = playbackActive
            if (playbackActive) {
                saveQueueToServer()
            } else {
                cancelPendingQueueSaves()
            }
        }
        if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) && playbackActive) saveQueueToServer()
    }

    override fun onPlayerError(error: PlaybackException) {
        playbackError = error.message ?: "Playback failed"
        refreshState()
    }

    private fun refreshState(rebuildQueue: Boolean = false) {
        val player = controller ?: run {
            queueSnapshot = emptyList()
            _state.value = PlaybackUiState()
            return
        }
        if (rebuildQueue) queueSnapshot = player.queueEntries()
        val item = player.currentMediaItem
        val metadata = item?.mediaMetadata
        val extras = metadata?.extras
        _state.value = PlaybackUiState(
            connected = true,
            currentSongId = item?.mediaId,
            title = metadata?.title?.toString().orEmpty(),
            artist = metadata?.artist?.toString().orEmpty(),
            album = metadata?.albumTitle?.toString().orEmpty(),
            artworkUrl = metadata?.artworkUri?.toString(),
            artworkKey = extras?.getString("albumId") ?: item?.mediaId,
            trackNumber = extras?.takeIf { it.containsKey("trackNumber") }?.getInt("trackNumber"),
            discNumber = extras?.takeIf { it.containsKey("discNumber") }?.getInt("discNumber"),
            codec = extras?.getString("codec"),
            bitDepth = extras?.takeIf { it.containsKey("bitDepth") }?.getInt("bitDepth"),
            samplingRate = extras?.takeIf { it.containsKey("samplingRate") }?.getInt("samplingRate"),
            bitRate = extras?.takeIf { it.containsKey("bitRate") }?.getInt("bitRate"),
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.effectiveDurationMs(),
            isSeekable = player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) &&
                player.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM) &&
                player.isCurrentMediaItemSeekable,
            error = playbackError,
            currentIndex = player.currentMediaItemIndex,
            queue = queueSnapshot,
        )
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
                artworkKey = itemExtras?.getString("albumId") ?: mediaItem.mediaId,
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
                refreshState()
                val player = controller
                if (player?.isPlaying == true) {
                    progressTicks++
                    if (progressTicks % 30 == 0) saveQueueToServer()
                }
                delay(if (controller?.isPlaying == true) 500 else 1_500)
            }
        }
    }

    fun release() {
        progressJob?.cancel()
        saveScheduleJob?.cancel()
        handoffRefreshJob?.cancel()
        queueSaveRequests.close()
        queueSaveWorker.cancel()
        pendingPlayback = null
        controller?.removeListener(this)
        controller?.release()
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

    private data class QueueSaveRequest(
        val sequence: Long,
        val session: NavidromeSession,
        val songIds: List<String>,
        val currentIndex: Int,
        val positionMs: Long,
    )
}
