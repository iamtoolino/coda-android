package dev.coda.core.sync

import dev.coda.core.playback.*
import dev.coda.core.subsonic.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock

enum class QueueHandoffDisposition { RESTORE, OFFER, IGNORE }

/** QueueHandoffCoordinator.swift: disposition, including foreign offers over a paused local queue. */
fun queueHandoffDisposition(queue: PlayQueue?, clientName: String, allowsOwnedQueueRestore: Boolean,
    localQueueIsEmpty: Boolean, localPlaybackIsPlaying: Boolean, queueWasHandled: Boolean): QueueHandoffDisposition {
    if (queue == null || queue.entry.isEmpty() || queueWasHandled || localPlaybackIsPlaying)
        return QueueHandoffDisposition.IGNORE
    if (queue.changedBy.equals(clientName, ignoreCase = true))
        return if (allowsOwnedQueueRestore && localQueueIsEmpty) QueueHandoffDisposition.RESTORE else QueueHandoffDisposition.IGNORE
    return QueueHandoffDisposition.OFFER
}

/**
 * Has another client saved the server's queue since the phone last saw it? Server-clock values only: the server's
 * `changed` against the `changed` we last saw ([seenChanged]), never the phone's clock. Our own save, or no baseline: false.
 */
fun serverQueueChangedSince(server: PlayQueue?, clientName: String, seenChanged: String?): Boolean =
    server != null && server.entry.isNotEmpty() && !server.changedBy.equals(clientName, ignoreCase = true) &&
        seenChanged != null && server.changed != null && server.changed != seenChanged

/** Whether an Auto start request must call [QueueHandoffCoordinator.expectStart] (it installs a new queue for playback). */
fun startRequestArmsGate(isForPlayback: Boolean, keepsPlayerQueue: Boolean) = isForPlayback && !keepsPlayerQueue

data class QueueHandoffSnapshot(val songIDs: List<String>, val currentIndex: Int, val positionMilliseconds: Long) {
    companion object {
        fun capture(player: PlaybackController): QueueHandoffSnapshot {
            val index = player.currentIndex
            return if (player.queue.isEmpty() || index == null) QueueHandoffSnapshot(emptyList(), 0, 0)
            else QueueHandoffSnapshot(player.queue.map { it.songId }, index.coerceIn(player.queue.indices), player.positionMs.coerceAtLeast(0))
        }
    }
}

/** One instance per authenticated session, confined to the playback owner's dispatcher. */
class QueueHandoffCoordinator(
    private val player: PlaybackController,
    parentScope: CoroutineScope,
    private val clientName: String,
    private val load: suspend () -> PlayQueue?,
    private val save: suspend (QueueHandoffSnapshot) -> Unit,
    /** The local copy of our queue, written with every save before the server is asked (it outlives the server's one queue). */
    private val saveLocal: suspend (LocalQueueCopy) -> Unit = {},
    /**
     * With the screen off there is no periodic save at all ([BackgroundWorkPolicy.periodicQueueSaves]); the
     * boundaries (pause, stop, skip, track change, queue edit, exit) still save at once. [onSaved] runs after each
     * successful server save (the scrobble batch rides along with it).
     */
    private val screenOn: () -> Boolean = { true },
    private val onSaved: () -> Unit = {},
    /**
     * No server save or load while offline or while the shared backoff runs. The local copy is still written at
     * every boundary; only the latest snapshot stays pending and goes out once when the gate opens. Null: no gate (tests).
     */
    private val serverGate: dev.coda.core.connectivity.ServerGate? = null,
    /** Reads the local copy [saveLocal] wrote (the last one, also from before a restart); null when there is none. */
    private val loadLocal: suspend () -> LocalQueueCopy? = { null },
) : AutoCloseable {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    // CONFLICT: a held save met a newer server queue of another client on reconnect. The phone keeps playing its queue and
    // keeps the local copy current, but never saves to the server until the user chooses (takes the offer, dismisses it = keep mine
    //, edits the queue, or an Auto start installs a queue); a play/pause toggle does not take the server back.
    private enum class Ownership { NONE, AWAITING_INITIAL, LOCAL, CONFLICT }
    private fun keepsLocal() = ownership == Ownership.LOCAL || ownership == Ownership.CONFLICT
    private var ownership = Ownership.AWAITING_INITIAL
    private var handled: String? = null
    private var observed = player.state.value
    private var scheduledSave: Job? = null
    private var worker: Job? = null
    private data class Request(val snapshot: QueueHandoffSnapshot, val local: LocalQueueCopy, val pauseBoundary: Boolean, val sequence: Long)
    // Every write (worker or exit) is serialized and stamped at capture; an older snapshot never overwrites a newer one.
    private val writeLock = kotlinx.coroutines.sync.Mutex()
    private var captureSequence = 0L
    private var writtenSequence = 0L
    private var localSequence = 0L
    private var pending: Request? = null
    private var pauseSavePending = false
    private var refresh: Job? = null
    private var lifecycleRefresh: Job? = null
    private var foreground = false
    private var restoringRemote = false
    // What the phone last knew of the server's queue, stamped on every local copy: the exact `changed` (server clock) a load
    // returned, including the read-back right after our own save (savePlayQueue returns none); "" when a load saw no queue (or no
    // `changed`). Null = nothing known yet.
    private var lastServerChanged: String? = null
    // A save was held back by an outage (offline or backoff): before it goes out, check that nobody else saved meanwhile.
    private var verifyBeforeSave = false
    // An Auto start request (empty voice query, resumption) puts a queue into the player. The coordinator numbers the queues
    // it observes: every install, replacement, clear, edit, restore or handoff-card apply is a new "generation" (one place:
    // [observeQueue]; a change of the entry ids, so a replacement by the very same songs counts too). The request tags the entries
    // it installs with its token ([expectStart] returns it, `QueueEntry.startToken`), so the install is recognised by the tag, never
    // by its content or by being "the first queue that differs": a user edit that lands first is just an earlier ordinary generation.
    // Exactly that one generation is held back (no save by any path: server, local copy, periodic, pause, exit; no handoff refresh while
    // the window runs) until the engine is audibly playing it (`isActuallyPlaying`, not the play intent, which is true while buffering,
    // offline or about to fail). A failed, aborted or never-started request therefore leaves the server's queue (the Mac's) untouched.
    // Any later generation, whatever its content, is an ordinary queue and is saved by the normal rules. The window
    // ([START_WINDOW_MS], extended while the player still wants to play) only ends the wait for the install (refreshes resume); an expired
    // held generation stays held until it plays or is superseded.
    private class StartGate(val token: Long) { var installed = false }
    private var gate: StartGate? = null
    private var startWindow: Job? = null
    private var generation = 0L
    private var lastEntryIds: List<java.util.UUID> = player.queue.map { it.id }
    private val seenStartTokens = HashSet<Long>()
    private var heldGeneration: Long? = null
    private val mutableOffer = MutableStateFlow<PlayQueue?>(null)
    val offer = mutableOffer.asStateFlow()
    /** Dismissing the offer shown now would be "keep mine" (a reconnect conflict), so Home words its notice for that. */
    fun offerKeepsMine(identity: String) = ownership == Ownership.CONFLICT && mutableOffer.value?.handoffIdentity == identity
    /** Cache migration must wait for a successful initial lookup or an explicit local queue. */
    var initialQueueResolved = false
        private set

    init {
        scope.launch {
            player.state.collect { state ->
                val previous = observed
                observed = state
                observeQueue()
                if (restoringRemote) return@collect
                val queueChanged = previous.queue.map { it.id } != state.queue.map { it.id }
                if (holdsGate()) {
                    if (state.isActuallyPlaying) { heldGeneration = null; releaseGate(); pauseSavePending = false; cancelSaves(); takeOwnership(); scheduleSave(250) }
                    return@collect
                }
                when {
                    queueChanged -> { takeOwnership(); scheduleSave(if (state.isPlaying) 250 else 0, !state.isPlaying) }
                    previous.isPlaying != state.isPlaying -> {
                        if (state.isPlaying) { pauseSavePending = false; if (ownership != Ownership.CONFLICT) takeOwnership(); scheduleSave(500) }
                        else if (keepsLocal()) scheduleSave(0, true)
                    }
                    // A track change saves the new track at position 0 (the snapshot is taken 500 ms later).
                    previous.currentIndex != state.currentIndex && state.isPlaying && keepsLocal() ->
                        scheduleSave(500, trackStart = state.currentIndex)
                }
            }
        }
        // Periodic position save while playing, every 15 s with the screen on only (with it off each request kept
        // the mobile radio awake; the boundaries above still save). A screen turned on saves at the next tick.
        // While quiet the position is still copied to the local file every 60 s: no network, so process death loses
        // at most ~60 s as before. The server save stays event-driven.
        scope.launch {
            var sinceLocalMs = 0L
            while (isActive) {
                delay(SAVE_TICK_MS)
                if (!keepsLocal() || !player.isPlaying) { sinceLocalMs = 0; continue }
                if (BackgroundWorkPolicy.periodicQueueSaves(screenOn())) { sinceLocalMs = 0; scheduleSave(0) }
                else {
                    sinceLocalMs += SAVE_TICK_MS
                    if (sinceLocalMs >= LOCAL_COPY_MS && !holdsGate() && !waitingForStart()) {
                        sinceLocalMs = 0
                        val copy = LocalQueueCopy.capture(player)
                        val sequence = ++captureSequence
                        writeLock.withLock {
                            if (sequence > localSequence) {
                                localSequence = sequence
                                try { saveLocal(stamp(copy)) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                            }
                        }
                    }
                }
            }
        }
        scope.launch { while (isActive) { delay(60_000); if (foreground && !player.isPlaying) refreshRemoteQueue() } }
        // Carry the server-time baseline over a restart until the first load replaces it.
        scope.launch {
            val seen = try { loadLocal()?.serverChanged } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (lastServerChanged == null) lastServerChanged = seen
        }
        scheduleRefresh(250)
    }

    /**
     * Call right before an Auto start request (empty voice query, playback resumption) hands the player a queue; returns the
     * token the request puts on the entries it installs. That queue is saved only once the engine is playing it; if it never does,
     * nothing is written.
     */
    fun expectStart(): Long {
        val g = StartGate(nextStartToken())
        gate = g
        startWindow?.cancel()
        startWindow = scope.launch { do { delay(START_WINDOW_MS) } while (player.isPlaying); expireGate(g.token) }
        return g.token
    }
    /** The wait ended without audio: a held generation stays held, unsaved, until it really plays; a late install is still recognised by its tag. */
    private fun expireGate(token: Long) {
        if (gate?.token != token) return                                   // a stale window of an earlier request
        gate = null; startWindow = null
        if (holdsGate()) cancelSaves()
    }
    private fun releaseGate() { gate = null; startWindow?.cancel(); startWindow = null }
    private fun waitingForStart() = gate != null
    /**
     * The one place that numbers queues: a change of the entry ids (or the first sight of an install token) is a new generation.
     * An entry tagged with a token not seen before is a start request's install: that generation is the held one. Anything else
     * (edit, clear, replacement, restore, handoff card) makes an older held generation ordinary and ends an installed gate.
     */
    private fun observeQueue(): Boolean {
        val queue = player.queue
        val ids = queue.map { it.id }
        val installs = queue.mapNotNull { it.startToken }.filter { it !in seenStartTokens }.distinct()
        if (ids == lastEntryIds && installs.isEmpty()) return false
        lastEntryIds = ids; generation++
        seenStartTokens += installs
        if (installs.isNotEmpty()) {
            heldGeneration = generation
            gate?.takeIf { it.token in installs }?.installed = true
        } else {
            heldGeneration = null
            if (gate?.installed == true) releaseGate()
        }
        return true
    }
    /** True while the player holds the generation a start request installed and the engine has not played it: nothing may be saved. */
    private fun holdsGate(): Boolean { observeQueue(); return heldGeneration == generation }

    fun setForeground(value: Boolean) { foreground = value; if (value) scheduleRefresh(250) }
    fun networkAvailable() = scheduleRefresh(2_000)
    fun scheduleRefresh(delayMs: Long = 0) {
        if (player.isPlaying) return
        // Rescheduling supersedes both the pending delay and any request already in flight.
        lifecycleRefresh?.cancel(); refresh?.cancel(); refresh = null
        lifecycleRefresh = scope.launch { delay(delayMs); refreshRemoteQueue() }
    }

    suspend fun refreshRemoteQueue() { (refresh ?: startRefresh())?.join() }

    /** The request itself is the tracked job (Swift refreshTask/refreshID): cancelling it cancels the load. */
    private fun startRefresh(): Job? {
        if (player.isPlaying || pauseSavePending || waitingForStart()) return null
        if (serverGate?.allows(dev.coda.core.connectivity.ServerGate.Kind.QUEUE_LOAD) == false) return null
        val allowsRestore = ownership == Ownership.AWAITING_INITIAL
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val request = coroutineContext[Job]
            // A failed load is not an empty queue: it must not clear an offer that was already discovered.
            var failed = false
            val queue = try { load() } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true; null }
            // The first lookup after a (re)start with an empty player: is the phone's own copy newer than the server's queue?
            val local = if (!failed && allowsRestore && player.queue.isEmpty() && queue?.entry?.isNotEmpty() == true)
                try { loadLocal() } catch (e: CancellationException) { throw e } catch (_: Exception) { null } else null
            ensureActive()
            if (refresh !== request) return@launch
            refresh = null
            if (!failed) lastServerChanged = queue?.changed.orEmpty()
            // A failed lookup keeps awaiting the initial one: the next successful refresh may still restore (own or local-newer).
            if (!failed && allowsRestore && ownership == Ownership.AWAITING_INITIAL) ownership = Ownership.NONE
            if (!failed && !player.isPlaying && !pauseSavePending && player.queue.isEmpty() && local?.isNewerThan(queue, clientName) == true) {
                // Local is newer: keep the phone's queue and position (not the server's older one) and save it by the usual rules.
                handled = queue!!.handoffIdentity
                restoreLocal(local)
            } else if (!failed && !player.isPlaying && !pauseSavePending) when (queueHandoffDisposition(queue, clientName,
                allowsRestore, player.queue.isEmpty(), player.isPlaying, queue?.handoffIdentity == handled)) {
                QueueHandoffDisposition.RESTORE -> {
                    handled = queue!!.handoffIdentity
                    ownership = Ownership.LOCAL
                    restore(queue, false)
                }
                QueueHandoffDisposition.OFFER -> {
                    cancelSaves(); if (ownership != Ownership.CONFLICT) ownership = Ownership.NONE; mutableOffer.value = queue
                }
                QueueHandoffDisposition.IGNORE -> mutableOffer.value = null
            }
            if (!failed) initialQueueResolved = true
        }
        refresh = job
        job.invokeOnCompletion { if (refresh === job) refresh = null }
        job.start()
        return job
    }

    /**
     * The user dismissed the offer (swipe or "Not now"); the offer going away on its own never calls this. In a
     * reconnect CONFLICT that is "keep mine": take ownership and save the phone's queue over the server's at once (normal save
     * rules, read-back for the new baseline). The save re-reads the server first: a queue the other device saved after the offer
     * was shown comes back as a new offer instead of being overwritten. No Undo for it (the UI shows none).
     */
    fun dismiss(identity: String) {
        if (mutableOffer.value?.handoffIdentity != identity) return
        handled = identity; mutableOffer.value = null
        if (ownership == Ownership.CONFLICT) {
            takeOwnership(); verifyBeforeSave = true
            scheduleSave(0, pauseBoundary = true)
        }
    }

    /** Undo only this dismissal; re-read the server so a newer queue or local playback wins. */
    fun undoDismiss(identity: String) {
        if (handled != identity || player.isPlaying || ownership == Ownership.LOCAL) return
        handled = null
        scheduleRefresh()
    }

    fun continuePlaying(identity: String) {
        val queue = mutableOffer.value?.takeIf { it.handoffIdentity == identity } ?: return
        // After a reconnect conflict the offer is shown while the phone plays; taking it replaces the playing queue.
        if (player.isPlaying && ownership != Ownership.CONFLICT) return
        handled = identity
        releaseGate() // an explicit choice on the phone replaces a pending Auto start
        takeOwnership()
        restore(queue, true)
        scheduleSave(500)
    }

    private fun restore(queue: PlayQueue, playing: Boolean) {
        releaseGate()
        restoringRemote = true
        try {
            player.replaceQueue(queue.entry.map { song -> QueueEntry(song.id, title = song.title,
                artist = song.artistName, album = song.album.orEmpty(), albumId = song.albumId,
                year = song.year, albumArtist = song.albumArtistName,
                coverArtId = song.albumArtworkId, trackNumber = song.track, discNumber = song.discNumber,
                durationMs = (song.duration?.toLong() ?: 0) * 1000, formatName = song.suffix?.uppercase(), bitRate = song.bitRate,
                replayGain = song.replayGain) },
                queue.resolvedCurrentIndex, (queue.position ?: 0).coerceAtLeast(0), playing)
        } finally {
            // Restoring server state is not a local edit, including with an immediate collector.
            observed = player.state.value
            restoringRemote = false
        }
    }

    private fun restoreLocal(copy: LocalQueueCopy) {
        val own = copy.toOwnQueue() ?: return
        releaseGate()
        restoringRemote = true
        try { player.replaceQueue(own.entries, own.currentIndex, own.positionMs, false) }
        finally { observed = player.state.value; restoringRemote = false }
        takeOwnership()
        scheduleSave(0, pauseBoundary = true)
    }

    private fun takeOwnership() {
        initialQueueResolved = true
        ownership = Ownership.LOCAL
        refresh?.cancel(); refresh = null; mutableOffer.value = null
    }
    private fun scheduleSave(delayMs: Long, pauseBoundary: Boolean = false, trackStart: Int? = null) {
        if (!keepsLocal() || holdsGate()) return
        if (pauseBoundary && ownership == Ownership.LOCAL) pauseSavePending = true
        scheduledSave?.cancel()
        scheduledSave = scope.launch {
            delay(delayMs)
            if (!keepsLocal() || holdsGate() || !pauseBoundary && !player.isPlaying) return@launch
            val snapshot = QueueHandoffSnapshot.capture(player).let {
                if (trackStart != null && player.currentIndex == trackStart) it.copy(positionMilliseconds = 0) else it
            }
            val captured = Request(snapshot, LocalQueueCopy.capture(player), pauseBoundary, ++captureSequence)
            if (ownership == Ownership.CONFLICT) { writeLocal(captured.sequence, captured.local); return@launch }  // Never the server
            pending = captured
            // Offline or backing off the server save waits (latest snapshot only), the local copy of this boundary does not.
            // Also when a worker is already busy (Rex r1 P3-3): its write may fail into a backoff, and this boundary's AA-3
            // copy must not wait for that.
            if (serverGate != null && (!serverGate.isOpen() || worker?.isActive == true)) {
                if (!serverGate.online) serverGate.skipped(dev.coda.core.connectivity.ServerGate.Kind.QUEUE_SAVE)
                if (!serverGate.isOpen()) verifyBeforeSave = true
                writeLocal(captured.sequence, captured.local)
            }
            if (worker?.isActive == true) return@launch
            worker = scope.launch {
                while (pending != null) {
                    serverGate?.awaitOpen()
                    if (verifyBeforeSave && serverGate != null) {
                        // Newer wins on reconnect. Read the server's queue first; if another client saved it while we
                        // were away, do not save over it: keep playing, give up ownership and show the normal handoff offer.
                        val server = try { load() } catch (e: CancellationException) { throw e } catch (_: Exception) { serverGate.failed(); continue }
                        verifyBeforeSave = false
                        val newer = serverQueueChangedSince(server, clientName, lastServerChanged)
                        lastServerChanged = server?.changed.orEmpty()
                        if (newer) {
                            pending = null; pauseSavePending = false; ownership = Ownership.CONFLICT; mutableOffer.value = server
                            break
                        }
                    }
                    val request = pending ?: break
                    pending = null
                    var keep = false
                    for (attempt in 0..2) {
                        if (pending != null) break
                        // Right after awaitOpen only "offline" stops the attempt: this round is shared with the scrobble outbox.
                        if (serverGate != null && (!serverGate.online || attempt > 0 && !serverGate.isOpen())) { keep = true; break }
                        try { write(request.sequence, request.snapshot, request.local); serverGate?.reset(); break }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) {
                            // The per-call retries run only while no backoff does; a failed round starts it and keeps the snapshot.
                            if (serverGate != null && (!serverGate.retriesAllowed || attempt == 2)) { serverGate.failed(); keep = true; break }
                            if (attempt < 2) delay(1000L shl attempt)
                        }
                    }
                    if (keep) verifyBeforeSave = true
                    if (keep && pending == null) pending = request
                    if (request.pauseBoundary && pending == null) pauseSavePending = false
                }
            }
        }
    }
    // In CONFLICT the server's queue is the newer one: the copy claims no baseline, so a restart offers the server's queue (as today).
    private fun stamp(local: LocalQueueCopy) =
        local.copy(serverChanged = lastServerChanged.takeIf { ownership != Ownership.CONFLICT })
    private suspend fun writeLocal(sequence: Long, local: LocalQueueCopy) = writeLock.withLock { writeLocalLocked(sequence, local) }
    private suspend fun writeLocalLocked(sequence: Long, local: LocalQueueCopy) {
        // Once per capture, also offline: a failed server save (retried) must not cost the local copy.
        if (sequence > localSequence) {
            localSequence = sequence
            try { saveLocal(stamp(local)) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }
    private suspend fun write(sequence: Long, snapshot: QueueHandoffSnapshot, local: LocalQueueCopy, readBack: Boolean = true) = writeLock.withLock {
        if (sequence < writtenSequence) return@withLock
        // Relies on this order: the local copy is never older than what the server got from us.
        writeLocalLocked(sequence, local)
        save(snapshot)
        writtenSequence = sequence
        // Read the server's queue back once for the exact `changed` of our save (pure server-value equality later, robust
        // to another device with the same client name). Only our own save counts; a failed read keeps the old baseline.
        if (readBack) try {
            load()?.takeIf { it.changedBy.equals(clientName, ignoreCase = true) }?.let { lastServerChanged = it.changed.orEmpty() }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        try { onSaved() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }
    private fun cancelSaves() {
        scheduledSave?.cancel(); worker?.cancel(); pending = null; pauseSavePending = false
    }

    /** Freeze before releasing the engine. Caller supplies an application-lifetime scope for exit I/O. */
    fun terminationSave(): (suspend () -> Unit)? {
        if (!keepsLocal() || holdsGate()) return null
        val snapshot = QueueHandoffSnapshot.capture(player)
        val local = LocalQueueCopy.capture(player)
        val sequence = ++captureSequence
        // An unverified held save (or a conflict) must not reach the server at exit: local copy only.
        val localOnly = ownership == Ownership.CONFLICT || verifyBeforeSave || serverGate?.isOpen() == false
        cancelSaves()
        return {
            try {
                // Offline at exit only the local copy is written (the server keeps its queue until the next save).
                if (serverGate?.online == false) { serverGate.skipped(dev.coda.core.connectivity.ServerGate.Kind.QUEUE_SAVE); writeLocal(sequence, local) }
                else if (localOnly) writeLocal(sequence, local)
                else write(sequence, snapshot, local, readBack = false)
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }
    companion object {
        const val START_WINDOW_MS = 60_000L
        const val SAVE_TICK_MS = 15_000L
        const val LOCAL_COPY_MS = 60_000L
        // Start tokens are unique for the whole process, never per coordinator: sign-out/re-login recreates the coordinator while the
        // player keeps its (tagged) queue, so a per-instance counter would hand a new request a token an old queue already carries.
        // Seeded from a secure random value (nothing persists tokens), then strictly increasing.
        private val startTokens = java.util.concurrent.atomic.AtomicLong(java.security.SecureRandom().nextLong() ushr 2)
        private fun nextStartToken(): Long = startTokens.incrementAndGet()
    }
    override fun close() { scope.cancel(); mutableOffer.value = null }
}
