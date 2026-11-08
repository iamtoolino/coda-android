package dev.coda.android.ui.common

import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.*
import kotlin.math.roundToInt

/*
 * The app's one way to describe things to TalkBack. Labels name the thing, never
 * the gesture; a row or cell is one stop; artwork, initials, separators and handles are decorative; a rating is one
 * adjustable control; every page announces itself and reads its header before its content.
 */

/** A row or cell label built from its parts in a fixed order (title, artist, rating, duration, state); blanks are dropped. */
internal fun itemLabel(vararg parts: String?): String = parts.filterNot { it.isNullOrBlank() }.joinToString(", ")

/**
 * One TalkBack stop for a row or cell: [label] replaces whatever the children say (covers, initials, badges, texts), and the
 * row's gestures are offered as named actions. Put it before the row's `clickable`/`combinedClickable` in the chain (it
 * replaces their semantics; touch keeps working; the actions here are what TalkBack sees).
 */
internal fun Modifier.itemSemantics(label: String, onClick: (() -> Unit)? = null, clickLabel: String? = null,
    onLongClick: (() -> Unit)? = null, longClickLabel: String? = null, actions: List<CustomAccessibilityAction> = emptyList(),
    state: String? = null, heading: Boolean = false, selected: Boolean? = null, role: Role? = null): Modifier = clearAndSetSemantics {
    contentDescription = label
    if (role != null) this.role = role
    if (state != null) stateDescription = state
    if (heading) heading()
    if (selected != null) this.selected = selected
    if (onClick != null) onClick(clickLabel) { onClick(); true }
    if (onLongClick != null) onLongClick(longClickLabel) { onLongClick(); true }
    if (actions.isNotEmpty()) customActions = actions
}

/**
 * Artwork, initials, separators, drag handles, badges a row already says: drawn, never read. The node merges its own
 * children (so a parent row does not absorb its text) and is hidden from TalkBack; it stays in the test semantics tree.
 */
internal fun Modifier.decorative(): Modifier = semantics(mergeDescendants = true) { hideFromAccessibility() }

/**
 * An album cell (Home shelves, Albums grid, artist Timeline, Search): its title and artist are read as they are, then the
 * rating its badge shows ("Rated 3 of 5"); the badge itself is not a stop (review H1). Pair with `onClickLabel = "Open album"`.
 */
internal fun Modifier.albumCell(rating: Int): Modifier = albumCellState(rating)?.let { state -> semantics { stateDescription = state } } ?: this

/** "3 of 5 stars" or "Not rated": the one way a rating is said. */
internal fun ratingState(rating: Int): String = if (rating <= 0) "Not rated" else "$rating of 5 stars"

/**
 * A rating as one adjustable control ("Album rating, 3 of 5 stars, slider"): swipe up/down or the Increase/Decrease actions
 * change it by one, [clear] adds "Clear rating" while rated; [enabled] is read when an action runs (a save may be in flight). No usage hint in the label and no percentage (the state
 * replaces the range value). [onClick] (with [clickLabel]) is the double-tap, e.g. the album header's "Rate album" editor.
 */
internal fun Modifier.ratingSemantics(label: String, rating: Int, enabled: () -> Boolean, rate: (Int) -> Unit, clear: Boolean = false,
    onClick: (() -> Unit)? = null, clickLabel: String? = null): Modifier = clearAndSetSemantics {
    rating(label, rating, enabled, rate, clear, onClick, clickLabel)
}

/**
 * [ratingSemantics] for a row whose children must stay in the test tree (the sheet's star cells): the row merges, its
 * children are [decorative].
 */
internal fun Modifier.ratingRowSemantics(label: String, rating: Int, enabled: () -> Boolean, rate: (Int) -> Unit, clear: Boolean = false): Modifier =
    semantics(mergeDescendants = true) { rating(label, rating, enabled, rate, clear, null, null) }

private fun SemanticsPropertyReceiver.rating(label: String, rating: Int, enabled: () -> Boolean, rate: (Int) -> Unit, clear: Boolean,
    onClick: (() -> Unit)?, clickLabel: String?) {
    contentDescription = label
    stateDescription = ratingState(rating)
    progressBarRangeInfo = ProgressBarRangeInfo(rating.toFloat(), 0f..5f, 4)
    setProgress { value -> if (!enabled()) false else { rate(value.roundToInt().coerceIn(0, 5)); true } }
    if (onClick != null) onClick(clickLabel) { onClick(); true }
    customActions = ratingActions(rating, enabled, rate, clear)
}

internal fun ratingActions(rating: Int, enabled: () -> Boolean, rate: (Int) -> Unit, clear: Boolean): List<CustomAccessibilityAction> =
    listOfNotNull(
        CustomAccessibilityAction("Increase rating") { if (!enabled() || rating >= 5) false else { rate(rating + 1); true } },
        CustomAccessibilityAction("Decrease rating") { if (!enabled() || rating <= 0) false else { rate(rating - 1); true } },
        if (clear && rating > 0) CustomAccessibilityAction("Clear rating") { if (!enabled()) false else { rate(0); true } } else null)

/**
 * A page root: TalkBack announces [title] when the page appears (a pane change) and keeps the page's nodes together.
 * The page's header goes first with [pageHeader].
 */
internal fun Modifier.pageSemantics(title: String): Modifier = semantics { paneTitle = title; isTraversalGroup = true }

/** The page header (Back, title, menu, sort, density) is read before the content it is drawn over. */
internal fun Modifier.pageHeader(): Modifier = then(PageHeader)
internal val PageHeader: Modifier = Modifier.semantics { isTraversalGroup = true; traversalIndex = -1f }

/** [pageSemantics] as a value, for call sites that chain with `then`. */
internal fun pageSemanticsOf(title: String): Modifier = Modifier.pageSemantics(title)

/** "3 minutes 46 seconds" for a row label (TalkBack reads "3:46" as "three colon forty-six" on some voices). */
internal fun spokenDuration(seconds: Long?): String? {
    if (seconds == null || seconds < 0) return null
    val h = seconds / 3600; val m = seconds % 3600 / 60; val s = seconds % 60
    fun unit(n: Long, one: String) = "$n $one" + if (n == 1L) "" else "s"
    return listOfNotNull(if (h > 0) unit(h, "hour") else null, if (m > 0) unit(m, "minute") else null,
        if (s > 0 || (h == 0L && m == 0L)) unit(s, "second") else null).joinToString(" ")
}

/**
 * A short status TalkBack says once ("12 results", "Track moved to position 3", "Waiting for connection"): a polite live
 * region on a 1 dp node that draws nothing. Place it in an overlay (a Box beside the visible content) so nothing moves.
 *
 * The node stays mounted and only its description changes: Compose sends a live-region change only for a node that
 * accessibility already knows, so a node added together with its text is never spoken. Hence the text
 * is set [AnnounceSettleMs] after the node first appears, and a change goes through an empty description first (the same
 * words twice are still a change). The text is removed again after [AnnounceHoldMs]: a node without words is no swipe
 * stop (and no unlabeled node for the accessibility checks).
 * A null [text] says nothing.
 */
@Composable
internal fun PoliteAnnouncement(text: String?, tag: String, modifier: Modifier = Modifier) {
    var spoken by remember { mutableStateOf("") }
    var known by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(AnnounceSettleMs); known = true }
    LaunchedEffect(text, known) {
        if (spoken.isNotEmpty()) { spoken = ""; kotlinx.coroutines.delay(AnnounceGapMs) }
        if (text == null || !known) return@LaunchedEffect
        spoken = text
        kotlinx.coroutines.delay(AnnounceHoldMs)
        spoken = ""
    }
    androidx.compose.foundation.layout.Box(modifier.size(1.dp).testTag(tag)
        .semantics { if (spoken.isNotEmpty()) contentDescription = spoken; liveRegion = LiveRegionMode.Polite })
}

/** Long enough for Compose's accessibility pass (every 100 ms) to have seen a newly added announcement node. */
internal const val AnnounceSettleMs = 500L
/** One accessibility pass, so a cleared description and the next text are two changes. */
internal const val AnnounceGapMs = 200L
/** How long an announcement keeps its text (TalkBack reads it when the change arrives). */
internal const val AnnounceHoldMs = 3000L

/** Test hook: forces [rememberAccessibilityOn] (null = ask the system). */
internal val LocalAccessibilityOverride = staticCompositionLocalOf<Boolean?> { null }

/**
 * True while TalkBack or another accessibility service is on, following changes. Used where the screen differs for
 * accessibility users only (the dock never folds; the A–Z chip and letter sheet).
 */
@Composable
internal fun rememberAccessibilityOn(): Boolean {
    LocalAccessibilityOverride.current?.let { return it }
    val manager = LocalContext.current.getSystemService(AccessibilityManager::class.java) ?: return false
    var on by remember(manager) { mutableStateOf(manager.isEnabled) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.AccessibilityStateChangeListener { on = it }
        manager.addAccessibilityStateChangeListener(listener)
        on = manager.isEnabled
        onDispose { manager.removeAccessibilityStateChangeListener(listener) }
    }
    return on
}
