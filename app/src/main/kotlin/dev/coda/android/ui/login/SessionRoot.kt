package dev.coda.android.ui.login

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.coda.android.session.*
import dev.coda.android.ui.theme.LoginAccent
import kotlinx.coroutines.launch

/**
 * Session gate for the library shell. An expired session (NeedsLogin) keeps the shell, with its snapshots,
 * navigation and mini player, and adds the re-login sheet and a sign-in banner (spec 2.1 / 12.2). Restoring is
 * the plain background.
 */
@Composable
fun SessionRoot(repository: SessionRepository,
    /** Open Music storage once the shell shows; dropped when nobody is signed in. [musicStorageOpened] clears it. */
    openMusicStorage: Boolean = false, musicStorageOpened: () -> Unit = {}) {
    val state by repository.state.collectAsState()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val current = state
        val shellSession = when (current) {
            is SessionState.Connected -> current.session
            is SessionState.NeedsLogin -> current.session
            else -> null
        }
        // One call site for both states, so the shell and its screen models survive Connected <-> NeedsLogin.
        var dismissed by remember(shellSession?.token) { mutableStateOf(false) }
        // Without an account the app opens normally (the login page), and a later sign-in doesn't jump to storage.
        if (openMusicStorage && shellSession == null && current !is SessionState.Restoring) LaunchedEffect(Unit) { musicStorageOpened() }
        if (shellSession != null) dev.coda.android.ui.LibraryShell(shellSession, openMusicStorage, musicStorageOpened) {
            if (current is SessionState.NeedsLogin) dev.coda.android.ui.theme.LoginTheme { Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().testTag("signInBanner")) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.error, androidx.compose.foundation.shape.CircleShape)
                        .testTag("needsLoginDot").semantics { contentDescription = "Needs login" })
                    Spacer(Modifier.width(8.dp))
                    Text("Signed out · Sign in", Modifier.weight(1f))
                    TextButton(onClick = { dismissed = false }) { Text("Sign in") }
                }
            } }
        }
        // Sign-in surfaces always use the fixed login cyan (was Espresso).
        if (current is SessionState.NeedsLogin) dev.coda.android.ui.theme.LoginTheme { ReloginSheet(repository, current.session, dismissed) { dismissed = it } }
        // While the saved sign-in is read (cold start) only the background shows, so no login page flashes before Home.
        else if (shellSession == null && current !is SessionState.Restoring) dev.coda.android.ui.theme.LoginTheme { LoginScreen(repository) }
    }
}

/**
 * The re-login sheet (spec 12.2): only the password is asked for; server and user stay fixed, and "Change" signs
 * out after a confirmation. Laid out in flow, so longer text or a larger font pushes the fields down instead of
 * overlapping; the header tint is always the login cyan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReloginSheet(repository: SessionRepository, session: Session, dismissed: Boolean, setDismissed: (Boolean) -> Unit) {
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<LoginError?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val autofill = LocalAutofillManager.current
    val config = session.client.configuration
    val host = java.net.URI(config.serverUrl).host
    fun submit() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try { error = repository.login(config.serverUrl, config.username, password) { autofill?.commit() } }
            finally { busy = false }
        }
    }
    if (!dismissed) ModalBottomSheet(onDismissRequest = { setDismissed(true) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), containerColor = Color(0xFF131518),
        scrimColor = Color.Black.copy(alpha = .56f), // Sheets
        dragHandle = { Box(Modifier.padding(top = 10.dp).size(32.dp, 4.dp).background(Color.White.copy(alpha = .3f), RoundedCornerShape(2.dp))) },
        modifier = Modifier.testTag("reloginSheet")) {
        // One frame first, so the field's focus listeners (ring, label colour) are collecting when focus arrives.
        LaunchedEffect(dismissed) { withFrameNanos { }; focus.requestFocus(); keyboard?.show() }
        Column(Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(0f to LoginAccent.surface, 1f to Color(0xFF131518), endY = with(LocalDensity.current) { 220.dp.toPx() }))
            .imePadding().verticalScroll(rememberScrollState()).padding(top = 10.dp, bottom = 28.dp)) {
            Text("SIGN IN AGAIN", Modifier.padding(horizontal = 20.dp).testTag("reloginKicker"), style = kickerStyle(), color = LoginAccent.text)
            Text("Navidrome didn't accept your password", Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp).testTag("reloginTitle"),
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.01).em), color = Text1)
            Text(buildAnnotatedString {
                append("The saved password for ")
                withStyle(SpanStyle(color = Text1, fontWeight = FontWeight.SemiBold)) { append(config.username) }
                append(" on ")
                withStyle(SpanStyle(color = Text1, fontWeight = FontWeight.SemiBold)) { append(host) }
                append(" no longer works. Maybe it was changed on the server.")
            }, Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp).testTag("reloginBody"),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp), color = Text2)
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                LoginField("${config.username} · $host", {}, "Signed in as", Icons.Default.Person, readOnly = true,
                    trailing = {
                        TextButton(onClick = { confirm = true }, enabled = !busy, modifier = Modifier.padding(end = 4.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = LoginAccent.text)) {
                            Text("Change", style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight(650)))
                        }
                    }, modifier = Modifier.testTag("reloginAccount"))
                Spacer(Modifier.height(12.dp))
                LoginField(password, { password = it; error = null }, "Password", Icons.Default.Lock, enabled = !busy,
                    error = error?.message,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailing = { VisibilityToggle(visible, !busy) { visible = !visible } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.testTag("reloginPasswordBlock"),
                    fieldModifier = Modifier.focusRequester(focus).testTag("reloginPassword")
                        .semantics { contentType = ContentType.Password })
                Spacer(Modifier.height(16.dp))
                SignInPill(busy, enabled = !busy, onClick = { submit() }, modifier = Modifier.testTag("reloginSignIn"))
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { setDismissed(true) }, colors = ButtonDefaults.textButtonColors(contentColor = Text2),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("reloginNotNow")) {
                    Text("Not now · keep browsing", style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }
    if (confirm) dev.coda.android.ui.common.SignOutDialog(onDismiss = { confirm = false }, onSignOut = { scope.launch { repository.signOut() } })
}
