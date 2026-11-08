package dev.coda.android.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistAlbumWidthsTest {
    @Test fun fittingNamesKeepTheirNaturalWidth() = assertEquals(100 to 150, artistAlbumWidths(100, 150, 400))
    @Test fun shortArtistLongAlbumGivesTheAlbumTheRest() = assertEquals(100 to 300, artistAlbumWidths(100, 900, 400))
    @Test fun longArtistShortAlbumGivesTheArtistWhatTheAlbumDoesNotNeed() = assertEquals(340 to 60, artistAlbumWidths(900, 60, 400))
    @Test fun bothLongCapsTheArtistAtFiftyFivePercent() = assertEquals(220 to 180, artistAlbumWidths(900, 900, 400))
    @Test fun artistJustAboveTheCapKeepsItsNaturalWidthWhenTheAlbumIsLong() = assertEquals(210 to 190, artistAlbumWidths(210, 900, 400))
    @Test fun noRoomNeverGoesNegative() = assertEquals(0 to 0, artistAlbumWidths(50, 50, 0))
}
