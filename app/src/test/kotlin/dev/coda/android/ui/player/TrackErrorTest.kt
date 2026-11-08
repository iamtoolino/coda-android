package dev.coda.android.ui.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The error line gets a plain-language reason from the controller-visible error code. */
class TrackErrorTest {
    @Test fun reasonsAreSpecificPlainAndNeverRawEngineText() {
        assertEquals("File not found on the server", trackErrorReason(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
        assertEquals("This file is damaged or in a format this phone can't play", trackErrorReason(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertEquals("The server refused to send this track", trackErrorReason(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertEquals("The file couldn't be read", trackErrorReason(PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        assertFalse(trackErrorReason(PlaybackException.ERROR_CODE_UNSPECIFIED).contains("http", ignoreCase = true))
    }
}
