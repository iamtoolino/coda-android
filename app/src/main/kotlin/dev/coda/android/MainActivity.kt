package dev.coda.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.coda.android.ui.theme.CodaTheme

class MainActivity : ComponentActivity() {
    /** A pending "open Music storage" request from App info → Manage space; the shell clears it. */
    private var musicStorageRequested by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // A recreated activity keeps its launch intent; only a fresh launch acts on the extra.
        if (savedInstanceState == null) musicStorageRequested = opensMusicStorage(intent)
        setContent {
            CodaTheme {
                dev.coda.android.ui.login.SessionRoot((application as CodaApp).sessions,
                    openMusicStorage = musicStorageRequested, musicStorageOpened = { musicStorageRequested = false })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (opensMusicStorage(intent)) musicStorageRequested = true
    }

    override fun onStart() {
        super.onStart()
        // A user-initiated job can only be scheduled while the app is visible; work left pending (for example
        // after the process was started in the background) gets its job here.
        (application as CodaApp).offlineLibrary.value?.downloads?.value?.takeIf { it.active || it.coversDue }?.let { dev.coda.android.cache.DownloadJobs.ensure(this, anyNetwork = it.queue != null) }
    }

    companion object {
        /**
         * Boolean extra that opens Settings → Music storage. Sent by ManageSpaceActivity (release builds without
         * -pre, app/src/batteryLogOff); a task relaunched from Recents carries its old intent, so that one is ignored.
         */
        const val EXTRA_OPEN_MUSIC_STORAGE = "dev.coda.android.extra.OPEN_MUSIC_STORAGE"

        internal fun opensMusicStorage(intent: Intent?) = intent != null &&
            intent.getBooleanExtra(EXTRA_OPEN_MUSIC_STORAGE, false) &&
            intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0
    }
}
