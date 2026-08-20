package io.github.iamtoolino.coda.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.iamtoolino.coda.ui.theme.CodaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loginFormRequiresAllCredentialsBeforeConnecting() {
        composeRule.setContent {
            CodaTheme {
                LoginScreen()
            }
        }

        composeRule.onNodeWithText("Connect to Navidrome").assertIsDisplayed()
        composeRule.onNodeWithText("Connect").assertIsNotEnabled()

        val fields = composeRule.onAllNodes(hasSetTextAction())
        fields.assertCountEquals(3)
        fields[0].performTextInput("https://music.example.test")
        fields[1].performTextInput("listener")
        fields[2].performTextInput("not-a-real-password")

        composeRule.onNodeWithText("Connect").assertIsEnabled()
    }
}
