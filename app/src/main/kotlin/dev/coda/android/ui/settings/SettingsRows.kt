package dev.coda.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** "Settings: Grouped, About page" tokens (Dana's page), shared by Settings, About and the preview-only developer rows. */
internal val SettingsText1 = Color(0xFFF1F1EE)
private val Text1 = SettingsText1
internal val SettingsText2 = Text1.copy(alpha = .66f)
internal val SettingsText3 = Text1.copy(alpha = .44f)
/** Dimmed text that still has to be read (the account's "http://"): at least 4.5:1 on the card. */
internal val SettingsTextDim = Text1.copy(alpha = .54f)
internal val SettingsDanger = Color(0xFFE5675E)
internal val SettingsCardColor = Color(0xFF16191D).copy(alpha = .9f)
private val LeadColor = Color(0xFF22252A)
private val Hairline = Color.White.copy(alpha = .06f)

/** The one heading style of the grouped Settings (a section label above a card). */
@Composable
internal fun SettingsGroupLabel(text: String, modifier: Modifier = Modifier, top: Int = 16) {
    Text(text, modifier.padding(start = 24.dp, end = 24.dp, top = top.dp, bottom = 6.dp).semantics { heading() },
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = SettingsText2)
}

/** A rounded card of rows; rows are separated by [SettingsRowDivider]. */
@Composable
internal fun SettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(SettingsCardColor), content = content)
}

@Composable
internal fun SettingsRowDivider() = HorizontalDivider(thickness = 1.dp, color = Hairline)

/** The leading icon tile of a settings row; also used by the Tracks cached ahead slider row so titles line up. */
@Composable
internal fun SettingsIconTile(icon: ImageVector, color: Color = Text1) =
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(LeadColor), Alignment.Center) {
        Icon(icon, null, Modifier.size(20.dp), tint = color)
    }

/**
 * One row: a leading icon tile (or none), a title with an optional subtitle, and a trailing part (switch, chevron, buttons).
 * [onClick] makes the whole row tappable; [chevron] marks a row that opens a page.
 */
@Composable
internal fun SettingsRow(
    title: AnnotatedString, modifier: Modifier = Modifier, icon: ImageVector? = null, subtitle: String? = null,
    danger: Boolean = false, onClick: (() -> Unit)? = null, role: Role = Role.Button, chevron: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val color = if (danger) SettingsDanger else Text1
    Row(modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(role = role, onClick = onClick)
            // A row that only shows information (the account) is read as one item, title and subtitle together.
            else Modifier.semantics(mergeDescendants = true) {})
        .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (icon != null) SettingsIconTile(icon, color)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium), color = color)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, lineHeight = 18.sp),
                color = SettingsText2)
        }
        trailing?.invoke()
        if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = SettingsText3)
    }
}

@Composable
internal fun SettingsRow(
    title: String, modifier: Modifier = Modifier, icon: ImageVector? = null, subtitle: String? = null,
    danger: Boolean = false, onClick: (() -> Unit)? = null, role: Role = Role.Button, chevron: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) = SettingsRow(AnnotatedString(title), modifier, icon, subtitle, danger, onClick, role, chevron, trailing)
