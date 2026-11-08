package dev.coda.android.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ReplayGain and the "Only complete albums" persistence (requirements 3a), in the app settings DataStore.
 * Reads and writes run off the main thread. The defaults (Off) hold until a saved choice is read; an explicit toggle
 * never loses to that read. [scope] must be a main-thread scope.
 */
class SettingsStore internal constructor(private val store: DataStore<Preferences>, private val scope: CoroutineScope) {
    constructor(context: Context, scope: CoroutineScope) : this(context.applicationContext.appSettings, scope)

    private val replayGain = MutableStateFlow(false)
    private var touched = false
    val replayGainEnabled: StateFlow<Boolean> = replayGain.asStateFlow()
    /** Offline, hide the albums with tracks missing on this phone. Off by default. */
    private val completeOnly = MutableStateFlow(false)
    private var completeOnlyTouched = false
    val completeAlbumsOnly: StateFlow<Boolean> = completeOnly.asStateFlow()

    init {
        scope.launch {
            val saved = store.readOrDefaults()
            if (!touched) replayGain.value = saved[SettingsKeys.REPLAY_GAIN] ?: false
            if (!completeOnlyTouched) completeOnly.value = saved[SettingsKeys.COMPLETE_ALBUMS_ONLY] ?: false
        }
    }

    fun setReplayGainEnabled(enabled: Boolean) {
        touched = true
        replayGain.value = enabled
        // Writes the latest value when it runs, so quick consecutive toggles cannot persist a stale one.
        scope.launch { store.save { it[SettingsKeys.REPLAY_GAIN] = replayGain.value } }
    }

    fun setCompleteAlbumsOnly(enabled: Boolean) {
        completeOnlyTouched = true
        completeOnly.value = enabled
        scope.launch { store.save { it[SettingsKeys.COMPLETE_ALBUMS_ONLY] = completeOnly.value } }
    }
}
