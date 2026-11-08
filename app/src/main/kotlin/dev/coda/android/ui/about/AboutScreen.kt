package dev.coda.android.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.coda.android.BuildConfig
import dev.coda.android.R
import dev.coda.android.ui.settings.SettingsCard
import dev.coda.android.ui.settings.SettingsRow
import dev.coda.android.ui.settings.SettingsRowDivider
import dev.coda.android.ui.settings.SettingsText2

/** "Version 0.11.0 (812)": the About hero and the Settings row's subtitle. */
internal val versionLine get() = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_COUNT})"

/** About Coda (version, build details in every build, Licences), opened from Settings. */
@Composable
fun AboutScreen(back: () -> Unit) {
    var notices by remember { mutableStateOf(false) }
    if (notices) {
        LicenceScreen { notices = false }
        return
    }
    dev.coda.android.ui.shell.PageBackHandler(onBack = back)
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).then(dev.coda.android.ui.common.pageSemanticsOf("About Coda")).testTag("about")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("About Coda", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // The launcher icon: its two 108-unit adaptive layers, cropped to the visible 72 units.
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)), Alignment.Center) {
                Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.requiredSize(96.dp))
                Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(96.dp))
            }
            Text("Coda", style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold))
            Text(versionLine, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), color = SettingsText2)
        }
        SettingsCard {
            listOfNotNull(
                "Git commit" to BuildConfig.GIT_COMMIT,
                ("Git tag" to BuildConfig.GIT_TAG).takeIf { it.second != "unknown" },
                "Source" to BuildConfig.GIT_DESCRIBE,
                "Configuration" to if (BuildConfig.DEBUG) "Debug" else "Release",
            ).forEachIndexed { index, (label, value) ->
                if (index > 0) SettingsRowDivider()
                Column(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.Center) {
                    Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp), color = SettingsText2)
                    val style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(value, style = if (label == "Configuration") style else style.copy(fontFamily = FontFamily.Monospace))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        SettingsCard {
            SettingsRow("Licences", Modifier.testTag("aboutLicences"), icon = Icons.Outlined.Description, subtitle = "Open-source notices",
                onClick = { notices = true }, chevron = true)
        }
        Spacer(Modifier.height(dev.coda.android.ui.shell.LocalDockPadding.current + 16.dp))
    }
}
