package dev.coda.android.session

import dev.coda.android.net.HttpClientFactory
import dev.coda.android.net.ServerContact
import dev.coda.core.subsonic.ServerAnswer
import dev.coda.core.subsonic.ServerProbe
import dev.coda.core.subsonic.SubsonicClient
import dev.coda.core.subsonic.SubsonicConfig
import dev.coda.core.subsonic.SubsonicError
import java.util.UUID
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class Session(val client: SubsonicClient, val token: UUID = UUID.randomUUID()) {
    /** Album pages shared by the background readers (album resume, offline catalogue) of this session. */
    // elapsedRealtime keeps counting while the phone sleeps (nanoTime stops), so a page is never reused across a night.
    val recentAlbums = dev.coda.core.subsonic.RecentAlbumPages(client::getAlbum, nowMs = android.os.SystemClock::elapsedRealtime)
    @Volatile private var transcodeOffset: Boolean? = null

    /**
     * Whether the server advertises OpenSubsonic `transcodeOffset`. Blocking (the stream loader's thread); asked
     * once per session, and again after a failed request (no server answer is not taken as "unsupported" for good).
     */
    fun supportsTranscodeOffset(): Boolean = transcodeOffset ?: try {
        kotlinx.coroutines.runBlocking { dev.coda.core.playback.TranscodeOffset.supportedBy(client.openSubsonicExtensions()) }
    } catch (_: dev.coda.core.subsonic.SubsonicError.Server) {
        false // A server without OpenSubsonic answers with an error or 404: it has no extensions.
    } catch (_: dev.coda.core.subsonic.SubsonicError.NotFound) {
        false
    } catch (error: dev.coda.core.subsonic.SubsonicError.Http) {
        if (error.status != 404) return false
        false
    } catch (_: Exception) {
        return false
    }.also { transcodeOffset = it }

    /** [supportsTranscodeOffset]'s answer once asked, without asking; null until then. */
    fun knownTranscodeOffset(): Boolean? = transcodeOffset
}
enum class LoginField { Server, Username, Password }
data class LoginError(val field: LoginField, val message: String)
sealed interface SessionState {
    /** The start, until [SessionRepository.restore] has read the saved sign-in (shown as the plain background, no login page). */
    data object Restoring : SessionState
    data object SignedOut : SessionState
    data object Connecting : SessionState
    class Connected(val session: Session, val verified: Boolean) : SessionState
    class NeedsLogin(val session: Session) : SessionState
}

/**
 * [url] as it may be persisted (credentials, recent servers): without query or fragment; null when
 * it carries user info (never stored, not even stripped: such an address did not come from the user).
 */
internal fun credentialFreeServerUrl(url: String): String? {
    val trimmed = url.trim()
    val parsed = trimmed.toHttpUrlOrNull() ?: return null
    if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
    return trimmed.substringBefore('#').substringBefore('?')
}

internal object LoginMessages {
    const val UNTRUSTED = "The server's certificate isn't trusted."
    const val UNREACHABLE = "Can't reach the server. Check the address and your network."
    /** A TLS failure that is not about the certificate (protocol or cipher mismatch, handshake cut off). */
    const val NO_SECURE_CONNECTION = "Couldn't set up a secure connection to the server."

    /** The message for a network failure: only certificate problems are "not trusted". */
    fun network(error: Throwable): String = when {
        error !is SSLException -> UNREACHABLE
        ServerProbe.isCertificateProblem(error) -> UNTRUSTED
        else -> NO_SECURE_CONNECTION
    }
}

/**
 * [input] (an address without a scheme) names a server that already worked over https: one of [known]
 * is https on the same canonical host (no DNS root dot) and port. Such a server never falls back to http.
 */
internal fun seenOverHttps(input: String, known: List<String>): Boolean = known.any { HttpsHistory.sameHttpsServer(input, it) }

/** Server check for the sign-in field: nothing typed yet, waiting for the answer, or the outcome. */
sealed interface ServerCheck {
    data object Checking : ServerCheck
    /** [url] is the resolved origin (with the scheme that answered); [subsonic] is false for a non-Subsonic answer. */
    data class Found(val url: String, val subsonic: Boolean) : ServerCheck
    /** [untrusted]: a certificate problem; [message]: what Sign in reports for this failure. */
    data class Failed(
        val untrusted: Boolean,
        val message: String = if (untrusted) LoginMessages.UNTRUSTED else LoginMessages.UNREACHABLE,
        /** LG-4, information only: some other web server answered over http (see [SubsonicError.Network.otherServerAnswered]). */
        val otherServer: Boolean = false,
    ) : ServerCheck
}

class SessionRepository internal constructor(
    private val store: CredentialStorage,
    private val clientName: String,
    private val autoLoginPreference: AutoLoginPreference = NoAutoLoginPreference,
    /**
     * The sign-in check; an address without `scheme://` becomes https, or http under [ServerProbe]'s rules. The
     * flag says the server worked over https before (then never http).
     */
    private val resolveServer: suspend (String, Boolean) -> ServerAnswer =
        { input, httpsSeenBefore -> ServerProbe(HttpClientFactory.client, clientName).resolve(input, httpsSeenBefore) },
    /** A successful manual sign-in records its resolved server (recent servers). */
    private val recordServer: suspend (String) -> Unit = {},
    /** Did this address work over https before ([HttpsHistory.contains]; waits for the stored history). */
    private val httpsSeen: suspend (String) -> Boolean = { false },
    /** An https server answered (sign-in or a Subsonic check answer): [HttpsHistory.add]. */
    private val recordHttps: suspend (String) -> Unit = {},
    /** A manual sign-in to this plain-http address succeeded, so the user agreed to it: [PlainHttpConsent.agree]. */
    private val recordPlainHttp: suspend (String) -> Unit = {},
    private val httpClient: () -> okhttp3.OkHttpClient = { HttpClientFactory.client },
    /**
     * A sign-in the server verified, after its login was saved and before the session is published (so nothing of
     * the new account was written yet): the offline storage of every other account goes. Never for a failed, cancelled
     * or superseded sign-in, a restore or a sign-out. A failure here does not fail the sign-in.
     */
    private val onVerifiedSignIn: suspend (Session) -> Unit = {},
    /** Before the login is cleared, while it still works: send what is unsent. The hook bounds its own time; a failure never stops the sign-out. */
    private val beforeSignOut: suspend () -> Unit = {},
    private val verify: suspend (Session) -> Unit,
) {
    constructor(store: CredentialStore) : this(store, DeviceClientName.get(), verify = { it.client.ping() })
    internal constructor(
        store: CredentialStore, preference: AutoLoginPreference, recordServer: suspend (String) -> Unit = {},
        httpsHistory: HttpsHistory? = null, plainHttpConsent: PlainHttpConsent? = null,
        onVerifiedSignIn: suspend (Session) -> Unit = {}, beforeSignOut: suspend () -> Unit = {},
    ) : this(store, DeviceClientName.get(), preference, recordServer = recordServer,
        httpsSeen = { httpsHistory?.contains(it) == true }, recordHttps = { httpsHistory?.add(it) },
        recordPlainHttp = { plainHttpConsent?.agree(it) }, onVerifiedSignIn = onVerifiedSignIn, beforeSignOut = beforeSignOut,
        verify = { it.client.ping() })
    private val mutex = Mutex()
    private var generation = 0L
    // Restoring until restore() ends it; the stable state (what a failed sign-in falls back to) starts SignedOut.
    private val mutableState = MutableStateFlow<SessionState>(SessionState.Restoring)
    val state = mutableState.asStateFlow()
    private var stableState: SessionState = SessionState.SignedOut
    private fun publish(value: SessionState) {
        mutableState.value = value
        if (value !is SessionState.Connecting) stableState = value
    }
    private suspend fun session(value: Credentials) = withContext(kotlinx.coroutines.Dispatchers.Default) {
        Session(SubsonicClient(SubsonicConfig(value.serverUrl, value.username, value.password, clientName), httpClient(),
            issue = ServerContact::issue))
    }
    fun isCurrent(token: UUID): Boolean = when (val current = state.value) {
        is SessionState.Connected -> current.session.token == token
        else -> false
    }
    /** The hashed server + user key of the stored sign-in (null: none or unreadable), for account-bound caches that load before [restore]. */
    suspend fun storedAccountKey(): String? = try {
        store.read()?.let { dev.coda.core.cache.TrackCache.serverKey(it.serverUrl, it.username) }
    } catch (error: Exception) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        null
    }

    /** Ends [SessionState.Restoring] on every path: Connected with a usable saved sign-in, else SignedOut (none, unreadable, cancelled). */
    suspend fun restore() {
        val restored = mutex.withLock {
            try {
                if (generation != 0L) return
                val credentials = try { store.read() } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    null
                } ?: return
                val session = try { session(credentials) } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    store.clear(); return
                }
                publish(SessionState.Connected(session, false))
                session to generation
            } finally {
                if (mutableState.value === SessionState.Restoring) publish(SessionState.SignedOut)
            }
        }
        try {
            verify(restored.first)
            mutex.withLock {
                if (generation == restored.second) {
                    recordRestoredHttps(restored.first)
                    publish(SessionState.Connected(restored.first, true))
                }
            }
        } catch (error: SubsonicError) {
            if (error is SubsonicError.Unauthorized) mutex.withLock {
                if (generation == restored.second) {
                    recordRestoredHttps(restored.first) // the server answered over https, only the password is wrong
                    publish(SessionState.NeedsLogin(restored.first))
                }
            }
        }
    }

    /** A restored https session protects its server from the http fallback even if no history knew it yet. */
    private suspend fun recordRestoredHttps(session: Session) {
        val url = session.client.configuration.serverUrl
        if (!url.startsWith("https://", ignoreCase = true)) return
        withContext(NonCancellable) { credentialFreeServerUrl(url)?.let { runCatching { recordHttps(it) } } }
    }
    /**
     * The unauthenticated check behind the sign-in server chip; never sends credentials. A typed scheme is
     * checked as it is, otherwise https first and http only when https got no answer at all, and never for a server
     * that worked over https before (the https history or the current session). The history is read (and
     * waited for) before the probe starts.
     */
    suspend fun checkServer(input: String): ServerCheck? = try {
        val seen = !ServerProbe.hasScheme(input) && (seenOverHttps(input, listOfNotNull(currentServerUrl())) || seenInHistory(input))
        val answer = resolveServer(input, seen)
        if (answer.secure && answer.subsonic) runCatching { recordHttps(answer.url) }
        ServerCheck.Found(answer.url, answer.subsonic)
    } catch (error: SubsonicError.InvalidConfiguration) {
        null
    } catch (error: SubsonicError.Network) {
        val certificate = error.exception is SSLException && ServerProbe.isCertificateProblem(error.exception)
        ServerCheck.Failed(untrusted = certificate, message = LoginMessages.network(error.exception), otherServer = error.otherServerAnswered)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        ServerCheck.Failed(untrusted = false)
    }

    /** The https history, failing closed: if it cannot be read, the address counts as seen (no http fallback). */
    private suspend fun seenInHistory(input: String): Boolean = try {
        httpsSeen(input)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        true
    }

    /** The server of the saved session (signed in, or waiting for its password again). */
    private fun currentServerUrl(): String? = when (val current = state.value) {
        is SessionState.Connected -> current.session.client.configuration.serverUrl
        is SessionState.NeedsLogin -> current.session.client.configuration.serverUrl
        else -> null
    }

    /**
     * [onConnected] runs on the caller's thread right before the new session is published, while the sign-in UI is
     * still composed (the password-manager save prompt is committed there).
     */
    suspend fun login(server: String, username: String, password: String, onConnected: () -> Unit = {}): LoginError? =
        login(server, username, password, manual = true, onConnected)

    internal suspend fun loginAutomatically(server: String, username: String, password: String): LoginError? =
        login(server, username, password, manual = false) {}

    private suspend fun login(server: String, username: String, password: String, manual: Boolean, onConnected: () -> Unit): LoginError? {
        val normalized = try { SubsonicConfig.normalizeServerURL(server) } catch (_: Exception) { null }
        val url = normalized?.toHttpUrlOrNull()
        if (url == null || url.username.isNotEmpty() || url.password.isNotEmpty())
            return LoginError(LoginField.Server, "That doesn't look like a server address.")
        if (username.isBlank()) return LoginError(LoginField.Username, "Enter a username.")
        if (password.isEmpty()) return LoginError(LoginField.Password, "Enter a password.")
        val (attempt, previous) = mutex.withLock {
            if (!manual && (stableState != SessionState.SignedOut || autoLoginPreference.disabled())) return null
            val previous = stableState
            ++generation
            if (previous !is SessionState.NeedsLogin) publish(SessionState.Connecting)
            generation to previous
        }
        try {
            // Authenticates exactly this URL: an address without a scheme means https (the sign-in screen resolves and
            // shows the scheme first), so credentials never go to an http URL the user has not seen.
            val typedUrl = requireNotNull(normalized)
            val checked = session(Credentials(typedUrl, username.trim(), password))
            verify(checked)
            // An http server that upgraded to https on the same host (RedirectGuard) is kept as its https address.
            val serverUrl = checked.client.upgradedServerUrl?.let(::credentialFreeServerUrl) ?: typedUrl
            val credentials = Credentials(serverUrl, username.trim(), password)
            val session = if (serverUrl == typedUrl) checked else session(credentials)
            mutex.withLock {
                if (generation != attempt) return null
                // Disk mutation and publication must complete together, even if the UI caller leaves.
                withContext(NonCancellable) {
                    store.write(credentials)
                    // Every https sign-in (also the automatic one): this server never falls back to http again.
                    if (serverUrl.startsWith("https://", ignoreCase = true)) credentialFreeServerUrl(serverUrl)?.let { runCatching { recordHttps(it) } }
                    if (manual) {
                        autoLoginPreference.setDisabled(false)
                        // The sign-in screen sends nothing to an http address before the user agreed ("Sign in
                        // anyway"), and the re-login sheet uses the saved, already agreed server: remember the address
                        // the user agreed to (the typed one, also when the server upgraded to https).
                        if (PlainHttpConsent.isPlainHttp(typedUrl)) runCatching { recordPlainHttp(typedUrl) }
                        credentialFreeServerUrl(serverUrl)?.let { runCatching { recordServer(it) } }
                        runCatching(onConnected)
                    }
                    // The pass logs its own problems and leaves its marker for the next start.
                    runCatching { onVerifiedSignIn(session) }
                    publish(SessionState.Connected(session, true))
                }
            }
            return null
        } catch (error: Exception) {
            withContext(NonCancellable) {
                mutex.withLock { if (generation == attempt) publish(previous) }
            }
            if (error is CancellationException) throw error
            return when {
                error is SubsonicError.Unauthorized -> LoginError(LoginField.Password, "Username or password is wrong.")
                error is SubsonicError.Network -> LoginError(LoginField.Server, LoginMessages.network(error.exception))
                error is SubsonicError -> LoginError(LoginField.Server, "Couldn't sign in. Check the server and try again.")
                else -> LoginError(LoginField.Password, "Couldn't save the login securely. Please try again.")
            }
        }
    }
    suspend fun signOut() = mutex.withLock {
        if (state.value is SessionState.Connected) {
            // Not cancellable (a cancelled caller must still sign out); the hook bounds its own time.
            withContext(NonCancellable) { try { beforeSignOut() } catch (_: Exception) { /* never stops the sign-out */ } }
        }
        ++generation
        withContext(NonCancellable) {
            autoLoginPreference.setDisabled(true)
            store.clear()
            publish(SessionState.SignedOut)
        }
    }
    suspend fun unauthorized(token: UUID) = mutex.withLock {
        val current = state.value as? SessionState.Connected ?: return@withLock
        if (current.session.token == token) {
            ++generation // A pending restore must not re-verify this rejected session.
            publish(SessionState.NeedsLogin(current.session))
        }
    }
}
