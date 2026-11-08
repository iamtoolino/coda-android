package dev.coda.android.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CurrentArtworkDataTest {
    private val scope = TestScope(UnconfinedTestDispatcher())
    private val requested = mutableListOf<String>()
    private var loaded = 0

    private fun data(load: suspend (String) -> ByteArray? = { id -> requested += id; id.toByteArray() }) =
        CurrentArtworkData(scope, load) { loaded++ }

    @Test fun loadsWantedCoversOnceAndNotifiesEach() {
        val artwork = data()
        artwork.track(listOf("a", "b"))
        artwork.track(listOf("a", "b"))
        assertEquals(listOf("a", "b"), requested)
        assertEquals(2, loaded)
        assertArrayEquals("a".toByteArray(), artwork.bytes("a"))
        assertNull(artwork.bytes("c"))
        assertNull(artwork.bytes(null))
    }

    @Test fun coversOutsideTheWantedSetAreDroppedAndInFlightLoadsCancelled() {
        val gate = CompletableDeferred<ByteArray?>()
        val artwork = data { id -> if (id == "slow") gate.await() else id.toByteArray() }
        artwork.track(listOf("a", "slow"))
        artwork.track(listOf("b"))
        assertNull(artwork.bytes("a"))
        gate.complete(byteArrayOf(1))
        assertNull("A load that was no longer wanted is never published", artwork.bytes("slow"))
        assertArrayEquals("b".toByteArray(), artwork.bytes("b"))
        artwork.track(emptyList())
        assertNull("Sign-out empties the queue and with it the artwork", artwork.bytes("b"))
    }

    @Test fun failedEmptyAndOversizedCoversAreNotPublishedAndCanBeRetried() {
        var fail = true
        val artwork = data { id -> when { id == "big" -> ByteArray(CurrentArtworkData.MAX_BYTES + 1); id == "empty" -> ByteArray(0); fail -> throw java.io.IOException("offline"); else -> byteArrayOf(7) } }
        artwork.track(listOf("big", "empty", "x"))
        assertTrue(listOf("big", "empty", "x").all { artwork.bytes(it) == null })
        assertEquals(0, loaded)
        fail = false
        artwork.track(listOf("x"))
        assertArrayEquals(byteArrayOf(7), artwork.bytes("x"))
        assertEquals(1, loaded)
    }
}
