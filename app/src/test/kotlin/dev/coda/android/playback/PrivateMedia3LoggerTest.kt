package dev.coda.android.playback

import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.*
import org.junit.Test

@UnstableApi
class PrivateMedia3LoggerTest {
    @Test fun everyLevelDropsFreeFormTagsMessagesAndThrowableChains() {
        val records = mutableListOf<Triple<String, String, Throwable?>>()
        val logger = PrivateMedia3Logger(object : Log.Logger {
            override fun d(tag: String, message: String, throwable: Throwable?) { records += Triple(tag, message, throwable) }
            override fun i(tag: String, message: String, throwable: Throwable?) = d(tag, message, throwable)
            override fun w(tag: String, message: String, throwable: Throwable?) = d(tag, message, throwable)
            override fun e(tag: String, message: String, throwable: Throwable?) = d(tag, message, throwable)
        })
        val sentinel = "https://ws9b-private.invalid/stream?t=ws9b-secret&s=salt&p=password"
        val error = Exception(sentinel, Exception(sentinel)).also { it.addSuppressed(Exception(sentinel)) }
        logger.d(sentinel, sentinel, error)
        logger.i(sentinel, sentinel, error)
        logger.w(sentinel, sentinel, error)
        logger.e(sentinel, sentinel, error)
        assertEquals(4, records.size)
        records.forEach { (tag, message, throwable) ->
            assertEquals("CodaMedia3", tag)
            assertEquals("Media3 diagnostic redacted", message)
            assertNull(throwable)
        }
    }
}
