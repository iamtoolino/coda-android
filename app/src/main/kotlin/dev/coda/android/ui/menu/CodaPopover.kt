package dev.coda.android.ui.menu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Popover: Studio compact", the one popover component for app chrome menus (Queue ⋮):
 * one accent-tinted container (radius 18, 248 dp), 48 dp rows with a 20 dp icon, hairline groups. A running action
 * gets a 64 dp row with a bar and a stop button ([PopoverProgressItem]).
 */
@Composable
internal fun CodaPopover(expanded: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit) {
    DropdownMenu(expanded, onDismiss, modifier.width(248.dp).testTag("popover"), shape = RoundedCornerShape(18.dp),
        containerColor = MaterialTheme.colorScheme.surfaceTint, border = BorderStroke(1.dp, Color.White.copy(alpha = .08f)),
        tonalElevation = 0.dp, shadowElevation = 8.dp, content = content)
}

@Composable
internal fun PopoverItem(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    danger: Boolean = false, line: String? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = if (line == null) 48.dp else 64.dp)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        val tint = if (danger) MenuDanger else MenuText2
        Icon(icon, null, Modifier.size(20.dp).alpha(if (enabled) 1f else .5f), tint = tint)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).alpha(if (enabled) 1f else .5f)) {
            Text(label, fontSize = 15.sp, color = if (danger) MenuDanger else MenuText1)
            if (line != null) Text(line, fontSize = 12.5.sp, color = MenuText2)
        }
    }
}

@Composable
internal fun PopoverDivider() = HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), thickness = 1.dp,
    color = Color.White.copy(alpha = .09f))

/** The running download row: accent label, a 3 dp bar (or a second line), and × to stop it. */
@Composable
internal fun PopoverProgressItem(label: String, icon: ImageVector, progress: Float?, line: String?, stopLabel: String, onStop: () -> Unit,
    modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = accent)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp, color = accent, fontWeight = FontWeight.SemiBold)
            if (line != null && line.isNotEmpty()) Text(line, fontSize = 12.5.sp, color = MenuText2)
            else if (progress != null) LinearProgressIndicator({ progress }, Modifier.padding(top = 6.dp).fillMaxWidth().height(3.dp),
                color = accent, trackColor = Color.White.copy(alpha = .12f), drawStopIndicator = {}, gapSize = 0.dp)
        }
        IconButton(onClick = onStop, Modifier.testTag("popoverStop")) { Icon(Icons.Filled.Close, stopLabel, tint = MenuText2) }
    }
}
