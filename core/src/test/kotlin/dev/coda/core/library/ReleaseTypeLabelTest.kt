package dev.coda.core.library

import org.junit.Assert.*
import org.junit.Test

class ReleaseTypeLabelTest {
    @Test fun knownTypesGetTheirUsualSpelling() {
        for ((raw, want) in listOf("album" to "Album", "ALBUM" to "Album", "ep" to "EP", "Ep" to "EP", "single" to "Single",
            "compilation" to "Compilation", "live" to "Live", "soundtrack" to "Soundtrack", "djmix" to "DJ mix", "spokenword" to "Spoken word"))
            assertEquals(raw, want, ReleaseTypeLabel.label(raw))
    }
    @Test fun unknownTypeGetsACapitalLetterAndKeepsTheRest() {
        assertEquals("Field recording", ReleaseTypeLabel.label("field recording"))
        assertEquals("MiniAlbum", ReleaseTypeLabel.label("miniAlbum"))
    }
    @Test fun blankIsNoLabel() { assertNull(ReleaseTypeLabel.label("  ")); assertNull(ReleaseTypeLabel.label(null)) }
    @Test fun ofTakesTheFirstUsableTypeOrAlbum() {
        assertEquals("Album", ReleaseTypeLabel.of(null))
        assertEquals("Album", ReleaseTypeLabel.of(emptyList()))
        assertEquals("Album", ReleaseTypeLabel.of(listOf("album", "live")))
        assertEquals("EP", ReleaseTypeLabel.of(listOf("", "ep")))
    }
}
