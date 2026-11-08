package dev.coda.core.accent

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class AccentRolesTest {
    @Test fun chromaticFillPreservesExtractedAccentAndFallbackIsTeal() {
        val color = ArtworkColor(.2, .3, .6)
        assertEquals(color, AccentRoles.from(AccentSelection(color, AccentSelectionKind.chromatic)).fill)
        assertEquals(ArtworkColor.fallback, AccentRoles.from(AccentSelection(color, AccentSelectionKind.brand)).accent)
    }
    @Test fun neutralRolesMatchDesignTokenExample() {
        val roles = AccentRoles.from(AccentSelection(ArtworkColor(94.0/255, 93.0/255, 93.0/255), AccentSelectionKind.neutral))
        val expectedFill = listOf(216, 215, 215)
        listOf(roles.fill.red, roles.fill.green, roles.fill.blue).zip(expectedFill).forEach { (actual, expected) ->
            assertTrue(abs(actual * 255 - expected) < 2)
        }
    }
}
