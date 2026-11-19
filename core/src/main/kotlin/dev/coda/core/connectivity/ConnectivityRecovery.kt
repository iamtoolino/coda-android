package dev.coda.core.connectivity

import dev.coda.core.playback.*

data class NetworkState(val available: Boolean = true, val unmetered: Boolean = true, val cellular: Boolean = false)
object StreamFormatPolicy {
    /** Mobile or metered (a hotspot counts as metered), for playback's session extras. Formats: [dev.coda.core.cache.CacheFormatPolicy]. */
    fun metered(network: NetworkState) = dev.coda.core.cache.CacheFormatPolicy.metered(network)
}

/**
 * Note: the fast backoff (and the wake/Wi-Fi locks the service holds for it) lasts [lockCapMs] from the
 * start of the wait. After that an auto-resume wait sleeps like a paused wait: no timed probe,
 * only Play, Retry or a real network switch probe it (a switch opens [switchFastRetryMs] of fast retries again).
 * Injectable so tests run in virtual time.
 */
data class RecoveryPolicy(val lockCapMs: Long = 10 * 60_000L, val switchFastRetryMs: Long = SWITCH_FAST_RETRY_MS) {
    companion object {
        /**
         * After a real network switch the fast backoff (and its locks) runs at least this long again,
         * so a probe that fails before a VPN tunnel or the new link is up is retried in seconds, not never.
         */
        const val SWITCH_FAST_RETRY_MS = 30_000L
    }
}

/**
 * Pure retry clock. The owner supplies monotonic time and performs the probe/load effects. A
 * paused wait ([Waiting.autoResume] false) probes only on Play, Retry or a real network switch ([networkChanged]; the
 * service calls it only when the network identity changes, not on signal updates). So does an auto-resume
 * wait once a probe fails past its lock window ([Waiting.asleep]); it still resumes by itself when a probe succeeds.
 */
class ConnectivityRecovery(private val policy: RecoveryPolicy = RecoveryPolicy()) {
    companion object {
        fun retryDelayMs(attempt: Int): Long = minOf(30_000L, 1000L shl attempt.coerceIn(0, 5))
        /** [Waiting.retryAtMs] of a paused wait: no timed probe; only Play, Retry or a real network switch probe it. */
        const val UNSCHEDULED = Long.MAX_VALUE
    }
    data class Waiting(val target: OccurrenceId, val positionMs: Long, val autoResume: Boolean,
        val attempt: Int, val retryAtMs: Long, val sinceMs: Long = 0, val lockUntilMs: Long = Long.MAX_VALUE,
        /**
         * A probe failed past the lock window: the wait sleeps (no timed probe, no locks; the foreground
         * service stays, see docs/reports/BAT-6b.md). Stays set for this wait, through switches, Play and Retry.
         */
        val asleep: Boolean = false) {
        /** False between probes for a paused or sleeping wait: nothing is due until Play, Retry or a network switch. */
        val scheduled get() = retryAtMs != UNSCHEDULED
    }
    var waiting: Waiting? = null
        private set
    /**
     * FR-1: [carried] is the previous wait of the same queue item whose reload failed again before any sound; its attempt,
     * start and lock window continue (as one more failed probe) instead of starting over, so the backoff and lock cap apply.
     */
    fun waitFor(target: OccurrenceId, positionMs: Long, autoResume: Boolean, nowMs: Long, carried: Waiting? = null) {
        waiting = carried?.copy(target = target, positionMs = positionMs, autoResume = autoResume)
            ?: Waiting(target, positionMs, autoResume, 0, if (autoResume) nowMs + retryDelayMs(0) else UNSCHEDULED, nowMs, nowMs + policy.lockCapMs)
        if (carried != null) failed(nowMs)
    }
    fun due(nowMs: Long) = waiting?.let { nowMs >= it.retryAtMs } == true
    fun failed(nowMs: Long) {
        waiting = waiting?.let {
            val attempt = (it.attempt + 1).coerceAtMost(5)
            // A paused wait never schedules its next probe; Nor does an auto-resume wait past its lock
            // window (it falls asleep). Both wait for Play, Retry or a network switch.
            val asleep = it.asleep || (it.autoResume && nowMs >= it.lockUntilMs)
            it.copy(attempt = attempt, asleep = asleep,
                retryAtMs = if (it.autoResume && nowMs < it.lockUntilMs) nowMs + retryDelayMs(attempt) else UNSCHEDULED)
        }
    }
    fun networkChanged(nowMs: Long) {
        waiting = waiting?.let { it.copy(attempt = 0, retryAtMs = nowMs, lockUntilMs = maxOf(it.lockUntilMs, nowMs + policy.switchFastRetryMs)) }
    }
    fun retryNow(nowMs: Long) { waiting = waiting?.userProbe(nowMs)?.copy(retryAtMs = nowMs) }
    fun play(nowMs: Long) { waiting = waiting?.userProbe(nowMs)?.copy(autoResume = true, retryAtMs = nowMs) }
    /** Play or Retry on a wait past its lock window (asleep, or paused that long) opens a switch's fast-retry window. */
    private fun Waiting.userProbe(nowMs: Long) = if (nowMs >= lockUntilMs) copy(attempt = 0, lockUntilMs = maxOf(lockUntilMs, nowMs + policy.switchFastRetryMs)) else this
    fun seek(positionMs: Long) { waiting = waiting?.copy(positionMs = positionMs) }
    fun pause() { waiting = waiting?.copy(autoResume = false, retryAtMs = UNSCHEDULED) }
    fun clear() { waiting = null }
}
