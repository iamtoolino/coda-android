package dev.coda.android.session

import dev.coda.core.subsonic.ServerAnswer
import dev.coda.core.subsonic.ServerProbe
import dev.coda.core.testing.FakeSubsonicServer
import dev.coda.core.testing.SchemeFrontDoor
import dev.coda.core.subsonic.SubsonicError
import java.net.ConnectException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

/** How sign-in and the server check use the probe (its own policy is covered by core ServerProbeTest). */
class LoginSchemeTest {
    private class Store : CredentialStorage {
        var saved: Credentials? = null
        var failWrites = false
        override suspend fun read() = saved
        override suspend fun write(value: Credentials) { if (failWrites) throw java.io.IOException("synthetic"); saved = value }
        override suspend fun clear() { saved = null }
    }

    private val resolved = mutableListOf<String>()
    private val seenOverHttpsFlags = mutableListOf<Boolean>()
    private val recorded = mutableListOf<String>()
    private fun repository(
        store: Store, verify: suspend (Session) -> Unit = {}, resolve: suspend (String) -> ServerAnswer = { error("no probe expected") },
        httpClient: okhttp3.OkHttpClient? = null, history: HttpsHistory? = null,
        probe: (suspend (String, Boolean) -> ServerAnswer)? = null,
    ) = SessionRepository(store, "test", NoAutoLoginPreference,
        resolveServer = { input, seen -> resolved += input; seenOverHttpsFlags += seen; probe?.invoke(input, seen) ?: resolve(input) },
        recordServer = { recorded += it }, httpsSeen = { history?.contains(it) == true }, recordHttps = { history?.add(it) },
        httpClient = { httpClient ?: okhttp3.OkHttpClient() }, verify = verify)

    /**
     * An https history kept in memory, loaded with the production rule ([HttpsHistory.stored]): [saved] is the
     * history file (null = not written yet). [saveResult] decides
     * each write (true = committed); [loadFailure] makes reads fail.
     */
    private class MemoryHistory(initial: Set<String>? = emptySet(), val gate: CompletableDeferred<Unit>? = null) {
        @Volatile var saved: Set<String>? = initial
        @Volatile var loadFailure: Exception? = null
        @Volatile var saveResult: suspend (Set<String>) -> Boolean = { true }
        val writes = java.util.concurrent.atomic.AtomicInteger()
        fun open(scope: kotlinx.coroutines.CoroutineScope) = HttpsHistory(scope,
            load = { gate?.await(); loadFailure?.let { throw it }; HttpsHistory.stored(saved) },
            save = { value -> writes.incrementAndGet(); saveResult(value).also { if (it) saved = value } })
    }

    private val loopback = okhttp3.OkHttpClient.Builder().connectTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
        // music.example.org (with or without the DNS root dot) is this machine, so the fixtures can sit behind a name.
        .dns(object : okhttp3.Dns {
            override fun lookup(hostname: String) = if (hostname.trimEnd('.') == "music.example.org" || hostname == "127.0.0.1")
                listOf(java.net.InetAddress.getByName("127.0.0.1")) else throw java.net.UnknownHostException("synthetic")
        }).build()
    private val realProbe: suspend (String, Boolean) -> ServerAnswer = { input, seen -> ServerProbe(loopback, "test").resolve(input, seen) }

    private val certificateFailure = SSLHandshakeException("synthetic").apply {
        initCause(java.security.cert.CertificateException(java.security.cert.CertPathValidatorException("no trust anchor")))
    }

    @Test fun signInAuthenticatesExactlyTheGivenUrlAndNeverProbes() = runBlocking {
        val store = Store()
        val repository = repository(store)
        assertNull(repository.login("http://home.example.net:4533", "synthetic", "synthetic-password"))
        assertEquals("http://home.example.net:4533", store.saved?.serverUrl)
        // Without a scheme, sign-in itself never picks http: the screen resolves and shows the scheme first.
        assertNull(repository.login("home.example.net", "synthetic", "synthetic-password"))
        assertEquals("https://home.example.net", store.saved?.serverUrl)
        assertEquals(emptyList<String>(), resolved)
        assertEquals(listOf("http://home.example.net:4533", "https://home.example.net"), recorded)
    }

    @Test fun onConnectedRunsAfterSavingAndBeforePublishing() = runBlocking {
        val store = Store()
        val repository = repository(store)
        // Observed inside the callback, asserted outside (the repository does not let a callback failure escape).
        val seenStates = mutableListOf<SessionState>()
        val seenSaved = mutableListOf<String?>()
        assertNull(repository.login("https://music.example.org", "synthetic", "synthetic-password") {
            seenStates += repository.state.value
            seenSaved += store.saved?.serverUrl
        })
        assertEquals(1, seenStates.size)
        assertTrue("not published yet: ${seenStates.single()}", seenStates.single() !is SessionState.Connected)
        assertEquals("already saved", listOf<String?>("https://music.example.org"), seenSaved)
        assertTrue(repository.state.value is SessionState.Connected)
    }

    /** A typed http server that upgrades to https on the same host is signed in, stored and recorded as https. */
    @Test fun sameHostHttpsUpgradeIsStoredAsHttps() = runBlocking {
        val upgrading = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val upgraded = chain.request().newBuilder().url(chain.request().url.newBuilder().scheme("https").build()).build()
            okhttp3.Response.Builder().request(upgraded).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}""".toByteArray()
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val store = Store()
        val repository = repository(store, verify = { it.client.ping() }, httpClient = upgrading)
        assertNull(repository.login("http://home.example.net:4533", "synthetic", "synthetic-password"))
        assertEquals("https://home.example.net:4533", store.saved?.serverUrl)
        assertEquals(listOf("https://home.example.net:4533"), recorded)
        assertEquals("https://home.example.net:4533", (repository.state.value as SessionState.Connected).session.client.configuration.serverUrl)
    }

    /** Review 3: an upgrade answer with user info never reaches the saved credentials or the recent servers. */
    @Test fun upgradeWithUserInfoIsNeverPersisted() = runBlocking {
        val upgrading = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url.newBuilder().scheme("https").username("synthetic-user").password("synthetic-pass").build()
            okhttp3.Response.Builder().request(chain.request().newBuilder().url(url).build()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}""".toByteArray()
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val store = Store()
        val repository = repository(store, verify = { it.client.ping() }, httpClient = upgrading)
        assertNull(repository.login("http://home.example.net", "synthetic", "synthetic-password"))
        val saved = store.saved?.serverUrl.orEmpty()
        assertTrue(saved, saved.startsWith("https://home.example.net") && "synthetic-user" !in saved && "@" !in saved)
        assertTrue(recorded.toString(), recorded.isNotEmpty() && recorded.none { "@" in it || "synthetic-user" in it })
        assertEquals(null, credentialFreeServerUrl("https://synthetic-user:synthetic-pass@home.example.net"))
        assertEquals("https://home.example.net/x", credentialFreeServerUrl("https://home.example.net/x?u=a#f"))
    }

    @Test fun onConnectedNeverRunsForFailedOrSupersededSignIns() = runBlocking {
        var calls = 0
        val count = { calls++; Unit }
        // Unauthorized.
        val rejected = repository(Store(), verify = { throw SubsonicError.Unauthorized() })
        assertNotNull(rejected.login("https://music.example.org", "synthetic", "wrong", count))
        // The credential store fails.
        val failing = repository(Store().apply { failWrites = true })
        assertNotNull(failing.login("https://music.example.org", "synthetic", "synthetic-password", count))
        assertTrue(failing.state.value is SessionState.SignedOut)
        // Superseded: signed out while the server was still answering.
        val verifying = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val superseded = repository(Store(), verify = { verifying.complete(Unit); finish.await() })
        val pending = launch { assertNull(superseded.login("https://music.example.org", "synthetic", "synthetic-password", count)) }
        verifying.await()
        superseded.signOut()
        finish.complete(Unit)
        pending.join()
        assertSame(SessionState.SignedOut, superseded.state.value)
        assertEquals(0, calls)
        assertEquals(emptyList<String>(), recorded)
    }

    @Test fun automaticLoginIsNotRecorded() = runBlocking {
        val repository = repository(Store())
        assertNull(repository.loginAutomatically("https://music.example.org", "synthetic", "synthetic-password"))
        assertTrue(repository.state.value is SessionState.Connected)
        assertEquals(emptyList<String>(), recorded)
    }

    @Test fun checkServerMapsTheAnswer() = runBlocking {
        assertEquals(ServerCheck.Found("https://music.example.org", true),
            repository(Store(), resolve = { ServerAnswer("https://$it", true) }).checkServer("music.example.org"))
        assertEquals(ServerCheck.Failed(untrusted = true, LoginMessages.UNTRUSTED),
            repository(Store(), resolve = { throw SubsonicError.Network(certificateFailure) }).checkServer("music.example.org"))
        // A TLS failure without a certificate problem (protocol/cipher mismatch, handshake EOF) is not "untrusted".
        assertEquals(ServerCheck.Failed(untrusted = false, LoginMessages.NO_SECURE_CONNECTION),
            repository(Store(), resolve = { throw SubsonicError.Network(SSLHandshakeException("Received fatal alert: protocol_version")) })
                .checkServer("music.example.org"))
        assertEquals(ServerCheck.Failed(untrusted = false, LoginMessages.NO_SECURE_CONNECTION),
            repository(Store(), resolve = { throw SubsonicError.Network(SSLHandshakeException("EOF").apply { initCause(java.io.EOFException()) }) })
                .checkServer("music.example.org"))
        assertEquals(ServerCheck.Failed(untrusted = true, LoginMessages.UNTRUSTED),
            repository(Store(), resolve = { throw SubsonicError.Network(javax.net.ssl.SSLPeerUnverifiedException("host")) })
                .checkServer("music.example.org"))
        assertEquals(ServerCheck.Failed(untrusted = false),
            repository(Store(), resolve = { throw SubsonicError.Network(ConnectException("synthetic")) }).checkServer("music.example.org"))
        // Another web server answered the http fallback. Only the flag differs; Sign in's message is the same one.
        assertEquals(ServerCheck.Failed(untrusted = false, LoginMessages.UNREACHABLE, otherServer = true),
            repository(Store(), resolve = { throw SubsonicError.Network(ConnectException("synthetic"), otherServerAnswered = true) })
                .checkServer("music.example.org"))
        assertEquals(ServerCheck.Failed(untrusted = false, LoginMessages.NO_SECURE_CONNECTION, otherServer = true),
            repository(Store(), resolve = { throw SubsonicError.Network(SSLHandshakeException("EOF"), otherServerAnswered = true) })
                .checkServer("music.example.org"))
        assertNull(repository(Store(), resolve = { throw SubsonicError.InvalidConfiguration("synthetic") }).checkServer(" "))
    }

    /** Sign-in reports the same diagnosis as the chip. */
    @Test fun signInMapsOnlyCertificateProblemsToUntrusted() = runBlocking {
        suspend fun message(failure: java.io.IOException) =
            repository(Store(), verify = { throw SubsonicError.Network(failure) }).login("https://music.example.org", "synthetic", "synthetic-password")
        assertEquals(LoginError(LoginField.Server, LoginMessages.UNTRUSTED), message(certificateFailure))
        assertEquals(LoginError(LoginField.Server, LoginMessages.NO_SECURE_CONNECTION), message(SSLHandshakeException("Received fatal alert: handshake_failure")))
        assertEquals(LoginError(LoginField.Server, LoginMessages.NO_SECURE_CONNECTION), message(javax.net.ssl.SSLProtocolException("synthetic")))
        assertEquals(LoginError(LoginField.Server, LoginMessages.UNREACHABLE), message(ConnectException("synthetic")))
    }

    @Test fun seenOverHttpsMatchesHostAndPort() {
        val known = listOf("https://Music.example.org", "http://plain.example.net", "https://home.example.net:8443/navidrome")
        assertTrue(seenOverHttps("music.example.org", known))
        assertTrue(seenOverHttps("music.example.org:443", known))
        assertTrue(seenOverHttps("home.example.net:8443", known))
        assertFalse("another port is another server", seenOverHttps("music.example.org:4533", known))
        assertFalse("seen over http only", seenOverHttps("plain.example.net", known))
        assertFalse("never seen", seenOverHttps("new.example.org", known))
        assertFalse("a typed scheme is used as it is", seenOverHttps("http://music.example.org", known))
        assertFalse(seenOverHttps(" ", known))
        // Re-review P2: the DNS root dot names the same host, in both directions.
        assertTrue(seenOverHttps("music.example.org.", known))
        assertTrue(seenOverHttps("music.example.org", listOf("https://music.example.org.")))
        assertTrue(seenOverHttps("MUSIC.example.org.:443", listOf("https://music.example.org.")))
        assertEquals(HttpsHistory.key("https://music.example.org"), HttpsHistory.key("music.example.org."))
        assertNull("http is never https history", HttpsHistory.key("http://music.example.org"))
    }

    /** Item 6, end to end with the real probe: https is silent, http answers on the same port (the device fixture). */
    @Test fun recentHttpsServerNeverProbesHttpWhileANewServerStillFallsBack() = runBlocking {
        FakeSubsonicServer().use { server ->
            SchemeFrontDoor(server, SchemeFrontDoor.Tls.SILENT).use { door ->
                // A server that worked over https: the https failure is shown, no http request at all.
                val known = repository(Store(), probe = realProbe, history = MemoryHistory(setOf(HttpsHistory.key("https://${door.address}")!!)).open(this))
                assertEquals(ServerCheck.Failed(untrusted = false, LoginMessages.UNREACHABLE), known.checkServer(door.address))
                assertEquals(0, door.plainConnections.get())
                assertEquals(0, server.anonymousPings.get())
                // Never seen over https (empty history, or an http server recorded somewhere else): the fallback is unchanged.
                assertEquals(ServerCheck.Found("http://${door.address}", true),
                    repository(Store(), probe = realProbe, history = MemoryHistory().open(this)).checkServer(door.address))
                assertEquals(1, server.anonymousPings.get())
                assertEquals(listOf(true, false), seenOverHttpsFlags)
            }
        }
    }

    /** Re-review P2: the DNS root dot does not escape the history, in either direction, with the real probe. */
    @Test fun trailingDotNeverEscapesTheHttpsHistory() = runBlocking {
        FakeSubsonicServer().use { server ->
            SchemeFrontDoor(server, SchemeFrontDoor.Tls.SILENT).use { door ->
                val port = door.address.substringAfter(':')
                for ((stored, typed) in listOf("https://music.example.org:$port" to "music.example.org.:$port",
                    "https://music.example.org.:$port" to "music.example.org:$port")) {
                    val history = MemoryHistory(setOf(HttpsHistory.key(stored)!!)).open(this)
                    val check = repository(Store(), probe = realProbe, history = history).checkServer(typed)
                    assertTrue("$stored / $typed: $check", check is ServerCheck.Failed)
                }
                assertEquals(0, door.plainConnections.get())
                assertEquals(0, server.anonymousPings.get())
                // Control: the same name never seen over https still falls back.
                assertEquals(ServerCheck.Found("http://music.example.org.:$port", true),
                    repository(Store(), probe = realProbe, history = MemoryHistory().open(this)).checkServer("music.example.org.:$port"))
            }
        }
    }

    /** Re-review P2: a check started before the stored history loaded waits for it: no https, proof or http before. */
    @Test fun checkWaitsForTheStoredHistory() = runBlocking {
        FakeSubsonicServer().use { server ->
            SchemeFrontDoor(server, SchemeFrontDoor.Tls.PLAIN_HTTP).use { door ->
                val gate = CompletableDeferred<Unit>()
                val history = MemoryHistory(setOf(HttpsHistory.key("https://${door.address}")!!), gate).open(this)
                val repository = repository(Store(), probe = realProbe, history = history)
                val check = async(kotlinx.coroutines.Dispatchers.Default) { repository.checkServer(door.address) }
                kotlinx.coroutines.delay(500)
                assertTrue("still waiting for the history", check.isActive)
                assertEquals("nothing contacted yet", 0, door.tlsConnections.get() + door.plainConnections.get())
                gate.complete(Unit)
                assertTrue(check.await() is ServerCheck.Failed)
                assertEquals("only the https attempt, no plain-HTTP proof", 1, door.tlsConnections.get())
                assertEquals(0, door.plainConnections.get())
                assertEquals(0, server.anonymousPings.get())
            }
        }
    }

    /**
     * Coordinator risk decision: the protection does not live in the five recent servers. Six https sign-ins evict the
     * first recent entry; after signing out (no session) and an app restart (history reloaded from what was saved) the
     * first server is still protected. Only forgetting it (× in the recents, [RecentServers] calls forget) removes it.
     */
    @Test fun httpsHistorySurvivesEvictionAndSignOutAndOnlyForgetClearsIt() = runBlocking {
        val memory = MemoryHistory()
        val store = Store()
        var recents = emptyList<RecentServer>()
        val repository = SessionRepository(store, "test", NoAutoLoginPreference,
            resolveServer = { input, seen -> seenOverHttpsFlags += seen; throw SubsonicError.Network(ConnectException("synthetic")) },
            recordServer = { recents = RecentServers.added(recents, it, recents.size.toLong()) },
            httpsSeen = { memory.open(this).contains(it) }, recordHttps = { memory.open(this).add(it) }, verify = {})
        for (i in 1..6) assertNull(repository.login("https://s$i.example.org", "synthetic", "synthetic-password"))
        assertEquals(RecentServers.LIMIT, recents.size)
        assertTrue("the first server left the recents", recents.none { it.url == "https://s1.example.org" })
        repository.signOut()
        assertNull(store.saved)
        repository.checkServer("s1.example.org")
        assertEquals("evicted and signed out, still protected", listOf(true), seenOverHttpsFlags)
        memory.open(this).forget("https://s1.example.org")
        repository.checkServer("s1.example.org")
        repository.checkServer("s2.example.org")
        assertEquals(listOf(true, false, true), seenOverHttpsFlags)
        assertFalse("an http server is never recorded", "s1.example.org:80" in memory.saved.orEmpty())
    }

    /** Round 2 P2: a write that is not committed, fails or is cancelled stays pending and is retried. */
    @Test fun failedOrCancelledWritesAreRetriedUntilCommitted() = runBlocking {
        // commit() returned false, then a write throws: nothing stored, still dirty.
        val memory = MemoryHistory()
        val history = memory.open(this)
        memory.saveResult = { false }
        history.add("https://a.example.org")
        memory.saveResult = { throw java.io.IOException("synthetic") }
        history.add("https://a.example.org")
        assertEquals(emptySet<String>(), memory.saved)
        assertFalse("a restart now would lose it", memory.open(this).contains("a.example.org"))
        // The next use (adding the same key again) retries and commits.
        memory.saveResult = { true }
        history.add("https://a.example.org")
        assertEquals(setOf("a.example.org:443"), memory.saved)
        assertTrue("after a restart", memory.open(this).contains("a.example.org"))

        // A caller cancelled while its write runs (the address changed during the check): the write still finishes.
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        memory.saveResult = { entered.complete(Unit); release.await(); true }
        val adding = launch(kotlinx.coroutines.Dispatchers.Default) { history.add("https://b.example.org") }
        entered.await()
        adding.cancelAndJoin()
        release.complete(Unit)
        awaitSaved(memory, "b.example.org:443")
        assertTrue(memory.open(this).contains("b.example.org"))
    }

    private suspend fun awaitSaved(memory: MemoryHistory, key: String) =
        kotlinx.coroutines.withTimeout(5_000) { while (memory.saved?.contains(key) != true) kotlinx.coroutines.delay(10) }

    /**
     * Round 4 (the reviewer's case): the history is already dirty (A's write was not committed), then B's add is
     * cancelled while the save of the older change runs. B was applied to memory before any suspension, and the
     * save in flight cannot be cancelled by the caller, so after a restart B (and A) are protected.
     */
    @Test fun cancelledAddDuringAnOlderDirtySaveIsNotLost() = runBlocking {
        val memory = MemoryHistory()
        val history = memory.open(this)
        memory.saveResult = { false }
        history.add("https://a.example.org") // dirty: A is in memory, not stored
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        memory.saveResult = { entered.complete(Unit); release.await(); true }
        // A's retry is in flight when B's add starts and is cancelled.
        val retry = launch(kotlinx.coroutines.Dispatchers.Default) { history.contains("a.example.org") }
        entered.await()
        val adding = launch(kotlinx.coroutines.Dispatchers.Default) { history.add("https://b.example.org") }
        kotlinx.coroutines.delay(100)
        adding.cancelAndJoin()
        release.complete(Unit)
        retry.join()
        awaitSaved(memory, "b.example.org:443")
        val restarted = memory.open(this)
        assertTrue("B after a restart", restarted.contains("b.example.org"))
        assertTrue("A after a restart", restarted.contains("a.example.org"))
    }

    /** Round 2 P2 risk: when the history cannot be read, the check refuses the http fallback (fail closed). */
    @Test fun unreadableHistoryRefusesTheFallback() = runBlocking {
        FakeSubsonicServer().use { server ->
            SchemeFrontDoor(server, SchemeFrontDoor.Tls.PLAIN_HTTP).use { door ->
                val memory = MemoryHistory().apply { loadFailure = java.io.IOException("synthetic") }
                val history = memory.open(this)
                try { history.contains(door.address); fail("expected Unavailable") } catch (_: HttpsHistory.Unavailable) {}
                val repository = repository(Store(), probe = realProbe, history = history)
                assertTrue(repository.checkServer(door.address) is ServerCheck.Failed)
                assertEquals(listOf(true), seenOverHttpsFlags)
                assertEquals("no plain-HTTP proof, no http", 0, door.plainConnections.get())
                assertEquals(0, server.anonymousPings.get())
                // Readable again: read anew, and the never-seen server falls back as before.
                memory.loadFailure = null
                assertEquals(ServerCheck.Found("http://${door.address}", true), repository.checkServer(door.address))
            }
        }
    }

    /** Round 3 P2: an https answer (and a forget) seen while the history cannot be read is kept and stored on recovery. */
    @Test fun httpsSeenWhileTheHistoryIsUnreadableIsKeptAfterRecovery() = runBlocking {
        val memory = MemoryHistory(setOf("old.example.org:443")).apply { loadFailure = java.io.IOException("synthetic") }
        val history = memory.open(this)
        val repository = repository(Store(), resolve = { ServerAnswer("https://new.example.org", true) }, history = history)
        assertEquals(ServerCheck.Found("https://new.example.org", true), repository.checkServer("new.example.org"))
        history.forget("https://old.example.org")
        assertEquals("nothing written while unreadable", 0, memory.writes.get())
        memory.loadFailure = null
        assertTrue("protected after recovery", history.contains("new.example.org"))
        assertFalse("the forget is kept too", history.contains("old.example.org"))
        assertEquals("merged and stored", setOf("new.example.org:443"), memory.saved)
        assertTrue("after a restart", memory.open(this).contains("new.example.org"))
    }

    /** Round 5: the history file format (the storage boundary itself is tested on the device, Lg1LoginDeviceTest). */
    @Test fun historyFileFormatIsStrict() {
        val keys = setOf("music.example.org:443", "home.example.net:8443", "[::1]:443", "xn--mnchen-3ya.example:443")
        assertEquals(keys, HttpsHistoryFile.parse(HttpsHistoryFile.encode(keys)))
        assertEquals(emptySet<String>(), HttpsHistoryFile.parse(HttpsHistoryFile.encode(emptySet())))
        assertEquals("[::1]:443", HttpsHistory.key("https://[0:0:0:0:0:0:0:1]"))
        val good = HttpsHistoryFile.encode(setOf("music.example.org:443"))
        fun rejected(bytes: ByteArray, why: String) =
            try { HttpsHistoryFile.parse(bytes); fail(why) } catch (_: java.io.IOException) {}
        rejected(ByteArray(0), "empty file")
        rejected(good.copyOf(good.size - 1), "truncated: no final newline")
        rejected(good.copyOf(good.size - 4), "truncated: no end line")
        rejected("coda-https-history 2\nmusic.example.org:443\nend\n".toByteArray(), "unknown version")
        rejected("<?xml version='1.0'?><map/>".toByteArray(), "garbage")
        rejected(byteArrayOf(0xC3.toByte(), 0x28, 0x0A), "not UTF-8")
        rejected("coda-https-history 1\nMusic.example.org:443\nend\n".toByteArray(), "not canonical")
        rejected("coda-https-history 1\nhttp://music.example.org\nend\n".toByteArray(), "not a key")
    }

    /**
     * Round 7 (decided by the coordinator, 2026-10-29): a damaged history stays fail-closed, but a typed http:// URL is
     * never subject to the history: the check and the sign-in still work, and only the scheme-less fallback is refused.
     */
    @Test fun typedHttpStillSignsInWhileTheHistoryIsUnavailable() = runBlocking {
        FakeSubsonicServer().use { server ->
            SchemeFrontDoor(server, SchemeFrontDoor.Tls.PLAIN_HTTP).use { door ->
                val memory = MemoryHistory().apply { loadFailure = java.io.IOException("synthetic damaged file") }
                val history = memory.open(this)
                try { history.contains(door.address); fail("expected Unavailable") } catch (_: HttpsHistory.Unavailable) {}
                val store = Store()
                val repository = repository(store, probe = realProbe, history = history)
                assertEquals(ServerCheck.Found("http://${door.address}", true), repository.checkServer("http://${door.address}"))
                assertEquals("typed http never asks the history", listOf(false), seenOverHttpsFlags)
                assertNull(repository.login("http://${door.address}", "synthetic", "synthetic-password"))
                assertEquals("http://${door.address}", store.saved?.serverUrl)
                // The automatic fallback (no scheme) stays refused.
                assertTrue(repository.checkServer(door.address) is ServerCheck.Failed)
                assertEquals(listOf(false, true), seenOverHttpsFlags)
            }
        }
    }

    /** No history file is an empty history that still has to be written; a file is taken as it is. */
    @Test fun startsFromTheFileElseEmpty() {
        assertEquals(HttpsHistory.Stored(setOf("file.example.org:443"), persisted = true), HttpsHistory.stored(setOf("file.example.org:443")))
        assertEquals(HttpsHistory.Stored(emptySet(), persisted = true), HttpsHistory.stored(emptySet()))
        assertEquals(HttpsHistory.Stored(emptySet(), persisted = false), HttpsHistory.stored(null))
    }

    @Test fun anHttpsSubsonicCheckAnswerIsRecorded() = runBlocking {
        val memory = MemoryHistory()
        repository(Store(), resolve = { ServerAnswer("https://music.example.org", true) }, history = memory.open(this)).checkServer("music.example.org")
        repository(Store(), resolve = { ServerAnswer("http://plain.example.org", true) }, history = memory.open(this)).checkServer("plain.example.org")
        repository(Store(), resolve = { ServerAnswer("https://web.example.org", false) }, history = memory.open(this)).checkServer("web.example.org")
        assertEquals(setOf("music.example.org:443"), memory.saved)
    }

    /** The saved session's https server counts as seen, and re-login authenticates exactly that https URL. */
    @Test fun savedHttpsSessionKeepsHttpsOnReLogin() = runBlocking {
        val store = Store()
        val repository = repository(store, resolve = { throw SubsonicError.Network(ConnectException("synthetic")) })
        assertNull(repository.login("https://music.example.org", "synthetic", "synthetic-password"))
        val session = (repository.state.value as SessionState.Connected).session
        repository.unauthorized(session.token)
        assertTrue(repository.state.value is SessionState.NeedsLogin)
        // The check of the same address (scheme-less) is told the server worked over https: no http fallback.
        repository.checkServer("music.example.org")
        assertEquals(listOf(true), seenOverHttpsFlags)
        // The re-login sheet signs in with the saved URL: https, never probed, never http.
        assertNull(repository.login(session.client.configuration.serverUrl, "synthetic", "new-password"))
        assertEquals("https://music.example.org", store.saved?.serverUrl)
        assertEquals("https://music.example.org", (repository.state.value as SessionState.Connected).session.client.configuration.serverUrl)
        assertEquals(listOf("music.example.org"), resolved)
    }

    /**
     * Round 8 P2: restoring saved https credentials records the server, so signing out does not remove its only
     * protection when the history did not know it (credentials older than the history / recents).
     */
    @Test fun restoredHttpsSessionIsRecordedAndSurvivesSignOut() = runBlocking {
        for (rejected in listOf(false, true)) {
            seenOverHttpsFlags.clear()
            val memory = MemoryHistory()
            val store = Store().apply { saved = Credentials("https://music.example.org", "synthetic", "synthetic-password") }
            val repository = repository(store, history = memory.open(this),
                verify = { if (rejected) throw SubsonicError.Unauthorized() },
                resolve = { throw SubsonicError.Network(ConnectException("synthetic")) })
            repository.restore()
            assertTrue(repository.state.value is SessionState.Connected || repository.state.value is SessionState.NeedsLogin)
            repository.signOut()
            val check = memory.open(this) // as after a restart
            assertTrue("rejected=$rejected: recorded", check.contains("music.example.org"))
            repository.checkServer("music.example.org")
            assertEquals("rejected=$rejected: no http fallback after sign-out", listOf(true), seenOverHttpsFlags)
        }
    }

    @Test fun restoredHttpSessionIsNotRecordedAndASupersededRestoreRecordsNothing() = runBlocking {
        val memory = MemoryHistory()
        val plain = Store().apply { saved = Credentials("http://music.example.org", "synthetic", "synthetic-password") }
        repository(plain, history = memory.open(this)).restore()
        assertEquals(emptySet<String>(), memory.saved)
        val gate = CompletableDeferred<Unit>()
        val store = Store().apply { saved = Credentials("https://music.example.org", "synthetic", "synthetic-password") }
        val repository = repository(store, history = memory.open(this), verify = { gate.await() })
        val restoring = launch(kotlinx.coroutines.Dispatchers.Default) { repository.restore() }
        while (repository.state.value !is SessionState.Connected) kotlinx.coroutines.yield()
        repository.signOut() // supersedes the restore
        gate.complete(Unit)
        restoring.join()
        assertEquals("a superseded restore records nothing", emptySet<String>(), memory.saved)
    }
}
