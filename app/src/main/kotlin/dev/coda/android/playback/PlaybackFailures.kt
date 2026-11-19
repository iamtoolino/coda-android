package dev.coda.android.playback

import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.decoder.DecoderException
import dev.coda.core.playback.PlaybackFailure

/**
 * Engine error → controller failure. Parser and decoder failures are checked before transport errors:
 * ParserException (including UnrecognizedInputFormatException) extends IOException, and corrupt or
 * unsupported audio must stop with an entry error rather than wait for a connection.
 */
@UnstableApi
internal fun playbackFailure(error: Throwable): PlaybackFailure {
    val chain = generateSequence(error) { it.cause }.take(16).toList()
    chain.firstNotNullOfOrNull { (it as? dev.coda.android.cache.TrackReadException)?.failure }?.let { return it }
    if (chain.any { it is ParserException || it is DecoderException } ||
        (error is PlaybackException && error.errorCode in 3000..4999)) return PlaybackFailure.Decode
    chain.firstNotNullOfOrNull { it as? HttpDataSource.InvalidResponseCodeException }?.let { http ->
        return when (http.responseCode) {
            401, 403 -> PlaybackFailure.Unauthorized
            404 -> PlaybackFailure.NotFound
            in 500..599 -> PlaybackFailure.Network
            else -> PlaybackFailure.Http(http.responseCode)
        }
    }
    // A local cache-file failure (including its wrapped IOException cause) is never a connectivity problem.
    if (chain.any { it is FileDataSource.FileDataSourceException }) return PlaybackFailure.Unknown
    if (chain.any { it is java.io.IOException }) return PlaybackFailure.Network
    return PlaybackFailure.Unknown
}

/**
 * A parser/format failure (ParserException, including UnrecognizedInputFormatException, or a Media3 parsing
 * error code), not a decoder one. Before the first sound of a remote stream it is usually a start cut off by a weak
 * network; the controller decides whether it counts as one ([dev.coda.core.playback.EngineEvent.Failed.cutOffStart]).
 */
@UnstableApi
internal fun isParserFailure(error: Throwable): Boolean {
    if (playbackFailure(error) != PlaybackFailure.Decode) return false
    val chain = generateSequence(error) { it.cause }.take(16).toList()
    return chain.any { it is ParserException } || (error is PlaybackException && error.errorCode in 3000..3999)
}
