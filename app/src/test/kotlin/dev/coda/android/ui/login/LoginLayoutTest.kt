package dev.coda.android.ui.login

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class LoginLayoutTest {
    /** The logo keeps 184 dp from 760 dp of screen height up and shrinks below it, to at least 96 dp. */
    @Test fun logoShrinksOnShortScreens() {
        assertEquals(184.dp, logoHeight(914.dp))
        assertEquals(184.dp, logoHeight(760.dp))
        assertEquals(124.dp, logoHeight(700.dp))
        assertEquals(96.dp, logoHeight(640.dp))
        assertEquals(96.dp, logoHeight(560.dp))
    }
}
