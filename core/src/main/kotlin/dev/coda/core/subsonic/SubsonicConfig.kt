package dev.coda.core.subsonic

import java.net.IDN
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest
import java.util.UUID
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Credentials are deliberately excluded from generated toString/equals/copy methods. */
class SubsonicConfig(
    server: String,
    username: String,
    private val password: String,
    val clientName: String = makeClientName(null),
) {
    val serverUrl: String = normalizeServerURL(server)
    val username: String = username.trim().also {
        if (it.isEmpty()) throw SubsonicError.InvalidConfiguration("Enter a username.")
    }

    init {
        if (password.isEmpty()) throw SubsonicError.InvalidConfiguration("Enter a password.")
    }

    fun endpointUrl(
        endpoint: String,
        parameters: List<Pair<String, String>> = emptyList(),
        salt: String = makeSalt(),
    ): HttpUrl {
        if (endpoint.isEmpty() || '/' in endpoint) {
            throw SubsonicError.InvalidConfiguration("Invalid API endpoint.")
        }
        val origin = serverUrl.toHttpUrlOrNull()
            ?: throw SubsonicError.InvalidConfiguration("Could not construct the server URL.")
        return origin.newBuilder()
            .addPathSegment("rest").addPathSegment("$endpoint.view")
            .apply {
                listOf(
                    "u" to username, "t" to md5(password + salt), "s" to salt,
                    "v" to "1.16.1", "c" to clientName, "f" to "json",
                ).plus(parameters).forEach { (key, value) -> addQueryParameter(key, value) }
            }.build()
    }

    fun coverArtUrl(id: String, size: Int, salt: String = makeSalt()): HttpUrl =
        endpointUrl("getCoverArt", listOf("id" to id, "size" to size.toString()), salt)

    /**
     * [timeOffsetSeconds]: the transcode starts there (OpenSubsonic `transcodeOffset`). Navidrome takes whole
     * seconds only (a fractional value is ignored: the transcode starts at 0) and ignores it for the original, so it is
     * sent only with the Opus transcode and when > 0.
     */
    fun streamUrl(songId: String, format: String = "raw", salt: String = makeSalt(), timeOffsetSeconds: Int = 0): HttpUrl {
        val id = songId.trim()
        if (id.isEmpty()) throw SubsonicError.InvalidConfiguration("The song ID is empty.")
        if (format != "raw" && format != "opus") {
            throw SubsonicError.InvalidConfiguration("Invalid stream format.")
        }
        val parameters = listOf("id" to id, "format" to format) +
            (if (format == "opus") listOf("maxBitRate" to "128") else emptyList()) +
            (if (format == "opus" && timeOffsetSeconds > 0) listOf("timeOffset" to "$timeOffsetSeconds") else emptyList())
        return endpointUrl("stream", parameters, salt)
    }

    companion object {
        fun normalizeServerURL(value: String): String {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) throw SubsonicError.InvalidConfiguration("Enter a server URL.")
            val candidate = if ("://" in trimmed) trimmed else "https://$trimmed"
            // Foundation accepts an empty authority here; endpoint construction rejects it later.
            if (candidate.lowercase() in listOf("http://", "https://")) return candidate.lowercase()
            val uri = try {
                val parsed = URI(candidate.replace(" ", "%20"))
                if (parsed.host == null && parsed.rawAuthority != null) {
                    val authority = parsed.rawAuthority
                    val host = authority.substringAfterLast('@').substringBefore(':')
                    val asciiHost = IDN.toASCII(host)
                    val hostStart = authority.lastIndexOf('@') + 1
                    val asciiAuthority = authority.replaceRange(hostStart, hostStart + host.length, asciiHost)
                    URI("${parsed.scheme}://$asciiAuthority${parsed.rawPath.orEmpty()}" +
                        (parsed.rawQuery?.let { "?$it" } ?: "") +
                        (parsed.rawFragment?.let { "#$it" } ?: ""))
                } else parsed
            } catch (_: URISyntaxException) {
                throw SubsonicError.InvalidConfiguration("Enter a valid HTTP or HTTPS server URL.")
            } catch (_: IllegalArgumentException) {
                throw SubsonicError.InvalidConfiguration("Enter a valid HTTP or HTTPS server URL.")
            }
            val scheme = uri.scheme?.lowercase()
            if (scheme !in listOf("http", "https") || uri.host == null) {
                throw SubsonicError.InvalidConfiguration("Enter a valid HTTP or HTTPS server URL.")
            }
            if (uri.rawQuery != null || uri.rawFragment != null) {
                throw SubsonicError.InvalidConfiguration("The server URL cannot contain a query or fragment.")
            }
            val path = uri.path.orEmpty()
            // URLComponents mutates its decoded path only when removing trailing slashes.
            val normalizedPath = if (path.length > 1 && path.endsWith('/')) {
                URI(null, null, path.trimEnd('/').ifEmpty { "/" }, null).toASCIIString()
            } else uri.rawPath.orEmpty()
            return "$scheme://${uri.rawAuthority}$normalizedPath"
        }

        fun md5(value: String): String = MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }

        fun makeSalt(): String = UUID.randomUUID().toString().replace("-", "").take(12)

        fun makeClientName(deviceName: String?): String =
            "Coda on ${deviceName?.trim()?.takeIf { it.isNotEmpty() } ?: "Android"}"
    }
}
