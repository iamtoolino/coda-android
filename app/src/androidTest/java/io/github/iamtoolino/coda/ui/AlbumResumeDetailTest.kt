package io.github.iamtoolino.coda.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamtoolino.coda.data.Album
import io.github.iamtoolino.coda.data.AlbumPage
import io.github.iamtoolino.coda.data.AlbumResumeItem
import io.github.iamtoolino.coda.data.Song
import io.github.iamtoolino.coda.ui.theme.CodaTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlbumResumeDetailTest {
    @get:Rule val composeRule = createComposeRule()
    private val page = AlbumPage(
        Album("fixture", "Fixture Album", "Fixture Artist", coverArt = "canonical-cover"),
        (1..4).map {
            Song("s$it", if (it == 2) "A saved track with a very long title that must stay compact" else "Track $it",
                albumId = "fixture", track = if (it < 3) it else it - 2,
                discNumber = if (it < 3) 1 else 2, duration = 180)
        },
    )
    private val target = mutableStateOf("s2")
    private val currentAlbum = mutableStateOf<String?>("elsewhere")
    private var played = emptyList<Song>()
    private var appended = emptyList<Song>()
    private var clickedIndex = -1

    private fun show(fontScale: Float = 1f) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                CodaTheme {
                    Surface(Modifier.width(320.dp)) {
                        val bookmarks = listOf(AlbumResumeItem(page.album, "s1", target.value, ""))
                        LazyColumn {
                            albumTrackItems(
                                page, albumDiscSections(page.songs),
                                albumResumeIndex(page, bookmarks, currentAlbum.value), null,
                                onResumePlay = { played = it },
                                onResumeAppend = { appended = it },
                                onSong = { clickedIndex = it },
                            )
                        }
                    }
                }
            }
        }
    }

    @Test fun inlineActionsUseCanonicalSuffixAndOrdinaryTrackClickKeepsItsIndex() {
        show()
        composeRule.onNodeWithText("RESUME").assertIsDisplayed()
        composeRule.onNodeWithText("DISC 2").assertIsDisplayed()
        val play = composeRule.onNodeWithContentDescription("Play remaining tracks")
        val append = composeRule.onNodeWithContentDescription("Append remaining tracks to queue")
        play.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        append.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        capture("album-resume-detail.png")
        append.performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("s2", "s3", "s4"), appended.map { it.id })
            assertTrue(played.isEmpty())
            assertTrue(appended.all { it.albumArtworkId == "canonical-cover" })
        }
        play.performClick()
        composeRule.runOnIdle { assertEquals(appended, played) }
        composeRule.onNodeWithText("Track 4").performClick()
        composeRule.runOnIdle { assertEquals(3, clickedIndex) }
    }

    @Test fun currentAlbumAndBookmarkChangesUpdateWithoutNavigation() {
        show()
        composeRule.onNodeWithText("RESUME").assertIsDisplayed()
        composeRule.runOnIdle { currentAlbum.value = "fixture" }
        composeRule.onNodeWithText("RESUME").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Play remaining tracks").assertDoesNotExist()
        composeRule.runOnIdle { currentAlbum.value = "other" }
        composeRule.onNodeWithText("RESUME").assertIsDisplayed()
        for (id in listOf("s1", "s3", "s4")) {
            composeRule.runOnIdle { target.value = id }
            composeRule.onNodeWithContentDescription("Play remaining tracks").performClick()
            composeRule.runOnIdle { assertEquals(page.songs.dropWhile { it.id != id }.map { it.id }, played.map { it.id }) }
        }
        composeRule.runOnIdle { target.value = "missing" }
        composeRule.onNodeWithText("RESUME").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Append remaining tracks to queue").assertDoesNotExist()
    }

    @Test fun compactLargeTextKeepsResumeActionsVisible() {
        show(fontScale = 1.5f)
        composeRule.onNodeWithText("RESUME").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Play remaining tracks").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Append remaining tracks to queue").assertIsDisplayed()
        capture("album-resume-detail-large-text.png")
    }

    private fun capture(name: String) {
        val image = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
