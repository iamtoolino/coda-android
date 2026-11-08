package dev.coda.android.ui.theme

import androidx.compose.material3.darkColorScheme
import dev.coda.core.accent.AccentSelection
import dev.coda.core.accent.AccentSelectionKind
import dev.coda.core.accent.ArtworkColor
import dev.coda.core.accent.DisplayedAccent
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * Material component defaults follow the resolver's accent. Nothing picks up brand gold or
 * Material's default lavender implicitly. DS-2 removes every explicit brand-gold UI slot.
 */
class ThemeLeakTest {
    private val material = darkColorScheme()
    private val green = AccentState.of(AccentSelection(ArtworkColor(.22, .62, .26), AccentSelectionKind.chromatic))
    private val sources = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }
        .associate { it.relativeTo(File("src/main/kotlin/dev/coda/android/ui")).path to it.readText() }

    @Test fun everyAccentSlotComesFromTheAccentNeverBrandOrMaterialDefaults() {
        val scheme = codaColorScheme(green)
        val slots = mapOf("primary" to scheme.primary, "onPrimary" to scheme.onPrimary, "primaryContainer" to scheme.primaryContainer,
            "secondary" to scheme.secondary, "secondaryContainer" to scheme.secondaryContainer, "tertiary" to scheme.tertiary,
            "tertiaryContainer" to scheme.tertiaryContainer, "surfaceTint" to scheme.surfaceTint, "inversePrimary" to scheme.inversePrimary)
        val defaults = with(material) { setOf(primary, onPrimary, primaryContainer, secondary, secondaryContainer, tertiary,
            tertiaryContainer, surfaceTint, inversePrimary) }
        slots.forEach { (name, color) ->
            assertNotEquals("$name is brand gold", androidx.compose.ui.graphics.Color(0xFFD19433), color)
            assertFalse("$name is a Material default", color in defaults)
        }
        assertEquals(green.text, scheme.primary); assertEquals(green.container, scheme.secondaryContainer)
        assertEquals(green.text, scheme.tertiary); assertEquals(green.surface, scheme.surfaceTint)
    }

    @Test fun neutralSurfacesAreTheSpecGreysNotMaterialLavender() {
        val scheme = codaColorScheme(green)
        with(material) {
            for ((name, pair) in mapOf("surfaceVariant" to (scheme.surfaceVariant to surfaceVariant),
                "surfaceContainer" to (scheme.surfaceContainer to surfaceContainer),
                "surfaceContainerHigh" to (scheme.surfaceContainerHigh to surfaceContainerHigh),
                "surfaceContainerHighest" to (scheme.surfaceContainerHighest to surfaceContainerHighest),
                "outline" to (scheme.outline to outline), "outlineVariant" to (scheme.outlineVariant to outlineVariant)))
                assertNotEquals("$name keeps the Material default", pair.second, pair.first)
        }
    }

    @Test fun withoutASessionTheSchemeIsTheBrandFallbackNotGold() {
        val scheme = codaColorScheme(AccentState.of(DisplayedAccent.brandSelection))
        assertNotEquals(androidx.compose.ui.graphics.Color(0xFFD19433), scheme.primary)
        assertEquals(AccentState.of(DisplayedAccent.brandSelection).text, scheme.primary)
    }

    @Test fun noBrandGoldIsReachableThroughUiCode() {
        assertTrue(sources.size > 20)
        // A colour scheme or MaterialTheme is only built in the theme package (nothing reintroduces Material defaults).
        sources.filterKeys { !it.startsWith("theme/") }.forEach { (path, text) ->
            assertFalse("$path builds a colour scheme", Regex("""\b(dark|light)ColorScheme\(""").containsMatchIn(text))
            assertFalse("$path builds a MaterialTheme", Regex("""\bMaterialTheme\(""").containsMatchIn(text))
        }
        assertTrue("Gold UI literal removed", sources.filterValues { it.contains("0xFFD19433", ignoreCase = true) }.isEmpty())
        assertTrue("BrandTheme removed", sources.filterValues { it.contains("BrandTheme") }.isEmpty())
        // The library shell and the player layer put every screen under the resolver's accent.
        assertTrue(sources.getValue("LibraryShell.kt").contains("AccentTheme(pageAccent)"))
        // Mini player, Now Playing and queue sheet.
        assertEquals(3, Regex("""AccentTheme\(accent\)""").findAll(sources.getValue("player/PlayerLayer.kt")).count())
    }
}
