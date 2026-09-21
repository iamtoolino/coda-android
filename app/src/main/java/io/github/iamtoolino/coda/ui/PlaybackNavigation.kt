package io.github.iamtoolino.coda.ui

import androidx.navigation.NavHostController

/** Remove Queue and its underlying player together, retaining the user's browsing destination. */
internal fun NavHostController.dismissPlaybackScreens() {
    if (popBackStack("now-playing", inclusive = true)) return
    if (popBackStack("queue", inclusive = true)) return
    navigate("home") {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}
