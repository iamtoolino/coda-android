package dev.coda.android.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Prefer fixed diagnostics. Raw transport text requires the configured server and any resolved IPs. */
object Redactor {
    fun redact(message: String, serverUrl: String? = null, serverAddresses: Set<String> = emptySet()): String {
        var result = message
            .replace(Regex("https?://[^\\s/]+", RegexOption.IGNORE_CASE), "https://<server>")
            .replace(Regex("([?&]|\\b)(u|t|s|p)=[^&\\s]*", RegexOption.IGNORE_CASE), "$1<redacted>")
        val identities = serverAddresses + listOfNotNull(serverUrl?.toHttpUrlOrNull()?.host)
        for (identity in identities.filter { it.isNotBlank() }.sortedByDescending { it.length }) {
            result = result.replace(
                Regex("(?<![\\w.-])${Regex.escape(identity.removeSurrounding("[", "]"))}(?![\\w.-])", RegexOption.IGNORE_CASE),
                "<server>",
            )
        }
        return result
    }
}
