package dev.coda.android.session

import dev.coda.core.subsonic.SubsonicError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The stored "Sign in anyway" agreements, with the https history's persistence rules. */
class PlainHttpConsentTest {
    /**
     * Kept in memory, loaded with the production rule ([PlainHttpConsent.stored]): [saved] is the setting (null = not
     * written yet), [savedSession] the saved session's server; [saveResult] decides each write; [loadFailure] fails reads.
     */
    private class Memory(initial: Set<String>? = emptySet()) {
        @Volatile var saved: Set<String>? = initial
        @Volatile var savedSession: String? = null
        @Volatile var loadFailure: Exception? = null
        @Volatile var saveResult: suspend (Set<String>) -> Boolean = { true }
        val writes = java.util.concurrent.atomic.AtomicInteger()
        /** The revocation file as bytes (null = absent), through the production format and parser. */
        @Volatile var revokedFile: ByteArray? = null
        @Volatile var revokedWriteOk = true
        @Volatile var revokedLoadFailure: Exception? = null
        fun open(scope: CoroutineScope) = PlainHttpConsent(scope,
            load = { loadFailure?.let { throw it }; PlainHttpConsent.stored(saved, savedSession) },
            save = { value -> writes.incrementAndGet(); saveResult(value).also { if (it) saved = value } },
            loadRevocations = { revokedLoadFailure?.let { throw it }; revokedFile?.let { PlainHttpConsent.parseRevocations(it) } ?: PlainHttpConsent.Revocations.NONE },
            saveRevocations = { value ->
                revokedWriteOk.also { if (it) revokedFile = PlainHttpConsent.formatRevocations(value).toByteArray() }
            })
        /** A new process: a new instance over the same storage. */
        fun restart(scope: CoroutineScope) = open(scope)
    }

    private class Store : CredentialStorage {
        var saved: Credentials? = null
        override suspend fun read() = saved
        override suspend fun write(value: Credentials) { saved = value }
        override suspend fun clear() { saved = null }
    }

    @Test fun keyIsHostAndPortOfPlainHttpOnly() {
        assertEquals("home.example.net:80", PlainHttpConsent.key("http://home.example.net"))
        assertEquals("home.example.net:80", PlainHttpConsent.key("HTTP://Home.Example.Net./"))
        assertEquals("home.example.net:4533", PlainHttpConsent.key(" http://home.example.net:4533/navidrome "))
        assertNull("https never needs consent", PlainHttpConsent.key("https://home.example.net"))
        assertNull("no scheme is https", PlainHttpConsent.key("home.example.net"))
        assertNull("never an address with user info", PlainHttpConsent.key("http://user:secret@home.example.net"))
        assertNull(PlainHttpConsent.key("not an address"))
    }

    @Test fun agreeContainsAndForget() = runBlocking {
        val memory = Memory()
        val consent = memory.open(this)
        assertFalse(consent.contains("http://home.example.net:4533"))
        consent.agree("http://home.example.net:4533")
        consent.agree("https://secure.example.net") // ignored
        assertTrue(consent.contains("http://HOME.example.net:4533/"))
        assertFalse("another port", consent.contains("http://home.example.net"))
        assertEquals(setOf("home.example.net:4533"), memory.saved)
        assertTrue("after a restart", memory.open(this).contains("http://home.example.net:4533"))
        consent.forget("http://home.example.net:4533")
        assertFalse(consent.contains("http://home.example.net:4533"))
        assertEquals(emptySet<String>(), memory.saved)
    }

    /** Migration: a saved http session from before LG-3 counts as agreed, and that is written at start. */
    @Test fun aSavedHttpSessionIsMigratedAndWrittenAtStart() = runBlocking {
        val memory = Memory(initial = null).apply { savedSession = "http://legacy.example.net:4533" }
        val consent = memory.open(this)
        repeat(20) { yield() } // the start-up write, before any use
        assertEquals(setOf("legacy.example.net:4533"), memory.saved)
        // Signing out afterwards (the saved session is gone) keeps it.
        memory.savedSession = null
        assertTrue(memory.open(this).contains("http://legacy.example.net:4533"))
        assertTrue(consent.contains("http://legacy.example.net:4533"))
        // An https session or none migrates nothing, but the (empty) set is written, so it never migrates again.
        val https = Memory(initial = null).apply { savedSession = "https://secure.example.net" }
        assertFalse(https.open(this).contains("http://secure.example.net"))
        assertEquals(emptySet<String>(), https.saved)
    }

    @Test fun failedOrCancelledWritesAreRetriedUntilCommitted() = runBlocking {
        val memory = Memory()
        val consent = memory.open(this)
        memory.saveResult = { false } // commit() returned false
        consent.agree("http://a.example.net")
        memory.saveResult = { throw java.io.IOException("synthetic") }
        consent.agree("http://a.example.net")
        assertEquals(emptySet<String>(), memory.saved)
        assertFalse("a restart now asks again", memory.open(this).contains("http://a.example.net"))
        memory.saveResult = { true }
        assertTrue("the next use retries the pending write", consent.contains("http://a.example.net"))
        assertEquals(setOf("a.example.net:80"), memory.saved)

        // A migration whose first write is not committed stays pending and is written on the next use.
        val migrated = Memory(initial = null).apply { savedSession = "http://legacy.example.net"; saveResult = { false } }
        val opened = migrated.open(this)
        repeat(20) { yield() }
        assertNull(migrated.saved)
        migrated.saveResult = { true }
        assertTrue(opened.contains("http://legacy.example.net"))
        assertEquals(setOf("legacy.example.net:80"), migrated.saved)

        // A write cancelled mid-way stays dirty.
        val entered = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Boolean>()
        memory.saveResult = { entered.complete(Unit); never.await() }
        val adding = launch(kotlinx.coroutines.Dispatchers.Default) { consent.agree("http://b.example.net") }
        entered.await()
        adding.cancelAndJoin()
        memory.saveResult = { true }
        assertTrue(consent.contains("http://b.example.net"))
        assertEquals(setOf("a.example.net:80", "b.example.net:80"), memory.saved)
    }

    /** Fail closed: an unreadable set is "not agreed" for the caller (the screen asks again) and is read again later. */
    @Test fun unreadableAgreementsThrowAndAreReadAgain() = runBlocking {
        val memory = Memory(initial = setOf("home.example.net:80")).apply { loadFailure = java.io.IOException("synthetic") }
        val consent = memory.open(this)
        try { consent.contains("http://home.example.net"); fail("expected Unavailable") } catch (_: PlainHttpConsent.Unavailable) {}
        try { consent.agree("http://other.example.net"); fail("expected Unavailable") } catch (_: PlainHttpConsent.Unavailable) {}
        assertEquals("nothing overwritten", setOf("home.example.net:80"), memory.saved)
        memory.loadFailure = null
        assertTrue(consent.contains("http://home.example.net"))
    }

    /** Only a successful manual http sign-in records the address the user agreed to; nothing else does. */
    @Test fun onlyASuccessfulManualHttpSignInIsRecorded() = runBlocking {
        val agreed = mutableListOf<String>()
        var reject = false
        val repository = SessionRepository(Store(), "test", NoAutoLoginPreference, recordPlainHttp = { agreed += it },
            httpClient = { okhttp3.OkHttpClient() }, verify = { if (reject) throw SubsonicError.Unauthorized() })
        reject = true
        repository.login("http://wrong.example.net:4533", "synthetic", "synthetic-password")
        reject = false
        repository.loginAutomatically("http://auto.example.net", "synthetic", "synthetic-password")
        repository.signOut()
        assertNull(repository.login("https://secure.example.net", "synthetic", "synthetic-password"))
        assertNull(repository.login("http://home.example.net:4533", "synthetic", "synthetic-password"))
        assertEquals(listOf("http://home.example.net:4533"), agreed)
    }

    /** The file format: a damaged file is unavailable (ask again), never an empty set. */
    @Test fun fileFormatRoundTripsAndRejectsDamage() {
        val keys = setOf("home.example.net:4533", "192.0.2.10:80", "::1:8080")
        assertEquals(keys, PlainHttpConsent.parse(PlainHttpConsent.format(keys)))
        assertEquals(emptySet<String>(), PlainHttpConsent.parse(PlainHttpConsent.format(emptySet())))
        val header = "coda-plain-http-consent v1\n"
        for (damaged in listOf("", "\n", "home.example.net:80\n", "coda-plain-http-consent v2\nhome.example.net:80\nend\n",
            header + "home.example.net\nend\n", header + "<?xml\nend\n", header + "\nend\n",
            header + "home.example.net:80\n", header + "home.example.net:80\nend", header + "end", header + "end\nend\n",
            header + "home.example.net:80\nend\nhome.example.net:81\n", header + "home.example.net:080\nend\n",
            header + "home.example.net:0\nend\n", header + "home.example.net:65536\nend\n", header + "Home.Example.net:80\nend\n",
            header + "home.example.net:80\r\nend\n")) {
            try { PlainHttpConsent.parse(damaged); fail("accepted damaged ${damaged.length} bytes: $damaged") } catch (_: java.io.IOException) {}
        }
        try { PlainHttpConsent.parse(byteArrayOf(*header.toByteArray(), 0xC3.toByte(), 0x28, *"\nend\n".toByteArray())); fail("accepted bad UTF-8") }
        catch (_: java.io.IOException) {}
    }

    /** A file cut anywhere (inside a port, between entries, before `end`) is damaged, never fewer or other entries. */
    @Test fun aTruncatedFileIsNeverAnAgreement() {
        val keys = setOf("home.example.net:8080", "other.example.net:4533")
        val text = PlainHttpConsent.format(keys)
        // The reviewed case: ":8080" cut to ":80" (a complete-looking line without the end marker).
        val cut = text.substring(0, text.indexOf(":8080") + 3)
        assertTrue(cut.endsWith("home.example.net:80"))
        for (length in 0 until text.length) {
            val prefix = text.substring(0, length)
            try { PlainHttpConsent.parse(prefix); fail("accepted a file cut at $length: $prefix") } catch (_: java.io.IOException) {}
            try { PlainHttpConsent.parse(prefix.toByteArray()); fail("accepted bytes cut at $length") } catch (_: java.io.IOException) {}
        }
        assertEquals(keys, PlainHttpConsent.parse(text))
    }

    /** Forgetting while the set is unreadable is kept and applied when it can be read again. */
    @Test fun aForgetWhileUnreadableIsAppliedOnRecovery() = runBlocking {
        val memory = Memory(initial = setOf("home.example.net:80", "keep.example.net:80"))
        val consent = memory.open(this)
        assertTrue(consent.contains("http://home.example.net"))
        // A fresh start whose read fails.
        val unreadable = Memory(initial = setOf("home.example.net:80", "keep.example.net:80")).apply { loadFailure = java.io.IOException("synthetic") }
        val reopened = unreadable.open(this)
        reopened.forget("http://home.example.net") // does not throw; the caller (the recents ×) is done
        try { reopened.contains("http://home.example.net"); fail("expected Unavailable") } catch (_: PlainHttpConsent.Unavailable) {}
        assertEquals("nothing overwritten while unreadable", setOf("home.example.net:80", "keep.example.net:80"), unreadable.saved)
        unreadable.loadFailure = null
        assertFalse("the forgotten agreement does not come back", reopened.contains("http://home.example.net"))
        assertTrue(reopened.contains("http://keep.example.net"))
        assertEquals("and the removal is written", setOf("keep.example.net:80"), unreadable.saved)
    }

    /** The forget is recorded before anything can cancel the caller. */
    @Test fun aCancelledForgetIsStillApplied() = runBlocking {
        val memory = Memory(initial = setOf("home.example.net:80"))
        val consent = memory.open(this)
        assertTrue(consent.contains("http://home.example.net")) // loaded
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Boolean>()
        memory.saveResult = { entered.complete(Unit); release.await() }
        val forgetting = launch(kotlinx.coroutines.Dispatchers.Default) { consent.forget("http://home.example.net") }
        kotlinx.coroutines.withTimeout(10_000) { entered.await() } // fails instead of hanging if the forget never writes
        forgetting.cancel() // the screen is left while the write is under way
        release.complete(true)
        forgetting.join()
        assertEquals(emptySet<String>(), memory.saved)
        assertFalse(consent.contains("http://home.example.net"))
    }

    /** An http login that upgraded to https is a https recent; forgetting it revokes the http agreement. */
    @Test fun forgettingAnUpgradedHttpsRecentRevokesTheHttpAgreement() = runBlocking {
        val memory = Memory()
        val consent = memory.open(this)
        consent.agree("http://home.example.net:8080") // the typed address; the recent became https://home.example.net (port 443)
        consent.agree("http://home.example.net:4533")
        consent.agree("http://other.example.net:8080")
        consent.forget("https://other-server.example.net") // another host: nothing
        assertTrue(consent.contains("http://home.example.net:8080"))
        consent.forget("https://Home.Example.Net/")
        assertFalse("typed http:// asks again", consent.contains("http://home.example.net:8080"))
        assertFalse(consent.contains("http://home.example.net:4533"))
        assertTrue("another host stays", consent.contains("http://other.example.net:8080"))
        assertEquals(setOf("other.example.net:8080"), memory.saved)
        // An http recent revokes exactly its host:port.
        consent.agree("http://other.example.net:80")
        consent.forget("http://other.example.net:8080")
        assertFalse(consent.contains("http://other.example.net:8080"))
        assertTrue(consent.contains("http://other.example.net"))
    }

    /** The same upgrade -> forget path while the set is unreadable. */
    @Test fun aHostForgetWhileUnreadableIsAppliedOnRecovery() = runBlocking {
        val memory = Memory(initial = setOf("home.example.net:8080")).apply { loadFailure = java.io.IOException("synthetic") }
        val consent = memory.open(this)
        consent.forget("https://home.example.net")
        memory.loadFailure = null
        assertFalse(consent.contains("http://home.example.net:8080"))
    }

    /** A forget made while the set is unreadable survives process death. */
    @Test fun aForgetWhileUnreadableSurvivesARestart() = runBlocking {
        val memory = Memory(initial = setOf("home.example.net:80", "keep.example.net:80")).apply { loadFailure = java.io.IOException("synthetic") }
        val first = memory.open(this)
        first.forget("http://home.example.net") // does not throw
        assertTrue("the revocation is on disk", memory.revokedFile != null)
        // Process death, then recovery: a new instance reads the set.
        memory.loadFailure = null
        val second = memory.restart(this)
        assertFalse("the forgotten agreement does not come back", second.contains("http://home.example.net"))
        assertTrue(second.contains("http://keep.example.net"))
        assertEquals(setOf("keep.example.net:80"), memory.saved)
        assertEquals("the revocation is cleared once the set is written", PlainHttpConsent.Revocations.NONE,
            PlainHttpConsent.parseRevocations(memory.revokedFile!!))
        assertFalse(memory.restart(this).contains("http://home.example.net"))
    }

    @Test fun aHostWideForgetWhileUnreadableSurvivesARestart() = runBlocking {
        val memory = Memory(initial = setOf("home.example.net:8080", "home.example.net:4533", "other.example.net:8080"))
            .apply { loadFailure = java.io.IOException("synthetic") }
        memory.open(this).forget("https://Home.Example.Net/") // the upgraded https recent
        memory.loadFailure = null
        val restarted = memory.restart(this)
        assertFalse(restarted.contains("http://home.example.net:8080"))
        assertFalse(restarted.contains("http://home.example.net:4533"))
        assertTrue("another host stays", restarted.contains("http://other.example.net:8080"))
        assertEquals(setOf("other.example.net:8080"), memory.saved)
    }

    /** Two forgets, each in its own process, while unreadable: both are kept (the file is merged, not replaced). */
    @Test fun revocationsAreMergedAcrossRestarts() = runBlocking {
        val memory = Memory(initial = setOf("a.example.net:80", "b.example.net:80", "c.example.net:80"))
            .apply { loadFailure = java.io.IOException("synthetic") }
        memory.open(this).forget("http://a.example.net")
        memory.restart(this).forget("http://b.example.net")
        memory.loadFailure = null
        val restarted = memory.restart(this)
        assertFalse(restarted.contains("http://a.example.net"))
        assertFalse(restarted.contains("http://b.example.net"))
        assertTrue(restarted.contains("http://c.example.net"))
    }

    /** A damaged revocation file revokes everything: the screen asks again for every server (fail closed). */
    @Test fun aDamagedRevocationFileAsksAgainForEverything() = runBlocking {
        for (damaged in listOf("garbage".toByteArray(), byteArrayOf(0xC3.toByte()), ByteArray(0),
            PlainHttpConsent.formatRevocations(PlainHttpConsent.Revocations(setOf("a.example.net:80"))).dropLast(5).toByteArray())) {
            val memory = Memory(initial = setOf("a.example.net:80", "b.example.net:80")).apply { revokedFile = damaged }
            val consent = memory.open(this)
            assertFalse(consent.contains("http://a.example.net"))
            assertFalse(consent.contains("http://b.example.net"))
            assertEquals(emptySet<String>(), memory.saved)
            assertEquals("the damaged file is replaced by a valid empty one", PlainHttpConsent.Revocations.NONE,
                PlainHttpConsent.parseRevocations(memory.revokedFile!!))
            consent.agree("http://a.example.net") // a new agreement after that works
            assertTrue(memory.restart(this).contains("http://a.example.net"))
        }
    }

    /** A revocation that cannot be written still holds in this process and is reported, never silent. */
    @Test fun aRevocationThatCannotBeSavedStillHoldsAndIsReported() = runBlocking {
        val memory = Memory(initial = setOf("home.example.net:80")).apply {
            loadFailure = java.io.IOException("synthetic"); revokedWriteOk = false
        }
        val consent = memory.open(this)
        try { consent.forget("http://home.example.net"); fail("a forget that was saved nowhere returned normally") } catch (_: PlainHttpConsent.RevocationNotSaved) {}
        memory.loadFailure = null
        assertFalse("not granted in this process", consent.contains("http://home.example.net"))
        // Loaded and committed in the set: the same forget is durable without the revocation file.
        val loaded = Memory(initial = setOf("home.example.net:80")).apply { revokedWriteOk = false }
        val second = loaded.open(this)
        assertTrue(second.contains("http://home.example.net"))
        second.forget("http://home.example.net")
        assertEquals(emptySet<String>(), loaded.saved)
        // Neither write works: reported, and still not granted.
        val none = Memory(initial = setOf("home.example.net:80")).apply { revokedWriteOk = false; saveResult = { false } }
        val third = none.open(this)
        assertTrue(third.contains("http://home.example.net"))
        try { third.forget("http://home.example.net"); fail("not reported") } catch (_: PlainHttpConsent.RevocationNotSaved) {}
        assertFalse(third.contains("http://home.example.net"))
    }

    /** A transient revocation-file read error (valid file, other entries) with an unreadable set: reported, and a restart keeps it out. */
    @Test fun aForgetDuringATransientRevocationReadErrorIsNotSilent() = runBlocking {
        val memory = Memory(initial = setOf("home.example.net:80")).apply {
            revokedFile = PlainHttpConsent.formatRevocations(PlainHttpConsent.Revocations(setOf("other.example.net:80"))).toByteArray()
            revokedLoadFailure = java.io.IOException("synthetic"); loadFailure = java.io.IOException("synthetic")
        }
        val consent = memory.open(this)
        try { consent.forget("http://home.example.net"); fail("a forget saved nowhere returned normally") } catch (_: PlainHttpConsent.RevocationNotSaved) {}
        memory.loadFailure = null
        assertFalse("not granted in this process", consent.contains("http://home.example.net"))
    }

    /** A loaded set whose write fails keeps the revocation on disk until the set is committed. */
    @Test fun aRevocationStaysUntilTheSetWithoutItIsCommitted() = runBlocking {
        val memory = Memory(initial = setOf("home.example.net:80"))
        val consent = memory.open(this)
        assertTrue(consent.contains("http://home.example.net"))
        memory.saveResult = { false }
        consent.forget("http://home.example.net") // durable: the revocation file
        assertFalse(consent.contains("http://home.example.net"))
        assertEquals(setOf("home.example.net:80"), memory.saved)
        assertTrue("restart before the set is written: still revoked", !memory.restart(this).contains("http://home.example.net"))
    }

    @Test fun revocationFileFormatRoundTripsAndRejectsDamage() {
        val revoked = PlainHttpConsent.Revocations(setOf("home.example.net:8080", "::1:80"), setOf("other.example.net"))
        val text = PlainHttpConsent.formatRevocations(revoked)
        assertEquals(revoked, PlainHttpConsent.parseRevocations(text.toByteArray()))
        for (length in 0 until text.length) {
            try { PlainHttpConsent.parseRevocations(text.substring(0, length).toByteArray()); fail("accepted a file cut at $length") } catch (_: java.io.IOException) {}
        }
        for (bad in listOf("k home.example.net:08080", "k Home.example.net:80", "h ", "x a.example.net:80", "k home.example.net", "h Home.Example.Net", "h a:b"))
            try { PlainHttpConsent.parseRevocations("coda-plain-http-revoked v1\n$bad\nend\n".toByteArray()); fail("accepted $bad") } catch (_: java.io.IOException) {}
    }
}
