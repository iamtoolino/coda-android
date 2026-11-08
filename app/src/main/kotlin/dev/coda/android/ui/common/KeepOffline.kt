package dev.coda.android.ui.common

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import dev.coda.android.CodaApp
import dev.coda.android.cache.OfflineLibrary
import dev.coda.core.cache.*
import dev.coda.core.subsonic.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Minimal hook-up of an add's questions (the final UI is the design studio's): "Make room?" with what would be
 * deleted, or "too big". Confirming repeats the add with that answer. Adds download only on proper Wi-Fi and never
 * ask about mobile data; an add made elsewhere just says it will download on Wi-Fi ([notice]).
 */
@Stable
class AddPrompt internal constructor(private val scope: CoroutineScope, private val askNotifications: () -> Unit = {}, private val host: () -> OfflineLibrary?) {
    class Question(val item: KeptItem, val result: AddResult)
    var question by mutableStateOf<Question?>(null)
        internal set
    var error by mutableStateOf<String?>(null)
        internal set
    /** A short message after an add (shown as a Night snackbar). */
    var notice by mutableStateOf<String?>(null)
        internal set
    var busy by mutableStateOf(false)
        private set

    /** [makeRoom]: the "Make room?" the user confirmed; the add deletes only what it listed, or asks again. */
    fun start(item: KeptItem, makeRoom: AddResult.NeedsRoom? = null) {
        val library = host() ?: return
        scope.launch {
            busy = true
            try {
                val result = library.add(item, makeRoom)
                question = if (result is AddResult.Started) null else Question(item, result)
                if (result is AddResult.Started) askNotifications()
                if (result is AddResult.Started && result.waitsForWifi) notice = WAITS_FOR_WIFI
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { error = "Couldn't save offline music. Try again." }
            finally { busy = false }
        }
    }

    /**
     * Prefer offline switched off for [key] (no dialog, nothing deleted). A failed save (a full
     * disk) leaves the set kept and says so as an add's failure does; the switch can be turned off again.
     */
    fun stop(key: String) {
        val library = host() ?: return
        scope.launch {
            try { library.stopKeeping(key) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { error = "Couldn't save offline music. Try again." }
        }
    }
}

@Composable
fun rememberAddPrompt(): AddPrompt {
    val app = LocalContext.current.applicationContext as CodaApp
    val scope = rememberCoroutineScope()
    val ask = rememberNotificationPermissionAsk()
    return remember(scope, ask) { AddPrompt(scope, ask) { app.offlineLibrary.value } }
}

private fun mb(bytes: Long) = (bytes.coerceAtLeast(0) + 999_999) / 1_000_000

/** After an add on mobile data, a hotspot or offline (minimal wording; final UI: the design studio). */
const val WAITS_FOR_WIFI = "Saved for offline · downloads on Wi-Fi"

@Composable
fun AddPromptDialogs(prompt: AddPrompt, snackbar: SnackbarHostState) {
    LaunchedEffect(prompt.notice) {
        // Cleared only after the snackbar is gone: clearing it first would restart (and cancel) this effect. Queued behind
        // a notice already showing (an Undo, the Details), never cutting it short.
        val notice = prompt.notice ?: return@LaunchedEffect
        try { snackbar.showNotice(notice) } finally { prompt.notice = null }
    }
    val question = prompt.question
    when (val result = question?.result) {
        is AddResult.NeedsRoom -> AlertDialog(onDismissRequest = { prompt.question = null },
            title = { Text("Make room?") },
            text = { Text("This deletes ${mb(result.bytes)} MB of music, other music first" +
                (if (result.items.isEmpty()) "." else ", then ${result.items.size} " + (if (result.items.size == 1) "item" else "items") +
                    " you prefer offline as a whole: " + result.titles.joinToString(", ") + ".")) },
            confirmButton = { TextButton(onClick = { prompt.question = null; prompt.start(question.item, makeRoom = result) },
                Modifier.testTag("makeRoomConfirm")) { Text("OK") } },
            dismissButton = { TextButton(onClick = { prompt.question = null }) { Text("Cancel") } })
        is AddResult.TooBig -> AlertDialog(onDismissRequest = { prompt.question = null },
            title = { Text("Not enough space") },
            text = { Text("This needs ${mb(result.missingBytes)} MB more than this phone can free." +
                if (result.lineBytes > 0) " Free ${dev.coda.core.cache.CacheSummary.size(result.missingBytes)} or lower Keep free space " +
                    "(${result.lineBytes / 1_000_000_000L} GB) in Music storage." else "") },
            confirmButton = { TextButton(onClick = { prompt.question = null }) { Text("Close") } })
        else -> Unit
    }
    prompt.error?.let { message ->
        AlertDialog(onDismissRequest = { prompt.error = null }, title = { Text("Offline music") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = { prompt.error = null }) { Text("Close") } })
    }
}

/**
 * Re-syncs a kept album or playlist when its page loaded fresh (no UI: PF-10a removed the page's download chip; the ⋮ menu's
 * Prefer offline row shows the state; switching it off stops that set's downloads).
 */
@Composable
fun KeepOfflineSync(key: String, songs: List<Song>, fresh: Boolean = true, title: String? = null) {
    val app = LocalContext.current.applicationContext as CodaApp
    val library by app.offlineLibrary.collectAsState()
    val host = library ?: return
    val items by host.items.collectAsState()
    val item = remember(key, songs, title) { KeptItem(key, songs.map(KeptTrack::from), title) }
    val kept = items.any { it.key == key }
    // A successful server refresh re-syncs a kept playlist (and album) in the background; it never asks. Opening a
    // kept playlist's page also renews it like an add, so a playlist re-syncs even when unchanged.
    LaunchedEffect(host, item, fresh) {
        if (fresh && kept && (key.startsWith(OfflineLibrary.PLAYLIST) ||
                items.firstOrNull { it.key == key }?.tracks?.map { it.id } != item.tracks.map { it.id })) {
            try { host.resync(item) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
        }
    }
}
