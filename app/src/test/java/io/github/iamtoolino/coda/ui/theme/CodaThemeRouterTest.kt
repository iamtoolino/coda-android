package io.github.iamtoolino.coda.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CodaThemeRouterTest {
    private val playback = CodaThemeRequest.Artwork(
        identity = "album:playing",
        artworkUrl = "https://music.example.test/playing",
    )
    private val viewedAlbum = CodaThemeRequest.Artwork(
        identity = "album:viewed",
        artworkUrl = "https://music.example.test/viewed",
    )

    @Test
    fun `foreground artwork overrides playback globally`() {
        assertEquals(viewedAlbum, resolveThemeRequest(viewedAlbum, playback))
    }

    @Test
    fun `artist and uncovered playlist inherit playback`() {
        assertEquals(
            playback,
            resolveThemeRequest(CodaThemeRequest.InheritPlayback, playback),
        )
    }

    @Test
    fun `missing playback resolves to brand`() {
        assertSame(
            CodaThemeRequest.Brand,
            resolveThemeRequest(CodaThemeRequest.InheritPlayback, playback = null),
        )
    }

    @Test
    fun `pending foreground prevents an intermediate playback theme`() {
        assertSame(
            CodaThemeRequest.Pending,
            resolveThemeRequest(CodaThemeRequest.Pending, playback),
        )
    }

    @Test
    fun `disposing an obsolete destination cannot clear the new owner`() {
        val router = CodaThemeRouter()
        router.present("album-a", viewedAlbum)
        router.present("album-b", CodaThemeRequest.Pending)

        router.clear("album-a")

        assertSame(CodaThemeRequest.Pending, router.foregroundRequest)
    }

    @Test
    fun `new request invalidates an older asynchronous completion`() {
        val gate = ThemeCommitGate()
        val old = gate.begin()
        val current = gate.begin()

        assertFalse(gate.isCurrent(old))
        assertTrue(gate.isCurrent(current))
    }
}
