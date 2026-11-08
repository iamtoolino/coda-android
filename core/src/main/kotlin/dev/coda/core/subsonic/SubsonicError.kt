package dev.coda.core.subsonic

import java.io.IOException

/** Messages never interpolate request URLs, response bodies, or transport exception text. */
sealed class SubsonicError(message: String) : Exception(message) {
    class InvalidConfiguration(message: String) : SubsonicError(message)
    /**
     * [otherServerAnswered] (information only, never a decision): the http fallback of a scheme-less address got an
     * HTTP answer that is not a Subsonic server, so [exception] is still the https failure. Nothing is ever sent to it.
     */
    class Network(val exception: IOException, val otherServerAnswered: Boolean = false) : SubsonicError("Could not reach the server.")
    class Http(val status: Int) : SubsonicError("The server returned HTTP $status.")
    /** [httpStatus]: the HTTP code when the rejection came as one (401/403), null for a Subsonic error envelope. */
    class Unauthorized(val httpStatus: Int? = null) : SubsonicError("The server rejected the credentials.")
    class NotFound : SubsonicError("The requested item was not found.")
    // Keep arbitrary server text out of throwable diagnostics; it may contain private data.
    class Server(val code: Int?, val serverMessage: String?) : SubsonicError("The server rejected the request.")
    class Decoding : SubsonicError("The server returned an invalid response.")
}
