package dev.coda.android.ui.offline

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.coda.android.ui.common.StateBlock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import dev.coda.core.connectivity.Reachability

/** What the line above the dock shows (the way back). */
enum class OfflineLineKind { NONE, OFFLINE, OFFER, BACK_ONLINE }

/**
 * Offline, automatic or chosen: "Offline · music on this phone". Server unreachable: the offer, unless a page's own
 * offer block is on screen (the offer is never shown twice). Online: "Back online · updating" while a view still shows
 * the phone's content ([updating], its live answer not yet in), else nothing.
 */
fun offlineLineKind(state: Reachability.State, offerBlockShown: Boolean = false, updating: Boolean = false) = when {
    state.link == Reachability.Link.OFFLINE || state.link == Reachability.Link.OFFLINE_CHOSEN -> OfflineLineKind.OFFLINE
    state.link == Reachability.Link.UNREACHABLE -> if (offerBlockShown) OfflineLineKind.NONE else OfflineLineKind.OFFER
    updating -> OfflineLineKind.BACK_ONLINE
    else -> OfflineLineKind.NONE
}

/** Offline, automatic or chosen: the views show what's on the phone. Server unreachable is not offline yet. */
fun showsPhone(state: Reachability.State) =
    state.link == Reachability.Link.OFFLINE || state.link == Reachability.Link.OFFLINE_CHOSEN

/** How long an offer block counts as shown after it left: moving between two pages that can't load must not bounce the line. */
const val OFFER_BLOCK_HOLD_MS = 300L

/** "Back online · updating" stays at least this long once shown, so it can be read. */
const val BACK_ONLINE_MIN_MS = 1500L

/**
 * True for [minMs] from each time [this] turns to BACK_ONLINE (the line's minimum time); OFFLINE or OFFER cuts it at once,
 * NONE (the reload done) does not.
 */
fun kotlinx.coroutines.flow.Flow<OfflineLineKind>.backOnlineHeld(minMs: Long): kotlinx.coroutines.flow.Flow<Boolean> = channelFlow {
    var timer: Job? = null
    collect { kind ->
        when (kind) {
            OfflineLineKind.BACK_ONLINE -> if (timer == null) timer = launch { send(true); delay(minMs); timer = null; send(false) }
            OfflineLineKind.NONE -> Unit
            else -> timer?.let { it.cancel(); timer = null; send(false) }
        }
    }
}.distinctUntilChanged()

/** True while [this] is true and for [holdMs] after it turned false (a true within the hold cancels the drop). */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun kotlinx.coroutines.flow.Flow<Boolean>.held(holdMs: Long): kotlinx.coroutines.flow.Flow<Boolean> =
    transformLatest { if (it) emit(true) else { delay(holdMs); emit(false) } }.distinctUntilChanged()

/**
 * The shell's view of the state for its pages: the link, the offer action, which pages show the offer block, and
 * how many views show the phone's content ([shown]).
 */
@Stable
class OfflineViewsState(private val source: State<Reachability.State>, val shown: PhoneShown = PhoneShown(), val choose: () -> Unit) {
    val state: Reachability.State get() = source.value
    /** Offer blocks of shown pages (a page that can't load while the server is unreachable). */
    var offerBlocks by mutableIntStateOf(0)
        internal set
    /** [offerBlocks] > 0, held [OFFER_BLOCK_HOLD_MS] after the last block left (see [holdOfferBlocks]). */
    var offerBlockShown by mutableStateOf(false)
        private set
    /** Runs while the shell is composed. */
    suspend fun holdOfferBlocks() {
        snapshotFlow { offerBlocks > 0 }.held(OFFER_BLOCK_HOLD_MS).collect { offerBlockShown = it }
    }
    /** "Back online · updating" is still within its minimum time ([holdBackOnline]). */
    private var backOnlineHeld by mutableStateOf(false)
    /** Runs while the shell is composed: keeps "Back online · updating" on screen [BACK_ONLINE_MIN_MS] once shown. */
    suspend fun holdBackOnline() {
        snapshotFlow { offlineLineKind(state, updating = shown.updating > 0) }.backOnlineHeld(BACK_ONLINE_MIN_MS)
            .collect { backOnlineHeld = it }
    }
    // The shell reads these, not [state], so a change that leaves them as they were (the start-up "known" flip, a
    // repeated verdict) doesn't recompose the shell.
    private val unreachable = derivedStateOf { !state.reachable }
    private val phoneNow = derivedStateOf { showsPhone(state) }
    // Online, a view still showing the phone's content waits for its live answer (a whole fresh list).
    // Once shown it stays its minimum time, online only (offline or unreachable again takes over at once).
    private val line = derivedStateOf {
        val kind = offlineLineKind(state, offerBlockShown, updating = shown.updating > 0)
        if (kind == OfflineLineKind.NONE && backOnlineHeld && state.link == Reachability.Link.ONLINE) OfflineLineKind.BACK_ONLINE else kind
    }
    /** The global line says the server is gone (not online): the pages' own offline lines step back. */
    val lineShown: Boolean get() = unreachable.value
    /** Offline, automatic or chosen: the views show what's on the phone. */
    val phone: Boolean get() = phoneNow.value
    /** What the line above the dock shows ([offlineLineKind] with [offerBlockShown] and the views still from the phone). */
    val lineKind: OfflineLineKind get() = line.value
}

/**
 * What a view drawn from the phone knows: the cached songs (others are dimmed) and the partial counts ("7 of 12",
 * keyed `album/<id>` and `playlist/<id>`). Provided only around a view that shows the phone's content; null: live data.
 */
@Immutable
class OnPhone(val songs: Set<String>, val counts: Map<String, dev.coda.core.library.OfflineCatalogue.Count> = emptyMap()) {
    fun has(songId: String) = songId in songs
    /** "7 of 12" for a partial album, else null. */
    fun album(id: String): String? = counts["album/$id"]?.takeIf { it.partial }?.label
    fun playlist(id: String): String? = counts["playlist/$id"]?.takeIf { it.partial }?.label
}

val LocalOnPhone = staticCompositionLocalOf<OnPhone?> { null }

/** Missing tracks offline: dimmed only, no circle. */
const val MISSING_ALPHA = .38f

/** The short Night snackbar for a tap on a dimmed track. */
const val NOT_ON_PHONE = "Not on this phone"

/**
 * One view's way back: its phone content stays until the view's own live data arrived after the server came
 * back, then one 300 ms cross-fade; never a blank. [live] is any value that changes with each new state of the view's
 * model; [loaded] says that state is a complete live answer.
 */
class WayBack(phone: Boolean) {
    var fromPhone by mutableStateOf(phone)
        private set
    private var answersAtReturn = -1
    /** [answers]: the live answers the view's model has had so far (it counts each one). */
    fun update(phone: Boolean, answers: Int) {
        if (phone) { fromPhone = true; answersAtReturn = -1; return }
        if (!fromPhone) return
        if (answersAtReturn < 0) { answersAtReturn = answers; return }
        if (answers > answersAtReturn) fromPhone = false
    }
}

/**
 * [WayBack] for a view: once the phone mode ends it asks the view's model for live data ([reload]), and again on the  * schedule ([Reachability.BACK_STEPS_MS], 60 s cap) until the answer is in. A reload is skipped while
 * the model is still [loading], so a slow answer is never cut off; only while the app is in front (no requests in the background).
 */
@Composable
fun rememberFromPhone(phone: Boolean, answers: Int, loading: () -> Boolean = { false }, reload: () -> Unit): Boolean {
    val way = remember { WayBack(phone) }
    way.update(phone, answers)
    val waiting = !phone && way.fromPhone
    val latestReload by rememberUpdatedState(reload)
    val latestLoading by rememberUpdatedState(loading)
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(waiting, lifecycle) {
        if (!waiting) return@LaunchedEffect
        var step = 0
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                if (step == 0 || !latestLoading()) latestReload()
                delay(retryDelay(step++))
            }
        }
    }
    return way.fromPhone
}

/** The way back's retry after [step] tries: 5, 10, 20, 40, then every 60 s (the back steps). */
fun retryDelay(step: Int): Long = Reachability.BACK_STEPS_MS[step.coerceAtMost(Reachability.BACK_STEPS_MS.size - 1)]

/** A tap on a dimmed track: the shell's Night snackbar (outside the shell: nothing). */
val LocalPhoneNotice = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * The 300 ms cross-fade between a view's phone content and its live data ("the way back"). Never a blank
 *: going offline, or opening a page offline, what's shown stays until the phone's content is read,
 * then the one cross-fade. Both sides share a [ScrollAnchor], so the same item stays in view.
 */
@Composable
fun <T> PhoneOrLive(fromPhone: Boolean, phone: T?, live: T, onPhone: OnPhone?, loading: Boolean = false, failed: Boolean = false,
    content: @Composable (T) -> Unit) {
    val anchor = remember { ScrollAnchor() }
    val ready = phoneReady(fromPhone, phone, onPhone)
    val shown = LocalPhoneShown.current
    val online = LocalOfflineViews.current?.state?.link == Reachability.Link.ONLINE
    val back = remember { FailedBack() }
    val failedBack = back.update(ready, online, loading, failed)
    DisposableEffect(shown, ready) {
        if (ready) shown?.let { it.views++ }
        onDispose { if (ready) shown?.let { it.views-- } }
    }
    val updating = ready && !failedBack
    DisposableEffect(shown, updating) {
        if (updating) shown?.let { it.updating++ }
        onDispose { if (updating) shown?.let { it.updating-- } }
    }
    CompositionLocalProvider(LocalScrollAnchor provides anchor) {
        androidx.compose.animation.Crossfade(ready, animationSpec = tween(300), label = "Phone or live") { showPhone ->
            if (showPhone && phone != null && onPhone != null)
                CompositionLocalProvider(LocalOnPhone provides onPhone, LocalFailedBack provides failedBack) { content(phone) }
            else content(live)
        }
    }
}

/**
 * How many composed views show the phone's content. Actions follow what is shown: after OFFLINE, while
 * the server is still unreachable, a view keeps the phone's content until its live answer, and Play, Shuffle and the
 * queue actions keep taking the tracks here meanwhile ([phoneActions]).
 */
@Stable
class PhoneShown {
    var views by mutableIntStateOf(0)
    /** Of [views], those still waiting for their live answer: "Back online · updating". */
    var updating by mutableIntStateOf(0)
}

/**
 * A view showing the phone's content whose way-back request failed with the server answering (an error
 * answer, e.g. a playlist deleted elsewhere) is no longer "updating": the line leaves and a page not on the phone shows its
 * normal failure block (Retry). Only a failure after a live request of the way back counts ([loading] seen online), not
 * the one left from the outage; its retries keep it; it resets when the server is gone again or the phone's side leaves.
 */
class FailedBack {
    private var asked = false
    var failed = false
        private set
    /** [shown]: the view shows the phone's content; [online]: the link is ONLINE. Returns [failed]. */
    fun update(shown: Boolean, online: Boolean, loading: Boolean, failed: Boolean): Boolean {
        if (!shown || !online) { asked = false; this.failed = false; return false }
        if (loading) asked = true
        else if (asked && failed) this.failed = true
        return this.failed
    }
}

/** Inside [PhoneOrLive]'s phone side: its live request failed with the server answering ([FailedBack]). */
val LocalFailedBack = staticCompositionLocalOf { false }

val LocalPhoneShown = staticCompositionLocalOf<PhoneShown?> { null }

/** The actions' decision: offline (automatic or chosen), or a view still shows the phone's content on the way back. */
fun phoneActions(phoneMode: Boolean, shown: PhoneShown?) = phoneMode || (shown?.views ?: 0) > 0

/** The phone's side shows once its content is read; until then the view keeps what it showed (never a blank). */
fun phoneReady(fromPhone: Boolean, phone: Any?, onPhone: OnPhone?) = fromPhone && phone != null && onPhone != null

/**
 * The items in view of a list shown from the phone or live: each side writes its visible items,
 * and the side that fades in starts at the first of them its list has (by key), else at the same index. A
 * section header or an album the other list lacks is skipped, so the nearest album still there stays in view.
 */
@Stable
class ScrollAnchor {
    var keys: List<Any> = emptyList(); var index = 0; var offset = 0
    /** Where a list starts: [indexOf] maps a key to this list's index (null: not there or unknown). */
    fun start(indexOf: (Any) -> Int?): Pair<Int, Int> {
        keys.forEachIndexed { n, key -> indexOf(key)?.let { return it to if (n == 0) offset else 0 } }
        return index to offset
    }
}

val LocalScrollAnchor = staticCompositionLocalOf<ScrollAnchor?> { null }

/**
 * A list's index of its [i]-th row after [lead] items, with one header item before each of [sections] (none: plain rows).
 * For [ScrollAnchor.start] lookups.
 */
fun sectionedIndex(i: Int, lead: Int, sections: List<dev.coda.core.library.LibrarySection> = emptyList()): Int =
    lead + i + if (sections.isEmpty()) 0 else sections.indexOfLast { it.start <= i } + 1

/** A view's list state that keeps the [LocalScrollAnchor]'s item across the cross-fade (outside [PhoneOrLive]: plain). */
@Composable
fun rememberAnchoredListState(indexOf: (Any) -> Int? = { null }): androidx.compose.foundation.lazy.LazyListState {
    val anchor = LocalScrollAnchor.current
    val (index, offset) = remember { anchor?.start(indexOf) ?: (0 to 0) }
    val state = androidx.compose.foundation.lazy.rememberLazyListState(index, offset)
    if (anchor != null) LaunchedEffect(state, anchor) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.let { v -> v.firstOrNull()?.let { Triple(v.map { it.key }, it.index, state.firstVisibleItemScrollOffset) } } }
            .collect { it?.let { (k, i, o) -> anchor.keys = k; anchor.index = i; anchor.offset = o } }
    }
    return state
}

@Composable
fun rememberAnchoredGridState(indexOf: (Any) -> Int? = { null }): androidx.compose.foundation.lazy.grid.LazyGridState {
    val anchor = LocalScrollAnchor.current
    val (index, offset) = remember { anchor?.start(indexOf) ?: (0 to 0) }
    val state = androidx.compose.foundation.lazy.grid.rememberLazyGridState(index, offset)
    if (anchor != null) LaunchedEffect(state, anchor) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.let { v -> v.firstOrNull()?.let { Triple(v.map { it.key }, it.index, state.firstVisibleItemScrollOffset) } } }
            .collect { it?.let { (k, i, o) -> anchor.keys = k; anchor.index = i; anchor.offset = o } }
    }
    return state
}

/**
 * A kept album or playlist re-syncs only from a fresh live page, never from the phone's copy ([onPhone] set): the phone's
 * copy may miss tracks the server has, and a re-sync from it would drop them from the kept set.
 */
fun keepSyncFresh(loading: Boolean, failed: Boolean, onPhone: OnPhone?) = !loading && !failed && onPhone == null

/** What an offline action takes: the items on the phone in the view's order, where to start, how many were left out. */
data class PhonePick<T>(val items: List<T>, val start: Int, val leftOut: Int)

/**
 * Offline, Play, Shuffle, Add to queue and Play next take the tracks on the phone only, in the order given ([items] is the
 * view's order, already shuffled for Shuffle). [start] moves to the first track here at or after it (else the first here).
 */
fun <T> pickOnPhone(items: List<T>, start: Int = 0, has: (T) -> Boolean): PhonePick<T> {
    val here = ArrayList<T>(items.size)
    var from = -1
    items.forEachIndexed { i, item -> if (has(item)) { if (from < 0 && i >= start) from = here.size; here += item } }
    return PhonePick(here, if (from < 0) 0 else from, items.size - here.size)
}

/** The Night snackbar when Play or Shuffle left tracks out. */
fun playingOnPhone(count: Int) = "Playing the $count ${if (count == 1) "track" else "tracks"} on this phone"

/** The queue's confirmation, offline with tracks left out: "Added to queue: Name · the 7 tracks on this phone". */
fun queuedOnPhone(line: String, count: Int) = "$line · the $count ${if (count == 1) "track" else "tracks"} on this phone"

/** Null outside the shell (standalone page tests): pages then behave as before. */
val LocalOfflineViews = compositionLocalOf<OfflineViewsState?> { null }

/** The line's slot: 28 dp line plus 2 dp air; the lists' bottom padding grows by this only while the line shows. */
val OfflineLineSlot: Dp = 30.dp

private val LineSurface = Color(0xD116191D)
private val LineHair = Color.White.copy(alpha = .06f)
private val Text1 = Color(0xFFF1F1EE)
private val Text2 = Text1.copy(alpha = .66f)
private val Caution = Color(0xFFF2C46B)
private val lineMotion = 200
/** The offer's touch target (48 dp), of which the visible line is the bottom 28 dp. */
val OfferHitHeight: Dp = 48.dp

/**
 * The slim line above the dock: slides in and out (200 ms), and changes its text in place when the
 * offer is taken or the server is back ("Back online · updating"). Not tappable as the offline line; TalkBack reads
 * it once when it appears or its text changes (polite live region).
 */
@Composable
fun OfflineLine(kind: OfflineLineKind, onChoose: () -> Unit, modifier: Modifier = Modifier) {
    // The exit animation keeps showing the last text.
    var shown by remember { mutableStateOf(kind) }
    if (kind != OfflineLineKind.NONE) shown = kind
    AnimatedVisibility(kind != OfflineLineKind.NONE, modifier,
        enter = fadeIn(tween(lineMotion)) + slideInVertically(tween(lineMotion)) { it / 2 },
        exit = fadeOut(tween(lineMotion)) + slideOutVertically(tween(lineMotion)) { it / 2 }) {
        AnimatedContent(shown, transitionSpec = { fadeIn(tween(lineMotion)) togetherWith fadeOut(tween(lineMotion)) },
            label = "Offline line") { current ->
            val offer = current == OfflineLineKind.OFFER
            val back = current == OfflineLineKind.BACK_ONLINE
            val accent = MaterialTheme.colorScheme.primary
            // The offer is a button: its hit area is 48 dp high, reaching up from the 28 dp line over the page (design.md);
            // the offline line is not tappable and keeps to its own 28 dp, so it never takes the page's touches.
            Box(Modifier.fillMaxWidth().height(if (offer) OfferHitHeight else 28.dp)
                .then(if (offer) Modifier.clickable(role = Role.Button, onClickLabel = "Show what's on the phone", onClick = onChoose) else Modifier)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                .testTag(if (offer) "offlineOffer" else if (back) "backOnlineLine" else "offlineLine"), contentAlignment = Alignment.BottomCenter) {
            Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(28.dp)
                .background(LineSurface, RoundedCornerShape(14.dp)).border(1.dp, LineHair, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (offer) Icons.Default.Error else if (back) Icons.Default.Cloud else Icons.Default.CloudOff, null,
                    Modifier.size(15.dp), tint = if (offer) Caution else Text2)
                Text(if (offer) "Server unreachable" else if (back) "Back online · updating" else "Offline · music on this phone", Modifier.padding(start = 8.dp).weight(1f),
                    fontSize = 13.sp, lineHeight = 16.sp, color = if (offer) Text1 else Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (offer) Text("Show what's on the phone", Modifier.padding(start = 8.dp), fontSize = 13.sp, lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold, color = accent, maxLines = 1)
            }
            }
        }
    }
}

/**
 * A page that can't load (no saved copy). Server unreachable (in the shell): the offer as the state block's main
 * action, Retry as text, and the global line steps back while this block is on a shown page. Otherwise today's block.
 */
@Composable
fun CantLoadBlock(title: String, body: String, retry: () -> Unit, modifier: Modifier = Modifier) {
    val views = LocalOfflineViews.current
    // Also while the view still shows the phone's answer after OFFLINE: on the way back ("Back online · updating"),
    // so the block doesn't turn into "check your connection" before the live answer is in, and while the server is
    // unreachable again (the global line shows the offer; this block is not an offer block, as the page shows the phone's
    // answer). Not once the view's live request failed with the server answering: the normal block, Retry.
    if (views != null && (views.phone || (LocalOnPhone.current != null && !LocalFailedBack.current))) {
        // Offline (automatic or chosen): the line says why; the block says the page isn't here (not "check your connection").
        StateBlock(Icons.Default.CloudOff, "Not on this phone", "This page isn't saved on this phone. It shows again when the server answers.",
            modifier = modifier)
        return
    }
    if (views == null || !views.state.offerShown) {
        StateBlock(Icons.Default.CloudOff, title, body, "Retry", retry, modifier)
        return
    }
    val shown = dev.coda.android.ui.shell.LocalPageOwnership.current.shown
    if (shown) DisposableEffect(views) {
        views.offerBlocks++
        onDispose { views.offerBlocks-- }
    }
    OfferBlock(views.choose, retry, modifier)
}

@Composable
private fun OfferBlock(choose: () -> Unit, retry: () -> Unit, modifier: Modifier) {
    Box(modifier.fillMaxWidth().height((LocalConfiguration.current.screenHeightDp * 0.6f).dp).testTag("offerBlock"),
        contentAlignment = Alignment.Center) {
        Column(Modifier.padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Error, null, Modifier.size(48.dp), tint = Color(0xFF707479))
            Text("Server unreachable", Modifier.padding(top = 12.dp).semantics { heading() }, fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text("This page isn't saved on this phone yet, so it can't be shown until the server answers.",
                Modifier.padding(top = 4.dp), fontSize = 13.sp, color = Color(0xFFA0A09D), textAlign = TextAlign.Center)
            FilledTonalButton(onClick = choose, Modifier.padding(top = 16.dp).testTag("offerAction")) { Text("Show what's on the phone") }
            TextButton(onClick = retry, Modifier.testTag("stateAction")) { Text("Retry") }
        }
    }
}
