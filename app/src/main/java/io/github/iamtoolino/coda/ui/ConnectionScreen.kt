package io.github.iamtoolino.coda.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import io.github.iamtoolino.coda.AppGraph
import io.github.iamtoolino.coda.BuildConfig
import io.github.iamtoolino.coda.data.ServerDiagnostics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.iamtoolino.coda.data.ServerCredentials

@Composable
internal fun ConnectionScreen(
    credentials: ServerCredentials,
    artworkRefreshing: Boolean,
    onBack: () -> Unit,
    onRefreshArtwork: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val diagnostics = remember(credentials) {
        RemoteDetailCoordinator<Unit, ServerDiagnostics>(scope, Unit) {
            AppGraph.withCurrentSession { serverDiagnostics() }
        }
    }
    LaunchedEffect(diagnostics) { diagnostics.load() }
    val state = diagnostics.state
    val info = state.value
    val status = when {
        state.isLoading -> "Checking…"
        state.errorMessage != null -> "Could not reach server — check connection and try again"
        else -> "Server responded successfully"
    }
    val serverRows = listOf(
        "Last check" to status,
        "Server" to credentials.serverUrl,
        "Account" to credentials.username,
        "Client" to (info?.clientName ?: "Not checked"),
        "Software" to (info?.software ?: "Not reported"),
        "Server version" to (info?.serverVersion ?: "Not reported"),
        "API version" to (info?.apiVersion ?: "Not reported"),
        "OpenSubsonic" to when (info?.openSubsonic) {
            true -> "Supported"
            false -> "Not supported"
            null -> "Not reported"
        },
        "Playback engine" to "AndroidX Media3 / ExoPlayer",
    )
    val buildRows = listOf(
        "Version" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        "Git commit" to BuildConfig.GIT_COMMIT,
        "Git tag" to BuildConfig.GIT_TAG,
        "Source" to BuildConfig.SOURCE_STATE,
        "Configuration" to BuildConfig.BUILD_TYPE,
        "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    )
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 4.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Text(
                text = "Connection",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Text("Coda", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            DiagnosticSection("Connection", serverRows)
            OutlinedButton(onClick = { diagnostics.refresh() }, enabled = !state.isLoading, modifier = Modifier.fillMaxWidth()) {
                Text("Check connection")
            }
            DiagnosticSection("Build", buildRows)
            OutlinedButton(onClick = {
                // Exclude account, server URL, and device-derived client name from shared diagnostics.
                val publicRows = serverRows.filterNot { it.first in setOf("Server", "Account", "Client") }
                val report = (publicRows + buildRows).joinToString("\n", prefix = "Coda diagnostics\n") { "${it.first}: ${it.second}" }
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Coda diagnostics", report))
            }, modifier = Modifier.fillMaxWidth()) { Text("Copy diagnostics") }
            Text("Copied diagnostics exclude your server address, account and client name.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = "If artwork was changed on the server, clear Coda's image cache to fetch it again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onRefreshArtwork,
                enabled = !artworkRefreshing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (artworkRefreshing) "Refreshing artwork…" else "Refresh artwork")
            }
            Text(
                text = "Disconnecting stops playback and removes the encrypted credentials from this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Disconnect")
            }
        }
    }
}

@Composable
private fun ConnectionValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            modifier = Modifier.weight(0.4f),
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            modifier = Modifier.weight(0.6f),
            textAlign = TextAlign.End,
            text = value,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun DiagnosticSection(title: String, values: List<Pair<String, String>>) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            values.forEach { (label, value) -> ConnectionValue(label, value) }
        }
    }
}
