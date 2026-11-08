package dev.coda.android.ui.player

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

/** Which page Now Playing opens and closes onto in place. */
class AlbumStageTest {
    private fun page(id: String = "album-1", scrolled: Boolean = false, sleeveTop: Float = 0f) = AlbumStage.Page(id).apply {
        sleeve = Rect(0f, sleeveTop, 1080f, sleeveTop + 1080f)
        play = Rect(850f, sleeveTop + 996f, 1018f, sleeveTop + 1164f)
        this.scrolled = { scrolled }
    }
    private fun stage(page: AlbumStage.Page?) = AlbumStage().apply { page?.let(::register) }

    @Test fun thePlayingAlbumsUnscrolledPageIsInPlace() {
        val page = page()
        assertSame(page, stage(page).inPlaceFor("album-1"))
    }

    @Test fun anotherAlbumsPageOrNoPageIsNot() {
        assertNull(stage(page("album-2")).inPlaceFor("album-1"))
        assertNull(stage(null).inPlaceFor("album-1"))
        assertNull("Nothing playing (or no album id)", stage(page()).inPlaceFor(null))
    }

    @Test fun aScrolledPageIsNot() {
        assertNull("Partly off screen", stage(page(scrolled = true)).inPlaceFor("album-1"))
        assertNull("Sleeve above the top", stage(page(sleeveTop = -40f)).inPlaceFor("album-1"))
    }

    @Test fun aPageNotLaidOutYetIsNot() {
        val page = AlbumStage.Page("album-1").apply { scrolled = { false } }
        assertNull(stage(page).inPlaceFor("album-1"))
    }

    @Test fun aLeavingPageOnlyUnregistersItself() {
        val old = page(); val new = page("album-2")
        val stage = stage(old)
        stage.register(new)
        stage.unregister(old)
        assertSame("Navigating A -> B: A's late dispose keeps B", new, stage.page)
        stage.unregister(new)
        assertNull(stage.page)
    }

    @Test fun coveringHidesOnlyTheRegisteredPagesButton() {
        val page = page(); val other = page("album-2")
        val stage = stage(page)
        var covering = false
        stage.covering = { covering }
        assertFalse(stage.covers(page))
        covering = true
        assertTrue(stage.covers(page))
        assertFalse(stage.covers(other))
    }
}
