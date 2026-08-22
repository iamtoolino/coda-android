package io.github.iamtoolino.coda.ui

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.iamtoolino.coda.ui.theme.CodaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SeekBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun seekBarExposesAdjustableProgressAndSeeks() {
        var requestedPosition = -1L
        composeRule.setContent {
            CodaTheme {
                SeekBar(
                    positionMs = 25_000,
                    durationMs = 100_000,
                    enabled = true,
                    onSeek = { requestedPosition = it },
                )
            }
        }

        composeRule.onNodeWithTag("playbackSeekBar")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo(0.25f, 0f..1f, 0),
                ),
            )
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                setProgress(0.5f)
            }

        composeRule.runOnIdle { assertEquals(50_000L, requestedPosition) }
    }

    @Test
    fun unavailableDurationDisablesSeeking() {
        composeRule.setContent {
            CodaTheme {
                SeekBar(positionMs = 0, durationMs = 0, enabled = true, onSeek = {})
            }
        }

        composeRule.onNodeWithTag("playbackSeekBar").assertIsNotEnabled()
    }
}
