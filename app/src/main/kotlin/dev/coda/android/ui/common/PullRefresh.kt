package dev.coda.android.ui.common

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** The longest a pull keeps its indicator; a reload that hangs falls back to the screen's own error/stale state. */
internal const val PULL_REFRESH_MAX_MS = 10_000L

/**
 * Pull down to reload from the server, like a browser. [reload] is the screen's existing reload path
 *, and [loading] its loading flag: the indicator spins until that reload has started and finished (at most
 * [PULL_REFRESH_MAX_MS]). Offline, the reload still runs, so the screen shows its usual offline hint, but nothing spins.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PullRefresh(loading: Boolean, online: () -> Boolean, reload: () -> Unit, color: Color,
    modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    var refreshing by remember { mutableStateOf(false) }
    val latestLoading by rememberUpdatedState(loading)
    LaunchedEffect(refreshing) {
        if (!refreshing) return@LaunchedEffect
        withTimeoutOrNull(PULL_REFRESH_MAX_MS) {
            // A reload that finishes before it is observed (a fast snapshot answer) must not leave the indicator spinning.
            withTimeoutOrNull(600) { snapshotFlow { latestLoading }.first { it } }
            snapshotFlow { latestLoading }.first { !it }
        }
        refreshing = false
    }
    val state = rememberPullToRefreshState()
    PullToRefreshBox(refreshing, onRefresh = {
        refreshing = online()
        reload()
    }, modifier.fillMaxSize().testTag("pullRefresh"), state = state, indicator = {
        PullToRefreshDefaults.Indicator(state = state, isRefreshing = refreshing, color = color,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().testTag(if (refreshing) "pullRefreshSpinning" else "pullRefreshIdle"))
    }, content = content)
}
