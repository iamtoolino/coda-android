package dev.coda.core.library

import dev.coda.core.subsonic.Playlist
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistNameTest {
    private fun parse(name: String) = PlaylistName.parse(name)

    @Test fun prefixAndTheFollowingSpaceAreDropped() {
        assertEquals(PlaylistTitle("Recently Added", true), parse("[Auto] Recently Added"))
    }

    @Test fun caseDoesNotMatter() {
        assertEquals(PlaylistTitle("Mix", true), parse("[auto] Mix"))
        assertEquals(PlaylistTitle("Mix", true), parse("[AUTO] Mix"))
        assertEquals(PlaylistTitle("Mix", true), parse("[aUtO] Mix"))
    }

    @Test fun spacingVariants() {
        assertEquals(PlaylistTitle("Mix", true), parse("[Auto]Mix"))
        assertEquals(PlaylistTitle("Mix", true), parse("[Auto]   Mix"))
        assertEquals(PlaylistTitle("Mix", true), parse("[Auto]\tMix"))
        assertEquals(PlaylistTitle("Mix", true), parse("  [Auto] Mix"))
        assertEquals(PlaylistTitle("Mix", true), parse("[Auto] Mix  "))
    }

    @Test fun onlyTheLeadingMarkerIsRemoved() {
        assertEquals(PlaylistTitle("Mix [Auto]", true), parse("[Auto] Mix [Auto]"))
        assertEquals(PlaylistTitle("[Auto] Mix", true), parse("[Auto] [Auto] Mix"))
    }

    @Test fun namesThatMerelyContainTheMarkerAreNotAuto() {
        for (name in listOf("My [Auto] mix", "Mix [Auto]", "Auto Mix", "(Auto) Mix", "[Autoplay] Mix", "[Aut] Mix", "Auto", "")) {
            assertEquals(name, PlaylistTitle(name, false), parse(name))
        }
    }

    @Test fun aNameThatIsOnlyTheMarkerStaysAsItIs() {
        assertEquals(PlaylistTitle("[Auto]", false), parse("[Auto]"))
        assertEquals(PlaylistTitle("[Auto]  ", false), parse("[Auto]  "))
    }

    @Test fun otherLettersAndUnicodeSurvive() {
        assertEquals(PlaylistTitle("Édith · 80er", true), parse("[Auto] Édith · 80er"))
    }

    @Test fun theServerNameIsNeverTouched() {
        val playlist = Playlist("p", "[Auto] Recently Added")
        assertEquals(true, PlaylistName.parse(playlist).auto)
        assertEquals("[Auto] Recently Added", playlist.name)
    }

    @Test fun setlistMarkerIsParsedLikeAuto() {
        assertEquals(PlaylistTitle("Friday", false, true), parse("[Setlist] Friday"))
        assertEquals(PlaylistTitle("Friday", false, true), parse("[SETLIST]Friday"))
        assertEquals(PlaylistTitle("Friday", false, true), parse("  [setlist]\tFriday  "))
        assertEquals(PlaylistTitle("Auto Mix", false, true), parse("[Setlist] Auto Mix"))
    }

    @Test fun onlyTheFirstLeadingMarkerCounts() {
        assertEquals(PlaylistTitle("[Setlist] Friday", true, false), parse("[Auto] [Setlist] Friday"))
        assertEquals(PlaylistTitle("[Auto] Friday", false, true), parse("[Setlist] [Auto] Friday"))
    }

    @Test fun setlistLookalikesAreNotSetlists() {
        for (name in listOf("My [Setlist] mix", "Friday [Setlist]", "Setlist", "[Set list] Friday", "[Setlists] Friday", "[Setlist]", "[Setlist]  ")) {
            assertEquals(name, PlaylistTitle(name, false), parse(name))
        }
    }
}
