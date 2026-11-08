package dev.coda.android

import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

import android.app.Application
import dev.coda.android.session.CredentialStore
import dev.coda.android.session.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CodaApp : Application() {
    // The instrumentation runner disables launch auto-login before Application.onCreate.
    internal var autoLoginEnabled = true
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Emits whenever a default network becomes available; screens showing an error use it to reload. */
    val networkRecovered = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** The one accent source every surface reads (prepare, then commit; see AccentResolver). */
    val accents = dev.coda.core.accent.AccentResolver(scope)
    /** Which track's waveform intro already ran; survives Now Playing being closed and reopened. */
    val waveformIntro = dev.coda.core.waveform.WaveformIntro()
    val handoffOffer = kotlinx.coroutines.flow.MutableStateFlow<dev.coda.core.subsonic.PlayQueue?>(null)
    /** The identity of the shown offer whose dismiss is "keep mine" (a reconnect conflict); null otherwise. */
    val handoffKeepsMine = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    lateinit var sessions: SessionRepository
        private set
    lateinit var settings: dev.coda.android.settings.SettingsStore
        private set
    /** Resolved servers of past sign-ins, for the sign-in dropdown. */
    lateinit var recentServers: dev.coda.android.session.RecentServers
        private set
    /** Servers that worked over https (never an http fallback for them); forgotten only with their recent entry. */
    lateinit var httpsHistory: dev.coda.android.session.HttpsHistory
        private set
    /** Plain-http servers the user agreed to sign in to without encryption; forgotten only with their recent entry. */
    lateinit var plainHttpConsent: dev.coda.android.session.PlainHttpConsent
        private set
    /** The one gate for background server requests (offline: none; server not answering: shared backoff). */
    lateinit var serverGate: dev.coda.core.connectivity.ServerGate
        private set
    /** The one connectivity state and the only reachability probe; OF-1c reads [Reachability.state]. */
    lateinit var reachability: dev.coda.core.connectivity.Reachability
        private set
    lateinit var scrobbles: dev.coda.android.scrobble.ScrobbleHost
        private set
    lateinit var albumResume: dev.coda.android.scrobble.AlbumResumeHost
        private set
    lateinit var cacheSettings: dev.coda.android.cache.CacheSettings
        private set
    /** The last committed playback colour, restored into [accents] at start so a cold start opens in it. */
    lateinit var playbackAccent: dev.coda.android.artwork.PlaybackAccentStore
        private set
    @Volatile var trackCache: dev.coda.core.cache.TrackCache? = null
        private set
    /** The one music cache's adds, housekeeping and background downloads, per signed-in session. */
    val offlineLibrary = kotlinx.coroutines.flow.MutableStateFlow<dev.coda.android.cache.OfflineLibrary?>(null)
    /** Device tests: a fake free-space source, set before sign-in. */
    @Volatile internal var freeSpaceOverride: dev.coda.core.cache.FreeSpace? = null
    /** The player is playing or buffering (published by the playback service; a paused service is idle). */
    val playbackActive = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** Screen state and "screen off and playing", the one place the background traffic asks. */
    lateinit var quietPlayback: dev.coda.android.playback.QuietPlayback
        private set
    /** Artwork fetches beyond the current and next cover need the screen on. */
    fun screenOn(): Boolean = !::quietPlayback.isInitialized || quietPlayback.screenOn.value
    /** Covers that failed are tried again once ONLINE returns with the screen on (CoverImage). */
    @Volatile var coverRetries: dev.coda.core.connectivity.CoverRetries? = null
        private set
    /**
     * Downloads transfer while the app is visible or the user-initiated transfer job runs; once the system stops
     * the job they wait for it to run again, even while the app is visible (JobService contract).
     */
    val transferGate = dev.coda.android.cache.TransferGate(scope, dev.coda.android.cache.DownloadJobService.running)
    val transfersAllowed: kotlinx.coroutines.flow.StateFlow<Boolean> get() = transferGate.allowed
    /** Coda is active, so the cache maintenance ticker runs: on screen, playing or buffering, or the download job runs. */
    private val cacheActive: kotlinx.coroutines.flow.StateFlow<Boolean> by lazy {
        combine(transferGate.visible, playbackActive, dev.coda.android.cache.DownloadJobService.running) { shown, playing, job -> shown || playing || job }
            .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)
    }
    /** The signed-in account's `library.db` (one per server + user; opened lazily off the main thread). */
    @Volatile var library: dev.coda.core.library.LibraryStore? = null
        private set
    /** Catalogue collector runs (tests: the catalogue fills after a track lands). */
    val catalogueRuns = kotlinx.coroutines.flow.MutableStateFlow(0)
    /** The result of the newest catalogue run, set before [catalogueRuns] counts it (the car reloads only on a real change). */
    @Volatile var lastCatalogueRun: dev.coda.core.library.CatalogueCollector.Result? = null
        private set
    /**
     * TD-1, tests only: how long a failed catalogue album request waits before it is asked again. Read when the collector
     * starts (at sign-in); the app never changes it from [CATALOGUE_RETRY_MS].
     */
    @Volatile internal var catalogueRetryMs: Long = CATALOGUE_RETRY_MS

    /**
     * The `library.db` store of the account [key]: the same instance while the account stays, a new one (the old one
     * closed) when it changes. Cheap and safe on the main thread (no disk access until first use). Its file lives apart
     * from the music files (`no_backup/library/<key>/`). It also holds the music cache's index; a damaged database
     * that has to be recreated reads as "no index", and the cache then adopts its audio files instead of deleting them.
     */
    @Synchronized fun libraryStore(key: String): dev.coda.core.library.LibraryStore {
        library?.takeIf { it.file.parentFile?.name == key && !it.isClosed }?.let { return it }
        // Closing may checkpoint the WAL: not on the caller's (main) thread.
        library?.let { old -> scope.launch(Dispatchers.IO) { old.close() } }
        return newLibraryStore(key).also { library = it }
    }

    /** A store on the account [key]'s file that [libraryStore] does not know (device tests use their own and close it themselves). */
    fun newLibraryStore(key: String): dev.coda.core.library.LibraryStore {
        // dataDir + no_backup is noBackupFilesDir without its main-thread exists() check (StrictMode).
        val file = java.io.File(applicationInfo.dataDir, "no_backup/library/$key/library.db")
        return dev.coda.core.library.LibraryStore(file, build = { path ->
            dev.coda.store.LibraryDatabase.open(androidx.room.Room.databaseBuilder<dev.coda.store.LibraryDatabase>(this, path),
                androidx.sqlite.driver.AndroidSQLiteDriver(), Dispatchers.IO)
        }, log = { android.util.Log.w("CodaLibrary", it) })
    }

    /** The phone keeps one account's offline storage; a verified sign-in as another account deletes the rest. */
    val accountStorage: dev.coda.core.cache.AccountStorage by lazy {
        // dataDir + no_backup is noBackupFilesDir without its main-thread exists() check (StrictMode).
        dev.coda.core.cache.AccountStorage(java.io.File(applicationInfo.dataDir, "no_backup"), java.io.File(applicationInfo.dataDir, "cache"),
            log = { android.util.Log.w("CodaAccount", it) })
    }

    /** What objects of this process hold for other accounts (run on IO by [accountStorage], before files go). */
    private val keepOnlyInProcess = dev.coda.core.cache.AccountStorage.InProcess { keep ->
        // The old account's playback stopped with its sign-out (SignedOut clears the player); drop its cache and library handles.
        // Its writers (service read-ahead, kept downloads) are fenced before the files go, so none recreates the folder.
        // An instance that is dropped is retired too (a case variant's folder stays): it must not write its index any more.
        trackCache?.takeIf { it.directory.name != keep.key }?.let { it.retire(); trackCache = null }
        closeLibraryUnless(keep.key)
        // An unreadable outbox throws now; the accent cleanup below must still run (other accounts' plays are never sent anyway).
        try { scrobbles.coordinator.retainOnly(keep.scrobbleKey) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
        if (playbackAccent.read()?.accountKey?.let { it != keep.key && it !in keep.sameAs } == true) {
            // AccentResolver is main-thread state.
            kotlinx.coroutines.withContext(Dispatchers.Main.immediate) { accents.forgetPlayback() }
            playbackAccent.clear()
        }
    }

    /** A verified sign-in (before it is published): same account keeps everything, another one deletes the rest. */
    private suspend fun keepOnlyAccountOf(session: dev.coda.android.session.Session) = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val config = session.client.configuration
        // Never delete this account's own folders: the same user typed in another letter case (Navidrome ignores case).
        val key = dev.coda.core.cache.TrackCache.serverKey(config.serverUrl, config.username)
        val folded = dev.coda.core.cache.TrackCache.serverKey(config.serverUrl, config.username.lowercase())
        val same = setOf(folded) - key
        val keep = dev.coda.core.cache.AccountStorage.Keep(key,
            dev.coda.android.scrobble.ScrobbleHost.accountKey(config.serverUrl, config.username), same, folded)
        accountStorage.signedIn(keep, keepOnlyInProcess)
    }

    @Synchronized private fun closeLibraryUnless(key: String) {
        library?.takeIf { it.file.parentFile?.name != key }?.let { old -> library = null; old.close() }
    }

    private var offlineJob: kotlinx.coroutines.Job? = null
    private var offlineToken: java.util.UUID? = null
    override fun onCreate() {
        super.onCreate()
        dev.coda.android.session.DeviceClientName.init(this)
        // First, so the kept playback colour is read on IO while the rest starts; the shell's first frame finds it in memory.
        playbackAccent = dev.coda.android.artwork.PlaybackAccentStore(this, scope).also { it.preload() }
        dev.coda.android.diag.BatteryLog.start(this) // Debug and preview builds only (no-op in release)
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                // An idle library has no timer; coming to the front is one of its wake-ups (housekeeping, expiry).
                androidx.lifecycle.Lifecycle.Event.ON_START -> { transferGate.cameToFront(); offlineLibrary.value?.poke() }
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> transferGate.setVisible(false)
                else -> Unit
            }
        })
        if (BuildConfig.DEBUG) android.os.StrictMode.setThreadPolicy(
            android.os.StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork()
                .penaltyListener(mainExecutor) { violation ->
                    // Stack only: no request URLs, credentials or library data.
                    android.util.Log.w("CodaMainThread", violation.javaClass.simpleName + "\n" +
                        violation.stackTrace.joinToString("\n"))
                }.build())
        getSystemService(android.net.ConnectivityManager::class.java).registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { networkRecovered.tryEmit(Unit) }
        })
        scope.launch { networkRecovered.collect { accents.retryFailed() } }
        // Kept with the signed-in account (server + user): another account never restores it.
        accents.onPlaybackCommitted = { memory -> accountKeyOf(sessions.state.value)?.let { playbackAccent.write(it, memory) } }
        settings = dev.coda.android.settings.SettingsStore(this, scope)
        cacheSettings = dev.coda.android.cache.CacheSettings(this)
        // The Music storage values are read once on IO; the screens then read them from memory.
        scope.launch(Dispatchers.IO) { cacheSettings.policy; cacheSettings.customChosen; cacheSettings.loadTracksAhead() }
        httpsHistory = dev.coda.android.session.HttpsHistory.forApp(this, scope)
        val credentials = CredentialStore(this)
        // A saved http session from before LG-3 counts as agreed (migration, written once).
        plainHttpConsent = dev.coda.android.session.PlainHttpConsent.inFile(this, scope) { credentials.read()?.serverUrl }
        recentServers = dev.coda.android.session.RecentServers(this, scope) { url ->
            // × forgets the server: its https protection and its plain-http agreement (each on its own; an unreadable
            // store fails closed by itself and must not crash the sign-in screen).
            for (forget in listOf(httpsHistory::forget, plainHttpConsent::forget)) {
                try { forget(url) } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    // Not silent: a forget that could not be saved holds in this process only (no address in the log).
                    android.util.Log.w("CodaApp", "Forgetting a server was not fully saved: ${error.javaClass.simpleName}")
                }
            }
        }
        sessions = SessionRepository(credentials, dev.coda.android.session.autoLoginPreference(this), recentServers::add, httpsHistory, plainHttpConsent,
            onVerifiedSignIn = ::keepOnlyAccountOf,
            // Only while the server answers; bounded (ScrobbleHost.SIGN_OUT_FLUSH_MS), entries stay on failure.
            beforeSignOut = { if (reachability.state.value.reachable) scrobbles.flushForSignOut() })
        quietPlayback = dev.coda.android.playback.QuietPlayback(this, scope, playbackActive)
        // Screen off and playing: close idle server connections shortly after each request burst.
        val idleConnections = dev.coda.android.net.IdleConnectionCloser(scope, quietPlayback.quiet,
            { dev.coda.android.net.HttpClientFactory.client.connectionPool.evictAll() })
        dev.coda.android.net.ConnectionActivity.onActivity = idleConnections::activity
        var gate: dev.coda.core.connectivity.ServerGate? = null
        var reach: dev.coda.core.connectivity.Reachability? = null
        val connectivity = dev.coda.android.net.AndroidConnectivity(this) { reach?.networkChanged(); gate?.reset() }
        reachability = dev.coda.core.connectivity.Reachability(scope, { android.os.SystemClock.elapsedRealtime() }, probe = {
            (sessions.state.value as? dev.coda.android.session.SessionState.Connected)?.session?.client?.let { it.ping(); true }
        }, quiet = quietPlayback.quiet, initial = connectivity.network.value,
            // The back schedule runs only while Coda is visible or playing.
            attended = combine(transferGate.visible, playbackActive) { shown, playing -> shown || playing }
                .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, transferGate.visible.value || playbackActive.value),
            // Debug and "-pre" builds log each failure (counted or why not); release passes nothing.
            log = if (dev.coda.android.diag.BatteryLog.ENABLED) { line ->
                dev.coda.android.diag.BatteryLog.reach("$line restrictBackground=${connectivity.restrictBackground}")
            } else null,
            // A return to the front gets its grace also while playing (attended stays true then).
            visible = transferGate.visible,
        ).also { reach = it }
        scope.launch { connectivity.network.collect(reachability::network) }
        scope.launch { connectivity.blocked.collect(reachability::blocked) }
        coverRetries = dev.coda.core.connectivity.CoverRetries(scope, reachability.state.map { it.reachable }, quietPlayback.screenOn)
        // The gate reads the network from the shared state (one reading for both).
        serverGate = dev.coda.core.connectivity.ServerGate(reachability, { android.os.SystemClock.elapsedRealtime() }) { kind ->
            dev.coda.android.diag.BatteryLog.count(when (kind) {
                dev.coda.core.connectivity.ServerGate.Kind.QUEUE_SAVE -> dev.coda.android.diag.BatteryIds.SKIPPED_QUEUE_SAVE
                dev.coda.core.connectivity.ServerGate.Kind.QUEUE_LOAD -> dev.coda.android.diag.BatteryIds.SKIPPED_QUEUE_LOAD
                dev.coda.core.connectivity.ServerGate.Kind.SCROBBLE -> dev.coda.android.diag.BatteryIds.SKIPPED_SCROBBLE
                dev.coda.core.connectivity.ServerGate.Kind.NOW_PLAYING -> dev.coda.android.diag.BatteryIds.SKIPPED_NOW_PLAYING
                dev.coda.core.connectivity.ServerGate.Kind.PING -> dev.coda.android.diag.BatteryIds.SKIPPED_PING
                dev.coda.core.connectivity.ServerGate.Kind.ALBUM_RESUME -> dev.coda.android.diag.BatteryIds.SKIPPED_ALBUM_RESUME
            })
        }.also { gate = it }
        reachability.gate = serverGate
        dev.coda.android.net.ServerContact.connect(reachability) { serverGate.reset() }
        scrobbles = dev.coda.android.scrobble.ScrobbleHost(this, scope, sessions, quietPlayback, serverGate)
        albumResume = dev.coda.android.scrobble.AlbumResumeHost(this, scope, sessions, serverGate)
        scope.launch {
            var signedIn = false
            var lastAccount: String? = null
            sessions.state.collect { state ->
                // Signing out, or signing in as another user or server, forgets the kept playback colour.
                val account = accountKeyOf(state)
                // Also the in-memory playback subject (else the next sign-in derives and stores the old one again).
                if (state is dev.coda.android.session.SessionState.SignedOut && signedIn) { accents.forgetPlayback(); playbackAccent.clear() }
                // The signed-out account's local queue copy goes too (also when the playback service never saw it, e.g. a cold NeedsLogin).
                if (state is dev.coda.android.session.SessionState.SignedOut) lastAccount?.let { key ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { dev.coda.android.playback.OwnQueueFiles.forget(this@CodaApp, key) }
                }
                if (account != null && lastAccount != null && account != lastAccount) { accents.forgetPlayback(); playbackAccent.clear() }
                // Signed out, or another user or server: the old server's answers say nothing about the next one.
                if (dev.coda.android.net.AccountSwitch.leftAccount(lastAccount, account, state is dev.coda.android.session.SessionState.SignedOut))
                    dev.coda.android.net.AccountSwitch.forgetServer(reachability, serverGate)
                if (account != null) lastAccount = account else if (state is dev.coda.android.session.SessionState.SignedOut) lastAccount = null
                signedIn = state is dev.coda.android.session.SessionState.Connected || state is dev.coda.android.session.SessionState.NeedsLogin
                val session = (state as? dev.coda.android.session.SessionState.Connected)?.session
                if (session?.token != offlineToken) {
                    offlineJob?.cancel()
                    offlineJob?.join()
                    offlineJob = null
                    offlineToken = null
                    // Signed out: nothing of the old library stays published (the job and the notification follow it).
                    if (session == null) {
                        offlineLibrary.value?.let { it.shutdown(); offlineLibrary.value = null }
                        // Explicitly, not by watching the state: a job waiting for Wi-Fi must not outlive the session.
                        dev.coda.android.cache.DownloadJobs.cancelIfIdle(this@CodaApp)
                    }
                }
                if (session != null) kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val config = session.client.configuration
                    val key = dev.coda.core.cache.TrackCache.serverKey(config.serverUrl, config.username)
                    // Read once; a verified sign-in as another account can null the field meanwhile (keepOnlyInProcess).
                    val cache = trackCache?.takeIf { it.directory.name == key } ?: run {
                        trackCache = null
                        offlineLibrary.value = null
                        val directory = java.io.File(noBackupFilesDir, "music/$key")
                        // The music storage settings are read off the main thread once, then from memory.
                        cacheSettings.policy.value
                        // The index lives in the account's library.db (asked for at each write: a closed store reopens).
                        // Never once the cache is retired (account wipe): that would recreate the old file and
                        // replace the current account's store.
                        var owner: dev.coda.core.cache.TrackCache? = null
                        dev.coda.core.cache.TrackCache(directory,
                            dev.coda.core.cache.CacheIndexDb({ android.util.Log.w("CodaCache", it) }, retired = { owner?.retired == true }) { libraryStore(key) },
                            freeSpaceOverride ?: dev.coda.android.cache.AndroidFreeSpace(this@CodaApp, directory),
                            policy = { cacheSettings.policy.value }, log = { android.util.Log.w("CodaCache", it) }).also { owner = it; trackCache = it }
                    }
                    if (offlineToken != session.token) {
                        val job = kotlinx.coroutines.SupervisorJob(scope.coroutineContext[kotlinx.coroutines.Job])
                        offlineJob = job
                        offlineToken = session.token
                        val monitor = dev.coda.android.net.NetworkMonitor(this@CodaApp)
                        fun current() = (sessions.state.value as? dev.coda.android.session.SessionState.Connected)?.session
                            ?.takeIf { it.token == session.token } ?: throw java.io.IOException("No active session")
                        offlineLibrary.value = dev.coda.android.cache.OfflineLibrary(
                            CoroutineScope(scope.coroutineContext + job), cache, monitor.state, monitor.changes,
                            // Cache downloads always ask for the whole file: never `timeOffset`.
                            remote = { source ->
                                current().client.streamUrl(source.songId,
                                    if (source.format == dev.coda.core.playback.StreamFormat.OPUS_128) "opus" else "raw").toString()
                            },
                            reachability = reachability, onClose = monitor::close, transfersAllowed = transfersAllowed,
                            onUserWork = transferGate::userAskedForWork, active = cacheActive, savePolicy = { policy, custom -> cacheSettings.setPolicy(policy, custom) }, networkIdentity = { monitor.identity },
                            visible = transferGate.visible, screenOn = quietPlayback.screenOn,
                            coverSource = dev.coda.android.artwork.KeptCoverSource(this@CodaApp, ::current))
                        collectCatalogue(CoroutineScope(scope.coroutineContext + job), offlineLibrary.value!!, libraryStore(key)) {
                            current().recentAlbums.get(it)
                        }
                    }
                }
                dev.coda.android.playback.PlaybackDependencies.remoteStreams = object : dev.coda.android.playback.RemoteStreamResolver {
                    override fun resolve(source: dev.coda.core.playback.TrackSource.Remote): android.net.Uri {
                        val current = session ?: throw java.io.IOException("No active session")
                        return android.net.Uri.parse(current.client.streamUrl(source.songId,
                            if (source.format == dev.coda.core.playback.StreamFormat.OPUS_128) "opus" else "raw",
                            source.timeOffsetSeconds).toString())
                    }
                    override fun supportsTranscodeOffset() = session?.supportsTranscodeOffset() == true
                    override fun knownTranscodeOffset() = session?.knownTranscodeOffset()
                }
            }
        }
        // One notification for the background downloads, and the transfer job whenever work appears.
        scope.launch {
            val schedule = dev.coda.android.cache.DownloadSchedule(
                ensureJob = { anyNetwork -> dev.coda.android.cache.DownloadJobs.ensure(this@CodaApp, anyNetwork) },
                cancelJobIfIdle = { dev.coda.android.cache.DownloadJobs.cancelIfIdle(this@CodaApp) })
            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            offlineLibrary.flatMapLatest { it?.downloads?.map { state -> true to state } ?: kotlinx.coroutines.flow.flowOf(false to dev.coda.core.cache.DownloadState()) }
                .combine(dev.coda.android.cache.DownloadJobService.running) { (hasLibrary, state), running -> Triple(hasLibrary, state, running) }
                .collect { (hasLibrary, state, running) ->
                    dev.coda.android.cache.DownloadNotifications.update(this@CodaApp, state, running)
                    schedule.onState(state.active || state.coversDue, hasLibrary, anyNetwork = state.queue != null)  // Kept covers too
                }
        }
        scope.launch {
            dev.coda.android.artwork.ArtworkContentProvider.initialize(this@CodaApp)
            // Before the session: the shell's first frame reads the restored colour.
            // Only the stored account's colour; another account's (or one with no stored sign-in) is dropped.
            // Finish an account wipe a crash or kill interrupted, before any account data is read or written.
            accountStorage.resume(sessions.storedAccountKey(), keepOnlyInProcess)
            playbackAccent.readFor(sessions.storedAccountKey())?.let(accents::restore)
            sessions.restore()
            // A restored colour whose session did not come back must not leak into the next sign-in.
            if (sessions.state.value is dev.coda.android.session.SessionState.SignedOut) { accents.forgetPlayback(); playbackAccent.clear() }
            if (autoLoginEnabled) dev.coda.android.session.autoLogin(sessions) }
    }

    /**
     * The catalogue fills itself whenever the music cache changed. The offline library's maintenance (at start,
     * then after each song written, each track change, every minute on screen, at least every 15 min while Coda is
     * active) and its
     * publishes (downloads, adds, deletes) trigger a run; a run is skipped when the cache did not change and nothing was
     * left to fetch, and runs are at least [CATALOGUE_GAP_MS] apart. Requests only while the server answers, on any network
     *. A screen saving the album page of a minimal entry, or a changed page of a full one, also triggers a run (NF-4 §3/§5),
     * coalesced by the gap.
     */
    private fun collectCatalogue(scope: CoroutineScope, library: dev.coda.android.cache.OfflineLibrary,
                                 store: dev.coda.core.library.LibraryStore, fetchAlbum: suspend (String) -> dev.coda.core.subsonic.AlbumPage) {
        val retryMs = catalogueRetryMs
        val collector = dev.coda.core.library.CatalogueCollector(store, fetchAlbum, retryMs = retryMs)
        scope.launch(Dispatchers.IO) {
            var seen = Long.MIN_VALUE
            var seenSaves = Int.MIN_VALUE
            var last: dev.coda.core.library.CatalogueCollector.Result? = null
            combine(library.maintenanceRuns, library.revision, store.catalogueAlbumPageSaves) { _, _, _ -> }
                .conflate()
                .collect {
                    val revision = library.cache.revision
                    val saves = store.catalogueAlbumPageSaves.value
                    val changed = revision != seen || saves != seenSaves
                    // A pass runs when the cache changed or the last one left something that is due now (a cap, an ended
                    // back-off, the server back); albums that keep failing or an unreachable server do not keep it busy.
                    if (changed || last?.dueAgain(false, library.catalogueFetchAllowed(), System.currentTimeMillis()) != false) {
                        seen = revision
                        seenSaves = saves
                        val result = try {
                            collector.collect(library.cache.records(), library.cache.addedSets) { library.catalogueFetchAllowed() }
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                            android.util.Log.w("CodaLibrary", "Catalogue pass failed: ${e.javaClass.simpleName}"); null
                        }
                        last = result ?: dev.coda.core.library.CatalogueCollector.Result(retryAtMs = System.currentTimeMillis() + retryMs)
                        lastCatalogueRun = result
                        catalogueRuns.value++
                    }
                    kotlinx.coroutines.delay(CATALOGUE_GAP_MS)
                }
        }
    }

    /** The hashed server + user key of a signed-in (or needs-login) state, else null. */
    private fun accountKeyOf(state: dev.coda.android.session.SessionState): String? {
        val session = when (state) {
            is dev.coda.android.session.SessionState.Connected -> state.session
            is dev.coda.android.session.SessionState.NeedsLogin -> state.session
            else -> return null
        }
        val config = session.client.configuration
        return dev.coda.core.cache.TrackCache.serverKey(config.serverUrl, config.username)
    }

    private companion object {
        const val CATALOGUE_GAP_MS = 5_000L
        const val CATALOGUE_RETRY_MS = 10 * 60_000L
    }
}
