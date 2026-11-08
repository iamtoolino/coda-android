package dev.coda.android.ui.common

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dev.coda.android.settings.SettingsKeys
import dev.coda.android.settings.appSettings
import dev.coda.android.settings.save
import kotlinx.coroutines.launch

/**
 * The download notification (progress, Cancel) needs the notification permission on Android 13+. It is asked for
 * in context, when the first download starts, and once: a refusal is final until the user changes it in the system
 * settings. Without it the downloads still run (no Cancel then): switching Prefer offline off stops a set's downloads,
 * and the queue ⋮ stops the queue download.
 */
object NotificationPermission {
    fun granted(context: Context) = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** True once, for the first caller while not granted; reads and marks "asked" in one DataStore write, off the main thread. */
    suspend fun takeAsk(context: Context): Boolean = !granted(context) && takeOnce(context.applicationContext.appSettings)

    internal suspend fun takeOnce(store: DataStore<Preferences>): Boolean {
        var first = false
        store.save { if (it[SettingsKeys.DOWNLOAD_NOTIFICATIONS_ASKED] != true) { it[SettingsKeys.DOWNLOAD_NOTIFICATIONS_ASKED] = true; first = true } }
        return first
    }
}

/** Returns "ask now if it is time": call it when a download starts. */
@Composable
fun rememberNotificationPermissionAsk(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // The waiting state may have been posted (and refused) before the answer: post it again.
        if (granted) dev.coda.android.cache.DownloadNotifications.permissionGranted(context)
    }
    val scope = rememberCoroutineScope()
    return remember(launcher, scope) {
        { scope.launch { if (NotificationPermission.takeAsk(context)) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) } }
    }
}
