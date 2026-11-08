package dev.coda.android.ui.menu

import org.junit.Assert.*
import org.junit.Test

/** One action list per item type, the PO's decisions of 2026-10-29 and the consistency rules. */
class ItemMenusTest {
    @Test fun albumHasRatingThreeButtonsNoShuffleAndOnePreferOfflineToggle() {
        val spec = ItemMenus.album()
        assertTrue(spec.rating)
        assertEquals(listOf(MenuAction.PLAY, MenuAction.PLAY_NEXT, MenuAction.ADD_TO_QUEUE), spec.buttons)
        assertEquals(listOf(listOf(MenuAction.ADD_TO_PLAYLIST, MenuAction.KEEP_OFFLINE), listOf(MenuAction.GO_TO_ARTIST)), spec.groups)
        assertFalse(MenuAction.SHUFFLE in spec.all)
        assertEquals(1, spec.all.count { it == MenuAction.KEEP_OFFLINE })
    }

    /** Prefer offline (KEEP_OFFLINE) is back in every album and playlist menu, never on tracks or artists. */
    @Test fun preferOfflineIsInAlbumAndPlaylistMenusOnly() {
        assertTrue(KEEP_OFFLINE_IN_MENUS)
        assertTrue(dev.coda.android.ui.settings.KEEP_OFFLINE_IN_SETTINGS)
        val with = listOf(ItemMenus.album(), ItemMenus.album(AlbumMenuContext(onArtistPage = true, compilation = true), resumable = true),
            ItemMenus.playlist(), ItemMenus.playlist(PlaylistMenuContext(editable = false, shuffle = false)))
        for (spec in with) assertEquals(spec.toString(), 1, spec.all.count { it == MenuAction.KEEP_OFFLINE })
        for (spec in listOf(ItemMenus.track(), ItemMenus.track(TrackMenuContext(removable = true)), ItemMenus.artist()))
            assertFalse(spec.toString(), MenuAction.KEEP_OFFLINE in spec.all)
        assertEquals(listOf(listOf(MenuAction.PLAY_NEXT, MenuAction.KEEP_OFFLINE), listOf(MenuAction.RENAME, MenuAction.DELETE)), ItemMenus.playlist().groups)
    }

    @Test fun contextOnlyHidesAnEntryOrAddsOneAtTheEnd() {
        assertFalse(MenuAction.GO_TO_ARTIST in ItemMenus.album(AlbumMenuContext(onArtistPage = true)).all)
        assertFalse(MenuAction.GO_TO_ARTIST in ItemMenus.album(AlbumMenuContext(compilation = true)).all)
        assertFalse(MenuAction.GO_TO_ALBUM in ItemMenus.track(TrackMenuContext(onAlbumPage = true)).all)
        assertEquals(MenuAction.REMOVE_FROM_PLAYLIST, ItemMenus.track(TrackMenuContext(removable = true)).all.last())
        // Every context keeps the shared order of what remains.
        val full = ItemMenus.track(TrackMenuContext(removable = true)).all
        val hidden = ItemMenus.track(TrackMenuContext(onAlbumPage = true, hasArtist = false)).all
        assertEquals(full.filter { it in hidden }, hidden)
    }

    /** A resumable album gets Resume album and Add remaining to queue as the first group, under the buttons. */
    @Test fun resumableAlbumHasResumeGroupFirst() {
        val spec = ItemMenus.album(resumable = true)
        assertEquals(ItemMenus.album().buttons, spec.buttons)
        assertEquals(listOf(MenuAction.RESUME, MenuAction.APPEND_REMAINING), spec.groups.first())
        assertEquals(ItemMenus.album().groups, spec.groups.drop(1))
        assertEquals("Resume album", MenuAction.RESUME.label)
        assertEquals("Add remaining to queue", MenuAction.APPEND_REMAINING.label)
        // The same on an artist page or for a compilation: only Go to artist goes.
        assertEquals(listOf(listOf(MenuAction.RESUME, MenuAction.APPEND_REMAINING), listOf(MenuAction.ADD_TO_PLAYLIST, MenuAction.KEEP_OFFLINE)),
            ItemMenus.album(AlbumMenuContext(onArtistPage = true), resumable = true).groups)
    }

    /** Not resumable: the sheet is exactly today's (no Resume anywhere, no trailing slot). */
    @Test fun albumThatCannotBeResumedIsUnchanged() {
        val spec = ItemMenus.album(resumable = false)
        assertEquals(listOf(listOf(MenuAction.ADD_TO_PLAYLIST, MenuAction.KEEP_OFFLINE), listOf(MenuAction.GO_TO_ARTIST)), spec.groups)
        assertFalse(MenuAction.RESUME in spec.all)
        assertFalse(MenuAction.APPEND_REMAINING in spec.all)
    }

    @Test fun tracksAreNeverRated() {
        assertFalse(ItemMenus.track().rating)
        assertEquals(listOf(MenuAction.PLAY_NEXT, MenuAction.ADD_TO_QUEUE, MenuAction.ADD_TO_PLAYLIST), ItemMenus.track().buttons)
    }

    @Test fun playlistShuffleFollowsThePageRule() {
        assertFalse(MenuAction.SHUFFLE in ItemMenus.playlist(PlaylistMenuContext(shuffle = false)).all)
        assertEquals(listOf(MenuAction.PLAY, MenuAction.ADD_TO_QUEUE), ItemMenus.playlist(PlaylistMenuContext(shuffle = false)).buttons)
    }

    @Test fun playlistKeepsShuffleAndEditsOnlyWhenEditable() {
        val spec = ItemMenus.playlist()
        assertEquals(listOf(MenuAction.PLAY, MenuAction.SHUFFLE, MenuAction.ADD_TO_QUEUE), spec.buttons)
        assertEquals(MenuAction.DELETE, spec.all.last())
        val other = ItemMenus.playlist(PlaylistMenuContext(editable = false)).all
        assertFalse(MenuAction.RENAME in other || MenuAction.DELETE in other)
    }

    @Test fun artistOrderIsPlayNewestBestThenAddToQueue() {
        // Play (all releases, oldest first) replaces Shuffle; "Play best" has no "of".
        assertEquals(listOf(MenuAction.PLAY, MenuAction.NEWEST, MenuAction.BEST_OF), ItemMenus.artist().buttons)
        assertFalse(MenuAction.SHUFFLE in ItemMenus.artist().all)
        assertEquals(listOf("Play", "Play newest", "Play best"), ItemMenus.artist().buttons.map { it.label })
        assertEquals("Play best", MenuAction.BEST_OF.label)
        assertEquals(listOf(listOf(MenuAction.ADD_TO_QUEUE)), ItemMenus.artist().groups)
    }

    @Test fun artistWithOneReleaseKeepsTheLayoutWithNewestAndBestDisabled() {
        // Add to queue stays in its row for every artist; only the enabled state changes.
        assertEquals(1, ItemMenus.artist().all.count { it == MenuAction.ADD_TO_QUEUE })
        for (releases in listOf(0, 1, 2, 7)) for (best in listOf(false, true)) {
            assertEquals(releases > 0, ItemMenus.artistEnabled(MenuAction.PLAY, releases, best))
            assertEquals(releases > 0, ItemMenus.artistEnabled(MenuAction.ADD_TO_QUEUE, releases, best))
            assertEquals(releases > 1, ItemMenus.artistEnabled(MenuAction.NEWEST, releases, best))
            assertEquals(releases > 1 && best, ItemMenus.artistEnabled(MenuAction.BEST_OF, releases, best))
        }
    }

    @Test fun buttonRowsHoldAtMostThreePlayActionsAndNamesAreFixed() {
        val play = setOf(MenuAction.PLAY, MenuAction.BEST_OF, MenuAction.NEWEST, MenuAction.SHUFFLE, MenuAction.PLAY_NEXT,
            MenuAction.ADD_TO_QUEUE, MenuAction.ADD_TO_PLAYLIST)
        for (spec in listOf(ItemMenus.album(), ItemMenus.track(), ItemMenus.playlist(), ItemMenus.artist())) {
            assertTrue(spec.buttons.size <= 3)
            assertTrue(spec.buttons.all { it in play })
            assertEquals(spec.all.size, spec.all.toSet().size)
        }
        assertEquals("Add to playlist…", MenuAction.ADD_TO_PLAYLIST.label)
        assertEquals("Prefer offline", MenuAction.KEEP_OFFLINE.label)
    }

    /** Review r2 P1: [A,X,A,Y,A,Z], the second A (index 2) chosen; two earlier entries are removed elsewhere. */
    @Test fun aRemovalIsRejectedWhenTheDuplicateSongsShiftedBeforeTheCheck() {
        val opened = listOf("A", "X", "A", "Y", "A", "Z")
        val now = listOf("A", "Y", "A", "Z")
        // The round-1 rule (the n-th occurrence of the song sits at the same index) wrongly passes: it would remove the last A.
        val occurrence = opened.take(2).count { it == "A" }
        assertEquals(2, now.indices.filter { now[it] == "A" }.getOrNull(occurrence))
        assertFalse(removalStillValid(opened, now, 2, "A"))
        assertTrue(removalStillValid(opened, opened, 2, "A"))
        assertFalse("a shifted twin", removalStillValid(opened, listOf("X", "A", "A", "Y", "A", "Z"), 2, "A"))
        assertFalse("no snapshot, no removal", removalStillValid(emptyList(), opened, 2, "A"))
        assertFalse("wrong song at the index", removalStillValid(opened, opened, 1, "A"))
    }
}
