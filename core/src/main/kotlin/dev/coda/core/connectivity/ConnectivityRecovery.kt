package dev.coda.core.connectivity

import dev.coda.core.playback.*

data class NetworkState(val available: Boolean = true, val unmetered: Boolean = true, val cellular: Boolean = false)
object StreamFormatPolicy {
    /** Mobile or metered (a hotspot counts as metered), for playback's session extras. Formats: [dev.coda.core.cache.CacheFormatPolicy]. */
    fun metered(network: NetworkState) = dev.coda.core.cache.CacheFormatPolicy.metered(network)
}

/**
 * Note: the fast backoff (and the wake/Wi-Fi locks the service holds for it) lasts [lockCapMs] from the
 * start of the wait. After that retries relax to [relaxedScreenOnMs] or [relaxedScreenOffMs]; the network
 * callback still probes at once. Injectable so tests run in virtual time.
 */
data class RecoveryPolicy(val lockCapMs: Long = 10 * 60_000L, val relaxedScreenOnMs: Long = 2 * 60_000L,
    val relaxedScreenOffMs: Long = 5 * 60_000L)

/** Pure retry clock. The owner supplies monotonic time and performs the probe/load effects. */
class ConnectivityRecovery(private val policy: RecoveryPolicy = RecoveryPolicy()) {
    companion object {
        fun retryDelayMs(attempt: Int): Long = minOf(30_000L, 1000L shl attempt.coerceIn(0, 5))
    }
    data class Waiting(val target: OccurrenceId, val positionMs: Long, val autoResume: Boolean,
        val attempt: Int, val retryAtMs: Long, val sinceMs: Long = 0, val lockUntilMs: Long = Long.MAX_VALUE)
    var waiting: Waiting? = null
        private set
    fun waitFor(target: OccurrenceId, positionMs: Long, autoResume: Boolean, nowMs: Long) {
        waiting = Waiting(target, positionMs, autoResume, 0, nowMs + retryDelayMs(0), nowMs, nowMs + policy.lockCapMs)
    }
    fun due(nowMs: Long) = waiting?.let { nowMs >= it.retryAtMs } == true
    fun failed(nowMs: Long, screenOn: Boolean = true) {
        waiting = waiting?.let {
            val attempt = (it.attempt + 1).coerceAtMost(5)
            val delay = if (nowMs >= it.lockUntilMs) { if (screenOn) policy.relaxedScreenOnMs else policy.relaxedScreenOffMs }
                else retryDelayMs(attempt)
            it.copy(attempt = attempt, retryAtMs = nowMs + delay)
        }
    }
    fun networkChanged(nowMs: Long) { waiting = waiting?.copy(attempt = 0, retryAtMs = nowMs) }
    fun retryNow(nowMs: Long) { waiting = waiting?.copy(retryAtMs = nowMs) }
    fun play(nowMs: Long) { waiting = waiting?.copy(autoResume = true, retryAtMs = nowMs) }
    fun seek(positionMs: Long) { waiting = waiting?.copy(positionMs = positionMs) }
    fun pause() { waiting = waiting?.copy(autoResume = false) }
    fun clear() { waiting = null }
}
