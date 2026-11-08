package dev.coda.android.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import kotlinx.coroutines.delay
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.media3.session.MediaController
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import dev.coda.android.artwork.*
import dev.coda.android.playback.retryOrTogglePlayback
import dev.coda.android.ui.theme.AccentState

@Composable
internal fun MiniPlayer(controller: MediaController, revision: Int, fetcher: CoverArtFetcher, accent: AccentState,
    onOpen: () -> Unit = {}, onQueue: () -> Unit = {}, modifier: Modifier = Modifier,
    /** The horizontal swipe's state; its track info and cover follow the finger. Null: no swipe (previews, layout tests). */
    swipe: MiniSwipe? = null) {
    val metadata = controller.mediaMetadata
    val title = metadata.title?.toString().orEmpty()
    val artist = metadata.artist?.toString().orEmpty()
    val playing = controller.isPlaying
    val waiting = controller.sessionExtras.getBoolean("waiting")
    var buffering by remember { mutableStateOf(false) }
    LaunchedEffect(controller, revision) {
        buffering = false
        if (controller.playbackState == Player.STATE_BUFFERING) { delay(300); buffering = true }
    }
    var progress by remember { mutableFloatStateOf(0f) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(controller, revision, lifecycle) {
        // Only while the activity is started (a stopped activity, e.g. with the screen off, keeps its composition).
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                dev.coda.android.diag.BatteryLog.count(dev.coda.android.diag.BatteryIds.UI_PROGRESS_TICK)
                run {
                    progress = if (controller.duration > 0) (controller.currentPosition.toFloat() / controller.duration).coerceIn(0f, 1f) else 0f
                }
                if (!controller.isPlaying) break
                delay(1000)
            }
        }
    }
    // M1/M2: the label names the thing ("Open player" is the double-tap's label, not part of it); the strip's swipes
    // and the long-press are named actions, Play/Pause follows the state.
    Surface(modifier.combinedClickable(onClick = onOpen, onClickLabel = "Open player", onLongClick = onQueue, onLongClickLabel = "Open queue")
        .fillMaxWidth().testTag("miniPlayer")
        .semantics {
            contentDescription = "Now playing: $title by $artist"
            customActions = listOf(CustomAccessibilityAction(if (waiting) "Retry now" else if (playing) "Pause" else "Play") { controller.retryOrTogglePlayback(); true },
                CustomAccessibilityAction("Next track") { controller.seekToNextMediaItem(); true },
                CustomAccessibilityAction("Previous track") { controller.seekToPreviousMediaItem(); true },
                CustomAccessibilityAction("Open queue") { onQueue(); true })
        },
        shape = RoundedCornerShape(16.dp), color = Color.Transparent) {
        Box {
            Row(Modifier.heightIn(min = 56.dp).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                MiniTrackStrip(controller, metadata, swipe, fetcher, accent, waiting, Modifier.weight(1f))
                Box(contentAlignment = Alignment.Center) {
                    IconButton(onClick = { controller.retryOrTogglePlayback() }, modifier = Modifier.size(48.dp).testTag("miniPlayerPlay")) {
                        Crossfade(playing && !waiting, animationSpec = tween(150), label = "Mini play icon") { active ->
                            Icon(if (active) Icons.Default.Pause else Icons.Default.PlayArrow, if (active) "Pause" else "Play", Modifier.size(28.dp))
                        }
                    }
                    if (buffering || waiting) CircularProgressIndicator(Modifier.size(36.dp).testTag("miniPlayerSpinner"), color = accent.text, strokeWidth = 2.dp)
                }
                IconButton(onClick = { controller.seekToNextMediaItem() }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SkipNext, "Next track")
                }
            }
            Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 14.dp).height(2.5.dp)) {
                drawLine(Color.White.copy(alpha = .12f), Offset.Zero, Offset(size.width, 0f), size.height)
                drawLine(accent.text, Offset.Zero, Offset(size.width * progress, 0f), size.height)
                progressDrawn(progress, controller)
            }
        }
    }
}

// The opt-in is for the debug PlaybackDiagnostics, which is @UnstableApi.
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun progressDrawn(progress: Float, controller: MediaController) =
    dev.coda.android.playback.PlaybackDiagnostics.onMiniProgressDrawn(progress, controller) // no per-draw allocation

private fun MediaController.metadataAt(index: Int): MediaMetadata? =
    if (index in 0 until mediaItemCount) getMediaItemAt(index).mediaMetadata else null

/**
 * The cover and the track's text. During a [MiniSwipe] the pinned current track follows the finger and the next and
 * previous tracks wait one strip width away on their own sides (mirrored in RTL), clipped to the strip. At rest only the
 * current track is composed. The offsets are read in graphics layers, so a drag never recomposes.
 */
@Composable
private fun MiniTrackStrip(controller: MediaController, live: MediaMetadata, swipe: MiniSwipe?, fetcher: CoverArtFetcher,
    accent: AccentState, waiting: Boolean, modifier: Modifier) {
    val side = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val held = if (swipe != null && swipe.active) swipe else null
    // P3: the neighbours' covers are fetched while the mini player shows, so a cold one does not fade in mid-slide.
    val neighbourCovers = listOf(controller.nextMediaItemIndex, controller.previousMediaItemIndex)
        .mapNotNull { controller.metadataAt(it)?.extras?.getString("coverArtId") }
    LaunchedEffect(fetcher, neighbourCovers) {
        for (id in neighbourCovers) {
            val art = CoverArt(id, 472)
            if (fetcher.cached(art) == null) try { fetcher.bitmap(art) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
        }
    }
    Box(modifier.clipToBounds().onSizeChanged { swipe?.width = it.width.toFloat() }) {
        if (held != null) {
            controller.metadataAt(held.next)?.let {
                MiniTrack(it, it.artist?.toString().orEmpty(), quiet, fetcher, "miniTitleNext",
                    Modifier.clearAndSetSemantics {}.graphicsLayer { translationX = held.offset + side * size.width })
            }
            controller.metadataAt(held.previous)?.let {
                MiniTrack(it, it.artist?.toString().orEmpty(), quiet, fetcher, "miniTitlePrevious",
                    Modifier.clearAndSetSemantics {}.graphicsLayer { translationX = held.offset - side * size.width })
            }
        }
        // The pinned track; its waiting and error states show only while it is also the one the player plays.
        val shown = held?.let { controller.metadataAt(it.current) } ?: live
        val playing = held == null || held.current == controller.currentMediaItemIndex
        val subtitle = if (!playing) shown.artist?.toString().orEmpty()
            else if (waiting) "Waiting for connection" else if (controller.playerError != null) "Can't play this track" else shown.artist?.toString().orEmpty()
        MiniTrack(shown, subtitle, if (playing && waiting) accent.text else quiet, fetcher, "miniTitle",
            if (swipe != null) Modifier.graphicsLayer { translationX = swipe.offset } else Modifier)
    }
}

/** One track's cover, title and subtitle (the artist, or the playing track's waiting/error state). */
@Composable
private fun MiniTrack(metadata: MediaMetadata, subtitle: String, subtitleColor: Color, fetcher: CoverArtFetcher, titleTag: String, modifier: Modifier) {
    Row(modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        CoverImage(fetcher, metadata.extras?.getString("coverArtId"), Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(metadata.title?.toString().orEmpty(), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag(titleTag))
            Text(subtitle, fontSize = 12.sp, color = subtitleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
