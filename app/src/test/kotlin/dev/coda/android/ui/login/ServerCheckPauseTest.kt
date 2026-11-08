package dev.coda.android.ui.login

import dev.coda.android.session.ServerCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The live server check waits for a pause, never runs for incomplete input, and a new keystroke cancels it. */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerCheckPauseTest {
    private val found = ServerCheck.Found("https://home.example.net", subsonic = true)

    private class Probe(val answer: ServerCheck? = ServerCheck.Found("https://home.example.net", subsonic = true)) {
        val calls = mutableListOf<String>()
        var cancelled = 0
        suspend fun check(input: String): ServerCheck? {
            calls += input
            try { awaitCancellation() } catch (e: CancellationException) { cancelled++; throw e }
        }
        suspend fun quick(input: String): ServerCheck? { calls += input; return answer }
    }

    /** The screen's effect for one address: it restarts (is cancelled) when the text changes. */
    private fun TestScope.typed(runner: ServerCheckRunner, input: String, focusLost: MutableStateFlow<Boolean> = MutableStateFlow(false)): Job =
        launch { runner.checkAfterPause(input, focusLost) }

    @Test fun debounceIsInTheCardsRange() {
        assertTrue(ServerCheckDebounceMillis in 600..800)
    }

    @Test fun nothingRunsBeforeThePauseThenExactlyOnce() = runTest {
        val probe = Probe()
        val runner = ServerCheckRunner(this, probe::quick)
        typed(runner, "home.example.net")
        advanceTimeBy(ServerCheckDebounceMillis - 1)
        runCurrent()
        assertTrue("no check before the pause", probe.calls.isEmpty())
        assertNull("nothing shown, not even Checking", runner.shown)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("home.example.net"), probe.calls)
        assertEquals("home.example.net" to probe.answer, runner.shown)
    }

    @Test fun typingKeepsPushingTheCheckBack() = runTest {
        val probe = Probe()
        val runner = ServerCheckRunner(this, probe::quick)
        var effect = typed(runner, "h")
        // Eight keystrokes 500 ms apart: each cancels the wait of the one before (the screen's LaunchedEffect).
        for (text in listOf("ho", "hom", "home", "home.", "home.e", "home.ex", "home.exa")) {
            advanceTimeBy(500)
            effect.cancel()
            effect = typed(runner, text)
        }
        advanceTimeBy(ServerCheckDebounceMillis - 1)
        runCurrent()
        assertTrue("no probe while the user keeps typing", probe.calls.isEmpty())
        assertNull(runner.shown)
        advanceTimeBy(2)
        runCurrent()
        assertEquals("only the final text is checked", listOf("home.exa"), probe.calls)
    }

    @Test fun aNewKeystrokeCancelsARunningCheckAndDropsWhatItShowed() = runTest {
        val probe = Probe()
        val runner = ServerCheckRunner(this, probe::check)
        val first = typed(runner, "home.example.net")
        advanceTimeBy(ServerCheckDebounceMillis + 1)
        runCurrent()
        assertEquals(ServerCheck.Checking, runner.shown?.second)
        first.cancel()
        typed(runner, "home.example.net:")
        runCurrent()
        assertEquals("the running check is cancelled", 1, probe.cancelled)
        assertNull("what the old address showed is gone with the keystroke", runner.shown)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("a trailing colon is never checked", listOf("home.example.net"), probe.calls)
    }

    @Test fun incompleteInputIsNeverChecked() = runTest {
        val probe = Probe()
        val runner = ServerCheckRunner(this, probe::quick)
        val gone = MutableStateFlow(true) // even with focus already gone
        for (text in listOf("", "  ", "home.example.net:", "home.example.net.", "https://", "http://", "https://home.example.net:", "198.51.100.")) {
            typed(runner, text, gone)
            advanceTimeBy(5_000)
            runCurrent()
        }
        assertTrue("no check for ${probe.calls}", probe.calls.isEmpty())
        assertNull(runner.shown)
    }

    @Test fun leavingTheFieldChecksAtOnce() = runTest {
        val probe = Probe()
        val runner = ServerCheckRunner(this, probe::quick)
        val focusLost = MutableStateFlow(false)
        typed(runner, "home.example.net", focusLost)
        advanceTimeBy(100)
        runCurrent()
        assertTrue(probe.calls.isEmpty())
        focusLost.value = true
        runCurrent()
        assertEquals(listOf("home.example.net"), probe.calls)
    }

    @Test fun incompleteInputRule() {
        assertTrue(isIncompleteServerInput(""))
        assertTrue(isIncompleteServerInput("home:"))
        assertTrue(isIncompleteServerInput("home."))
        assertTrue(isIncompleteServerInput("https://"))
        assertFalse(isIncompleteServerInput("home"))
        assertFalse(isIncompleteServerInput("home.example.net:4533"))
        assertFalse(isIncompleteServerInput("http://198.51.100.5:4"))
    }

    @Test fun intermediateResultsAreInformationNotAlarms() {
        // A plain web server on 80/443 before the Navidrome port is typed: neutral hint, no chip, no error colour.
        assertEquals(ServerNote.NoServerYet, serverNote(ServerCheck.Found("http://home.example.net", subsonic = false)))
        assertEquals(ServerNote.NoServerYet, serverNote(ServerCheck.Found("https://home.example.net", subsonic = false)))
        // The http fallback met another web server: the https failure stays for Sign in, the field shows the hint.
        assertEquals(ServerNote.NoServerYet, serverNote(ServerCheck.Failed(untrusted = false, otherServer = true)))
        assertNull(serverNote(ServerCheck.Failed(untrusted = false)))
        assertEquals("No music server found here yet", ServerNote.NoServerYet.text)
        // A certificate this phone does not trust: information with an explanation.
        assertEquals(ServerNote.CertificateNotTrusted, serverNote(ServerCheck.Failed(untrusted = true)))
        assertEquals("Certificate not trusted by this phone", ServerNote.CertificateNotTrusted.text)
        // A music server, a check in progress, a network failure and no result keep their own states.
        assertNull(serverNote(found))
        assertNull(serverNote(ServerCheck.Found("http://home.example.net", subsonic = true)))
        assertNull(serverNote(ServerCheck.Checking))
        assertNull(serverNote(ServerCheck.Failed(untrusted = false)))
        assertNull(serverNote(null))
    }
}
