package dev.coda.android.ui.player

import androidx.compose.animation.Crossfade
import dev.coda.core.waveform.WaveformIntro
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import dev.coda.android.artwork.*
import dev.coda.android.playback.retryOrTogglePlayback
import dev.coda.android.playback.currentSeekable
import dev.coda.android.playback.resolvedSuccessor
import dev.coda.android.ui.common.GlassIconButton
import dev.coda.android.ui.theme.AccentState
import dev.coda.android.ui.theme.SleeveFade
import kotlinx.coroutines.delay
import kotlin.math.*

/** Whether a programmatic track change (next, previous, auto-advance) slides the sleeve: only when the cover changes. */
internal fun sleeveAnimates(fromCover: String?, toCover: String?): Boolean = fromCover != toCover

internal fun playbackTime(ms: Long): String = (ms.coerceAtLeast(0) / 1000).let { "%d:%02d".format(it / 60, it % 60) }

@Composable
internal fun NowPlaying(
    controller: MediaController, revision: Int, fetcher: CoverArtFetcher, accent: AccentState,
    expansion: () -> Float, miniTop: androidx.compose.ui.unit.Dp, gesture: Modifier, onCollapse: () -> Unit, onQueue: () -> Unit,
    onArtist: (albumId: String, songId: String) -> Unit = { _, _ -> },
    /** Opens one credited artist; [artistsOf] names a track's credited artists (several: each is tappable). */
    onArtistId: (artistId: String) -> Unit = {},
    artistsOf: suspend (albumId: String, songId: String) -> List<dev.coda.core.subsonic.ArtistReference> = { _, _ -> emptyList() },
    onAlbum: (albumId: String) -> Unit = {},
    scroll: androidx.compose.foundation.ScrollState = rememberScrollState(),
    bottomPadding: androidx.compose.ui.unit.Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
    /** Open and close over the playing album's own page (spec M3) instead of the mini player; null: the normal transform. */
    inPlace: InPlace? = null,
) {
    val metadata = controller.mediaMetadata
    val app = LocalContext.current.applicationContext as dev.coda.android.CodaApp
    var position by remember(controller) { mutableLongStateOf(0) }
    LaunchedEffect(controller, revision) {
        do { withFrameMillis { position = controller.currentPosition }; } while (controller.isPlaying)
    }
    val fullyExpanded by remember { derivedStateOf { expansion() == 1f } }
    val detailsShown by remember { derivedStateOf { expansion() > .25f } }
    val duration = controller.duration.coerceAtLeast(0)
    BoxWithConstraints(Modifier.fillMaxSize().then(gesture).semantics { paneTitle = "Now Playing"; isTraversalGroup = true }.testTag("nowPlaying")) {
        // The overlay clips this full window content during the transform.
        val windowHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
        val width = maxWidth
        val sleeve = if (windowHeight < 760.dp) minOf(width, (windowHeight - 448.dp).coerceAtLeast(104.dp)) else width
        val pageHeight = maxHeight
        if (inPlace != null) with(androidx.compose.ui.platform.LocalDensity.current) { inPlace.coverEnd = Rect(0f, 0f, width.toPx(), sleeve.toPx()) }
        // In place on a short window (the open sleeve is a centre crop, shorter than wide), the sleeve stays laid out square
        // like the album's while it moves, and a clip window closes from the square onto the centre crop: the picture is
        // never stretched. Fully open it is the normal crop again.
        val square = inPlace != null && !fullyExpanded
        val crop = if (square) (width - sleeve).coerceAtLeast(0.dp) else 0.dp
        Box(Modifier.width(width).height(if (square) width else sleeve)
            .graphicsLayer {
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                if (inPlace != null) {
                    // From the album page's sleeve: uniform scale (none when both are full width), the crop closing in.
                    val p = expansion()
                    val source = inPlace.sleeve
                    scaleX = (source.width + (width.toPx() - source.width) * p) / width.toPx(); scaleY = scaleX
                    val cut = crop.toPx() / 2 * p
                    translationX = source.left * (1 - p)
                    translationY = source.top * (1 - p) - cut * scaleY
                    shape = androidx.compose.foundation.shape.GenericShape { box, _ -> addRect(Rect(0f, cut, box.width, box.height - cut)) }
                    clip = true
                    // The same picture at the same rect crossfades with the album page's sleeve over p 0–0.3.
                    alpha = (p / .3f).coerceIn(0f, 1f)
                } else {
                    // From the mini cover (48 dp).
                    val startSize = 48.dp.toPx()
                    scaleX = (startSize + (width.toPx() - startSize) * expansion()) / width.toPx()
                    scaleY = (startSize + (sleeve.toPx() - startSize) * expansion()) / sleeve.toPx()
                    translationX = 16.dp.toPx() * (1 - expansion())
                    translationY = (miniTop + 8.dp).toPx() * (1 - expansion())
                    shape = RoundedCornerShape((10 * (1 - expansion())).dp); clip = true
                }
            }) {
            val latestRevision = rememberUpdatedState(revision)
            val queue = remember(controller) { RevisionSleeveQueue({ latestRevision.value }, ControllerSleeveQueue(controller)) }
            SleevePager(queue, userScrollEnabled = fullyExpanded, modifier = Modifier.fillMaxSize().testTag("sleevePager")) { page ->
                CoverImage(fetcher, queue.coverAt(page), Modifier.fillMaxSize(), size = 1440)
            }
            // The scrim and the bottom fade follow the visible window (no offset unless in place on a short window).
            Box(Modifier.graphicsLayer { translationY = crop.toPx() / 2 * expansion() }.fillMaxWidth().height(104.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .42f), Color.Transparent))))
            Box(Modifier.fillMaxSize().graphicsLayer { translationY = -crop.toPx() / 2 * expansion() }) {
                SleeveFade(accent, edge = { sleeve.toPx() + crop.toPx() * (1 - expansion()) }, height = { pageHeight.toPx() })
            }
        }
        // The details are composed past 25 % and fade/slide in as one column (v0.1.5). In place (spec M3) they rise 24 dp
        // element by element instead, because the play button is on screen from the start and morphs from the album's.
        if (detailsShown || inPlace != null) {
            val column = if (inPlace != null) Modifier else Modifier.graphicsLayer {
                alpha = ((expansion() - .25f) / .75f).coerceIn(0f, 1f); translationY = (160 * (1 - expansion())).dp.toPx()
            }
            val reveal = if (inPlace == null) Modifier else Modifier.graphicsLayer {
                val t = ((expansion() - .25f) / .75f).coerceIn(0f, 1f)
                alpha = t; translationY = 24.dp.toPx() * (1 - t)
            }
            Row(reveal.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                GlassIconButton(onCollapse, fill = Color.Black.copy(alpha = .24f)) {
                    Icon(Icons.Default.KeyboardArrowDown, "Collapse player", Modifier.size(28.dp))
                }
                GlassIconButton(onQueue, fill = Color.Black.copy(alpha = .24f)) {
                    Icon(Icons.Default.QueueMusic, "Open queue")
                }
            }
            val waiting = controller.sessionExtras.getBoolean("waiting")
            var overflowing by remember { mutableStateOf(false) }
            val top = sleeve + 12.dp
            val bottom = bottomPadding
            val availableHeight = constraints.maxHeight - with(androidx.compose.ui.platform.LocalDensity.current) { (top + bottom).roundToPx() }
            // In place, the play button flies in from above this column (the album's button sits on the sleeve edge); the
            // scroll container would clip it, so it joins only once the player is open (same offset, no clip meanwhile).
            val scrolls = inPlace == null || fullyExpanded
            Column(Modifier.fillMaxSize().padding(top = top, bottom = bottom)
                // Measure the actual text/banner/controls; scroll only when their minimum height overflows.
                .then(if (scrolls) Modifier.verticalScroll(scroll, enabled = overflowing) else Modifier).layout { measurable, constraints ->
                    val height = maxOf(availableHeight, measurable.minIntrinsicHeight(constraints.maxWidth))
                    overflowing = height > availableHeight
                    val child = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
                    layout(child.width, minOf(child.height, constraints.maxHeight)) { child.placeRelative(0, if (scrolls) 0 else -scroll.value) }
                }.then(column)
                ) {
                Box(reveal.padding(horizontal = 20.dp)) {
                    Text(metadata.title?.toString().orEmpty(), Modifier.testTag("nowPlayingTitle"),
                        fontSize = 27.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // The artist and album names link to their pages (the player collapses first).
                val albumId = metadata.extras?.getString("albumId")?.takeIf { it.isNotEmpty() }
                val songId = controller.currentMediaItem?.mediaId?.removePrefix("song/")
                // Artist and album share the full width (see ArtistAlbumLayout), one line each.
                ArtistAlbumLayout(reveal.padding(start = 20.dp, end = 20.dp, top = 3.dp, bottom = 3.dp).fillMaxWidth()) {
                    ArtistCreditLine(metadata.artist?.toString().orEmpty(), albumId, songId, accent.text, artistsOf, onArtistId) {
                        Text(metadata.artist?.toString().orEmpty(), Modifier.testTag("nowPlayingArtist")
                            .clickable(enabled = albumId != null && songId != null, onClickLabel = "Open artist") { onArtist(albumId!!, songId!!) },
                            color = accent.text, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(" · ", color = Color(0xFFF1F1EE).copy(alpha = .66f), fontSize = 16.sp, maxLines = 1)
                    Text(metadata.albumTitle?.toString().orEmpty(), Modifier.testTag("nowPlayingAlbum")
                        .clickable(enabled = albumId != null, onClickLabel = "Open album") { onAlbum(albumId!!) },
                        color = Color(0xFFF1F1EE).copy(alpha = .66f), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (waiting) Box(reveal) { ConnectionBanner(controller.mediaMetadata.title?.toString().orEmpty(),
                    controller.sessionExtras.getLong("retrySeconds")) }
                else Row(reveal.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AlbumRatingControl(metadata.extras, accent.text)
                    val playing = dev.coda.android.playback.AudioDetailsHub.snapshot()
                    val format = playing?.let { dev.coda.android.playback.AudioDetailsFormat.playingFormat(it) }
                        ?: controller.sessionExtras.getString("currentFormat") ?: "Unknown"
                    // Plain info, no action; TalkBack hears "FLAC, 912 kilobits per second", not "FLAC 912".
                    val spokenFormat = playing?.let { dev.coda.android.playback.AudioDetailsFormat.playingFormatSpoken(it) } ?: format
                    Text(format, Modifier.testTag("statusChip").clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = .08f))
                        .semantics { contentDescription = spokenFormat }
                        .heightIn(min = 32.dp).wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 8.dp),
                        fontSize = 11.sp, color = Color.White.copy(alpha = .75f))
                }
                controller.playerError?.let { error ->
                    val hasNext = controller.resolvedSuccessor() != null
                    Row(reveal.padding(horizontal = 20.dp).fillMaxWidth().testTag("trackError"), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Can't play this track", color = Color(0xFFF2777A))
                            Text(trackErrorReason(error.errorCode), Modifier.testTag("trackErrorReason"), fontSize = 13.sp, color = Color.White.copy(alpha = .66f))
                        }
                        if (hasNext) FilledTonalButton(onClick = { controller.seekToNextMediaItem() }, Modifier.testTag("trackSkip")) { Text("Skip") }
                    }
                }
                // Memory-only lookup also avoids a pending frame on cached replay before the extras tick.
                val trackCache = app.trackCache
                val waveform = remember(songId, trackCache, trackCache?.revision, revision) {
                    songId?.let { trackCache?.waveform(it) }
                        ?: if (controller.sessionExtras.getString("waveformSongId") == songId)
                            controller.sessionExtras.getFloatArray("waveform")?.toList() else null
                }
                val seekEnabled = controller.currentSeekable || waveform != null
                // The bar's motion only with the activity started and the screen on (the signals); Now Playing
                // hidden = this column is not composed at all.
                val started by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateAsState()
                val screenOn by app.quietPlayback.screenOn.collectAsState()
                val onScreen = dev.coda.core.playback.BackgroundWorkPolicy.waveformMotion(
                    started.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED), screenOn)
                Box(reveal.graphicsLayer { this.alpha = if (waiting) .5f else 1f }) {
                    val current = controller.currentMediaItem
                    SeekBar(position, duration, current?.mediaId.orEmpty(), accent, controller::seekTo,
                        seekable = seekEnabled, peaks = waveform, playing = controller.isPlaying, intro = app.waveformIntro, onScreen = onScreen,
                        occurrence = current?.mediaMetadata?.extras?.getString("queueEntryId") ?: current?.mediaId.orEmpty())
                }
                Row(reveal.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(playbackTime(position), color = Color.White.copy(alpha = .65f), fontSize = 12.sp)
                    Text("−${playbackTime(duration - position)}", color = Color.White.copy(alpha = .65f), fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                Transport(controller, revision, accent, reveal = reveal, playFrom = inPlace?.play, expansion = expansion)
                Spacer(Modifier.weight(.5f))
                val next = remember(controller, revision) { controller.resolvedSuccessor() }
                Row(reveal.padding(horizontal = 12.dp, vertical = 12.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(Color.White.copy(alpha = .07f)).clickable(onClick = onQueue)
                    .heightIn(min = 60.dp).padding(10.dp).testTag("upNext"),
                    verticalAlignment = Alignment.CenterVertically) {
                    CoverImage(fetcher, next?.mediaMetadata?.extras?.getString("coverArtId"), Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text("UP NEXT", fontSize = 10.sp, color = Color.White.copy(alpha = .45f), fontWeight = FontWeight.Bold)
                        Text(next?.mediaMetadata?.title?.toString() ?: "End of queue", fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Default.QueueMusic, "Open queue")
                }
            }
        }
    }
}

/** Shuffle, previous, play/pause, next and repeat at their built sizes. */
@Composable
internal fun Transport(controller: MediaController, revision: Int, accent: AccentState, modifier: Modifier = Modifier,
    /** The other buttons rise with the details; the play button morphs from the album's [playFrom] (root px). */
    reveal: Modifier = Modifier, playFrom: Rect? = null, expansion: () -> Float = { 1f }) {
    val waiting = controller.sessionExtras.getBoolean("waiting")
    var buffering by remember { mutableStateOf(false) }
    LaunchedEffect(controller, revision) {
        buffering = false
        if (controller.playbackState == Player.STATE_BUFFERING) { delay(300); buffering = true }
    }
    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(onClick = {
            controller.sendCustomCommand(androidx.media3.session.SessionCommand(
                dev.coda.android.playback.SHUFFLE_REMAINING, android.os.Bundle.EMPTY), android.os.Bundle.EMPTY)
        }, enabled = controller.currentMediaItemIndex + 1 < controller.mediaItemCount, modifier = reveal.size(48.dp).testTag("shuffleRemaining")) {
            Icon(Icons.Default.Shuffle, "Shuffle remaining queue", tint = Color.White.copy(alpha = .6f))
        }
        IconButton(onClick = { controller.seekToPreviousMediaItem() }, reveal.size(56.dp)) { Icon(Icons.Default.SkipPrevious, "Previous track", Modifier.size(38.dp)) }
        val radius by animateDpAsState(if (controller.isPlaying) 28.dp else 42.dp, tween(300), label = "Play shape")
        // Keep the existing seek-buffering footprint; the waiting ring overlays the normal button.
        var playEnd by remember { mutableStateOf(Rect.Zero) }
        Box(Modifier.size(if (buffering && !waiting) 90.dp else 84.dp)
            .then(if (playFrom == null) Modifier else Modifier.onGloballyPositioned { playEnd = it.boundsInRoot() }.graphicsLayer {
                // Spec M3 `play-button`: opaque from the start, from the album's 64 dp button to its own place and size. The
                // button and its buffering ring move together, about their shared centre (the 84 dp button's).
                val p = expansion().coerceIn(0f, 1f)
                if (playEnd != Rect.Zero) {
                    translationX = (playFrom.center.x - playEnd.center.x) * (1 - p); translationY = (playFrom.center.y - playEnd.center.y) * (1 - p)
                    scaleX = (playFrom.width + (84.dp.toPx() - playFrom.width) * p) / 84.dp.toPx(); scaleY = scaleX
                }
            }), contentAlignment = Alignment.Center) {
            FilledIconButton(onClick = { controller.retryOrTogglePlayback() }, Modifier.size(84.dp)
                .testTag("nowPlayingPlay").semantics { dev.coda.android.ui.album.PlayFillKey.let { this[it] = accent.fill.toArgb() } },
                shape = RoundedCornerShape(radius), colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent.fill, contentColor = accent.onFill)) {
                Crossfade(controller.isPlaying && !waiting, animationSpec = tween(200), label = "Play icon") { playing ->
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(38.dp))
                }
            }
            if (buffering || waiting) CircularProgressIndicator(Modifier.requiredSize(90.dp).testTag("nowPlayingSpinner"), color = accent.text, strokeWidth = 3.dp)
        }
        IconButton(onClick = { controller.seekToNextMediaItem() }, reveal.size(56.dp)) {
            Icon(Icons.Default.SkipNext, "Next track", Modifier.size(38.dp))
        }
        ModeButton("Repeat ${when (controller.repeatMode) { Player.REPEAT_MODE_ONE -> "one"; Player.REPEAT_MODE_ALL -> "all"; else -> "off" }}", controller.repeatMode != Player.REPEAT_MODE_OFF, accent,
            if (controller.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, reveal) {
            controller.repeatMode = when (controller.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
        }
    }
}

@Composable
private fun ModeButton(label: String, active: Boolean, accent: AccentState, icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    IconButton(onClick, modifier.size(48.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, label, tint = if (active) accent.text else Color.White.copy(alpha = .6f))
            if (active) Box(Modifier.padding(top = 3.dp).size(4.dp).background(accent.text, RoundedCornerShape(50)))
        }
    }
}

/** The seek bar plus BF-1 item 3's hint: an overlay (no layout shift) shown ~2 s only when the disabled bar is tapped or dragged. */
@Composable
internal fun SeekBar(position: Long, duration: Long, songId: String, accent: AccentState, seek: (Long) -> Unit, seekable: Boolean,
    peaks: List<Float>? = null, playing: Boolean = false, intro: WaveformIntro = remember { WaveformIntro() },
    reduceMotion: Boolean = !android.animation.ValueAnimator.areAnimatorsEnabled(), onScreen: Boolean = true, occurrence: String = songId) {
    var hintVisible by remember { mutableStateOf(false) }
    var hintRequest by remember { mutableIntStateOf(0) }
    LaunchedEffect(hintRequest) { if (hintRequest > 0) { hintVisible = true; delay(2000); hintVisible = false } }
    Box {
        BarSeek(position, duration, songId, accent, seek, enabled = seekable, peaks = peaks, playing = playing, intro = intro, onBlockedSeek = { hintRequest++ }, reduceMotion = reduceMotion, onScreen = onScreen, occurrence = occurrence)
        if (hintVisible && !seekable) Text("Seeking unlocks once this track is cached",
            Modifier.align(Alignment.TopCenter).offset(y = (-6).dp).testTag("seekHint").clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = .78f)).padding(horizontal = 10.dp, vertical = 6.dp),
            fontSize = 12.sp, color = Color.White.copy(alpha = .9f))
    }
}

@Composable
internal fun BarSeek(position: Long, duration: Long, songId: String, accent: AccentState, seek: (Long) -> Unit, enabled: Boolean = true,
    peaks: List<Float>? = null, playing: Boolean = false, intro: WaveformIntro = remember { WaveformIntro() }, onBlockedSeek: () -> Unit = {},
    reduceMotion: Boolean = !android.animation.ValueAnimator.areAnimatorsEnabled(), onScreen: Boolean = true,
    /** The queue occurrence the bar seeks in: a gesture held while it changes is dropped, even for an equal duration. */
    occurrence: String = songId) {
    val latestBlocked by rememberUpdatedState(onBlockedSeek)
    val waveform = peaks.takeIf { enabled }
    var scrub by remember(occurrence) { mutableStateOf<Float?>(null) }
    val latestSeek by rememberUpdatedState(seek)
    val fraction = scrub ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val pattern = remember(songId, waveform) { dev.coda.core.waveform.waveformBars(waveform.orEmpty(), 44) }
    // Every new track grows its bars from the baseline, cached waveform or not; the same track
    // re-composed (Now Playing reopened) starts fully drawn. One Animatable per track: a skip disposes the previous one
    // with its effect, and a restarted effect (waveform arriving, reduced motion flipping, the screen coming back)
    // continues from the current value, so animations never stack. With the screen off nothing runs (no frames);
    // a track that changed meanwhile unfolds once it is shown.
    val arrival = remember(songId) { Animatable(WaveformIntro.initialArrival(waveform != null, intro.hasPlayed(songId))) }
    LaunchedEffect(songId, waveform, reduceMotion, onScreen) {
        if (waveform == null) arrival.snapTo(0f)
        else if (onScreen) {
            if (intro.beginIfShown(songId, onScreen)) arrival.snapTo(0f)
            // Reduced motion is the 150 ms fade in real time, also at animator scale 0 (which would otherwise complete it at
            // once); same approach as PlayerLayer's crossfade.
            val fade = tween<Float>(WaveformIntro.durationMs(reduceMotion), easing = LinearEasing)
            if (reduceMotion) kotlinx.coroutines.withContext(object : androidx.compose.ui.MotionDurationScale { override val scaleFactor = 1f }) { arrival.animateTo(1f, fade) }
            else arrival.animateTo(1f, fade)
        }
    }
    // The loading tide: a frame loop only while SeekPlaceholder.tideRuns (shown with the screen on); stopped, the
    // bars keep their last position.
    var tideTime by remember(songId) { mutableFloatStateOf(0f) }
    LaunchedEffect(songId, playing, waveform, reduceMotion, onScreen) {
        if (dev.coda.core.waveform.SeekPlaceholder.tideRuns(playing, waveform != null, reduceMotion, onScreen)) {
            val start = withInfiniteAnimationFrameNanos { it } - (tideTime * 1_000_000_000).toLong()
            while (true) withInfiniteAnimationFrameNanos { tideTime = (it - start) / 1_000_000_000f }
        }
    }
    LaunchedEffect(occurrence, enabled) { scrub = null }
    Canvas(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(48.dp).testTag("seekBar")
        .semantics {
            contentDescription = if (enabled) "Seek, ${playbackTime(position)} of ${playbackTime(duration)}"
                else "Seek bar disabled until this track is cached, ${playbackTime(position)} of ${playbackTime(duration)}"
            stateDescription = if (!enabled) "Downloading" else if (waveform == null) "Waveform pending" else "Waveform ready"
            progressBarRangeInfo = ProgressBarRangeInfo(position.toFloat().coerceIn(0f, duration.toFloat()), 0f..duration.toFloat(), (duration / 5000).toInt().coerceAtLeast(0))
            if (enabled) setProgress { latestSeek(it.toLong().coerceIn(0, duration)); true } else disabled()
        }.pointerInput(occurrence, duration, enabled) {
            if (enabled) detectTapGestures { point -> if (duration > 0) latestSeek((point.x / size.width * duration).toLong().coerceIn(0, duration)) }
            else detectTapGestures { latestBlocked() }
        }.pointerInput(occurrence, duration, enabled) {
            if (!enabled) detectDragGestures(onDragStart = { latestBlocked() }, onDrag = { change, _ -> change.consume() })
            else
            detectDragGestures(onDragStart = { scrub = (it.x / size.width).coerceIn(0f, 1f) }, onDrag = { change, _ ->
                change.consume(); scrub = (change.position.x / size.width).coerceIn(0f, 1f)
            }, onDragEnd = { scrub?.let { latestSeek((it * duration).toLong()) }; scrub = null }, onDragCancel = { scrub = null })
        }) {
        val playhead = size.width * fraction
        val step = size.width / 44
        pattern.forEachIndexed { i, height ->
            val placeholder = dev.coda.core.waveform.SeekPlaceholder.height(i, tideTime)
            val grow = if (reduceMotion) arrival.value else dev.coda.core.waveform.SeekPlaceholder.unfold(i, fraction, arrival.value)
            val realHeight = 8f + 26f * height
            val h = (if (reduceMotion) placeholder else placeholder + (realHeight - placeholder) * grow).dp.toPx()
            val played = barPlayed(i, fraction)
            val baseAlpha = if (played) .55f + .45f * grow else .20f + .02f * grow
            val color = (if (played) accent.text else Color.White).copy(alpha = baseAlpha)
            val x = step * i + step / 2
            fun bar(tint: Color, barHeight: Float = h) = drawLine(tint,
                Offset(x, (size.height - barHeight) / 2), Offset(x, (size.height + barHeight) / 2),
                4.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
            if (reduceMotion && waveform != null) {
                bar(color.copy(alpha = color.alpha * (1 - arrival.value)))
                bar(color.copy(alpha = color.alpha * arrival.value), realHeight.dp.toPx())
            } else bar(color)
        }
        drawLine(Color.White, Offset(playhead, (size.height - 38.dp.toPx()) / 2), Offset(playhead, (size.height + 38.dp.toPx()) / 2), 6.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/** A bar counts as played once the playhead passes its centre, so it flips as the head crosses it (not at 0:00 for bar 0). */
internal fun barPlayed(index: Int, fraction: Float) = (index + 0.5f) / 44f <= fraction
