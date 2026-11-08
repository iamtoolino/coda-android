package dev.coda.android.cache

import dev.coda.core.cache.CachePolicy
import dev.coda.core.cache.Lifetime
import dev.coda.core.cache.StoragePreset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** "CacheSettings": defaults, unknown keys, bad order, and Tracks cached ahead independent. */
class CacheSettingsTest {
    private class MemoryStore(val values: MutableMap<String, Any> = mutableMapOf()) : CacheSettings.Store {
        override fun string(key: String) = values[key] as? String
        override fun int(key: String) = values[key] as? Int
        override fun put(values: Map<String, Any>): Boolean { this.values.putAll(values); return true }
    }

    @Test fun noKeysReadAsTheDefault() {
        val settings = CacheSettings(MemoryStore())
        assertEquals(CachePolicy.DEFAULT, settings.policy.value)
        assertEquals(4, settings.tracksAhead)
    }

    @Test fun unknownKeysReadAsTheDefaultAndABadOrderIsNormalized() {
        assertEquals(CachePolicy.DEFAULT, CacheSettings(MemoryStore(mutableMapOf(CacheSettings.KEPT to "forever",
            CacheSettings.HEARD_ONCE to "2d", CacheSettings.KEEP_FREE_GB to 7))).policy.value)
        assertEquals(CachePolicy(Lifetime.MONTH, Lifetime.MONTH, Lifetime.MONTH), CacheSettings(MemoryStore(mutableMapOf(
            CacheSettings.KEPT to "1w", CacheSettings.HEARD_ONCE to "1m", CacheSettings.HEARD_AGAIN to "3d"))).policy.value)
    }

    /** Custom is a stored choice, so Custom times equal to a preset's stay Custom until a preset is picked. */
    @Test fun customChosenIsStoredAndKeepsCustomEvenWithPresetTimes() {
        val store = MemoryStore()
        val settings = CacheSettings(store)
        assertEquals(false, settings.customChosen.value)
        assertEquals(StoragePreset.BALANCED, settings.policy.value.selection(settings.customChosen.value))
        settings.setCustomChosen(true)
        assertEquals(mapOf(CacheSettings.CUSTOM_CHOSEN to 1), store.values)
        assertEquals(null, settings.policy.value.selection(settings.customChosen.value))
        assertEquals("a new settings object reads the flag", true, CacheSettings(store).customChosen.value)
        settings.setCustomChosen(false)
        assertEquals(StoragePreset.BALANCED, CacheSettings(store).policy.value.selection(CacheSettings(store).customChosen.value))
    }

    @Test fun savedValuesAreKeysAndTracksAheadIsIndependent() = runBlocking {
        val store = MemoryStore()
        val settings = CacheSettings(store)
        settings.setTracksAhead(7)
        val custom = CachePolicy(Lifetime.THREE_MONTHS, Lifetime.THREE_DAYS, Lifetime.TWO_WEEKS, 10 * CachePolicy.GB)
        settings.setPolicy(custom)
        assertEquals(mapOf(CacheSettings.TRACKS_AHEAD to 7, CacheSettings.KEPT to "3m", CacheSettings.HEARD_ONCE to "3d",
            CacheSettings.HEARD_AGAIN to "2w", CacheSettings.KEEP_FREE_GB to 10), store.values)
        assertEquals(custom, settings.policy.value)
        assertEquals(custom, CacheSettings(store).policy.value)
        assertEquals(7, settings.tracksAhead)
        assertEquals(7, CacheSettings(store).loadTracksAhead())
    }

    /** Playback reads Tracks cached ahead from memory: the default until loaded, then the stored value and its changes. */
    @Test fun tracksAheadIsInMemoryLoadedOnceAndUpdatedOnChange() = runBlocking {
        val store = MemoryStore(mutableMapOf(CacheSettings.TRACKS_AHEAD to 6))
        var reads = 0
        val counted = object : CacheSettings.Store by store {
            override fun int(key: String) = store.int(key).also { if (key == CacheSettings.TRACKS_AHEAD) reads++ }
        }
        val settings = CacheSettings(counted)
        assertEquals("the default before the first load", 4, settings.tracksAhead)
        assertEquals(0, reads)
        assertEquals(6, settings.loadTracksAhead())
        assertEquals(6, settings.tracksAhead); assertEquals(6, settings.tracksAhead)
        assertEquals("then from memory", 1, reads)
        settings.setTracksAhead(9)
        assertEquals(9, settings.tracksAhead)
        assertEquals(1, reads)
    }

    /** A Tracks cached ahead write that fails leaves the value in memory as it was. */
    @Test fun aFailedTracksAheadWriteKeepsTheValueInMemory() = runBlocking {
        val store = MemoryStore(mutableMapOf(CacheSettings.TRACKS_AHEAD to 6))
        val failing = object : CacheSettings.Store by store { override fun put(values: Map<String, Any>) = false }
        val settings = CacheSettings(failing)
        assertEquals(6, settings.loadTracksAhead())
        assertFalse(settings.setTracksAhead(9))
        assertEquals("not the value that was never saved", 6, settings.tracksAhead)
    }
}
