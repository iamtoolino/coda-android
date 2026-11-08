package dev.coda.android.ui.library

import androidx.annotation.PluralsRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.coda.android.ui.common.decorative
import dev.coda.android.ui.common.itemSemantics
import dev.coda.android.ui.theme.AccentState
import dev.coda.core.library.LibrarySection
import kotlinx.coroutines.launch
import java.text.NumberFormat

/*
 * The A–Z letter sheet, the TalkBack way to the fast scroller's jumps. PO
 * 2026-11-01: the "A–Z" chip and the sheet show only while an accessibility service is on, for Albums sorted by Title and
 * for Artists; everyone else sees the screen as before.
 */

/** One key of the sheet: "#" then A–Z, always all 27 so letters never move; [section] is -1 for a letter with no entries. */
internal data class LetterKey(val letter: String, val section: Int, val count: Int)

internal val SheetLetters: List<String> = listOf("#") + ('A'..'Z').map { it.toString() }

internal fun letterKeys(sections: List<LibrarySection>): List<LetterKey> = SheetLetters.map { letter ->
    val index = sections.indexOfFirst { it.label == letter }
    LetterKey(letter, index, if (index >= 0) sections[index].count else 0)
}

/** The key's small count: "34", "1.2k" from 1,000. */
internal fun keyCount(count: Int): String = if (count < 1000) "$count" else {
    val tenths = (count + 50) / 100
    if (tenths % 10 == 0) "${tenths / 10}k" else "${tenths / 10}.${tenths % 10}k"
}

/** "#" is said "Numbers and symbols". */
internal fun spokenLetter(letter: String): String = if (letter == "#") "Numbers and symbols" else letter

/**
 * After a jump, focus moves to the section's header (Artists in gutter mode: its first row): [choose] marks it when a key is
 * picked, [focusChosen] moves focus there once the sheet has been dismissed and its window is gone (while the sheet window
 * closes, TalkBack places focus by itself). Input focus (keyboard) and TalkBack's focus are both moved: TalkBack does not
 * follow a programmatic input-focus change here, so the item's accessibility node is asked for accessibility focus, as
 * `View.requestAccessibilityFocus` does for a view. [target] makes that one item focusable only until focus leaves it, so a
 * keyboard user does not meet an extra stop later.
 */
@Stable
internal class JumpFocus(private val view: android.view.View) {
    private val requester = FocusRequester()
    private var index by mutableIntStateOf(-1)
    private var chosen = -1
    private var held = false
    fun target(itemIndex: Int): Modifier = if (index != itemIndex) Modifier else Modifier.focusRequester(requester)
        .onFocusChanged { if (it.isFocused) held = true else if (held) { held = false; index = -1 } }
        .focusable().semantics { this[JumpTarget] = Unit }
    fun choose(itemIndex: Int) { chosen = itemIndex }
    suspend fun focusChosen() {
        val item = chosen.takeIf { it >= 0 } ?: return
        chosen = -1; held = false; index = item
        kotlinx.coroutines.delay(JumpFocusDelayMs)
        runCatching { requester.requestFocus() }
        withFrameNanos { }; withFrameNanos { }
        val node = (view as? androidx.compose.ui.node.RootForTest)?.semanticsOwner?.getAllSemanticsNodes(mergingEnabled = true)
            ?.firstOrNull { JumpTarget in it.config }
        if (node != null) view.accessibilityNodeProvider?.performAction(node.id,
            android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        if (!held) index = -1
    }
}

private val JumpTarget = SemanticsPropertyKey<Unit>("JumpTarget")

/** After the sheet's dismissal: its window is removed and TalkBack has settled on the list window. */
private const val JumpFocusDelayMs = 600L

private val SheetColor = Color(0xFF131518)
private val KeyFill = Color.White.copy(alpha = .075f)

/**
 * The entry: an outlined 32 dp pill "A–Z" (13 sp / 600, text2) in a 48 dp tall touch box, at the end of the title row.
 * [open] keeps the pressed look while the sheet is open.
 */
@Composable
internal fun AzChip(open: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val fontScale = LocalDensity.current.fontScale
    val shape = RoundedCornerShape(16.dp)
    Box(modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).testTag("azChip")
        .itemSemantics("Jump to letter", onClick = onClick, role = Role.Button)
        .clickable(source, indication = null, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.heightIn(min = 32.dp).clip(shape).border(1.dp, LibOutline, shape)
            .background(if (pressed || open) Color.White.copy(alpha = .10f) else Color.Transparent)
            .indication(source, ripple()).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            // The label grows with the font up to 1.3×, then stays.
            Text("A–Z", fontSize = (13f * if (fontScale > 1.3f) 1.3f / fontScale else 1f).sp, fontWeight = FontWeight(600),
                letterSpacing = .13.sp, color = if (pressed || open) LibText1 else LibText2, maxLines = 1)
        }
    }
}

/** The chip, faded in and out (150 ms) as its list gains or loses its sections (e.g. while the Artists filter has text). */
@Composable
internal fun AzChipSlot(visible: Boolean, open: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible, modifier, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) { AzChip(open, onClick) }
}

/**
 * Spec §5.6 sheet with a 6-column grid of 48 dp letter keys. [current] is the pinned section (the first while the list is
 * at the top). [pick] receives a section index: the caller jumps there at once; the sheet then closes over the moved list.
 * [plural] names the entries ("41 artists", "1,904 albums").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LetterSheet(sections: List<LibrarySection>, current: Int, total: Int, @PluralsRes plural: Int, accent: AccentState,
    pick: (Int) -> Unit, dismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val tick = rememberTick()
    val keys = remember(sections) { letterKeys(sections) }
    val fontScale = LocalDensity.current.fontScale
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = state, containerColor = SheetColor, contentColor = LibText1,
        // The handle is drawn in the content (decorative, review U2): M3's handle slot would add an unlabelled 22 dp control.
        dragHandle = null,
        modifier = Modifier.testTag("letterSheet").semantics { paneTitle = "Jump to letter" }) {
        Column(Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(accent.surface, SheetColor), endY = with(LocalDensity.current) { 220.dp.toPx() }))
            .verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
            Box(Modifier.fillMaxWidth().height(22.dp).decorative()) {
                Box(Modifier.align(Alignment.TopCenter).padding(top = 11.dp).size(32.dp, 4.dp)
                    .background(Color.White.copy(alpha = .30f), RoundedCornerShape(2.dp)))
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp).semantics(mergeDescendants = true) { heading() },
                verticalAlignment = Alignment.CenterVertically) {
                Text("Jump to letter", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight(600), color = LibText1, maxLines = 1)
                Text(pluralStringResource(plural, total, total).replace("$total", NumberFormat.getIntegerInstance().format(total)),
                    fontSize = 12.5.sp, color = LibText2, style = Tabular, maxLines = 1)
            }
            BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
                // 6 columns of 48 dp; wider phones grow the gaps, never the keys.
                val gap = ((maxWidth - 48.dp * 6) / 5).coerceAtLeast(4.dp)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    keys.chunked(6).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            row.forEach { key ->
                                LetterKeyCell(key, key.section >= 0 && key.section == current.coerceAtLeast(0), plural, accent, fontScale) {
                                    tick()
                                    pick(key.section)
                                    scope.launch { state.hide() }.invokeOnCompletion { dismiss() }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LetterKeyCell(key: LetterKey, current: Boolean, @PluralsRes plural: Int, accent: AccentState, fontScale: Float, choose: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val empty = key.section < 0
    val spoken = spokenLetter(key.letter) + ", " + pluralStringResource(plural, key.count, key.count)
    // Letters cap at 1.5× the font; above 1.3× the count line is dropped (TalkBack still says it).
    val letterSp = 17f * if (fontScale > 1.5f) 1.5f / fontScale else 1f
    Column(Modifier.width(48.dp).heightIn(min = 48.dp).testTag("letterKey-${key.letter}")
        .then(if (empty) Modifier.clearAndSetSemantics { hideFromAccessibility(); disabled() } else Modifier
            .itemSemantics(spoken, onClick = choose, selected = current, role = Role.Button))
        .clip(shape)
        .then(when {
            empty -> Modifier
            current -> Modifier.background(accent.container).border(1.dp, accent.text.copy(alpha = .35f), shape)
            else -> Modifier.background(KeyFill)
        })
        .then(if (empty) Modifier else Modifier.clickable(interactionSource = null, indication = ripple(color = Color.White), onClick = choose)),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(key.letter, fontSize = letterSp.sp, lineHeight = (20f * letterSp / 17f).sp, fontWeight = FontWeight(700), maxLines = 1,
            color = when { empty -> LibText3.copy(alpha = LibText3.alpha * .6f); current -> accent.text; else -> LibText1 })
        if (!empty && fontScale <= 1.3f) Text(keyCount(key.count), fontSize = 10.5.sp, lineHeight = 12.sp, style = Tabular, maxLines = 1,
            color = if (current) accent.text.copy(alpha = .70f) else LibText3)
    }
}
