package io.github.iamtoolino.coda.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PlaybackNavigationTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var nav: NavHostController
    private var connected by mutableStateOf(true)
    private var queueEmpty by mutableStateOf(false)

    private fun show() {
        rule.setContent {
            nav = rememberNavController()
            val entry by nav.currentBackStackEntryAsState()
            LaunchedEffect(entry?.id, connected, queueEmpty) {
                nav.dismissEmptyPlaybackDestination(connected, queueEmpty)
            }
            NavHost(nav, startDestination = "home") {
                composable("home") { Text("Home") }
                composable("album") { Text("Album") }
                composable("now-playing") { Text("Player") }
                composable("queue") { Text("Queue") }
            }
        }
        rule.waitForIdle()
    }

    @Test fun emptyingQueueReturnsToAlbumAndBackCannotReopenEmptyPlayer() {
        show()
        rule.runOnIdle {
            nav.navigate("album")
            nav.navigate("now-playing")
            nav.navigate("queue")
        }
        rule.waitForIdle()
        rule.runOnIdle { nav.dismissPlaybackScreens() }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals("album", nav.currentDestination?.route)
            assertEquals("home", nav.previousBackStackEntry?.destination?.route)
            nav.popBackStack()
            assertEquals("home", nav.currentDestination?.route)
        }
    }

    @Test fun emptyingQueueOpenedFromHomeReturnsHome() {
        show()
        rule.runOnIdle {
            nav.navigate("now-playing")
            nav.navigate("queue")
        }
        rule.waitForIdle()
        rule.runOnIdle { nav.dismissPlaybackScreens() }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals("home", nav.currentDestination?.route)
            assertEquals(null, nav.previousBackStackEntry)
        }
    }
    @Test fun backSkipsOlderEmptyPlayerWithoutDiscardingAlbumHistory() {
        show()
        rule.runOnIdle {
            nav.navigate("now-playing")
            nav.navigate("album")
            nav.navigate("now-playing")
            nav.navigate("queue")
        }
        rule.waitForIdle()
        rule.runOnIdle {
            nav.dismissPlaybackScreens()
            queueEmpty = true
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals("album", nav.currentDestination?.route)
            nav.popBackStack()
        }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals("home", nav.currentDestination?.route) }
    }

    @Test fun disconnectedControllerDoesNotDismissRestoredPlayerBeforeQueueArrives() {
        connected = false
        queueEmpty = true
        show()
        rule.runOnIdle { nav.navigate("now-playing") }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals("now-playing", nav.currentDestination?.route)
            queueEmpty = false
            connected = true
        }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals("now-playing", nav.currentDestination?.route) }
    }

}
