package dev.coda.core.scrobble

import dev.coda.core.connectivity.ServerGate
import dev.coda.core.playback.PlaybackEvent
import dev.coda.core.playback.UnknownSession
import dev.coda.core.playback.sessionStampMatches
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

fun interface ScrobbleApi {
    suspend fun scrobble(songId: String, submission: Boolean, timeMs: Long)
}

/**
 * [key] identifies the account (never a URL or credential); [api] sends for that account. [generation] is the
 * session token the playback events are stamped with ([UnknownSession]: not given, events are not checked).
 */
class ScrobbleSession(val key: String, val api: ScrobbleApi, val generation: Any? = UnknownSession)

/**
 * ScrobbleCoordinator.swift port with a durable outbox. Now-playing is best effort, with the Swift
 * retry schedule and no persistence. A completed play is written to the outbox first, then sent
 * oldest first with its original time. Failed writes stay in memory until persistence recovers;
 * acknowledged plays await removal without being submitted again in this process.
 * Call inputs on the single thread owning [scope]; [io] only runs blocking outbox file calls.
 */
class ScrobbleCoordinator(
    private val scope: CoroutineScope,
    private val outbox: ScrobbleStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val retryDelays: List<Long> = ScrobblePolicy.retryDelaysMs(),
    /** Whether a "now playing" hint is sent now ([dev.coda.core.playback.BackgroundWorkPolicy.sendsNowPlaying]). */
    private val sendsNowPlaying: () -> Boolean = { true },
    /**
     * While true (screen off and playing, [dev.coda.core.playback.BackgroundWorkPolicy.defersScrobbles]) a completed
     * play is persisted at once but sent only with the next boundary request ([flushBatch]), a play persisted within
     * [batchPiggybackMs] after one, or at the latest after [batchMaxMs]. Session changes and a returning network still
     * send at once. Exactly once and in order: the outbox is the only queue either way.
     */
    private val defersSubmissions: () -> Boolean = { false },
    private val batchMaxMs: Long = dev.coda.core.playback.BackgroundWorkPolicy.SCROBBLE_BATCH_MS,
    private val batchPiggybackMs: Long = dev.coda.core.playback.BackgroundWorkPolicy.SCROBBLE_PIGGYBACK_MS,
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000 },
    /**
     * Offline no submission or now-playing is sent (now-playing is dropped, plays stay in the outbox and go out in
     * order when the gate opens). After a failed round the shared backoff decides when the outbox is tried again. Null: no gate.
     */
    private val gate: ServerGate? = null,
) {
    private val session = AtomicReference<ScrobbleSession?>(null)
    private var nowPlayingJob: Job? = null
    private var flushJob: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val persistWake = Channel<Unit>(Channel.CONFLATED)
    private var pendingWrites = emptyList<PendingScrobble>()
    private var awaitingRemoval: String? = null
    private var batchTimer: Job? = null
    private var lastBoundaryAt: Long? = null
    /** One sender at a time: the sign-out flush and the flush worker would otherwise both send the outbox head. */
    private val sendLock = Mutex()
    /** Held while one pending write goes to disk, so [retainOnly] never runs between its commit and its bookkeeping. */
    private val persistLock = Mutex()

    init {
        // Persistence must keep running even while the network worker is suspended in an API
        // request or retry backoff. Otherwise later completions would exist only in memory.
        scope.launch {
            for (signal in persistWake) {
                withStorageRetries(persistWake) {
                    while (persistLock.withLock {
                        val entry = pendingWrites.firstOrNull() ?: return@withLock false
                        withContext(io) { outbox.add(entry, clock()) }
                        pendingWrites = pendingWrites.filterNot { it.id == entry.id }
                        true
                    }) Unit
                }
                flushOrBatch()
            }
        }
        // This consumer stays alive while idle. A wake during the final IO lookup cannot be lost
        // between an empty result and worker shutdown. Only the per-flush child is cancelled.
        scope.launch {
            for (signal in wake) {
                val current = session.get()
                flushJob = scope.launch { flush(current) }
                flushJob?.join()
            }
        }
    }

    /** Session change: in-flight work for the old session is cancelled, then pending plays are flushed. */
    fun setSession(value: ScrobbleSession?) {
        session.set(value)
        nowPlayingJob?.cancel(); nowPlayingJob = null
        flushJob?.cancel(); flushJob = null
        if (pendingWrites.isNotEmpty()) persistWake.trySend(Unit)
        startFlush()
    }

    /** One sink of the single playback event fan-out. Returns quickly; the network work is launched. */
    suspend fun onEvent(event: PlaybackEvent) {
        when (event) {
            PlaybackEvent.OccurrenceEnded -> cancelNowPlaying()
            is PlaybackEvent.OccurrenceStarted -> {
                cancelNowPlaying()
                // The fan-out may deliver late, after an account switch: the event belongs to the session that played it.
                if (sendsNowPlaying()) session.get()?.takeIf { sessionStampMatches(event.sessionGeneration, it.generation) }
                    ?.let { beginOccurrence(it, event.entry.songId) }
            }
            is PlaybackEvent.Completed -> {
                cancelNowPlaying()
                session.get()?.takeIf { sessionStampMatches(event.playback.sessionGeneration, it.generation) }
                    ?.let { submit(it, event.playback.songId) }
            }
        }
    }

    /**
     * A boundary request (the queue save on track change, pause, stop, exit) just went out, or the screen came on:
     * send the batched plays now, while the radio is up anyway.
     */
    fun flushBatch() {
        lastBoundaryAt = elapsedMs()
        batchTimer?.cancel(); batchTimer = null
        startFlush()
    }

    /** Persisted plays go out now, or wait for the batch (see [defersSubmissions]). */
    private fun flushOrBatch() {
        val boundary = lastBoundaryAt
        if (!defersSubmissions() || boundary != null && elapsedMs() - boundary <= batchPiggybackMs) { startFlush(); return }
        if (batchTimer?.isActive == true) return
        batchTimer = scope.launch { delay(batchMaxMs); batchTimer = null; startFlush() }
    }

    /** Connectivity returned: retry now instead of waiting out a back-off delay. */
    fun onNetworkAvailable() {
        if (pendingWrites.isNotEmpty()) persistWake.trySend(Unit)
        startFlush()
    }

    /**
     * Sign-out: send what the outbox holds for the current session once, oldest first, while its login is still
     * valid. No back-off waits: the first failure stops and the entries stay. The caller bounds the time.
     */
    suspend fun flushNow() {
        val current = session.get() ?: return
        flushJob?.cancelAndJoin()
        sendLock.withLock {
            removeAcknowledged()
            while (session.get() === current) {
                // Only "no network" stops it, not a running backoff: sign-out is the user's one chance, bounded
                // by the caller, and the caller already skips it when the server did not answer (as before the gate).
                if (gate != null && !gate.online) { gate.skipped(ServerGate.Kind.SCROBBLE); return }
                val head = withContext(io) { outbox.prune(clock()); outbox.peek(current.key) } ?: return
                try {
                    current.api.scrobble(head.songId, true, head.playedAtMs)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (ScrobblePolicy.classify(e) != ScrobblePolicy.Failure.REJECTED) return
                }
                // Accepted: never sent again, even if the removal fails (the next sender removes it first, as in drain()).
                awaitingRemoval = head.id
                removeAcknowledged()
            }
        }
    }

    /**
     * A sign-in as another account keeps only [serverKey]'s plays. Invariant (Sol P2): memory and disk are
     * filtered together, after any write in flight committed, on the [scope] thread that owns [pendingWrites]; so a
     * delayed or retried write of the old account cannot bring its play back after the wipe.
     */
    suspend fun retainOnly(serverKey: String) = withContext(scope.coroutineContext.minusKey(Job)) {
        persistLock.withLock {
            pendingWrites = pendingWrites.filter { it.serverKey == serverKey }
            withContext(io) { outbox.retainOnly(serverKey) }
        }
    }

    private fun cancelNowPlaying() {
        nowPlayingJob?.cancel()
        nowPlayingJob = null
    }

    private fun beginOccurrence(current: ScrobbleSession, songId: String) {
        nowPlayingJob?.cancel()
        val id = ScrobblePolicy.normalizedSongId(songId) ?: return
        // A hint is only worth it now; offline or backing off it is dropped, not queued.
        if (gate?.allows(ServerGate.Kind.NOW_PLAYING) == false) return
        val time = clock()
        nowPlayingJob = scope.launch {
            for (attempt in 0..retryDelays.size) {
                if (attempt > 0 && gate?.isOpen() == false) return@launch
                try {
                    current.api.scrobble(id, false, time)
                    gate?.reset()
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    if (attempt == retryDelays.size || gate?.retriesAllowed == false) return@launch
                    delay(retryDelays[attempt])
                }
            }
        }
    }

    private fun submit(current: ScrobbleSession, songId: String) {
        val id = ScrobblePolicy.normalizedSongId(songId) ?: return
        val now = clock()
        pendingWrites = ScrobblePolicy.prune(pendingWrites +
            PendingScrobble(serverKey = current.key, songId = id, playedAtMs = now), now)
        persistWake.trySend(Unit)
    }

    private fun startFlush() { wake.trySend(Unit) }

    private suspend fun flush(current: ScrobbleSession?) = withStorageRetries(wake) {
        // A closed gate keeps the outbox and waits (no polling) until a network or the backoff's end opens it.
        // A caller that waited out the gate gets its attempt in this round even if another caller's failure just closed it
        // again: the pending queue save and the outbox go out together.
        var waited = false
        while (drain(current, waited) == Outcome.GATED) { gate?.awaitOpen() ?: return@withStorageRetries; waited = true }
    }

    private suspend fun withStorageRetries(signals: Channel<Unit>, operation: suspend () -> Unit) {
        for (attempt in 0..retryDelays.size) {
            try {
                operation()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // File open/write/sync/rename/read/remove failures are best effort. Do not let
                // them escape the app scope or playback fan-out. Keep work for the next wake.
                if (attempt == retryDelays.size) return
                withTimeoutOrNull(retryDelays[attempt]) { signals.receive() }
            }
        }
    }

    private suspend fun drain(current: ScrobbleSession?, waited: Boolean = false): Outcome = sendLock.withLock {
        removeAcknowledged()
        if (current == null) return@withLock Outcome.STOP
        while (session.get() === current) {
            val head = withContext(io) { outbox.prune(clock()); outbox.peek(current.key) } ?: return@withLock Outcome.STOP
            if (gate != null) {
                if (!gate.online) { gate.skipped(ServerGate.Kind.SCROBBLE); return@withLock Outcome.GATED }
                if (!waited && !gate.isOpen()) return@withLock Outcome.GATED
            }
            when (val outcome = send(current, head)) {
                Outcome.DONE -> {
                    awaitingRemoval = head.id
                    removeAcknowledged()
                }
                Outcome.STOP, Outcome.GATED -> return@withLock outcome
            }
        }
        Outcome.STOP
    }

    private suspend fun removeAcknowledged() {
        val id = awaitingRemoval ?: return
        withContext(io) { outbox.remove(id) }
        awaitingRemoval = null
    }

    /** GATED: not sent because the gate is closed; the entry stays and the flush waits for the gate. */
    private enum class Outcome { DONE, STOP, GATED }

    private suspend fun send(current: ScrobbleSession, entry: PendingScrobble): Outcome {
        for (attempt in 0..retryDelays.size) {
            if (attempt > 0 && gate?.isOpen() == false) return Outcome.GATED
            try {
                current.api.scrobble(entry.songId, true, entry.playedAtMs)
                gate?.reset()
                return Outcome.DONE
            } catch (e: Throwable) {
                when (ScrobblePolicy.classify(e)) {
                    ScrobblePolicy.Failure.REJECTED -> return Outcome.DONE
                    ScrobblePolicy.Failure.UNAUTHORIZED -> return Outcome.STOP
                    ScrobblePolicy.Failure.RETRY -> {
                        // A failed round (all retries, or the one attempt while backing off) starts the next backoff step.
                        if (gate != null && (attempt == retryDelays.size || !gate.retriesAllowed)) { gate.failed(); return Outcome.GATED }
                        if (attempt == retryDelays.size) return Outcome.STOP
                        withTimeoutOrNull(retryDelays[attempt]) { wake.receive() }
                    }
                }
            }
        }
        return Outcome.STOP
    }
}
