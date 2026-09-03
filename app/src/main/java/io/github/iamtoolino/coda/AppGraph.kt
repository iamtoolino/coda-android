package io.github.iamtoolino.coda

import android.content.Context
import io.github.iamtoolino.coda.data.CredentialStore
import io.github.iamtoolino.coda.data.NavidromeClient
import io.github.iamtoolino.coda.data.ServerCredentials
import io.github.iamtoolino.coda.data.queueClientName
import io.github.iamtoolino.coda.data.AlbumResumeCoordinator
import io.github.iamtoolino.coda.data.AlbumResumeWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

internal data class NavidromeSession(
    val client: NavidromeClient,
    val cacheNamespace: String,
    val generation: Long,
)

object AppGraph {
    private var albumResumeScope: CoroutineScope? = null
    internal var albumResume: AlbumResumeCoordinator? = null
        private set
    private lateinit var credentialStore: CredentialStore
    private var queueWriterName = NavidromeClient.CLIENT_NAME
    private var nextGeneration = 0L
    @Volatile
    private var session = NavidromeSession(
        client = NavidromeClient("", "", ""),
        cacheNamespace = "",
        generation = nextGeneration,
    )
    private val _credentials = MutableStateFlow<ServerCredentials?>(null)

    val credentials: StateFlow<ServerCredentials?> = _credentials.asStateFlow()
    val navidrome: NavidromeClient
        get() = session.client
    val cacheNamespace: String
        get() = session.cacheNamespace

    fun initialize(context: Context) {
        if (::credentialStore.isInitialized) return
        credentialStore = CredentialStore(context.applicationContext)
        queueWriterName = queueClientName(context.applicationContext)
        credentialStore.load()?.let(::useCredentials)
    }

    suspend fun login(serverUrl: String, username: String, password: String) {
        val credentials = ServerCredentials(
            serverUrl = NavidromeClient.normalizeServerUrl(serverUrl),
            username = username.trim(),
            password = password,
        )
        require(credentials.username.isNotBlank()) { "Enter your username" }
        require(credentials.password.isNotBlank()) { "Enter your password" }
        val candidate = credentials.toClient()
        candidate.ping()
        withContext(Dispatchers.IO) { credentialStore.save(credentials) }
        useCredentials(credentials)
    }

    fun logout() {
        albumResumeScope?.cancel()
        albumResumeScope = null
        albumResume = null
        credentialStore.clear()
        session = NavidromeSession(
            client = NavidromeClient("", "", ""),
            cacheNamespace = "",
            generation = ++nextGeneration,
        )
        _credentials.value = null
    }

    private fun useCredentials(credentials: ServerCredentials) {
        albumResumeScope?.cancel()
        session = NavidromeSession(
            client = credentials.toClient(),
            cacheNamespace = credentials.cacheNamespace(),
            generation = ++nextGeneration,
        )
        val captured = session
        val resumeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        albumResumeScope = resumeScope
        albumResume = AlbumResumeCoordinator(
            scope = resumeScope,
            loadBookmarks = { withAlbumResumeSession(captured) { bookmarks() } },
            loadSong = { id -> withAlbumResumeSession(captured) { song(id) } },
            loadAlbum = { id -> withAlbumResumeSession(captured) { album(id) } },
            upsert = { id, comment ->
                withAlbumResumeSession(captured) { createBookmark(id, comment) }
            },
            delete = { id -> withAlbumResumeSession(captured) { deleteBookmark(id) } },
            writer = AlbumResumeWriter(NavidromeClient.CLIENT_NAME, "Android", BuildConfig.VERSION_NAME),
        ).also { it.refresh() }
        _credentials.value = credentials
    }

    private suspend fun <T> withAlbumResumeSession(
        captured: NavidromeSession,
        request: suspend NavidromeClient.() -> T,
    ): T {
        currentCoroutineContext().ensureActive()
        ensureSessionGeneration(captured.generation, session.generation)
        return withCurrentSession(request)
    }

    internal fun sessionSnapshot(): NavidromeSession? = session.takeIf { it.client.isConfigured }

    internal fun isCurrent(session: NavidromeSession): Boolean =
        this.session.generation == session.generation

    internal suspend fun <T> withCurrentSession(
        request: suspend NavidromeClient.() -> T,
    ): T {
        val captured = sessionSnapshot() ?: error("Connect a Navidrome server first")
        val result = runCatching { captured.client.request() }
        currentCoroutineContext().ensureActive()
        ensureSessionGeneration(captured.generation, session.generation)
        return result.getOrThrow()
    }

    private fun ServerCredentials.toClient() = NavidromeClient(
        serverUrl = serverUrl,
        username = username,
        password = password,
        queueClientName = queueWriterName,
    )

    private fun ServerCredentials.cacheNamespace(): String = MessageDigest.getInstance("SHA-256")
        .digest("$serverUrl\u0000$username".encodeToByteArray())
        .take(12)
        .joinToString("") { "%02x".format(it) }
}

internal fun ensureSessionGeneration(expected: Long, current: Long) {
    if (expected != current) throw CancellationException("Navidrome account changed")
}
