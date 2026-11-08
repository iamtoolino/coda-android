package dev.coda.android.playback

import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import androidx.media3.session.SessionCommand
import androidx.media3.common.Player
import android.os.Bundle
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import dev.coda.android.CodaApp
import dev.coda.android.artwork.ArtworkContentProvider
import dev.coda.android.session.Session
import dev.coda.android.session.SessionState
import dev.coda.android.session.SessionEnded
import dev.coda.android.session.request
import dev.coda.core.library.LibraryNode
import dev.coda.core.library.AlphaBuckets
import dev.coda.core.library.CarFallback
import dev.coda.core.library.CarIndex
import dev.coda.core.library.CarReloads
import dev.coda.core.library.CarReloads.atMostEvery
import dev.coda.core.library.LibraryTree
import dev.coda.core.library.treeSource
import dev.coda.core.subsonic.SubsonicError
import dev.coda.core.sync.OwnQueue
import dev.coda.core.sync.StartQueueResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android Auto browse tree and playback entry points (architecture §13). Browsing needs a signed-in session;
 * the tree itself lives in `:core` so structure and paging are JVM-tested.
 */
@UnstableApi
open class AutoLibraryCallback(
    private val app: CodaApp,
    private val scope: CoroutineScope,
    /** The player's queue when it has one (read on Main): step 1 of the empty voice request before the local copy. */
    private val playerQueue: () -> OwnQueue? = { null },
    /** Called on Main right before a start request hands the player a new queue: the server save waits for playback. Returns the token the request tags its items with. */
    private val onStartQueue: () -> Long? = { null },
) : MediaLibrarySession.Callback {
    companion object {
        /** Queue-item extra naming the session that resolved an Auto request; the player drops it once that session ended. */
        const val SESSION_TAG = "autoSessionToken"
        /** Root hint a media browser client may send with its page size (MediaBrowserCompat.EXTRA_PAGE_SIZE). */
        const val PAGE_SIZE_HINT = "android.media.browse.extra.PAGE_SIZE"
        /** A cold start by Auto can ask before the stored login has been restored. */
        private const val RESTORE_WAIT_MS = 2_000L
        private const val SEARCH_COUNT_LIMIT = LibraryTree.MAX_PAGE_SIZE
        /** The only non-tree ids the player resolves (`PlaybackDependencies.resolveEntry`). */
        private const val SONG_PREFIX = "song/"
        /** Item extra: the player already holds exactly this queue, so setting it must not rebuild it. */
        const val KEEP_QUEUE = "autoKeepQueue"
        /** Item extra: the token of the start request that installs the queue (`QueueEntry.startToken`); the coordinator recognises the install by it. */
        const val START_TOKEN = "autoStartToken"
        /** Resumption may only show a card: it gives up (cleanly) when the chain takes longer than this. */
        private const val RESUMPTION_TIMEOUT_MS = 10_000L
        /** Bursts of tree changes (a flaky link, a download batch) reach the car as one reload. */
        private const val RELOAD_COALESCE_MS = 500L
        /** How long a phone voice request waits for Media3 to install its resolved queue before it gives up. */
        private const val INSTALL_WAIT_MS = 1_000L
    }

    /** Only trusted controllers may connect; each gets a read grant on the (non-exported) artwork provider. */
    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
        if (!ControllerTrust.accepts(controller.uid, android.os.Process.myUid(), controller.isTrusted,
                controller.isPackageNameVerified, ControllerTrust.approvedSigner(app.packageManager, controller.packageName))) return MediaSession.ConnectionResult.reject()
        if (controller.uid != android.os.Process.myUid()) runCatching {
            app.grantUriPermission(controller.packageName, ArtworkContentProvider.BASE_URI,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        // Authenticated above, so full session, library and player commands. (super.onConnect returns Media3's
        // "not implemented" sentinel, which subclasses could not extend and which gives non-Media3-trusted hosts read access only.)
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
    }

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val result = SettableFuture.create<T>()
        val job = scope.launch {
            try { result.set(block()) }
            catch (_: SessionEnded) { result.setException(IllegalStateException("Session ended")) }
            catch (e: CancellationException) { result.cancel(false); throw e }
            catch (e: Throwable) { result.setException(e) }
        }
        result.addListener({ if (result.isCancelled) job.cancel() }, MoreExecutors.directExecutor())
        // A scope that is already cancelled never runs the body: the future must still complete.
        job.invokeOnCompletion { if (!result.isDone) result.cancel(false) }
        return result
    }

    private suspend fun connected(): Session? {
        val state = withTimeoutOrNull(RESTORE_WAIT_MS) {
            // Off Main's critical path: a cold start may ask before the stored login is restored (still Restoring).
            app.sessions.state.first { it is SessionState.Connected || it is SessionState.NeedsLogin }
        }
        return (state as? SessionState.Connected)?.session
    }

    private fun <T : Any> signIn() = LibraryResult.ofError<T>(SessionError(SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED, "Sign in on your phone"))

    private suspend fun <T : Any> libraryCall(block: suspend (LibraryTree, Session) -> LibraryResult<T>): LibraryResult<T> {
        val session = connected() ?: return signIn<T>()
        try {
            // Browsing and search follow "Complete albums only" on the phone tree; playback (withTree) does not.
            return served(session, app.settings.completeAlbumsOnly.value) { tree -> block(tree, session) }
        } catch (_: SessionEnded) {
            return signIn<T>()
        } catch (e: CancellationException) {
            throw e
        } catch (_: SubsonicError.Unauthorized) {
            app.sessions.unauthorized(session.token)
            return signIn<T>()
        } catch (_: SubsonicError.NotFound) {
            return LibraryResult.ofError<T>(SessionError.ERROR_BAD_VALUE)
        } catch (_: Exception) {
            return LibraryResult.ofError<T>(SessionError.ERROR_IO)
        }
    }

    private fun items(nodes: List<LibraryNode>, params: LibraryParams?): LibraryResult<ImmutableList<MediaItem>> =
        LibraryResult.ofItemList(nodes.map(AutoMediaItems::browse), params)

    /** Largest list the connected hosts show. Starts at the conservative default; a root hint can only lower it. */
    @Volatile private var listLimit = AlphaBuckets.DEFAULT_LIMIT
    private val index = CarIndex()

    private val sourceSwitched = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * The car's lists change without a sign-in change: the source switches (reachability's offline flag, or the tree
     * that answered the last request; at most once per [CarReloads.SWITCH_GAP_MS], so a flaky link or the reload's own
     * mixed answers don't keep reloading), "Complete albums only" changes, or a catalogue run changed the phone's lists
     * while the car is served from the phone (also when only the on-phone tracks changed an album's place in them,
     * [CarReloads.phoneLists]). One flow per subscription.
     */
    private fun treeChanged(): Flow<Unit> {
        val switches = merge(sourceSwitched,
            app.reachability.state.map(CarFallback::phoneFirst).distinctUntilChanged().drop(1)
                .onEach(index::offlineChanged).map { })
        var kept = keptKeys()
        var lists: Map<String, Boolean>? = null
        val catalogue = app.catalogueRuns.drop(1).onStart { lists = phoneLists() }.filter {
            val now = keptKeys()
            val keptChanged = now != kept
            kept = now
            val onPhone = CarFallback.phoneFirst(app.reachability.state.value) || index.onPhone
            // Read only while the car is served from the phone; a baseline from before is still the right comparison.
            val listsChanged = onPhone && phoneLists().let { current -> (current != null && current != lists).also { if (current != null) lists = current } }
            CarReloads.catalogueReloads(onPhone, app.lastCatalogueRun, keptChanged, listsChanged)
        }.map { }
        return merge(switches.atMostEvery(CarReloads.SWITCH_GAP_MS), app.settings.completeAlbumsOnly.drop(1).map { }, catalogue)
    }

    private val listsLock = Mutex()
    private var listsAt: Triple<Any, Int, Map<String, Boolean>>? = null

    /** [CarReloads.phoneLists] of the signed-in account, read once per catalogue run for all subscriptions. */
    private suspend fun phoneLists(): Map<String, Boolean>? {
        val session = (app.sessions.state.value as? SessionState.Connected)?.session ?: return null
        val run = app.catalogueRuns.value
        return listsLock.withLock {
            listsAt?.takeIf { it.first == session.token && it.second == run }?.third ?: withContext(Dispatchers.IO) {
                val config = session.client.configuration
                val store = app.libraryStore(dev.coda.core.cache.TrackCache.serverKey(config.serverUrl, config.username))
                val records = app.offlineLibrary.value?.cache?.records().orEmpty()
                CarReloads.phoneLists(dev.coda.core.library.OfflineCatalogue(store), records.mapTo(HashSet()) { it.songId })
            }.also { listsAt = Triple(session.token, run, it) }
        }
    }

    private fun keptKeys() = app.offlineLibrary.value?.items?.value?.mapTo(HashSet()) { it.key }

    init {
        // F2: the in-memory album index never outlives the server's reachability.
        scope.launch { app.reachability.state.collect(index::reachabilityChanged) }
    }

    /** [bounded]: each server call within [CarFallback.LIVE_TIMEOUT_MS], for [served]. */
    private fun tree(session: Session, bounded: Boolean = false): LibraryTree {
        val cache = index.forSession(session.token)
        val source = session.client.treeSource()
        return LibraryTree(if (bounded) CarFallback.bounded(source) else source, listLimit, cache)
    }

    /**
     * One car request on the live tree while the server answers ([CarFallback]: each call within its bound, not offline),
     * else on the phone's offline catalogue (the phone's offline views' rules: only what's on the phone). Per request,
     * so the next request after the server answers again is live.
     */
    private suspend fun <T> served(session: Session, completeOnly: Boolean = false, block: suspend (LibraryTree) -> T): T =
        CarFallback.serve(app.reachability.state.value,
            live = { app.sessions.request(session) { block(tree(session, bounded = true)) } },
            phone = { app.sessions.request(session) { block(phoneTree(session, completeOnly)) } },
            onSource = { if (index.served(it)) sourceSwitched.tryEmit(Unit) })

    private suspend fun phoneTree(session: Session, completeOnly: Boolean): LibraryTree = LibraryTree(phoneSource(session, completeOnly), listLimit)

    /** [completeOnly] only for the car's lists and search; taps, voice and the start chain use every album on the phone. */
    private suspend fun phoneSource(session: Session, completeOnly: Boolean = false) = withContext(Dispatchers.IO) {
        val config = session.client.configuration
        val store = app.libraryStore(dev.coda.core.cache.TrackCache.serverKey(config.serverUrl, config.username))
        val records = app.offlineLibrary.value?.cache?.records().orEmpty()
        val covers = app.offlineLibrary.value?.cache?.covers
        // A playlist without a stored cover shows its first song's stored cover (PlaylistCover), as on the phone.
        dev.coda.core.library.PhoneTreeSource(dev.coda.core.library.OfflineCatalogue(store) { covers?.stored(it) == true },
            records.mapTo(HashSet()) { it.songId }, records.mapNotNull { r -> r.lastPlayedAt?.let { r.songId to it } }.toMap(), completeOnly)
    }

    private fun readLimitHint(params: LibraryParams?) {
        val hint = params?.extras?.getInt(PAGE_SIZE_HINT, 0) ?: 0
        if (hint >= AlphaBuckets.MIN_LIMIT) listLimit = minOf(listLimit, hint)
    }

    override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> {
        readLimitHint(params)
        return getRoot()
    }

    private fun getRoot() =
        // The legacy bridge blocks Main waiting for this future. Never suspend or dispatch here.
        Futures.immediateFuture(LibraryResult.ofItem(AutoMediaItems.browse(LibraryTree.root),
            LibraryParams.Builder().setExtras(AutoMediaItems.contentStyle(
                androidx.media3.session.MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)).build()))

    override fun onSubscribe(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
        parentId: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> {
        subscriptions.remove(browser to parentId)?.cancel()
        subscriptions[browser to parentId] = scope.launch {
            // The subscribe itself loads the children; afterwards reload on restoration/sign-in and invalidate
            // old-account children on sign-out or account switch.
            launch {
                app.sessions.state.drop(1).collect { state ->
                    session.notifyChildrenChanged(browser, parentId,
                        if (state is SessionState.Connected) Int.MAX_VALUE else 0, params)
                }
            }
            // And whenever the tree itself changed (source switch, Complete albums only, kept items).
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            treeChanged().debounce(RELOAD_COALESCE_MS).collect {
                if (app.sessions.state.value is SessionState.Connected) session.notifyChildrenChanged(browser, parentId, Int.MAX_VALUE, params)
            }
        }
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    private val subscriptions = mutableMapOf<Pair<MediaSession.ControllerInfo, String>, kotlinx.coroutines.Job>()

    override fun onUnsubscribe(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String): ListenableFuture<LibraryResult<Void>> {
        subscriptions.remove(browser to parentId)?.cancel()
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
        subscriptions.keys.filter { it.first == controller }.forEach { subscriptions.remove(it)?.cancel() }
        super.onDisconnected(session, controller)
    }

    override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String) =
        future { libraryCall { tree, _ ->
            tree.item(mediaId)?.let { LibraryResult.ofItem(AutoMediaItems.browse(it), null) }
                ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        } }

    override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
        page: Int, pageSize: Int, params: LibraryParams?) =
        future { libraryCall { tree, _ ->
            tree.children(parentId, page, pageSize)?.let { items(it, params) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        } }

    override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?) =
        future { libraryCall { tree, active ->
            val count = tree.search(query, 0, SEARCH_COUNT_LIMIT).size
            if (!app.sessions.isCurrent(active.token)) throw SessionEnded()
            session.notifySearchResultChanged(browser, query, count, params)
            LibraryResult.ofVoid()
        } }

    override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String,
        page: Int, pageSize: Int, params: LibraryParams?) =
        future { libraryCall { tree, _ -> items(tree.search(query, page, pageSize), params) } }

    /** Browse items become queues: a track plays its container from that track, an album or playlist from the top. */
    override fun onSetMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val id = mediaItems.singleOrNull()?.mediaId
        // Voice ("play X on Coda"): Media3 passes playFromSearch as one item with an empty id and the query. The first
        // album or track match plays from its container; no match is refused (nothing changes). "play music on
        // Coda" without a title (empty query) plays the start chain ([StartQueueResolver]).
        val voice = mediaItems.singleOrNull()?.takeIf { it.mediaId.isEmpty() && it.requestMetadata.mediaUri == null }
        val query = voice?.requestMetadata?.searchQuery
        // "music", an empty query or the "any" focus name nothing: the start chain, not a title search.
        // Phone voice sends no Play of its own (a Play on an empty player is Media3's playback resumption); the
        // session plays once the resolved queue is installed. The car's legacy bridge plays by itself and sends no flag.
        val playOnInstall = voice?.requestMetadata?.extras?.getBoolean(PhoneVoice.EXTRA_PLAY) == true
        if (voice != null && StartQueueResolver.isGeneric(query, voice.requestMetadata.extras?.getString(StartQueueResolver.EXTRA_MEDIA_FOCUS)))
            return told(session, controller, future { start(StartQueueResolver.Reason.VOICE) }, playOnInstall)
        if (query != null) return told(session, controller, playOnInstall = playOnInstall, request = future {
            withTree { tree, active ->
                val request = tree.search(query, 0, SEARCH_COUNT_LIMIT).asSequence().firstNotNullOfOrNull { tree.playback(it.id) }
                    ?: throw NothingToPlay("Couldn't find $query")
                tagged(request, active)
            }
        })
        if (id == null || !LibraryTree.isTreeId(id)) return super.onSetMediaItems(session, controller, mediaItems, startIndex, startPositionMs)
        return future {
            return@future withTree { tree, active ->
                val request = tree.playback(id) ?: throw IllegalArgumentException("Nothing to play")
                tagged(request, active)
            }
        }
    }

    /** A voice request that cannot play says why (Media3's legacy bridge drops the failure itself). */
    private class NothingToPlay(message: String) : IllegalArgumentException(message)

    private fun <T> told(session: MediaSession, controller: MediaSession.ControllerInfo, request: ListenableFuture<T>,
        playOnInstall: Boolean = false): ListenableFuture<T> {
        Futures.addCallback(request, object : com.google.common.util.concurrent.FutureCallback<T> {
            override fun onSuccess(result: T) {
                if (playOnInstall && result is MediaSession.MediaItemsWithStartPosition) playWhenInstalled(session, controller, result)
            }
            override fun onFailure(t: Throwable) {
                if (t is java.util.concurrent.CancellationException) return
                session.sendError(controller, when {
                    t is SubsonicError.Unauthorized || t is NotSignedIn ->
                        SessionError(SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED, "Sign in on your phone")
                    t is NothingToPlay -> SessionError(SessionError.ERROR_NOT_SUPPORTED, t.message.orEmpty())
                    else -> SessionError(SessionError.ERROR_IO, "Nothing to play right now")
                })
            }
        }, app.mainExecutor)
        return request
    }

    private class NotSignedIn : IllegalStateException("Not signed in")

    /**
     * Plays once the player holds the resolved queue (Media3 installs it after this future's callbacks), then tells
     * the phone's controller. A queue that never arrives (a newer request replaced it) starts nothing.
     */
    private fun playWhenInstalled(session: MediaSession, controller: MediaSession.ControllerInfo, result: MediaSession.MediaItemsWithStartPosition) {
        val player = session.player
        val ids = result.mediaItems.map { it.mediaId }
        var started = false
        fun holds() = player.mediaItemCount == ids.size && ids.indices.all { player.getMediaItemAt(it).mediaId == ids[it] }
        fun start() {
            started = true
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
            session.sendCustomCommand(controller, SessionCommand(PhoneVoice.STARTED, Bundle.EMPTY), Bundle.EMPTY)
        }
        val listener = object : Player.Listener {
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED && holds()) { player.removeListener(this); start() }
            }
        }
        player.addListener(listener)
        // The install follows within a few main-loop turns. A queue equal to the one already there may change no timeline:
        // then the check after the wait plays it. A listener left longer would play a later, foreign queue.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!started) { player.removeListener(listener); if (holds()) start() }
        }, INSTALL_WAIT_MS)
        // Our own queue already in the player (voice resumed it unchanged, KEEP_QUEUE): no timeline change follows.
        if (result.mediaItems.firstOrNull()?.mediaMetadata?.extras?.getBoolean(KEEP_QUEUE) == true && holds()) { player.removeListener(listener); start() }
    }

    override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
        // Folders ("albums", "home/newest"), an empty voice query or a foreign id: refuse instead of handing the player
        // an item it cannot resolve (only `song/` ids and tree ids can be queued).
        if (mediaItems.any { !LibraryTree.isTreeId(it.mediaId) && !it.mediaId.startsWith(SONG_PREFIX) })
            return Futures.immediateFailedFuture(IllegalArgumentException("Not playable"))
        if (mediaItems.none { LibraryTree.isTreeId(it.mediaId) }) return Futures.immediateFuture(mediaItems.map(::stripped))
        return future {
            withTree { tree, active -> mediaItems.flatMap { item ->
                if (!LibraryTree.isTreeId(item.mediaId)) listOf(stripped(item))
                else tagged(tree.playback(item.mediaId) ?: throw IllegalArgumentException("Nothing to play"), active).mediaItems
            } }
        }
    }

    /**
     * Android's playback resumption (the resume card at car start or Bluetooth connect, Play on an empty player):
     * only our own last queue, from the local copy and so also offline; never the handoff queue unseen. Without one,
     * the start chain from the newest unrated albums on.
     */
    override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
        // isForPlayback = false only asks for the notification metadata: nothing is installed, so nothing is armed.
        future { start(StartQueueResolver.Reason.RESUMPTION, arm = isForPlayback) }

    private suspend fun start(reason: StartQueueResolver.Reason, arm: Boolean = true): MediaSession.MediaItemsWithStartPosition {
        // Resumption may only show a card: the whole request (sign-in wait, chain, album fetches, item construction)
        // gives up cleanly after the timeout; nothing changes and nothing is saved.
        val result = if (reason == StartQueueResolver.Reason.RESUMPTION)
            withTimeoutOrNull(RESUMPTION_TIMEOUT_MS) { startItems(reason) } ?: throw IllegalStateException("Resumption timed out")
        else startItems(reason)
        // A new queue in the player is saved (server, local copy) only once it plays; the queue the player already has is not touched.
        val token = if (dev.coda.core.sync.startRequestArmsGate(arm, result.mediaItems.firstOrNull()?.mediaMetadata?.extras?.getBoolean(KEEP_QUEUE) == true)) onStartQueue() else null
        if (token == null) return result
        val items = result.mediaItems.map { item ->
            item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(
                android.os.Bundle(item.mediaMetadata.extras).apply { putLong(START_TOKEN, token) }).build()).build()
        }
        return MediaSession.MediaItemsWithStartPosition(items, result.startIndex, result.startPositionMs)
    }

    /**
     * The start chain ([StartQueueResolver]). Like a browse request it runs on the server while that
     * answers (each call within [CarFallback.LIVE_TIMEOUT_MS]), else on the phone's music. A voice request from the phone
     * builds a new queue by the same rules over the albums on the phone: it never resumes our last queue
     * (most of it may not be here) and never reads the server queue. Resumption keeps step 1 (the local copy) everywhere.
     */
    private suspend fun startItems(reason: StartQueueResolver.Reason): MediaSession.MediaItemsWithStartPosition {
        val session = connected() ?: throw NotSignedIn()
        try {
            return CarFallback.serve(app.reachability.state.value,
                live = { app.sessions.request(session) { startChain(reason, session, null) } },
                phone = { app.sessions.request(session) { startChain(reason, session, phoneSource(session)) } },
                onSource = { if (index.served(it)) sourceSwitched.tryEmit(Unit) })
        } catch (e: SubsonicError.Unauthorized) { app.sessions.unauthorized(session.token); throw e }
    }

    /** One run of the start chain: on the server ([phone] null) or on the phone's music. */
    private suspend fun startChain(reason: StartQueueResolver.Reason, active: Session,
        phone: dev.coda.core.library.PhoneTreeSource?): MediaSession.MediaItemsWithStartPosition {
        val calls = CarFallback.Calls()
        val voice = reason == StartQueueResolver.Reason.VOICE
        val sources = object : StartQueueResolver.Sources {
            override suspend fun ownQueue(): dev.coda.core.sync.OwnQueue? {
                if (phone != null && voice) return null
                val queue = playerQueue() ?: withContext(Dispatchers.IO) { OwnQueueFiles.store(app, active).read() }?.toOwnQueue()
                // Voice resumes our queue only while the server answers (a dead one sends it to the phone's music above).
                if (phone == null && voice && queue != null && queue.entries.isNotEmpty()) calls.call { active.client.ping() }
                return queue
            }
            override suspend fun serverQueue() = if (phone != null) null else calls.call { active.client.getPlayQueue() }
            override suspend fun albumList(type: dev.coda.core.subsonic.AlbumListType, size: Int, offset: Int) =
                phone?.getAlbumList2(size, offset, type)?.albums ?: calls.call { active.client.getAlbumList2(size, offset, type).albums }
        }
        val resolver = StartQueueResolver(sources, active.client.configuration.clientName)
        val choice = resolver.resolve(reason) ?: throw nothing(phone)
        return when (choice) {
            is StartQueueResolver.Choice.Own -> choice.queue.let { queue ->
                // The player already holds this queue (voice step 1): hand it back unchanged and keep it as it is.
                val keep = playerQueue()?.entries?.map { it.songId } == queue.entries.map { it.songId }
                tagged(queue.entries.map(CodaPlayer::mediaItem), queue.currentIndex, queue.positionMs, active, keep)
            }
            is StartQueueResolver.Choice.Handoff -> serverQueue(choice.queue, active)
            is StartQueueResolver.Choice.OwnOnServer -> serverQueue(choice.queue, active)
            is StartQueueResolver.Choice.Albums -> {
                // One album that cannot be fetched (deleted meanwhile, a hiccup) is skipped; the rest plays.
                val pages = coroutineScope {
                    choice.albums.map { album -> async {
                        try { phone?.getAlbum(album.id) ?: calls.call { active.client.getAlbum(album.id) } }
                        catch (e: CancellationException) { throw e }
                        catch (e: SubsonicError.Unauthorized) { throw e }
                        catch (_: Exception) { null }
                    } }.awaitAll().filterNotNull()
                }
                val items = pages.flatMap { page -> page.songs.map { QueueMediaItems.from(it, page.album) } }
                // Nothing came back because the server stopped answering: the phone answers instead.
                if (items.isEmpty()) throw calls.unanswered ?: nothing(phone)
                tagged(items, 0, 0, active)
            }
        }
    }

    private fun nothing(phone: Any?) = NothingToPlay(if (phone != null) "Nothing to play offline" else "Nothing to play right now")

    private fun serverQueue(queue: dev.coda.core.subsonic.PlayQueue, active: Session) =
        tagged(queue.entry.map { QueueMediaItems.from(it) }, queue.resolvedCurrentIndex, (queue.position ?: 0).coerceAtLeast(0), active)

    /** A tree request (play from a list, voice search) falls back to the phone like browsing. */
    private suspend fun <T> withTree(block: suspend (LibraryTree, Session) -> T): T {
        val session = connected() ?: throw NotSignedIn()
        try {
            return served(session) { tree -> block(tree, session) }
        }
        catch (e: SubsonicError.Unauthorized) { app.sessions.unauthorized(session.token); throw e }
    }

    private fun tagged(request: dev.coda.core.library.PlaybackRequest, active: Session): MediaSession.MediaItemsWithStartPosition {
        return tagged(AutoMediaItems.queue(request), request.startIndex, 0, active)
    }

    private fun tagged(queue: List<MediaItem>, startIndex: Int, startPositionMs: Long, active: Session,
        keepQueue: Boolean = false): MediaSession.MediaItemsWithStartPosition {
        if (!app.sessions.isCurrent(active.token)) throw SessionEnded()
        val items = queue.map { item ->
            item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(
                android.os.Bundle(item.mediaMetadata.extras).apply {
                    putString(SESSION_TAG, active.token.toString())
                    if (keepQueue) putBoolean(KEEP_QUEUE, true)
                }
            ).build()).build()
        }
        return MediaSession.MediaItemsWithStartPosition(items, startIndex, startPositionMs)
    }

    // Resolution into QueueEntries happens in CodaPlayer; these items intentionally have no URI.
    private fun stripped(item: MediaItem) = MediaItem.Builder().setMediaId(item.mediaId).setMediaMetadata(item.mediaMetadata).build()
}
