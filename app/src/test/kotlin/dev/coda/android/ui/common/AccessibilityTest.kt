package dev.coda.android.ui.common

import dev.coda.android.ui.library.LibraryState
import dev.coda.android.ui.library.keyCount
import dev.coda.android.ui.library.letterKeys
import dev.coda.android.ui.library.searchAnnouncement
import dev.coda.android.ui.library.searchedText
import dev.coda.android.ui.library.spokenLetter
import dev.coda.core.library.LibrarySection
import dev.coda.core.subsonic.Artist
import dev.coda.core.subsonic.SearchResults
import org.junit.Assert.*
import org.junit.Test

/** The pure parts of the shared accessibility helpers and the letter sheet. */
class AccessibilityTest {
    @Test fun itemLabelDropsBlanksInOrder() {
        assertEquals("Low Tide Engine, Halden Grey, Rated 3 of 5", itemLabel("Low Tide Engine", null, "Halden Grey", " ", "Rated 3 of 5"))
    }

    @Test fun ratingIsSaidAsANumber() {
        assertEquals("Not rated", ratingState(0))
        assertEquals("3 of 5 stars", ratingState(3))
        assertEquals("Rated 4 of 5", albumCellState(4))
        assertNull(albumCellState(0))
    }

    @Test fun ratingActionsStepAndClear() {
        val rated = mutableListOf<Int>()
        val actions = ratingActions(3, enabled = { true }, rate = { rated += it }, clear = true)
        assertEquals(listOf("Increase rating", "Decrease rating", "Clear rating"), actions.map { it.label })
        actions.forEach { assertTrue(it.action()) }
        assertEquals(listOf(4, 2, 0), rated)
        val top = ratingActions(5, enabled = { true }, rate = { rated += it }, clear = false)
        assertEquals(listOf("Increase rating", "Decrease rating"), top.map { it.label })
        assertFalse("no increase past 5", top[0].action())
        assertFalse("busy", ratingActions(2, enabled = { false }, rate = { fail() }, clear = true)[0].action())
        assertEquals("unrated: nothing to clear", 2, ratingActions(0, { true }, {}, clear = true).size)
    }

    @Test fun spokenDurations() {
        assertEquals("46 seconds", spokenDuration(46))
        assertEquals("1 minute", spokenDuration(60))
        assertEquals("3 minutes 46 seconds", spokenDuration(226))
        assertEquals("1 hour 2 minutes 1 second", spokenDuration(3721))
        assertEquals("0 seconds", spokenDuration(0))
        assertNull(spokenDuration(null))
        assertNull(spokenDuration(-1))
    }

    @Test fun letterKeysKeepAll27Positions() {
        val keys = letterKeys(listOf(LibrarySection("#", 6, 0), LibrarySection("A", 58, 6), LibrarySection("M", 41, 64)))
        assertEquals(27, keys.size)
        assertEquals(listOf("#", "A", "B"), keys.take(3).map { it.letter })
        assertEquals("Z", keys.last().letter)
        assertEquals(LetterKeyTriple(0, 6), keys[0].let { LetterKeyTriple(it.section, it.count) })
        assertEquals(LetterKeyTriple(2, 41), keys.first { it.letter == "M" }.let { LetterKeyTriple(it.section, it.count) })
        assertEquals(LetterKeyTriple(-1, 0), keys.first { it.letter == "Q" }.let { LetterKeyTriple(it.section, it.count) })
    }

    private data class LetterKeyTriple(val section: Int, val count: Int)

    @Test fun keyCountsAndSpokenLetters() {
        assertEquals("34", keyCount(34))
        assertEquals("999", keyCount(999))
        assertEquals("1k", keyCount(1000))
        assertEquals("1.2k", keyCount(1234))
        assertEquals("13k", keyCount(12960))
        assertEquals("Numbers and symbols", spokenLetter("#"))
        assertEquals("M", spokenLetter("M"))
    }

    @Test fun searchResultCountIsSaidOnceFinished() {
        assertNull(searchAnnouncement(LibraryState()))
        assertNull(searchAnnouncement(LibraryState(query = "hal", pending = true)))
        assertEquals("No results", searchAnnouncement(LibraryState(query = "hal")))
        assertEquals("1 result", searchAnnouncement(LibraryState(query = "hal", results = SearchResults(artists = listOf(Artist("a", "Halden Grey"))))))
        assertEquals("2 results", searchAnnouncement(LibraryState(query = "hal",
            results = SearchResults(artists = listOf(Artist("a", "Halden Grey"), Artist("b", "Halcyon"))))))
    }

    /** A cursor or composition change in the search field is no new search (no second count said). */
    @Test fun aCursorOnlyChangeDoesNotSearch() {
        val typed = androidx.compose.ui.text.input.TextFieldValue("Halden", androidx.compose.ui.text.TextRange(6))
        assertNull(searchedText(typed, typed.copy(selection = androidx.compose.ui.text.TextRange(2))))
        assertNull(searchedText(typed, typed.copy(composition = androidx.compose.ui.text.TextRange(0, 6))))
        assertNull(searchedText(typed, typed.copy(selection = androidx.compose.ui.text.TextRange(0, 6), composition = null)))
        assertEquals("Halde", searchedText(typed, androidx.compose.ui.text.input.TextFieldValue("Halde")))
        assertEquals("", searchedText(typed, androidx.compose.ui.text.input.TextFieldValue("")))
    }
}
