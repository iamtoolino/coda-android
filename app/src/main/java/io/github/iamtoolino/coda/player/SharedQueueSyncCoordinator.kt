package io.github.iamtoolino.coda.player

import androidx.media3.common.Player
import io.github.iamtoolino.coda.NavidromeSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class SharedQueueSnapshot(
    val songIds: List<String>,
    val currentIndex: Int,
    val positionMs: Long,
)

internal data class QueueSyncDecision(
    val active: Boolean,
    val requestSave: Boolean = false,
    val cancelPending: Boolean = false,
)

internal fun queueSyncDecision(
    wasActive: Boolean,
    hasPlaybackIntent: Boolean,
    mediaItemTransition: Boolean,
): QueueSyncDecision = when {
    !hasPlaybackIntent && wasActive -> QueueSyncDecision(active = false, cancelPending = true)
    !hasPlaybackIntent -> QueueSyncDecision(active = false)
    !wasActive || mediaItemTransition -> QueueSyncDecision(active = true, requestSave = true)
    else -> QueueSyncDecision(active = true)
}

internal fun Player.hasLocalPlaybackIntent(): Boolean =
    mediaItemCount > 0 && playWhenReady && playbackState != Player.STATE_ENDED

internal fun Player.sharedQueueSnapshot(): SharedQueueSnapshot? {
    if (mediaItemCount == 0 || currentMediaItemIndex !in 0 until mediaItemCount) return null
    val ids = (0 until mediaItemCount).map { getMediaItemAt(it).mediaId }
    if (ids.any(String::isBlank)) return null
    return SharedQueueSnapshot(
        songIds = ids,
        currentIndex = currentMediaItemIndex,
        positionMs = currentPosition.coerceAtLeast(0),
    )
}

internal class SharedQueueSyncCoordinator(
    private val scope: CoroutineScope,
    private val snapshot: () -> SharedQueueSnapshot?,
    private val sessionSnapshot: () -> NavidromeSession?,
    private val isCurrentSession: (NavidromeSession) -> Boolean,
    private val save: suspend (NavidromeSession, SharedQueueSnapshot) -> Unit,
) {
    private var localPlaybackActive = false
    private var periodicJob: Job? = null
    @Volatile
    private var latestSequence = 0L
    private val requests = Channel<QueueSaveRequest>(Channel.CONFLATED)
    private var worker = launchWorker()

    fun onPlayerEvents(
        hasPlaybackIntent: Boolean,
        isPlaying: Boolean,
        mediaItemTransition: Boolean,
    ) {
        val decision = queueSyncDecision(
            wasActive = localPlaybackActive,
            hasPlaybackIntent = hasPlaybackIntent,
            mediaItemTransition = mediaItemTransition,
        )
        localPlaybackActive = decision.active
        if (decision.cancelPending) cancelPending()
        if (decision.requestSave) requestSave()
        updatePeriodicSaves(isPlaying && hasPlaybackIntent)
    }

    fun invalidate() {
        localPlaybackActive = false
        updatePeriodicSaves(false)
        cancelPending()
    }

    fun close() {
        invalidate()
        requests.close()
        worker.cancel()
    }

    private fun updatePeriodicSaves(enabled: Boolean) {
        if (!enabled) {
            periodicJob?.cancel()
            periodicJob = null
            return
        }
        if (periodicJob?.isActive == true) return
        periodicJob = scope.launch {
            while (isActive) {
                delay(SAVE_INTERVAL_MILLIS)
                requestSave()
            }
        }
    }

    private fun requestSave() {
        val queue = snapshot() ?: return
        val session = sessionSnapshot() ?: return
        requests.trySend(QueueSaveRequest(++latestSequence, session, queue))
    }

    private fun cancelPending() {
        latestSequence++
        worker.cancel()
        while (requests.tryReceive().isSuccess) Unit
        worker = launchWorker()
    }

    private fun launchWorker(): Job = scope.launch(Dispatchers.IO) {
        for (request in requests) saveRequest(request)
    }

    private suspend fun saveRequest(request: QueueSaveRequest) {
        var retryDelayMs = 1_000L
        repeat(3) { attempt ->
            if (request.sequence != latestSequence || !isCurrentSession(request.session)) return
            try {
                save(request.session, request.queue)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (attempt == 2) return
            }
            delay(retryDelayMs)
            retryDelayMs *= 2
        }
    }

    private data class QueueSaveRequest(
        val sequence: Long,
        val session: NavidromeSession,
        val queue: SharedQueueSnapshot,
    )

    private companion object {
        const val SAVE_INTERVAL_MILLIS = 15_000L
    }
}
