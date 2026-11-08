package dev.coda.core.accent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccentResolverTest {
    private val yellow = AccentSelection(ArtworkColor(.85, .75, .15), AccentSelectionKind.chromatic)
    private val green = AccentSelection(ArtworkColor(.22, .62, .26), AccentSelectionKind.chromatic)
    private val red = AccentSelection(ArtworkColor(.8, .2, .1), AccentSelectionKind.chromatic)
    private val fallback = AccentDisplayModel.fallbackSelection
    private val brand = DisplayedAccent.brandSelection
    private val session = Any()

    /** Covers named after their accent; "slow-*" covers take 2 s, "broken" throws. */
    private suspend fun derive(cover: String): AccentSelection {
        if (cover.startsWith("slow-")) delay(2_000)
        return when (cover.removePrefix("slow-")) {
            "yellow" -> yellow; "green" -> green; "red" -> red
            else -> throw java.io.IOException("cover unavailable")
        }
    }

    private fun TestScope.resolver(): Pair<AccentResolver, MutableList<DisplayedAccent>> {
        val resolver = AccentResolver(this)
        val seen = mutableListOf<DisplayedAccent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { resolver.page.collect { seen += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { resolver.playback.collect { seen += it } }
        return resolver to seen
    }

    @Test fun courtesyTracksCommittedAlbumAndClearsWhenLeavingWithoutChangingPlayback() = runTest {
        val (resolver, _) = resolver()
        resolver.attach(session, ::derive)
        resolver.setPlayback("playing", "red")
        runCurrent()
        resolver.showAlbum("other", "slow-green", true)
        runCurrent()
        assertEquals(.12f, resolver.dockTint.value, 0f)
        advanceTimeBy(2000); runCurrent()
        assertEquals(.05f, resolver.dockTint.value, 0f)
        assertEquals(red, resolver.playback.value.selection)
        assertEquals(green, resolver.page.value.selection)
        resolver.showAlbum(null)
        assertEquals(.12f, resolver.dockTint.value, 0f)
        assertEquals(red, resolver.playback.value.selection)
    }

    @Test fun artworkRefreshRetainsCommittedAccentUntilNewDerivationAndDropsBadgeTintWhilePending() = runTest {
        val resolver = AccentResolver(this)
        var selected = yellow
        var gate = CompletableDeferred<Unit>().also { it.complete(Unit) }
        resolver.attach(session) { gate.await(); selected }
        resolver.setPlayback("album", "cover"); advanceUntilIdle()
        assertTrue(resolver.pageTintReady.value)
        selected = green; gate = CompletableDeferred()
        resolver.artworkChanged(setOf("cover"))
        assertEquals(yellow, resolver.page.value.selection)
        assertFalse(resolver.pageTintReady.value)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(green, resolver.page.value.selection)
        assertTrue(resolver.pageTintReady.value)
    }

    @Test fun detachAttachRestoresUnchangedPlaybackAndAlbumWithFreshLoader() = runTest {
        val resolver = AccentResolver(this)
        resolver.attach(session) { throw java.io.IOException() }
        resolver.setPlayback("same-song", "green")
        resolver.showAlbum("same-album", "red", true)
        runCurrent()
        assertEquals(fallback, resolver.page.value.selection)
        resolver.detach(session)
        val replacement = Any()
        resolver.attach(replacement, ::derive)
        // No new setPlayback/showAlbum emission is needed after restore.
        resolver.detach(session) // A late disposal from the previous shell cannot detach the new one.
        advanceUntilIdle()
        assertEquals(red, resolver.page.value.selection)
        assertEquals(green, resolver.playback.value.selection)
    }

    @Test fun retiringActivityCannotClearTheResumedActivityAlbum() = runTest {
        val resolver = AccentResolver(this)
        val old = Any()
        resolver.attach(old, ::derive)
        resolver.showAlbum("old", "red", true, owner = old); runCurrent()
        resolver.attach(session, ::derive)
        resolver.showAlbum("resumed", "green", true, owner = session)
        resolver.showAlbum(null, owner = old)
        advanceUntilIdle()
        assertEquals(green, resolver.page.value.selection)
    }

    @Test fun reattachingSameSessionReplacesStaleLoaderAndRetriesUnchangedIdentity() = runTest {
        val resolver = AccentResolver(this)
        resolver.attach(session) { throw java.io.IOException() }
        resolver.setPlayback("same", "green"); runCurrent()
        resolver.attach(session, ::derive); runCurrent()
        assertEquals(green, resolver.playback.value.selection)
    }

    @Test fun recoveryRestartsRequestStillWaitingOnOldNetworkAndIgnoresLateResult() = runTest {
        val resolver = AccentResolver(this)
        var calls = 0
        val late = CompletableDeferred<Unit>()
        resolver.attach(session) {
            if (++calls == 1) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                late.await(); red
            } else green
        }
        resolver.setPlayback("same", "cover"); runCurrent()
        resolver.retryFailed(); runCurrent()
        assertEquals(green, resolver.playback.value.selection)
        late.complete(Unit); advanceUntilIdle()
        assertEquals(green, resolver.playback.value.selection)
        assertEquals(2, calls)
    }

    @Test fun cancelledLoaderDoesNotBlockForegroundRetry() = runTest {
        val resolver = AccentResolver(this)
        var cancelled = true
        resolver.attach(session) {
            if (cancelled) throw kotlinx.coroutines.CancellationException("loader disposed") else green
        }
        resolver.setPlayback("same", "green"); runCurrent()
        cancelled = false
        resolver.retryFailed(); runCurrent()
        assertEquals(green, resolver.playback.value.selection)
    }

    @Test fun foregroundBypassesLongBackoff() = runTest {
        val resolver = AccentResolver(this)
        var offline = true
        resolver.attach(session) { if (offline) throw java.io.IOException() else green }
        resolver.setPlayback("same", "green"); runCurrent()
        advanceTimeBy(3_001); runCurrent()
        advanceTimeBy(10_001); runCurrent()
        offline = false
        resolver.retryFailed(); runCurrent()
        assertEquals(green, resolver.playback.value.selection)
    }

    @Test fun noPlaybackIdentityGivesTheBrandColour() = runTest {
        val (resolver, _) = resolver()
        resolver.attach(session, ::derive)
        advanceUntilIdle()
        assertEquals(brand, resolver.page.value.selection)
        resolver.setPlayback("song-1", "yellow"); advanceUntilIdle()
        assertEquals(yellow, resolver.page.value.selection)
        resolver.setPlayback(null, null); advanceUntilIdle()
        assertEquals(DisplayedAccent.brand, resolver.page.value)
        assertEquals(DisplayedAccent.brand, resolver.playback.value)
    }

    @Test fun trackChangeWithSlowDerivationNeverEmitsTheFallback() = runTest {
        val (resolver, seen) = resolver()
        resolver.attach(session, ::derive)
        resolver.setPlayback("song-a", "yellow"); advanceUntilIdle()
        seen.clear()
        resolver.setPlayback("song-b", "slow-green")
        advanceTimeBy(1_000)
        assertEquals("A stays while B derives", yellow, resolver.page.value.selection)
        assertEquals(yellow, resolver.playback.value.selection)
        advanceUntilIdle()
        assertEquals(green, resolver.page.value.selection)
        assertEquals(green, resolver.playback.value.selection)
        assertTrue("Only A and B, never brand or fallback: $seen", seen.all { it.selection == yellow || it.selection == green })
    }

    @Test fun pauseKeepsTheAccent() = runTest {
        val (resolver, seen) = resolver()
        resolver.attach(session, ::derive)
        resolver.setPlayback("song-a", "yellow"); advanceUntilIdle()
        seen.clear()
        // Pausing re-reports the same item: the identity does not depend on play/pause.
        repeat(3) { resolver.setPlayback("song-a", "yellow"); advanceUntilIdle() }
        assertEquals(yellow, resolver.page.value.selection)
        assertTrue("No new emission: $seen", seen.isEmpty())
    }

    @Test fun explicitFailureGoesToTheFallback() = runTest {
        val (resolver, _) = resolver()
        resolver.attach(session, ::derive)
        resolver.setPlayback("song-a", "yellow"); runCurrent()
        resolver.setPlayback("song-b", "broken"); runCurrent()
        assertEquals(DisplayedAccent("playing:song-b", fallback), resolver.page.value)
        assertNotEquals("Failure is the fallback teal, not the brand state", brand, resolver.page.value.selection)
        // No artwork at all is an immediate, explicit failure too.
        resolver.setPlayback("song-c", null)
        assertEquals(fallback, resolver.page.value.selection)
    }

    @Test fun failedDerivationIsRetriedWhenTheNetworkIsBack() = runTest {
        var broken = true
        val (resolver, _) = resolver()
        resolver.attach(session) { cover -> if (broken) throw java.io.IOException() else derive(cover) }
        resolver.setPlayback("song-a", "yellow"); runCurrent()
        assertEquals(fallback, resolver.page.value.selection)
        broken = false
        resolver.retryFailed(); runCurrent()
        assertEquals(yellow, resolver.page.value.selection)
    }

        /** A derive that fails [failures] times, then succeeds. */
    private class Flaky(var failures: Int) {
        var calls = 0
        suspend fun derive(cover: String): AccentSelection { calls++; if (failures-- > 0) throw java.io.IOException(); return AccentSelection(ArtworkColor(.22, .62, .26), AccentSelectionKind.chromatic) }
    }

    @Test fun failedDerivationRecoversByItselfWithBackoff() = runTest {
        val flaky = Flaky(3)
        val (resolver, _) = resolver()
        resolver.attach(session, flaky::derive)
        resolver.setPlayback("song-a", "green"); runCurrent()
        assertEquals(fallback, resolver.page.value.selection)
        advanceTimeBy(3_000); runCurrent(); assertEquals(2, flaky.calls)   // 3 s
        advanceTimeBy(10_000); runCurrent(); assertEquals(3, flaky.calls)  // 10 s
        assertEquals(fallback, resolver.page.value.selection)
        advanceTimeBy(30_000); runCurrent(); assertEquals(4, flaky.calls)  // 30 s: succeeds
        assertEquals(green, resolver.page.value.selection)
        advanceTimeBy(300_000); runCurrent(); assertEquals(4, flaky.calls) // no more retries
    }

    @Test fun backoffSettlesAtOneMinute() = runTest {
        val flaky = Flaky(Int.MAX_VALUE)
        val (resolver, _) = resolver()
        resolver.attach(session, flaky::derive)
        resolver.setPlayback("song-a", "green"); runCurrent()
        advanceTimeBy(3_000 + 10_000 + 30_000 + 1); runCurrent(); assertEquals(4, flaky.calls)
        advanceTimeBy(60_000); runCurrent(); assertEquals(5, flaky.calls)
        advanceTimeBy(60_000); runCurrent(); assertEquals(6, flaky.calls)
        resolver.detach(session) // lets runTest finish
    }

    @Test fun recoversAfterAFailedNetworkRetryInBetween() = runTest {
        val flaky = Flaky(2)
        val (resolver, _) = resolver()
        resolver.attach(session, flaky::derive)
        resolver.setPlayback("song-a", "green"); runCurrent()
        resolver.retryFailed(); runCurrent()   // onAvailable fires, the server is still not reachable
        assertEquals(2, flaky.calls)
        assertEquals(fallback, resolver.page.value.selection)
        advanceTimeBy(3_001); runCurrent()     // re-armed backoff succeeds with no further trigger
        assertEquals(green, resolver.page.value.selection)
    }

    @Test fun retryStopsWhenTheIdentityChangesOrTheSessionEnds() = runTest {
        val flaky = Flaky(Int.MAX_VALUE)
        val (resolver, _) = resolver()
        resolver.attach(session, flaky::derive)
        resolver.setPlayback("song-a", "green"); runCurrent()
        resolver.setPlayback(null, null); runCurrent()
        val afterDrop = flaky.calls
        advanceTimeBy(200_000); runCurrent(); assertEquals(afterDrop, flaky.calls)
        resolver.setPlayback("song-b", "green"); runCurrent()
        resolver.detach(session); runCurrent()
        val afterDetach = flaky.calls
        advanceTimeBy(200_000); runCurrent(); assertEquals(afterDetach, flaky.calls)
    }

    @Test fun tileAccentIsNullNotFallbackOnFailure() = runTest {
        val (resolver, _) = resolver()
        resolver.attach(session) { throw java.io.IOException() }
        assertNull(resolver.tileAccent("green"))
        assertEquals(fallback, resolver.tileAccent(null))
    }

@Test fun albumPageCommitsItsAccentOnlyWhenReady() = runTest {
        val (resolver, seen) = resolver()
        resolver.attach(session, ::derive)
        resolver.setPlayback("song-a", "yellow"); advanceUntilIdle()
        seen.clear()
        // Opened from a list without metadata: nothing to derive yet, the playback accent stays.
        resolver.showAlbum("album-red"); advanceUntilIdle()
        assertEquals(yellow, resolver.page.value.selection)
        resolver.showAlbum("album-red", "slow-red", coverKnown = true)
        advanceTimeBy(1_000)
        assertEquals(yellow, resolver.page.value.selection)
        advanceUntilIdle()
        assertEquals(red, resolver.page.value.selection)
        assertEquals("Player surfaces keep the playback accent", yellow, resolver.playback.value.selection)
        assertTrue(seen.all { it.selection == yellow || it.selection == red })
    }

    @Test fun leavingAnAlbumPageCommitsThePlaybackAccentOnlyWhenItIsReady() = runTest {
        val (resolver, seen) = resolver()
        resolver.attach(session, ::derive)
        resolver.showAlbum("album-red", "red", coverKnown = true); advanceUntilIdle()
        assertEquals(red, resolver.page.value.selection)
        // Playback starts elsewhere while the album is shown; its accent is still deriving when the user leaves.
        resolver.setPlayback("song-b", "slow-green")
        seen.clear()
        resolver.showAlbum(null)
        advanceTimeBy(1_000)
        assertEquals("Album accent holds until the playback accent is ready", red, resolver.page.value.selection)
        advanceUntilIdle()
        assertEquals(green, resolver.page.value.selection)
        assertTrue("Never brand or fallback in between: $seen", seen.all { it.selection == red || it.selection == green })
    }

    @Test fun staleDerivationCannotOverwriteANewerSubject() = runTest {
        val gate = CompletableDeferred<Unit>()
        val (resolver, _) = resolver()
        resolver.attach(session) { cover -> if (cover == "yellow") gate.await(); derive(cover) }
        resolver.setPlayback("song-a", "yellow")
        resolver.setPlayback("song-b", "green"); advanceUntilIdle()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(green, resolver.page.value.selection)
    }

    @Test fun withoutASessionTheBrandThemeHoldsAndLateResultsAreDropped() = runTest {
        val (resolver, _) = resolver()
        resolver.setPlayback("song-a", "yellow"); advanceUntilIdle()
        assertEquals(DisplayedAccent.brand, resolver.page.value)
        resolver.attach(session, ::derive)
        resolver.setPlayback("song-b", "slow-green")
        advanceTimeBy(500)
        resolver.detach(session); advanceUntilIdle()
        assertEquals(DisplayedAccent.brand, resolver.page.value)
        assertEquals(DisplayedAccent.brand, resolver.playback.value)
        assertNull(resolver.tileAccent("green"))
    }

    @Test fun displayContextResolvesLikeMacOS() {
        assertEquals(AccentDisplayContext.Brand, AccentDisplayContext.resolve(false, "a", "p"))
        assertEquals(AccentDisplayContext.Album("a"), AccentDisplayContext.resolve(true, "a", "p"))
        assertEquals(AccentDisplayContext.Standard("p"), AccentDisplayContext.resolve(true, null, "p"))
        assertEquals(AccentDisplayContext.Standard(null), AccentDisplayContext.resolve(true, null, null))
    }

    /** ArtworkAndPresentationTests.swift "same album identity can repair stale artwork treatment". */
    @Test fun sameIdentityCanRepairItsAccent() {
        val model = AccentDisplayModel()
        model.remember("album", AccentDerivation.Ready(red))
        model.apply(AccentDisplayContext.Album("album"), null)
        model.remember("album", AccentDerivation.Ready(green))
        model.apply(AccentDisplayContext.Album("album"), null)
        assertEquals(green, model.page.selection)
    }

    /** "pending themes hold the previous valid treatment". */
    @Test fun pendingThemesHoldThePreviousValidTreatment() {
        val model = AccentDisplayModel()
        model.remember("playing", AccentDerivation.Ready(yellow))
        model.apply(AccentDisplayContext.Standard("playing"), "playing")
        model.apply(AccentDisplayContext.Standard("stale"), "stale")
        assertEquals(DisplayedAccent("playing", yellow), model.page)
        model.apply(AccentDisplayContext.Album("red-pending"), "playing")
        assertEquals(yellow, model.page.selection)
        model.remember("red-pending", AccentDerivation.Ready(red))
        model.apply(AccentDisplayContext.Album("red-pending"), "playing")
        assertEquals(red, model.page.selection)
        assertEquals(yellow, model.playback.selection)
    }

    /** "late playback artwork cannot replace the disconnected brand theme". */
    @Test fun latePlaybackArtworkCannotReplaceTheBrandTheme() {
        val model = AccentDisplayModel()
        model.remember("playing", AccentDerivation.Ready(yellow))
        model.apply(AccentDisplayContext.Standard("playing"), "playing")
        model.apply(AccentDisplayContext.Brand, "playing")
        model.remember("playing", AccentDerivation.Ready(red))
        model.apply(AccentDisplayContext.resolve(false, null, "playing"), "playing")
        assertEquals(DisplayedAccent.brand, model.page)
        assertEquals(DisplayedAccent.brand, model.playback)
    }

    // The album colour comes from the memory cache at the tap, and stays known after the page is left.

    @Test fun cachedAlbumCommitsInTheShowAlbumCallWithoutAnyDispatch() = runTest {
        val (resolver, seen) = resolver()
        resolver.attach(session, peek = { if (it == "green") green else null }, derive = ::derive)
        resolver.setPlayback("playing", "red"); runCurrent()
        assertEquals(red, resolver.page.value.selection)
        seen.clear()
        resolver.showAlbum("a", "green", true)
        // No runCurrent: the hit is committed synchronously, so the page's first frame already has its own colour.
        assertEquals(DisplayedAccent("album:a", green), resolver.page.value)
        assertEquals(red, resolver.playback.value.selection)
        assertTrue("the album identity never carries another colour", seen.none { it.identity == "album:a" && it.selection != green })
    }

    @Test fun uncachedAlbumKeepsThePreviousIdentityUntilItsColourIsDerived() = runTest {
        val (resolver, _) = resolver()
        resolver.attach(session, peek = { null }, derive = ::derive)
        resolver.setPlayback("playing", "red"); runCurrent()
        resolver.showAlbum("a", "slow-green", true); runCurrent()
        // Prepare, then commit: the page flow still names the playing identity, which an album page must not show as its own.
        assertEquals("playing:playing", resolver.page.value.identity)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(DisplayedAccent("album:a", green), resolver.page.value)
    }

    @Test fun leftAlbumKeepsItsColourForTheNextVisitEvenBeforeTheCoverIsKnown() = runTest {
        val (resolver, _) = resolver()
        var derived = 0
        resolver.attach(session) { cover -> derived++; derive(cover) }
        resolver.setPlayback("playing", "red"); runCurrent()
        resolver.showAlbum("a", "slow-green", true); advanceTimeBy(2_000); runCurrent()
        resolver.showAlbum(null); runCurrent()
        assertEquals(red, resolver.page.value.selection)
        val before = derived
        // Opened from the queue: no cover id at the tap. The retained colour commits at once.
        resolver.showAlbum("a", null, false)
        assertEquals(DisplayedAccent("album:a", green), resolver.page.value)
        resolver.showAlbum("a", "slow-green", true); runCurrent()
        assertEquals(DisplayedAccent("album:a", green), resolver.page.value)
        assertEquals("the same cover is derived again (stale-colour repair), the page keeps its colour meanwhile", before + 1, derived)
    }

    @Test fun failedAlbumColourIsNotRetained() = runTest {
        val (resolver, _) = resolver()
        resolver.attach(session, ::derive)
        resolver.setPlayback("playing", "red"); runCurrent()
        resolver.showAlbum("a", "broken", true); runCurrent()
        assertEquals(DisplayedAccent("album:a", fallback), resolver.page.value)
        resolver.showAlbum(null); runCurrent()
        resolver.showAlbum("a", null, false)
        assertNotEquals("album:a", resolver.page.value.identity)
    }

    @Test fun refreshedArtworkDropsTheRetainedColour() = runTest {
        val (resolver, _) = resolver()
        resolver.attach(session, ::derive)
        resolver.setPlayback("playing", "red"); runCurrent()
        resolver.showAlbum("a", "green", true); runCurrent()
        resolver.showAlbum(null); runCurrent()
        resolver.artworkChanged(setOf("green")); runCurrent()
        resolver.showAlbum("a", null, false)
        assertNotEquals("album:a", resolver.page.value.identity)
    }

    @Test fun outgoingAlbumLeavingDoesNotClearTheIncomingAlbum() = runTest {
        val (resolver, _) = resolver()
        resolver.attach(session, peek = { mapOf("green" to green, "yellow" to yellow)[it] }, derive = ::derive)
        resolver.setPlayback("playing", "red"); runCurrent()
        resolver.showAlbum("a", "green", true)
        resolver.showAlbum("b", "yellow", true)
        resolver.leaveAlbum("a")
        assertEquals(DisplayedAccent("album:b", yellow), resolver.page.value)
        resolver.leaveAlbum("b")
        assertEquals(red, resolver.page.value.selection)
        assertNotEquals("album:b", resolver.page.value.identity)
    }
    /** A cold start opens in the colour the previous process committed; it stays until the current subject's own colour is ready. */
    @Test fun restoredPlaybackColourShowsFromTheFirstFrameUntilTheNewOneIsReady() = runTest {
        val (resolver, seen) = resolver()
        resolver.restore(PlaybackAccentMemory("playing:album:a", "yellow", yellow))
        assertEquals(DisplayedAccent("playing:album:a", yellow), resolver.page.value)
        assertEquals(DisplayedAccent("playing:album:a", yellow), resolver.playback.value)
        seen.clear()   // the flows' initial values, before the app restored (nothing composes before that)
        resolver.attach(session, ::derive)
        runCurrent()
        assertEquals("the restored subject is confirmed by its own derivation", yellow, resolver.page.value.selection)
        // The shell reports the Continue Listening seed (another album, slow cover): the restored colour stays meanwhile, never brand.
        resolver.setPlayback("album:b", "slow-green")
        runCurrent()
        assertEquals(yellow, resolver.page.value.selection)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(DisplayedAccent("playing:album:b", green), resolver.page.value)
        assertTrue("no brand frame", seen.none { it.selection == brand })
    }

    @Test fun restoreIsIgnoredOnceASessionOrSubjectIsKnown() = runTest {
        val (resolver, _) = resolver()
        resolver.attach(session, ::derive)
        resolver.restore(PlaybackAccentMemory("playing:album:a", "yellow", yellow))
        assertEquals(brand, resolver.page.value.selection)
    }

    @Test fun committedPlaybackColoursAreReportedOncePerChange() = runTest {
        val (resolver, _) = resolver()
        val reported = mutableListOf<PlaybackAccentMemory>()
        resolver.onPlaybackCommitted = { reported += it }
        resolver.attach(session, ::derive)
        resolver.setPlayback("album:a", "yellow"); runCurrent()
        resolver.showAlbum("x", "green", true); runCurrent()   // an album page does not change the playback colour
        resolver.showAlbum(null); runCurrent()
        resolver.setPlayback("album:b", "broken"); runCurrent()   // a failed cover is not remembered
        resolver.setPlayback(null, null); runCurrent()            // nor the brand theme
        assertEquals(listOf(PlaybackAccentMemory("playing:album:a", "yellow", yellow)), reported)
    }

    /** Fix 1: a kept colour belongs to its server + user; another account, or none, gets nothing. */
    @Test fun keptPlaybackColourIsUsedOnlyForItsOwnAccount() {
        val kept = AccountPlaybackAccent("account-a", PlaybackAccentMemory("playing:album:a", "yellow", yellow))
        assertEquals(kept.memory, kept.forAccount("account-a"))
        assertNull("another user or server", kept.forAccount("account-b"))
        assertNull("no stored sign-in", kept.forAccount(null))
    }

    @Test fun aColourOfAnotherAccountIsNeverRestoredIntoTheResolver() = runTest {
        val (resolver, _) = resolver()
        AccountPlaybackAccent("account-a", PlaybackAccentMemory("playing:album:a", "yellow", yellow)).forAccount("account-b")?.let(resolver::restore)
        assertEquals(brand, resolver.page.value.selection)
        assertEquals(brand, resolver.playback.value.selection)
    }

    /** Sign-out ends the playback subject; the next session neither derives nor stores the old one. */
    @Test fun signOutForgetsThePlaybackSubjectSoTheNextSessionNeverDerivesOrStoresIt() = runTest {
        val (resolver, _) = resolver()
        val requested = mutableListOf<String>()
        val stored = mutableListOf<PlaybackAccentMemory>()
        resolver.onPlaybackCommitted = { stored += it }
        val loader: suspend (String) -> AccentSelection = { requested += it; derive(it) }
        resolver.attach(session, loader)
        resolver.setPlayback("album:a", "yellow")
        runCurrent()
        assertEquals(1, stored.size)
        resolver.detach(session); resolver.forgetPlayback()
        requested.clear(); stored.clear()
        resolver.attach(Any(), loader)
        runCurrent()
        assertEquals(emptyList<String>(), requested)
        assertEquals(emptyList<PlaybackAccentMemory>(), stored)
        assertEquals(brand, resolver.playback.value.selection)
    }

    /** Forget while the old shell is still attached; a late setPlayback must not restore the subject, the next session starts clean. */
    @Test fun forgetWhileAttachedSticksAgainstALateSetPlaybackUntilTheNextSession() = runTest {
        val (resolver, _) = resolver()
        val requested = mutableListOf<String>()
        val stored = mutableListOf<PlaybackAccentMemory>()
        resolver.onPlaybackCommitted = { stored += it }
        val loader: suspend (String) -> AccentSelection = { requested += it; derive(it) }
        resolver.attach(session, loader)
        resolver.setPlayback("album:a", "yellow")
        runCurrent()
        resolver.forgetPlayback()
        resolver.setPlayback("album:a", "yellow") // the old shell's late seed / player event
        runCurrent()
        resolver.detach(session)
        requested.clear(); stored.clear()
        resolver.attach(Any(), loader)
        runCurrent()
        assertEquals(emptyList<String>(), requested)
        assertEquals(emptyList<PlaybackAccentMemory>(), stored)
        assertEquals(brand, resolver.playback.value.selection)
        resolver.setPlayback("album:b", "yellow") // the new session's own playback works again
        runCurrent()
        assertEquals(listOf("yellow"), requested)
    }

    /** The forget is tied to the session key (token), not the attached object: a stale screen of the old token cannot clear it, a new token does. */
    @Test fun aStaleScreenOfTheOldTokenCannotClearTheForgetButANewTokenDoes() = runTest {
        val (resolver, _) = resolver()
        val requested = mutableListOf<String>()
        val loader: suspend (String) -> AccentSelection = { requested += it; derive(it) }
        resolver.attach(session, { null }, "token-1", loader)
        resolver.setPlayback("album:a", "yellow")
        runCurrent()
        resolver.detach(session); resolver.forgetPlayback()
        requested.clear()
        // A second / stale composition of the old session attaches another object with the same token, and seeds the old subject.
        resolver.attach(Any(), { null }, "token-1", loader)
        resolver.setPlayback("album:a", "yellow")
        runCurrent()
        assertEquals(emptyList<String>(), requested)
        assertEquals(brand, resolver.playback.value.selection)
        // The next real session (new token) gets colours normally.
        resolver.attach(Any(), { null }, "token-2", loader)
        resolver.setPlayback("album:b", "yellow")
        runCurrent()
        assertEquals(listOf("yellow"), requested)
        assertEquals(yellow, resolver.playback.value.selection)
    }
}
