package io.github.iamtoolino.coda

import android.content.Context
import io.github.iamtoolino.coda.data.CredentialStore
import io.github.iamtoolino.coda.data.NavidromeClient
import io.github.iamtoolino.coda.data.ServerCredentials
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
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
    private lateinit var credentialStore: CredentialStore
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
        credentialStore.clear()
        session = NavidromeSession(
            client = NavidromeClient("", "", ""),
            cacheNamespace = "",
            generation = ++nextGeneration,
        )
        _credentials.value = null
    }

    private fun useCredentials(credentials: ServerCredentials) {
        session = NavidromeSession(
            client = credentials.toClient(),
            cacheNamespace = credentials.cacheNamespace(),
            generation = ++nextGeneration,
        )
        _credentials.value = credentials
    }

    internal fun sessionSnapshot(): NavidromeSession? = session.takeIf { it.client.isConfigured }

    internal fun isCurrent(session: NavidromeSession): Boolean =
        this.session.generation == session.generation

    private fun ServerCredentials.toClient() = NavidromeClient(serverUrl, username, password)

    private fun ServerCredentials.cacheNamespace(): String = MessageDigest.getInstance("SHA-256")
        .digest("$serverUrl\u0000$username".encodeToByteArray())
        .take(12)
        .joinToString("") { "%02x".format(it) }
}
