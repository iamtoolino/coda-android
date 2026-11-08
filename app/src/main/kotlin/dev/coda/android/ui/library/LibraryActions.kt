package dev.coda.android.ui.library

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.coda.android.session.Session
import dev.coda.android.session.SessionRepository
import dev.coda.core.subsonic.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class RatingState(val overrides: Map<String, Int> = emptyMap(), val busy: Boolean = false, val failed: Boolean = false, val failedId: String? = null)

/** Session-scoped optimistic ratings shared by album rows and the player. */
class LibraryActions internal constructor(
    private val current: () -> Boolean,
    private val unauthorized: suspend () -> Unit,
    private val writeRating: suspend (String, Int) -> Unit,
) : ViewModel() {
    constructor(session: Session, sessions: SessionRepository) : this(
        { sessions.isCurrent(session.token) }, { sessions.unauthorized(session.token) }, session.client::setRating)
    private val mutable = MutableStateFlow(RatingState())
    val ratings = mutable.asStateFlow()
    private var activeId: String? = null
    private var desired = 0
    fun rate(id: String, value: Int) {
        if (!current() || (activeId != null && activeId != id)) return
        desired = value.coerceIn(0, 5)
        if (activeId == id) {
            mutable.value = mutable.value.copy(overrides = mutable.value.overrides + (id to desired))
            return
        }
        activeId = id
        var confirmed = mutable.value.overrides
        mutable.value = RatingState(confirmed + (id to desired), busy = true)
        viewModelScope.launch {
            try {
                do {
                    val sending = desired
                    writeRating(id, sending)
                    ensureActive()
                    if (!current()) return@launch
                    confirmed = confirmed + (id to sending)
                } while (sending != desired)
                mutable.value = mutable.value.copy(busy = false)
            } catch (e: CancellationException) { throw e }
            catch (_: SubsonicError.Unauthorized) { if (current()) { mutable.value = RatingState(confirmed, failed = true, failedId = id); unauthorized() } }
            catch (_: Exception) { if (current()) mutable.value = RatingState(confirmed, failed = true, failedId = id) }
            finally { activeId = null }
        }
    }

}

internal val LocalLibraryActions = staticCompositionLocalOf<LibraryActions?> { null }
internal val LocalLibrarySession = staticCompositionLocalOf<Session?> { null }
internal val LocalOpenPlaylist = staticCompositionLocalOf<(String) -> Unit> { {} }
internal val LocalOpenAlbum = staticCompositionLocalOf<(String) -> Unit> { {} }
/** Opens the artist page of a playing track, given its album and song IDs. */
internal val LocalOpenTrackArtist = staticCompositionLocalOf<(albumId: String, songId: String) -> Unit> { { _, _ -> } }
/** Opens an artist page by id (Now Playing's per-artist taps; the player collapses first). */
internal val LocalOpenArtist = staticCompositionLocalOf<(artistId: String) -> Unit> { {} }
/** The artists (ids and names) credited on a playing track, from its album page; empty when unknown. */
internal val LocalTrackArtists = staticCompositionLocalOf<suspend (albumId: String, songId: String) -> List<dev.coda.core.subsonic.ArtistReference>> { { _, _ -> emptyList() } }
