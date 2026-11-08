package dev.coda.core.subsonic

import dev.coda.core.subsonic.ArtistCredits.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtistCreditsTest {
    private val a = ArtistReference("a", "Alpha")
    private val b = ArtistReference("b", "Beta & Co")
    private val c = ArtistReference("c", "Gamma")

    @Test fun cutsTheDisplayTextAroundEachArtistAndKeepsTheSeparators() {
        assertEquals(listOf(Segment("Alpha", "a"), Segment(" feat. "), Segment("Beta & Co", "b")), ArtistCredits.segments("Alpha feat. Beta & Co", listOf(a, b)))
        assertEquals(listOf(Segment("Alpha", "a"), Segment(", "), Segment("Beta & Co", "b"), Segment(" & "), Segment("Gamma", "c")),
            ArtistCredits.segments("Alpha, Beta & Co & Gamma", listOf(a, b, c)))
    }

    @Test fun textAroundTheNamesIsKept() {
        assertEquals(listOf(Segment("with "), Segment("Alpha", "a"), Segment(" / "), Segment("Gamma", "c"), Segment(" (live)")),
            ArtistCredits.segments("with Alpha / Gamma (live)", listOf(a, c)))
    }

    @Test fun fallsBackToTodaysBehaviourWhenTheTextDoesNotNameThem() {
        assertNull(ArtistCredits.segments("Various Artists", listOf(a, b)))
        assertNull(ArtistCredits.segments("Gamma & Alpha", listOf(a, c)))      // credit order differs from the text
        assertNull(ArtistCredits.segments("Alpha", listOf(a)))                 // one artist: the display name already opens it
        assertNull(ArtistCredits.segments("Alpha & Alpha", listOf(a, a)))      // duplicates count once
        assertNull(ArtistCredits.segments("Alpha & Beta", null))
        assertNull(ArtistCredits.segments("Alpha & Beta", listOf(a, ArtistReference("", "Beta"))))  // an id is missing
    }
}

class ArtistPlayOrderTest {
    @Test fun playAndAddToQueueGoOldestReleaseFirst() {
        fun album(id: String, year: Int?) = Album(id, id, year = year, artistId = "a")
        val page = ArtistPage(Artist("a", "A"), listOf(album("new", 2020), album("undated", null), album("old", 1999), album("mid", 2005)))
        val projection = ArtistProjection.from(page)
        assertEquals(listOf("old", "mid", "new", "undated"), projection.playOrder.map { it.id })
        assertEquals("new", projection.newestRelease?.id)
    }
}
