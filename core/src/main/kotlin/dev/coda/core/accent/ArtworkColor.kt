package dev.coda.core.accent

/** Normalized sRGB components, independent of any UI toolkit. */
data class ArtworkColor(val red: Double, val green: Double, val blue: Double) {
    companion object {
        val fallback = ArtworkColor(43.0 / 255.0, 122.0 / 255.0, 130.0 / 255.0)
    }
}

data class ArtworkPixel(
    val red: Double,
    val green: Double,
    val blue: Double,
    val alpha: Double = 1.0,
)

enum class AccentSelectionKind { chromatic, tintedNeutral, neutral, brand }

data class AccentSelection(val color: ArtworkColor, val kind: AccentSelectionKind)
