package dev.coda.android.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

/** The sleeve fade targets exactly the page-dye colour the shared backdrop paints at its edge. */
class PageBackdropTest {
    private val accent = Color(0xFF8B3D71)
    private val page = srgbLerp(Color(0xFF08090A), accent, .40f)
    private val state = AccentState(accent, Color.White, accent, page, Color.Black, Color.Black)
    private fun assertClose(expected: Color, actual: Color) {
        assertEquals(expected.red, actual.red, .002f); assertEquals(expected.green, actual.green, .002f)
        assertEquals(expected.blue, actual.blue, .002f)
    }

    @Test fun dyeStops() {
        assertClose(page, state.dyeAt(0f, 808f))
        assertClose(srgbLerp(Base, page, .55f), state.dyeAt(520f, 808f))
        assertClose(Base, state.dyeAt(808f, 808f))
    }

    @Test fun dyeIsContinuousAndMonotonicDown() {
        val samples = (0..808).map { state.dyeAt(it.toFloat(), 808f).red }
        samples.zipWithNext().forEach { (a, b) -> assertTrue(b <= a + 1e-6f); assertTrue(a - b < .01f) }
    }

    @Test fun sleeveEdgeOnAPhoneIsBetweenPageAndMiddle() {
        // A 360 dp sleeve on an 808 dp page: 69 % of the way to the middle stop, not the flat top colour.
        assertClose(srgbLerp(page, srgbLerp(Base, page, .55f), 360f / 520f), state.dyeAt(360f, 808f))
    }
}
