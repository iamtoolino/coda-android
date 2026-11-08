package dev.coda.core.accent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The app's single accent source (macOS `PlaybackArtworkCoordinator` + `ArtworkTreatmentSettings`).
 * Every surface reads [page] (backdrops, buttons, pills, Settings) or [playback] (Now Playing, mini player,
 * queue). Inputs are the session's derivation function, the playback identity and the displayed album; this class
 * derives what they need, cancels stale work and commits through [AccentDisplayModel] (prepare, then commit).
 *
 * Owned at app level and driven from one thread: [scope] must run on that thread (the main dispatcher in the app).
 */
/**
 * The last committed playback colour with the subject it belongs to ([identity] as the resolver keys it, e.g. `playing:album:<id>`, and
 * its [coverId]), kept by the app across process death so a cold start opens in it.
 */
data class PlaybackAccentMemory(val identity: String, val coverId: String, val selection: AccentSelection)

/**
 * Fix 1: a kept [memory] belongs to one account ([accountKey] is the hashed server + user key, never the address, user name or password).
 * It is used only for the same account; another user or server, or no stored account, gets nothing.
 */
data class AccountPlaybackAccent(val accountKey: String, val memory: PlaybackAccentMemory) {
    fun forAccount(current: String?): PlaybackAccentMemory? = memory.takeIf { current != null && current == accountKey }
}

class AccentResolver(private val scope: CoroutineScope) {
    private data class Subject(val identity: String, val coverId: String?)
    private class Derivation(val coverId: String?, val job: Job?, val retry: Job? = null) {
        fun cancel() { job?.cancel(); retry?.cancel() }
    }

    private val model = AccentDisplayModel()
    private val mutablePage = MutableStateFlow(DisplayedAccent.brand)
    private val mutablePlayback = MutableStateFlow(DisplayedAccent.brand)
    val page: StateFlow<DisplayedAccent> = mutablePage.asStateFlow()
    val playback: StateFlow<DisplayedAccent> = mutablePlayback.asStateFlow()

    private val mutableDockTint = MutableStateFlow(.12f)
    val dockTint: StateFlow<Float> = mutableDockTint.asStateFlow()

    private val mutablePageTintReady = MutableStateFlow(false)
    val pageTintReady: StateFlow<Boolean> = mutablePageTintReady.asStateFlow()
    private val mutableArtworkRevision = MutableStateFlow(0L)
    val artworkRevision: StateFlow<Long> = mutableArtworkRevision.asStateFlow()

    /** Told about every newly committed Ready playback colour (the app persists it for the next cold start). Main thread. */
    var onPlaybackCommitted: ((PlaybackAccentMemory) -> Unit)? = null
    private var remembered: PlaybackAccentMemory? = null

    private var session: Any? = null
    private var derive: (suspend (coverId: String) -> AccentSelection)? = null
    /** The session's in-memory accent cache, read synchronously so a cached album page commits at the tap. */
    private var peek: (coverId: String) -> AccentSelection? = { null }
    private var playing: Subject? = null
    /** The displayed album page: its id, plus its cover once known (null [Subject] cover with [albumCoverKnown] = coverless). */
    private var albumId: String? = null
    private var albumCover: String? = null
    private var albumCoverKnown = false
    private val derivations = HashMap<String, Derivation>()
    /**
     * Album pages left this session, with the cover their accent came from. Their Ready outcome is kept (not dropped
     * by [AccentDisplayModel.retainOnly]), so a revisit commits the album's colour at once, even before its cover is known.
     */
    private val retainedAlbums = object : LinkedHashMap<String, String?>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?) = size > RETAINED_ALBUMS
    }

    /**
     * A session is established: [derive] computes a cover's accent (throws on failure); [peek] returns an accent already in
     * the session's memory cache without blocking, or null. Replaces any earlier session.
     */
    fun attach(session: Any, derive: suspend (coverId: String) -> AccentSelection) = attach(session, { null }, derive = derive)

    /**
     * [sessionKey] identifies the signed-in session across screens (the app passes the session token): several attached objects
     * (a stale or second screen of the same session) share one key, and a [forgetPlayback] is tied to the key, not the object.
     */
    fun attach(
        session: Any, peek: (coverId: String) -> AccentSelection?, sessionKey: Any = session,
        derive: suspend (coverId: String) -> AccentSelection,
    ) {
        if (this.session === session) {
            // Rebinding can replace a loader after activity restoration.
            this.derive = derive; this.peek = peek
            retryFailed()
            return
        }
        // A forgotten subject stays forgotten until a different session (another key) attaches: a stale screen of the old one cannot clear it.
        if (forgotten && sessionKey != forgottenFor) forgotten = false
        reset()
        this.session = session; lastKey = sessionKey
        this.derive = derive; this.peek = peek
        refresh()
    }

    /**
     * The session ended (sign-out, server switch): back to the brand theme. Ignored for a session already replaced. The playback subject is
     * kept on purpose (a recreated Activity of the same session); to end it with the account, call [forgetPlayback] too.
     */
    fun detach(session: Any) {
        if (this.session !== session) return
        reset()
        refresh()
    }

    /**
     * The signed-in account's playback subject ends with the session (sign-out, account change): the resolver forgets the subject and
     * the kept colour, so the next account's first frame is neutral and nothing derives or stores the old subject again. [detach] alone
     * keeps the subject on purpose (a recreated Activity of the same session).
     */
    fun forgetPlayback() {
        // Sticks until the next session attaches: a setPlayback in the frame before the old shell is removed (the 300 ms seed timer, a
        // player event) must not bring the old subject back.
        forgotten = true; forgottenFor = lastKey
        playing = null
        remembered = null
        refresh()
    }

    /**
     * A cold start begins with the playback colour the previous process committed last ([memory]): the subject stays the playback
     * subject and its colour the displayed one until the shell reports the current subject and that subject's colour is ready (the normal
     * prepare-then-commit path). Only into a fresh resolver: ignored once a session or a playback subject is known.
     */
    fun restore(memory: PlaybackAccentMemory) {
        if (session != null || playing != null) return
        playing = Subject(memory.identity, memory.coverId)
        remembered = memory
        model.restore(DisplayedAccent(memory.identity, memory.selection))
        publish(null)
    }

    /**
     * The current queue item: [identity] is stable per item and independent of play/pause; [coverId] is its artwork
     * (null: no artwork, the fallback accent). A null identity means nothing is queued (the brand theme).
     */
    fun setPlayback(identity: String?, coverId: String?) {
        if (forgotten) return
        val subject = identity?.let { Subject("playing:$it", coverId) }
        if (subject == playing) return
        playing = subject
        refresh()
    }

    /**
     * The album page on screen, or null. [coverKnown] is false while the page's metadata is still loading: the previous
     * accent then stays until the album's accent is ready.
     */
    fun showAlbum(albumId: String?, coverId: String? = null, coverKnown: Boolean = false, owner: Any? = null) {
        // A retiring Activity must not clear the foreground Activity's restored context.
        if (owner != null && owner !== session) return
        if (albumId == this.albumId && coverId == albumCover && coverKnown == albumCoverKnown) return
        this.albumId = albumId; albumCover = coverId; albumCoverKnown = albumId != null && coverKnown
        refresh()
    }

    /**
     * The page of [albumId] left composition. It clears the album context only while that album is still the displayed
     * one: during a page transition the outgoing album leaves after the incoming page has already been shown.
     */
    fun leaveAlbum(albumId: String, owner: Any? = null) {
        if (owner != null && owner !== session) return
        if (this.albumId == albumId) showAlbum(null, owner = owner)
    }

    /** Refresh keeps the committed colour until the replacement is ready. */
    fun artworkChanged(covers: Set<String>) {
        mutableArtworkRevision.value += 1
        retainedAlbums.entries.removeAll { it.value in covers }
        derivations.entries.filter { it.value.coverId in covers }.map { it.key }.forEach {
            derivations.remove(it)?.cancel()
        }
        refresh()
    }

    /** Foreground/network recovery: restart unresolved work, including requests stuck on the old connection. */
    fun retryFailed() {
        derivations.entries.filter { (identity, d) -> d.coverId != null && (d.job != null || model.outcome(identity) !is AccentDerivation.Ready) }
            .map { it.key }.forEach { derivations.remove(it)?.cancel() }
        refresh()
    }

    /** A leaf tile's own accent (rating badge, resume bar), outside the page context. Null without a session or when the derivation fails (no tint; the caller retries). */
    suspend fun tileAccent(coverId: String?): AccentSelection? {
        val derive = derive ?: return null
        if (coverId == null) return model.fallback
        return try { derive(coverId).takeIf { it.kind != AccentSelectionKind.brand } } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }

    private var forgotten = false
    private var forgottenFor: Any? = null
    /** The key of the session attached last; survives [detach], so a forget after the shell left still knows whose it was. */
    private var lastKey: Any? = null

    private fun reset() {
        derivations.values.forEach { it.cancel() }
        derivations.clear()
        model.retainOnly(emptySet())
        retainedAlbums.clear()
        session = null; derive = null; peek = { null }
        remembered = null
    }

    private fun refresh() {
        val derive = derive
        val album = albumId?.let { id -> Subject("album:$id", albumCover).takeIf { albumCoverKnown } }
        val wanted = if (derive == null) emptyList() else listOfNotNull(playing, album).distinctBy { it.identity }
        val identities = wanted.map { it.identity }.toSet()
        derivations.keys.filter { it !in identities }.forEach { derivations.remove(it)?.cancel() }
        if (album != null) retainedAlbums[album.identity] = album.coverId
        // Left album pages keep a Ready colour for the next visit; a failed one is derived again.
        model.retainOnly(identities + retainedAlbums.keys.filter { model.outcome(it) is AccentDerivation.Ready })
        if (derive != null) wanted.forEach { subject -> ensureDerived(subject, derive, 0) }
        commit()
    }

    private fun ensureDerived(subject: Subject, derive: suspend (String) -> AccentSelection, attempt: Int) {
        val existing = derivations[subject.identity]
        if (existing != null && existing.coverId == subject.coverId) return
        existing?.cancel()
        val cover = subject.coverId
        if (cover == null) {
            derivations[subject.identity] = Derivation(null, null)
            model.remember(subject.identity, AccentDerivation.Failed)
            return
        }
        // A cover whose accent is already in memory commits in this call, before the page draws its first frame.
        peek(cover)?.let { cached ->
            derivations[subject.identity] = Derivation(cover, null)
            model.remember(subject.identity, if (cached.kind == AccentSelectionKind.brand) AccentDerivation.Failed else AccentDerivation.Ready(cached))
            return
        }
        lateinit var request: Derivation
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val outcome = try { derive(cover).let { if (it.kind == AccentSelectionKind.brand) AccentDerivation.Failed else AccentDerivation.Ready(it) } }
                catch (e: CancellationException) {
                    // A cancelled loader must not leave a permanently registered in-flight request.
                    if (derivations[subject.identity] === request) derivations.remove(subject.identity)
                    throw e
                } catch (_: Exception) { AccentDerivation.Failed }
            val current = derivations[subject.identity]
            if (current !== request || this@AccentResolver.derive !== derive) return@launch
            // A failed cover (offline, server warming up) is retried with backoff, no external trigger needed.
            val retry = if (outcome == AccentDerivation.Failed) scope.launch {
                delay(backoffMillis(attempt))
                if (derivations[subject.identity]?.coverId != cover || this@AccentResolver.derive !== derive) return@launch
                derivations.remove(subject.identity)
                ensureDerived(subject, derive, attempt + 1)
            } else null
            derivations[subject.identity] = Derivation(cover, null, retry)
            model.remember(subject.identity, outcome)
            commit()
        }
        request = Derivation(cover, job)
        derivations[subject.identity] = request
        job.start()
    }

    private fun backoffMillis(attempt: Int): Long = RETRY_DELAYS_MS.getOrElse(attempt) { RETRY_DELAYS_MS.last() }

    private fun commit() {
        val context = AccentDisplayContext.resolve(derive != null, albumId?.let { "album:$it" }, playing?.identity)
        model.apply(context, playing?.identity)
        val identity = when (context) {
            AccentDisplayContext.Brand -> null
            is AccentDisplayContext.Album -> context.identity
            is AccentDisplayContext.Standard -> context.playbackIdentity
        }
        mutablePageTintReady.value = identity != null && derivations[identity]?.job == null && model.outcome(identity) is AccentDerivation.Ready
        publish(model.page.selection.takeIf { context is AccentDisplayContext.Album && model.page.identity == context.identity })
        val subject = playing
        val cover = subject?.coverId
        if (subject != null && cover != null && model.playback.identity == subject.identity && model.outcome(subject.identity) is AccentDerivation.Ready) {
            val memory = PlaybackAccentMemory(subject.identity, cover, model.playback.selection)
            if (memory != remembered) { remembered = memory; onPlaybackCommitted?.invoke(memory) }
        }
    }

    private fun publish(albumPage: AccentSelection?) {
        mutablePage.value = model.page
        mutablePlayback.value = model.playback
        mutableDockTint.value = OneVoice.dockTint(model.playback.selection, albumPage)
    }

    private companion object {
        val RETRY_DELAYS_MS = longArrayOf(3_000, 10_000, 30_000, 60_000)
        const val RETAINED_ALBUMS = 512
    }
}
