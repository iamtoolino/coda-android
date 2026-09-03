package io.github.iamtoolino.coda.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

internal enum class PlaybackTransition {
    AUTOMATIC,
    REPEAT,
    MANUAL,
    PLAYLIST_CHANGED,
}

internal data class PlaybackOccurrence(
    val id: Long,
    val songId: String,
)

internal sealed interface ScrobbleAction {
    val occurrence: PlaybackOccurrence

    data class NowPlaying(
        override val occurrence: PlaybackOccurrence,
    ) : ScrobbleAction

    data class Submission(
        override val occurrence: PlaybackOccurrence,
    ) : ScrobbleAction
}

internal class ScrobblePolicy {
    private var nextOccurrenceId = 0L
    private var activeOccurrence: PlaybackOccurrence? = null

    fun synchronize(songId: String?): List<ScrobbleAction> {
        if (songId == null) {
            activeOccurrence = null
            return emptyList()
        }
        if (activeOccurrence?.songId == songId) return emptyList()
        return begin(songId)
    }

    fun transition(songId: String?, transition: PlaybackTransition): List<ScrobbleAction> {
        val outgoing = activeOccurrence
        if (transition == PlaybackTransition.PLAYLIST_CHANGED && outgoing?.songId == songId) {
            return emptyList()
        }

        val actions = buildList {
            if (outgoing != null && transition.completesPlayback) {
                add(ScrobbleAction.Submission(outgoing))
            }
        }
        activeOccurrence = null
        if (songId == null) return actions
        return actions + begin(songId)
    }

    fun playbackEnded(): List<ScrobbleAction> {
        val outgoing = activeOccurrence ?: return emptyList()
        activeOccurrence = null
        return listOf(ScrobbleAction.Submission(outgoing))
    }

    fun invalidate() {
        activeOccurrence = null
    }

    private fun begin(songId: String): List<ScrobbleAction> {
        val occurrence = PlaybackOccurrence(++nextOccurrenceId, songId)
        activeOccurrence = occurrence
        return listOf(ScrobbleAction.NowPlaying(occurrence))
    }
}

private val PlaybackTransition.completesPlayback: Boolean
    get() = this == PlaybackTransition.AUTOMATIC || this == PlaybackTransition.REPEAT

internal typealias ScrobbleOperation = suspend () -> Unit

internal class ScrobbleCoordinator(
    private val scope: CoroutineScope,
    private val onCompleted: (String) -> Unit = {},
    private val operationFactory: (
        songId: String,
        submission: Boolean,
        eventTime: Long,
    ) -> ScrobbleOperation?,
) {
    private val policy = ScrobblePolicy()
    private var nowPlayingJob: Job? = null
    private val submissionJobs = mutableSetOf<Job>()

    fun synchronize(songId: String?) = dispatch(policy.synchronize(songId))

    fun transition(songId: String?, transition: PlaybackTransition) =
        dispatch(policy.transition(songId, transition))

    fun playbackEnded() = dispatch(policy.playbackEnded())

    fun invalidate() {
        policy.invalidate()
        nowPlayingJob?.cancel()
        nowPlayingJob = null
        submissionJobs.toList().forEach(Job::cancel)
        submissionJobs.clear()
    }

    fun close() = invalidate()

    private fun dispatch(actions: List<ScrobbleAction>) {
        actions.forEach { action ->
            when (action) {
                is ScrobbleAction.NowPlaying -> {
                    nowPlayingJob?.cancel()
                    nowPlayingJob = launch(action.occurrence.songId, submission = false)
                }

                is ScrobbleAction.Submission -> {
                    onCompleted(action.occurrence.songId)
                    launch(action.occurrence.songId, submission = true)?.let(::trackSubmission)
                }
            }
        }
    }

    private fun launch(songId: String, submission: Boolean): Job? {
        val operation = operationFactory(songId, submission, System.currentTimeMillis()) ?: return null
        return scope.launch {
            withContext(Dispatchers.IO) {
                retryScrobble(action = operation)
            }
        }
    }

    private fun trackSubmission(job: Job) {
        submissionJobs += job
        job.invokeOnCompletion {
            scope.launch { submissionJobs -= job }
        }
    }
}
