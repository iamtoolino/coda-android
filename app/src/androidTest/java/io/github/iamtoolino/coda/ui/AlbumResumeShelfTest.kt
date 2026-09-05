package io.github.iamtoolino.coda.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamtoolino.coda.data.Album
import io.github.iamtoolino.coda.ui.theme.CodaTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlbumResumeShelfTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun resumeShelfUsesPlainCardsAndAlbumNavigation() {
        var selected = ""
        composeRule.setContent {
            val ratings = AlbumRatingCoordinator(rememberCoroutineScope()) { _, _ -> }
            CompositionLocalProvider(LocalAlbumRatingCoordinator provides ratings) {
                CodaTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Column {
                            AlbumShelf(
                                title = "Continue Listening",
                                albums = listOf(
                                    Album("one", "A Long Album Title for Testing", "Album Artist", userRating = 5),
                                    Album("two", "Second Album", "Second Artist"),
                                ),
                                showRatingBadge = false,
                                onAlbum = { selected = it.id },
                            )
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithText("Continue Listening").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("See all").assertDoesNotExist()
        composeRule.onNodeWithText("5").assertDoesNotExist()
        val image = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "album-resume-shelf.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        composeRule.onNodeWithText("A Long Album Title for Testing").performClick()
        composeRule.runOnIdle { assertEquals("one", selected) }
    }
}
