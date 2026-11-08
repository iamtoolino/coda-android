package dev.coda.android.ui.login

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The logo glyph is rasterised off the caller's (main) thread, and a cancelled producer publishes nothing. */
class LogoGlyphThreadingTest {
    @Test fun theGlyphIsDrawnOffTheCallersThread() = runBlocking {
        val caller = Thread.currentThread()
        var drawnOn: Thread? = null
        val result = rasteriseOffMain { drawnOn = Thread.currentThread(); "glyph" }
        assertEquals("glyph", result)
        assertNotEquals("drawn on the calling (main) thread", caller, drawnOn)
        assertTrue(drawnOn!!.name, drawnOn!!.name.startsWith("DefaultDispatcher-worker"))
    }

    @Test fun aCancelledProducerCannotPublish() = runBlocking {
        val drawing = CompletableDeferred<Unit>()
        val finish = CountDownLatch(1)
        var published: String? = null
        // Like the screen's produceState: value = rasteriseOffMain { ... }; the pixel work blocks (it does not suspend).
        val producer = launch(Dispatchers.Default) {
            published = rasteriseOffMain { drawing.complete(Unit); check(finish.await(10, TimeUnit.SECONDS)); "glyph" }
        }
        withTimeout(10_000) { drawing.await() }
        producer.cancel() // the size changed or the screen left while the bitmap is being drawn
        finish.countDown() // the draw completes after the cancel
        producer.join()
        assertTrue(producer.isCancelled)
        assertNull("a cancelled producer published its result", published)
    }
}
