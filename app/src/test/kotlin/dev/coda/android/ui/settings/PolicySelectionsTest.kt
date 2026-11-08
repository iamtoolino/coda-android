package dev.coda.android.ui.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Music storage selections land in the order they were made. Preset A's preview is held until preset B has
 * finished; A's late result must neither replace B's sheet nor apply A after B.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PolicySelectionsTest {
    private class Screen(val selections: PolicySelections) {
        val shown = mutableListOf<String>()
        val applied = mutableListOf<String>()
        /** As MusicStorageScreen.change: [plan] "" deletes nothing (applied at once), anything else opens the sheet. */
        fun change(policy: String, plan: suspend () -> String) = selections.select { latest ->
            previewThenApply(plan, { it.isEmpty() }, { applied += policy; null }, latest) { shown += "$policy:$it" }
        }
    }

    @Test fun aSlowPreviewOfAnEarlierSelectionDoesNotReplaceTheNewerSheet() = runTest {
        val screen = Screen(PolicySelections(this))
        val slowA = CompletableDeferred<String>()
        screen.change("A") { slowA.await() }; runCurrent()
        screen.change("B") { "deletes 2 GB" }; runCurrent()
        assertEquals(listOf("B:deletes 2 GB"), screen.shown)
        slowA.complete("deletes 5 GB"); advanceUntilIdle()
        assertEquals("A's late preview is dropped", listOf("B:deletes 2 GB"), screen.shown)
        assertEquals(emptyList<String>(), screen.applied)
    }

    @Test fun aSlowPreviewOfAnEarlierSelectionIsNotAppliedAfterTheNewerOne() = runTest {
        val screen = Screen(PolicySelections(this))
        val slowA = CompletableDeferred<String>()
        screen.change("A") { slowA.await() }; runCurrent()
        screen.change("B") { "" }; runCurrent()
        assertEquals(listOf("B"), screen.applied)
        slowA.complete(""); advanceUntilIdle()
        assertEquals("the policy saved last is the newest selection", listOf("B"), screen.applied)
        assertEquals(emptyList<String>(), screen.shown)
    }

    @Test fun aSingleSelectionStillShowsOrApplies() = runTest {
        val screen = Screen(PolicySelections(this))
        screen.change("A") { "deletes 1 GB" }; advanceUntilIdle()
        screen.change("B") { "" }; advanceUntilIdle()
        assertEquals(listOf("A:deletes 1 GB"), screen.shown)
        assertEquals(listOf("B"), screen.applied)
    }
}
