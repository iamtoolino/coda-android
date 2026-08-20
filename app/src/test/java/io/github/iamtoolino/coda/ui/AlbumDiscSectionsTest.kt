package io.github.iamtoolino.coda.ui

import io.github.iamtoolino.coda.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumDiscSectionsTest {
    @Test
    fun `single disc album does not add a redundant header`() {
        val sections = albumDiscSections(
            listOf(song("one", track = 1), song("two", track = 2, disc = 1)),
        )

        assertEquals(listOf(1), sections.map { it.number })
        assertFalse(shouldShowDiscHeaders(sections))
    }

    @Test
    fun `multidisc album preserves queue indexes and calculates disc durations`() {
        val sections = albumDiscSections(
            listOf(
                song("disc-one-a", track = 1, disc = 1, duration = 120),
                song("disc-one-b", track = 2, disc = 1, duration = 180),
                song("disc-two-a", track = 1, disc = 2, duration = 240),
            ),
        )

        assertTrue(shouldShowDiscHeaders(sections))
        assertEquals(listOf(1, 2), sections.map { it.number })
        assertEquals(listOf(0, 1), sections[0].songs.map { it.index })
        assertEquals(listOf(2), sections[1].songs.map { it.index })
        assertEquals(300, sections[0].duration)
        assertEquals(240, sections[1].duration)
    }

    @Test
    fun `an album identified only as disc two still explains its track numbers`() {
        val sections = albumDiscSections(listOf(song("bonus", track = 1, disc = 2)))

        assertTrue(shouldShowDiscHeaders(sections))
        assertEquals(2, sections.single().number)
    }

    private fun song(
        id: String,
        track: Int,
        disc: Int? = null,
        duration: Int = 0,
    ) = Song(
        id = id,
        title = id,
        track = track,
        discNumber = disc,
        duration = duration,
    )
}
