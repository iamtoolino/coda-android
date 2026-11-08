package dev.coda.android.ui.common

import androidx.compose.material3.SnackbarDuration
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Up to 50 characters Short (4 s); longer text or an action that leads somewhere (Details) Long (10 s). Fixed
 * strings only, so a rewording of today's notices (the queue notice waits for the PO) cannot fail the rule.
 */
class NoticeDurationTest {
    @Test fun shortLinesStayShort() {
        assertEquals(SnackbarDuration.Short, NoticeDuration.of("Queue cleared", NoticeAction.Undo))
        assertEquals(SnackbarDuration.Short, NoticeDuration.of("Hidden", NoticeAction.Undo))
        assertEquals(SnackbarDuration.Short, NoticeDuration.of("Couldn't save rating. Try again."))
        assertEquals(SnackbarDuration.Short, NoticeDuration.of("Saved as playlist", NoticeAction.Open))
        assertEquals(SnackbarDuration.Short, NoticeDuration.of(WAITS_FOR_WIFI))
    }

    @Test fun boundaryIsFiftyCharacters() {
        assertEquals(SnackbarDuration.Short, NoticeDuration.of("x".repeat(50)))
        assertEquals(SnackbarDuration.Long, NoticeDuration.of("x".repeat(51)))
        assertEquals(SnackbarDuration.Long, NoticeDuration.of("x".repeat(51), NoticeAction.Undo))
    }

    @Test fun aLongActionKindIsLongWhateverItsLabel() {
        assertEquals(SnackbarDuration.Long, NoticeDuration.of("Removed 2 albums you preferred offline", NoticeAction.Details))
        assertEquals(listOf(NoticeAction.Details), NoticeAction.entries.filter { it.long })
    }

    @Test fun aLongLineWithoutActionIsLong() {
        // Like the queue load notice: the rule, not today's wording.
        assertEquals(SnackbarDuration.Long, NoticeDuration.of("7 tracks aren't on this phone yet. They download on Wi-Fi and stay 3 days."))
    }
}
