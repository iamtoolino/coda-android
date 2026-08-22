package io.github.iamtoolino.coda.player

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryRootTest {
    @Test
    fun `normal browsing uses the category root`() {
        assertEquals(
            CodaMediaIds.ROOT,
            libraryRootId(
                isRecent = false,
                isSuggested = false,
                isRecommendationBroker = false,
            ),
        )
    }

    @Test
    fun `suggested browsing uses the playable recommendation root`() {
        assertEquals(
            CodaMediaIds.RECOMMENDATIONS,
            libraryRootId(
                isRecent = false,
                isSuggested = true,
                isRecommendationBroker = false,
            ),
        )
    }

    @Test
    fun `recent browsing uses the playable recommendation root`() {
        assertEquals(
            CodaMediaIds.RECOMMENDATIONS,
            libraryRootId(
                isRecent = true,
                isSuggested = false,
                isRecommendationBroker = false,
            ),
        )
    }

    @Test
    fun `legacy recommendation broker uses the playable recommendation root`() {
        assertEquals(
            CodaMediaIds.RECOMMENDATIONS,
            libraryRootId(
                isRecent = false,
                isSuggested = false,
                isRecommendationBroker = true,
            ),
        )
    }
}
