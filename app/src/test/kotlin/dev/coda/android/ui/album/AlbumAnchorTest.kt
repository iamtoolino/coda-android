package dev.coda.android.ui.album

import dev.coda.android.ui.offline.ScrollAnchor
import dev.coda.core.library.AlbumSections
import dev.coda.core.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Test

/** The album page's phone/live cross-fade keeps the visible track by key, not by index. */
class AlbumAnchorTest {
    private fun rows(vararg ids: String, discs: Map<String, Int> = emptyMap()) =
        AlbumSections.rows(ids.mapIndexed { i, id -> Song(id, "t", track = i + 1, discNumber = discs[id]) })
    private fun anchor(keys: List<Any>, index: Int, offset: Int) = ScrollAnchor().also { it.keys = keys; it.index = index; it.offset = offset }

    @Test fun goingOfflineWithoutTheResumeRowKeepsTheSameTrack() {
        // Online: header, Resume, a..e (c at index 4). Offline: the resume track isn't here, so no Resume row.
        val offline = rows("a", "b", "c", "d", "e")
        val start = anchor(listOf("c", "d", "e"), 4, 30).start { albumListIndex(offline, resume = false, key = it) }
        assertEquals(3 to 30, start)
    }

    @Test fun comingBackOnlineWithTheResumeRowKeepsTheSameTrack() {
        val online = rows("a", "b", "c", "d", "e")
        val start = anchor(listOf("b", "c"), 2, 5).start { albumListIndex(online, resume = true, key = it) }
        assertEquals(3 to 5, start)
    }

    @Test fun aTrackThePhoneLacksFallsToTheNextOneInView() {
        val offline = rows("a", "b", "d", "e")
        assertEquals(3 to 0, anchor(listOf("c", "d"), 4, 7).start { albumListIndex(offline, resume = false, key = it) })
    }

    @Test fun theHeaderAndDiscHeadingsKeepTheirPlace() {
        val online = rows("a", "b", "c", discs = mapOf("a" to 1, "b" to 2, "c" to 2))
        // Rows: disc-1, a, disc-2, b, c.
        assertEquals(0 to 100, anchor(listOf("header", "resume", "disc-1"), 0, 100).start { albumListIndex(online, resume = false, key = it) })
        assertEquals(4 to 12, anchor(listOf("disc-2", "b"), 3, 12).start { albumListIndex(online, resume = true, key = it) })
        assertEquals(null, albumListIndex(online, resume = false, key = "resume"))
        assertEquals(1, albumListIndex(online, resume = true, key = "resume"))
    }
}
