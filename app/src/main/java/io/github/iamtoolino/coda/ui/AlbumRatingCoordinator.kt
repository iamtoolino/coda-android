package io.github.iamtoolino.coda.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class AlbumRatingState(
    val rating: Int,
    val confirmedRating: Int,
    val revision: Long = 0L,
    val isUpdating: Boolean = false,
)

internal data class AlbumRatingFailure(val albumId: String)

private data class RatingTransaction(
    val revision: Long,
    val rating: Int,
)

@Stable
internal class AlbumRatingCoordinator(
    private val scope: CoroutineScope,
    private val saver: suspend (albumId: String, rating: Int) -> Unit,
) {
    private val states = mutableStateMapOf<String, AlbumRatingState>()
    private val pending = mutableMapOf<String, RatingTransaction>()
    private val workers = mutableMapOf<String, Job>()
    private val failureChannel = Channel<AlbumRatingFailure>(Channel.BUFFERED)

    val failures: Flow<AlbumRatingFailure> = failureChannel.receiveAsFlow()

    fun state(albumId: String, serverRating: Int?): AlbumRatingState = states[albumId]
        ?: baselineState(serverRating)

    fun select(albumId: String, serverRating: Int?, selectedRating: Int): Job {
        require(selectedRating in 1..5) { "Album rating selection must be between 1 and 5" }
        val current = state(albumId, serverRating)
        val newRating = if (selectedRating == current.rating) 0 else selectedRating
        val revision = current.revision + 1L
        states[albumId] = current.copy(
            rating = newRating,
            revision = revision,
            isUpdating = true,
        )
        pending[albumId] = RatingTransaction(revision, newRating)
        return ensureWorker(albumId)
    }

    fun close() {
        pending.clear()
        workers.values.toList().forEach(Job::cancel)
        workers.clear()
        failureChannel.close()
    }

    private fun ensureWorker(albumId: String): Job {
        workers[albumId]?.takeIf { it.isActive }?.let { return it }
        val job = scope.launch { runWorker(albumId) }
        workers[albumId] = job
        job.invokeOnCompletion {
            if (workers[albumId] === job) {
                workers.remove(albumId)
                if (pending.containsKey(albumId) && scope.isActive) ensureWorker(albumId)
            }
        }
        return job
    }

    private suspend fun runWorker(albumId: String) {
        while (true) {
            val transaction = pending.remove(albumId) ?: return
            try {
                saver(albumId, transaction.rating)
                val current = states[albumId] ?: continue
                states[albumId] = current.copy(
                    confirmedRating = transaction.rating,
                    isUpdating = current.revision > transaction.revision ||
                        pending.containsKey(albumId),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                val current = states[albumId] ?: continue
                if (current.revision == transaction.revision) {
                    states[albumId] = current.copy(
                        rating = current.confirmedRating,
                        isUpdating = false,
                    )
                    failureChannel.trySend(AlbumRatingFailure(albumId))
                }
            }
        }
    }

    private fun baselineState(serverRating: Int?): AlbumRatingState {
        val rating = serverRating?.coerceIn(0, 5) ?: 0
        return AlbumRatingState(rating = rating, confirmedRating = rating)
    }
}

internal val LocalAlbumRatingCoordinator = staticCompositionLocalOf<AlbumRatingCoordinator> {
    error("AlbumRatingCoordinator is not available outside an authenticated Coda session")
}
