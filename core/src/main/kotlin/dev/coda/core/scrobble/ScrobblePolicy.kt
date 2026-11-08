package dev.coda.core.scrobble

import dev.coda.core.subsonic.SubsonicError
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlin.math.max
import kotlin.math.min

/** Port of the scrobbling policy in ScrobbleCoordinator.swift, plus the Android outbox limits. */
object ScrobblePolicy {
    const val MAX_ATTEMPTS = 6
    const val INITIAL_DELAY_MS = 1_000L
    private const val MAX_DELAY_MS = 30_000L

    /** Durable outbox limits (decisions.md, architecture section 20 Q2). */
    const val OUTBOX_MAX_ENTRIES = 500
    val OUTBOX_MAX_AGE_MS: Long = TimeUnit.DAYS.toMillis(14)

    /** scrobbleRetryDelays(): delay before attempt n+1; doubles from the initial delay, capped at 30 s. */
    fun retryDelaysMs(maxAttempts: Int = MAX_ATTEMPTS, initialDelayMs: Long = INITIAL_DELAY_MS): List<Long> {
        if (maxAttempts <= 1) return emptyList()
        var delay = max(0L, initialDelayMs)
        return List(maxAttempts - 1) { delay.also { delay = min(delay * 2, MAX_DELAY_MS) } }
    }

    /** NavidromeConfiguration.scrobbleURL: the song id is trimmed and must not be empty. */
    fun normalizedSongId(songId: String): String? = songId.trim().ifEmpty { null }

    enum class Failure {
        /** The server was unreachable or unwell: try again. */
        RETRY,
        /** An explicitly permanent rejection (for example an unknown song): drop it. */
        REJECTED,
        /** The credentials are rejected: keep the entry until a new session exists. */
        UNAUTHORIZED,
    }

    /** Cancellation is never a failure; callers rethrow it before classifying. */
    fun classify(error: Throwable): Failure {
        if (error is CancellationException) throw error
        return when (error) {
            is SubsonicError.Network -> Failure.RETRY
            is SubsonicError.Http -> when (error.status) {
                401, 403 -> Failure.UNAUTHORIZED
                400, 404, 405, 410, 422 -> Failure.REJECTED
                else -> Failure.RETRY
            }
            is SubsonicError.Unauthorized -> Failure.UNAUTHORIZED
            is SubsonicError.NotFound, is SubsonicError.InvalidConfiguration -> Failure.REJECTED
            // A malformed response or generic API failure says nothing about permanence.
            else -> Failure.RETRY
        }
    }

    /** Drops entries older than 14 days, then the oldest beyond 500. The input is oldest first. */
    fun prune(entries: List<PendingScrobble>, nowMs: Long): List<PendingScrobble> {
        val fresh = entries.filter { nowMs - it.playedAtMs <= OUTBOX_MAX_AGE_MS }
        return if (fresh.size > OUTBOX_MAX_ENTRIES) fresh.takeLast(OUTBOX_MAX_ENTRIES) else fresh
    }
}
