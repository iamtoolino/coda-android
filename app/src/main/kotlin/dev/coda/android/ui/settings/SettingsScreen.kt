package dev.coda.android.ui.settings

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * The signed-in account as the Account card shows it: [server] is the saved session's address, [user] its user name.
 * Both come from the session the app already holds; nothing is stored for this.
 */
data class SettingsAccount(val server: String, val user: String)

/** The Offline group's switch (PO-approved, off by default) with its setter. */
data class OfflineSwitches(val completeOnly: Boolean, val setCompleteOnly: (Boolean) -> Unit)

/** Permitted settings/actions (requirements 3a), PF-11c "Settings: Grouped, About page". */
@Composable
fun SettingsScreen(replayGain: Boolean, setReplayGain: (Boolean) -> Unit, signOut: () -> Unit, back: () -> Unit,
    /** Open Music storage directly (the snackbar's Details); [storageOpened] clears the request. */
    storageRequested: Boolean = false, storageOpened: () -> Unit = {},
    /** Shown in the Account card; null shows Sign out only. */
    account: SettingsAccount? = null,
    /** The Offline group; null hides it (screens that host Settings without the shell). */
    offline: OfflineSwitches? = null) {
    // Not saveable, like Licences before it: Settings opened again starts on Settings, not on About.
    var about by remember { mutableStateOf(false) }
    if (about) {
        dev.coda.android.ui.about.AboutScreen { about = false }
        return
    }
    var storage by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(storageRequested) { if (storageRequested) { storage = true; storageOpened() } }
    if (storage) {
        MusicStorageScreen { storage = false }
        return
    }
    var confirm by remember { mutableStateOf(false) }
    if (confirm) dev.coda.android.ui.common.SignOutDialog(onDismiss = { confirm = false }, onSignOut = { confirm = false; signOut() })
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).then(dev.coda.android.ui.common.pageSemanticsOf("Settings")).testTag("settings")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Settings", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        }
        SettingsGroupLabel("Playback", top = 4)
        SettingsCard {
            SwitchRow("ReplayGain", "Even out loudness, keep album dynamics", Icons.Outlined.Equalizer, "replayGainSwitch", replayGain, setReplayGain)
            SettingsRowDivider()
            // Read-ahead is a playback setting, so it moved here from Music storage.
            TracksAheadSetting()
            SettingsRowDivider()
            // One row replaces the cache status text.
            MusicStorageRow(onClick = { storage = true })
        }
        // Under Music storage, the other "what's on this phone" settings.
        if (offline != null) {
            SettingsGroupLabel("Offline")
            SettingsCard {
                SwitchRow("Only complete albums", "When offline, hide albums with songs missing on this phone", Icons.Outlined.CloudOff,
                    "offlineCompleteSwitch", offline.completeOnly, offline.setCompleteOnly)
            }
        }
        SettingsGroupLabel("Account")
        SettingsCard {
            if (account != null) {
                // As on the sign-in screen (Recent servers): the scheme dimmed, the rest of the address in full.
                val scheme = account.server.substringBefore("://", "")
                val address = buildAnnotatedString {
                    if (scheme.isNotEmpty()) withStyle(SpanStyle(color = SettingsTextDim)) { append("$scheme://") }
                    append(account.server.substringAfter("://"))
                }
                SettingsRow(address, Modifier.testTag("settingsAccount"), icon = Icons.Outlined.Dns, subtitle = "Signed in as ${account.user}")
                SettingsRowDivider()
            }
            SettingsRow("Sign out", Modifier.testTag("settingsSignOut"), icon = Icons.AutoMirrored.Outlined.Logout, danger = true,
                onClick = { confirm = true })
        }
        Spacer(Modifier.height(14.dp))
        SettingsCard {
            SettingsRow("About Coda", Modifier.testTag("settingsAbout"), icon = Icons.Outlined.Info,
                subtitle = "${dev.coda.android.ui.about.versionLine} · licences", onClick = { about = true }, chevron = true)
        }
        // Debug and preview builds only; release compiles an empty inline stub and skips the card.
        if (dev.coda.android.diag.BatteryLog.ENABLED) {
            SettingsGroupLabel("Developer · preview build")
            SettingsCard {
                dev.coda.android.diag.BatteryLogSettings()
            }
        }
        Spacer(Modifier.height(dev.coda.android.ui.shell.LocalDockPadding.current + 16.dp))
    }
}

/** One toggleable row, so TalkBack reads the switch with its title and state. */
@Composable
private fun SwitchRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tag: String,
    checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingsRow(title, Modifier.testTag(tag)
        .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
        .semantics { stateDescription = if (checked) "On" else "Off" },
        icon = icon, subtitle = subtitle) {
        Switch(checked = checked, onCheckedChange = null)
    }
}
