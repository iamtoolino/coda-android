package dev.coda.core.connectivity

import dev.coda.core.connectivity.Reachability.Link
import dev.coda.core.connectivity.Reachability.Network
import dev.coda.core.subsonic.SubsonicError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** The one connectivity state in virtual time, with BAT-2/the battery limits. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReachabilityTest {
    /** [hangMs]: the ping takes this long before it answers (a black hole: longer than the probe timeout). */
    private class Server { var up = true; var answer: Boolean? = null; var hangMs = 0L; val probes = mutableListOf<Long>() }

    private fun TestScope.machine(server: Server, initial: Network = Network.USABLE,
        quiet: MutableStateFlow<Boolean> = MutableStateFlow(false), attended: MutableStateFlow<Boolean> = MutableStateFlow(true),
        log: ((String) -> Unit)? = null, visible: MutableStateFlow<Boolean>? = null) =
        Reachability(backgroundScope, { testScheduler.currentTime }, {
            server.probes += testScheduler.currentTime
            if (server.hangMs > 0) delay(server.hangMs)
            server.answer ?: server.up.also { if (!it) throw SubsonicError.Network(java.io.IOException("down")) }
        }, quiet, initial, attended, log, visible = visible)

    private fun TestScope.at(ms: Long) { advanceTimeBy(ms - testScheduler.currentTime); runCurrent() }

    @Test fun noNetworkForTwoSecondsIsOfflineButAHandoverBlipChangesNothing() = runTest {
        val server = Server()
        val r = machine(server)
        r.network(Network.NONE); at(1_900)
        r.network(Network.USABLE); at(5_000)
        assertEquals("blip < 2 s", Link.ONLINE, r.state.value.link)
        assertTrue("the network change is probed once (1 s settle)", server.probes == listOf(2_900L))
        r.network(Network.NONE); at(6_999)
        assertEquals(Link.ONLINE, r.state.value.link)
        at(7_000)
        assertEquals(Link.OFFLINE, r.state.value.link)
        assertTrue(r.state.value.known)
        assertFalse("the gate's signal", r.online.value)
    }

    @Test fun twoFailuresTenSecondsApartWithNoSuccessBetweenMakeTheServerUnreachable() = runTest {
        val server = Server().apply { up = false }
        val r = machine(server)
        r.failed(); at(9_000); r.failed()
        assertEquals("9 s apart: not yet", Link.ONLINE, r.state.value.link)
        at(9_999)
        assertTrue("no probe before the 10 s confirmation", server.probes.isEmpty())
        at(10_000)
        assertEquals("the first failure's probe is the second failure", listOf(10_000L), server.probes)
        assertEquals(Link.UNREACHABLE, r.state.value.link)
        assertTrue(r.state.value.offerShown)

        // A success between two failures resets the run.
        val other = Server()
        val s = machine(other)
        val t = testScheduler.currentTime
        s.failed(); at(t + 4_000); s.succeeded(); at(t + 11_000); s.failed()
        assertEquals(Link.ONLINE, s.state.value.link)
        assertTrue("the success cancelled the confirmation probe", other.probes.isEmpty())
    }

    @Test fun backOnlineNeedsTwoSuccessesTwoSecondsApartAndANetworkAloneDoesNotLeaveOffline() = runTest {
        val server = Server().apply { up = false }
        val r = machine(server, Network.NONE)
        at(2_000)
        assertEquals(Link.OFFLINE, r.state.value.link)
        r.network(Network.USABLE)
        at(60_000)
        assertEquals("network back, no server answer: still offline", Link.OFFLINE, r.state.value.link)
        server.up = true
        r.succeeded()
        assertEquals("one success is not enough", Link.OFFLINE, r.state.value.link)
        at(60_000 + 1_999); r.succeeded()
        assertEquals("< 2 s apart", Link.OFFLINE, r.state.value.link)
        at(62_000)
        assertEquals("the success scheduled the second (a probe at +2 s)", 62_000L, server.probes.last())
        assertEquals(Link.ONLINE, r.state.value.link)
    }

    @Test fun backProbesSettleOneSecondThenFiveTenTwentyFortySixtyCapped() = runTest {
        val server = Server().apply { up = false }
        val r = machine(server, Network.NONE)
        at(10_000)
        r.network(Network.USABLE)
        at(10_000 + 400_000)
        val gaps = server.probes.zipWithNext { a, b -> b - a }
        assertEquals(11_000L, server.probes.first())
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L, 60_000L), gaps.take(7))
        // Another network change starts the schedule again.
        val n = server.probes.size
        r.networkChanged(); at(testScheduler.currentTime + 1_000)
        assertEquals(n + 1, server.probes.size)
        at(testScheduler.currentTime + 5_000)
        assertEquals(n + 2, server.probes.size)
    }

    @Test fun unreachableToOnlineAndTheOfferIsHeldFiveSeconds() = runTest {
        val server = Server().apply { up = false }
        val r = machine(server)
        r.failed(); at(10_000)
        assertEquals(Link.UNREACHABLE, r.state.value.link)
        server.up = true
        r.succeeded(); at(12_000); r.succeeded()
        assertEquals("held ≥ 5 s, no blinking", Link.UNREACHABLE, r.state.value.link)
        at(15_000)
        assertEquals(Link.ONLINE, r.state.value.link)
    }

    @Test fun theUserChoosesOfflineAndTheServerAnsweringBringsItBack() = runTest {
        val server = Server().apply { up = false }
        val r = machine(server)
        r.chooseOffline()
        assertEquals("no offer, nothing to choose", Link.ONLINE, r.state.value.link)
        r.failed(); at(10_000)
        r.chooseOffline()
        assertTrue(r.state.value.chosen)
        server.up = true
        at(30_000)
        assertEquals("the back probes run from the chosen state too", Link.ONLINE, r.state.value.link)
    }

    @Test fun captivePortalIsUnreachableWithoutProbesAndAHotelPageIsAFailure() = runTest {
        val server = Server()
        val r = machine(server)
        r.network(Network.BLOCKED); at(2_000)
        assertEquals(Link.UNREACHABLE, r.state.value.link)
        at(3_600_000)
        assertTrue("no probe on an unusable network", server.probes.isEmpty())
        assertFalse(r.online.value)
        // A login page that answers the API is a failure; 401 and 404 are server answers.
        assertEquals(false, Reachability.answered(SubsonicError.Decoding()))
        assertEquals(false, Reachability.answered(SubsonicError.Http(502)))
        assertEquals(false, Reachability.answered(SubsonicError.Network(java.io.IOException())))
        assertEquals(true, Reachability.answered(SubsonicError.Unauthorized()))
        assertEquals(true, Reachability.answered(SubsonicError.NotFound()))
        assertEquals(true, Reachability.answered(SubsonicError.Http(404)))
        assertEquals(true, Reachability.answered(SubsonicError.Unauthorized(401)))
        // A proxy or firewall in front of the server: not the server's answer.
        assertEquals(false, Reachability.answered(SubsonicError.Unauthorized(403)))
        assertEquals(false, Reachability.answered(SubsonicError.Http(407)))
        assertEquals(false, Reachability.answered(SubsonicError.Http(429)))
        assertNull(Reachability.answered(SubsonicError.InvalidConfiguration("x")))
    }

    @Test fun unauthorizedAndNotFoundProbesMeanReachable() = runTest {
        val server = Server().apply { up = false }
        val r = Reachability(backgroundScope, { testScheduler.currentTime }, {
            throw SubsonicError.Unauthorized()
        })
        r.failed(); at(10_000)
        assertEquals(Link.ONLINE, r.state.value.link)
        val n = Reachability(backgroundScope, { testScheduler.currentTime }, { throw SubsonicError.Http(404) })
        n.failed(); at(20_000)
        assertEquals(Link.ONLINE, n.state.value.link)
        assertTrue(server.probes.isEmpty())
    }

    @Test fun noProbeWithoutANetworkForAnHour() = runTest {
        val server = Server()
        val r = machine(server, Network.NONE)
        repeat(60) { r.failed(); launch { r.refresh(5 * 60_000L) }; at(testScheduler.currentTime + 60_000) }
        assertTrue(server.probes.isEmpty())
        assertEquals(Link.OFFLINE, r.state.value.link)
    }

    @Test fun noProbeAfterARecentAnswer() = runTest {
        val server = Server()
        val r = machine(server)
        r.succeeded()
        r.refresh(5 * 60_000L)
        assertTrue("a fresh answer stands in for the probe", server.probes.isEmpty())
        // A network change followed by an answer within the settle: no probe.
        r.networkChanged(); at(testScheduler.currentTime + 500); r.succeeded()
        at(testScheduler.currentTime + 60_000)
        assertTrue(server.probes.isEmpty())
        // An answer older than the cache clock's 5 minutes does not count.
        at(testScheduler.currentTime + 10 * 60_000)
        r.refresh(5 * 60_000L)
        assertEquals("stale answer: one probe", 1, server.probes.size)
        // The sign-out dialog asks for a fresh verdict regardless.
        r.refresh(0)
        assertEquals(2, server.probes.size)
    }

    @Test fun refreshWaitsForTheConfirmedVerdict() = runTest {
        val server = Server().apply { up = false }
        val r = machine(server)
        val started = testScheduler.currentTime
        r.refresh(5 * 60_000L)
        assertEquals("probe, then the confirmation 10 s later", 2, server.probes.size)
        assertEquals(Link.UNREACHABLE, r.state.value.link)
        assertTrue(r.state.value.known)
        assertTrue(testScheduler.currentTime - started <= Reachability.REFRESH_WAIT_MS)
        // Offline already: the cache clock's check adds no probe (the back schedule runs anyway).
        val n = server.probes.size
        r.refresh(5 * 60_000L)
        assertEquals(n, server.probes.size)
    }

    /** Against a dead server (a black hole) refresh returns within 15 s; the offer is still decided later. */
    @Test fun refreshAgainstADeadServerReturnsWithinFifteenSeconds() = runTest {
        val server = Server().apply { up = false; hangMs = 60_000 }
        val r = machine(server)
        val started = testScheduler.currentTime
        r.refresh(0)
        assertEquals(15_000L, Reachability.REFRESH_WAIT_MS)
        assertEquals("bounded by the 15 s wait, not the confirming ping", 15_000L, testScheduler.currentTime - started)
        assertEquals("the first ping timed out (10 s); the run is not confirmed yet", Link.ONLINE, r.state.value.link)
        at(started + 30_000)
        assertEquals("the confirming ping (20 s) times out at 30 s", Link.UNREACHABLE, r.state.value.link)
    }

    @Test fun screenOffAndPlayingTheProbesFollowTheSharedBackoff() = runTest {
        val server = Server().apply { up = false }
        val quiet = MutableStateFlow(true)
        val r = machine(server, Network.NONE, quiet)
        val gate = ServerGate(r, { testScheduler.currentTime })
        r.gate = gate
        at(10_000)
        r.network(Network.USABLE)
        at(10_000 + 30 * 60_000)
        val gaps = server.probes.zipWithNext { a, b -> b - a }
        assertEquals(11_000L, server.probes.first())
        assertEquals("30 s doubling to 10 min instead of the 60 s cap",
            listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 600_000L), gaps.take(6))
    }

    /** The screen coming on cuts a backoff wait short; the §4 schedule follows, with no gate reset by hand. */
    @Test fun theScreenComingOnEndsTheBackoffWaitAndTheVisibleScheduleFollows() = runTest {
        val server = Server().apply { up = false }
        val quiet = MutableStateFlow(true)
        val r = machine(server, Network.NONE, quiet)
        val gate = ServerGate(r, { testScheduler.currentTime })
        r.gate = gate
        at(10_000)
        r.network(Network.USABLE)
        at(10_000 + 20 * 60_000)
        val last = server.probes.last()
        // Mid-wait in the backoff step (the next probe would be 10 min after the last): the screen comes on.
        val on = testScheduler.currentTime + 60_000
        assertTrue(on < last + 600_000)
        at(on)
        quiet.value = false; runCurrent()
        assertEquals("a probe at once, not at the end of the backoff", on, server.probes.last())
        at(on + 200_000)
        val later = server.probes.filter { it >= on }.zipWithNext { a, b -> b - a }
        assertEquals("then the visible (§4) schedule", listOf(5_000L, 10_000L, 20_000L, 40_000L), later.take(4))
    }

    /** Idle (not visible, not playing) on a usable network with the server unreachable: no timer, no probe. */
    @Test fun anIdleHourOnAUsableNetworkWithAnUnreachableServerSendsNoProbe() = runTest {
        val server = Server().apply { up = false }
        val attended = MutableStateFlow(true)
        val r = machine(server, attended = attended)
        r.failed(); at(10_000)
        assertEquals(Link.UNREACHABLE, r.state.value.link)
        at(20_000)
        attended.value = false; runCurrent()
        val n = server.probes.size
        val idleFrom = testScheduler.currentTime
        at(idleFrom + 3_600_000)
        assertEquals("idle hour: 0 probes", n, server.probes.size)
        assertEquals(Link.UNREACHABLE, r.state.value.link)
        // Coming back to the front re-evaluates at once, then the §4 schedule from its start.
        val back = testScheduler.currentTime
        attended.value = true; runCurrent()
        assertEquals(back, server.probes.last())
        at(back + 5_000)
        assertEquals(back + 5_000, server.probes.last())
        // Idle again after a request failure (download job, scrobble): still no timer.
        attended.value = false; runCurrent()
        val m = server.probes.size
        r.failed()
        at(testScheduler.currentTime + 3_600_000)
        assertEquals(m, server.probes.size)
        // A network change while idle is probed once, then nothing again.
        r.networkChanged(); at(testScheduler.currentTime + 3_600_000)
        assertEquals(m + 1, server.probes.size)
        // The server answering a request while idle still brings the state back (the +2 s probe it schedules).
        server.up = true
        r.succeeded(); at(testScheduler.currentTime + 10_000)
        assertEquals(Link.ONLINE, r.state.value.link)
    }

    /** Idle from an automatic offline: the network returning is probed once; the timer waits for the app. */
    @Test fun idleOfflineTheNetworkReturningIsProbedOnceOnly() = runTest {
        val server = Server().apply { up = false }
        val r = machine(server, Network.NONE, attended = MutableStateFlow(false))
        at(2_000)
        r.network(Network.USABLE)
        at(3_600_000)
        assertEquals(listOf(3_000L), server.probes)
        assertEquals(Link.OFFLINE, r.state.value.link)
    }

    /** A request failing during the offer's 5 s hold must not leave the state with no probe scheduled. */
    @Test fun aFailureDuringTheOfferHoldKeepsTheWayBackGoing() = runTest {
        val server = Server().apply { up = false }
        val r = machine(server)
        r.failed(); at(10_000)
        assertEquals(Link.UNREACHABLE, r.state.value.link)
        server.up = true
        at(10_100); r.succeeded()          // the streak's +2 s probe at 12 100 succeeds: inside the hold (ends 15 000)
        at(13_000); r.failed()             // any request fails: the streak breaks
        server.up = true
        at(15_000)
        assertEquals("held, and the streak broke", Link.UNREACHABLE, r.state.value.link)
        at(37_000)
        assertEquals("a probe was still scheduled: back online", Link.ONLINE, r.state.value.link)
        assertTrue(server.probes.any { it > 13_000 })

        // Choosing offline during a hold: the chosen state keeps probing too.
        val other = Server().apply { up = false }
        val c = machine(other)
        val t = testScheduler.currentTime
        c.failed(); at(t + 10_000)
        other.up = true
        c.succeeded(); at(t + 12_500)        // second success (probe at +2 s) inside the hold
        c.chooseOffline()
        assertTrue(c.state.value.chosen)
        at(t + 60_000)
        assertEquals(Link.ONLINE, c.state.value.link)
    }

    /** Another account or server: the old answers do not count as fresh evidence. */
    @Test fun resetForgetsTheOldSessionsAnswers() = runTest {
        val server = Server()
        val r = machine(server)
        r.succeeded()
        r.refresh(5 * 60_000L)
        assertTrue(server.probes.isEmpty())
        r.reset()
        assertFalse(r.state.value.known)
        r.refresh(5 * 60_000L)
        assertEquals("the new session is asked", 1, server.probes.size)
        // A reset offline lands in the state the network says, at once.
        r.network(Network.NONE); at(testScheduler.currentTime + 2_000)
        r.reset()
        assertEquals(Link.OFFLINE, r.state.value.link)
    }

    /** A captive portal gives the offer whatever the state before (online or automatic offline). */
    @Test fun aCaptivePortalIsTheOfferAlsoAfterOffline() = runTest {
        val server = Server()
        val r = machine(server, Network.NONE)
        at(2_000)
        assertEquals(Link.OFFLINE, r.state.value.link)
        r.network(Network.BLOCKED); at(10_000)
        assertEquals(Link.UNREACHABLE, r.state.value.link)
        assertTrue(server.probes.isEmpty())
    }

    @Test fun aFailingRequestWhileOfflineBreaksTheRunOfSuccesses() = runTest {
        val server = Server().apply { up = false }
        val r = machine(server, Network.NONE)
        at(2_000); r.network(Network.USABLE); at(2_500)
        r.succeeded(); at(3_500); r.failed(); at(4_600); r.succeeded()
        assertEquals(Link.OFFLINE, r.state.value.link)
    }

    /** In the app the probe's own ping also reports through the client hook: the schedule must not skip a step. */
    @Test fun aProbeWhoseFailureIsAlsoReportedByTheClientKeepsTheSchedule() = runTest {
        lateinit var r: Reachability
        val probes = mutableListOf<Long>()
        r = Reachability(backgroundScope, { testScheduler.currentTime }, {
            probes += testScheduler.currentTime
            r.failed()
            throw SubsonicError.Network(java.io.IOException("down"))
        })
        r.failed(); at(10_000)
        assertEquals(Link.UNREACHABLE, r.state.value.link)
        at(100_000)
        assertEquals(listOf(10_000L, 15_000L, 25_000L, 45_000L, 85_000L), probes)
    }

    /** Signed out the probe has nothing to ask (null): no failure, no Server unreachable, no retry timers. */
    @Test fun aProbeWithNothingToAskKeepsTheStateAndSchedulesNothing() = runTest {
        val probes = mutableListOf<Long>()
        val r = Reachability(backgroundScope, { testScheduler.currentTime }, { probes += testScheduler.currentTime; null })
        r.networkChanged(); at(120_000)
        assertEquals("the network change asked once", listOf(1_000L), probes)
        assertEquals(Link.ONLINE, r.state.value.link)
        r.network(Network.NONE); at(130_000); r.network(Network.USABLE); at(300_000)
        assertEquals(Link.OFFLINE, r.state.value.link)
        assertEquals("offline: the settle probe, nothing asked, no back schedule", listOf(1_000L, 131_000L), probes)
    }

    /** A network change moves the streak's probe earlier; its early success must re-arm the confirmation. */
    @Test fun anEarlySuccessAfterANetworkChangeKeepsTheWayBackGoing() = runTest {
        val server = Server().apply { up = false }
        val r = machine(server, Network.NONE)
        at(2_000); r.network(Network.USABLE); at(3_000)    // the settle probe fails
        assertEquals(Link.OFFLINE, r.state.value.link)
        server.up = true
        at(3_500); r.succeeded()           // first success: the confirmation probe is due at 5 500
        at(3_600); r.networkChanged()      // the settle probe replaces it at 4 600: a success < 2 s after the first
        at(4_600)
        assertEquals(Link.OFFLINE, r.state.value.link)
        at(5_500)
        assertEquals("the confirmation is still due at first + 2 s", Link.ONLINE, r.state.value.link)
    }

    /** After the hold's streak broke, one replacement success does not end the offer. */
    @Test fun oneReplacementSuccessAfterABrokenStreakDoesNotEndTheOfferHold() = runTest {
        val server = Server().apply { up = false }
        val r = machine(server)
        r.failed(); at(10_000)
        assertEquals(Link.UNREACHABLE, r.state.value.link)  // hold until 15 000
        server.up = true
        at(10_100); r.succeeded()          // confirmed by the probe at 12 100, inside the hold
        at(12_100)
        at(13_000); r.failed()             // the streak breaks
        server.up = false
        at(14_000); r.succeeded()          // one replacement success; its confirmation probe (16 000) fails
        at(15_000)
        assertEquals("one success is not a confirmed streak", Link.UNREACHABLE, r.state.value.link)
        at(16_000)
        assertEquals(Link.UNREACHABLE, r.state.value.link)
        server.up = true
        at(70_000)
        assertEquals("the back schedule still finds the server", Link.ONLINE, r.state.value.link)
    }

    // The offer is slower to decide and ignores failures that say nothing about the server.

    /** A VPN rebuild or Wi-Fi/VPN flip starts a fresh run. */
    @Test fun aNetworkChangeClearsEarlierFailures() = runTest {
        val server = Server().apply { up = false }
        val r = machine(server)
        r.failed(); at(5_000)
        r.networkChanged(); at(6_000)
        assertEquals("the settle probe failed: the first failure of a new run", listOf(6_000L), server.probes)
        at(15_999)
        assertEquals("the failure before the change does not confirm", Link.ONLINE, r.state.value.link)
        at(16_000)
        assertEquals(Link.UNREACHABLE, r.state.value.link)
    }

    /** Failing page loads alone never show the offer; a fresh ping 10 s after the first failure must fail. */
    @Test fun onlyAFreshFailingPingConfirmsAndThePingWaitsTenSeconds() = runTest {
        val server = Server().apply { up = false; hangMs = 60_000 }
        val r = machine(server)
        r.failed(); at(10_000)
        assertEquals("the confirming ping goes out", listOf(10_000L), server.probes)
        at(12_000); r.failed(); at(15_000); r.failed()
        assertEquals("requests failing while the ping runs do not confirm", Link.ONLINE, r.state.value.link)
        at(19_999)
        assertEquals("a ping is given 10 s", Link.ONLINE, r.state.value.link)
        at(20_000)
        assertEquals("its timeout confirms", Link.UNREACHABLE, r.state.value.link)
        assertEquals(10_000L, Reachability.PROBE_TIMEOUT_MS)
    }

    /** In the background and not playing (Doze, standby, a frozen process) nothing counts or is probed. */
    @Test fun failuresWhileIdleDoNotCount() = runTest {
        val server = Server().apply { up = false }
        val attended = MutableStateFlow(false)
        val r = machine(server, attended = attended)
        r.failed(); at(30_000); r.failed(); at(60_000)
        assertEquals(Link.ONLINE, r.state.value.link)
        assertTrue("no confirming probe while idle", server.probes.isEmpty())
    }

    /** Going idle ends the run, so a failure from before a sleep can't confirm after the clock jumped. */
    @Test fun goingIdleClearsTheRunAndTheReturnHasAGracePeriod() = runTest {
        val server = Server().apply { up = false }
        val attended = MutableStateFlow(true)
        val r = machine(server, attended = attended)
        r.failed(); at(5_000)
        attended.value = false; runCurrent()
        at(3_600_000)
        assertTrue("the confirmation was dropped with the run", server.probes.isEmpty())
        attended.value = true; runCurrent()                      // grace until 3 605 000
        at(3_601_000); r.failed()                                // right after the return: ignored
        at(3_604_999)
        assertTrue(server.probes.isEmpty())
        at(3_605_000)
        assertEquals("the grace's end asks the server", listOf(3_605_000L), server.probes)
        assertEquals("that failure is the first of a new run", Link.ONLINE, r.state.value.link)
        at(3_614_999)
        assertEquals(Link.ONLINE, r.state.value.link)
        at(3_615_000)
        assertEquals(Link.UNREACHABLE, r.state.value.link)
    }

    /** While Android blocks Coda's network nothing counts; a block ends the run, the unblock gives 5 s grace. */
    @Test fun failuresWhileTheNetworkIsBlockedForCodaDoNotCount() = runTest {
        val server = Server().apply { up = false }
        val r = machine(server)
        r.blocked(true)
        r.failed(); at(30_000); r.failed(); at(60_000)
        assertEquals(Link.ONLINE, r.state.value.link)
        assertTrue(server.probes.isEmpty())
        r.blocked(false); at(61_000); r.failed()                 // in the grace after the unblock
        at(65_000)
        assertEquals(listOf(65_000L), server.probes)
        at(74_999)
        assertEquals(Link.ONLINE, r.state.value.link)
        at(75_000)
        assertEquals(Link.UNREACHABLE, r.state.value.link)

        // A block during an open run clears it.
        val other = Server().apply { up = false }
        val s = machine(other)
        val t = testScheduler.currentTime
        s.failed(); at(t + 5_000)
        s.blocked(true); at(t + 6_000); s.blocked(false)
        at(t + 12_000); s.failed()                               // after the grace: the first failure of a new run
        at(t + 21_999)
        assertEquals(Link.ONLINE, s.state.value.link)
        assertTrue("no probe from the old run", other.probes.none { it < t + 22_000 })
        at(t + 22_000)
        assertEquals(Link.UNREACHABLE, s.state.value.link)
    }

    /** A failure in the grace asks for a probe at its end; going idle or a block before then drops it. */
    @Test fun idleOrABlockDuringTheGraceDropsItsProbe() = runTest {
        val server = Server().apply { up = false }
        val attended = MutableStateFlow(false)
        val r = machine(server, attended = attended)
        attended.value = true; runCurrent()                      // grace until 5 000
        at(1_000); r.failed()                                    // ignored; probe asked for at 5 000
        at(2_000); attended.value = false; runCurrent()
        at(60_000)
        assertTrue("idle: the grace's probe was dropped", server.probes.isEmpty())

        val other = Server().apply { up = false }
        val s = machine(other)
        val t = testScheduler.currentTime
        s.blocked(true); s.blocked(false)                        // grace until t + 5 000
        at(t + 1_000); s.failed()
        at(t + 2_000); s.blocked(true)
        at(t + 60_000)
        assertTrue("blocked: the grace's probe was dropped", other.probes.isEmpty())
        assertEquals(Link.ONLINE, s.state.value.link)
    }

    /** One line per failure, counted or why not; no host. */
    @Test fun eachFailureIsLoggedWithWhetherItCounted() = runTest {
        val server = Server().apply { up = false }
        val attended = MutableStateFlow(false)
        val lines = mutableListOf<String>()
        val r = machine(server, attended = attended, log = { lines += it })
        r.failed()
        r.network(Network.NONE); r.failed(); r.network(Network.USABLE)
        r.blocked(true); attended.value = true; runCurrent(); r.failed(); r.blocked(false)
        r.networkChanged()                                       // its settle probe (1 s) fails in the grace
        r.failed()
        at(testScheduler.currentTime + 5_000); r.failed()
        assertTrue("the settle probe in the grace is logged too", lines.any { it.startsWith("reach failure ignored grace probe") })
        lines.retainAll { " request " in it }
        assertEquals(5, lines.size)
        assertTrue(lines[0], lines[0].startsWith("reach failure ignored idle request"))
        assertTrue(lines[1], lines[1].startsWith("reach failure ignored no-net request"))
        assertTrue(lines[2], lines[2].startsWith("reach failure ignored blocked request"))
        assertTrue(lines[3], lines[3].startsWith("reach failure ignored grace request"))
        assertTrue(lines[4], lines[4].startsWith("reach failure counted request"))
        assertTrue(lines[4], lines[4].contains("attended=true") && lines[4].contains("blocked=false") && lines[4].contains("link=ONLINE"))
    }

    /** Back to the front while playback kept Coda attended all along still gets the 5 s grace. */
    @Test fun aForegroundReturnDuringPlaybackGetsTheGrace() = runTest {
        val server = Server().apply { up = false; hangMs = 60_000 }
        val visible = MutableStateFlow(false)
        val r = machine(server, attended = MutableStateFlow(true), visible = visible)   // playing in the background
        r.failed()                                               // t=0: the run starts, the confirming ping is due at 10 s
        at(10_000)
        assertEquals(listOf(10_000L), server.probes)
        at(17_000); visible.value = true; runCurrent()           // back to the front: grace until 22 000
        at(20_000)                                               // the confirming ping times out in the grace
        assertEquals("no offer right after the return", Link.ONLINE, r.state.value.link)
        at(21_999)
        assertEquals(Link.ONLINE, r.state.value.link)
        at(22_000)
        assertEquals("the grace's end asks the server", listOf(10_000L, 22_000L), server.probes)
        at(31_999)
        assertEquals(Link.ONLINE, r.state.value.link)
        at(32_000)
        assertEquals("that ping's timeout confirms", Link.UNREACHABLE, r.state.value.link)
    }

    /** A ping started before the 10 s mark does not confirm, even when it fails after it. */
    @Test fun anEarlyRefreshPingDoesNotConfirmAndAFreshOneIsSent() = runTest {
        val server = Server().apply { up = false; hangMs = 60_000 }
        val r = machine(server)
        r.failed()                                               // t=0
        at(1_000); backgroundScope.launch { r.refresh(0) }; runCurrent()
        assertEquals("the refresh ping starts at 1 s", listOf(1_000L), server.probes)
        at(11_000)                                               // it times out after the 10 s mark
        assertEquals("a ping started at 1 s does not confirm", Link.ONLINE, r.state.value.link)
        assertEquals("a fresh confirming ping goes out", listOf(1_000L, 11_000L), server.probes)
        at(20_999)
        assertEquals(Link.ONLINE, r.state.value.link)
        at(21_000)
        assertEquals("the fresh ping's timeout confirms", Link.UNREACHABLE, r.state.value.link)
    }

    /** A ping started on the previous network says nothing about the new one. */
    @Test fun aPingFromBeforeANetworkChangeDoesNotCount() = runTest {
        val server = Server().apply { up = false; hangMs = 60_000 }
        val r = machine(server)
        r.failed(); at(10_000)                                   // the confirming ping starts
        at(15_000); r.networkChanged()                           // a fresh run; settle probe due at 16 s
        at(20_000)                                               // the old ping times out: obsolete, ignored
        assertEquals(listOf(10_000L, 20_000L), server.probes)     // the settle probe goes out
        at(30_000)                                               // its timeout is the first failure of the new run
        assertEquals(Link.ONLINE, r.state.value.link)
        at(49_999)
        assertEquals(Link.ONLINE, r.state.value.link)
        at(50_000)
        assertEquals(Link.UNREACHABLE, r.state.value.link)
    }

    /**
     * The probe's wait runs on another clock (the Main handler's uptime) than [nowMs]
     * (elapsedRealtime), so the ping due at first failure + 10 s can start 1 ms early by [nowMs]. It is still the
     * confirming ping: what counts is when it was due.
     */
    @Test fun theConfirmingPingDueAtTenSecondsCountsEvenIfStartedOneMillisecondEarlyByTheOtherClock() = runTest {
        val server = Server().apply { up = false }
        var skew = 0L
        val r = Reachability(backgroundScope, { testScheduler.currentTime + skew }, {
            server.probes += testScheduler.currentTime
            throw SubsonicError.Network(java.io.IOException("down"))
        })
        r.failed(); runCurrent()
        skew = -1L // the wait ends when this clock reads 9 999 ms
        at(10_000)
        assertEquals(listOf(10_000L), server.probes)
        assertEquals("the ping due at +10 s confirms", Link.UNREACHABLE, r.state.value.link)
    }
}
