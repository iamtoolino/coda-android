package dev.coda.core.playback

import dev.coda.core.connectivity.RecoveryPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The read-ahead loop rests ([ReadAheadRest]) while an auto-resume wait sleeps past its lock cap, like BAT-6 deep
 * sleep: no pass, no timer. A real switch runs one pass; the end of the wait (a successful probe) brings it back. Fake clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadAheadRestTest {
    private var now = 0L
    private var online = false
    private val player = PlaybackController(FakeAudioEngine(), networkAvailable = { online }, nowMs = { now }, recoveryPolicy = RecoveryPolicy())
    private val deepSleep = MutableStateFlow(false)
    private val switches = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private fun TestScope.restCall() = async { ReadAheadRest.await(deepSleep, player.state, switches) }.also { runCurrent() }

    @Test fun anAsleepAutoResumeWaitRestsUntilASwitchOrItsEnd() = runTest {
        player.replaceQueue(listOf(QueueEntry("remote")), startPositionMs = 1234) // offline: an auto-resume wait
        assertTrue("Within the first 10 min the read-ahead runs", restCall().isCompleted)
        now = RecoveryPolicy().lockCapMs + 1
        player.probeFailed()
        assertTrue(ReadAheadRest.rests(false, player.state.value))
        val first = restCall()
        assertFalse("Asleep: rests", first.isCompleted)
        switches.tryEmit(Unit); runCurrent()
        assertTrue("A real switch runs one pass", first.isCompleted)
        val second = restCall()
        player.retryNow(); runCurrent()
        assertFalse("Retry alone does not end the wait", second.isCompleted)
        online = true
        player.probeSucceeded(player.recovery.waiting!!.target); runCurrent()
        assertTrue("The wait ended: read-ahead is back", second.isCompleted)
        assertFalse(ReadAheadRest.rests(false, player.state.value))
    }

    @Test fun deepSleepStillRestsAsBefore() = runTest {
        deepSleep.value = true
        val call = restCall()
        assertFalse(call.isCompleted)
        deepSleep.value = false; runCurrent()
        assertTrue(call.isCompleted)
    }
}
