package dev.coda.core.sync

import dev.coda.core.playback.CompletedPlayback
import dev.coda.core.playback.PlaybackEvent
import dev.coda.core.playback.sessionStampMatches
import dev.coda.core.subsonic.AlbumPage
import dev.coda.core.subsonic.Bookmark
import dev.coda.core.subsonic.Song
import dev.coda.core.subsonic.SubsonicError
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The server calls the album-resume work needs; the app backs them with SubsonicClient. */
interface AlbumResumeApi {
    suspend fun getSong(id: String): Song
    suspend fun getAlbum(id: String): AlbumPage
    suspend fun getBookmarks(): List<Bookmark>
    suspend fun createBookmark(songId: String, positionMilliseconds: Long, comment: String)
    suspend fun deleteBookmark(songId: String)
}

/** [token] identifies the signed-in session; work for another token is dropped. */
class AlbumResumeSession(val api: AlbumResumeApi, val writer: AlbumResumeWriter?, val token: Any = Any())

/**
 * AlbumResumeCoordinator.swift port. Completion writes and authoritative refreshes share one serial queue, so
 * a refresh fetches only after earlier writes finished and its retention deletes finish before later writes.
 *
 * Writes: only a natural [PlaybackEvent.Completed] inside an album run writes (see [albumResumeCompletionAction]);
 * after server success [items] is updated provisionally (macOS publishLocalUpsert/Deletion).
 * Refresh: [refresh] calls getBookmarks once, publishes the newest 20 to [items], then best-effort deletes the
 * compatible markers beyond them using only that fresh answer. Errors are dropped like on macOS (no retry or outbox);
 * [onEvent] never waits for the network. A session change drops queued and in-flight work and clears [items].
 */
class AlbumResumeCoordinator(
    private val scope: CoroutineScope,
    private val now: () -> Instant = Instant::now,
    /** Where the snapshot is grouped and sorted; the host scope is the main thread, so this is never that. */
    private val computation: CoroutineDispatcher = Dispatchers.Default,
    /** Offline no request is made; the write is skipped like a failed one (macOS: no retry, no outbox). */
    private val gate: dev.coda.core.connectivity.ServerGate? = null,
) {
    private val session = AtomicReference<AlbumResumeSession?>(null)
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    /** The job the consumer is running; a session change cancels it (macOS AlbumResumeWorkQueue.cancel). */
    private val running = AtomicReference<kotlinx.coroutines.Job?>(null)
    private var itemsToken: Any? = null
    private var pendingRefresh: Pair<Any, CompletableDeferred<Boolean>>? = null
    private val mutableItems = MutableStateFlow<List<AlbumResumeItem>>(emptyList())

    /** Home shelf content: authoritative after [refresh], provisionally edited by completed writes. */
    val items: StateFlow<List<AlbumResumeItem>> = mutableItems

    init {
        scope.launch {
            for (job in queue) {
                val child = launch(start = kotlinx.coroutines.CoroutineStart.ATOMIC) { job() }
                running.set(child)
                child.join()
                running.set(null)
            }
        }
    }

    /**
     * Queued and in-flight work of the old session is dropped. The shelf is cleared only for another session: a
     * reconnect of the same session (login retry, brief non-connected state) keeps what it shows.
     */
    fun setSession(value: AlbumResumeSession?) {
        val old = session.getAndSet(value)
        if (old !== value) { running.get()?.cancel(); pendingRefresh = null }
        if (value != null && value.token != itemsToken) { mutableItems.value = emptyList(); itemsToken = value.token }
    }

    suspend fun onEvent(event: PlaybackEvent) {
        if (event !is PlaybackEvent.Completed) return
        if (albumResumeRecordingAlbumId(event.playback) == null) return
        val current = session.get() ?: return
        // The fan-out may deliver late, after an account switch: the completion belongs to the session that played it.
        if (!sessionStampMatches(event.playback.sessionGeneration, current.token)) return
        queue.trySend { process(current, event.playback) }
    }

    /**
     * Queue an authoritative refresh for [token]'s session; completes when it (and its cleanup) is done. Overlapping
     * calls share one refresh. False: nothing was fetched (other session, offline, cancelled). An Unauthorized answer
     * is thrown so the caller can sign out like for any other request.
     */
    suspend fun refresh(token: Any): Boolean {
        val current = session.get()?.takeIf { it.token == token } ?: return false
        val existing = pendingRefresh?.takeIf { it.first == token }?.second
        val done = existing ?: CompletableDeferred<Boolean>().also { deferred ->
            pendingRefresh = token to deferred
            queue.trySend {
                try {
                    deferred.complete(refreshNow(current))
                } catch (e: CancellationException) {
                    deferred.complete(false); throw e
                } catch (e: Throwable) {
                    deferred.completeExceptionally(e)
                } finally {
                    if (pendingRefresh?.second === deferred) pendingRefresh = null
                }
            }
        }
        return done.await()
    }

    /**
     * Session connect (host): install [value] and, for a real session, start its authoritative refresh right away so
     * cleanup does not wait for the phone UI to open. Shares the pending refresh with Home's own calls; an
     * Unauthorized answer goes to [onUnauthorized] (the host signs out); other failures are dropped like in [refresh].
     */
    fun connect(value: AlbumResumeSession?, onUnauthorized: () -> Unit = {}) {
        setSession(value)
        val token = value?.token ?: return
        scope.launch {
            try { refresh(token) } catch (e: CancellationException) { throw e }
            catch (_: SubsonicError.Unauthorized) { onUnauthorized() } catch (_: Throwable) {}
        }
    }

    /** True when the fresh bookmark list was fetched and published (cleanup is best effort after that). */
    private suspend fun refreshNow(job: AlbumResumeSession): Boolean {
        try {
            if (session.get() !== job) return false
            if (gate?.allows(dev.coda.core.connectivity.ServerGate.Kind.ALBUM_RESUME) == false) return false
            val bookmarks = job.api.getBookmarks()
            if (session.get() !== job) return false
            // One pass off the caller's (main) thread: shelf and cleanup candidates both come from these buckets.
            val buckets = withContext(computation) { albumResumeBuckets(bookmarks) }
            if (session.get() !== job) return false
            mutableItems.value = buckets.take(ALBUM_RESUME_RETENTION_LIMIT).map { it.item }
            for (songId in retentionDeletesOf(buckets)) {
                if (session.get() !== job) return true
                try { job.api.deleteBookmark(songId) } catch (e: CancellationException) { throw e } catch (_: Throwable) {}
            }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: SubsonicError.Unauthorized) {
            throw e
        } catch (_: Throwable) {
            // Offline or server error: keep what is shown; the next refresh converges.
            return false
        }
    }

    private suspend fun process(job: AlbumResumeSession, playback: CompletedPlayback) {
        val expectedAlbumId = albumResumeRecordingAlbumId(playback) ?: return
        val api = job.api
        try {
            if (session.get() !== job) return
            if (gate?.allows(dev.coda.core.connectivity.ServerGate.Kind.ALBUM_RESUME) == false) return
            val song = api.getSong(playback.songId)
            if (session.get() !== job || !isEligibleAlbumResumeSong(song) ||
                song.albumId?.trim() != expectedAlbumId) return
            val page = api.getAlbum(expectedAlbumId)
            if (session.get() !== job) return
            when (val action = albumResumeCompletionAction(playback, page)) {
                is AlbumResumeCompletionAction.Upsert -> {
                    val comment = albumResumeComment(action.resumeSongId, job.writer) ?: return
                    api.createBookmark(action.anchorSongId, 0, comment)
                    if (session.get() !== job) return
                    val item = AlbumResumeItem(page.album, action.anchorSongId, action.resumeSongId,
                        now().truncatedTo(ChronoUnit.SECONDS).toString())
                    mutableItems.value = (listOf(item) + mutableItems.value.filter {
                        it.album.id != item.album.id && it.anchorSongId != item.anchorSongId
                    }).take(ALBUM_RESUME_RETENTION_LIMIT)
                }
                is AlbumResumeCompletionAction.Delete -> {
                    api.deleteBookmark(action.anchorSongId)
                    if (session.get() !== job) return
                    mutableItems.value = mutableItems.value.filter {
                        it.album.id != page.album.id && it.anchorSongId != action.anchorSongId
                    }
                }
                AlbumResumeCompletionAction.None -> Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // macOS parity: offline or a server error skips this write.
        }
    }
}
