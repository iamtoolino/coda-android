package dev.coda.android.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.coda.core.cache.Lifetime

/** The sign-out confirmation's copy (wording: Dana, design lead). */
internal object SignOutCopy {
    const val TITLE = "Sign out?"
    const val BASE = "Signing out clears the saved login. Cached music stays on this phone."
    const val KEPT = "Music you prefer offline is removed if you then sign in to a different server or as a different user."
    const val OFFLINE = "The server can't be reached right now. You can sign in again only once it's reachable."

    /**
     * The lines after [BASE]: the kept-music note when music is kept offline and Kept is "As long as possible" (then the
     * account's kept music would otherwise stay), and, independently, the unreachable note while the server can't be reached.
     */
    fun extraLines(keptOffline: Boolean, kept: Lifetime, serverReachable: Boolean): List<String> =
        listOfNotNull(KEPT.takeIf { keptOffline && kept == Lifetime.MAX }, OFFLINE.takeUnless { serverReachable })
}

/** The one sign-out confirmation (Settings' Account card, the re-login sheet's "Change"). */
@Composable
fun SignOutDialog(onDismiss: () -> Unit, onSignOut: () -> Unit) {
    val app = LocalContext.current.applicationContext as? dev.coda.android.CodaApp
    val extra = if (app == null) emptyList() else {
        val library by app.offlineLibrary.collectAsState()
        // Freshest state: a bounded check starts now in the background; the line appears if it finds the server down.
        androidx.compose.runtime.LaunchedEffect(library) { library?.recheckReachability() }
        val reachable by remember(library) { library?.serverReachable ?: kotlinx.coroutines.flow.MutableStateFlow(true) }.collectAsState()
        val policy by app.cacheSettings.policy.collectAsState()
        // In memory (TrackCache keeps its maps for main-thread lookups): a "Keep offline" set with a track on the phone.
        val cache = app.trackCache
        val keptOffline = remember(cache, cache?.revision) { cache?.let { c -> c.addedSets.any { set -> set.trackIds.any(c::contains) } } == true }
        SignOutCopy.extraLines(keptOffline, policy.kept, reachable)
    }
    SignOutDialog(extra, onDismiss, onSignOut)
}

/** The dialog itself: [extra] lines after the unchanged base text ([SignOutCopy.extraLines]). */
@Composable
internal fun SignOutDialog(extra: List<String>, onDismiss: () -> Unit, onSignOut: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(SignOutCopy.TITLE) },
        text = {
            // Nothing kept: exactly the dialog as before.
            if (extra.isEmpty()) Text(SignOutCopy.BASE)
            else Column {
                Text(SignOutCopy.BASE)
                extra.forEachIndexed { index, line ->
                    Spacer(Modifier.height(12.dp))
                    Text(line, Modifier.testTag(if (line == SignOutCopy.OFFLINE) "signOutOfflineNote" else "signOutKeptNote"))
                }
            }
        },
        confirmButton = { TextButton(onClick = onSignOut) { Text("Sign out") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
