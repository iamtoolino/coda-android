package dev.coda.android.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class SessionRepositoryTest {
    private val credentials = Credentials("https://synthetic.example", "synthetic", "synthetic-password")

    private class Store : CredentialStorage {
        var saved: Credentials? = null
        var afterMutation: suspend () -> Unit = {}
        override suspend fun read() = saved
        override suspend fun write(value: Credentials) = withContext(Dispatchers.IO) {
            saved = value
            afterMutation()
        }
        override suspend fun clear() = withContext(Dispatchers.IO) {
            saved = null
            afterMutation()
        }
    }

    private class Preference : AutoLoginPreference {
        var value = false
        override suspend fun disabled() = value
        override suspend fun setDisabled(value: Boolean) { this.value = value }
    }

    @Test(timeout = 10_000) fun signOutSuppressesAutomaticLoginAcrossRepositoryRecreation() = runBlocking {
        val store = Store()
        val preference = Preference()
        val repository = SessionRepository(store, "test", preference) {}
        repository.loginAutomatically(credentials.serverUrl, credentials.username, credentials.password)
        assertTrue(repository.state.value is SessionState.Connected)
        repository.signOut()
        assertTrue(preference.value)
        assertNull(store.saved)
        val relaunched = SessionRepository(store, "test", preference) { fail("Suppressed login must not contact server") }
        relaunched.restore()
        relaunched.loginAutomatically(credentials.serverUrl, credentials.username, credentials.password)
        assertSame(SessionState.SignedOut, relaunched.state.value)
    }

    @Test(timeout = 10_000) fun onlySuccessfulManualLoginReenablesAutomaticLogin() = runBlocking {
        val store = Store()
        val preference = Preference().apply { value = true }
        var reject = true
        val repository = SessionRepository(store, "test", preference) {
            if (reject) throw dev.coda.core.subsonic.SubsonicError.Unauthorized()
        }
        assertNotNull(repository.login(credentials.serverUrl, credentials.username, credentials.password))
        assertTrue(preference.value)
        assertNotNull(repository.login("", credentials.username, credentials.password))
        assertTrue(preference.value)
        reject = false
        assertNull(repository.login(credentials.serverUrl, credentials.username, credentials.password))
        assertFalse(preference.value)
    }

    @Test(timeout = 10_000) fun signOutWinsAgainstPendingAutomaticLogin() = runBlocking {
        val store = Store()
        val preference = Preference()
        val verifying = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val repository = SessionRepository(store, "test", preference) { verifying.complete(Unit); finish.await() }
        val login = launch { repository.loginAutomatically(credentials.serverUrl, credentials.username, credentials.password) }
        verifying.await()
        repository.signOut()
        finish.complete(Unit)
        login.join()
        assertSame(SessionState.SignedOut, repository.state.value)
        assertNull(store.saved)
        assertTrue(preference.value)
    }

    @Test(timeout = 10_000) fun cancellationAtWriteCompletionKeepsSavedSessionPublished() = runBlocking {
        val store = Store()
        val repository = SessionRepository(store, "test") {}
        val mutated = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        store.afterMutation = { mutated.complete(Unit); finish.await() }
        val login = launch { repository.login(credentials.serverUrl, credentials.username, credentials.password) }
        mutated.await()
        login.cancel()
        finish.complete(Unit)
        login.join()
        assertTrue(login.isCancelled)
        assertEquals(credentials.serverUrl, store.saved?.serverUrl)
        val connected = repository.state.value as SessionState.Connected
        assertTrue(connected.verified)
        assertTrue(repository.isCurrent(connected.session.token))
        assertEquals(credentials.username, connected.session.client.configuration.username)
    }

    @Test(timeout = 10_000) fun cancellationAtDeleteCompletionPublishesSignedOut() = runBlocking {
        val store = Store()
        val repository = SessionRepository(store, "test") {}
        repository.login(credentials.serverUrl, credentials.username, credentials.password)
        val token = (repository.state.value as SessionState.Connected).session.token
        val mutated = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        store.afterMutation = { mutated.complete(Unit); finish.await() }
        val signOut = launch { repository.signOut() }
        mutated.await()
        signOut.cancel()
        finish.complete(Unit)
        signOut.join()
        assertTrue(signOut.isCancelled)
        assertNull(store.saved)
        assertSame(SessionState.SignedOut, repository.state.value)
        assertFalse(repository.isCurrent(token))
    }

    @Test(timeout = 10_000) fun delayedRestoreSuccessCannotOverrideAuthenticationFailure() = runBlocking {
        val store = Store().apply { saved = credentials }
        val verifying = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        val repository = SessionRepository(store, "test") { verifying.complete(Unit); response.await() }
        val restore = launch { repository.restore() }
        verifying.await()
        val connected = repository.state.value as SessionState.Connected
        assertFalse(connected.verified)
        repository.unauthorized(connected.session.token)
        response.complete(Unit)
        restore.join()
        val needsLogin = repository.state.value as SessionState.NeedsLogin
        assertSame(connected.session, needsLogin.session)
        assertFalse(repository.isCurrent(connected.session.token))
    }

    /** Fix 1: the stored sign-in's account key (hashed server + user), never the address, name or password. */
    @Test(timeout = 10_000) fun storedAccountKeyIsTheHashedServerAndUser() = runBlocking {
        val store = Store()
        val repository = SessionRepository(store, "test") {}
        assertNull("nothing stored", repository.storedAccountKey())
        store.saved = credentials
        val key = repository.storedAccountKey()!!
        assertEquals(dev.coda.core.cache.TrackCache.serverKey(credentials.serverUrl, credentials.username), key)
        assertFalse(key.contains("synthetic"))
        store.saved = Credentials(credentials.serverUrl, "someone-else", "x")
        assertNotEquals(key, repository.storedAccountKey())
    }
    /** Only a sign-in the server verified (and that is still current) runs the account storage pass, before it is published. */
    @Test(timeout = 10_000) fun onlyAVerifiedSignInRunsTheAccountStoragePass() = runBlocking {
        val store = Store()
        var reject = true
        val passes = mutableListOf<String>()
        lateinit var repository: SessionRepository
        val order = mutableListOf<Boolean>()
        repository = SessionRepository(store, "test", onVerifiedSignIn = { session ->
            // Recorded, not asserted here: the repository swallows a failing pass.
            order += repository.state.value !is SessionState.Connected && store.saved != null
            passes += session.client.configuration.username
        }) { if (reject) throw dev.coda.core.subsonic.SubsonicError.Unauthorized() }
        assertNotNull(repository.login(credentials.serverUrl, credentials.username, credentials.password))
        assertNotNull(repository.login(credentials.serverUrl, " ", credentials.password))
        assertEquals("a failed sign-in deletes nothing", emptyList<String>(), passes)
        reject = false
        assertNull(repository.login(credentials.serverUrl, credentials.username, credentials.password))
        assertEquals(listOf(credentials.username), passes)
        assertEquals("after the login was saved, before the session is published", listOf(true), order)
        repository.signOut()
        assertEquals("sign-out deletes nothing", 1, passes.size)
    }

    @Test(timeout = 10_000) fun aSupersededSignInDoesNotRunThePass() = runBlocking {
        val store = Store()
        val verifying = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var passes = 0
        val repository = SessionRepository(store, "test", onVerifiedSignIn = { passes++ }) { verifying.complete(Unit); finish.await() }
        val login = launch { repository.login(credentials.serverUrl, credentials.username, credentials.password) }
        verifying.await()
        repository.signOut()
        finish.complete(Unit)
        login.join()
        assertEquals(0, passes)
    }

    @Test(timeout = 10_000) fun aFailingPassDoesNotFailTheSignIn() = runBlocking {
        val repository = SessionRepository(Store(), "test", onVerifiedSignIn = { error("disk") }) {}
        assertNull(repository.login(credentials.serverUrl, credentials.username, credentials.password))
        assertTrue(repository.state.value is SessionState.Connected)
    }

    /** A store whose read can be held open (the cold-start window) or fail. */
    private class SlowStore(var saved: Credentials?, val failure: Exception? = null) : CredentialStorage {
        val reading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override suspend fun read(): Credentials? { reading.complete(Unit); release.await(); failure?.let { throw it }; return saved }
        override suspend fun write(value: Credentials) { saved = value }
        override suspend fun clear() { saved = null }
    }

    /** Until restore() has read the saved sign-in, the state is Restoring (no login page), never SignedOut. */
    @Test(timeout = 10_000) fun theInitialStateIsRestoring() {
        assertSame(SessionState.Restoring, SessionRepository(Store(), "test") {}.state.value)
    }

    @Test(timeout = 10_000) fun restoreWithASavedLoginGoesFromRestoringToConnected() = runBlocking {
        val store = SlowStore(credentials)
        val verified = CompletableDeferred<Unit>()
        val repository = SessionRepository(store, "test") { verified.await() }
        val restore = launch { repository.restore() }
        store.reading.await()
        assertSame("still Restoring while the saved sign-in is read", SessionState.Restoring, repository.state.value)
        store.release.complete(Unit)
        withTimeout(5_000) { repository.state.first { it is SessionState.Connected } }
        assertFalse((repository.state.value as SessionState.Connected).verified)
        verified.complete(Unit)
        restore.join()
        assertTrue((repository.state.value as SessionState.Connected).verified)
    }

    @Test(timeout = 10_000) fun restoreWithoutASavedLoginEndsRestoringSignedOut() = runBlocking {
        val repository = SessionRepository(Store(), "test") { fail("nothing to verify") }
        repository.restore()
        assertSame(SessionState.SignedOut, repository.state.value)
    }

    @Test(timeout = 10_000) fun anUnreadableStoreEndsRestoringSignedOut() = runBlocking {
        val store = SlowStore(credentials, failure = IllegalStateException("synthetic read failure")).apply { release.complete(Unit) }
        val repository = SessionRepository(store, "test") { fail("nothing to verify") }
        repository.restore()
        assertSame(SessionState.SignedOut, repository.state.value)
    }

    @Test(timeout = 10_000) fun anUnusableSavedLoginEndsRestoringSignedOutAndIsCleared() = runBlocking {
        val store = Store().apply { saved = Credentials("https://synthetic.example", "synthetic", "") }
        val repository = SessionRepository(store, "test") { fail("nothing to verify") }
        repository.restore()
        assertSame(SessionState.SignedOut, repository.state.value)
        assertNull(store.saved)
    }

    @Test(timeout = 10_000) fun aCancelledRestoreEndsRestoringSignedOut() = runBlocking {
        val store = SlowStore(credentials)
        val repository = SessionRepository(store, "test") {}
        val restore = launch { repository.restore() }
        store.reading.await()
        restore.cancelAndJoin()
        assertSame(SessionState.SignedOut, repository.state.value)
    }

    /** A sign-in or sign-out that ran first wins; the later restore (generation != 0) leaves its state alone. */
    @Test(timeout = 10_000) fun aRestoreAfterASignInOrSignOutKeepsTheirState() = runBlocking {
        val store = Store().apply { saved = credentials }
        val signedIn = SessionRepository(store, "test") {}
        assertNull(signedIn.login(credentials.serverUrl, credentials.username, credentials.password))
        val connected = signedIn.state.value as SessionState.Connected
        signedIn.restore()
        assertSame(connected, signedIn.state.value)
        val signedOut = SessionRepository(Store().apply { saved = credentials }, "test", Preference()) {}
        signedOut.signOut()
        signedOut.restore()
        assertSame(SessionState.SignedOut, signedOut.state.value)
    }

    /** Interactive sign-in is unchanged: Connecting while it runs, and a failed one ends SignedOut (never back to Restoring). */
    @Test(timeout = 10_000) fun aFailedInteractiveSignInBeforeRestoreEndsSignedOut() = runBlocking {
        val verifying = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Unit>()
        val repository = SessionRepository(Store(), "test", Preference()) {
            verifying.complete(Unit); reply.await(); throw dev.coda.core.subsonic.SubsonicError.Unauthorized()
        }
        val login = async { repository.login(credentials.serverUrl, credentials.username, credentials.password) }
        verifying.await()
        assertSame(SessionState.Connecting, repository.state.value)
        reply.complete(Unit)
        assertNotNull(login.await())
        assertSame(SessionState.SignedOut, repository.state.value)
    }
}
