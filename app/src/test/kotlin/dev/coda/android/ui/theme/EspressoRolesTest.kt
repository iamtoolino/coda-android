package dev.coda.android.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.coda.core.accent.*
import org.junit.Assert.*
import org.junit.Test

class EspressoRolesTest {
    /** Replaced Espresso with the no-artwork cyan roles of tokens.json. */
    @Test fun loginUsesTheNoArtworkCyanRoles() {
        val accent = LoginAccent
        assertEquals(ArtworkColor.fallback.composeColor(), accent.accent)
        assertEquals(Color(0xFF77C3CB), accent.text)
        assertEquals(Color(0xFF2B7A82), accent.fill)
        assertEquals(Color(0xFF16363A), accent.page)
        assertEquals(Color(0xFF19272B), accent.surface)
        assertTrue((accent.text.luminance() + .05f) / (accent.page.luminance() + .05f) >= 6.3f)
        assertEquals(Color.White, accent.onFill)
        assertTrue((accent.onFill.luminance() + .05f) / (accent.fill.luminance() + .05f) >= 4.9f)
    }

    @Test fun graphiteGetsNeutralSilverFillRatherThanGreyOnGrey() {
        val accent = AccentState.of(AccentSelection(ArtworkColor(.38, .38, .38), AccentSelectionKind.neutral))
        assertTrue(accent.fill.luminance() > .6f)
        assertEquals(accent.fill.red, accent.fill.green, .001f)
        assertEquals(accent.fill.green, accent.fill.blue, .001f)
        assertEquals(Color.Black.copy(alpha = .82f), accent.onFill)
    }
}
