package dev.coda.android.cache

import dev.coda.core.cache.*
import dev.coda.core.connectivity.NetworkState
import dev.coda.core.playback.StreamFormat
import dev.coda.core.playback.TrackSource
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/**
 * Application-lifetime owner of the one cache's background work: explicit adds ("Keep offline") and their serial
 * downloads, the one-shot "Load for offline", switching a set off, maintenance (expiry, Keep free space,
 * whole kept sets) and server reachability for expiry (old music leaves only while the server is reachable). The read-ahead window runs in the playback service.
 *
 * [downloads] is the one shared download state (notification, Keep offline lines, queue header). The downloads
 * run here; the user-initiated data transfer job ([DownloadJobService]) only keeps the process running while work is
 * pending. While nothing is pending the pass sleeps until it is woken.
 *
 * Maintenance never runs inside the download loop: once at start, on a settings Confirm, and from its own
 * ticker. The ticker runs a pass after each song written and each track change ([TrackCache.maintenanceEvents]),
 * and while Coda is [active] (foreground, playing or buffering, or the download job running) at the latest every
 * [fallbackMs] (expiry between events), or every [housekeepingMs] while the app is on screen, or near or below the line (with the screen off [screenOffMs]).
 * A pass with nothing due returns before any folder walk ([TrackCache.housekeep]).
 */
class OfflineLibrary(
    private val scope: CoroutineScope,
    val cache: TrackCache,
    private val network: StateFlow<NetworkState>,
    networkChanges: Flow<Unit>,
    private val remote: (TrackSource.Remote) -> String,
    /** The shared connectivity state; it owns the one reachability probe ([Reachability.refresh]). */
    private val reachability: dev.coda.core.connectivity.Reachability,
    client: OkHttpClient = dev.coda.android.net.HttpClientFactory.client,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    onClose: () -> Unit = {},
    /** Class names only: messages may contain URLs. */
    private val log: (String) -> Unit = { android.util.Log.w("CodaCache", it) },
    /**
     * Whether transfers may run now. The app sets it to "the app is visible or the user-initiated transfer job
     * runs"; when it turns false the running transfer stops and pending work is kept, so nothing downloads after the
     * job was stopped (JobService contract). Tests leave it open.
     */
    private val transfersAllowed: StateFlow<Boolean> = MutableStateFlow(true),
    /** How soon the ticker looks again while the last pass left something due (the 60 s tick). */
    private val housekeepingMs: Long = HOUSEKEEPING_MS,
    /** The slow fallback while Coda is [active] and nothing is due (an expiry falling between events). */
    private val fallbackMs: Long = FALLBACK_MS,
    /**
     * The app is on screen: the ticker keeps the [housekeepingMs], so space taken by other apps (a video) is
     * noticed as before while the user looks; the CPU is awake then and a pass with nothing due costs about a millisecond.
     */
    private val visible: StateFlow<Boolean> = MutableStateFlow(false),
    /** The screen is on. Off, a phone near or below the line is looked at every [screenOffMs] instead of [housekeepingMs]. */
    private val screenOn: StateFlow<Boolean> = MutableStateFlow(true),
    /** The near-the-line interval with the screen off (the 5 min; also caps a phone that stays below the line). */
    private val screenOffMs: Long = SCREEN_OFF_MS,
    /** The user asked for downloads (Keep offline, Load for offline); ends a system-stop hold of the transfers. */
    private val onUserWork: () -> Unit = {},
    /** Coda is active (the ticker runs); otherwise the ticker suspends without a timer. Tests leave it on. */
    private val active: StateFlow<Boolean> = MutableStateFlow(true),
    /** Saves the music storage settings ([applyPolicy]). */
    private val savePolicy: (CachePolicy, Boolean?) -> Unit = { _, _ -> },
    /** Names the active network + transports (null: unknown); "incomplete" tracks retry only after it changes. */
    private val networkIdentity: () -> String? = { null },
    /** Where kept covers come from (the picture cache, then one fetch on proper Wi-Fi); null: no cover store. */
    private val coverSource: dev.coda.core.cache.CoverStore.Source? = null,
) {
    /** Added sets (for badges and pages). */
    val items = MutableStateFlow(cache.addedSets)
    /** Bumps whenever cached tracks, sets or the summary may have changed. */
    val revision = MutableStateFlow(0L)
    /** The set whose tracks download right now. */
    val downloading = MutableStateFlow<String?>(null)
    /** The shared download state. */
    val downloads = MutableStateFlow(DownloadState())
    val summary = MutableStateFlow(CacheSummary(cache.sizeBytes(), Long.MAX_VALUE, 0))
    /** Kept sets maintenance removed for space, until OK on Music storage. */
    val removedForSpace = MutableStateFlow(cache.removedForSpace)
    /** Maintenance passes run (tests: the ticker's gating). */
    val maintenanceRuns = MutableStateFlow(0)
    /** Ticker timer wake-ups (tests: no wake-up once Coda is idle). */
    val tickerWakeups = MutableStateFlow(0)
    /** Disk queries made for the Music storage screen (tests: no polling in the background). */
    val uiPolls = java.util.concurrent.atomic.AtomicInteger()
    /** The shared state's "server reachable" (optimistic until the first verdict); expiry pauses while false. */
    val serverReachable: StateFlow<Boolean> get() = reachable
    private val reachable = MutableStateFlow(reachability.state.value.reachable)
    private val mutex = Mutex()
    private val prefetch = Prefetcher(client, nowMs)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    /** A maintenance pass ran (any trigger); the ticker recomputes its wait from it. */
    private val passed = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var generation = 0L
    /** The running transfer: an audio download, or the kept-cover fill; every stop path cancels it. */
    @Volatile private var transfer: Job? = null
    /** When an offline check was last counted as a skipped ping (one per [PING_MS], like the pings it stands for). */
    @Volatile private var skippedPingAt: Long? = null
    /** The cache hears the shared state only from this library's first check on (expiry waits for a real verdict). */
    @Volatile private var checked = false
    /** The running "Load for offline": the queue's tracks at the tap (not an added set; persisted with the cache index). */
    private val queueDownload: KeptItem? get() = cache.queueDownload
    /**
     * Tracks the running "Load for offline" stored (memory only). A network change never fetches them again: one
     * loaded as Opus on mobile data is not replaced by the original when Wi-Fi comes back during the same load.
     */
    @Volatile private var queueFetched: Set<String> = emptySet()
    /** Opus copies whose replacement the user cancelled ([TrackCache.replacementsCancelled]). */
    private val replacementsCancelled: Set<String> get() = cache.replacementsCancelled
    /** Tracks stored by this run of work (see [DownloadState.done]). */
    @Volatile private var runDone = 0
    @Volatile private var stalled = false
    /** The last pass found something to download (or replace); otherwise the loop sleeps until woken. */
    @Volatile private var passHadWork = true
    @Volatile private var closed = false
    /** When the last maintenance pass ran ([nowMs]); the ticker's waits count from it. */
    @Volatile private var lastPassAt = nowMs()
    /** Cover copies (`id@size`) fetched once by this library (per sign-in; a failure waits for the next one, never loops). */
    private val coversTried: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /**
     * Kept covers still to store in this pass. While true the transfer job does not end after the
     * last audio download ([DownloadState.coversPending]), so a keep finishing in the background stores its covers first.
     */
    @Volatile private var keptCoversDue = false

    init {
        scope.coroutineContext[Job]?.invokeOnCompletion { onClose() }
        // Persisted work (kept sets' pending tracks, the queue download) is known from the constructor on: publish it
        // before anything suspends (the first reachability check can take 15 s), so the transfer job that restarted the
        // process never mistakes "not published yet" for "nothing to do". Kept covers still missing count too (an
        // artwork-only job after a restart). Constructed on IO (the check looks at the cover files).
        keptCoversDue = coverSource != null && cache.covers.keptMissing(cache.keptCoverIds(), coversTried)
        publishDownloads()
        var lastAllowed = transfersAllowed.value
        scope.launch {
            transfersAllowed.collect { allowed ->
                if (allowed == lastAllowed) return@collect
                lastAllowed = allowed
                // The job stopped (or the app left the screen without it): the running transfer stops, pending work stays.
                if (!allowed) transfer?.cancel()
                publishDownloads()
                wake.trySend(Unit)
            }
        }
        scope.launch {
            networkChanges.collect {
                // Adds download only on proper Wi-Fi: leaving it stops the running download (an original resumes
                // later with Range, an Opus transcode restarts). "Load for offline" stops only without a network.
                if (downloading.value?.let { !canTransfer(it) } ?: !canTransfer()) transfer?.cancel()
                prefetch.networkChanged(networkIdentity())
                publishDownloads()
                wake.trySend(Unit)
            }
        }
        scope.launch(Dispatchers.IO) {
            // Trigger 2: process start, once, before the first download pass.
            try { maintain() } catch (e: CancellationException) { throw e }
                catch (e: Exception) { log("Music cache maintenance failed: ${e.javaClass.simpleName}") }
            while (isActive) {
                try {
                    checkReachability()
                    publish()
                    downloadPass()
                    cache.flush()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    log("Music cache pass failed: ${e.javaClass.simpleName}")
                }
                // With nothing to download (or nothing allowed to run) the loop has no timer at all: it sleeps
                // until an add, a re-sync, a delete, a network change, the app coming to the front or the job starting.
                val idle = !passHadWork && downloads.value.let { !it.active || it.waitingForWifi || it.paused }
                if (idle) wake.receive()
                else withTimeoutOrNull(prefetch.retryDelayMs().coerceAtLeast(PASS_MS)) { wake.receive() }
            }
        }
    }

    private fun startTicker(scope: CoroutineScope) = scope.launch(Dispatchers.IO) {
        // Trigger 3: a pass after each song written and each track change (coalesced), otherwise while
        // active at the latest after [fallbackMs] (or [housekeepingMs] while on screen or something is still due) since the last pass;
        // while idle suspended on the events only (no timer, no wakeup). Screen on or off does not matter.
        val events = Channel<Unit>(Channel.CONFLATED)
        launch { cache.maintenanceEvents.drop(1).collect { events.trySend(Unit) } }
        kotlinx.coroutines.flow.combine(active, visible, screenOn) { on, shown, lit -> Triple(on, shown, lit) }.collectLatest { (on, shown, lit) ->
            while (true) {
                // On screen: 60 s. Near or below the line: 60 s with the screen on, 5 min with it off. Otherwise the fallback.
                val interval = if (shown) housekeepingMs
                    else if (cache.dueAfterPass || cache.nearLine) (if (lit) housekeepingMs else maxOf(housekeepingMs, screenOffMs))
                    else fallbackMs
                // true: an event; false: a pass ran elsewhere (start, Confirm), so the wait is computed again; null: the timer.
                val woke = if (on) withTimeoutOrNull((interval - (nowMs() - lastPassAt)).coerceAtLeast(1)) { nextWake(events) }
                    else nextWake(events)
                when (woke) {
                    null -> tickerWakeups.value++
                    false -> continue
                    // A song commit follows a track change within seconds: one pass for the burst.
                    // Coda turning idle meanwhile restarts this block: the event is kept for it.
                    true -> { try { delay(EVENT_SETTLE_MS) } catch (e: CancellationException) { events.trySend(Unit); throw e }; events.tryReceive() }
                }
                // A step that began finishes, even if Coda goes idle meanwhile.
                // Reachability first, when due (the download loop may be asleep): expiry must not run on a stale answer.
                try { withContext(NonCancellable) { checkReachability(); maintain() } } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { log("Music cache maintenance failed: ${e.javaClass.simpleName}") }
            }
        }
    }

    private suspend fun nextWake(events: Channel<Unit>): Boolean = kotlinx.coroutines.selects.select {
        events.onReceive { true }
        passed.onReceive { false }
    }

    init {
        startTicker(scope)
        // The shared state changes on its own (network, request outcomes, probes): the clock follows it at once.
        // Off the main thread: reachability is set under the cache's monitor, which IO maintenance holds.
        scope.launch(Dispatchers.IO) { reachability.state.collect { observe(it) } }
    }

    /**
     * One maintenance step: under the library mutex, which no transfer holds, so it never waits for one.
     * Expiry, the line, whole kept sets, dead sets, then catalogue unpins and a new generation if a set left. IO.
     */
    suspend fun maintain() = withContext(Dispatchers.IO) { mutex.withLock { afterMaintenance(cache.housekeep()) } }

    /** Under the mutex. */
    private fun afterMaintenance(left: List<String>) {
        if (left.isNotEmpty()) { generation++; if (downloading.value in left) transfer?.cancel() }
        // Playback can cache the last missing tracks of a queue download on mobile data; nothing else would end it then.
        finishQueueDownloadIfDone(generation)
        lastPassAt = nowMs()
        passed.trySend(Unit)
        maintenanceRuns.value++
        publish()
    }

    private fun measuredSummary() = CacheSummary(cache.sizeBytes(), cache.freeBytes(), cache.lineBytes(), cache.directory.totalSpace,
        cache.covers.sizeBytes(), cache.removableMusicBytes())

    /** Re-reads the status card's numbers (music on the phone, free space). IO. */
    suspend fun refreshSummary() = withContext(Dispatchers.IO) {
        uiPolls.incrementAndGet()
        summary.value = measuredSummary()
    }

    /** What saving [policy] would delete now (the sheet's live preview). IO. */
    suspend fun preview(policy: CachePolicy): CachePlanner.Plan = withContext(Dispatchers.IO) { uiPolls.incrementAndGet(); cache.preview(policy) }

    /**
     * Confirm on the settings sheet, or a change whose preview deletes nothing: re-plans under the mutex and
     * the cache lock; when the plan names a kept set [shown] did not, or deletes more than its total plus
     * max(5 %, 100 MB), nothing is saved or deleted and the new plan is returned. Otherwise saves [policy] and deletes
     * by that plan in the same locked step (no notice: the sheet named the sets), and returns null.
     */
    suspend fun applyPolicy(policy: CachePolicy, shown: CachePlanner.Plan?, custom: Boolean? = null): CachePlanner.Plan? = withContext(Dispatchers.IO) {
        mutex.withLock {
            var refused: CachePlanner.Plan? = null
            val left = cache.housekeepIf(policy, notice = false, shown = shown) { plan ->
                val ok = plan.isEmpty || (shown != null && !changedMaterially(shown, plan))
                if (ok) savePolicy(policy, custom) else refused = plan
                ok
            }
            if (left != null) { cache.refreshDue(); afterMaintenance(left) }
            refused
        }
    }

    private fun publish() {
        items.value = cache.addedSets
        summary.value = measuredSummary()
        removedForSpace.value = cache.removedForSpace
        revision.value++
        publishDownloads()
    }

    /**
     * The catalogue may ask the server for album metadata now: the server answers, on any network (* mobile data and hotspots too; a run is capped at 200 albums and stops after 3 failures in a row). Not tied to the
     * screen being on.
     */
    fun catalogueFetchAllowed() = reachable.value

    /** Transfers may run now: proper Wi-Fi, and the app is visible or the transfer job runs. */
    private fun canTransfer(net: NetworkState = network.value) = CacheFormatPolicy.addsDownload(net) && transfersAllowed.value

    /**
     * The network lets [key]'s downloads run. A kept set only on proper Wi-Fi; "Load for offline"
     * on any network, mobile data and hotspots too, with nothing to confirm (each track in that network's format).
     */
    private fun networkAllows(key: String, net: NetworkState = network.value) =
        if (key == DownloadLines.QUEUE_KEY) net.available else CacheFormatPolicy.addsDownload(net)

    /** [key]'s transfers may run now: [networkAllows], and the app is visible or the transfer job runs. */
    private fun canTransfer(key: String, net: NetworkState = network.value) = networkAllows(key, net) && transfersAllowed.value

    /**
     * The one definition of download work for [set] (memory only), used by execution ([candidates]), the shared state and
     * Cancel: tracks pending since the add or re-sync that are not on the phone and not refused by the server, plus Opus
     * copies the original replaces (on Wi-Fi) unless the user cancelled that. [whateverTheNetwork]: Cancel also stops replacements that wait for Wi-Fi.
     */
    private fun missing(set: KeptItem, whateverTheNetwork: Boolean = false): List<String> {
        val queue = set.key == DownloadLines.QUEUE_KEY
        val pending = if (queue) set.trackIds.toSet() else cache.pending(set.key)
        val replaces = replacesNow() || whateverTheNetwork
        val seen = HashSet<String>()
        return set.tracks.mapNotNull { track ->
            // An incomplete track failed for this run: not pending, so the pass neither spins on it nor holds a queue download open.
            if (!seen.add(track.id) || prefetch.unavailable(track.id) || prefetch.isIncomplete(track.id)) return@mapNotNull null
            val due = when (cache.kind(track.id)) {
                null -> track.id in pending
                StreamFormat.OPUS_128 -> replaces && track.id !in replacementsCancelled && !(queue && track.id in queueFetched)
                StreamFormat.RAW -> false
            }
            track.id.takeIf { due }
        }
    }

    private fun replacesNow() = CacheFormatPolicy.replacesOpus(network.value)

    /** The download work in its order: the newest add (or queue download) first. */
    private fun work(): List<KeptItem> = (cache.addedSets + listOfNotNull(queueDownload)).sortedByDescending { it.addedAt }

    /** Recomputes [downloads] (memory only). */
    @Synchronized private fun publishDownloads() {
        if (closed) return
        val missing = work().associate { it.key to missing(it) }.filterValues { it.isNotEmpty() }
        val pending = missing.values.flatten().toSet().size
        if (pending == 0) { runDone = 0; stalled = false }
        val currentKey = downloading.value?.takeIf { it in missing } ?: work().firstOrNull { it.key in missing }?.key
        val current = work().firstOrNull { it.key == currentKey }
        val queue = queueDownload?.let { q -> QueueDownload(q.trackIds.size, q.trackIds.count(cache::contains)) }
        // Waiting: no pending work may run on this network (a queue load runs on any network, kept sets on Wi-Fi only).
        val waiting = pending > 0 && missing.keys.none { networkAllows(it) }
        downloads.value = DownloadState(runDone, pending, currentKey, current?.let(::title), missing.keys,
            waiting, stalled && pending > 0, queue, paused = pending > 0 && !waiting && !transfersAllowed.value,
            coversPending = keptCoversDue && canTransfer(), coversDue = keptCoversDue)
    }

    /** The name the notification shows for a set: its title, else its album's name. */
    private fun title(set: KeptItem): String = set.title ?: when {
        set.key == DownloadLines.QUEUE_KEY -> "Queue"
        set.key.startsWith(PLAYLIST) -> "Playlist"
        else -> set.tracks.firstNotNullOfOrNull { it.meta?.album?.takeIf(String::isNotBlank) } ?: "Album"
    }

    /**
     * A verdict no older than [PING_MS] before expiry or downloads act on it. The shared state probes only when no
     * answer or probe is that recent and never without a network; offline it already runs its schedule.
     */
    private suspend fun checkReachability() {
        if (!reachability.online.value) {
            val now = nowMs()
            if (skippedPingAt.let { it == null || now - it >= PING_MS }) {
                skippedPingAt = now; reachability.gate?.skipped(dev.coda.core.connectivity.ServerGate.Kind.PING)
            }
        }
        reachability.refresh(PING_MS)
        checked = true
        observe(reachability.state.value)
    }

    /** The shared state to [serverReachable] and, once it is a verdict, to the cache (expiry only while reachable). */
    private fun observe(state: dev.coda.core.connectivity.Reachability.State) {
        if (closed) return
        reachable.value = state.reachable
        if (checked && state.known) cache.serverReachable(state.reachable)
    }

    /**
     * Opening the sign-out dialog: a fresh, bounded check (the shared state's probe times out) in the background, ignoring
     * the 5-minute spacing. [serverReachable] updates when it finishes; nothing waits for it.
     */
    fun recheckReachability() {
        if (closed) return
        scope.launch(Dispatchers.IO) { reachability.refresh(0); observe(reachability.state.value) }
    }

    /** A download stored: the server answered (the stream request already told the shared state; this is for a direct caller). */
    private fun setReachable(ok: Boolean) { if (ok) reachability.succeeded() }

    private suspend fun CoroutineScope.downloadPass() {
        val (pass, sets) = mutex.withLock { generation to work() }
        prefetch.updateWindow(sets.flatMap { it.trackIds })
        // Not on proper Wi-Fi (mobile data, a hotspot, offline): every add's downloads wait, without asking; a
        // "Load for offline" still runs on mobile data or a hotspot, only without a network it waits too.
        // A queue that is all on the phone is done whatever the network (no transfer is needed to finish it).
        if (sets.none { canTransfer(it.key) }) {
            // Off Wi-Fi the kept covers get their picture-cache copies only (no fetch).
            storeCovers()
            // A kept album removed off Wi-Fi, or covers found in the picture cache, are no longer due.
            keptCoversDue = coverSource != null && cache.covers.keptMissing(cache.keptCoverIds(), coversTried)
            passHadWork = false; mutex.withLock { finishQueueDownloadIfDone(pass) }; publishDownloads(); return
        }
        var hadWork = false
        var storedAny = false
        // Known before the last audio download publishes "nothing pending", so the job waits for the covers.
        keptCoversDue = cache.covers.keptMissing(cache.keptCoverIds(), coversTried)
        // The newest add first, in its own order.
        for (set in sets) {
            // Memory-only checks first: a track that is cached, or that left the set's downloads (aged out, evicted,
            // deleted), costs neither the lock nor a space query.
            val candidates = candidates(set)
            if (candidates.isEmpty()) continue
            hadWork = true
            val members = set.trackIds.toHashSet()
            for (track in candidates) {
                val pending = mutex.withLock { start(pass, set, members, track) } ?: continue
                publishDownloads()
                val result = try { pending.await() } catch (e: CancellationException) { currentCoroutineContext().ensureActive(); Prefetcher.Result() }
                finally { transfer = null }
                if (result.stored) {
                    setReachable(true); storedAny = true
                    if (set.key == DownloadLines.QUEUE_KEY) queueFetched = queueFetched + track.id
                    // A replaced Opus copy was counted as pending too.
                    synchronized(this@OfflineLibrary) { runDone++ }
                }
                revision.value++
                publishDownloads()
                // No room for this one: the rest of the set waits for the next pass instead of asking again per track.
                if (result.budgetReached) break
            }
        }
        downloading.value = null
        // The queue download is one-shot: once nothing of it is missing, it is done.
        mutex.withLock { finishQueueDownloadIfDone(pass) }
        // Kept covers after the audio (never ahead of a download), at most [COVERS_PER_PASS] fetches per pass.
        val coversLeft = storeCovers()
        // Also true when the fill was stopped (job stopped, Wi-Fi lost): a cut-short fetch is not counted as tried.
        keptCoversDue = coverSource != null && cache.covers.keptMissing(cache.keptCoverIds(), coversTried)
        passHadWork = hadWork || coversLeft
        stalled = hadWork && !storedAny
        publishDownloads()
    }

    /**
     * Completes the 472 and 1440 copies of kept and played albums in the durable store: old picture-cache
     * copies always; a fetch for a kept cover only while [canTransfer] (proper Wi-Fi, transfers allowed: during a keep), for
     * a played album only while the screen is on too (no artwork fetches with the screen off beyond the current and
     * next cover). Asked before every fetch. It runs as [transfer], so leaving Wi-Fi, the job stopping, Cancel and Delete
     * now cancel it like an audio download. True: fetches are left.
     */
    private suspend fun CoroutineScope.storeCovers(): Boolean {
        val source = coverSource ?: return false
        val ids = cache.keptCoverIds()
        val played = cache.covers.played()
        if (ids.isEmpty() && played.isEmpty()) return false
        val before = cache.covers.sizeBytes()
        val job = async {
            try { cache.covers.fill(ids, played, source, { canTransfer() }, { canTransfer() && screenOn.value }, coversTried, COVERS_PER_PASS,
                { id, size, bytes -> cache.putCover(id, size, bytes) }) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { log("Cover store fill failed: ${e.javaClass.simpleName}"); false }
        }
        transfer = job
        val left = try { job.await() } catch (e: CancellationException) { currentCoroutineContext().ensureActive(); false }
            finally { transfer = null }
        if (cache.covers.sizeBytes() != before) publish()
        return left
    }

    /** Under the mutex: the one-shot queue download ends once nothing of it is missing (also when nothing was ever missing). */
    private fun finishQueueDownloadIfDone(pass: Long) {
        queueDownload?.let { if (pass == generation && missing(it).isEmpty()) cache.endQueueDownload() }
    }

    /** The tracks of [set] to transfer now, in the set's order: [missing], or nothing while transfers are held. */
    private fun candidates(set: KeptItem): List<KeptTrack> {
        if (!canTransfer(set.key)) return emptyList()
        val due = missing(set).toHashSet()
        return set.tracks.filter { it.id in due }.distinctBy { it.id }
    }

    /** Under the mutex: the next transfer of this pass, or null when there's nothing to do for this track now. */
    private fun CoroutineScope.start(pass: Long, set: KeptItem, members: Set<String>, track: KeptTrack): Deferred<Prefetcher.Result>? {
        // The set's tracks can't change within a pass: an add, re-sync or delete starts a new generation.
        val queue = set.key == DownloadLines.QUEUE_KEY
        if (pass != generation || !(if (queue) queueDownload?.key == set.key else cache.isAdded(set.key))) return null
        val net = network.value
        if (!canTransfer(set.key, net)) return null
        if (queue) cache.adoptLegacy(track.id, track.format, track.meta)
        else if (cache.kind(track.id) == null) {
            // Aged out, evicted or deleted since it was cached: not fetched again until the set is added again.
            if (track.id !in cache.pending(set.key)) return null
            cache.adoptLegacy(track.id, track.format, track.meta)
        }
        // Each track's format follows the network at the moment it downloads; on Wi-Fi an Opus copy counts as missing.
        val format = CacheFormatPolicy.download(cache.kind(track.id), net, track.format) ?: return null
        downloading.value = set.key
        return async(start = CoroutineStart.LAZY) {
            // A queue download is read-ahead: dated now, on the heard-once clock, and it pins nothing.
            prefetch.fetchResult(cache, Prefetcher.Request(track.id, format, track.format, track.meta,
                addedAt = if (queue) null else set.addedAt, protect = members, window = false), remote)
        }.also {
            transfer = it; it.start()
            // A switch away from Wi-Fi right before [transfer] was set: the network collector may have missed it.
            if (!canTransfer(set.key)) it.cancel()
        }
    }

    /** An added set has downloads waiting for proper Wi-Fi (mobile data, a hotspot or offline now). */
    fun waitingForWifi(key: String) = !CacheFormatPolicy.addsDownload(network.value) && cache.isAdded(key) && cache.pending(key).isNotEmpty()

    fun status(key: String?, trackIds: Collection<String>): ItemStatus {
        val status = cache.status(key, trackIds)
        return status.copy(downloading = key != null && downloading.value == key && status.pendingTracks > 0)
    }

    /**
     * "Keep offline" / "Keep queue offline". Asks (via the result) for "Make room?" when the add can't fit above the
     * reserve; a set that can't fit at all downloads nothing. On [AddResult.Started] the set is renewed (30 days for
     * every track) and downloads in the background, only on proper Wi-Fi: on mobile data or a hotspot
     * it never asks, the result says [AddResult.Started.waitsForWifi] and the downloads start by themselves on Wi-Fi.
     * The size is planned for Wi-Fi, where the downloads will run.
     *
     * [makeRoom] is the "Make room?" the user confirmed. It covers deleting only files it listed, at most its bytes;
     * when storage or the cache changed so that the add needs anything else, the add asks again. The deletion itself
     * re-checks every file atomically; if one changed in the meantime, nothing is deleted and the add asks again.
     */
    suspend fun add(item: KeptItem, makeRoom: AddResult.NeedsRoom? = null): AddResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val net = CacheFormatPolicy.ADD_NETWORK
            var confirmation = makeRoom
            var plan = cache.planAdd(item, net)
            while (true) {
                val confirmed = confirmation != null && plan.evictBytes <= confirmation.bytes && confirmation.victims.containsAll(plan.evict) &&
                    confirmation.items.containsAll(plan.affectedSets)
                val result = when {
                    plan.missingBytes > 0 -> AddResult.TooBig(plan.missingBytes, cache.lineBytes())
                    plan.evict.isNotEmpty() && !confirmed ->
                        AddResult.NeedsRoom(plan.evictBytes, plan.needBytes, plan.affectedSets, plan.otherTracks, plan.evict, plan.titles)
                    else -> null
                }
                if (result != null) return@withLock result
                if (plan.evict.isEmpty() || cache.evictExactly(plan.evict, plan.affectedSets)) {
                    break
                }
                // A listed file changed between planning and deleting: plan again, and ask unless nothing must go now.
                confirmation = null
                plan = cache.planAdd(item, net)
            }
            generation++
            // One index write: the set, its pending tracks and its cancelled replacements (cleared again).
            cache.renew(item)
            onUserWork()
            publish()
            wake.trySend(Unit)
            AddResult.Started(plan.format, plan.needBytes, waitsForWifi = plan.needBytes > 0 && !CacheFormatPolicy.addsDownload(network.value))
        }
    }

    /**
     * Background re-sync of an added item's track list (its page was opened with fresh server data). Never asks.
     * An added playlist is also renewed like an add: 30 days from now for the set and its tracks;
     * new tracks get that date. False when not added.
     */
    suspend fun resync(item: KeptItem): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val old = cache.addedSets.firstOrNull { it.key == item.key }
            val renew = item.key.startsWith(PLAYLIST)
            cache.resync(item, renew).also {
                if (!it) return@also
                // A changed list or a renewal invalidates the running pass: its set snapshot holds the old tracks and
                // date. A download already running takes the renewed date when it commits (TrackCache.commit).
                if (old?.tracks != item.tracks || renew) { generation++; publish(); wake.trySend(Unit) }
            }
        }
    }

    /**
     * Prefer offline switched off for [key]: the set leaves (its pending downloads stop), nothing is deleted, and
     * its tracks become ordinary music with a fresh Heard once from now ([TrackCache.stopKeeping]: gentle).
     */
    suspend fun stopKeeping(key: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            generation++
            if (downloading.value == key) transfer?.cancelAndJoin()
            // A failed save leaves the set kept (TrackCache.stopKeeping); its stopped downloads resume at the wake.
            try { if (cache.stopKeeping(key)) publish() } finally { wake.trySend(Unit) }
        }
    }

    /**
     * "Load for offline": fetches every track of [tracks] that isn't on the phone, as read-ahead (no kept set,
     * the normal expiry). It starts at once on any network, mobile data too, without asking; each
     * track in the format of the network it downloads on ([CacheFormatPolicy.download]: Opus 128 on mobile for lossless
     * or > 320 kbit/s, the original on Wi-Fi). It takes the queue as it is now; replaces a running queue download.
     * True when it waits (no network now); false when it runs or nothing is missing (done at once).
     */
    suspend fun downloadQueue(tracks: List<KeptTrack>): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            generation++
            if (downloading.value == DownloadLines.QUEUE_KEY) transfer?.cancelAndJoin()
            val item = KeptItem(DownloadLines.QUEUE_KEY, tracks.distinctBy { it.id }, "Queue", cache.now())
            // Every target gets a fresh Heard once time; tracks already on the phone now (in the same index
            // write as the queue download), missing ones at commit.
            cache.startQueueDownload(item)
            queueFetched = emptySet()
            finishQueueDownloadIfDone(generation)
            onUserWork()
            publish()
            wake.trySend(Unit)
            queueDownload != null && !networkAllows(DownloadLines.QUEUE_KEY)
        }
    }

    /** Stops the queue download (the × in the queue ⋮): what is on the phone stays. */
    suspend fun stopQueueDownload() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (queueDownload == null) return@withLock
            generation++
            if (downloading.value == DownloadLines.QUEUE_KEY) transfer?.cancelAndJoin()
            cache.endQueueDownload()
            queueFetched = emptySet()
            publish()
        }
    }

    /**
     * Notification Cancel: every pending download stops (kept sets and the queue download). What is on the phone
     * stays as ordinary cached music; a kept set that is then incomplete loses its mark (it looks not kept, so it is not kept) and downloads again only when it is kept again.
     */
    suspend fun cancelDownloads() = withContext(Dispatchers.IO) {
        mutex.withLock {
            generation++
            transfer?.cancelAndJoin()
            // One definition of work: Opus → original replacements are work too, so Cancel stops them (until the set is
            // added again).
            val replacements = work().flatMap { missing(it, whateverTheNetwork = true) }.filter { cache.kind(it) == StreamFormat.OPUS_128 }
            // One index write: the cancelled replacements, no pending tracks, no queue download, the lapsed marks.
            cache.cancelDownloads(replacements)
            queueFetched = emptySet()
            synchronized(this@OfflineLibrary) { runDone = 0 }
            publish()
        }
    }

    /** The snackbar for [removedForSpace] was shown (delivery only; the record stays until [acknowledgeRemoved]). */
    suspend fun snackbarShown() = withContext(Dispatchers.IO) { cache.snackbarShown(); publish() }

    /** OK on the Music storage notice line: the record goes. */
    suspend fun acknowledgeRemoved() = withContext(Dispatchers.IO) { cache.acknowledgeRemoved(); publish() }

    /** Wakes the background pass (tests, network or settings changes, the app coming to the front). */
    fun poke() { wake.trySend(Unit) }

    /**
     * The session ended: nothing of this library is published any more (the transfer job and the notification see no
     * work), whatever the persisted state holds. The caller cancels the library's scope.
     */
    @Synchronized fun shutdown() {
        closed = true
        transfer?.cancel()
        downloads.value = DownloadState()
    }

    companion object {
        /** A preview grew materially: a kept set it didn't name, or more than max(5 %, 100 MB) more bytes. */
        fun changedMaterially(shown: CachePlanner.Plan, now: CachePlanner.Plan): Boolean {
            val names = shown.keptSetsGone.map { it.key }.toSet()
            return now.keptSetsGone.any { it.key !in names } || now.bytes > shown.bytes + maxOf(shown.bytes / 20, 100_000_000L)
        }

        /** Key prefix of an added playlist (re-synced and renewed when its page is opened). */
        const val PLAYLIST = "playlist/"
        private const val PASS_MS = 1000L
        /** Cover fetches per download pass (an upgrade with many kept albums fills over several passes). */
        internal const val COVERS_PER_PASS = 20
        private const val HOUSEKEEPING_MS = 60_000L
        private const val SCREEN_OFF_MS = 5 * 60_000L
        private const val FALLBACK_MS = 15 * 60_000L
        private const val EVENT_SETTLE_MS = 2_000L
        private const val PING_MS = 5 * 60_000L
    }
}
