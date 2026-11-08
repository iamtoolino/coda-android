package dev.coda.android.ui.queue

import org.junit.Assert.assertEquals
import org.junit.Test

/** Back, the scrim, TalkBack and the header exit all share one gate; a race between them dismisses once. */
class DismissOnceTest {
    @Test fun runsTheActionOnlyOnce() {
        var count = 0
        val gate = DismissOnce { count++ }
        repeat(5) { gate() }
        assertEquals(1, count)
    }

    @Test fun twoPathsRacingDismissOnce() {
        var count = 0
        val gate = DismissOnce { count++ }
        val backPath = { gate() }
        val headerExitPath = { gate() }
        backPath(); headerExitPath()
        assertEquals(1, count)
    }
}
