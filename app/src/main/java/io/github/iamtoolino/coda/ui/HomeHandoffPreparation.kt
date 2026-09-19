package io.github.iamtoolino.coda.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import io.github.iamtoolino.coda.data.PlayQueue
import kotlinx.coroutines.flow.first

internal data class HomeHandoffReveal(val entryId: String, val queue: PlayQueue)

/** Measure Home beneath NPS before allowing its native dismissal. Removing this cancels readiness. */
@Composable
internal fun HomeHandoffPreparation(
    onReady: () -> Unit,
    content: @Composable (LazyListState) -> Unit,
) {
    val listState = rememberLazyListState()
    val latestOnReady by rememberUpdatedState(onReady)
    // Measure without showing duplicate content through system-bar padding or exposing its actions.
    Box(
        Modifier.fillMaxSize()
            .drawWithContent { }
            .clearAndSetSemantics { }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            },
    ) { content(listState) }
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 &&
                listState.layoutInfo.visibleItemsInfo.any { it.key == "continue" }
        }.first { it }
        // Synchronize with drawing, not an estimated animation or network delay.
        withFrameNanos { }
        latestOnReady()
    }
}
