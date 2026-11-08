package dev.coda.android.session

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SessionRequestsTest {
    private class Store : CredentialStorage {
        override suspend fun read(): Credentials? = null
        override suspend fun write(value: Credentials) {}
        override suspend fun clear() {}
    }
    private suspend fun repository() = SessionRepository(Store(), "test") {}.also {
        it.login("https://synthetic.example", "synthetic", "synthetic-password")
    }
    private fun SessionRepository.session() = (state.value as SessionState.Connected).session

    @Test fun signOutCancelsAnInFlightRequest() = runTest {
        val sessions = repository()
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val request = async {
            sessions.request(sessions.session()) {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
        }
        entered.await()
        sessions.signOut()
        cancelled.await()
        assertTrue(runCatching { request.await() }.exceptionOrNull() is SessionEnded)
    }

    @Test fun lateCancellationResistantResponseCannotPublishAfterAccountSwitch() = runTest {
        val sessions = repository()
        val old = sessions.session()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var published = false
        val request = async {
            sessions.request(old) {
                withContext(NonCancellable) { entered.complete(Unit); release.await(); "old metadata" }
            }.also { published = true }
        }
        entered.await()
        sessions.signOut()
        sessions.login("https://other.synthetic.example", "synthetic", "synthetic-password")
        release.complete(Unit)
        assertTrue(runCatching { request.await() }.exceptionOrNull() is SessionEnded)
        assertFalse(published)
        assertNotEquals(old.token, sessions.session().token)
    }

    @Test fun alreadyEndedSessionNeverStartsWork() = runTest {
        val sessions = repository()
        val old = sessions.session()
        sessions.signOut()
        assertThrows(SessionEnded::class.java) {
            runBlocking { sessions.request(old) { fail("Stale work started") } }
        }
    }

    @Test fun playerAdmitsUntaggedAndCurrentItemsButDropsItemsFromAnEndedSession() = runTest {
        val sessions = repository()
        val old = sessions.session().token.toString()
        assertTrue(admitsSessionTag(null, sessions::isCurrent))
        assertTrue(admitsSessionTag(old, sessions::isCurrent))
        sessions.signOut()
        assertFalse(admitsSessionTag(old, sessions::isCurrent))
        sessions.login("https://other.synthetic.example", "synthetic", "synthetic-password")
        assertFalse(admitsSessionTag(old, sessions::isCurrent))
        assertTrue(admitsSessionTag(sessions.session().token.toString(), sessions::isCurrent))
        assertFalse(admitsSessionTag("not-a-token", sessions::isCurrent))
    }
}
