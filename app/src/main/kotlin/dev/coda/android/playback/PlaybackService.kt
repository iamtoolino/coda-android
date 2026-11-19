package dev.coda.android.playback

import android.content.Intent
import android.app.PendingIntent
import dev.coda.android.MainActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import dev.coda.core.playback.*
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import java.io.IOException

/** App/session composition seam; the library/session owner installs these when authenticated. */
object PlaybackDependencies {
    var remoteStreams: RemoteStreamResolver = RemoteStreamResolver { throw IOException("No active session") }
    /**
     * The resolver the service's engine uses. It reads [remoteStreams] at each call (a later sign-in applies) and
     * forwards every member: a SAM wrapper around `resolve` alone dropped `supportsTranscodeOffset`, so the shipping
     * engine never asked for a transcode offset.
     */
    val engineStreams: RemoteStreamResolver = object : RemoteStreamResolver {
        override fun resolve(source: dev.coda.core.playback.TrackSource.Remote) = remoteStreams.resolve(source)
        override fun supportsTranscodeOffset() = remoteStreams.supportsTranscodeOffset()
        override fun knownTranscodeOffset() = remoteStreams.knownTranscodeOffset()
    }
    /** The engine exactly as [PlaybackService] builds it (Mp2bOffsetResumeDeviceTest drives this one). */
    @UnstableApi
    fun newEngine(context: android.content.Context) = ExoAudioEngine(context, engineStreams)
    var resolveEntry: (MediaItem) -> QueueEntry = { item ->
        require(item.mediaId.startsWith("song/"))
        QueueEntry(item.mediaId.removePrefix("song/"),
            id = item.mediaMetadata.extras?.getString("queueEntryId")?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() } ?: java.util.UUID.randomUUID(),
            albumRating = item.mediaMetadata.extras?.takeIf { it.containsKey("albumRating") }?.getInt("albumRating"),
            formatName = item.mediaMetadata.extras?.getString("formatName"),
            bitRate = item.mediaMetadata.extras?.takeIf { it.containsKey("bitRate") }?.getInt("bitRate"), title = item.mediaMetadata.title?.toString().orEmpty(),
            artist = item.mediaMetadata.artist?.toString().orEmpty(), album = item.mediaMetadata.albumTitle?.toString().orEmpty(),
            durationMs = item.mediaMetadata.durationMs ?: 0,
            year = item.mediaMetadata.releaseYear, albumArtist = item.mediaMetadata.albumArtist?.toString(),
            albumId = item.mediaMetadata.extras?.getString("albumId"),
            replayGain = item.mediaMetadata.extras?.readReplayGain(),
            trackNumber = item.mediaMetadata.trackNumber, discNumber = item.mediaMetadata.discNumber,
            coverArtId = item.mediaMetadata.extras?.getString("coverArtId"),
            albumContinuationId = item.mediaMetadata.extras?.getString("albumContinuationId"),
            startToken = item.mediaMetadata.extras?.takeIf { it.containsKey(AutoLibraryCallback.START_TOKEN) }?.getLong(AutoLibraryCallback.START_TOKEN))
    }
    var eventSinks: List<suspend (PlaybackEvent) -> Unit> = emptyList()
}

@UnstableApi
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    // Safety net only: workers handle their own errors. Class name only; messages may contain URLs.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error ->
        android.util.Log.e("CodaPlayback", "Playback service task failed: ${error.javaClass.simpleName}")
    })
    private var dispatcher: PlaybackEventDispatcher? = null
    private var guard: AudioSessionGuard? = null
    private var network: dev.coda.android.net.NetworkMonitor? = null
    private var handoff: dev.coda.core.sync.QueueHandoffCoordinator? = null
    private val app get() = application as dev.coda.android.CodaApp
    /**
     * An activity of the app is started, i.e. its UI can be on screen (ProcessLifecycleOwner; stops ~700 ms
     * after the last activity, also when the screen turns off). Session extras, fast position samples, the waveform
     * poll and the 1 s read-ahead poll serve that UI only; without it they stop or slow down.
     */
    private val uiVisible = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val lifecycleObserver = androidx.lifecycle.LifecycleEventObserver { _, event ->
        if (event == androidx.lifecycle.Lifecycle.Event.ON_START) { uiVisible.value = true; handoff?.setForeground(true) }
        if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) { uiVisible.value = false; handoff?.setForeground(false) }
    }
    // The screen state has one source (QuietPlayback), shared with the scrobbles and the session covers.
    private val screenOn get() = app.quietPlayback.screenOn.value
    // Application scope so exit I/O outlives the service; the coordinator serializes it with its own writes.
    private fun saveOnExit() { handoff?.terminationSave()?.let { save -> app.scope.launch { withTimeoutOrNull(5_000) { save() } } } }

    /** A transient focus loss will resume on gain ([AudioFocusPolicy]). */
    private val transientHold = kotlinx.coroutines.flow.MutableStateFlow(false)
    private var foregroundRequired: Boolean? = null
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        dev.coda.android.diag.BatteryLog.count(dev.coda.android.diag.BatteryIds.NOTIFICATION_UPDATE)
        if (foregroundRequired != startInForegroundRequired) {
            foregroundRequired = startInForegroundRequired
            dev.coda.android.diag.BatteryLog.event(dev.coda.android.diag.BatteryIds.EV_FOREGROUND, if (startInForegroundRequired) 1 else 0)
        }
        super.onUpdateNotification(session, startInForegroundRequired)
    }

    /**
     * Leave the foreground as soon as deep sleep has released the player, not when Media3's uptime timeout fires.
     * Media3 1.11.1 passes this method's boolean on to `MediaNotificationManager.updateNotification`, which calls
     * `startForeground` or `notify` + `stopForeground(false)` with it; the boolean of `onUpdateNotification(session, Boolean)`
     * is not used. Play (playWhenReady) brings the foreground back through Media3's own path.
     */
    private var deepSleep: DeepSleep? = null
    override fun onUpdateNotificationAsync(session: MediaSession, startInForegroundRequired: Boolean): ListenableFuture<Void> {
        val asleep = deepSleep?.asleep?.value == true
        return super.onUpdateNotificationAsync(session,
            BackgroundWorkPolicy.runsInForeground(startInForegroundRequired, asleep, session.player.playWhenReady))
    }

    /** The deep-sleep grace by wall time; a non-wakeup alarm needs no permission and fires at the next CPU wake. */
    private val wallAlarm = WallAlarm { afterMs, onFire ->
        val alarms = getSystemService(android.app.AlarmManager::class.java)
        val listener = android.app.AlarmManager.OnAlarmListener { onFire() }
        alarms.set(android.app.AlarmManager.ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime() + afterMs,
            "coda:deep-sleep", listener, android.os.Handler(android.os.Looper.getMainLooper()))
        return@WallAlarm { alarms.cancel(listener) }
    }

    override fun onCreate() {
        super.onCreate()
        // Media3 ends the foreground service this long after playback stops being "engaged" (paused or
        // stopped); its default is DEFAULT_FOREGROUND_SERVICE_TIMEOUT_MS, 10 min.
        setForegroundServiceTimeoutMs(BackgroundWorkPolicy.DEEP_SLEEP_GRACE_MS)
        dev.coda.android.diag.BatteryLog.event(dev.coda.android.diag.BatteryIds.EV_SERVICE_CREATE)
        val monitor = dev.coda.android.net.NetworkMonitor(this).also { network = it }
        uiVisible.value = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        val engine = PlaybackDependencies.newEngine(this)
        scope.launch { uiVisible.collect { engine.setFastPositionSamples(it) } }
        val controller = PlaybackController(engine,
            sourceResolver = dev.coda.core.connectivity.CachedSourceResolver({ app.trackCache }, { monitor.state.value },
                { app.reachability.state.value.reachable }),
            networkAvailable = { monitor.state.value.available }, nowMs = { android.os.SystemClock.elapsedRealtime() },
            sessionGeneration = { (app.sessions.state.value as? dev.coda.android.session.SessionState.Connected)?.session?.token })
        val prefetcher = dev.coda.android.cache.Prefetcher(dev.coda.android.net.HttpClientFactory.client)
        val cacheWorker = dev.coda.android.cache.QueueCacheWorker(prefetcher, { monitor.state.value },
            { app.cacheSettings.tracksAhead }) { source -> PlaybackDependencies.engineStreams.resolve(source).toString() }
        val prefetchWake = Channel<Unit>(Channel.CONFLATED)
        // Main thread, in memory only: window pins follow the queue at once, not the download worker, and
        // leases taken at hand-off are released only once the engine can no longer open those files.
        fun protectCachedFiles() {
            val cache = app.trackCache ?: return
            val state = controller.state.value
            cache.pin(cacheWorker.pins(state.queue, state.currentIndex))
            cache.retainLeases(controller.engineSources.filterIsInstance<TrackSource.CachedFile>().map { it.path })
        }
        scope.launch { controller.state.collect { protectCachedFiles() } }
        // Playback state markers for the battery log (debug and preview builds; a no-op in release).
        if (dev.coda.android.diag.BatteryLog.ENABLED) scope.launch {
            controller.state.collect { s ->
                dev.coda.android.diag.BatteryLog.playback(when {
                    s.waiting != null -> dev.coda.android.diag.BatteryIds.PLAYBACK_WAITING
                    s.isPlaying && s.isBuffering -> dev.coda.android.diag.BatteryIds.PLAYBACK_BUFFERING
                    s.isPlaying -> dev.coda.android.diag.BatteryIds.PLAYBACK_PLAYING
                    s.isPrepared || s.currentIndex != null -> dev.coda.android.diag.BatteryIds.PLAYBACK_PAUSED
                    else -> dev.coda.android.diag.BatteryIds.PLAYBACK_IDLE
                })
            }
        }
        // Playing or buffering (playWhenReady and READY or BUFFERING) keeps the cache maintenance ticker running.
        scope.launch { controller.state.collect { app.playbackActive.value = it.isPlaying && (it.isActuallyPlaying || it.isBuffering) } }
        // "played" means the track became current (not the gapless pre-load of the next file); CA-3 counts it.
        scope.launch {
            var played: OccurrenceId? = null
            controller.state.collect { state ->
                val occurrence = state.currentOccurrence ?: return@collect
                if (occurrence.item.occurrence == played) return@collect
                played = occurrence.item.occurrence
                // On ART-1: off the main thread (the record and cover locks are also held across disk writes).
                val cache = app.trackCache ?: return@collect
                val meta = dev.coda.core.cache.TrackMeta.from(occurrence.entry)
                val songId = occurrence.entry.songId
                scope.launch(Dispatchers.IO) { cache.markPlayed(songId, meta) }
            }
        }
        // Only a real network switch resets the retry clock, the FR-1 rounds, the read-ahead backoff and probes a
        // paused wait; signal-strength and bandwidth updates of the same network do none of that.
        val networkSwitches = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        scope.launch {
            monitor.switches.collect {
                controller.networkChanged()
                prefetcher.networkChanged(monitor.identity)
                networkSwitches.tryEmit(Unit)
                prefetchWake.trySend(Unit)
                // The handoff refresh too; a signal or bandwidth update sends no getPlayQueue.
                if (monitor.state.value.available) handoff?.networkAvailable()
            }
        }
        scope.launch { monitor.changes.collect { prefetchWake.trySend(Unit) } }
        // Deep sleep after a pause of DEEP_SLEEP_GRACE_MS once the read-ahead has no download running.
        val readAheadBusy = kotlinx.coroutines.flow.MutableStateFlow(false)
        val deepSleep = DeepSleep(controller.state, readAheadBusy, controller::releaseIdle, transientHold = transientHold,
            wait = { wallClockDelay(it, wallAlarm) },
            // The pause save's pooled connection would otherwise stay open (server keep-alives wake the CPU)
            // until the 20 s uptime closer runs; deep sleep has already waited a minute of wall time.
            closeIdleConnections = { scope.launch(Dispatchers.IO) { dev.coda.android.net.HttpClientFactory.client.connectionPool.evictAll() } },
        ).also { this.deepSleep = it }
        scope.launch { deepSleep.run() }
        // Re-run Media3's foreground decision when deep sleep starts or ends (onUpdateNotificationAsync).
        // And unstart the service once asleep and paused (a bound UI keeps it; otherwise onDestroy runs now).
        scope.launch {
            deepSleep.asleep.drop(1).collect { asleep ->
                triggerNotificationUpdate()
                if (BackgroundWorkPolicy.stopsService(asleep, session?.player?.playWhenReady == true)) stopSelf()
            }
        }
        val probe = ConnectionProbe(controller,
            session = { (app.sessions.state.value as? dev.coda.android.session.SessionState.Connected)?.session?.let { session ->
                ConnectionProbe.Session(session.token) { session.client.ping() } } },
            unauthorized = { token -> app.sessions.unauthorized(token) },
            nowMs = { android.os.SystemClock.elapsedRealtime() })
        // The probe only acts while a wait is active (no 5/s poll otherwise). Not collectLatest: a state change
        // inside runIfDue (stop on Unauthorized) must not cancel the sign-out that follows it.
        scope.launch {
            while (isActive) {
                // A paused wait has no probe scheduled (Play, Retry or a network switch schedules one).
                if (BackgroundWorkPolicy.probePollMs(controller.state.value.waiting?.scheduled == true) == null)
                    controller.state.first { it.waiting?.scheduled == true }
                dev.coda.android.diag.BatteryLog.count(dev.coda.android.diag.BatteryIds.PROBE_LOOP)
                probe.runIfDue()
                delay(BackgroundWorkPolicy.probePollMs(true)!!)
            }
        }
        // The read-ahead worker wakes on what changes its window instead of polling every second without a UI.
        scope.launch {
            var queue: List<QueueEntry>? = null
            var index: Int? = null
            controller.state.collect { state ->
                if (state.queue === queue && state.currentIndex == index) return@collect
                queue = state.queue; index = state.currentIndex
                prefetchWake.trySend(Unit)
            }
        }
        scope.launch { app.sessions.state.collect { prefetchWake.trySend(Unit) } }
        scope.launch { uiVisible.collect { prefetchWake.trySend(Unit) } }
        scope.launch {
            while (isActive) {
                // A wait that ended (timeout, a signal-only wake) after falling asleep runs no further pass.
                // Likewise while an auto-resume wait sleeps past its lock cap; its end (or a switch) wakes it.
                ReadAheadRest.await(deepSleep.asleep, controller.state, networkSwitches)
                val cache = app.trackCache
                val state = controller.state.value
                if (cache != null && (controller.queue.isNotEmpty() || handoff?.initialQueueResolved == true)) {
                    dev.coda.android.diag.BatteryLog.count(dev.coda.android.diag.BatteryIds.CACHE_CYCLE)
                    protectCachedFiles()
                    try {
                        readAheadBusy.value = true
                        withContext(Dispatchers.IO) {
                            cacheWorker.cycle(cache, state.queue, state.currentIndex,
                                stillCurrent = { controller.state.value.let { it.queue == state.queue && it.currentIndex == state.currentIndex } },
                                // A successor prepared before its download finished plays from the file.
                                downloaded = { songId -> scope.launch { controller.trackCached(songId) } })
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        // Storage trouble must not end the worker; pending downloads keep their retry schedule.
                        android.util.Log.w("CodaPlayback", "Track cache pass failed: ${e.javaClass.simpleName}")
                    } finally { readAheadBusy.value = false }
                } else prefetcher.updateWindow(emptyList())
                // Asleep, no read-ahead pass or retry timer runs until Play (or another item) or a network switch
                // (the check at the top of the loop; after a switch one pass runs, then it sleeps again).
                if (!ReadAheadRest.rests(deepSleep.asleep.value, controller.state.value)) withTimeoutOrNull(prefetcher.retryDelayMs(BackgroundWorkPolicy.readAheadIdleMs(uiVisible.value))) { prefetchWake.receive() }
            }
        }
        // Likewise when another downloader ("Load for offline", a keep) completes the successor's copy.
        scope.launch { dev.coda.android.cache.QueueCacheWorker.followPublishedCopies(app.offlineLibrary) { controller.cacheChanged() } }
        // Separate from prefetch and Media3: one low-priority decoder, suspended around boundaries.
        scope.launch {
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor { work ->
                Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); work.run() }, "CodaWaveform")
            }
            val worker = executor.asCoroutineDispatcher()
            // One waveform per song, computed from whatever copy is first fully cached; it outlives the audio.
            // Only while the app's UI is started (screen on), the current track first, then the
            // next ones as before; nothing is decoded or precomputed with the screen off. Leaving the UI mid-decode stops it
            // (not a failure: retried when the UI returns).
            val waveformUi = kotlinx.coroutines.flow.combine(uiVisible, app.quietPlayback.screenOn) { v, on -> BackgroundWorkPolicy.waveformPollMs(v, on) != null }
            val attempted = mutableSetOf<Pair<dev.coda.core.cache.TrackCache, String>>()
            fun upcoming() = controller.queue.drop((controller.currentIndex ?: 0).coerceAtLeast(0)).take(5).map { it.songId }
            try {
                while (isActive) {
                    fun pollMs() = BackgroundWorkPolicy.waveformPollMs(uiVisible.value, screenOn)
                    if (pollMs() == null) waveformUi.first { it }
                    dev.coda.android.diag.BatteryLog.count(dev.coda.android.diag.BatteryIds.WAVEFORM_LOOP)
                    val cache = app.trackCache
                    val window = upcoming().distinct()
                    if (cache != null) {
                        withContext(worker) { cache.waveforms.load(window) }
                        // Re-check right before the decode is counted/started (no suspension in between).
                        val songId = window.takeIf { pollMs() != null }?.firstOrNull { cache.contains(it) && !cache.waveforms.has(it) && (cache to it) !in attempted }
                        val file = songId?.let { cache.resolve(it) }
                        if (songId != null && file != null) {
                            try {
                                dev.coda.android.diag.BatteryLog.count(dev.coda.android.diag.BatteryIds.WAVEFORM_DECODES)
                                val peaks = withContext(worker) {
                                    dev.coda.android.waveform.WaveformDecoder().decode(file) {
                                        while (true) {
                                            val allowed = withContext(Dispatchers.Main.immediate) {
                                                check(app.trackCache === cache && cache.contains(songId) && songId in upcoming())
                                                if (BackgroundWorkPolicy.waveformPollMs(uiVisible.value, screenOn) == null) throw WaveformDeferred()
                                                dev.coda.core.waveform.waveformDecodeAllowed(controller.isPlaying, controller.positionMs, controller.durationMs)
                                            }
                                            if (allowed) break
                                            delay(250)
                                        }
                                    }
                                }
                                withContext(worker) { cache.storeWaveform(songId, peaks) }
                            } catch (e: CancellationException) { throw e }
                            catch (_: WaveformDeferred) { }
                            catch (_: Exception) {
                                if (app.trackCache === cache && cache.contains(songId) && songId in upcoming()) attempted += cache to songId
                            }
                        }
                    }
                    // A UI that starts cuts a slow wait short.
                    if (pollMs() != null) delay(500) else withTimeoutOrNull(0) { waveformUi.first { it } }
                }
            } finally { worker.close() }
        }
        this.controller = controller
        PlaybackDiagnostics.onControllerCreated(controller)
        // The Now Playing format chip reads this (same process, main thread); nothing leaves the app.
        AudioDetailsHub.provider = provider@{
            val occurrence = controller.state.value.currentOccurrence ?: return@provider null
            AudioDetailsInput(serverSuffix = occurrence.entry.formatName, source = occurrence.item.source,
                engine = controller.engineDetails(), serverBitRateKbps = occurrence.entry.bitRate)
        }
        // The controller closes its event channel on release. App scope lets the fan-out drain
        // the final occurrence invalidation after the service scope has been cancelled.
        dispatcher = PlaybackEventDispatcher(controller, (application as dev.coda.android.CodaApp).scope,
            listOf({ event -> PlaybackDependencies.eventSinks.forEach { it(event) } }))
        // Cover bytes for the current item's metadata (SystemUI/Auto now-playing read it without a provider grant).
        val coverFetchers = HashMap<java.util.UUID, dev.coda.android.artwork.CoverArtFetcher>()
        val player = CodaPlayer(controller, { PlaybackDependencies.resolveEntry(it) }, { id ->
            val session = (app.sessions.state.value as? dev.coda.android.session.SessionState.Connected)?.session
            session?.let { s ->
                val fetcher = coverFetchers.getOrPut(s.token) { coverFetchers.clear(); dev.coda.android.artwork.CoverArtFetcher(this, s) }
                SessionCover.select(id, fetcher::bytes).takeIf { app.sessions.isCurrent(s.token) }
            }
        }, { item ->
            // Auto requests that raced a sign-out or account switch never reach the queue.
            dev.coda.android.session.admitsSessionTag(item.mediaMetadata.extras?.getString(AutoLibraryCallback.SESSION_TAG), app.sessions::isCurrent)
        }, coverOffsets = { state ->
            // The play intent of the state being published: a buffering track change must not flip back to the neighbours.
            BackgroundWorkPolicy.sessionCoverOffsets(screenOn, state.isPlaying) },
            seekable = { state -> currentItemSeekable(state, { app.trackCache?.contains(it) == true }, engine.seeksInsideTranscodes()) })
        // A copy completed by any downloader can make the current transcode seekable for every session client.
        scope.launch { dev.coda.android.cache.QueueCacheWorker.followPublishedCopies(app.offlineLibrary) { player.refreshSeekCapability() } }
        scope.launch {
            var token: java.util.UUID? = null
            var offers: Job? = null
            app.sessions.state.collect { state ->
                val connected = (state as? dev.coda.android.session.SessionState.Connected)?.session
                if (state == dev.coda.android.session.SessionState.SignedOut) {
                    // The local queue copy is deleted by CodaApp on sign-out (OwnQueueFiles.forget fences late writers).
                    // The coordinator is closed first so that clearing the player cannot queue a save.
                    handoff?.close(); handoff = null
                    player.stop(); player.clearMediaItems()
                } else if (state is dev.coda.android.session.SessionState.NeedsLogin) player.stop()
                if (connected?.token != token) {
                    handoff?.close(); handoff = null; offers?.cancel(); app.handoffOffer.value = null; app.handoffKeepsMine.value = null
                    if (token != null && connected != null) { player.stop(); player.clearMediaItems() }
                    token = connected?.token
                    if (connected != null) {
                        val ownQueue = OwnQueueFiles.store(app, connected)
                        handoff = dev.coda.core.sync.QueueHandoffCoordinator(controller, scope,
                            connected.client.configuration.clientName, { dev.coda.android.diag.BatteryLog.count(dev.coda.android.diag.BatteryIds.QUEUE_LOADS); connected.client.getPlayQueue() }, { snapshot ->
                                dev.coda.android.diag.BatteryLog.count(dev.coda.android.diag.BatteryIds.QUEUE_SAVES)
                                connected.client.savePlayQueue(snapshot.songIDs, snapshot.currentIndex, snapshot.positionMilliseconds)
                            }, { copy -> withContext(Dispatchers.IO) { ownQueue.write(copy) } }, screenOn = { screenOn },
                            // Batched scrobbles go out right behind each queue save (the radio is up anyway).
                            onSaved = { app.scrobbles.coordinator.flushBatch() }, serverGate = app.serverGate,
                            loadLocal = { withContext(Dispatchers.IO) { ownQueue.read() } }).also { coordinator ->
                                coordinator.setForeground(androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
                                offers = scope.launch { coordinator.offer.collect {
                                    app.handoffKeepsMine.value = it?.handoffIdentity?.takeIf(coordinator::offerKeepsMine)
                                    app.handoffOffer.value = it
                                } }
                            }
                    }
                }
            }
        }
        scope.launch {
            controller.state.collect { state ->
                if (state.failure == PlaybackFailure.Unauthorized) {
                    val connected = app.sessions.state.value as? dev.coda.android.session.SessionState.Connected
                    connected?.let { app.sessions.unauthorized(it.session.token) }
                }
            }
        }
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
        // While a call (a transient focus loss) will resume playback, Media3 keeps its default foreground timeout
        // (10 min, as before BAT-6) instead of the 1 min one, and deep sleep waits; set before the suspension is published.
        guard = AudioSessionGuard(this, controller, scope) { pending ->
            setForegroundServiceTimeoutMs(if (pending) DEFAULT_FOREGROUND_SERVICE_TIMEOUT_MS else BackgroundWorkPolicy.DEEP_SLEEP_GRACE_MS)
            transientHold.value = pending
        }
        // The ReplayGain setting reaches the controller here; an explicit toggle applies to the current item too.
        scope.launch { (application as dev.coda.android.CodaApp).settings.replayGainEnabled.collect { controller.setReplayGainEnabled(it) } }
        // AutoLibraryCallback authenticates controllers and serves the Auto tree; the app's own custom commands are added here.
        // No custom layout or media button preferences, so the car shows only the standard transport.
        session = MediaLibrarySession.Builder(this, player, object : AutoLibraryCallback(app, scope, {
            controller.queue.takeIf { it.isNotEmpty() }?.let { queue ->
                dev.coda.core.sync.OwnQueue(queue, (controller.currentIndex ?: 0).coerceIn(queue.indices), controller.positionMs.coerceAtLeast(0))
            }
        }, { handoff?.expectStart() }) {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                val base = super.onConnect(session, controller)
                if (!base.isAccepted) return base
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailablePlayerCommands(base.availablePlayerCommands)
                    .setAvailableSessionCommands(base.availableSessionCommands.buildUpon()
                        .add(androidx.media3.session.SessionCommand(HANDOFF_CONTINUE, android.os.Bundle.EMPTY))
                        .add(androidx.media3.session.SessionCommand(HANDOFF_DISMISS, android.os.Bundle.EMPTY))
                        .add(androidx.media3.session.SessionCommand(HANDOFF_UNDO_DISMISS, android.os.Bundle.EMPTY))
                        .add(androidx.media3.session.SessionCommand(HANDOFF_REFRESH, android.os.Bundle.EMPTY))
                        .add(androidx.media3.session.SessionCommand(SHUFFLE_REMAINING, android.os.Bundle.EMPTY))
                        .add(androidx.media3.session.SessionCommand(RETRY_CONNECTION, android.os.Bundle.EMPTY)).build()).build()
            }
            override fun onCustomCommand(session: MediaSession, browser: MediaSession.ControllerInfo,
                command: androidx.media3.session.SessionCommand, args: android.os.Bundle): ListenableFuture<androidx.media3.session.SessionResult> {
                when (command.customAction) {
                    HANDOFF_CONTINUE -> handoff?.continuePlaying(args.getString("identity").orEmpty())
                    HANDOFF_DISMISS -> handoff?.dismiss(args.getString("identity").orEmpty())
                    HANDOFF_UNDO_DISMISS -> handoff?.undoDismiss(args.getString("identity").orEmpty())
                    HANDOFF_REFRESH -> handoff?.scheduleRefresh()
                }
                if (command.customAction == SHUFFLE_REMAINING) controller.shuffleRemaining()
                if (command.customAction == RETRY_CONNECTION) controller.retryNow()
                return Futures.immediateFuture(androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS))
            }
        })
            .setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        scope.launch {
            var previous: List<Any?>? = null
            var indexedCache: dev.coda.core.cache.TrackCache? = null
            var indexedRevision = -1L
            var indexedQueue = emptyList<QueueEntry>()
            var cachedSongs = emptyList<String>()
            while (isActive) {
                // Only the app's own UI reads these extras; a newly started UI gets the current set at once.
                if (BackgroundWorkPolicy.extrasPollMs(uiVisible.value) == null) uiVisible.first { BackgroundWorkPolicy.extrasPollMs(it) != null }
                dev.coda.android.diag.BatteryLog.count(dev.coda.android.diag.BatteryIds.EXTRAS_LOOP)
                val cache = app.trackCache
                val queue = controller.queue
                val revision = cache?.revision ?: 0L
                if (cache !== indexedCache || revision != indexedRevision || queue != indexedQueue) {
                    cachedSongs = withContext(Dispatchers.Default) {
                        queue.filter { cache?.contains(it.songId) == true || it.source is TrackSource.CachedFile }.map { it.songId }
                    }
                    indexedCache = cache; indexedRevision = revision; indexedQueue = queue
                }
                val waiting = controller.recovery.waiting
                val seconds = waiting?.takeIf { it.scheduled }?.let { ((it.retryAtMs - android.os.SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0) } ?: 0
                val source = controller.state.value.currentOccurrence?.item?.source
                val metered = dev.coda.core.connectivity.StreamFormatPolicy.metered(monitor.state.value)
                val offline = !monitor.state.value.available || waiting != null
                val format = if (source?.transcodeFormat != null) "Opus 128" else "Original"
                val seekable = SeekPolicy.isSeekable(source, controller.currentEntry?.songId in cachedSongs, engine.seeksInsideTranscodes())
                val waveform = controller.currentEntry?.songId?.let { cache?.waveform(it) }
                val signature = listOf(player.successorEntryId, waiting != null, seconds, metered, offline, seekable,
                    source is TrackSource.CachedFile, format, queueProgress(), queueWaitsForWifi(), cachedSongs, controller.currentEntry?.songId, waveform)
                if (signature != previous) {
                    player.refreshSeekCapability() // also picks up a newly known `timeOffset` capability
                    dev.coda.android.diag.BatteryLog.count(dev.coda.android.diag.BatteryIds.SESSION_EXTRAS)
                    session?.setSessionExtras(android.os.Bundle().apply {
                        putString(SUCCESSOR_ENTRY_ID, player.successorEntryId)
                        putBoolean("waiting", waiting != null); putLong("retrySeconds", seconds)
                        putBoolean(SEEKABLE, seekable)
                        putFloatArray("waveform", waveform?.toFloatArray())
                        putString("waveformSongId", controller.currentEntry?.songId)
                        putBoolean("metered", metered); putBoolean("offline", offline)
                        putBoolean("currentCached", source is TrackSource.CachedFile); putString("currentFormat", format)
                        putString("cacheProgress", queueProgress()); putBoolean(QUEUE_WAITS_FOR_WIFI, queueWaitsForWifi()); putStringArrayList("cachedSongs", ArrayList(cachedSongs))
                    })
                    previous = signature
                }
                delay(BackgroundWorkPolicy.extrasPollMs(true)!!)
            }
        }
    }

    private var controller: PlaybackController? = null
    /** "Caching 12 of 40" while "Load for offline" runs (the queue sheet shows it; DL-1 shared state). */
    private fun queueProgress(): String {
        val state = app.offlineLibrary.value?.downloads?.value ?: return ""
        val queue = state.queue ?: return ""
        return if (state.waitingForWifi) "${queue.onPhone} of ${queue.total} ready offline" else "Caching ${queue.onPhone} of ${queue.total}"
    }
    /** "Load for offline" is waiting for proper Wi-Fi (downloads run only there). */
    private fun queueWaitsForWifi(): Boolean = app.offlineLibrary.value?.downloads?.value?.let { it.queue != null && it.waitingForWifi } == true

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session
    override fun onTaskRemoved(rootIntent: Intent?) {
        saveOnExit()
        if (session?.player?.playWhenReady != true) stopSelf()
    }
    override fun onDestroy() {
        dev.coda.android.diag.BatteryLog.event(dev.coda.android.diag.BatteryIds.EV_SERVICE_DESTROY)
        dev.coda.android.diag.BatteryLog.playback(dev.coda.android.diag.BatteryIds.PLAYBACK_IDLE)
        AudioDetailsHub.provider = null
        saveOnExit()
        handoff?.close(); app.handoffOffer.value = null; app.handoffKeepsMine.value = null
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        guard?.release(); scope.cancel(); network?.close()
        app.playbackActive.value = false
        session?.let { it.release(); it.player.release() }
        dispatcher = null // collector finishes when player.release() closes controller.events
        session = null
        super.onDestroy()
    }
}

/** Session extra: may the current item be seeked (the waveform bar builds on it). */
const val SEEKABLE = "seekable"
/** Session extra: "Load for offline" has downloads waiting for proper Wi-Fi. */
const val QUEUE_WAITS_FOR_WIFI = "queueWaitsForWifi"
const val HANDOFF_CONTINUE = "dev.coda.handoff.CONTINUE"
const val HANDOFF_DISMISS = "dev.coda.handoff.DISMISS"
const val HANDOFF_REFRESH = "dev.coda.handoff.REFRESH"

const val SHUFFLE_REMAINING = "dev.coda.queue.SHUFFLE_REMAINING"

const val HANDOFF_UNDO_DISMISS = "dev.coda.handoff.UNDO_DISMISS"

/** The UI went away mid-decode; the waveform is computed when it returns (not a failed song). */
private class WaveformDeferred : Exception()
