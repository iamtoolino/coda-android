package dev.coda.android.ui.library

import androidx.compose.foundation.layout.Column
import dev.coda.android.ui.common.NoticeAction
import dev.coda.android.ui.common.showNotice
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import dev.coda.android.CodaApp
import androidx.compose.ui.platform.LocalContext
import dev.coda.core.subsonic.SubsonicError
import kotlinx.coroutines.*
import java.time.LocalDate

/** The caller supplies an immutable snapshot of the entire queue, including earlier occurrences. */
@Composable
internal fun SavePlaylistDialog(songIds: List<String>, dismiss: () -> Unit, saved: (String) -> Unit,
    title: String = "Save as playlist", initialName: String? = null) {
    val session = LocalLibrarySession.current ?: return
    val sessions = (LocalContext.current.applicationContext as CodaApp).sessions
    var name by rememberSaveable { mutableStateOf(initialName ?: "Queue ${LocalDate.now()}") }
    var busy by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun save() {
        if (busy) return
        busy = true
        scope.launch {
            var failed = false
            try {
                val playlist = session.client.createPlaylist(name, songIds)
                ensureActive()
                if (sessions.isCurrent(session.token)) saved(playlist.id)
            } catch (e: CancellationException) { throw e }
            catch (_: SubsonicError.Unauthorized) { sessions.unauthorized(session.token) }
            catch (_: Exception) { failed = sessions.isCurrent(session.token) }
            finally { busy = false }
            if (failed && snackbar.showNotice("Couldn't save the playlist", NoticeAction.Retry, indefinite = true) == SnackbarResult.ActionPerformed) save()
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(title) },
        text = { Column {
            OutlinedTextField(name, { name = it }, label = { Text("Playlist name") }, enabled = !busy, singleLine = true)
            if (busy) LinearProgressIndicator()
            dev.coda.android.ui.common.CodaSnackbarHost(snackbar)
        } },
        confirmButton = { TextButton(enabled = name.isNotBlank() && !busy && songIds.isNotEmpty(), onClick = ::save) { Text("Save") } },
        dismissButton = { TextButton(enabled = !busy, onClick = dismiss) { Text("Cancel") } })
}
