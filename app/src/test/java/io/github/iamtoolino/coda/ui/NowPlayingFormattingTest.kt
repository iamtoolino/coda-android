package io.github.iamtoolino.coda.ui

import io.github.iamtoolino.coda.player.PlaybackUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NowPlayingFormattingTest {
    @Test
    fun `cellular transcode retains its codec label without source measurements`() {
        assertEquals("OPUS", qualityLabel(PlaybackUiState(codec = "opus")))
    }

    @Test
    fun `original stream label includes available technical measurements`() {
        assertEquals(
            "FLAC • 24/96 kHz • 1411 kb/s",
            qualityLabel(
                PlaybackUiState(
                    codec = "flac",
                    bitDepth = 24,
                    samplingRate = 96_000,
                    bitRate = 1_411,
                ),
            ),
        )
    }

    @Test
    fun `missing codec has no quality label`() {
        assertNull(qualityLabel(PlaybackUiState()))
    }
}
