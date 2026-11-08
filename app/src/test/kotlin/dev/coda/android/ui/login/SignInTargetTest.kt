package dev.coda.android.ui.login

import dev.coda.android.session.LoginError
import dev.coda.android.session.LoginField
import dev.coda.android.session.ServerCheck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Without a scheme, Sign in authenticates only the shared check's result, once it is on screen. */
class SignInTargetTest {
    private val https = ServerCheck.Found("https://home.example.net", subsonic = true)
    private val http = ServerCheck.Found("http://home.example.net", subsonic = true)
    /** The tests before LG-3: the user has agreed to every http address. */
    private val agreed: suspend (String) -> Boolean = { true }

    /** A probe whose successive calls disagree (https, then http): only one of them may ever run per address. */
    private class Probe(private vararg val answers: ServerCheck?) {
        val gates = answers.map { CompletableDeferred<Unit>() }
        var calls = 0
        suspend fun check(@Suppress("UNUSED_PARAMETER") input: String): ServerCheck? {
            val call = calls++
            gates[call].await()
            return answers[call]
        }
    }

    private suspend fun signIn(runner: ServerCheckRunner, events: MutableList<String>, current: ServerCheck? = runner.shown?.second) =
        signInTarget("home.example.net", current, { runner.request(it, retryFailed = true).await() }, agreed) {
            events += "shown ${runner.shown?.second}"
        }.also { events += "authenticate $it" }

    @Test fun backgroundCheckFirstThenSignInJoinsIt() = runBlocking {
        val probe = Probe(https, http)
        val runner = ServerCheckRunner(this, probe::check)
        val events = mutableListOf<String>()
        runner.request("home.example.net") // the field's check, still running
        val target = async { signIn(runner, events, current = ServerCheck.Checking) }
        repeat(5) { yield() }
        assertFalse("nothing is authenticated while the check is pending", events.any { it.startsWith("authenticate") })
        assertEquals(1, probe.calls)
        probe.gates[0].complete(Unit)
        target.await()
        assertEquals("Sign in joined the running check", 1, probe.calls)
        assertEquals(listOf("shown $https", "authenticate ${SignInTarget.Url(https.url)}"), events)
        assertEquals("home.example.net" to https, runner.shown)
    }

    @Test fun signInFirstThenTheBackgroundCheckJoinsIt() = runBlocking {
        val probe = Probe(http, https)
        val runner = ServerCheckRunner(this, probe::check)
        val events = mutableListOf<String>()
        val target = async { signIn(runner, events, current = null) }
        repeat(5) { yield() }
        runner.request("home.example.net") // the debounce fires while Sign in waits
        repeat(5) { yield() }
        probe.gates.forEach { it.complete(Unit) }
        target.await()
        assertEquals("one probe for the address", 1, probe.calls)
        assertEquals(listOf("shown $http", "authenticate ${SignInTarget.Url(http.url)}"), events)
        assertEquals("home.example.net" to http, runner.shown)
    }

    @Test fun aSupersededCheckNeverWritesTheScreen() = runBlocking {
        val oldGate = CompletableDeferred<Unit>()
        val newGate = CompletableDeferred<Unit>()
        val runner = ServerCheckRunner(this) { input ->
            if (input == "old.example.net") {
                // Ignores cancellation, like a probe stuck in blocking I/O, and answers late.
                withContext(NonCancellable) { oldGate.await() }
                https.copy(url = "https://old.example.net")
            } else { newGate.await(); http }
        }
        runner.request("old.example.net")
        repeat(3) { yield() } // the old check is running
        runner.reset("home.example.net")
        val events = mutableListOf<String>()
        val target = async { signIn(runner, events, current = null) }
        repeat(5) { yield() }
        oldGate.complete(Unit) // the superseded check answers while the new one is pending
        repeat(5) { yield() }
        assertEquals("home.example.net" to ServerCheck.Checking, runner.shown)
        newGate.complete(Unit)
        assertEquals(SignInTarget.Url(http.url), target.await())
        assertEquals(listOf("shown $http", "authenticate ${SignInTarget.Url(http.url)}"), events)
    }

    @Test fun aFinishedFailureIsCheckedAgainOnSignIn() = runBlocking {
        val probe = Probe(ServerCheck.Failed(untrusted = false), http)
        probe.gates.forEach { it.complete(Unit) }
        val runner = ServerCheckRunner(this, probe::check)
        runner.request("home.example.net").await()
        val events = mutableListOf<String>()
        assertEquals(SignInTarget.Url(http.url), signIn(runner, events))
        assertEquals(2, probe.calls)
        assertEquals(listOf("shown $http", "authenticate ${SignInTarget.Url(http.url)}"), events)
    }

    @Test fun aFinishedDisplayedCheckIsUsedAsItIs() = runBlocking {
        val target = signInTarget("music.example.org", ServerCheck.Found("https://music.example.org", true), { error("no new check") }, agreed) { error("nothing to wait for") }
        assertEquals(SignInTarget.Url("https://music.example.org"), target)
    }

    @Test fun typedSchemeIsUsedAsItIsWithoutACheck() = runBlocking {
        assertEquals(SignInTarget.Url("http://home.example.net:4533"),
            signInTarget("http://home.example.net:4533", null, { error("no check") }, agreed) { error("nothing to wait for") })
    }

    @Test fun failedChecksRefuseWithTheExistingMessages() = runBlocking {
        assertEquals(SignInTarget.Refused(LoginError(LoginField.Server, "The server's certificate isn't trusted.")),
            signInTarget("music.example.org", null, { ServerCheck.Failed(untrusted = true) }, agreed) {})
        assertEquals(SignInTarget.Refused(LoginError(LoginField.Server, "Can't reach the server. Check the address and your network.")),
            signInTarget("music.example.org", null, { ServerCheck.Failed(untrusted = false) }, agreed) {})
        // A non-certificate TLS failure is reported as a connection problem, not as "untrusted".
        assertEquals(SignInTarget.Refused(LoginError(LoginField.Server, "Couldn't set up a secure connection to the server.")),
            signInTarget("music.example.org", null, {
                ServerCheck.Failed(untrusted = false, dev.coda.android.session.LoginMessages.NO_SECURE_CONNECTION)
            }, agreed) {})
    }

    @Test fun notAnAddressGoesToSignInForItsMessage() = runBlocking {
        assertEquals(SignInTarget.Url("not an address"), signInTarget("not an address", null, { null }, agreed) {})
    }

    // Plain http only after the user agreed ("Sign in anyway", now or in an earlier successful sign-in).

    @Test fun anInstantPressOnAnUnconfirmedHttpServerAuthenticatesNothing() = runBlocking {
        // Autofill + an instant press: the check is still running, finds http, shows it, and then stops at the notice.
        val probe = Probe(http)
        val runner = ServerCheckRunner(this, probe::check)
        val events = mutableListOf<String>()
        val asked = mutableListOf<String>()
        val target = async {
            signInTarget("home.example.net", ServerCheck.Checking, { runner.request(it, retryFailed = true).await() },
                consented = { asked += it; false }) { events += "shown ${runner.shown?.second}" }
        }
        repeat(5) { yield() }
        assertEquals("nothing is asked or authenticated before the check is shown", emptyList<String>(), asked)
        probe.gates[0].complete(Unit)
        assertEquals(SignInTarget.NeedsConsent(http.url), target.await())
        assertEquals(listOf("shown $http"), events)
        assertEquals("consent is asked for the resolved, shown address", listOf(http.url), asked)
    }

    @Test fun aTypedHttpAddressAsksToo() = runBlocking {
        assertEquals(SignInTarget.NeedsConsent("http://home.example.net:4533"),
            signInTarget("http://home.example.net:4533", null, { error("no check") }, { false }) { error("nothing to wait for") })
        assertEquals(SignInTarget.NeedsConsent("HTTP://home.example.net"),
            signInTarget("HTTP://home.example.net", null, { error("no check") }, { false }) { error("nothing to wait for") })
    }

    @Test fun aConfirmedAddressDoesNotAskAgain() = runBlocking {
        val confirmed = setOf("http://home.example.net:4533")
        assertEquals(SignInTarget.Url("http://home.example.net:4533"),
            signInTarget("http://home.example.net:4533", null, { error("no check") }, { it in confirmed }) {})
        assertEquals("another port is another server", SignInTarget.NeedsConsent("http://home.example.net:8080"),
            signInTarget("http://home.example.net:8080", null, { error("no check") }, { it in confirmed }) {})
        assertEquals(SignInTarget.Url(http.url), signInTarget("home.example.net", http, { error("no check") }, { it == http.url }) {})
    }

    @Test fun httpsNeverAsks() = runBlocking {
        val never: suspend (String) -> Boolean = { error("https must not ask: $it") }
        assertEquals(SignInTarget.Url(https.url), signInTarget("home.example.net", https, { error("no check") }, never) {})
        assertEquals(SignInTarget.Url("https://home.example.net"), signInTarget("https://home.example.net", null, { error("no check") }, never) {})
        // A refused check or something that is no address is not a consent question either.
        assertEquals(SignInTarget.Refused(LoginError(LoginField.Server, "Can't reach the server. Check the address and your network.")),
            signInTarget("home.example.net", null, { ServerCheck.Failed(untrusted = false) }, never) {})
        assertEquals(SignInTarget.Url("not an address"), signInTarget("not an address", null, { null }, never) {})
    }
}
