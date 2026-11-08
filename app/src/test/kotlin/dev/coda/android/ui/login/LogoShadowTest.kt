package dev.coda.android.ui.login

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The logo shadow's box blur spreads a point symmetrically and keeps (almost) all of its weight. */
class LogoShadowTest {
    @Test fun blurSpreadsSymmetricallyAndKeepsTheWeight() {
        val w = 41; val h = 41
        val a = IntArray(w * h).also { it[20 * w + 20] = 255 * 49 } // a bright point
        boxBlur3(a, w, h, 3)
        val total = a.sum()
        assertTrue("weight kept: $total", total in 255 * 49 * 4 / 5..255 * 49) // integer division loses a little
        assertEquals(a[20 * w + 17], a[20 * w + 23])
        assertEquals(a[17 * w + 20], a[23 * w + 20])
        assertTrue("peak at the centre", a[20 * w + 20] >= a.max() - 1)
        assertTrue("it spread", a[20 * w + 25] > 0)
    }
}
