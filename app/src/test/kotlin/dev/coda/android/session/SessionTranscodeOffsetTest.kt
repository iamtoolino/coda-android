package dev.coda.android.session

import dev.coda.core.testing.FakeSubsonicServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** A server known not to support `timeOffset` stays known (no capability thread per transcode source). */
class SessionTranscodeOffsetTest {
    private class Store : CredentialStorage {
        override suspend fun read(): Credentials? = null
        override suspend fun write(value: Credentials) {}
        override suspend fun clear() {}
    }
    private fun session(server: FakeSubsonicServer): Session = runBlocking {
        val sessions = SessionRepository(Store(), "test") {}
        sessions.login(server.baseUrl, "synthetic", "synthetic-password")
        (sessions.state.value as SessionState.Connected).session
    }

    @Test(timeout = 15_000) fun aNoFromTheServerIsKeptAsKnown() = FakeSubsonicServer().use { server ->
        server.openSubsonicExtensions = null // answers "endpoint not found"
        val session = session(server)
        assertNull("unknown before asking", session.knownTranscodeOffset())
        assertFalse(session.supportsTranscodeOffset())
        assertEquals(false, session.knownTranscodeOffset())
    }

    @Test(timeout = 15_000) fun aYesIsKnownToo() = FakeSubsonicServer().use { server ->
        server.openSubsonicExtensions = listOf("transcodeOffset")
        val session = session(server)
        assertTrue(session.supportsTranscodeOffset())
        assertEquals(true, session.knownTranscodeOffset())
    }
}
