package dev.coda.android

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.coda.android.ui.PlaybackSubject
import dev.coda.android.ui.playbackSubject
import dev.coda.android.ui.album.albumDuration
import dev.coda.android.ui.theme.srgbLerp
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PresentationRegressionTest {
    @Test fun playbackIdentityFollowsTheItemsArtworkLikeMacOS() {
        assertEquals(PlaybackSubject("album:a1", "c1"), playbackSubject("song/1", "a1", "c1"))
        assertEquals(PlaybackSubject("cover:c1", "c1"), playbackSubject("song/1", "", "c1"))
        assertEquals(PlaybackSubject("song/1", null), playbackSubject("song/1", null, null))
        assertEquals(PlaybackSubject(null, null), playbackSubject(null, null, null))
    }
    @Test fun subMinuteAlbumsHaveUsefulDuration() {
        for (seconds in 1..59) assertEquals("1 min", albumDuration(seconds))
        assertEquals("1 min", albumDuration(60))
        assertEquals("2 min", albumDuration(120))
    }
    @Test fun surfacesMatchAllApprovedTokenExamples() {
        val tokens = File("../design/tokens.json").readText()
            .substringAfter("\"examples\": {").substringBefore("\"typography\"")
        val examples = Regex("\\{[^{}]*\\}").findAll(tokens).filter { it.value.contains("\"accentContainer\"") }.toList()
        assertEquals(8, examples.size)
        for (example in examples) {
            fun field(name: String) = Regex("\"$name\": \"([^\"]+)\"").find(example.value)!!.groupValues[1]
            fun color(name: String) = Color(0xFF000000 or field(name).removePrefix("#").toLong(16))
            val accent = color("accent")
            val neutral = field("kind") in setOf("neutral", "tintedNeutral")
            for ((role, actual) in listOf(
                "accentContainer" to srgbLerp(Color(0xFF090B0D), accent, .30f),
                "surfaceTint" to srgbLerp(Color(0xFF15171A), accent, .16f),
                "page" to srgbLerp(Color(0xFF08090A), accent, if (neutral) .42f else .40f))) {
                val expected = color(role).toArgb()
                for (shift in listOf(0, 8, 16)) assertEquals("$role in ${example.value}",
                    ((expected shr shift) and 255).toDouble(), ((actual.toArgb() shr shift) and 255).toDouble(), 1.0)
            }
        }
    }
}
