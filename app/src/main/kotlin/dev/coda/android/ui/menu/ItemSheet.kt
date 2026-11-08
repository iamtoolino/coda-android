package dev.coda.android.ui.menu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.coda.android.ui.album.playFillArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.coda.android.ui.album.AddToQueueIcon
import dev.coda.android.ui.common.decorative
import dev.coda.android.ui.common.ratingRowSemantics
import dev.coda.android.ui.album.PlayNextIcon

internal val MenuText1 = Color(0xFFF1F1EE)
internal val MenuText2 = Color(0xFFF1F1EE).copy(alpha = .66f)
internal val MenuText3 = Color(0xFFF1F1EE).copy(alpha = .44f)
internal val MenuDanger = Color(0xFFE5675E)
private val SheetColor = Color(0xFF131518)
private val Hairline = Color.White.copy(alpha = .06f)

/** The accent roles a menu draws with (the page accent where it opens). */
data class MenuColors(val fill: Color, val onFill: Color, val text: Color)

/** The same icon for an action everywhere. */
internal fun MenuAction.icon(): ImageVector = when (this) {
    MenuAction.PLAY -> Icons.Filled.PlayArrow
    MenuAction.BEST_OF -> Icons.Outlined.StarOutline
    MenuAction.NEWEST -> Icons.Outlined.NewReleases
    MenuAction.SHUFFLE -> Icons.Filled.Shuffle
    MenuAction.PLAY_NEXT -> PlayNextIcon
    MenuAction.ADD_TO_QUEUE -> AddToQueueIcon
    MenuAction.ADD_TO_PLAYLIST -> Icons.AutoMirrored.Filled.PlaylistAdd
    MenuAction.KEEP_OFFLINE -> Icons.Outlined.DownloadForOffline
    MenuAction.GO_TO_ALBUM -> Icons.Outlined.Album
    MenuAction.GO_TO_ARTIST -> Icons.Outlined.Person
    MenuAction.RENAME -> Icons.Outlined.Edit
    MenuAction.DELETE -> Icons.Outlined.Delete
    MenuAction.RESUME -> Icons.Filled.History
    MenuAction.APPEND_REMAINING -> AddToQueueIcon
    MenuAction.REMOVE_FROM_PLAYLIST -> Icons.Outlined.RemoveCircleOutline
}

/** The Keep offline switch row's state: the toggle and its quiet line. */
data class KeepOfflineRow(val kept: Boolean, val line: String?, val enabled: Boolean,
    val toggle: (Boolean) -> Unit)

/**
 * "Sheet: Grouped buttons": header (cover, title, meta), the album rating row, one row of up to
 * three buttons for the play actions, then the list in hairline groups. One component for every item type.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ItemSheet(
    spec: MenuSpec,
    colors: MenuColors,
    onDismiss: () -> Unit,
    onAction: (MenuAction) -> Unit,
    header: @Composable () -> Unit,
    enabled: (MenuAction) -> Boolean = { true },
    rating: (@Composable () -> Unit)? = null,
    keepOffline: KeepOfflineRow? = null,
    tag: String = "itemSheet",
    /** The sheet's own snackbar: the sheet is a window above the shell, so a notice from inside it shows here. */
    snackbar: SnackbarHostState? = null,
    /** An entry's secondary line (Resume album's "Track 5 · Title"). */
    detail: (MenuAction) -> String? = { null },
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SheetColor, contentColor = MenuText1, modifier = Modifier.testTag(tag)) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 8.dp)) {
                header()
                if (spec.rating && rating != null) rating()
                if (spec.buttons.isNotEmpty()) ButtonRow(spec.buttons, colors, enabled, onAction)
                spec.groups.forEachIndexed { index, group ->
                    if (index > 0 || spec.buttons.isEmpty()) HorizontalDivider(Modifier.padding(vertical = 4.dp), thickness = 1.dp, color = Hairline)
                    else Spacer(Modifier.height(8.dp))
                    group.forEach { action ->
                        if (action == MenuAction.KEEP_OFFLINE && keepOffline != null) KeepOfflineSwitch(keepOffline, colors)
                        else EntryRow(action, enabled(action), detail(action)) { onAction(action) }
                    }
                }
            }
            if (snackbar != null) dev.coda.android.ui.common.CodaSnackbarHost(snackbar,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().testTag("sheetSnackbar"))
        }
    }
}

/** Header 64 dp: cover 56 (radius 10), title 16/600, meta 12.5 in text2. */
@Composable
internal fun SheetHeader(title: String, meta: String?, cover: @Composable (Modifier) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 64.dp).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically) {
        cover(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)))
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, Modifier.testTag("sheetTitle"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MenuText1,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!meta.isNullOrBlank()) Text(meta, fontSize = 12.5.sp, color = MenuText2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    Spacer(Modifier.height(8.dp))
}

/**
 * Album rating row 48 dp: five 24 dp stars; tap a star to rate, the same star again to clear. Albums only. A11Y-1 (Dana
 * item 3): five contiguous 48 × 48 cells (padding 20 start / 8 end), one adjustable TalkBack control ("Album rating, 3 of 5
 * stars") with Increase/Decrease/Clear; above 1.3× font the label sits on its own line.
 */
@Composable
internal fun RatingRow(rating: Int, colors: MenuColors, rate: (Int) -> Unit) {
    val stacked = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
    val label: @Composable (Modifier) -> Unit = { modifier -> Text("Album rating", modifier.decorative(), fontSize = 14.sp, color = MenuText2, maxLines = 1) }
    val stars: @Composable () -> Unit = {
        (1..5).forEach { star ->
            val filled = star <= rating
            // The 48 dp cell takes the touch; the press ripple stays main's bounded one on the 40 dp box around the star.
            val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Box(Modifier.size(48.dp).clickable(interactionSource = press, indication = null) {
                    rate(if (star == rating) 0 else star) }
                .decorative().testTag("sheetStar-$star"), contentAlignment = Alignment.Center) {
                Box(Modifier.size(40.dp).indication(press, androidx.compose.foundation.LocalIndication.current), contentAlignment = Alignment.Center) {
                    Icon(if (filled) Icons.Filled.Star else Icons.Outlined.StarOutline, null, Modifier.size(24.dp),
                        tint = if (filled) colors.text else MenuText3)
                }
            }
        }
    }
    val row = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp).testTag("sheetRating")
        .ratingRowSemantics("Album rating", rating, enabled = { true }, rate = rate, clear = true)
    if (stacked) Column(row.padding(top = 8.dp)) {
        label(Modifier)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) { stars() }
    } else Row(row.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        label(Modifier.weight(1f))
        stars()
    }
}

/** Row: 3 equal buttons, 76 dp tall, radius 18, 8 dp gaps in the 16 dp gutters; the first is the filled main action. */
@Composable
private fun ButtonRow(actions: List<MenuAction>, colors: MenuColors, enabled: (MenuAction) -> Boolean, onAction: (MenuAction) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("sheetButtons"),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.forEachIndexed { index, action ->
            val main = index == 0
            val on = enabled(action)
            val shape = RoundedCornerShape(18.dp)
            // A Surface draws the button's container itself (a background modifier on a row whose items turned enabled
            // after the sheet's content loaded was not drawn on the device).
            Surface(onClick = { onAction(action) }, enabled = on, shape = shape,
                color = if (main) colors.fill else colors.fill.copy(alpha = .22f),
                border = if (main) null else BorderStroke(1.dp, colors.text.copy(alpha = .10f)),
                modifier = Modifier.weight(1f).height(76.dp)
                    .semantics(mergeDescendants = true) { if (main) playFillArgb = colors.fill.toArgb() }
                    .testTag(action.tag)) {
                Column(Modifier.fillMaxSize().alpha(if (on) 1f else .38f),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(action.icon(), null, Modifier.size(24.dp), tint = if (main) colors.onFill else colors.text)
                    Spacer(Modifier.height(6.dp))
                    Text(action.label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (main) colors.onFill else MenuText1, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
        }
    }
}

/** Entries 48 dp: icon 24 in text2 at 20 dp, label 15 at 64 dp. Delete is red (no Undo). */
@Composable
private fun EntryRow(action: MenuAction, enabled: Boolean, detail: String? = null, onClick: () -> Unit) {
    val danger = action == MenuAction.DELETE
    // With a secondary line: 64 dp and 12.5 sp text2, as the Prefer offline row.
    Row(Modifier.fillMaxWidth().heightIn(min = if (detail != null) 64.dp else 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .alpha(if (enabled) 1f else .38f).padding(horizontal = 20.dp).testTag(action.tag), verticalAlignment = Alignment.CenterVertically) {
        Icon(action.icon(), null, Modifier.size(24.dp), tint = if (danger) MenuDanger else MenuText2)
        Spacer(Modifier.width(20.dp))
        if (detail == null) Text(action.label, fontSize = 15.sp, color = if (danger) MenuDanger else MenuText1)
        else Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(action.label, fontSize = 15.sp, color = MenuText1)
            Text(detail, fontSize = 12.5.sp, color = MenuText2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Keep offline 64 dp: one switch and the line under it. The icon turns accent while on. Switching
 * off removes the set without asking (the caller). Switching on again keeps the whole set and downloads what is missing.
 */
@Composable
private fun KeepOfflineSwitch(row: KeepOfflineRow, colors: MenuColors) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
        .toggleable(row.kept, enabled = row.enabled, role = Role.Switch, onValueChange = row.toggle)
        .alpha(if (row.enabled) 1f else .38f).padding(horizontal = 20.dp).testTag(MenuAction.KEEP_OFFLINE.tag),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(MenuAction.KEEP_OFFLINE.icon(), null, Modifier.size(24.dp), tint = if (row.kept) colors.text else MenuText2)
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(MenuAction.KEEP_OFFLINE.label, fontSize = 15.sp, color = MenuText1)
            if (row.line != null) Text(row.line, Modifier.testTag("keepOfflineLine"), fontSize = 12.5.sp, color = MenuText2)
        }
        Switch(row.kept, null, enabled = row.enabled, colors = SwitchDefaults.colors(checkedTrackColor = colors.fill,
            checkedThumbColor = colors.onFill, checkedBorderColor = colors.fill))
    }
}
