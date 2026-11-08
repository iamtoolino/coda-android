package dev.coda.android.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.coda.android.artwork.PlaybackAccentStore
import dev.coda.android.cache.CacheSettings
import dev.coda.android.session.RecentServers
import dev.coda.android.ui.common.NotificationPermission
import dev.coda.android.ui.library.AlbumSort
import dev.coda.android.ui.library.LibraryPreferences
import dev.coda.core.accent.AccentSelection
import dev.coda.core.accent.AccentSelectionKind
import dev.coda.core.accent.ArtworkColor
import dev.coda.core.accent.PlaybackAccentMemory
import dev.coda.core.cache.CachePolicy
import dev.coda.core.cache.Lifetime
import dev.coda.core.library.PlaylistSort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Each settings owner on a real Preferences DataStore file. An empty store reads as the defaults; every value
 * round-trips through a new store on the same file (a restart). No import from SharedPreferences (6 Nov).
 */
class AppDataStoresTest {
    @get:Rule val folder = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()

    /** A new store (and scope) on [name]'s file, as after a process restart. */
    private fun open(name: String = "settings"): Pair<DataStore<Preferences>, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return PreferenceDataStoreFactory.create(scope = scope) { java.io.File(folder.root, "$name.preferences_pb") } to scope
    }

    /** Closes the previous store (one active DataStore per file), then opens the file again. */
    private fun restart(name: String = "settings"): Pair<DataStore<Preferences>, CoroutineScope> {
        scopes.forEach { it.cancel() }; scopes.clear()
        runBlocking { delay(50) } // the cancelled store releases the file
        return open(name)
    }

    @After fun close() { scopes.forEach { it.cancel() } }

    private suspend fun eventually(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(10) }

    @Test fun settingsStoreDefaultsThenRoundTrips() = runBlocking {
        val (store, scope) = open()
        val first = SettingsStore(store, scope)
        delay(200)
        assertFalse(first.replayGainEnabled.value)
        assertFalse(first.completeAlbumsOnly.value)
        first.setReplayGainEnabled(true)
        first.setCompleteAlbumsOnly(true)
        eventually { runBlocking { store.readOrDefaults()[SettingsKeys.COMPLETE_ALBUMS_ONLY] } == true }
        val (again, againScope) = restart()
        val second = SettingsStore(again, againScope)
        eventually { second.replayGainEnabled.value && second.completeAlbumsOnly.value }
    }

    @Test fun anExplicitToggleWinsOverTheFirstRead() = runBlocking {
        val (store, scope) = open()
        val settings = SettingsStore(store, scope)
        settings.setReplayGainEnabled(true) // before the saved (empty) value is read
        delay(200)
        assertTrue(settings.replayGainEnabled.value)
    }

    @Test fun recentServersDefaultsThenRoundTrip() = runBlocking {
        val (store, scope) = open()
        val recents = RecentServers(store, scope)
        delay(100)
        assertEquals(emptyList<Any>(), recents.servers.value)
        recents.add("https://one.example.org")
        recents.add("http://two.example.org")
        recents.remove("https://one.example.org")
        recents.add("https://three.example.org")
        val (again, againScope) = restart()
        val reread = RecentServers(again, againScope)
        eventually { reread.servers.value.isNotEmpty() }
        assertEquals(listOf("https://three.example.org", "http://two.example.org"), reread.servers.value.map { it.url })
    }

    @Test fun cacheSettingsDefaultsThenRoundTrip() = runBlocking {
        val (store, _) = open()
        val empty = CacheSettings(CacheSettings.DataStoreBacked(store))
        assertEquals(CachePolicy.DEFAULT, empty.policy.value)
        assertEquals(4, empty.tracksAhead)
        assertFalse(empty.customChosen.value)
        val custom = CachePolicy(Lifetime.MONTH, Lifetime.MONTH, Lifetime.MONTH)
        empty.setPolicy(custom, customChosen = true)
        empty.setTracksAhead(7)
        val (again, _) = restart()
        val reread = CacheSettings(CacheSettings.DataStoreBacked(again))
        assertEquals(custom.normalized(), reread.policy.value)
        assertTrue(reread.customChosen.value)
        assertEquals(7, reread.loadTracksAhead())
        assertEquals(7, reread.tracksAhead)
    }

    @Test fun libraryPreferencesDefaultsThenRoundTrip() = runBlocking {
        val (store, scope) = open()
        val library = LibraryPreferences(store, scope)
        assertEquals(LibraryPreferences.Saved(false, AlbumSort.TITLE, false, PlaylistSort.NAME), library.read())
        library.setArtistNewestFirst(true)
        library.setAlbumSort(AlbumSort.entries.last())
        library.setAlbumsDense(true)
        library.setPlaylistSort(PlaylistSort.entries.last())
        val expected = LibraryPreferences.Saved(true, AlbumSort.entries.last(), true, PlaylistSort.entries.last())
        eventually { runBlocking { library.read() } == expected }
        val (again, againScope) = restart()
        assertEquals(expected, LibraryPreferences(again, againScope).read())
    }

    @Test fun unknownSortNamesReadAsTheDefaults() = runBlocking {
        val (store, scope) = open()
        store.save { it[SettingsKeys.ALBUM_SORT] = "RETIRED"; it[SettingsKeys.PLAYLIST_SORT] = "RETIRED" }
        val saved = LibraryPreferences(store, scope).read()
        assertEquals(AlbumSort.TITLE, saved.albumSort)
        assertEquals(PlaylistSort.NAME, saved.playlistSort)
    }

    @Test fun downloadNotificationsAreAskedOnceAcrossRestarts() = runBlocking {
        val (store, _) = open()
        assertTrue("the first download asks", NotificationPermission.takeOnce(store))
        assertFalse("never twice", NotificationPermission.takeOnce(store))
        val (again, _) = restart()
        assertFalse("not after a restart either", NotificationPermission.takeOnce(again))
    }

    @Test fun playbackAccentIsEmptyThenRoundTripsExactlyAndClears() = runBlocking {
        val (store, scope) = open("playback-accent")
        val accent = PlaybackAccentStore(store, scope)
        assertNull(accent.read())
        // Doubles that do not survive a decimal round-trip: the raw bits must come back.
        val memory = PlaybackAccentMemory("album:a1", "cover-1", AccentSelection(ArtworkColor(0.1 + 0.2, 1.0 / 3.0, 0.0), AccentSelectionKind.tintedNeutral))
        accent.write("0123456789abcdef", memory)
        eventually { runBlocking { accent.read() } != null }
        val (again, againScope) = restart("playback-accent")
        val reread = PlaybackAccentStore(again, againScope)
        assertEquals(memory, reread.readFor("0123456789abcdef"))
        assertNull("another account's colour is not used", reread.readFor("fedcba9876543210"))
        eventually { runBlocking { reread.read() } == null } // and forgotten
        reread.write("0123456789abcdef", memory)
        eventually { runBlocking { reread.read() } != null }
        reread.clear()
        eventually { runBlocking { reread.read() } == null }
    }
}
