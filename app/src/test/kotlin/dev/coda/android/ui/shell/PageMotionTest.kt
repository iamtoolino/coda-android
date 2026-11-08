package dev.coda.android.ui.shell

import androidx.compose.ui.graphics.Color
import dev.coda.android.ui.theme.AccentState
import dev.coda.android.ui.theme.NeutralPageAccent
import dev.coda.core.accent.AccentSelection
import dev.coda.core.accent.AccentSelectionKind
import dev.coda.core.accent.ArtworkColor
import org.junit.Assert.*
import org.junit.Test

/**
 * Frame-by-frame at 120 Hz, an album page opened
 * while another album plays. The resolver's page accent names the playing item until the album's colour is committed.
 */
class PageMotionTest {
    private val playing = AccentSelection(ArtworkColor(.18, .35, .68), AccentSelectionKind.chromatic) // blue
    private val album = AccentSelection(ArtworkColor(.61, .30, .07), AccentSelectionKind.chromatic)  // orange
    private val playingRoles = AccentState.of(playing)
    private val albumRoles = AccentState.of(album)

    private data class Shown(val t: Long, val frame: PageMotion.Frame, val roles: AccentState)

    /** Drives one incoming album page like RevealedPage: the resolver commits the album at [commitAt] (null: never). */
    private fun open(commitAt: Long?, scale: Float = 1f, until: Long = 1_200): List<Shown> {
        var readyAt: Long? = null
        var held: AccentSelection? = null
        return (0..until step 8).map { t ->
            val committed = commitAt != null && t >= commitAt
            val (identity, selection) = if (committed) "album:a" to album else "playing:p" to playing
            PageMotion.albumOwn(identity, selection, "a")?.let { held = it; if (readyAt == null) readyAt = t }
            val frame = PageMotion.frame(t, readyAt, scale)
            Shown(t, frame, PageMotion.roles(held?.let { AccentState.of(it) }, frame.colour))
        }
    }

    private fun List<Shown>.visible() = filter { it.frame.visible }

    @Test fun hitAtTheTapShowsOnlyTheAlbumColourFromTheFirstVisibleFrame() {
        val frames = open(commitAt = 0)
        val first = frames.visible().first()
        assertEquals(PageMotion.OUT_MS, first.frame.revealedAt)
        assertTrue(frames.visible().all { it.roles == albumRoles })
        assertTrue(frames.none { it.frame.visible && it.roles.fill == playingRoles.fill })
    }

    @Test fun hitAfterAFewFramesNeverShowsThePlayingColour() {
        val frames = open(commitAt = 25)
        assertEquals(PageMotion.OUT_MS, frames.visible().first().frame.revealedAt)
        frames.visible().forEach { assertEquals("t=${it.t}", albumRoles, it.roles) }
        assertTrue(frames.none { it.frame.visible && (it.roles.fill == playingRoles.fill || it.roles.page == playingRoles.page) })
    }

    @Test fun colourReadyBetween90And150MsDelaysTheFadeInUntilThen() {
        val frames = open(commitAt = 120)
        assertTrue(frames.filter { it.t < 120 }.none { it.frame.visible })
        assertEquals(120L, frames.visible().first().frame.revealedAt)
        frames.visible().forEach { assertEquals(albumRoles, it.roles) }
    }

    @Test fun missFadesInNeutralAtTheCapThenFadesToTheAlbumColour() {
        val frames = open(commitAt = 400)
        assertTrue(frames.filter { it.t < PageMotion.CAP_MS }.none { it.frame.visible })
        val first = frames.visible().first()
        assertEquals(PageMotion.CAP_MS, first.frame.revealedAt)
        // Neutral roles: grey play button with a white icon, no dye.
        assertEquals(Color(0xFF3A3D42), first.roles.fill)
        assertEquals(Color.White, first.roles.onFill)
        assertEquals(Color(0xFF08090A), first.roles.page)
        frames.filter { it.t in PageMotion.CAP_MS until 400 }.forEach { assertEquals(NeutralPageAccent, it.roles) }
        // Then every role moves monotonically to the album colour over 450 ms.
        val fade = frames.filter { it.t >= 400 }
        fade.zipWithNext().forEach { (a, b) -> assertTrue(b.frame.colour >= a.frame.colour) }
        assertTrue(frames.first { it.t >= 400 + PageMotion.COLOUR_MS }.frame.settled)
        assertEquals(albumRoles, frames.last().roles)
        assertTrue(frames.none { it.frame.visible && it.roles.fill == playingRoles.fill })
    }

    @Test fun neverReadyStaysNeutral() {
        val frames = open(commitAt = null)
        assertTrue(frames.visible().all { it.roles == NeutralPageAccent })
        assertFalse(frames.last().frame.settled)
    }

    @Test fun fadeInIs210MsWithScaleFrom096() {
        val frames = open(commitAt = 0)
        val first = frames.visible().first()
        assertTrue(first.frame.scale < .97f)
        val done = frames.first { it.frame.alpha == 1f }
        assertTrue(done.t - (PageMotion.OUT_MS + PageMotion.IN_MS) in 0..8)
        assertEquals(1f, done.frame.scale)
    }

    @Test fun animationScaleZeroShowsAtReadyOrCapWithoutFades() {
        val hit = open(commitAt = 40, scale = 0f)
        val shown = hit.visible().first()
        assertEquals(40L, shown.t)
        assertEquals(1f, shown.frame.alpha); assertEquals(1f, shown.frame.scale)
        val miss = open(commitAt = 400, scale = 0f)
        assertEquals(PageMotion.CAP_MS, miss.visible().first().frame.revealedAt)
        assertEquals(NeutralPageAccent, miss.visible().first().roles)
        assertEquals(albumRoles, miss.first { it.t >= 400 }.roles)
    }

    @Test fun slowerAnimationScaleStretchesTheWindow() {
        assertEquals(180L, PageMotion.frame(400, 10, 2f).revealedAt)
        assertEquals(300L, PageMotion.frame(400, null, 2f).revealedAt)
    }

    @Test fun pageAlreadyOnScreenShowsAtOnceAndFadesALateColourIn() {
        assertEquals(1f, PageMotion.frame(0, null, 1f, entering = false).alpha)
        assertTrue(PageMotion.frame(0, 0, 1f, entering = false).settled)
        assertEquals(0f, PageMotion.frame(100, 100, 1f, entering = false).colour)
        assertEquals(1f, PageMotion.frame(100 + PageMotion.COLOUR_MS, 100, 1f, entering = false).colour)
    }

    @Test fun albumPagesNeverTakeAnotherIdentitysColour() {
        assertNull(PageMotion.albumOwn("playing:p", playing, "a"))
        assertNull(PageMotion.albumOwn("album:b", album, "a"))
        assertNull(PageMotion.albumOwn(null, playing, "a"))
        assertEquals(album, PageMotion.albumOwn("album:a", album, "a"))
        // The playing album's own page may wear the playback colour until its page colour is committed; another album may not.
        assertEquals(playing, PageMotion.albumOwn("playing:album:a", playing, "a", "playing:album:a", playing))
        assertNull(PageMotion.albumOwn("playing:album:p", playing, "a", "playing:album:p", playing))
        assertEquals(album, PageMotion.albumOwn("album:a", album, "a", "playing:album:a", playing))
    }

    // ---- the deadline runs from the tap, whatever the frames do (review MO-1 round 1, items 3 and 4) ----

    /** The ms after the tap at which the page asks for a frame; a tick comes every 8 ms and the colour arrives at [arrivesAt]. */
    private fun requests(readyAt: Long?, arrivesAt: Long? = null, until: Long = 3_000): List<Long> {
        val asked = ArrayList<Long>()
        var ready = readyAt
        var frame = PageMotion.frame(0, ready, 1f)
        for (t in 8..until step 8) {
            if (ready == null && arrivesAt != null && t >= arrivesAt) ready = t // the page's frame loop restarts when it changes
            if (!PageMotion.wantsFrame(frame, ready)) continue
            asked += t
            frame = PageMotion.frame(t, ready, 1f)
        }
        return asked
    }

    @Test fun aFirstFrameThatComesLateStillMeetsTheDeadlineFromTheTap() {
        // The first frame is drawn 400 ms after the tap (a heavy composition): the cap was 150 ms after the tap, not after that frame.
        val late = PageMotion.frame(400, null, 1f)
        assertEquals(PageMotion.CAP_MS, late.revealedAt)
        assertTrue("the fade-in is already under way", late.alpha > 0f)
        assertEquals(NeutralPageAccent, PageMotion.roles(null, late.colour))
        // 160 ms after the tap, with no earlier frame: visible, 10 ms into its fade-in; a deadline counted from this frame would still be hidden.
        val first = PageMotion.frame(160, null, 1f)
        assertEquals(PageMotion.CAP_MS, first.revealedAt)
        assertTrue(first.alpha > 0f)
        // A colour that was ready at the tap is not held back by the late frame either.
        assertEquals(PageMotion.OUT_MS, PageMotion.frame(160, 0, 1f).revealedAt)
    }

    @Test fun aColourThatNeverArrivesRequestsFramesOnlyUntilTheNeutralPageIsFadedIn() {
        val asked = requests(readyAt = null)
        assertTrue(asked.isNotEmpty())
        // Reveal at 150 ms plus the 210 ms fade-in: no frame after that, however long the colour takes.
        assertTrue("last frame at ${asked.last()} ms", asked.last() <= PageMotion.CAP_MS + PageMotion.IN_MS + 16)
        assertFalse(PageMotion.wantsFrame(PageMotion.frame(2_000, null, 1f), null))
    }

    @Test fun aColourThatArrivesLaterRestartsTheFramesAndTheyStopWhenSettled() {
        val asked = requests(readyAt = null, arrivesAt = 2_000)
        assertTrue("no frame while waiting", asked.none { it in (PageMotion.CAP_MS + PageMotion.IN_MS + 16)..1_999L })
        val after = asked.filter { it >= 2_000 }
        assertTrue("frames resume when the colour arrives", after.isNotEmpty())
        assertTrue("and stop once the 450 ms fade has settled (${after.last()})", after.last() <= 2_000 + PageMotion.COLOUR_MS + 16)
    }

    @Test fun aHitSettlesAndAsksForNothingMore() {
        val asked = requests(readyAt = 0)
        assertTrue(asked.last() <= PageMotion.OUT_MS + PageMotion.IN_MS + 16)
        assertFalse(PageMotion.wantsFrame(PageMotion.frame(1_000, 0, 1f), 0))
    }

    @Test fun anInvisiblePageStillWaitsForFramesToReachItsReveal() {
        assertTrue(PageMotion.wantsFrame(PageMotion.frame(0, null, 1f), null))
        assertTrue(PageMotion.wantsFrame(PageMotion.frame(100, null, 1f), null))
        // A page that is already on screen with a missing colour asks for nothing at all.
        assertFalse(PageMotion.wantsFrame(PageMotion.frame(0, null, 1f, entering = false), null))
    }
}
