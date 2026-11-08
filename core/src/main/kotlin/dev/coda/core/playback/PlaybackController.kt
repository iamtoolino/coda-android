package dev.coda.core.playback

import java.util.UUID
import dev.coda.core.connectivity.ConnectivityRecovery
import dev.coda.core.connectivity.RecoveryPolicy
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** One playback attempt, distinct from the stable queue-entry identity. */
data class Occurrence(
    val entry: QueueEntry,
    val albumSequence: AlbumSequenceContext?,
    val item: EngineItem,
    /** The hand-off source an adapter fallback replaced; the fallback stays frozen for this occurrence. */
    val fallbackFrom: TrackSource? = null,
) {
    internal fun withFallback(source: TrackSource) =
        if (source == item.source) this else copy(item = item.copy(source = source), fallbackFrom = fallbackFrom ?: item.source)
}

/** Stamp of a [CompletedPlayback] whose controller was not told which session it plays for (tests, standalone use). */
data object UnknownSession

/**
 * The one policy for late-delivered playback events (album resume, scrobbling): an event stamped by the controller
 * at emission belongs to the session [current] only if the stamps agree. An [UnknownSession] stamp (no session source
 * on the controller) or an [UnknownSession] current (a consumer that was not given a session generation) cannot be
 * judged and is accepted; a null stamp (signed out when it happened) never matches a real session.
 */
fun sessionStampMatches(stamp: Any?, current: Any?): Boolean =
    stamp === UnknownSession || current === UnknownSession || stamp == current

data class CompletedPlayback(
    val songId: String,
    val albumSequence: AlbumSequenceContext?,
    val id: UUID = UUID.randomUUID(),
    /**
     * The session generation (its token) that was signed in when the track completed, or null when nobody was.
     * Stamped by the controller at completion, so a delivery that lags behind an account switch still names the
     * account that played the track. [UnknownSession] when the controller has no session source.
     */
    val sessionGeneration: Any? = UnknownSession,
)

sealed interface PlaybackEvent {
    /** [sessionGeneration]: stamped like [CompletedPlayback.sessionGeneration], so a late now-playing is attributable. */
    data class OccurrenceStarted(val entry: QueueEntry, val sessionGeneration: Any? = UnknownSession) : PlaybackEvent
    data class Completed(val playback: CompletedPlayback) : PlaybackEvent
    /** The active attempt ended without a replacement starting (clear, stop, failure, release). */
    data object OccurrenceEnded : PlaybackEvent
}

enum class RepeatMode { OFF, ALL, ONE }

data class PlaybackState(
    val queue: List<QueueEntry> = emptyList(),
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val currentIndex: Int? = null,
    val currentOccurrence: Occurrence? = null,
    val completedPlayback: CompletedPlayback? = null,
    /** Controller play intent (playWhenReady), including while buffering. */
    val isPlaying: Boolean = false,
    val isActuallyPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    /** Loaded engine resources, distinct from a retained stopped queue. */
    val isPrepared: Boolean = false,
    val replayGainEnabled: Boolean = false,
    val status: String = "Nothing queued",
    val failure: PlaybackFailure? = null,
    val entryFailures: Map<UUID, PlaybackFailure> = emptyMap(),
    val waiting: ConnectivityRecovery.Waiting? = null,
    /** The engine's length for [engineDurationOccurrence]; it counts only while that is the current occurrence. */
    val engineDurationOccurrence: OccurrenceId? = null,
    val engineDurationMs: Long? = null,
) {
    /** Length the player UI shows for the current item: the engine's real length once known, else the server's. */
    val displayDurationMs: Long get() {
        val server = currentEntry?.durationMs?.coerceAtLeast(0) ?: 0
        val engine = engineDurationMs?.takeIf { engineDurationOccurrence != null && engineDurationOccurrence == currentOccurrence?.item?.occurrence }
        return resolveDisplayDurationMs(server, engine)
    }
    val currentEntry: QueueEntry? get() = currentIndex?.let(queue::getOrNull)
    val nextEntry: QueueEntry? get() = currentIndex?.let { queue.getOrNull(it + 1) }
}

/**
 * PlayerController.swift queue/occurrence port. The owner serializes commands and engine events
 * on the main thread. No timers, I/O, Android APIs, settings storage, or engine-owned full queue.
 * SettingsStore supplies/persists the ReplayGain choice; restoring a queue never changes it.
 */
class PlaybackController(
    private val engine: AudioEngine,
    replayGainEnabled: Boolean = false,
    private val sourceResolver: SourceResolver = SourceResolver { it.source },
    var playbackGate: PlaybackGate = PlaybackGate.Unrestricted,
    private val networkAvailable: () -> Boolean = { true },
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    recoveryPolicy: RecoveryPolicy = RecoveryPolicy(),
    private val screenOn: () -> Boolean = { true },
    /** Token of the signed-in session right now (null: signed out); stamped on every [CompletedPlayback]. */
    private val sessionGeneration: () -> Any? = { UnknownSession },
) {
    private val mutableState = MutableStateFlow(PlaybackState(replayGainEnabled = replayGainEnabled))
    val state: StateFlow<PlaybackState> = mutableState.asStateFlow()
    private val pendingEvents = Channel<PlaybackEvent>(Channel.UNLIMITED)
    /** Buffered until the service-owned PlaybackEventDispatcher fans out to all sinks. Not durable. */
    val events: Flow<PlaybackEvent> = pendingEvents.receiveAsFlow()
    // A public command may touch the state several times; observers (the queue handoff saves) must only
    // ever see the finished result, never the queue without its selection. Held values stay readable here.
    private var held: PlaybackState? = null
    private var atomicDepth = 0
    private var snapshot: PlaybackState
        get() = held ?: mutableState.value
        set(value) { if (atomicDepth > 0) held = value else mutableState.value = value }
    private inline fun <T> atomically(block: () -> T): T {
        atomicDepth++
        try { return block() } finally {
            if (--atomicDepth == 0) held?.let { held = null; mutableState.value = it }
        }
    }

    private var released = false
    private var preparedNext: Occurrence? = null
    // An adapter may already be playing a superseded preparation while Advanced is queued.
    private val supersededNext = ArrayDeque<Occurrence>()
    private var hasLoadedItem = false
    private var hasStartedCurrentOccurrence = false
    private var savedPositionMs = 0L
    val recovery = ConnectivityRecovery(recoveryPolicy)

    fun networkChanged() {
        recovery.networkChanged(nowMs()); publishWaiting()
        if (recovery.waiting == null) synchronizeNextItem()
    }
    /**
     * The read-ahead finished [songId]. A prepared successor that still streams it is prepared again from the
     * file (through [synchronizeNextItem]), so it plays from the cache instead of streaming at playback pace. Only while
     * the successor cannot have started: not near the end of the current item ([refreshesPreparedNext]), not while
     * waiting, and never one that already fell back to another source.
     */
    fun trackCached(songId: String) {
        if (released || !hasLoadedItem || recovery.waiting != null) return
        val prepared = preparedNext ?: return
        if (prepared.entry.songId != songId || prepared.item.source !is TrackSource.Remote || prepared.fallbackFrom != null) return
        val remaining = snapshot.displayDurationMs.takeIf { it > 0 }?.let { it - positionMs }
        if (!refreshesPreparedNext(remaining)) return
        if (sourceResolver.resolve(prepared.entry) !is TrackSource.CachedFile) return
        synchronizeNextItem()
    }
    /** A song was written to the cache by any downloader ("Load for offline" too): [trackCached] for the successor. */
    fun cacheChanged() { preparedNext?.let { trackCached(it.entry.songId) } }
    fun retryNow() { recovery.retryNow(nowMs()); publishWaiting() }
    fun probeFailed() { recovery.failed(nowMs(), screenOn()); publishWaiting() }
    fun probeSucceeded(target: OccurrenceId) {
        val waiting = recovery.waiting?.takeIf { it.target == target } ?: return
        val frozen = snapshot.currentOccurrence ?: return
        // A read-ahead copy completed while waiting is played instead of fetching the stream again.
        val cached = (frozen.item.source as? TrackSource.Remote)?.let { sourceResolver.resolve(frozen.entry) as? TrackSource.CachedFile }
        val current = frozen.copy(item = frozen.item.copy(occurrence = OccurrenceId(), source = cached ?: frozen.item.source))
        recovery.clear()
        val willPlay = waiting.autoResume && playbackGate.requestPlay()
        hasLoadedItem = true
        hasStartedCurrentOccurrence = willPlay
        preparedNext = successorOccurrence(currentIndex ?: return)
        snapshot = snapshot.copy(currentOccurrence = current, waiting = null, failure = null, isPlaying = willPlay, isPrepared = true,
            isBuffering = true, status = "Buffering", entryFailures = snapshot.entryFailures - current.entry.id)
        if (willPlay) emitStarted()
        engine.load(current.item, preparedNext?.item, waiting.positionMs, willPlay)
    }
    private fun publishWaiting() {
        val waiting = recovery.waiting ?: return
        snapshot = snapshot.copy(waiting = waiting, isPlaying = waiting.autoResume,
            isActuallyPlaying = false, isBuffering = true, isPrepared = false, failure = null, status = "Waiting for connection")
    }
    private fun waitFor(current: Occurrence, index: Int, position: Long, autoplay: Boolean) {
        hasLoadedItem = false; hasStartedCurrentOccurrence = false; savedPositionMs = position
        preparedNext = null; supersededNext.clear()
        snapshot = snapshot.copy(currentIndex = index, currentOccurrence = current)
        recovery.waitFor(current.item.occurrence, position, autoplay, nowMs())
        engine.pause()
        publishWaiting()
    }
    private fun successorOccurrence(index: Int, entries: List<QueueEntry> = queue): Occurrence? =
        occurrence(successorIndex(index, entries), entries)?.takeUnless { !networkAvailable() && it.item.source is TrackSource.Remote }

    val queue: List<QueueEntry> get() = snapshot.queue
    val currentIndex: Int? get() = snapshot.currentIndex
    val currentEntry: QueueEntry? get() = snapshot.currentEntry
    val nextEntry: QueueEntry? get() = snapshot.nextEntry
    /** Natural playback successor, including repeat; manual Next retains its separate semantics. */
    val successorEntry: QueueEntry? get() = currentIndex?.let { queue.getOrNull(successorIndex(it)) }
    val playbackOccurrenceId: OccurrenceId? get() = snapshot.currentOccurrence?.item?.occurrence
    val currentAlbumSequence: AlbumSequenceContext? get() = snapshot.currentOccurrence?.albumSequence
    val currentReplayGainDb: Double get() = snapshot.currentOccurrence?.item?.gainDb ?: 0.0
    val completedPlayback: CompletedPlayback? get() = snapshot.completedPlayback
    val isPlaying: Boolean get() = snapshot.isPlaying
    val playWhenReady: Boolean get() = snapshot.isPlaying
    val replayGainEnabled: Boolean get() = snapshot.replayGainEnabled
    val status: String get() = snapshot.status
    val positionMs: Long get() = if (hasLoadedItem) engine.positionMs().coerceAtLeast(0) else savedPositionMs
    val durationMs: Long get() = currentEntry?.durationMs?.coerceAtLeast(0) ?: 0
    /** [durationMs], replaced by the engine's real length of the current item once it is known and plausible. */
    val displayDurationMs: Long get() = snapshot.displayDurationMs
    /** Display-only engine snapshot for the Now Playing format chip (main thread). */
    fun engineDetails(): EngineDetails? = engine.details()
    val canGoPrevious: Boolean get() = currentIndex != null
    val canGoNext: Boolean get() = nextEntry != null
    /** Sources the engine may still open: current, prepared successor and superseded preparations. */
    val engineSources: List<TrackSource> get() =
        listOfNotNull(snapshot.currentOccurrence?.item?.source, preparedNext?.item?.source) + supersededNext.map { it.item.source }

    init {
        engine.listener = AudioEngine.Listener(::handleEngineEvent)
    }

    fun replaceQueue(
        entries: List<QueueEntry>, startAt: Int = 0, startPositionMs: Long = 0,
        startsPlaying: Boolean = true,
    ): Unit = atomically {
        playbackGate.onPause()
        resetLoadedState("Nothing queued")
        engine.stop()
        snapshot = snapshot.copy(queue = entries.toList(), shuffleEnabled = false, entryFailures = emptyMap())
        if (entries.isNotEmpty()) load(startAt.coerceIn(entries.indices), startPositionMs, startsPlaying)
    }

    fun enqueue(entries: List<QueueEntry>, startPlayingIfEmpty: Boolean = false): Unit = atomically {
        if (entries.isEmpty()) return@atomically
        val oldCount = queue.size
        snapshot = snapshot.copy(queue = queue + entries)
        // A waiting recovery target occupies the selection; appending must not replace it.
        if (oldCount == 0 || (!hasLoadedItem && recovery.waiting == null)) {
            load(if (oldCount == 0) 0 else oldCount, 0, startPlayingIfEmpty)
        } else synchronizeNextItem()
    }

    fun insert(entries: List<QueueEntry>, before: UUID?) {
        if (entries.isEmpty()) return
        if (queue.isEmpty()) { enqueue(entries); return }
        val index = queue.indexOfFirst { it.id == before }.takeIf { it >= 0 } ?: queue.size
        applyReorderedQueue(queue.toMutableList().apply { addAll(index, entries) })
    }

    fun move(entryIds: List<UUID>, before: UUID?): Boolean {
        val reordered = reorderedQueueIds(queue.map { it.id }, entryIds, before) ?: return false
        val byId = queue.associateBy { it.id }
        applyReorderedQueue(reordered.map { byId.getValue(it) })
        return true
    }

    /** One-shot album-run shuffle after the playing occurrence; no Media3 shuffle mode. */
    fun shuffleRemaining(random: kotlin.random.Random = kotlin.random.Random.Default) {
        val start = (currentIndex ?: return) + 1
        applyReorderedQueue(queue.take(start) + dev.coda.core.library.TrackListPresentation.shuffledGroups(
            queue.drop(start), { it.albumId }, random))
    }

    fun remove(entryId: UUID) = remove(listOf(entryId))

    fun remove(entryIds: List<UUID>) {
        val removed = entryIds.toSet()
        if (queue.none { it.id in removed }) return
        val oldQueue = queue
        val oldIndex = currentIndex
        val newQueue = oldQueue.filterNot { it.id in removed }
        if (oldIndex == null || currentEntry?.id !in removed) {
            applyReorderedQueue(newQueue)
            return
        }
        val replacement = oldQueue.drop(oldIndex + 1).firstOrNull { it.id !in removed }
            ?: oldQueue.take(oldIndex).lastOrNull { it.id !in removed }
        val wasPlaying = playWhenReady
        if (replacement == null) clear()
        else load(newQueue.indexOfFirst { it.id == replacement.id }, 0, wasPlaying, newQueue)
    }

    fun play(index: Int) {
        if (index in queue.indices) load(index, 0, true)
    }

    fun play() {
        if (recovery.waiting != null) { recovery.play(nowMs()); publishWaiting(); return }
        val index = currentIndex ?: return
        if (!hasLoadedItem) { load(index, savedPositionMs, true); return }
        if (!playbackGate.requestPlay()) return
        val startsOccurrence = !hasStartedCurrentOccurrence
        hasStartedCurrentOccurrence = true
        snapshot = snapshot.copy(isPlaying = true, failure = null, status = "Playing")
        if (startsOccurrence) emitStarted()
        engine.play()
    }

    /** [immediate]: no fade, for headphones unplugged. */
    fun pause(immediate: Boolean = false) {
        playbackGate.onPause()
        suspendPlayback(immediate)
    }

    /** Focus suspension keeps the guard's pending resume; explicit pause always cancels it. */
    fun suspendPlayback(immediate: Boolean = false) {
        if (recovery.waiting != null) { recovery.pause(); publishWaiting(); return }
        snapshot = snapshot.copy(isPlaying = false, isActuallyPlaying = false,
            status = if (currentEntry == null) "Nothing queued" else "Paused")
        if (immediate) engine.pauseNow() else engine.pause()
    }

    /** Release loaded resources, retaining queue selection and position for prepare/play. */
    fun stop() = atomically {
        // One published state: `waiting = null` alone with the old buffering flag read as IDLE + loading.
        recovery.clear()
        snapshot = snapshot.copy(waiting = null)
        playbackGate.onPause()
        invalidateOccurrence()
        // Finished has no active occurrence; retain the last item/position exposed by the session.
        val finished = status == "Finished" && queue.isNotEmpty()
        savedPositionMs = if (finished) queue.last().durationMs.coerceAtLeast(0) else positionMs
        hasLoadedItem = false
        preparedNext = null
        supersededNext.clear()
        snapshot = snapshot.copy(currentIndex = if (finished) queue.lastIndex else currentIndex,
            isPlaying = false, isActuallyPlaying = false, isBuffering = false,
            isPrepared = false, status = "Stopped")
        engine.stop()
    }

    fun prepare() {
        if (recovery.waiting != null) return
        val index = currentIndex ?: return
        if (!hasLoadedItem) load(index, savedPositionMs, playWhenReady)
    }

    fun next() {
        val index = currentIndex ?: return
        val next = if (index + 1 in queue.indices) index + 1 else if (snapshot.repeatMode == RepeatMode.ALL) 0 else return
        load(next, 0, playWhenReady)
    }

    /** Legacy callers/restored mode requests cannot enable shuffle; playlist shuffle is a one-shot action. */
    @Suppress("UNUSED_PARAMETER")
    fun setShuffleEnabled(enabled: Boolean) = Unit

    fun setRepeatMode(mode: RepeatMode) {
        if (mode == snapshot.repeatMode) return
        snapshot = snapshot.copy(repeatMode = mode)
        synchronizeNextItem()
    }

    private fun successorIndex(index: Int, entries: List<QueueEntry> = queue): Int = when {
        snapshot.repeatMode == RepeatMode.ONE -> index
        index + 1 < entries.size -> index + 1
        snapshot.repeatMode == RepeatMode.ALL && entries.isNotEmpty() -> 0
        else -> entries.size
    }

    fun previous() {
        val index = currentIndex ?: return
        if (positionMs > 3_000 || index == 0) seek(0)
        else load(index - 1, 0, playWhenReady)
    }

    fun seek(positionMs: Long) {
        // Server duration is only display metadata; it may be shorter than the decoded audio.
        val bounded = positionMs.coerceAtLeast(0)
        if (hasLoadedItem && bounded > 0) {
            val occurrence = snapshot.currentOccurrence
            val source = occurrence?.item?.source
            if (occurrence != null && source is TrackSource.Remote && source.format == StreamFormat.OPUS_128) {
                // A transcode cannot seek: continue from the cached file once it is complete. Otherwise the engine
                // serves it (from its buffer, or a new transcode at `timeOffset`) when the server supports
                // that; without it the seek is ignored, as before.
                if (continueFromCache(occurrence, bounded) || !engine.seeksInsideTranscodes()) return
                val target = TranscodeSeek.clamp(bounded, snapshot.displayDurationMs.takeIf { it > 0 })
                savedPositionMs = target
                if (recovery.waiting != null) { recovery.seek(target); publishWaiting() }
                engine.seekTo(target)
                return
            }
        }
        savedPositionMs = bounded
        if (recovery.waiting != null) { recovery.seek(bounded); publishWaiting() }
        if (hasLoadedItem) engine.seekTo(bounded)
    }

    /** Session seeks may select another item while preserving paused/play intent. */
    fun seek(index: Int, positionMs: Long) {
        if (index == currentIndex) seek(positionMs)
        else if (index in queue.indices) load(index, positionMs, playWhenReady)
    }

    fun clear(): Unit = atomically {
        playbackGate.onPause()
        resetLoadedState("Nothing queued")
        snapshot = snapshot.copy(queue = emptyList(), entryFailures = emptyMap())
        engine.stop()
    }

    fun setReplayGainEnabled(enabled: Boolean) {
        if (enabled == replayGainEnabled) return
        snapshot = snapshot.copy(replayGainEnabled = enabled)
        val current = snapshot.currentOccurrence ?: return
        val gain = ReplayGainPolicy.gainDb(enabled, current.albumSequence, current.entry.replayGain)
        // Also while waiting for connection (nothing loaded): the recovery load copies this retained occurrence,
        // so it must carry the new gain; its album context stays frozen.
        snapshot = snapshot.copy(currentOccurrence = current.copy(item = current.item.copy(gainDb = gain)))
        if (!hasLoadedItem) return
        engine.setGain(gain)
        synchronizeNextItem()
    }

    fun release() {
        if (released) return
        engine.listener = null
        clear()
        released = true
        engine.release()
        pendingEvents.close()
    }

    private fun occurrence(at: Int, entries: List<QueueEntry> = queue): Occurrence? {
        val entry = entries.getOrNull(at) ?: return null
        val context = AlbumSequenceResolver.context(entries, at)
        return Occurrence(entry, context, EngineItem(
            OccurrenceId(), sourceResolver.resolve(entry),
            ReplayGainPolicy.gainDb(replayGainEnabled, context, entry.replayGain),
        ))
    }

    private fun load(index: Int, positionMs: Long, autoplay: Boolean, entries: List<QueueEntry> = queue): Unit = atomically {
        val current = occurrence(index, entries) ?: return@atomically
        val wasWaiting = recovery.waiting != null
        recovery.clear()
        snapshot = snapshot.copy(waiting = null)
        if (current.item.source is TrackSource.Remote && (!networkAvailable() || wasWaiting)) {
            snapshot = snapshot.copy(queue = entries.toList())
            waitFor(current, index, positionMs, autoplay); return@atomically
        }
        val willPlay = autoplay && playbackGate.requestPlay()
        if (!willPlay) invalidateOccurrence()
        hasLoadedItem = true
        hasStartedCurrentOccurrence = willPlay
        savedPositionMs = positionMs.coerceAtLeast(0)
        supersededNext.clear()
        preparedNext = successorOccurrence(index, entries)
        snapshot = snapshot.copy(
            queue = entries.toList(), currentIndex = index, currentOccurrence = current, isPlaying = willPlay,
            isActuallyPlaying = false, isBuffering = false, isPrepared = true, failure = null,
            status = if (willPlay) "Playing" else "Ready",
            entryFailures = snapshot.entryFailures.filterKeys { id -> entries.any { it.id == id } } -
                setOfNotNull(current.entry.id, preparedNext?.entry?.id),
        )
        if (willPlay) emitStarted()
        engine.load(current.item, preparedNext?.item, savedPositionMs, willPlay)
    }

    private fun synchronizeNextItem() {
        val index = currentIndex ?: return
        if (!hasLoadedItem) return
        // A paused restore may adopt edits until its first play; a played then paused item may not.
        if (!hasStartedCurrentOccurrence) {
            val current = snapshot.currentOccurrence ?: return
            val context = AlbumSequenceResolver.context(queue, index)
            val gain = ReplayGainPolicy.gainDb(replayGainEnabled, context, current.entry.replayGain)
            snapshot = snapshot.copy(currentOccurrence = current.copy(
                albumSequence = context, item = current.item.copy(gainDb = gain),
            ))
            if (gain != current.item.gainDb) engine.setGain(gain)
        }
        val next = successorOccurrence(index)
        val prepared = preparedNext
        // A prepared successor that already fell back keeps its accepted source.
        if (next?.entry == prepared?.entry && next?.item?.gainDb == prepared?.item?.gainDb &&
            (next?.item?.source == prepared?.item?.source || (prepared?.fallbackFrom != null && next?.item?.source == prepared.fallbackFrom))) return
        preparedNext?.let {
            if (supersededNext.size == 8) supersededNext.removeFirst()
            supersededNext.addLast(it)
        }
        preparedNext = next
        next?.let { snapshot = snapshot.copy(entryFailures = snapshot.entryFailures - it.entry.id) }
        engine.updateNext(preparedNext?.item)
    }

    private fun applyReorderedQueue(reordered: List<QueueEntry>): Unit = atomically {
        val currentId = currentEntry?.id
        val newIndex = reordered.indexOfFirst { it.id == currentId }.takeIf { it >= 0 }
        snapshot = snapshot.copy(
            queue = reordered.toList(), currentIndex = newIndex,
            entryFailures = snapshot.entryFailures.filterKeys { id -> reordered.any { it.id == id } },
        )
        if (reordered.isEmpty()) clear()
        else if (newIndex != null) synchronizeNextItem()
    }

    /** Swaps a streamed transcode for its complete cached copy at [positionMs]; false: no complete copy yet. */
    private fun continueFromCache(occurrence: Occurrence, positionMs: Long): Boolean {
        val cached = sourceResolver.resolve(occurrence.entry) as? TrackSource.CachedFile ?: return false
        savedPositionMs = positionMs
        snapshot = snapshot.copy(currentOccurrence = occurrence.copy(item = occurrence.item.copy(source = cached)))
        engine.replaceCurrentSource(cached, positionMs)
        return true
    }

    private fun handleEngineEvent(event: EngineEvent) {
        if (released) return
        when (event) {
            is EngineEvent.State -> {
                if (!hasLoadedItem || playbackOccurrenceId != event.occurrence) return
                val startsOccurrence = event.isPlaying && !hasStartedCurrentOccurrence
                if (event.isPlaying) hasStartedCurrentOccurrence = true
                snapshot = snapshot.copy(
                    isActuallyPlaying = event.isPlaying, isBuffering = event.isBuffering,
                    engineDurationOccurrence = event.occurrence, engineDurationMs = event.durationMs,
                    status = when {
                        event.isBuffering -> "Buffering"
                        event.isPlaying -> "Playing"
                        snapshot.failure == null -> "Paused"
                        else -> status
                    },
                )
                if (startsOccurrence) emitStarted()
            }
            is EngineEvent.Advanced -> {
                if (!hasLoadedItem) return
                val incoming = preparedNext?.takeIf { it.item.occurrence == event.to }
                    ?: supersededNext.firstOrNull { it.item.occurrence == event.to } ?: return
                val index = successorIndex(currentIndex ?: return)
                if (queue.getOrNull(index)?.id != incoming.entry.id) return
                publishCompletion()
                hasStartedCurrentOccurrence = true
                savedPositionMs = 0
                supersededNext.clear()
                preparedNext = successorOccurrence(index)
                snapshot = snapshot.copy(
                    currentIndex = index, currentOccurrence = incoming, failure = null,
                    isActuallyPlaying = false, isBuffering = false, status = if (isPlaying) "Playing" else "Ready",
                    entryFailures = snapshot.entryFailures - setOfNotNull(incoming.entry.id, preparedNext?.entry?.id),
                )
                emitStarted()
                engine.updateNext(preparedNext?.item)
            }
            is EngineEvent.Ended -> {
                if (!hasLoadedItem || playbackOccurrenceId != event.last) return
                publishCompletion()
                val successorIndex = successorIndex(currentIndex!!)
                val successor = queue.getOrNull(successorIndex)
                if (successor == null) resetLoadedState("Finished")
                else if (snapshot.entryFailures[successor.id] == PlaybackFailure.Network ||
                    (!networkAvailable() && sourceResolver.resolve(successor) is TrackSource.Remote)) {
                    val incoming = preparedNext ?: occurrence(successorIndex)!!
                    waitFor(incoming, successorIndex, 0, playWhenReady)
                } else if (snapshot.repeatMode != RepeatMode.OFF && snapshot.entryFailures[successor.id] == null) {
                    load(successorIndex, 0, playWhenReady)
                } else {
                    // An unavailable next item pauses at the boundary, retaining a retry target.
                    invalidateOccurrence()
                    val failure = snapshot.entryFailures[successor.id]
                    val incoming = preparedNext?.takeIf { it.entry.id == successor.id }
                    hasLoadedItem = false
                    hasStartedCurrentOccurrence = false
                    savedPositionMs = 0
                    preparedNext = null
                    supersededNext.clear()
                    snapshot = snapshot.copy(currentIndex = successorIndex, currentOccurrence = incoming,
                        isPlaying = false, isActuallyPlaying = false, isBuffering = false, isPrepared = false, failure = failure,
                        status = when (failure) {
                            PlaybackFailure.Network -> "Waiting for connection"
                            null -> "Ready"
                            else -> "Playback failed"
                        })
                }
            }
            is EngineEvent.StreamInterrupted -> {
                if (!hasLoadedItem || playbackOccurrenceId != event.occurrence) return
                val occurrence = snapshot.currentOccurrence ?: return
                val source = occurrence.item.source
                if (source is TrackSource.Remote && source.format == StreamFormat.OPUS_128) continueFromCache(occurrence, positionMs)
            }
            is EngineEvent.SourceFallback -> {
                snapshot.currentOccurrence?.takeIf { it.item.occurrence == event.occurrence }?.let {
                    snapshot = snapshot.copy(currentOccurrence = it.withFallback(event.source))
                }
                preparedNext = preparedNext?.let { if (it.item.occurrence == event.occurrence) it.withFallback(event.source) else it }
                for (i in supersededNext.indices) if (supersededNext[i].item.occurrence == event.occurrence)
                    supersededNext[i] = supersededNext[i].withFallback(event.source)
            }
            is EngineEvent.Failed -> {
                if (!hasLoadedItem) return
                if (event.occurrence == playbackOccurrenceId) {
                    // The connection is gone but the read-ahead finished the song: carry on from the complete local
                    // copy at the exact position (same occurrence, so no end/start pair), instead of waiting for the network.
                    if (event.failure == PlaybackFailure.Network) {
                        val occurrence = snapshot.currentOccurrence
                        if (occurrence != null && occurrence.item.source is TrackSource.Remote && continueFromCache(occurrence, positionMs)) return
                    }
                    invalidateOccurrence()
                    savedPositionMs = positionMs
                    if (event.failure == PlaybackFailure.Network) {
                        waitFor(snapshot.currentOccurrence!!, currentIndex!!, savedPositionMs, playWhenReady)
                        return
                    }
                    hasLoadedItem = false
                    snapshot = snapshot.copy(isPlaying = false, isActuallyPlaying = false, isBuffering = false, isPrepared = false,
                        failure = event.failure, status = "Playback failed",
                        entryFailures = snapshot.entryFailures + (currentEntry!!.id to event.failure))
                } else if (event.occurrence == preparedNext?.item?.occurrence) {
                    // The adapter must keep current audio running when preparing next fails.
                    snapshot = snapshot.copy(entryFailures = snapshot.entryFailures +
                        (preparedNext!!.entry.id to event.failure))
                }
            }
        }
    }

    private fun emitStarted() {
        currentEntry?.let { if (!released) pendingEvents.trySend(PlaybackEvent.OccurrenceStarted(it, sessionGeneration())) }
    }

    private fun invalidateOccurrence() {
        if (hasStartedCurrentOccurrence && !released) pendingEvents.trySend(PlaybackEvent.OccurrenceEnded)
        hasStartedCurrentOccurrence = false
    }

    private fun publishCompletion() {
        val entry = currentEntry ?: return
        val completion = CompletedPlayback(entry.songId, currentAlbumSequence, sessionGeneration = sessionGeneration())
        snapshot = snapshot.copy(completedPlayback = completion)
        if (!released) pendingEvents.trySend(PlaybackEvent.Completed(completion))
    }

    private fun resetLoadedState(status: String) {
        invalidateOccurrence()
        recovery.clear()
        snapshot = snapshot.copy(waiting = null)
        hasLoadedItem = false
        hasStartedCurrentOccurrence = false
        savedPositionMs = 0
        preparedNext = null
        supersededNext.clear()
        snapshot = snapshot.copy(currentIndex = null, currentOccurrence = null,
            isPlaying = false, isActuallyPlaying = false, isBuffering = false, isPrepared = false, status = status, failure = null)
    }

    companion object {
        /**
         * Media3 starts reading the successor well before the boundary (about 84 s ahead in the step-1
         * timeline, more than its 50 s buffer suggests), so within the last stretch of the current item the successor
         * may already be loading for the gapless join; swapping it then would waste the bytes read. Unknown length: no swap.
         */
        const val NEXT_REFRESH_MIN_REMAINING_MS = 90_000L
        fun refreshesPreparedNext(remainingMs: Long?): Boolean = remainingMs != null && remainingMs > NEXT_REFRESH_MIN_REMAINING_MS
    }
}
