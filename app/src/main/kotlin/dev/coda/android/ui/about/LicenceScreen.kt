package dev.coda.android.ui.about

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bundled static notices are available offline and loaded away from composition's main thread. */
@Composable
fun LicenceScreen(back: () -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    val up = { if (selected != null) selected = null else back() }
    dev.coda.android.ui.shell.PageBackHandler(onBack = up)
    val assets = LocalContext.current.assets
    val notices by produceState<List<String>?>(null, assets, selected) {
        value = null
        val file = selected
        if (file != null) value = withContext(Dispatchers.IO) {
            assets.open("licenses/$file").bufferedReader().use { it.readText() }.split("\n\n")
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().testTag("licences")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = up) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Licences", style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            item { Text("Coda · Apache License 2.0", style = MaterialTheme.typography.titleMedium) }
            if (selected == null) {
                item { TextButton(onClick = { selected = "ThirdPartyNotices.txt" }) { Text("Bundled libraries") } }
                item { TextButton(onClick = { selected = "FFmpeg-LGPL-2.1.txt" }) { Text("FFmpeg · LGPL-2.1") } }
                item { TextButton(onClick = { selected = "GoogleSansFlex-OFL.txt" }) { Text("Google Sans Flex · SIL OFL 1.1") } }
            } else if (notices == null) item { Text("Loading notices…") }
            items(notices?.size ?: 0) { index ->
                Text(notices!![index], Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
