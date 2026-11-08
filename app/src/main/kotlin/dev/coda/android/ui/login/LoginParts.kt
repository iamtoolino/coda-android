package dev.coda.android.ui.login

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.coda.android.session.ServerCheck
import dev.coda.android.ui.theme.LoginAccent

// Sign-in look (mockup v3 lg1-login.html): see-through glass fields, cyan roles from LoginTheme, danger for errors.
internal val Text1 = Color(0xFFF1F1EE)
internal val Text2 = Text1.copy(alpha = .66f)
internal val Text3 = Text1.copy(alpha = .44f)
private val Danger = Color(0xFFE5675E)
private val Online = Color(0xFF5BC27A)
/** Caution (tokens.json color.caution, from the logo's gold): the no-encryption notice's icon and ring. */
internal val Caution = Color(0xFFF2C46B)
private val FieldShape = RoundedCornerShape(16.dp)

/** Kicker: 11.5/14 700, +0.12 em, caps. */
@Composable internal fun kickerStyle(): TextStyle = MaterialTheme.typography.labelSmall.copy(
    fontSize = 11.5.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.12.em)

/**
 * A sign-in field: filled, no indicator line, radius 16 (login and re-login only), at least 60 dp tall so it grows
 * with the font. The label is a kicker; [prefix] shows the resolved scheme dimmed. The error sits below, outside the
 * ring, in danger 12 sp; with [animateError] it grows in over 200 ms (the sign-in form grows upward).
 * [locked] keeps the field focused and editable for the IME (the keyboard stays up while signing in) but
 * dims the text; the caller ignores edits meanwhile.
 */
@Composable
internal fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    locked: Boolean = false,
    error: String? = null,
    animateError: Boolean = false,
    prefix: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    fieldModifier: Modifier = Modifier,
) = LoginFieldFrame(label, icon, modifier, readOnly, locked, error, animateError, prefix, interactionSource) { m, colors, decor ->
    // A read-only value (re-login "Signed in as") wraps instead of clipping at large fonts.
    TextField(value, onValueChange, fieldModifier.then(m), enabled = enabled, readOnly = readOnly, singleLine = !readOnly,
        textStyle = decor.textStyle, label = decor.label, leadingIcon = decor.leading, trailingIcon = trailing, prefix = decor.prefix,
        isError = error != null, visualTransformation = visualTransformation, keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions, interactionSource = interactionSource, shape = FieldShape, colors = colors)
}

/** The same field over a [TextFieldValue], so the caller can select the text (wrong password selects it). */
@Composable
internal fun LoginField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    locked: Boolean = false,
    error: String? = null,
    animateError: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    fieldModifier: Modifier = Modifier,
) = LoginFieldFrame(label, icon, modifier, false, locked, error, animateError, null, interactionSource) { m, colors, decor ->
    TextField(value, onValueChange, fieldModifier.then(m), singleLine = true,
        textStyle = decor.textStyle, label = decor.label, leadingIcon = decor.leading, trailingIcon = trailing,
        isError = error != null, visualTransformation = visualTransformation, keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions, interactionSource = interactionSource, shape = FieldShape, colors = colors)
}

private class FieldDecor(
    val textStyle: TextStyle, val label: @Composable () -> Unit, val leading: @Composable () -> Unit, val prefix: (@Composable () -> Unit)?,
)

@Composable
private fun LoginFieldFrame(
    label: String, icon: ImageVector, modifier: Modifier, readOnly: Boolean, locked: Boolean, error: String?, animateError: Boolean,
    prefix: String?, interactionSource: MutableInteractionSource,
    field: @Composable (Modifier, TextFieldColors, FieldDecor) -> Unit,
) {
    val accent = LoginAccent.text
    val focused by interactionSource.collectIsFocusedAsState()
    val ring = when {
        error != null -> BorderSpec(2.dp, Danger)
        readOnly -> BorderSpec(1.dp, Color.White.copy(alpha = .09f))
        focused -> BorderSpec(2.dp, accent)
        else -> BorderSpec(1.dp, Color.White.copy(alpha = .06f))
    }
    // The focus ring moves in 150 ms; MotionDurationScale makes it instant with animations off.
    val ringWidth by animateDpAsState(ring.width, tween(150, easing = LoginStandard), label = "ringWidth")
    val ringColor by animateColorAsState(ring.color, tween(150, easing = LoginStandard), label = "ringColor")
    val glass = if (readOnly) Color.Transparent else Color.White.copy(alpha = .06f)
    val text = if (locked) Text1.copy(alpha = .6f) else Text1
    val colors = TextFieldDefaults.colors(
        focusedContainerColor = LoginAccent.accent.copy(alpha = .12f),
        unfocusedContainerColor = glass, disabledContainerColor = glass, errorContainerColor = Danger.copy(alpha = .07f),
        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent, errorIndicatorColor = Color.Transparent,
        focusedTextColor = text, unfocusedTextColor = text, disabledTextColor = Text1.copy(alpha = .6f), errorTextColor = text,
        focusedLabelColor = accent, unfocusedLabelColor = Text2, disabledLabelColor = Text2, errorLabelColor = Danger,
        focusedLeadingIconColor = accent, unfocusedLeadingIconColor = Text2, disabledLeadingIconColor = Text2, errorLeadingIconColor = Danger,
        focusedTrailingIconColor = Text2, unfocusedTrailingIconColor = Text2, disabledTrailingIconColor = Text2, errorTrailingIconColor = Text2,
        focusedPrefixColor = Text3, unfocusedPrefixColor = Text3, disabledPrefixColor = Text3, errorPrefixColor = Text3,
        cursorColor = accent, errorCursorColor = Danger,
    )
    val decor = FieldDecor(
        MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 21.sp),
        { Text(label.uppercase(), style = kickerStyle()) },
        { Icon(icon, null, Modifier.size(22.dp)) },
        prefix?.let { { Text(it, color = Text3) } },
    )
    Column(modifier) {
        // Locked (signing in): the field keeps focus and the IME connection, so it stays enabled for the input system,
        // but accessibility reports it as unavailable instead of an editable field that silently ignores input.
        val busySemantics = if (locked) Modifier.semantics { disabled(); stateDescription = "Unavailable while signing in" } else Modifier
        // L1: the field reports its error, and the message is said when it appears (polite live region).
        val errorSemantics = if (error != null) Modifier.semantics { error(error) } else Modifier
        // The message is said by an announcement in an overlay that stays mounted: the text below appears with its parent,
        // and a live region on a node that is new is never spoken.
        Box {
            field(Modifier.fillMaxWidth().heightIn(min = 60.dp).border(ringWidth, ringColor, FieldShape).then(busySemantics).then(errorSemantics), colors, decor)
            dev.coda.android.ui.common.PoliteAnnouncement(error, "fieldErrorAnnouncement")
        }
        val errorText: @Composable (String) -> Unit = {
            Text(it, color = Danger, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp))
        }
        if (animateError) {
            // Keep the last message while it collapses.
            var last by remember { mutableStateOf(error.orEmpty()) }
            if (error != null) last = error
            AnimatedVisibility(error != null, enter = expandVertically(tween(200, easing = LoginStandard)) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(200, easing = LoginStandard)) + fadeOut(tween(200))) { errorText(last) }
        } else if (error != null) errorText(error)
    }
}

private data class BorderSpec(val width: androidx.compose.ui.unit.Dp, val color: Color)

/** Password visibility toggle, a 48 dp target. */
@Composable
internal fun VisibilityToggle(visible: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle, enabled = enabled, modifier = Modifier.padding(end = 4.dp)) {
        Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
            if (visible) "Hide password" else "Show password", Modifier.size(22.dp))
    }
}

/**
 * Full-width "Sign in >" pill: 56 dp, the login fill with its on-fill label (white on cyan, 5.0:1). [anyway] makes
 * it "Sign in anyway" with an open lock (TalkBack: "Sign in anyway, without encryption"); while [busy], [busyLabel] is
 * the spinner's description ("Checking server" until the sign-in request is sent, then "Signing in").
 */
@Composable
internal fun SignInPill(
    busy: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    anyway: Boolean = false, busyLabel: String = "Signing in",
) {
    val fill = LoginAccent.fill
    val onFill = LoginAccent.onFill
    Button(onClick = onClick, enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = fill, contentColor = onFill,
            disabledContainerColor = fill.copy(alpha = .6f), disabledContentColor = onFill.copy(alpha = .8f)),
        shape = CircleShape, contentPadding = PaddingValues(horizontal = 24.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp)
            .shadow(24.dp, CircleShape, ambientColor = Color.Black.copy(alpha = .4f), spotColor = Color.Black.copy(alpha = .4f))
            .then(if (anyway && !busy) Modifier.semantics { contentDescription = "Sign in anyway, without encryption" } else Modifier)) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp)
            .semantics { contentDescription = busyLabel; liveRegion = LiveRegionMode.Polite }.testTag("signInProgress"),
            strokeWidth = 2.dp, color = onFill)
        else {
            Text(if (anyway) "Sign in anyway" else "Sign in",
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.width(if (anyway) 8.dp else 4.dp))
            if (anyway) Icon(Icons.Default.LockOpen, null, Modifier.size(20.dp))
            else Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(22.dp))
        }
    }
}

/**
 * Notice above Sign in (design C): open lock in [Caution], 13/18 text, glass card with a 1 dp caution ring. A polite
 * live region that says what happened ("Not signed in yet. <host> has no encryption. …"); focus stays on the pill.
 */
@Composable
internal fun HttpNotice(host: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    val body = "Your password is sent unencrypted, and others on this network could read it."
    Row(modifier.fillMaxWidth()
        .background(Color(0xFFF4B640).copy(alpha = .08f), shape).border(1.dp, Color(0xFFF4B640).copy(alpha = .32f), shape)
        .padding(start = 12.dp, end = 14.dp, top = 10.dp, bottom = 10.dp)
        .testTag("httpNotice")
        .clearAndSetSemantics { contentDescription = "Not signed in yet. $host has no encryption. $body"; liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.LockOpen, null, Modifier.size(20.dp), tint = Caution)
        Text(buildAnnotatedString {
            withStyle(SpanStyle(color = Text1, fontWeight = FontWeight.Bold)) { append("No encryption.") }
            append(" ")
            append(body)
        }, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp), color = Text2)
    }
}

/**
 * The server chip: checking, found (check only), plain http (open lock), not reachable. A certificate this
 * phone does not trust is information, not an alarm: a quiet info button ([onInfo] opens the explanation under the
 * field, see [ServerNoteLine]); a non-Subsonic answer has no chip at all.
 */
@Composable
internal fun ServerChip(check: ServerCheck?, onInfo: () -> Unit = {}) {
    val label = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold)
    @Composable fun chip(color: Color, content: @Composable RowScope.() -> Unit) = Row(
        Modifier.padding(end = 16.dp).testTag("serverChip"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) { CompositionLocalProvider(LocalContentColor provides color) { content() } }
    when {
        check == null -> Unit
        check is ServerCheck.Checking -> chip(Text3) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Text2, trackColor = Text1.copy(alpha = .18f))
            Text("Checking…", style = label.copy(fontWeight = FontWeight.Medium))
        }
        check is ServerCheck.Found && check.subsonic && isUnencryptedServer(check.url) -> chip(Text2) {
            Icon(Icons.Default.LockOpen, null, Modifier.size(16.dp))
            Text("http", style = label, modifier = Modifier.semantics { contentDescription = "Unencrypted connection, http" })
        }
        check is ServerCheck.Found && check.subsonic -> chip(Online) {
            Icon(Icons.Default.CheckCircle, "Server found", Modifier.size(20.dp))
        }
        serverNote(check) == ServerNote.CertificateNotTrusted -> IconButton(onInfo, Modifier.padding(end = 4.dp).testTag("serverChip")) {
            Icon(Icons.Outlined.Info, "${ServerNote.CertificateNotTrusted.text}. Show why", Modifier.size(22.dp), tint = Text2)
        }
        serverNote(check) == ServerNote.NoServerYet -> Unit
        else -> chip(Danger) {
            Icon(Icons.Default.Error, null, Modifier.size(18.dp))
            Text("Not reachable", style = label)
        }
    }
}

/**
 * The calm line under Server for [ServerNote]: Text2 12 sp, no error colour. The certificate note opens a short
 * explanation when tapped ([open], [onToggle]); the field's info button does the same with a 48 dp target.
 */
@Composable
internal fun ServerNoteLine(note: ServerNote?, open: Boolean, onToggle: () -> Unit) {
    var last by remember { mutableStateOf(note ?: ServerNote.NoServerYet) }
    if (note != null) last = note
    val style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp)
    AnimatedVisibility(note != null, enter = expandVertically(tween(200, easing = LoginStandard)) + fadeIn(tween(200)),
        exit = shrinkVertically(tween(200, easing = LoginStandard)) + fadeOut(tween(200))) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp).testTag("serverNote")) {
            val tappable = last == ServerNote.CertificateNotTrusted
            Text(last.text, color = Text2, style = style,
                modifier = Modifier.then(if (tappable) Modifier.clickable(onClickLabel = "Show why", onClick = onToggle) else Modifier))
            if (tappable && open) Text(CertificateExplanation, color = Text3, style = style,
                modifier = Modifier.padding(top = 2.dp).testTag("serverNoteMore"))
        }
    }
}

/** True when the server address uses plain http (allowed, but shown; The http chip). */
internal fun isUnencryptedServer(input: String): Boolean = input.trim().startsWith("http://", ignoreCase = true)
