package dev.coda.android.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * The app-wide settings, one typed Preferences DataStore (`files/datastore/settings.preferences_pb`), not per
 * account: ReplayGain, Complete albums only, recent servers, Music storage, library sorts and density, the download
 * notification flag. The playback accent is its own single-record store, read at cold start before anything else.
 * DataStore reads and writes off the main thread and keeps the last value in memory. No import from the old
 * SharedPreferences (6 Nov: nothing released yet); a fresh store reads as the defaults, and so does a damaged one
 * (as a damaged SharedPreferences file did): these are preferences, not data that must fail closed.
 */
internal val Context.appSettings: DataStore<Preferences> by preferencesDataStore(name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() })

/** the last committed playback colour (`files/datastore/playback-accent.preferences_pb`). */
internal val Context.playbackAccentData: DataStore<Preferences> by preferencesDataStore(name = "playback-accent",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() })

/** Every key of [appSettings] in one place, so two owners never share a name. */
internal object SettingsKeys {
    val REPLAY_GAIN = booleanPreferencesKey("replayGainEnabled")
    val COMPLETE_ALBUMS_ONLY = booleanPreferencesKey("offlineCompleteAlbumsOnly")
    val RECENT_SERVERS = stringPreferencesKey("recentServers")
    val ARTIST_NEWEST_FIRST = booleanPreferencesKey("artistNewestFirst")
    val ALBUM_SORT = stringPreferencesKey("albumSort")
    val ALBUMS_DENSE = booleanPreferencesKey("albumsDense")
    val PLAYLIST_SORT = stringPreferencesKey("playlistSort")
    val DOWNLOAD_NOTIFICATIONS_ASKED = booleanPreferencesKey("downloadNotificationsAsked")

    /** Music storage ([dev.coda.android.cache.CacheSettings] names them). */
    fun cacheString(name: String) = stringPreferencesKey("trackCache.$name")
    fun cacheInt(name: String) = intPreferencesKey("trackCache.$name")
}

/** [playbackAccentData]'s fields. */
internal object AccentKeys {
    val ACCOUNT = stringPreferencesKey("account"); val IDENTITY = stringPreferencesKey("identity")
    val COVER = stringPreferencesKey("cover"); val KIND = stringPreferencesKey("kind")
    val RED = longPreferencesKey("red"); val GREEN = longPreferencesKey("green"); val BLUE = longPreferencesKey("blue")
}

/** The stored values; empty (the defaults) when the file cannot be read, as SharedPreferences did. */
internal suspend fun DataStore<Preferences>.readOrDefaults(): Preferences =
    try { data.first() } catch (_: java.io.IOException) { emptyPreferences() }

/** One atomic write; false when it failed (like a failed SharedPreferences commit, it never throws). */
internal suspend fun DataStore<Preferences>.save(transform: (MutablePreferences) -> Unit): Boolean =
    try { edit { transform(it) }; true } catch (_: java.io.IOException) { false }
