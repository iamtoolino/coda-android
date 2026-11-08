package dev.coda.android.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.coda.android.CodaApp
import dev.coda.android.cache.OfflineLibrary
import dev.coda.android.ui.common.decorative
import dev.coda.core.cache.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** design-ca3 tokens not in the colour scheme. */
private val Caution = Color(0xFFF2C46B)
private val Danger = Color(0xFFE5675E)

/** The row type of the grouped Settings (SettingsRow), for the rows this page draws itself (presets, sliders). */
private val RowTitle @Composable get() = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium)
private val RowText @Composable get() = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, lineHeight = 18.sp)
private val SmallText @Composable get() = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 17.sp)

private fun size(bytes: Long) = CacheSummary.size(bytes)

/** The Settings row, "18 GB · Balanced". */
@Composable
fun MusicStorageRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as CodaApp
    val host by app.offlineLibrary.collectAsState()
    val summary = host?.summary?.collectAsState()?.value
    val policy by app.cacheSettings.policy.collectAsState()
    val customChosen by app.cacheSettings.customChosen.collectAsState()
    // A grouped Settings row with an icon and a chevron, so it reads as tappable.
    SettingsRow("Music storage", modifier.testTag("musicStorageRow"), icon = Icons.Outlined.Storage,
        subtitle = listOfNotNull(summary?.let { size(it.totalBytes) }, policy.selection(customChosen)?.title ?: "Custom").joinToString(" · "),
        onClick = onClick, chevron = true)
}

/**
 * One owner for the screen's policy selections, so they take effect in the order they were made. A newer
 * selection cancels an older one that is still previewing (or waiting to apply), and [select]'s `latest` tells a result
 * that is no longer the newest selection's, so it neither shows a sheet nor applies anything.
 */
internal class PolicySelections(private val scope: CoroutineScope) {
    private var job: Job? = null
    private var generation = 0
    fun select(block: suspend (latest: () -> Boolean) -> Unit) {
        job?.cancel()
        val mine = ++generation
        job = scope.launch { block { mine == generation } }
    }
}

/**
 * A selection's preview, then: a plan that deletes nothing is applied at once ([apply] returns the plan it refused, if
 * the cache changed meanwhile), any other plan goes to the sheet ([show]). A stale result ([latest] false) does neither.
 */
internal suspend fun <P> previewThenApply(preview: suspend () -> P, isEmpty: (P) -> Boolean, apply: suspend () -> P?, latest: () -> Boolean,
    show: (P) -> Unit) {
    val plan = preview()
    if (!latest()) return
    if (isEmpty(plan)) {
        val refused = apply()
        if (refused != null && latest()) show(refused)
    } else show(plan)
}

/** A change waiting for the sheet: the new policy and the preview first shown for it. */
internal data class Pending(val policy: CachePolicy, val first: CachePlanner.Plan, val current: CachePlanner.Plan, val deleting: Boolean = false,
    /** Custom stays chosen (or becomes chosen) once this change is saved; false for a preset. */
    val custom: Boolean = false)

/** The Music storage screen, PF-12b in the grouped Settings style ("Grouped, dot legend"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicStorageScreen(back: () -> Unit) {
    BackHandler(onBack = back)
    val app = LocalContext.current.applicationContext as CodaApp
    val host by app.offlineLibrary.collectAsState()
    val saved by app.cacheSettings.policy.collectAsState()
    val customChosen by app.cacheSettings.customChosen.collectAsState()
    val summary = host?.summary?.collectAsState()?.value
    val removed = host?.removedForSpace?.collectAsState()?.value
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pending?>(null) }
    // Read-only here since RA-1 (the setting lives in Settings → Playback): the sheet names how many tracks stay.
    var ahead by remember { mutableIntStateOf(4) }
    LaunchedEffect(Unit) { ahead = withContext(Dispatchers.IO) { app.cacheSettings.loadTracksAhead() } }
    // The status card is live: music on the phone and free space move with read-ahead, downloads and other apps.
    // Only while the screen is visible (STARTED): a backgrounded app with this screen mounted makes no disk queries.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(host, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { host?.refreshSummary(); delay(2_000) } }
    }

    /**
     * A preset, a released slider or a larger line: saved silently when it deletes nothing, else the sheet (§5.4). [custom] is
     * whether Custom is chosen afterwards: a preset clears it, a slider sets it, Keep free space keeps it.
     */
    val selections = remember(scope) { PolicySelections(scope) }
    fun change(next: CachePolicy, custom: Boolean = customChosen) {
        val library = host
        val unchanged = next == saved
        selections.select { latest ->
            if (unchanged) withContext(Dispatchers.IO) { app.cacheSettings.setCustomChosen(custom) }
            else if (library == null) withContext(Dispatchers.IO) { app.cacheSettings.setPolicy(next, custom) }
            else previewThenApply({ library.preview(next) }, { it.isEmpty }, { library.applyPolicy(next, null, custom) }, latest) { plan ->
                pending = Pending(next, plan, plan, custom = custom)
            }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).then(dev.coda.android.ui.common.pageSemanticsOf("Music storage")).testTag("musicStorage")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Music storage", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        }
        // What Music storage is, in the dim small style under the title, above the status card.
        Text("Coda uses free space on your phone to build an offline library from the music you play. It gives the space back when your phone needs it.",
            Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp), style = SmallText, color = SettingsTextDim)
        Spacer(Modifier.height(4.dp))
        StatusCard(summary)
        removed?.let { notice ->
            SettingsGroupLabel("Removed for space")
            SettingsCard { NoticeRow(notice) { scope.launch { host?.acknowledgeRemoved() } } }
        }
        val stops = CachePolicy.KEEP_FREE_GB
        val freeIndex = stops.indexOf((saved.keepFreeBytes / CachePolicy.GB).toInt()).coerceAtLeast(0)
        SettingsGroupLabel("Keep free space")
        SettingsCard {
            StepSetting("Keep free", stops.map { "$it GB" }, freeIndex, tag = "keepFree", library = host,
                candidate = { saved.copy(keepFreeBytes = stops[it] * CachePolicy.GB) },
                text = "Coda removes music from its offline library to keep at least this much free for photos, videos and apps. It only cleans up while it's running, so a bigger margin is safer and a smaller one keeps more music offline.",
                idle = pending == null) { change(saved.copy(keepFreeBytes = stops[it] * CachePolicy.GB)) }
        }

        SettingsGroupLabel("What stays on this phone")
        val current = saved.selection(customChosen)
        SettingsCard {
            StoragePreset.entries.forEach { preset ->
                RadioRow(preset.title, preset.explanation, selected = current == preset, isDefault = preset == StoragePreset.BALANCED,
                    tag = "preset-${preset.name}") { change(saved.with(preset), custom = false) }
                SettingsRowDivider()
            }
            // Custom is a real option; its three times open right under it, a preset hides them.
            RadioRow("Custom", if (current == null) "Starts from the preset you had; change any time." else "Set your own times.",
                selected = current == null, isDefault = false, tag = "preset-CUSTOM") { scope.launch(Dispatchers.IO) { app.cacheSettings.setCustomChosen(true) } }
            if (current == null) Column(Modifier.padding(start = 40.dp).testTag("customTimes")) {
                CustomTimes(saved, host, ::change, idle = pending == null)
            }
        }
        Text("While your server can't be reached, music only leaves when Coda needs the space. Albums and playlists set to Prefer offline go last.", Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp),
            style = SmallText, color = SettingsTextDim)
        Spacer(Modifier.height(dev.coda.android.ui.shell.LocalDockPadding.current + 24.dp))
    }

    pending?.let { shown ->
        FreeSpaceSheet(shown, ahead, onRefresh = { plan -> pending = pending?.copy(current = plan) },
            onCancel = { pending = null },
            onConfirm = {
                val library = host ?: return@FreeSpaceSheet
                pending = shown.copy(deleting = true)
                scope.launch {
                    val refused = library.applyPolicy(shown.policy, shown.current, shown.custom)
                    // Changed while open: nothing saved; the sheet shows the new numbers and the caution line.
                    pending = refused?.let { shown.copy(current = it, deleting = false) }
                }
            }, library = host)
    }
}

/** The artwork swatch.2 `accentText`: the music colour blended 45 % toward white (no theme token yet). */
@Composable
private fun artworkColor() = androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.primary, Color.White, .45f)

/**
 * DS-19b, Dana's colour mix: Coda's parts take the artwork accent; the phone's parts (Available to Coda, Keep free, Other
 * apps and files) fixed greys; what Coda will clean up the fixed caution colour in stripes, so it never relies on colour alone.
 */
private sealed interface BarPaint {
    data class Solid(val color: Color) : BarPaint
    data class Stripes(val base: Color, val stripe: Color) : BarPaint
}
private val Neutral = Color(0xFFF1F1EE)
private val OtherPaint = BarPaint.Solid(Neutral.copy(alpha = .36f))
private val AvailablePaint = BarPaint.Solid(Neutral.copy(alpha = .17f))
private val KeepFreePaint = BarPaint.Stripes(Neutral.copy(alpha = .05f), Neutral.copy(alpha = .20f))
private val KeepFreeOverPaint = BarPaint.Stripes(Caution.copy(alpha = .12f), Caution.copy(alpha = .55f))
private val CleanUpPaint = BarPaint.Stripes(Color(0xFF0A0C0E).copy(alpha = .9f), Caution)

/** One source for a bar part and its legend swatch, so the two always match. */
@Composable
private fun paintFor(segment: StorageSegment, summary: CacheSummary): BarPaint = when (segment) {
    StorageSegment.ARTWORK -> BarPaint.Solid(artworkColor())
    StorageSegment.MUSIC -> BarPaint.Solid(MaterialTheme.colorScheme.primary)
    StorageSegment.CLEAN_UP -> CleanUpPaint
    StorageSegment.AVAILABLE -> AvailablePaint
    StorageSegment.KEEP_FREE -> if (summary.belowLine) KeepFreeOverPaint else KeepFreePaint
    StorageSegment.OTHER -> OtherPaint
}

/** Fills the rect with [paint]; stripes run "/" 2 dp wide every 5 dp, on one grid so neighbouring parts line up. */
private fun DrawScope.fill(paint: BarPaint, left: Float, width: Float) {
    when (paint) {
        is BarPaint.Solid -> drawRect(paint.color, Offset(left, 0f), Size(width, size.height))
        is BarPaint.Stripes -> {
            drawRect(paint.base, Offset(left, 0f), Size(width, size.height))
            val h = size.height
            val step = 5.dp.toPx() * 1.4142f
            clipRect(left, 0f, left + width, h) {
                var x = kotlin.math.floor((left - h) / step) * step
                while (x < left + width) {
                    drawLine(paint.stripe, Offset(x, h), Offset(x + h, 0f), strokeWidth = 2.dp.toPx())
                    x += step
                }
            }
        }
    }
}

/**
 * The status card, DS-19b (Dana's "Music next to free", `A2-gap.png`): "N used by Coda" (music plus artwork), the storage
 * bar, then the legend in bar order: Artwork, Music (with "To clean up" under it over the line), Available to Coda, Keep free,
 * Other apps and files (hidden when the volume's size is unknown). Over the line a caution line says what happens, and in
 * state 4 a dim line says what the user can do. One TalkBack stop ([CacheSummary.spoken]); the texts come from
 * [CacheSummary] so the card and TalkBack never disagree.
 */
@Composable
private fun StatusCard(measured: CacheSummary?) {
    // The placeholder before the first measurement (free space unknown) draws no bar and no legend yet.
    val summary = measured?.takeIf { it.measured }
    SettingsCard(Modifier.testTag("storageStatus").semantics(mergeDescendants = true) { summary?.let { contentDescription = it.spoken } }) {
        // The visible texts are drawn, not read: the card's one description says them (they stay in the test tree).
        Column(Modifier.fillMaxWidth().then(if (summary != null) Modifier.decorative() else Modifier).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(summary?.let { size(it.totalBytes) } ?: "–", style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp),
                    fontWeight = FontWeight.SemiBold)
                Text("  used by Coda", style = RowText, color = SettingsText2)
            }
            StorageBar(summary)
            if (summary != null) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BreakdownRow("Artwork", size(summary.artworkBytes), "storageRowArtwork", paintFor(StorageSegment.ARTWORK, summary))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BreakdownRow("Music", summary.musicText, "storageRowMusic", paintFor(StorageSegment.MUSIC, summary))
                    // Indented under Music: the striped slice of Music that Coda will clean up (state 3).
                    if (summary.cleanUpBytes > 0) BreakdownRow("To clean up", size(summary.cleanUpBytes), "storageRowCleanUp", CleanUpPaint,
                        color = Caution, indent = 18.dp)
                }
                BreakdownRow("Available to Coda", summary.availableText, "storageRowAvailable", paintFor(StorageSegment.AVAILABLE, summary))
                BreakdownRow("Keep free", summary.keepFreeText, "storageRowKeepFree", paintFor(StorageSegment.KEEP_FREE, summary),
                    color = if (summary.belowLine) Caution else null)
                if (summary.otherBytes > 0) BreakdownRow(CacheSummary.OTHER, size(summary.otherBytes), "storageRowOther",
                    paintFor(StorageSegment.OTHER, summary))
            }
            summary?.caution?.let { caution -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(caution, Modifier.testTag("storageCaution"), style = RowText, color = Caution)
                summary.cautionHint?.let { Text(it, Modifier.testTag("storageCautionHint"), style = RowText, color = SettingsText2) }
            } }
        }
    }
}

@Composable
private fun Swatch(paint: BarPaint) {
    Canvas(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp))) { fill(paint, 0f, size.width) }
}

/** One legend row: swatch, label (wraps at large font scales) and the number at the end (never wraps); [color] for caution. */
@Composable
private fun BreakdownRow(label: String, value: String, tag: String, paint: BarPaint, color: Color? = null, indent: androidx.compose.ui.unit.Dp = 0.dp) {
    Row(Modifier.fillMaxWidth().heightIn(min = 24.dp).padding(start = indent).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Swatch(paint)
        Text(label, Modifier.weight(1f).padding(start = 8.dp, end = 12.dp), style = RowText, color = color ?: SettingsText2)
        Text(value, style = RowText.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"), color = color ?: SettingsText1,
            maxLines = 1, softWrap = false)
    }
}

/**
 * The parts in [CacheSummary.segments] order. Coda's block (Artwork, Music, To clean up, Available, Keep free) has
 * hairline joins and is rounded as one; a 3 dp gap sets the grey Other apps and files apart at the end (at least 3 dp wide,
 * like every part). Over the line with music to clean up, a caution mark stands where the clean-up stops. No mark otherwise:
 * the striped Keep free part already shows the line, and in state 4 the mark would land in other apps' space.
 */
@Composable
private fun StorageBar(summary: CacheSummary?) {
    val parts = summary?.segments?.map { (segment, bytes) -> Triple(segment, bytes, paintFor(segment, summary)) }.orEmpty()
    Canvas(Modifier.fillMaxWidth().height(10.dp).semantics { invisibleToUser() }) {
        val h = size.height
        val round = CornerRadius(5.dp.toPx())
        val inner = CornerRadius(2.dp.toPx())
        if (parts.isEmpty()) { drawRoundRect((AvailablePaint).color, cornerRadius = round); return@Canvas }
        val minW = 3.dp.toPx()
        val hair = 1.dp.toPx()
        val total = parts.sumOf { it.second }.toFloat()
        val other = parts.lastOrNull()?.takeIf { it.first == StorageSegment.OTHER }
        val coda = parts.filter { it.first != StorageSegment.OTHER }
        val gap = if (other != null && coda.isNotEmpty()) 3.dp.toPx() else 0f
        val otherW = when { other == null -> 0f; coda.isEmpty() -> size.width; else -> maxOf(minW, size.width * other.second / total) }
        val blockW = size.width - otherW - gap
        // Each part at least 3 dp; the widest part gives up what the minimums take.
        val rest = blockW - hair * (coda.size - 1).coerceAtLeast(0)
        val codaBytes = coda.sumOf { it.second }.toFloat()
        val widths = coda.map { maxOf(minW, rest * it.second / codaBytes) }.toMutableList()
        if (widths.isNotEmpty()) { val widest = widths.indices.maxBy { widths[it] }; widths[widest] = (widths[widest] - (widths.sum() - rest)).coerceAtLeast(minW) }
        var markX: Float? = null
        if (coda.isNotEmpty()) clipPath(Path().apply { addRoundRect(RoundRect(0f, 0f, blockW, h, round, if (other != null) inner else round,
            if (other != null) inner else round, round)) }) {
            var x = 0f
            coda.forEachIndexed { i, (segment, _, paint) ->
                if (segment == StorageSegment.CLEAN_UP) markX = (x - hair / 2).coerceAtLeast(1.dp.toPx())
                fill(paint, x, widths[i])
                x += widths[i] + hair
            }
        }
        if (other != null) clipPath(Path().apply { addRoundRect(RoundRect(blockW + gap, 0f, size.width, h,
            if (coda.isEmpty()) round else inner, round, round, if (coda.isEmpty()) round else inner)) }) {
            fill(other.third, blockW + gap, otherW)
        }
        markX?.let { drawRoundRect(Caution, Offset(it - 1.dp.toPx(), -3.dp.toPx()), Size(2.dp.toPx(), h + 6.dp.toPx()), CornerRadius(1.dp.toPx())) }
    }
}

/** Notice, PF-12b as a Settings row: what happened as the title, the albums as the subtitle, OK at the end. */
@Composable
private fun NoticeRow(notice: RemovedForSpace, ok: () -> Unit) {
    val date = remember(notice.at) {
        java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.getDefault())
            .format(java.time.Instant.ofEpochMilli(notice.at).atZone(java.time.ZoneId.systemDefault()))
    }
    val count = notice.titles.size
    SettingsRow("$count ${if (count == 1) "album" else "albums"} removed for space\u00A0·\u00A0$date", Modifier.testTag("removedNotice"),
        icon = Icons.Outlined.DeleteSweep,
        subtitle = notice.titles.joinToString(", ") + ". Turn on Prefer offline again when there's room.") {
        TextButton(onClick = ok, Modifier.testTag("removedNoticeOk")) { Text("OK") }
    }
}

/** A preset as a row of the "What stays" card: the radio sits where Settings rows have their icon tile. */
@Composable
private fun RadioRow(title: String, text: String, selected: Boolean, isDefault: Boolean, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).selectable(selected, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 10.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp), Alignment.Center) { RadioButton(selected = selected, onClick = null) }
        Column(Modifier.padding(start = 16.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = RowTitle)
                if (isDefault) Text("Default", Modifier.padding(start = 8.dp).clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 6.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelSmall, color = SettingsText2)
            }
            Text(text, style = RowText, color = SettingsText2)
        }
    }
}

/**
 * The three times of Custom. Every slider moves over its full range; the others follow live so that
 * Heard once <= Heard again <= Prefer offline holds, and everything is saved as one change on release. The slider
 * Hid is shown again, labelled "Prefer offline" (internal slot and tag stay KEPT / "kept").
 */
@Composable
private fun CustomTimes(saved: CachePolicy, host: OfflineLibrary?, change: (CachePolicy, Boolean) -> Unit, idle: Boolean) {
    val labels = Lifetime.entries.map { it.label }
    // The policy shown while a thumb is being dragged; null shows the saved one.
    var draft by remember { mutableStateOf<CachePolicy?>(null) }
    LaunchedEffect(saved, idle) { if (idle) draft = null }
    val shown = draft ?: saved
    @Composable fun slider(title: String, slot: TimeSlot, tag: String, text: String, of: (CachePolicy) -> Lifetime) =
        StepSetting(title, labels, of(shown).ordinal, tag = tag, library = host, idle = idle, committed = of(saved).ordinal,
            candidate = { saved.moved(slot, Lifetime.entries[it]) }, text = text,
            onDrag = { draft = saved.moved(slot, Lifetime.entries[it]) }) { change(saved.moved(slot, Lifetime.entries[it]), true) }
    if (KEEP_OFFLINE_IN_SETTINGS) slider("Prefer offline", TimeSlot.KEPT, "kept", "Albums and playlists set to Prefer offline.") { it.kept }
    slider("Heard again", TimeSlot.HEARD_AGAIN, "heardAgain", "Songs you've played more than once.") { it.heardAgain }
    slider("Heard once", TimeSlot.HEARD_ONCE, "heardOnce", "Songs played once, plus songs cached ahead (including Cache queue ahead).") { it.heardOnce }
    Text("Playing a song again starts its time over.", Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
        style = SmallText, color = SettingsTextDim)
}

/**
 * A stepped setting (§5.3): title, value at the row's end, a Material 3 slider with one step per stop that moves over its
 * whole range. While dragging, a preview line says what it would free. Committed on release. [index] is what the thumb
 * shows when not dragging (a custom time may be pushed by a neighbour); [committed] is the saved index, so a release
 * only commits a real change; [onDrag] reports every dragged value.
 */
@Composable
private fun StepSetting(title: String, stops: List<String>, index: Int, tag: String, library: OfflineLibrary?,
    candidate: ((Int) -> CachePolicy)?, text: String, committed: Int = index, onDrag: (Int) -> Unit = {},
    rowTag: String = "${tag}Row", sliderTag: String = "${tag}Slider", idle: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null, commit: (Int) -> Unit) {
    var value by remember { mutableIntStateOf(index) }
    var dragging by remember { mutableStateOf(false) }
    // While nothing is staged ([idle]) and no drag runs, the thumb follows the shown value (also when a neighbour pushes it);
    // a staged change keeps its value until the sheet ends, and Cancel or a swipe-down shows the saved one again (§5.4).
    LaunchedEffect(index, idle) { if (idle && !dragging) value = index }
    var frees by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(value, dragging) {
        frees = null
        if (!dragging || value == committed || library == null || candidate == null) return@LaunchedEffect
        delay(150)
        frees = library.preview(candidate(value)).bytes
    }
    // With an [icon] (Settings → Playback) the tile leads as in the other rows there, so the titles line up.
    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).testTag(rowTag), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (icon != null) SettingsIconTile(icon)
        Column(Modifier.weight(1f)) {
            // The slider below already reads "<title>, <value>", so the visual title and value are hidden from
            // TalkBack (one stop, not three); the explanation line stays readable.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f).semantics { hideFromAccessibility() }, style = RowTitle)
                Text(stops[value], style = RowTitle, textAlign = TextAlign.End,
                    modifier = Modifier.semantics { hideFromAccessibility() }.testTag("${tag}Value"))
            }
            Slider(value = value.toFloat(), valueRange = 0f..stops.lastIndex.toFloat(), steps = (stops.size - 2).coerceAtLeast(0),
                onValueChange = { raw ->
                    dragging = true
                    val i = Math.round(raw).coerceIn(0, stops.lastIndex)
                    if (i != value) { value = i; onDrag(i) }
                },
                onValueChangeFinished = { dragging = false; if (value != committed) commit(value) },
                modifier = Modifier.testTag(sliderTag).semantics {
                    contentDescription = title
                    stateDescription = stops[value]
                    setProgress { target -> val i = Math.round(target).coerceIn(0, stops.lastIndex); value = i; onDrag(i); if (i != committed) commit(i); true }
                })
            val preview = when {
                dragging && (frees ?: 0) > 0 -> "Frees about ${size(frees!!)}" to Caution
                else -> null
            }
            preview?.let { (line, color) -> Text(line, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.testTag("${tag}Preview")) }
            Text(text, style = RowText, color = SettingsText2)
        }
    }
}

/**
 * "Tracks cached ahead" (2–10, [dev.coda.android.cache.CacheSettings.tracksAhead]) in Settings →
 * Playback. The read-ahead window reads the stored value at each pass, so a change applies to the next one.
 */
@Composable
internal fun TracksAheadSetting() {
    val app = LocalContext.current.applicationContext as CodaApp
    val scope = rememberCoroutineScope()
    var ahead by remember { mutableIntStateOf(4) }
    LaunchedEffect(Unit) { ahead = withContext(Dispatchers.IO) { app.cacheSettings.loadTracksAhead() } }
    StepSetting("Tracks cached ahead", (2..10).map { "$it tracks" }, ahead - 2, tag = "tracksAhead", library = null, candidate = null,
        text = "Coda saves the next songs in your queue so they play without a gap, also on a weak connection.",
        rowTag = "tracksAheadRow", sliderTag = "tracksAheadSlider",
        icon = Icons.AutoMirrored.Outlined.QueueMusic) { val next = it + 2; ahead = next; scope.launch { app.cacheSettings.setTracksAhead(next) } }
}

/** Hid the Kept offline slider for 1.0; RC-1a shows it again as "Prefer offline". */
internal const val KEEP_OFFLINE_IN_SETTINGS = true

private fun kinds(sets: List<CachePlanner.KeptSet>): String {
    val albums = sets.all { it.key.startsWith("album/") }
    val playlists = sets.all { it.key.startsWith("playlist/") }
    val one = sets.size == 1
    return when {
        albums -> if (one) "album" else "albums"
        playlists -> if (one) "playlist" else "playlists"
        else -> if (one) "item" else "items"
    }
}

/** "This frees about X GB", live while open, "Changed while open", Confirm re-checks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FreeSpaceSheet(pending: Pending, ahead: Int, onRefresh: (CachePlanner.Plan) -> Unit, onCancel: () -> Unit,
    onConfirm: () -> Unit, library: OfflineLibrary?) {
    val plan = pending.current
    // Live: recomputed whenever the cache changes or free space moves, at most once a second.
    // Compared with what the sheet shows now, not with what it showed when the effect started (A, then B, then A again).
    val shown by rememberUpdatedState(plan)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    if (library != null) LaunchedEffect(pending.policy, pending.deleting, lifecycle) {
        if (pending.deleting) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(1_000)
                val next = library.preview(pending.policy)
                if (next != shown) onRefresh(next)
            }
        }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onCancel, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), modifier = Modifier.testTag("freeSpaceSheet")) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            // The explanation scrolls when it outgrows the sheet (large fonts, long names); the buttons stay
            // below it. At normal size it fits and wraps its content as before.
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("freeSpaceBody")) {
                Text("This frees about ${size(plan.bytes)}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("freeSpaceTitle"))
                if (OfflineLibrary.changedMaterially(pending.first, plan)) {
                    val added = plan.keptSetsGone.filter { set -> pending.first.keptSetsGone.none { it.key == set.key } }
                    Text("Changed while open: " + (if (added.isEmpty()) "" else
                        "${if (added.size == 1) "one more" else "${added.size} more"} ${kinds(added)} you prefer offline (${added.joinToString(", ") { it.title.orEmpty() }}), ") +
                        "now ${size(plan.bytes)}.", Modifier.padding(top = 8.dp).testTag("changedWhileOpen"),
                        style = MaterialTheme.typography.bodyMedium, color = Caution)
                }
                Text("Coda deletes this music now:", Modifier.padding(top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.bodyMedium)
                val notKept = plan.notKeptFiles
                val sets = plan.keptSetsGone
                val keptBytes = plan.keptFiles.sumOf { it.bytes }
                // "Prefer offline" is named only when such music is part of the deletion; otherwise the row is just "Music".
                val anyKept = sets.isNotEmpty() || keptBytes > 0
                if (notKept.isNotEmpty()) SheetRow("${if (anyKept) "Other music" else "Music"} · ${notKept.size} ${if (notKept.size == 1) "track" else "tracks"}", notKept.sumOf { it.bytes })
                if (anyKept) {
                    val names = sets.map { it.title.orEmpty() }
                    val shown = names.take(3).joinToString(", ") + if (names.size > 3) " and ${names.size - 3} more" else ""
                    SheetRow(listOfNotNull("Preferred offline", sets.takeIf { it.isNotEmpty() }?.let { "${it.size} ${kinds(it)}" },
                        shown.takeIf { it.isNotEmpty() }).joinToString(" · "), keptBytes)
                }
                if (plan.missingBytes > 0) Text("Even then, ${size(plan.missingBytes)} is missing. Until there's room, Coda reads ahead less and streams the rest.",
                    Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = Caution)
                Text("What's playing and the next $ahead tracks stay${if (anyKept) ", and so does any album or playlist you prefer offline that they're in" else ""}. Anything deleted downloads again when you play it.",
                    Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel, Modifier.testTag("freeSpaceCancel")) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onConfirm, enabled = !pending.deleting, modifier = Modifier.testTag("freeSpaceDelete"),
                    colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Color(0xFF2A0B08))) {
                    Text(if (pending.deleting) "Deleting…" else "Delete ${size(plan.bytes)}")
                }
            }
        }
    }
}

@Composable
private fun SheetRow(label: String, bytes: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(size(bytes), style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"))
    }
}
