package dev.coda.android.ui.offline

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import dev.coda.core.connectivity.Reachability.Link
import dev.coda.core.connectivity.Reachability.State
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The shell reads [OfflineViewsState.phone], [OfflineViewsState.lineKind] and [OfflineViewsState.lineShown]; a
 * connection state change that leaves them as they were (the start-up "known" flip) must not invalidate the reader, a change
 * of what they say must. Same answers as before ([showsPhone], [offlineLineKind]).
 */
class OfflineViewsStateTest {
    private val source = mutableStateOf(State())
    private val views = OfflineViewsState(source) {}
    private var changes = 0
    private val observer = SnapshotStateObserver { it() }
    private val reader = Any()

    @Before fun start() {
        observer.start()
        read()
    }

    @After fun stop() { observer.stop() }

    private fun read(): Unit = observer.observeReads(reader, { changes++; read() }) { views.phone; views.lineKind; views.lineShown }

    private fun set(state: State) {
        Snapshot.withMutableSnapshot { source.value = state }
        Snapshot.sendApplyNotifications()
    }

    @Test fun theKnownFlipDoesNotInvalidateTheReader() {
        set(State(Link.ONLINE, known = true))
        assertEquals(0, changes)
        assertFalse(views.phone); assertEquals(OfflineLineKind.NONE, views.lineKind); assertFalse(views.lineShown)
    }

    @Test fun aChangeOfWhatIsShownDoes() {
        set(State(Link.UNREACHABLE, known = true))
        assertTrue(changes > 0)
        val before = changes
        assertEquals(OfflineLineKind.OFFER, views.lineKind); assertTrue(views.lineShown); assertFalse(views.phone)
        set(State(Link.OFFLINE, known = true))
        assertTrue(changes > before)
        assertEquals(OfflineLineKind.OFFLINE, views.lineKind); assertTrue(views.phone)
    }

    /** Back online, "Back online · updating" until the shown view's live answer is in, then no line. */
    @Test fun backOnlineUntilTheViewsLiveAnswerIsIn() {
        set(State(Link.OFFLINE, known = true))
        Snapshot.withMutableSnapshot { views.shown.views = 1; views.shown.updating = 1 } // a view shows the phone's content
        Snapshot.sendApplyNotifications()
        assertEquals(OfflineLineKind.OFFLINE, views.lineKind)
        set(State(Link.ONLINE, known = true))
        assertEquals(OfflineLineKind.BACK_ONLINE, views.lineKind); assertFalse(views.lineShown); assertFalse(views.phone)
        val before = changes
        Snapshot.withMutableSnapshot { views.shown.views = 0; views.shown.updating = 0 } // its live answer is in (the cross-fade)
        Snapshot.sendApplyNotifications()
        assertTrue(changes > before)
        assertEquals(OfflineLineKind.NONE, views.lineKind)
    }

    @Test fun noBackOnlineWhenTheViewsLiveAnswerFailsOnline() {
        set(State(Link.OFFLINE, known = true))
        Snapshot.withMutableSnapshot { views.shown.views = 1; views.shown.updating = 1 }
        Snapshot.sendApplyNotifications()
        set(State(Link.ONLINE, known = true))
        assertEquals(OfflineLineKind.BACK_ONLINE, views.lineKind)
        // The view's live request failed with the server answering: it still shows the phone's content (the actions
        // keep taking the tracks here), but it is no longer updating.
        Snapshot.withMutableSnapshot { views.shown.updating = 0 }
        Snapshot.sendApplyNotifications()
        assertEquals(OfflineLineKind.NONE, views.lineKind)
        assertTrue(phoneActions(views.phone, views.shown))
    }

    @Test fun backOnlineWithNothingFromThePhoneShowsNoLine() {
        set(State(Link.OFFLINE, known = true))
        set(State(Link.ONLINE, known = true))
        assertEquals(OfflineLineKind.NONE, views.lineKind)
    }

    private fun updating(n: Int) {
        Snapshot.withMutableSnapshot { views.shown.views = n; views.shown.updating = n }
        Snapshot.sendApplyNotifications()
    }

    /** Once shown, "Back online · updating" stays [BACK_ONLINE_MIN_MS] (or until the reload is done if longer). */
    @Test fun backOnlineStaysLongEnoughToRead() = runTest {
        val job = launch { views.holdBackOnline() }
        set(State(Link.OFFLINE, known = true)); updating(1); runCurrent()
        set(State(Link.ONLINE, known = true)); runCurrent()
        assertEquals(OfflineLineKind.BACK_ONLINE, views.lineKind)
        advanceTimeBy(200); updating(0); runCurrent() // the live answer is in after 0.2 s
        assertEquals(OfflineLineKind.BACK_ONLINE, views.lineKind)
        advanceTimeBy(BACK_ONLINE_MIN_MS - 200 - 1); runCurrent()
        assertEquals(OfflineLineKind.BACK_ONLINE, views.lineKind)
        advanceTimeBy(2); runCurrent()
        assertEquals(OfflineLineKind.NONE, views.lineKind)
        // A reload longer than the minimum: the line stays until it is done, then leaves at once.
        set(State(Link.OFFLINE, known = true)); updating(1); runCurrent()
        set(State(Link.ONLINE, known = true)); runCurrent()
        advanceTimeBy(BACK_ONLINE_MIN_MS + 1000); runCurrent()
        assertEquals(OfflineLineKind.BACK_ONLINE, views.lineKind)
        updating(0); runCurrent()
        assertEquals(OfflineLineKind.NONE, views.lineKind)
        job.cancel()
    }

    /** Within the minimum, the link going away again hands over to the offline line or the offer at once. */
    @Test fun offlineAgainTakesOverAtOnce() = runTest {
        val job = launch { views.holdBackOnline() }
        set(State(Link.OFFLINE, known = true)); updating(1); runCurrent()
        set(State(Link.ONLINE, known = true)); runCurrent()
        advanceTimeBy(100); updating(0); runCurrent()
        set(State(Link.UNREACHABLE, known = true)); runCurrent()
        assertEquals(OfflineLineKind.OFFER, views.lineKind)
        // Back online with nothing from the phone (unreachable is not offline): the hold is gone, no line.
        set(State(Link.ONLINE, known = true)); runCurrent()
        assertEquals(OfflineLineKind.NONE, views.lineKind)
        updating(1); runCurrent(); updating(0) // a new way back starts its own minimum
        set(State(Link.OFFLINE, known = true)); runCurrent()
        assertEquals(OfflineLineKind.OFFLINE, views.lineKind)
        advanceTimeBy(BACK_ONLINE_MIN_MS + 1); runCurrent()
        assertEquals(OfflineLineKind.OFFLINE, views.lineKind)
        job.cancel()
    }

    @Test fun sameAnswersAsThePlainFunctions() {
        for (link in Link.entries) for (known in listOf(false, true)) {
            val state = State(link, known)
            set(state)
            assertEquals(showsPhone(state), views.phone)
            assertEquals(offlineLineKind(state), views.lineKind)
            assertEquals(!state.reachable, views.lineShown)
        }
    }
}
