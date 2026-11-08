package dev.coda.core.accent

import org.junit.Assert.*
import org.junit.Test

class OneVoiceTest {
    private fun color(hex: String) = hex.removePrefix("#").chunked(2).map { it.toInt(16) / 255.0 }.let { ArtworkColor(it[0], it[1], it[2]) }
    private fun dye(accent: ArtworkColor, factor: Float) = ArtworkColor(
        8/255.0 + (accent.red - 8/255.0)*factor, 9/255.0 + (accent.green - 9/255.0)*factor, 10/255.0 + (accent.blue - 10/255.0)*factor)
    private fun close(expected: ArtworkColor, actual: ArtworkColor) {
        assertEquals(expected.red, actual.red, .5/255 + 1e-6)
        assertEquals(expected.green, actual.green, .5/255 + 1e-6)
        assertEquals(expected.blue, actual.blue, .5/255 + 1e-6)
    }
    @Test fun rolesAndCourtesyMatchConceptGoldensIncludingHueWrapAndBoundary() {
        javaClass.getResourceAsStream("/one-voice/roles.csv")!!.bufferedReader().useLines { lines ->
            lines.filterNot { it.startsWith("# ") }.forEach { line ->
                val parts = line.split(',')
                val (play, kind, view, page, player) = parts
                val clash = parts[5]
                val selection = AccentSelection(color(play), AccentSelectionKind.valueOf(kind))
                close(color(page), dye(selection.color, OneVoice.pageDye(selection)))
                close(color(player), dye(selection.color, OneVoice.playerDye(selection)))
                assertEquals(line, if (clash.toBoolean()) .05f else .12f,
                    OneVoice.dockTint(selection, AccentSelection(color(view), AccentSelectionKind.chromatic)), 0f)
            }
        }
    }
    @Test fun dimFactorsAndAbsentAlbum() {
        for (kind in AccentSelectionKind.entries) {
            val selection = AccentSelection(ArtworkColor(.5,.4,.3), kind)
            val grey = kind == AccentSelectionKind.neutral || kind == AccentSelectionKind.tintedNeutral
            assertEquals(if (grey) .26f else .24f, OneVoice.pageDye(selection), 0f)
            assertEquals(if (grey) .30f else .28f, OneVoice.playerDye(selection), 0f)
            assertEquals(if (grey) .24f else .30f, OneVoice.homeGlow(selection), 0f)
            assertEquals(.12f, OneVoice.dockTint(selection, null), 0f)
        }
        assertEquals(.12f, OneVoice.HomeArtwork, 0f)
        assertEquals(.14f, OneVoice.PlayerGlow, 0f)
    }
}
