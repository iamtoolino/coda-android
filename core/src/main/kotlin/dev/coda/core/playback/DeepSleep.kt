package dev.coda.core.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Deep sleep after a pause. Once nothing plays (paused, stopped, a failure; an auto-resume
 * wait counts as playing) for [graceMs] and the read-ahead has no download running ([readAheadBusy] false; a running
 * one is waited for), [release] runs once and [asleep] turns true. Play or any other item (a new occurrence) wakes it
 * at once and the next pause starts a new grace. [release] returns false when there was nothing to release (still
 * asleep: nothing to poll either). Runs until cancelled.
 *
 * [wait] runs the grace. The service passes [wallClockDelay], so a sleeping CPU (uptime frozen) does not
 * stretch "1 min" into hours; the default uptime [delay] is for tests that only use virtual time.
 */
class DeepSleep(
    private val state: StateFlow<PlaybackState>,
    private val readAheadBusy: StateFlow<Boolean>,
    private val release: () -> Boolean,
    private val graceMs: Long = BackgroundWorkPolicy.DEEP_SLEEP_GRACE_MS,
    /** A transient focus loss (a call) that resumes on gain counts as playing: no grace, no release. */
    private val transientHold: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false),
    private val wait: suspend (Long) -> Unit = { delay(it) },
    /** Runs right after [release]: closes idle pooled server connections (in-use ones stay). */
    private val closeIdleConnections: () -> Unit = {},
) {
    private val mutableAsleep = kotlinx.coroutines.flow.MutableStateFlow(false)
    val asleep: StateFlow<Boolean> = mutableAsleep

    private data class Key(val playing: Boolean, val occurrence: OccurrenceId?)

    suspend fun run() {
        kotlinx.coroutines.flow.combine(state, transientHold) { it, hold -> Key(it.isPlaying || hold, it.currentOccurrence?.item?.occurrence) }
            .distinctUntilChanged().collectLatest { key ->
            mutableAsleep.value = false
            if (key.playing) return@collectLatest
            wait(graceMs)
            readAheadBusy.first { !it }
            release()
            closeIdleConnections()
            mutableAsleep.value = true
        }
    }
}

/**
 * A one-shot alarm [afterMs] of wall time (elapsed realtime) from now that calls [onFire]; the returned function
 * cancels it. The service uses a non-wakeup `AlarmManager` alarm: it fires at the next CPU wake after the deadline.
 */
fun interface WallAlarm {
    fun set(afterMs: Long, onFire: () -> Unit): () -> Unit
}

/**
 * Waits [ms] by wall time: whichever comes first of an uptime [delay] (the awake case) and [alarm]. A
 * cancelled wait (Play) cancels the alarm.
 */
suspend fun wallClockDelay(ms: Long, alarm: WallAlarm) {
    val fired = CompletableDeferred<Unit>()
    val cancel = alarm.set(ms) { fired.complete(Unit) }
    try {
        kotlinx.coroutines.withTimeoutOrNull(ms) { fired.await() }
    } finally {
        cancel()
    }
}

/**
 * The playback service's read-ahead rests while [DeepSleep.asleep] is on or an auto-resume wait sleeps past its
 * lock cap ([dev.coda.core.connectivity.ConnectivityRecovery.Waiting.asleep]): no pass and no 30 s timer.
 */
object ReadAheadRest {
    fun rests(asleep: Boolean, state: PlaybackState) = asleep || state.waiting?.asleep == true
    /** Returns at once unless resting; otherwise when the rest ends (Play after deep sleep, the wait ends) or on a network switch. */
    suspend fun await(asleep: StateFlow<Boolean>, state: StateFlow<PlaybackState>, switches: kotlinx.coroutines.flow.Flow<Unit>) {
        if (!rests(asleep.value, state.value)) return
        val awake = kotlinx.coroutines.flow.combine(asleep, state) { a, s -> rests(a, s) }.filter { !it }.map { }
        kotlinx.coroutines.flow.merge(awake, switches).first()
    }
}
