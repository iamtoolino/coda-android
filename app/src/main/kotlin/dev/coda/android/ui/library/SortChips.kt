package dev.coda.android.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.coda.android.ui.theme.AccentState

/**
 * Spec §2 sort chips (the Albums tab's, shared with Playlists): single-select, the selected one kept visible on entry.
 * [trailing] adds items after the chips (Albums' reshuffle chip). Test tags: [listTag] for the row, [chipTag] + name per chip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T : Enum<T>> SortChips(choices: List<T>, sort: T, label: (T) -> String, name: (T) -> String, listTag: String, chipTag: String,
    select: (T) -> Unit, accent: AccentState, trailing: LazyListScope.() -> Unit = {}) {
    val row = rememberLazyListState()
    LaunchedEffect(Unit) { row.animateScrollToItem((sort.ordinal - 1).coerceAtLeast(0)) }
    // T2: a single choice, read as radio buttons in one group (FilterChip says "checkbox").
    LazyRow(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectableGroup().testTag(listTag), state = row,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        items(choices.size, key = { name(choices[it]) }) { i ->
            val choice = choices[i]
            val selected = choice == sort
            FilterChip(selected, { if (!selected) select(choice) }, label = {
                Text(label(choice), fontSize = 14.sp, fontWeight = FontWeight(if (selected) 650 else 500), maxLines = 1)
            }, modifier = Modifier.heightIn(min = 32.dp).semantics { role = Role.RadioButton }.testTag(chipTag + name(choice)), shape = RoundedCornerShape(8.dp),
                leadingIcon = if (selected) { { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) } } else null,
                colors = FilterChipDefaults.filterChipColors(containerColor = Color.Transparent, labelColor = LibText1,
                    selectedContainerColor = accent.container, selectedLabelColor = accent.text, selectedLeadingIconColor = accent.text),
                border = FilterChipDefaults.filterChipBorder(true, selected, borderColor = LibOutline, selectedBorderColor = Color.Transparent,
                    borderWidth = 1.dp, selectedBorderWidth = 0.dp))
        }
        trailing()
    }
}
