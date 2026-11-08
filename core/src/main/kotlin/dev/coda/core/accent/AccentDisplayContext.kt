package dev.coda.core.accent

/**
 * Port of macOS `ArtworkDisplayContext` (ArtworkTreatment.swift): which artwork drives the app's accent.
 * - [Brand] without an established session;
 * - [Album] on an album page (that album's artwork);
 * - [Standard] everywhere else: the playback identity, which does not depend on play/pause.
 */
sealed interface AccentDisplayContext {
    data object Brand : AccentDisplayContext
    data class Album(val identity: String) : AccentDisplayContext
    data class Standard(val playbackIdentity: String?) : AccentDisplayContext

    companion object {
        fun resolve(connected: Boolean, displayedAlbumId: String?, playbackIdentity: String?): AccentDisplayContext = when {
            !connected -> Brand
            displayedAlbumId != null -> Album(displayedAlbumId)
            else -> Standard(playbackIdentity)
        }
    }
}

/** The outcome of deriving one identity's accent. "Not ready yet" is the absence of an outcome, never [Failed]. */
sealed interface AccentDerivation {
    data class Ready(val selection: AccentSelection) : AccentDerivation
    /** No artwork, a failed fetch or an undecodable cover: the brand fallback accent. */
    data object Failed : AccentDerivation
}

/** The accent a surface shows, and which identity it belongs to (null: the brand theme). */
data class DisplayedAccent(val identity: String?, val selection: AccentSelection) {
    companion object {
        val brandSelection = AccentSelection(ArtworkColor.fallback, AccentSelectionKind.brand)
        val brand = DisplayedAccent(null, brandSelection)
    }
}

/**
 * The one accent policy, shared by every surface: macOS `ArtworkTreatmentSettings.applyDisplayContext`.
 *
 * Prepare, then commit: a context's accent replaces the displayed one only once that identity's derivation has an
 * outcome. Until then the previous valid accent stays; the brand colour is never inserted while artwork loads. The
 * brand/fallback colour appears only without a playback identity, without a session, or after an explicit [AccentDerivation.Failed].
 *
 * Two outputs: [page] follows the display context (album page or playback); [playback] always follows the playback
 * identity (Now Playing, mini player, queue), also on top of another album's page. Not thread-safe: one owner thread.
 */
class AccentDisplayModel {
    val fallback: AccentSelection = fallbackSelection
    private fun brand() = DisplayedAccent.brand

    private val outcomes = HashMap<String, AccentDerivation>()

    var page: DisplayedAccent = DisplayedAccent.brand
        private set
    var playback: DisplayedAccent = DisplayedAccent.brand
        private set

    /** Records (or repairs) the outcome for [identity]; call [apply] afterwards to commit it. */
    fun remember(identity: String, outcome: AccentDerivation) { outcomes[identity] = outcome }

    fun outcome(identity: String): AccentDerivation? = outcomes[identity]

    /**
     * [remembered] is the playback colour committed before the process last ended (one small prefs entry): it becomes the displayed
     * page and playback accent and its identity's Ready outcome, as if it had just been committed. Prepare-then-commit then keeps it until the
     * current subject's own colour is ready, so the first frame after a cold start has a colour.
     */
    fun restore(remembered: DisplayedAccent) {
        val identity = remembered.identity ?: return
        outcomes[identity] = AccentDerivation.Ready(remembered.selection)
        page = remembered; playback = remembered
    }

    /** Drops outcomes no context needs any more, so a later visit derives (and repairs) again. */
    fun retainOnly(identities: Set<String>) { outcomes.keys.retainAll(identities) }

    /**
     * Commits [context] for the page, and the playback identity for the player surfaces (which keep following
     * [playbackIdentity] while an album page is shown). Returns whether anything changed.
     */
    fun apply(context: AccentDisplayContext, playbackIdentity: String?): Boolean {
        val newPage = commit(page, context)
        val newPlayback = if (context == AccentDisplayContext.Brand) brand()
            else commit(playback, AccentDisplayContext.Standard(playbackIdentity))
        val changed = newPage != page || newPlayback != playback
        page = newPage; playback = newPlayback
        return changed
    }

    private fun commit(current: DisplayedAccent, context: AccentDisplayContext): DisplayedAccent {
        val identity = when (context) {
            AccentDisplayContext.Brand -> return brand()
            is AccentDisplayContext.Album -> context.identity
            is AccentDisplayContext.Standard -> context.playbackIdentity ?: return brand()
        }
        return when (val outcome = outcomes[identity]) {
            null -> current // Pending: keep the previous valid accent.
            is AccentDerivation.Ready -> DisplayedAccent(identity, outcome.selection)
            AccentDerivation.Failed -> DisplayedAccent(identity, fallback)
        }
    }

    companion object {
        /** No artwork or a failed derivation: the fallback teal, treated as chromatic. */
        val fallbackSelection = AccentSelection(ArtworkColor.fallback, AccentSelectionKind.chromatic)
    }
}
