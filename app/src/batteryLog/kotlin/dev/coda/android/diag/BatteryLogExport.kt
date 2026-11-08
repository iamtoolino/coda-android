package dev.coda.android.diag

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import dev.coda.android.ui.settings.SettingsRow
import dev.coda.android.ui.settings.SettingsText2

/** "Export battery log": a plain-text summary followed by the raw log, shared through the share sheet. */
internal object BatteryLogExport {
    private val name = Regex("coda-battery-[0-9]{8}-[0-9]{6}\\.txt")
    fun directory(context: Context) = File(context.cacheDir, "battery-export")
    fun authority(context: Context) = context.packageName + ".batterylog"

    /** Closes the running segment, writes the export file (replacing older ones) and returns it. Not on Main. */
    fun write(context: Context): File? = try { writeFile(context) } catch (e: Exception) { null }

    private fun writeFile(context: Context): File? {
        val recorder = BatteryLog.active ?: return null
        val lines = recorder.sync { closeSegment(); log.lines() }.map(BatteryLogFormat::scrubLine)
        val header = listOf(
            "Coda battery log (BAT-1a, preview diagnostics)",
            "Build: ${dev.coda.android.BuildConfig.VERSION_NAME} (${BatteryLogFormat.word(dev.coda.android.BuildConfig.GIT_COMMIT.take(10))}), " +
                "Android ${android.os.Build.VERSION.SDK_INT}",
            "Content: ids, counts and durations only; no titles, names, server addresses or credentials.",
            "Segments are split at screen, playback and charging changes and every ${BatteryLog.SNAPSHOT_MS / 60_000} min of awake time " +
                "while the app is in use, a player is loaded or a lock is held (an idle process is not sampled).",
            "Locks observed: Coda playback wake lock, Coda stream Wi-Fi lock, DL-1 job. NOT observed: the Media3 " +
                "ExoPlayer:WakeLockManager wake lock; cross-check with `dumpsys batterystats` before concluding that no lock is held.",
        )
        val dir = directory(context).apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val file = File(dir, "coda-battery-$stamp.txt")
        file.bufferedWriter().use { out ->
            out.write(BatteryLogFormat.summary(lines, header))
            out.write("\n==== raw log (oldest first) ====\n")
            lines.forEach { out.write(it); out.write("\n") }
        }
        return file
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri = Uri.Builder().scheme("content").authority(authority(context)).appendPath(file.name).build()
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Export battery log").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun resolve(context: Context, uri: Uri): File? {
        val file = uri.lastPathSegment?.takeIf { it.matches(name) && uri.pathSegments.size == 1 } ?: return null
        return File(directory(context), file).takeIf { it.isFile }
    }
}

/** Serves only the current export file, read-only, to the app the user picked in the share sheet. */
class BatteryLogProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "text/plain"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        val file = BatteryLogExport.resolve(context!!, uri) ?: throw java.io.FileNotFoundException()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val file = BatteryLogExport.resolve(context!!, uri) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) file.name else if (it == OpenableColumns.SIZE) file.length() else null })
        }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

/** The battery log row of the preview-only Developer card in Settings: size, then Export and Clear. */
@Composable
fun BatteryLogSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var size by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(busy) { size = withContext(Dispatchers.IO) { BatteryLog.active?.log?.sizeBytes() } }
    SettingsRow("Battery log · ${size?.let { "${(it + 1023) / 1024} KB" } ?: "-"}", icon = Icons.Outlined.BatteryStd,
        subtitle = "Counts and timings only") {
        Row {
            IconButton(enabled = !busy, modifier = Modifier.testTag("exportBatteryLog"), onClick = {
                busy = true
                scope.launch {
                    try {
                        val file = withContext(Dispatchers.IO) { BatteryLogExport.write(context) }
                        if (file != null) context.startActivity(BatteryLogExport.shareIntent(context, file))
                    } finally { busy = false }
                }
            }) { Icon(Icons.Outlined.Share, "Export battery log", Modifier.size(20.dp), tint = SettingsText2) }
            IconButton(enabled = !busy, modifier = Modifier.testTag("clearBatteryLog"), onClick = {
                busy = true
                scope.launch {
                    try { withContext(Dispatchers.IO) { runCatching { BatteryLog.active?.sync { clear() } } } } finally { busy = false }
                }
            }) { Icon(Icons.Outlined.Delete, "Clear battery log", Modifier.size(20.dp), tint = SettingsText2) }
        }
    }
}
