package dev.coda.android.cache

import android.content.Context
import dev.coda.android.settings.save
import dev.coda.android.settings.readOrDefaults
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dev.coda.android.settings.SettingsKeys
import dev.coda.android.settings.appSettings
import dev.coda.core.cache.CachePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Music storage settings: three lifetimes and Keep free space, stored as keys, and
 * "Tracks cached ahead" (unchanged). App-wide. No stored preset: it is derived from the three times.
 */
class CacheSettings(private val store: Store) {
    /** The few storage calls used, so the reading rules are testable on the JVM. */
    interface Store {
        fun string(key: String): String?
        fun int(key: String): Int?
        fun put(values: Map<String, Any>): Boolean
    }

    constructor(context: Context) : this(DataStoreBacked(context.applicationContext.appSettings))

    /**
     * The app settings DataStore. Callers are already off the main thread (IO), so the calls block on it; after the
     * first read DataStore answers from memory.
     */
    internal class DataStoreBacked(private val data: DataStore<Preferences>) : Store {
        private fun read() = runBlocking { data.readOrDefaults() }
        override fun string(key: String) = read()[SettingsKeys.cacheString(key)]
        override fun int(key: String) = read()[SettingsKeys.cacheInt(key)]
        /** False when the write failed (like a failed SharedPreferences commit); the values stay in memory. */
        override fun put(values: Map<String, Any>): Boolean = runBlocking {
            data.save { prefs ->
                values.forEach { (key, value) ->
                    if (value is Int) prefs[SettingsKeys.cacheInt(key)] = value else prefs[SettingsKeys.cacheString(key)] = value.toString()
                }
            }
        }
    }

    /** Null until [loadTracksAhead] ran. */
    @Volatile private var ahead: Int? = null

    /**
     * Memory only (main-thread safe: playback reads it for its pins): the stored value once [loadTracksAhead] ran (the
     * app does it on IO at start), the default before.
     */
    val tracksAhead: Int get() = ahead ?: DEFAULT_TRACKS_AHEAD

    /** Reads the stored value into memory and returns it. IO. */
    @Synchronized fun loadTracksAhead(): Int =
        (store.int(TRACKS_AHEAD) ?: DEFAULT_TRACKS_AHEAD).coerceIn(2, 10).also { ahead = it }

    /** False when the write failed: the value in memory then stays what is stored. */
    suspend fun setTracksAhead(count: Int) = withContext(Dispatchers.IO) {
        require(count in 2..10)
        synchronized(this@CacheSettings) { store.put(mapOf(TRACKS_AHEAD to count)).also { if (it) ahead = count } }
    }

    private val current by lazy {
        MutableStateFlow(CachePolicy.fromKeys(store.string(KEPT), store.string(HEARD_ONCE), store.string(HEARD_AGAIN), store.int(KEEP_FREE_GB)))
    }

    /** Read through [CachePolicy.normalized]; missing or unknown keys read as the default (Balanced, 5 GB). */
    val policy: StateFlow<CachePolicy> get() = current

    private val custom by lazy { MutableStateFlow((store.int(CUSTOM_CHOSEN) ?: 0) == 1) }

    /** Custom was chosen (a Custom set equal to a preset's times stays Custom). See [CachePolicy.selection]. */
    val customChosen: StateFlow<Boolean> get() = custom

    /** Writes the flag (a commit: IO only). */
    fun setCustomChosen(value: Boolean) {
        store.put(mapOf(CUSTOM_CHOSEN to if (value) 1 else 0))
        custom.value = value
    }

    /** Saves [policy] (normalized) and, when [customChosen] is given, the Custom flag in the same write. IO. */
    fun setPolicy(policy: CachePolicy, customChosen: Boolean? = null) {
        val value = policy.normalized()
        val values = mutableMapOf<String, Any>(KEPT to value.kept.key, HEARD_ONCE to value.heardOnce.key, HEARD_AGAIN to value.heardAgain.key,
            KEEP_FREE_GB to (value.keepFreeBytes / CachePolicy.GB).toInt())
        if (customChosen != null) values[CUSTOM_CHOSEN] = if (customChosen) 1 else 0
        store.put(values)
        current.value = value
        if (customChosen != null) custom.value = customChosen
    }

    companion object {
        const val TRACKS_AHEAD = "tracksAhead"
        const val DEFAULT_TRACKS_AHEAD = 4
        const val KEPT = "keptFor"
        const val HEARD_ONCE = "heardOnceFor"
        const val HEARD_AGAIN = "heardAgainFor"
        const val KEEP_FREE_GB = "keepFreeGb"
        const val CUSTOM_CHOSEN = "customChosen"
    }
}
