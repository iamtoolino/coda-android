package io.github.iamtoolino.coda.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import io.github.iamtoolino.coda.ui.theme.CodaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class QueueCacheActionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun buttonIsAStableIdempotentPolicySwitch() {
        val wholeQueue = mutableStateOf(false)
        val hasQueue = mutableStateOf(false)
        var requests = 0
        rule.setContent {
            CodaTheme { Row { QueueCacheAction(wholeQueue.value, hasQueue.value) { requests++ } } }
        }
        val button = rule.onNodeWithContentDescription("Cache this queue")
        button.assertIsNotEnabled().assertIsNotSelected()
        rule.runOnIdle { hasQueue.value = true }
        button.performClick()
        rule.runOnIdle { assertEquals(1, requests); wholeQueue.value = true }
        button.assertIsEnabled().assertIsSelected().performClick()
        rule.runOnIdle { assertEquals(1, requests) }
        // Queue replacement resets the policy; a fresh opt-in is possible.
        rule.runOnIdle { wholeQueue.value = false }
        button.assertIsNotSelected().performClick()
        rule.runOnIdle { assertEquals(2, requests) }
    }
}
