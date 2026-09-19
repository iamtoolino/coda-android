package io.github.iamtoolino.coda.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeHandoffPreparationTest {
    @get:Rule val composeRule = createComposeRule()
    private val request = mutableStateOf<Int?>(null)
    private val cardAvailable = mutableStateOf(true)
    private lateinit var nav: NavHostController
    private lateinit var homeList: LazyListState
    private val completed = mutableListOf<Int>()

    private fun show(initialIndex: Int = 12) {
        composeRule.setContent {
            nav = rememberNavController()
            homeList = rememberLazyListState(initialIndex)
            Box(Modifier.fillMaxSize()) {
                request.value?.let { id ->
                    key(id) {
                        HomeHandoffPreparation(onReady = {
                            assertEquals("now-playing", nav.currentDestination?.route)
                            completed += id
                            homeList.requestScrollToItem(0)
                            nav.popBackStack("home", false)
                            request.value = null
                        }) { preparedList ->
                            LazyColumn(state = preparedList) {
                                item { Text("Header", Modifier.height(64.dp)) }
                                if (cardAvailable.value) {
                                    item(key = "continue") { Text("Continue", Modifier.height(96.dp)) }
                                }
                            }
                        }
                    }
                }
                NavHost(nav, startDestination = "home") {
                    composable("home") {
                        LazyColumn(state = homeList) {
                            item { Text("Header", Modifier.height(64.dp)) }
                            item(key = "continue") { Text("Continue", Modifier.height(96.dp)) }
                            items(30) { Text("Album $it", Modifier.height(80.dp)) }
                        }
                    }
                    composable("album") { Text("Album detail") }
                    composable("now-playing") { Text("Now Playing") }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun reveal(throughAlbum: Boolean, initialIndex: Int) {
        show(initialIndex)
        composeRule.runOnIdle {
            assertEquals(initialIndex, homeList.firstVisibleItemIndex)
            if (throughAlbum) nav.navigate("album")
            nav.navigate("now-playing")
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { request.value = 1 }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(listOf(1), completed)
            assertEquals("home", nav.currentDestination?.route)
            assertEquals(0, homeList.firstVisibleItemIndex)
            assertTrue(homeList.layoutInfo.visibleItemsInfo.any { it.key == "continue" })
            assertEquals(null, nav.previousBackStackEntry)
        }
    }

    @Test fun scrolledHomeIsReadyBeforeDirectDismissal() = reveal(false, 12)
    @Test fun albumBetweenHomeAndNpsIsPoppedWithoutRestoringOldScroll() = reveal(true, 12)
    @Test fun alreadyAtTopStillCompletes() = reveal(false, 0)

    @Test fun canceledPreparationDoesNotNavigateOrResetHome() {
        show()
        composeRule.runOnIdle {
            cardAvailable.value = false
            nav.navigate("now-playing")
            request.value = 1
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { request.value = null }
        composeRule.runOnIdle { cardAvailable.value = true }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(completed.isEmpty())
            assertEquals("now-playing", nav.currentDestination?.route)
            assertEquals(12, homeList.firstVisibleItemIndex)
        }
    }

    @Test fun supersedingPreparationOnlyCompletesNewestRequest() {
        show()
        composeRule.runOnIdle {
            cardAvailable.value = false
            nav.navigate("now-playing")
            request.value = 1
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { request.value = 2 }
        composeRule.waitForIdle()
        composeRule.runOnIdle { cardAvailable.value = true }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(listOf(2), completed) }
    }
}
