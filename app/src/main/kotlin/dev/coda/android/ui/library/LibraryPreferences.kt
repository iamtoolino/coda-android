package dev.coda.android.ui.library

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dev.coda.android.settings.SettingsKeys
import dev.coda.android.settings.readOrDefaults
import dev.coda.android.settings.save
import dev.coda.core.library.PlaylistSort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The library tabs' app-wide choices (DS-9 Albums sort and density, PL-3 Playlists sort, the artist "Newest first"), in the app
 * settings DataStore. Reads and writes run off the main thread; writes run in [scope] (the app's), so leaving the
 * library screen never drops one.
 */
internal class LibraryPreferences(private val store: DataStore<Preferences>, private val scope: CoroutineScope) {
    data class Saved(val artistNewestFirst: Boolean, val albumSort: AlbumSort, val albumsDense: Boolean, val playlistSort: PlaylistSort)

    /** Unknown or missing names read as the defaults (Title, Name). */
    suspend fun read(): Saved = store.readOrDefaults().let { p ->
        Saved(p[SettingsKeys.ARTIST_NEWEST_FIRST] ?: false,
            p[SettingsKeys.ALBUM_SORT]?.let { name -> AlbumSort.entries.firstOrNull { it.name == name } } ?: AlbumSort.TITLE,
            p[SettingsKeys.ALBUMS_DENSE] ?: false,
            p[SettingsKeys.PLAYLIST_SORT]?.let { name -> PlaylistSort.entries.firstOrNull { it.name == name } } ?: PlaylistSort.NAME)
    }

    fun setArtistNewestFirst(value: Boolean) = write { it[SettingsKeys.ARTIST_NEWEST_FIRST] = value }
    fun setAlbumSort(value: AlbumSort) = write { it[SettingsKeys.ALBUM_SORT] = value.name }
    fun setAlbumsDense(value: Boolean) = write { it[SettingsKeys.ALBUMS_DENSE] = value }
    fun setPlaylistSort(value: PlaylistSort) = write { it[SettingsKeys.PLAYLIST_SORT] = value.name }

    private fun write(transform: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) { scope.launch { store.save(transform) } }
}
