package dev.coda.android.ui.album

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.coda.android.session.Session
import dev.coda.android.session.SessionRepository
import dev.coda.core.library.SnapshotStore
import dev.coda.core.subsonic.SubsonicError
import dev.coda.core.subsonic.AlbumPage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import dev.coda.android.ui.common.retryWhenBack

/** [page] may be a saved snapshot while [loading]; [failed] with a page means "showing the saved copy". */
data class AlbumState(val page: AlbumPage? = null, val loading: Boolean = true, val failed: Boolean = false,
    /** Live answers so far (the way back waits for the next one). */
    val answers: Int = 0)
class AlbumViewModel(private val session: Session, private val id: String, private val sessions: SessionRepository,
    private val snapshots: SnapshotStore? = null, recovered: Flow<Unit> = emptyFlow()) : ViewModel() {
    private val mutable = MutableStateFlow(AlbumState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    init { refresh(); viewModelScope.retryWhenBack(recovered, { mutable.value.failed }, ::refresh) }
    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            mutable.value = mutable.value.copy(loading = true, failed = false)
            if (mutable.value.page == null && snapshots != null) {
                // Stale-while-revalidate: show the saved copy at once, then refresh it.
                val saved = withContext(Dispatchers.IO) { snapshots.album(id) }
                if (saved != null && mutable.value.page == null) mutable.value = mutable.value.copy(page = saved)
            }
            try {
                val result = session.client.getAlbum(id)
                if (sessions.isCurrent(session.token)) {
                    mutable.value = AlbumState(result, false, answers = mutable.value.answers + 1)
                    snapshots?.let { withContext(Dispatchers.IO) { it.putAlbum(result) } }
                } else mutable.value = mutable.value.copy(loading = false, failed = true)
            }
            catch (e: CancellationException) { throw e }
            catch (e: SubsonicError.Unauthorized) {
                sessions.unauthorized(session.token)
                mutable.value = mutable.value.copy(loading = false, failed = true)
            }
            catch (_: Exception) { mutable.value = mutable.value.copy(loading = false, failed = true) }
        }
    }
}
