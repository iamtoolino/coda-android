package dev.coda.android.ui.player

import androidx.media3.common.PlaybackException

/** Plain-language reason shown under "Can't play this track" (spec 9). Never carries engine text, which may hold URLs. */
internal fun trackErrorReason(errorCode: Int): String = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "File not found on the server"
    PlaybackException.ERROR_CODE_DECODING_FAILED -> "This file is damaged or in a format this phone can't play"
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The server refused to send this track"
    else -> "The file couldn't be read"
}
