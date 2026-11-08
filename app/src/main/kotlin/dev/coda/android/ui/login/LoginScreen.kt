package dev.coda.android.ui.login

import android.text.format.DateUtils
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import dev.coda.android.CodaApp
import dev.coda.android.R
import dev.coda.android.session.*
import dev.coda.android.ui.theme.Base
import dev.coda.android.ui.theme.CornerGlow
import dev.coda.android.ui.theme.LoginAccent
import dev.coda.core.subsonic.ServerProbe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Cold sign-in (mockup v3): bar-C logo, "Coda" wordmark and tagline over Home's corner glow in cyan; the form is
 * anchored to the bottom with a flexible gap above Server, so inline errors grow upward and Sign in never moves.
 *
 * The keyboard's own inset animation is the clock. The rest frame is laid out once; each frame only
 * moves layers (the form lifts so Sign in ends 16 dp above the keyboard, the logo and wordmark become a lockup above
 * Server, the tagline fades). When the form doesn't fit above the keyboard the brand fades and the lift is a scroll
 * driven by the same progress, so the user can drag. The window never pans or resizes for the keyboard
 * (`adjustResize` under edge-to-edge only dispatches insets).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LoginScreen(repository: SessionRepository) {
    val recentServers = (LocalContext.current.applicationContext as CodaApp).recentServers
    val recents by recentServers.servers.collectAsState()
    val plainHttpConsent = (LocalContext.current.applicationContext as CodaApp).plainHttpConsent
    // The address as typed; a scheme picked from Recent servers is kept apart and shown dimmed, like a resolved one.
    var server by remember { mutableStateOf("") }
    var pinnedScheme by remember { mutableStateOf<String?>(null) }
    var username by remember { mutableStateOf("") }
    // Never save passwords in the activity instance-state Bundle.
    var password by remember { mutableStateOf(TextFieldValue("")) }
    var visible by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // Busy, and the sign-in request is about to be sent (until then the spinner says "Checking server").
    var sending by remember { mutableStateOf(false) }
    // The no-encryption notice for this address; while it shows, a press (after the guard) is the user's consent.
    var armed by remember { mutableStateOf<HttpNoticeState?>(null) }
    var error by remember { mutableStateOf<LoginError?>(null) }
    var menuDismissed by remember { mutableStateOf(false) }
    // The certificate note's explanation is open.
    var certificateMoreOpen by remember { mutableStateOf(false) }
    var prefilled by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // One check per address, shared by the field and Sign in, so what is shown is what gets authenticated.
    val checks = remember(scope, repository) { ServerCheckRunner(scope, repository::checkServer) }
    val autofill = LocalAutofillManager.current
    val focusManager = LocalFocusManager.current
    val passwordFocus = remember { FocusRequester() }
    val serverSource = remember { MutableInteractionSource() }
    val usernameSource = remember { MutableInteractionSource() }
    val passwordSource = remember { MutableInteractionSource() }
    val serverFocused by serverSource.collectIsFocusedAsState()
    val usernameFocused by usernameSource.collectIsFocusedAsState()

    fun pick(recent: RecentServer) {
        server = recent.url.substringAfter("://")
        pinnedScheme = recent.url.substringBefore("://")
        error = null
    }
    LaunchedEffect(recents) {
        if (!prefilled && recents.isNotEmpty()) {
            prefilled = true
            if (server.isEmpty()) pick(recents.first())
        }
    }
    val input = (if (ServerProbe.hasScheme(server) || pinnedScheme == null) server else "$pinnedScheme://$server").trim()
    // Check on focus loss or ServerCheckDebounceMillis after typing stops (never for incomplete input); a new address
    // cancels the old check. Editing Server clears the notice and the consent given on this screen.
    val notice = armed?.takeIf { it.input == input }
    LaunchedEffect(input) {
        if (armed?.input != input) armed = null
        certificateMoreOpen = false
        checks.checkAfterPause(input, snapshotFlow { !serverFocused })
    }
    val current = checks.shown?.takeIf { it.first == input }?.second
    val prefix = when {
        server.isBlank() || ServerProbe.hasScheme(server) -> null
        pinnedScheme != null -> "$pinnedScheme://"
        current is ServerCheck.Found -> current.url.substringBefore("://") + "://"
        else -> null
    }

    // The keyboard stays up while signing in (no down-and-up bounce on a wrong password).
    fun submit() {
        if (busy) return
        val shown = notice
        // Double-press guard (an input guard, so also with "Remove animations"): Sign in and Done are ignored for
        // 600 ms after the notice appears, so a double tap or mashed Done cannot skip it.
        if (shown != null && android.os.SystemClock.uptimeMillis() - shown.shownAt < NoticeGuardMillis) return
        busy = true
        sending = false
        error = null
        val address = input
        scope.launch {
            try {
                // Without a scheme, sign in only against the resolved scheme once it is on screen (http shows its chip
                // first); plain http only after the user agreed: earlier (stored) or now, by pressing "Sign in anyway".
                val target = signInTarget(address, current, { checks.request(it, retryFailed = true).await() },
                    consented = { url -> (shown?.input == address && shown.url == url) || agreedBefore(plainHttpConsent, url) }) {
                    withFrameNanos { }
                    withFrameNanos { }
                }
                when (target) {
                    is SignInTarget.Refused -> error = target.error
                    is SignInTarget.NeedsConsent ->
                        armed = HttpNoticeState(address, target.url, hostOf(target.url), android.os.SystemClock.uptimeMillis())
                    // Commit before the session is published, while the fields are still composed, so the password manager offers to save.
                    is SignInTarget.Url -> {
                        sending = true
                        error = repository.login(target.url, username, password.text) { autofill?.commit() }
                    }
                }
            } finally { busy = false; sending = false }
            // Focus touches views: be explicit about the main thread (the test clock may resume this on the login thread).
            if (error?.field == LoginField.Password) withContext(Dispatchers.Main.immediate) {
                password = password.copy(selection = TextRange(0, password.text.length))
                passwordFocus.requestFocus()
            }
        }
    }

    val density = LocalDensity.current
    val motion = rememberKeyboardMotion()
    // The keyboard went away (back, swipe, a tap outside): clear focus so the page returns to exactly the rest frame.
    // Driven by the IME window's real visibility (shown -> hidden), read from the window insets on every traversal, not
    // by its inset: a floating or stylus (handwriting) keyboard has no inset but is in use and keeps its field focused,
    // and dismissing one that started floating still clears focus. (Compose's isImeVisible did not follow every
    // show/hide on the test emulator.)
    val view = LocalView.current
    DisposableEffect(view) {
        // Event-driven on the main looper: re-evaluated whenever the IME gains or loses insets control (also for floating
        // keyboards, which never redraw the app on hide) and, throttled to every 100 ms, on pre-draw (a hide ends after
        // the last animation frame). A show can pass through a brief hidden state (seen on device, ~90 ms), so only a
        // hide that lasts 300 ms counts. A hide because this window lost focus (app in the background, a password
        // manager's or autofill sheet, another window in split screen) keeps the field focused.
        fun readShown() = view.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
        var shown = readShown()
        var readAt = android.os.SystemClock.uptimeMillis()
        val recheck = Runnable { if (!readShown() && view.hasWindowFocus()) focusManager.clearFocus() }
        lateinit var trailing: Runnable
        fun onChange(force: Boolean) {
            val now = android.os.SystemClock.uptimeMillis()
            if (!force && now - readAt < 100) {
                // rootWindowInsets allocates and pre-draw is per frame: at most one read per 100 ms, but never drop the
                // last change (a hide ends after the last frame): one trailing read.
                view.removeCallbacks(trailing); view.postDelayed(trailing, 100 - (now - readAt)); return
            }
            view.removeCallbacks(trailing)
            readAt = now
            val was = shown
            shown = readShown()
            if (shown) view.removeCallbacks(recheck)
            else if (was && view.hasWindowFocus()) { view.removeCallbacks(recheck); view.postDelayed(recheck, 300) }
        }
        trailing = Runnable { onChange(force = true) }
        val controller = view.windowInsetsController
        val preDraw = android.view.ViewTreeObserver.OnPreDrawListener { onChange(force = false); true }
        val controllable = android.view.WindowInsetsController.OnControllableInsetsChangedListener { _, _ -> onChange(force = true) }
        view.viewTreeObserver.addOnPreDrawListener(preDraw)
        controller?.addOnControllableInsetsChangedListener(controllable)
        onDispose {
            view.removeCallbacks(recheck)
            view.removeCallbacks(trailing)
            view.viewTreeObserver.removeOnPreDrawListener(preDraw)
            controller?.removeOnControllableInsetsChangedListener(controllable)
        }
    }
    val scroll = rememberScrollState()
    motion.scrollValue = { scroll.value.toFloat() }
    val metrics = remember(density) { LoginMetrics(density.density) }
    val bars = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val topInset = bars.getTop(density).toFloat()
    // Rest-frame measurements in scroll-content coordinates (px); window position = content - scroll.value.
    val geo = remember { RestGeometry() }
    val keyboardSettled by remember(motion) { derivedStateOf { motion.settled } }
    val keyboardOut by remember(motion) { derivedStateOf { motion.keyboardOut } }
    // While the notice shows, the overflow scroll brings Sign in above the keyboard (see RestGeometry.restAt).
    val focusedField = when { notice != null -> 3; serverFocused -> 0; usernameFocused -> 1; else -> 2 }
    val noticeVisibility = remember { androidx.compose.animation.core.MutableTransitionState(false) }
    noticeVisibility.targetState = notice != null
    val noticeSettled = noticeVisibility.isIdle && noticeVisibility.currentState

    BoxWithConstraints(Modifier.fillMaxSize().background(Base).testTag("loginScreen")
        // A tap on the background closes the keyboard (focus is cleared, the motion plays in reverse). A field's own tap
        // also reaches this detector, so taps on the (lifted) form are left alone.
        .pointerInput(Unit) { detectTapGestures { at ->
            val top = geo.formTop - scroll.value - geo.lift
            if (at.y !in top..(top + geo.formHeight)) focusManager.clearFocus()
        } }) {
        val screenHeight = maxHeight
        val windowH = with(density) { screenHeight.toPx() }
        CornerGlow(LoginAccent.accent.copy(alpha = .30f))

        // Read through state so long-running effects and layers never see a stale focus or window size.
        geo.windowH = windowH; geo.topInset = topInset; geo.focused = focusedField
        fun restAt(scrollPx: Float): RestFrame = geo.restAt(scrollPx)
        // Does the form fit above the keyboard? Decided for the current settled keyboard height and the scroll position
        // at opening. Within a keyboard session it only ever switches to overflow (a resize that makes it too tall), never
        // back: a shrinking keyboard just shrinks the scroll range.
        val wouldOverflow by remember {
            derivedStateOf {
                motion.height > 0f && geo.measured && !metrics.fits(geo.formTop - motion.openScroll,
                    geo.formTop + geo.formHeight - motion.openScroll, geo.windowH, geo.topInset, motion.height)
            }
        }
        var overflow by remember { mutableStateOf(false) }
        LaunchedEffect(motion) {
            snapshotFlow { wouldOverflow to (motion.height > 0f) }.collect { (would, session) ->
                if (!session) { overflow = false; return@collect }
                if (!would || overflow) return@collect
                // Opening straight into overflow: the drive below does the lift. A resize while open: hand the layer lift
                // over to the scroll at the same visual position, then shift to the focused field (250 ms).
                val handover = motion.settled && geo.lift > 0f
                val start = motion.openScroll + geo.lift
                overflow = true
                if (handover) {
                    withFrameNanos { } // the scroll range is laid out
                    scroll.scrollTo(start.toInt())
                    motion.scrollEnd = motion.openScroll + metrics.overflowLift(restAt(motion.openScroll), motion.height)
                    scroll.animateScrollTo(motion.scrollEnd.toInt(), tween(250, easing = LoginStandard))
                }
            }
        }

        // At rest, once the page cannot give way any further (Server stays 16 dp below the status bar), the notice
        // growing or collapsing scrolls the page by as much, so Sign in still stays put (the rest is a drag away).
        LaunchedEffect(motion) {
            var last = -1
            snapshotFlow { scroll.maxValue }.collect { max ->
                val noticeMoving = noticeVisibility.targetState || !noticeVisibility.isIdle
                if (last >= 0 && noticeMoving && !motion.keyboardOut && motion.openHeight == 0f) scroll.dispatchRawDelta((max - last).toFloat())
                last = max
            }
        }

        // Overflow: the lift is a scroll from the rest position to the focused field, driven by the same progress, but
        // only while the keyboard opens or closes; a resize or the settled state keep the scroll (and a drag) as they are.
        LaunchedEffect(motion) {
            snapshotFlow { if (overflow) motion.driveProgress() else Float.NaN }.collect { p ->
                if (p.isNaN()) return@collect
                val target = lerp(motion.openScroll, motion.scrollEnd, p)
                scroll.dispatchRawDelta(target - scroll.value)
            }
        }
        LaunchedEffect(motion) {
            snapshotFlow { motion.openHeight to motion.opening }.collect { (h, opening) ->
                if (h <= 0f) return@collect
                if (opening) {
                    motion.scrollEnd = motion.openScroll + metrics.overflowLift(restAt(motion.openScroll), h)
                } else motion.scrollEnd = scroll.value.toFloat() // closing from wherever the user dragged to
            }
        }
        // A focus change while the keyboard is open: in the fitting layout only the ring moves; otherwise a 250 ms shift.
        // Also once the notice has grown in (its top is final only then).
        LaunchedEffect(focusedField, noticeSettled) {
            if (!overflow || !motion.settled) return@LaunchedEffect
            val rest = restAt(motion.openScroll)
            motion.scrollEnd = motion.openScroll + metrics.overflowLift(rest, motion.height)
            scroll.animateScrollTo(motion.scrollEnd.toInt(), tween(250, easing = LoginStandard))
        }

        // When even a 96 dp logo leaves no room, the rest frame takes the lockup (in the error line's 200 ms).
        val minLogoPx = with(density) { 96.dp.toPx() }
        val crowded by remember { derivedStateOf { geo.shift > 0f && geo.logoH - geo.shift < kotlin.math.min(minLogoPx, geo.logoH) - .5f } }
        val crowdedLockup by androidx.compose.animation.core.animateFloatAsState(if (crowded) 1f else 0f,
            tween(200, easing = LoginStandard), label = "crowdedLockup")

        // Per-frame (layer blocks): scalars only, one reused output.
        val wordFontPx = with(density) { 46.sp.toPx() }
        val lockupFontPx = with(density) { 36.sp.toPx() }
        val lockup = remember { LockupOut() }
        fun formLift(): Float {
            if (!geo.measured || overflow) return 0f
            return motion.lift(metrics, geo.formTop + geo.formHeight - scroll.value, geo.windowH)
        }
        fun brandAt(): LockupOut? {
            if (!geo.measured) return null
            val s = scroll.value.toFloat()
            val p = max(motion.lockupProgress(), crowdedLockup)
            val serverTop = geo.formTop - s
            val kb = motion.openHeight
            val formTop = if (overflow) 0f
                else serverTop - (if (kb > 0f) metrics.fullLift(geo.formTop + geo.formHeight - s, geo.windowH, kb) else 0f)
            val lockupH = if (overflow) 0f else metrics.lockupHeight(formTop - topInset)
            // The rest frame gives way to a grown form (geo.shift moved the page up by what was missing, so Sign in
            // stays put): the logo keeps its top and shrinks by that much (to at least 96 dp); the wordmark and tagline
            // have moved up with the page. The lockup and the keyboard motion start from that shrunk logo.
            val d = geo.shift
            val logoH = if (d > 0f) max(geo.logoH - d, kotlin.math.min(minLogoPx, geo.logoH)) else geo.logoH
            val k = logoH / geo.logoH
            val logoX = geo.logoX + geo.logoW * (1 - k) / 2
            val logoY = geo.logoY - s + d
            lockupInto(lockup, p, logoX, logoY, geo.logoW * k, logoH, geo.wordX, geo.wordY - s, geo.wordH,
                wordFontPx, lockupFontPx, lockupH, topInset, formTop, density.density)
            if (d > 0f) with(lockup.logo) { set(x + logoX - geo.logoX, y + logoY - (geo.logoY - s), scale * k, alpha) }
            return lockup
        }
        fun GraphicsLayerScope.apply(t: LayerOut?) {
            if (t == null) return
            transformOrigin = TransformOrigin(0f, 0f)
            translationX = t.x; translationY = t.y; scaleX = t.scale; scaleY = t.scale; alpha = t.alpha
        }
        val navBottom = with(density) { bars.getBottom(density).toDp() }
        val short = screenHeight < 760.dp
        val bottomGap = if (short) 32.dp else 64.dp
        // Overflow: room to scroll the form up by its full lift. Sized in the layout phase, because the subcomposition
        // is not recomposed in time while the keyboard animates (the scroll range would appear only afterwards).
        fun extraPx(): Int = if (overflow) metrics.fullLift(geo.formTop + geo.formHeight - motion.openScroll, geo.windowH, motion.height).toInt() else 0

        // Short phones: the rest frame must fit without scrolling. The mockup's 640 dp frame assumes a 24 dp status
        // bar; with a taller one (display cutout) only the gap above the logo gives way, from 48 down to 16 dp.
        var topGap by remember { mutableStateOf(48.dp) }
        LaunchedEffect(short) {
            if (!short) { topGap = 48.dp; return@LaunchedEffect }
            // What the rest column is missing (measured before the page gives way, see geo.shift).
            snapshotFlow { Triple(geo.deficit, motion.keyboardOut, topGap) }.collect { (missing, out, gap) ->
                if (out || motion.openHeight > 0f) return@collect
                val overflowAt48 = with(density) { missing.toDp() } + (48.dp - gap)
                topGap = (48.dp - overflowAt48).coerceIn(16.dp, 48.dp)
            }
        }

        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            // A rest column taller than the screen (a grown form: the notice, an error line, large fonts) is moved
            // up by what is missing, so Sign in stays put; the brand gives way (brandAt). Never further than keeps
            // Server 16 dp below the status bar; beyond that the page scrolls as before.
            val topRoomPx = topInset + with(density) { 16.dp.toPx() }
            Column(Modifier.fillMaxWidth().heightIn(min = screenHeight).layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val missing = (placeable.height - constraints.minHeight).coerceAtLeast(0)
                val shift = missing.coerceAtMost((geo.formTopIn - topRoomPx).toInt().coerceAtLeast(0))
                geo.deficit = missing.toFloat()
                layout(placeable.width, placeable.height - shift) {
                    geo.shift = shift.toFloat()
                    placeable.place(0, -shift)
                }
            }) {
                Spacer(Modifier.height(with(density) { topInset.toDp() } + topGap))
                BarCLogo(logoHeight(screenHeight), Modifier.align(Alignment.CenterHorizontally)
                    .onPlaced { geo.logo(it.positionInParent(), it.size.width, it.size.height) }
                    .graphicsLayer { apply(brandAt()?.logo) })
                Spacer(Modifier.height(30.dp))
                Text("Coda", Modifier.padding(horizontal = 20.dp)
                    .onPlaced { geo.word(it.positionInParent(), it.size.height) }
                    .graphicsLayer { apply(brandAt()?.word) }.testTag("loginWordmark"),
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontSize = 46.sp, lineHeight = 48.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em), color = Text1)
                Spacer(Modifier.height(8.dp))
                Text("Your Navidrome library, artwork first.", Modifier.padding(horizontal = 20.dp)
                    .graphicsLayer { apply(brandAt()?.tagline) }.testTag("loginTagline"),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 20.sp), color = Text2)
                Spacer(Modifier.height(24.dp))
                Spacer(Modifier.weight(1f))
                Column(Modifier.padding(horizontal = 16.dp)
                    .onPlaced { geo.form(it.positionInParent().y, it.size.height) }
                    .graphicsLayer { translationY = -formLift().also { geo.lift = it } }) {
                    @Composable fun fieldError(field: LoginField): String? = error?.takeIf { it.field == field }?.message
                    var fieldWidth by remember { mutableStateOf(0.dp) }
                    Box(Modifier.onSizeChanged { fieldWidth = with(density) { it.width.toDp() } }
                        .onPlaced { geo.fieldTops[0] = it.positionInParent().y }) {
                        LoginField(server, {
                            if (!busy && it != server) { server = it; pinnedScheme = null; error = null; menuDismissed = true }
                        }, "Server", Icons.Default.Dns, locked = busy, error = fieldError(LoginField.Server), animateError = true,
                            prefix = prefix,
                            trailing = if (current != null && serverNote(current) != ServerNote.NoServerYet) {
                                { ServerChip(current, onInfo = { certificateMoreOpen = !certificateMoreOpen }) }
                            } else null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                            interactionSource = serverSource, fieldModifier = Modifier.testTag("server"))
                        LaunchedEffect(serverFocused) { if (serverFocused) menuDismissed = false }
                        // Opens only once the keyboard has settled, so it never trails a moving field.
                        val menuReady by produceState(false, serverFocused, keyboardSettled, keyboardOut) {
                            value = false
                            if (!serverFocused || !keyboardSettled) return@produceState
                            // 150 ms after the keyboard settles (motion table); 400 ms when there is no keyboard inset at all
                            // (hardware or floating keyboard, or one that has not started yet).
                            delay(if (keyboardOut) 150 else 400)
                            value = true
                        }
                        RecentServersMenu(expanded = menuReady && !menuDismissed && !busy && recents.isNotEmpty(),
                            recents = recents, current = input, width = fieldWidth,
                            onDismiss = { menuDismissed = true },
                            onPick = { pick(it); menuDismissed = true },
                            onForget = { scope.launch { recentServers.remove(it.url) } })
                    }
                    ServerNoteLine(serverNote(current).takeIf { fieldError(LoginField.Server) == null }, certificateMoreOpen) {
                        certificateMoreOpen = !certificateMoreOpen
                    }
                    Spacer(Modifier.height(12.dp))
                    LoginField(username, { if (!busy) { username = it; error = null } }, "Username", Icons.Default.Person, locked = busy,
                        error = fieldError(LoginField.Username), animateError = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
                        interactionSource = usernameSource,
                        modifier = Modifier.onPlaced { geo.fieldTops[1] = it.positionInParent().y },
                        fieldModifier = Modifier.testTag("username").semantics { contentType = ContentType.Username })
                    Spacer(Modifier.height(12.dp))
                    LoginField(password, {
                        if (!busy) { if (it.text != password.text) error = null; password = it }
                    }, "Password", Icons.Default.Lock, locked = busy,
                        error = fieldError(LoginField.Password), animateError = true,
                        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailing = { VisibilityToggle(visible, !busy) { visible = !visible } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        interactionSource = passwordSource,
                        modifier = Modifier.onPlaced { geo.fieldTops[2] = it.positionInParent().y },
                        fieldModifier = Modifier.testTag("password").focusRequester(passwordFocus)
                            .semantics { contentType = ContentType.Password })
                    // The no-encryption notice grows in above Sign in like an error line (200 ms), so the form grows upward.
                    var noticeHost by remember { mutableStateOf("") }
                    notice?.let { noticeHost = it.host } // kept while it collapses
                    val noticePad = with(density) { 12.dp.toPx() }
                    androidx.compose.animation.AnimatedVisibility(noticeVisibility,
                        Modifier.onPlaced { geo.noticeTop = it.positionInParent().y + noticePad },
                        enter = androidx.compose.animation.expandVertically(tween(200, easing = LoginStandard)) +
                            androidx.compose.animation.fadeIn(tween(200)),
                        exit = androidx.compose.animation.shrinkVertically(tween(200, easing = LoginStandard)) +
                            androidx.compose.animation.fadeOut(tween(200))) {
                        HttpNotice(noticeHost, Modifier.padding(top = 12.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    SignInPill(busy, enabled = !busy, onClick = { submit() }, modifier = Modifier.testTag("signIn"),
                        anyway = notice != null, busyLabel = if (sending) "Signing in" else "Checking server")
                }
                Spacer(Modifier.height(bottomGap + navBottom))
            }
            Spacer(Modifier.fillMaxWidth().layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = 0))
                layout(placeable.width, extraPx()) { placeable.place(0, 0) }
            })
        }
        // Overflow only: the form slides under a status-bar fade.
        Box(Modifier.fillMaxWidth().height(with(density) { topInset.toDp() } + 16.dp)
            .graphicsLayer { alpha = if (overflow) motion.progress() else 0f }
            .background(Brush.verticalGradient(listOf(Base, Base.copy(alpha = 0f)))))
    }
}

/** Rest-frame boxes (px, scroll-content coordinates), written on placement and read by the layers. */
private class RestGeometry {
    var logoX by mutableFloatStateOf(0f)
    var logoW by mutableFloatStateOf(0f); var logoH by mutableFloatStateOf(0f)
    var wordX by mutableFloatStateOf(0f); var wordH by mutableFloatStateOf(0f)
    /** The form's top in the rest column; [formTop] is in scroll-content coordinates (after [shift]). */
    var formTopIn by mutableFloatStateOf(0f); var formHeight by mutableFloatStateOf(0f)
    private var logoYIn by mutableFloatStateOf(0f); private var wordYIn by mutableFloatStateOf(0f)
    /** How far the rest column is moved up so a grown form keeps Sign in in place, and what it was missing (px). */
    var shift by mutableFloatStateOf(0f); var deficit by mutableFloatStateOf(0f)
    val formTop: Float get() = formTopIn - shift
    val logoY: Float get() = logoYIn - shift
    val wordY: Float get() = wordYIn - shift
    /** The form's current lift (px), as last drawn; plain field: written from the layer block, read only by the tap handler. */
    var lift = 0f
    /** Server, Username and Password, relative to the form. */
    val fieldTops = mutableStateListOf(0f, 0f, 0f)
    var windowH by mutableFloatStateOf(0f); var topInset by mutableFloatStateOf(0f); var focused by mutableIntStateOf(2)
    val measured: Boolean get() = formHeight > 0f && logoH > 0f
    /** The notice's top (inside its 12 dp padding), relative to the form. */
    var noticeTop by mutableFloatStateOf(0f)
    // With the notice (focused 3) the target is Sign in above the keyboard, so the notice and Sign in show together;
    // The gap above Sign in shrinks from 16 to 8 dp first (LoginMetrics.noticeGap); when even that doesn't fit
    // (200 % font on 640 dp) Sign in wins and the notice's top is a drag away.
    fun restAt(scrollPx: Float) = RestFrame(windowH, topInset, formTop - scrollPx, formTop + formHeight - scrollPx,
        if (focused == 3) Float.POSITIVE_INFINITY else formTop + fieldTops[focused] - scrollPx,
        if (focused == 3) formTop + noticeTop - scrollPx else Float.NaN)
    fun logo(at: Offset, w: Int, h: Int) { logoX = at.x; logoYIn = at.y; logoW = w.toFloat(); logoH = h.toFloat() }
    fun word(at: Offset, h: Int) { wordX = at.x; wordYIn = at.y; wordH = h.toFloat() }
    fun form(top: Float, h: Int) { formTopIn = top; formHeight = h.toFloat() }
}

/**
 * The keyboard as one progress value 0..1 from Compose's IME insets, which follow the system WindowInsetsAnimation
 * frame by frame. A keyboard that jumps (no animation) is eased over 250 ms standard instead; with animations off
 * that tween is instant too (MotionDurationScale).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun rememberKeyboardMotion(): KeyboardMotion {
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val source = WindowInsets.imeAnimationSource
    val target = WindowInsets.imeAnimationTarget
    val motion = remember(density) {
        KeyboardMotion({ ime.getBottom(density).toFloat() }, { source.getBottom(density).toFloat() }, { target.getBottom(density).toFloat() })
    }
    LaunchedEffect(motion) { motion.run() }
    return motion
}

/** The keyboard's state for the page. The per-frame reads ([progress], [lift], ...) allocate nothing. */
@Stable
private class KeyboardMotion(val now: () -> Float, val source: () -> Float, val target: () -> Float) {
    private val jump = Animatable(0f)
    private var jumping by mutableStateOf(false)
    /** The keyboard's height when it opened from closed (0 once it has fully closed); the lockup uses it (it stays on a resize). */
    var openHeight by mutableFloatStateOf(0f); private set
    /** The keyboard's current settled height while open (set at opening and when a resize settles); fit and scroll range use it. */
    var height by mutableFloatStateOf(0f); private set
    var opening by mutableStateOf(false); private set
    /** The page's scroll position; read when a keyboard session starts (set by the screen). */
    var scrollValue: () -> Float = { 0f }
    /** Scroll at opening and the overflow scroll's end; both set when a session starts, so a drive never sees stale ones. */
    var openScroll by mutableFloatStateOf(0f)
    var scrollEnd by mutableFloatStateOf(0f)

    fun progress(): Float = if (jumping) jump.value else imeProgress(now(), source(), target())
    fun lockupProgress(): Float = if (!jumping && imeResizing(source(), target())) 1f else progress()
    /** Progress for the overflow scroll, or NaN: only while opening or closing, never on a resize or when settled. */
    fun driveProgress(): Float = overflowDriveProgress(now(), source(), target(), jumping, jump.value)
    val settled: Boolean get() = !jumping && source() == target()
    val keyboardOut: Boolean get() = jumping || now() > 0f || source() != target()

    /** The form's lift for this frame (fitting layout): Sign in [gap] above the keyboard, by progress; 1:1 on a resize. */
    fun lift(metrics: LoginMetrics, signInBottom: Float, windowHeight: Float): Float {
        if (jumping) return jump.value * metrics.fullLift(signInBottom, windowHeight, openHeight)
        val src = source(); val tgt = target(); val n = now()
        if (imeResizing(src, tgt)) return metrics.fullLift(signInBottom, windowHeight, n)
        val full = max(src, tgt)
        return imeProgress(n, src, tgt) * metrics.fullLift(signInBottom, windowHeight, if (full > 0f) full else openHeight)
    }

    suspend fun run() {
        var last = 0f
        var lastTarget = 0f
        var wasClosed = true
        snapshotFlow { ImeFrame(now(), source(), target()) }.collectLatest { f ->
            if (f.full > 0f && wasClosed) {
                openScroll = scrollValue(); scrollEnd = openScroll // the real end follows from the layout (screen effect)
                openHeight = f.full; height = f.full; wasClosed = false
            }
            if (!f.animating && f.now > 0f) height = f.now // a resize (or a jump) has settled
            if (f.now == 0f && !f.animating) wasClosed = true
            val keyboardTarget = if (f.animating) f.target else f.now
            if (keyboardTarget > 0f && lastTarget == 0f) opening = true
            if (keyboardTarget == 0f && lastTarget > 0f) opening = false
            lastTarget = keyboardTarget
            val p = f.progress
            val from = if (jumping) jump.value else last
            last = p
            if (imeJumped(f.animating, p, from)) {
                jumping = true
                jump.snapTo(from)
                jump.animateTo(p, tween(250, easing = LoginStandard))
            }
            jumping = false
            if (f.now == 0f && !f.animating) { openHeight = 0f; height = 0f; opening = false } // fully closed: the session ends
        }
    }
}

/** 184 dp tall as in the mockup; below 760 dp of screen height it shrinks, to at least 96 dp (fits a 640 dp phone). */
internal fun logoHeight(screenHeight: Dp): Dp =
    if (screenHeight >= 760.dp) 184.dp else (184.dp - (760.dp - screenHeight)).coerceIn(96.dp, 184.dp)

/**
 * The app icon's bar-C with its own gradient (no tint), plus a soft drop shadow. The shadow is a pre-rendered bitmap
 *: a live blur (RenderEffect) inside the lockup's scaled layer rendered as an opaque square on a test emulator,
 * and a scaled layer should only scale pixels. It appears once rendered (off the main thread).
 */
@Composable
private fun BarCLogo(height: Dp, modifier: Modifier = Modifier) {
    // ic_coda is the 108-unit launcher foreground; the glyph spans 40.8 x 52.4 units, centred 0.5 units below the middle.
    val full = height * (108f / 52.4f)
    val shift = -full * (0.5f / 108f)
    val context = LocalContext.current
    val density = LocalDensity.current
    val fullPx = with(density) { full.roundToPx() }
    // The former blur(12.dp): RenderEffect turns a blur radius into sigma = radius * 0.57735 + 0.5 px.
    val sigmaPx = with(density) { 12.dp.toPx() } * 0.57735f + 0.5f
    val shadow by produceState<ImageBitmap?>(null, fullPx, sigmaPx) {
        value = withContext(Dispatchers.Default) { renderLogoShadow(context, fullPx, sigmaPx) }
    }
    // Rasterised by the platform instead of drawn by painterResource: Compose's vector painter left two 1 px lines
    // (gold, grey) at the glyph image's bottom edge, 88 px under the logo in the 640 dp rest lockup (same pixels on two
    // emulators with different GPUs; Lg3SizesDeviceTest checks the gap). The same drawable, at the same size and place.
    // Off the main thread like the shadow (a ~5 MiB bitmap at 3x density); a cancelled producer drops its bitmap.
    val glyph by produceState<ImageBitmap?>(null, fullPx) {
        value = rasteriseOffMain { renderLogoGlyph(context, fullPx) }
    }
    Box(modifier.size(height * (40.8f / 52.4f), height).testTag("loginLogo")) {
        shadow?.let {
            Image(it, null, Modifier.align(Alignment.Center).wrapContentSize(unbounded = true).requiredSize(full)
                .offset(y = shift + 10.dp), contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.Low)
        }
        glyph?.let {
            Image(it, null, Modifier.align(Alignment.Center).wrapContentSize(unbounded = true).requiredSize(full).offset(y = shift),
                contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.Low)
        }
    }
}

/**
 * Runs [draw] (blocking pixel work) on [dispatcher], never on the caller's thread; a caller cancelled meanwhile gets
 * the cancellation, not the result, so a cancelled producer cannot publish (`LogoGlyphThreadingTest`).
 */
internal suspend fun <T : Any> rasteriseOffMain(dispatcher: CoroutineDispatcher = Dispatchers.Default, draw: suspend () -> T?): T? =
    withContext(dispatcher) { draw() }

/** The bar-C at [fullPx] (the launcher foreground, gradient and all), rasterised by the platform's vector drawable. */
private suspend fun renderLogoGlyph(context: android.content.Context, fullPx: Int): ImageBitmap? {
    val drawable = context.getDrawable(R.drawable.ic_coda)?.mutate() ?: return null
    currentCoroutineContext().ensureActive()
    val bitmap = android.graphics.Bitmap.createBitmap(fullPx, fullPx, android.graphics.Bitmap.Config.ARGB_8888)
    drawable.setBounds(0, 0, fullPx, fullPx)
    drawable.draw(android.graphics.Canvas(bitmap))
    if (!currentCoroutineContext().isActive) { bitmap.recycle(); currentCoroutineContext().ensureActive() }
    return bitmap.asImageBitmap()
}

/** Shadow resolution relative to the drawn size; the blur hides the upscaling. */
private const val ShadowDownscale = 4

/** The bar-C in black at 40 % alpha, blurred to [sigmaPx] (three box passes of radius r give sigma ≈ r + 0.5), at 1/[ShadowDownscale] size. */
private fun renderLogoShadow(context: android.content.Context, fullPx: Int, sigmaPx: Float): ImageBitmap? {
    val size = (fullPx / ShadowDownscale).coerceAtLeast(8)
    val drawable = context.getDrawable(R.drawable.ic_coda)?.mutate() ?: return null
    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    drawable.setBounds(0, 0, size, size)
    drawable.draw(android.graphics.Canvas(bitmap))
    val pixels = IntArray(size * size)
    bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
    val alpha = IntArray(pixels.size) { pixels[it] ushr 24 }
    boxBlur3(alpha, size, size, (sigmaPx / ShadowDownscale - .5f).roundToInt().coerceAtLeast(1))
    for (i in pixels.indices) pixels[i] = (alpha[i] * 4 / 10) shl 24 // black, 40 %
    bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
    return bitmap.asImageBitmap()
}

/** Three horizontal + vertical box-blur passes of [radius] over an alpha plane (0..255): close to a Gaussian. */
internal fun boxBlur3(a: IntArray, w: Int, h: Int, radius: Int) {
    val tmp = IntArray(a.size)
    val span = 2 * radius + 1
    repeat(3) {
        for (y in 0 until h) {
            var sum = 0
            for (k in -radius..radius) sum += a[y * w + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[y * w + x] = sum / span
                sum += a[y * w + (x + radius + 1).coerceAtMost(w - 1)] - a[y * w + (x - radius).coerceAtLeast(0)]
            }
        }
        for (x in 0 until w) {
            var sum = 0
            for (k in -radius..radius) sum += tmp[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                a[y * w + x] = sum / span
                sum += tmp[(y + radius + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - radius).coerceAtLeast(0) * w + x]
            }
        }
    }
}

/** Up to five resolved servers, newest first; picking one fills the field, × forgets it (48 dp target). */
@Composable
private fun RecentServersMenu(
    expanded: Boolean, recents: List<RecentServer>, current: String, width: Dp,
    onDismiss: () -> Unit, onPick: (RecentServer) -> Unit, onForget: (RecentServer) -> Unit,
) {
    // Not focusable: the Server field keeps focus and the keyboard while the menu is open.
    DropdownMenu(expanded, onDismiss, Modifier.width(width).testTag("recentServers"), offset = DpOffset(0.dp, 4.dp),
        properties = PopupProperties(focusable = false), shape = RoundedCornerShape(16.dp),
        containerColor = Color(0xFF22252A), shadowElevation = 8.dp) {
        Text("RECENT SERVERS", Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp), style = kickerStyle(), color = Text3)
        val now = System.currentTimeMillis()
        recents.forEachIndexed { index, recent ->
            val scheme = recent.url.substringBefore("://") + "://"
            val rest = recent.url.substringAfter("://")
            val selected = RecentServers.same(recent.url, current)
            DropdownMenuItem(
                text = {
                    Column {
                        Text(buildAnnotatedString {
                            withStyle(SpanStyle(color = Text3)) { append(scheme) }
                            append(rest)
                        }, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 20.sp), color = Text1, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(if (index == 0) "Last used" else DateUtils.getRelativeTimeSpanString(recent.usedAt, now, DateUtils.MINUTE_IN_MILLIS).toString(),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp), color = Text3)
                    }
                },
                leadingIcon = { Icon(Icons.Default.History, null, Modifier.size(20.dp), tint = Text2) },
                trailingIcon = {
                    IconButton(onClick = { onForget(recent) }, modifier = Modifier.testTag("forgetServer")) {
                        Icon(Icons.Default.Close, "Forget $rest", Modifier.size(20.dp), tint = Text2)
                    }
                },
                onClick = { onPick(recent) },
                contentPadding = PaddingValues(start = 16.dp),
                modifier = Modifier.padding(horizontal = 6.dp).heightIn(min = 52.dp)
                    .background(if (selected) Color.White.copy(alpha = .07f) else Color.Transparent, RoundedCornerShape(10.dp))
                    .testTag("recentServer"),
            )
        }
    }
}

/** The no-encryption notice shown for [input] (resolved to [url]) since [shownAt] (uptime ms). */
private data class HttpNoticeState(val input: String, val url: String, val host: String, val shownAt: Long)

/** Presses within this time after the notice appears are ignored (design C). */
internal const val NoticeGuardMillis = 600L

private fun hostOf(url: String): String = runCatching { java.net.URI(url.trim()).host }.getOrNull() ?: url.substringAfter("://").substringBefore('/')

/** The stored agreement for [url], failing closed: an unreadable store means "ask again". */
private suspend fun agreedBefore(consent: PlainHttpConsent, url: String): Boolean = try {
    consent.contains(url)
} catch (error: Exception) {
    if (error is kotlinx.coroutines.CancellationException) throw error
    false
}
