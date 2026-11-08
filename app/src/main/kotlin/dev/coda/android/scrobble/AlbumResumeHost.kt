package dev.coda.android.scrobble

import android.content.Context
import dev.coda.android.playback.PlaybackDependencies
import dev.coda.android.session.SessionRepository
import dev.coda.android.session.SessionState
import dev.coda.core.subsonic.AlbumPage
import dev.coda.core.subsonic.Song
import dev.coda.core.sync.AlbumResumeApi
import dev.coda.core.sync.AlbumResumeCoordinator
import dev.coda.core.sync.AlbumResumeSession
import dev.coda.core.sync.AlbumResumeWriter
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** App-scope wiring of the album-resume bookmark writer; same pattern as [ScrobbleHost]. */
class AlbumResumeHost(context: Context, scope: CoroutineScope, sessions: SessionRepository,
    gate: dev.coda.core.connectivity.ServerGate? = null) {
    val coordinator = AlbumResumeCoordinator(scope, gate = gate)
    private var activeToken: UUID? = null
    private val appVersion = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "0"

    init {
        PlaybackDependencies.eventSinks = PlaybackDependencies.eventSinks + { coordinator.onEvent(it) }
        scope.launch {
            sessions.state.collect { state ->
                val session = (state as? SessionState.Connected)?.session
                if (session?.token == activeToken) return@collect
                activeToken = session?.token
                coordinator.connect(session?.let { s ->
                    val client = s.client
                    AlbumResumeSession(object : AlbumResumeApi {
                        override suspend fun getSong(id: String): Song = client.getSong(id)
                        override suspend fun getAlbum(id: String): AlbumPage = s.recentAlbums.get(id)
                        override suspend fun getBookmarks() = client.getBookmarks()
                        override suspend fun createBookmark(songId: String, positionMilliseconds: Long, comment: String) =
                            client.createBookmark(songId, positionMilliseconds, comment)
                        override suspend fun deleteBookmark(songId: String) = client.deleteBookmark(songId)
                    }, AlbumResumeWriter(client.configuration.clientName, "Android", appVersion), s.token)
                }, onUnauthorized = { session?.let { scope.launch { sessions.unauthorized(it.token) } } })
            }
        }
    }
}
