package dev.coda.core.playback

/** How far the engine's length may differ from the server's whole-second one and still be taken for the same audio. */
const val DISPLAY_DURATION_TOLERANCE_MS = 2_000L

/**
 * The length the player UI uses for the fraction, the remaining-time label and tap-to-seek.
 * The server reports whole seconds (up to 1 s off, which is 5 % of a 20 s track); the engine knows the real
 * length for FLAC, Opus and MP3s with a Xing/Info header, so the playhead then ends together with the audio.
 * Other MP3s only get an estimate from the decoder, wildly off for header-less VBR (3.5 s for a 20.3 s file),
 * so an engine value that disagrees with the server's by more than [DISPLAY_DURATION_TOLERANCE_MS] is not
 * trusted. With no server value ([serverMs] <= 0) the engine's value is all there is.
 */
fun resolveDisplayDurationMs(serverMs: Long, engineMs: Long?): Long {
    val server = serverMs.coerceAtLeast(0)
    if (engineMs == null || engineMs <= 0) return server
    if (server == 0L) return engineMs
    return if (kotlin.math.abs(engineMs - server) <= DISPLAY_DURATION_TOLERANCE_MS) engineMs else server
}
