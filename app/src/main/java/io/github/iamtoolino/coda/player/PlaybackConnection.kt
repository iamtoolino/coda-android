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

internal fun playedSubmissionPoint(durationMs: Long): Long =
    durationMs.coerceAtLeast(0) * 95 / 100

internal suspend fun retryScrobble(
    maxAttempts: Int = 6,
    initialDelayMs: Long = 1_000,
    action: suspend () -> Unit,
): Boolean {
    require(maxAttempts > 0)
    var retryDelayMs = initialDelayMs.coerceAtLeast(0)
    repeat(maxAttempts) { attempt ->
        try {
            action()
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            if (attempt == maxAttempts - 1) return false
        }
        if (retryDelayMs > 0) delay(retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(30_000)
    }
    return false
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
    private var nowPlayingScrobbleJob: Job? = null
    private val submissionScrobbleJobs = mutableSetOf<Job>()
    private var pendingPlayback: ((MediaController) -> Unit)? = null
    private var submittedCurrentTrack = false
    private var progressTicks = 0
    private var playbackError: String? = null
    private var queueSnapshot: List<QueueEntry> = emptyList()
    @Volatile
    private var latestQueueSaveSequence = 0L
    private val queueSaveRequests = Channel<QueueSaveRequest>(Channel.CONFLATED)
    private var queueSaveWorker = launchQueueSaveWorker()

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    init {
        controllerFuture.addListener(
            {
                val connectedController = runCatching { controllerFuture.get() }.getOrNull()
                    ?: return@addListener
                controller = connectedController.also { it.addListener(this) }
                pendingPlayback?.invoke(connectedController)
                pendingPlayback = null
                refreshState(rebuildQueue = true)
                startProgressUpdates()
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
            saveQueueToServer(delayMs = 750)
        }
    }

    fun restore(queue: PlayQueue) {
        if (queue.entry.isEmpty()) return
        val currentIndex = queue.currentIndex
            ?: queue.entry.indexOfFirst { it.id == queue.current }.takeIf { it >= 0 }
            ?: 0
        runWhenConnected { player ->
            playbackError = null
            val mobile = isMobileNetwork(context)
            player.setMediaItems(
                queue.entry.map { it.toPlayableMediaItem(context, mobile) },
                currentIndex.coerceIn(queue.entry.indices),
                queue.position ?: 0,
            )
            player.prepare()
            player.play()
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
            saveQueueToServer(delayMs = 250)
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
            saveQueueToServer(delayMs = 250)
        }
    }

    fun clearQueue() {
        controller?.clearMediaItems()
        refreshState(rebuildQueue = true)
        saveQueueToServer(delayMs = 100)
    }

    fun disconnect(oldNamespace: String = AppGraph.cacheNamespace) {
        saveScheduleJob?.cancel()
        latestQueueSaveSequence++
        queueSaveWorker.cancel()
        queueSaveWorker = launchQueueSaveWorker()
        nowPlayingScrobbleJob?.cancel()
        val pendingSubmissions = submissionScrobbleJobs.toList()
        submissionScrobbleJobs.clear()
        pendingSubmissions.forEach(Job::cancel)
        pendingPlayback = null
        controller?.run {
            stop()
            clearMediaItems()
        }
        PlaybackService.clearTransientAudioCacheFor(oldNamespace)
        refreshState(rebuildQueue = true)
    }

    private fun runWhenConnected(action: (MediaController) -> Unit) {
        val player = controller
        if (player != null) action(player) else pendingPlayback = action
    }

    fun saveQueueToServer(delayMs: Long = 0) {
        val sequence = ++latestQueueSaveSequence
        saveScheduleJob?.cancel()
        saveScheduleJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            val player = controller ?: return@launch
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

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) playbackError = null
        val rebuildQueue = events.contains(Player.EVENT_TIMELINE_CHANGED) ||
            events.contains(Player.EVENT_MEDIA_METADATA_CHANGED)
        refreshState(rebuildQueue = rebuildQueue)
        if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
            submittedCurrentTrack = false
            nowPlayingScrobbleJob?.cancel()
            player.currentMediaItem?.mediaId?.let { songId ->
                val eventTime = System.currentTimeMillis()
                nowPlayingScrobbleJob = launchScrobble(songId, submission = false, eventTime)
            }
        }
        if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
            events.contains(Player.EVENT_IS_PLAYING_CHANGED) ||
            events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)
        ) {
            saveQueueToServer(delayMs = 500)
        }
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
                    val duration = player.effectiveDurationMs()
                    val submissionPoint = playedSubmissionPoint(duration)
                    if (!submittedCurrentTrack && duration > 0 && player.currentPosition >= submissionPoint) {
                        submittedCurrentTrack = true
                        player.currentMediaItem?.mediaId?.let { songId ->
                            val eventTime = System.currentTimeMillis()
                            trackSubmissionScrobble(
                                launchScrobble(
                                    songId,
                                    submission = true,
                                    eventTime,
                                ),
                            )
                        }
                    }
                }
                delay(if (controller?.isPlaying == true) 500 else 1_500)
            }
        }
    }

    fun release() {
        progressJob?.cancel()
        saveScheduleJob?.cancel()
        queueSaveRequests.close()
        queueSaveWorker.cancel()
        nowPlayingScrobbleJob?.cancel()
        val pendingSubmissions = submissionScrobbleJobs.toList()
        submissionScrobbleJobs.clear()
        pendingSubmissions.forEach(Job::cancel)
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

    private fun launchScrobble(songId: String, submission: Boolean, eventTime: Long): Job? {
        val session = AppGraph.sessionSnapshot() ?: return null
        return scope.launch(Dispatchers.IO) {
            retryScrobble {
                if (!AppGraph.isCurrent(session)) throw CancellationException("Account changed")
                session.client.scrobble(
                    songId = songId,
                    submission = submission,
                    time = eventTime,
                )
            }
        }
    }

    private fun trackSubmissionScrobble(job: Job?) {
        if (job == null) return
        submissionScrobbleJobs += job
        job.invokeOnCompletion {
            scope.launch { submissionScrobbleJobs -= job }
        }
    }

    private data class QueueSaveRequest(
        val sequence: Long,
        val session: NavidromeSession,
        val songIds: List<String>,
        val currentIndex: Int,
        val positionMs: Long,
    )
}
