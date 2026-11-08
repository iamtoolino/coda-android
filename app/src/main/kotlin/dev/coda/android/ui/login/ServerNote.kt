package dev.coda.android.ui.login

import dev.coda.android.session.ServerCheck

/**
 * How long the Server field must rest before the live check runs. Gaps between keystrokes on a phone are mostly
 * 150 to 450 ms, slow typing and a glance at the keyboard reach about 600 ms, and a pause to think is a second or more.
 * 700 ms sits above the slow-typing gaps (no probe for each half-typed host or port) and still shows a result less than
 * a second after the last key. Leaving the field checks at once.
 */
internal const val ServerCheckDebounceMillis = 700L

/**
 * Input that cannot be an address yet: nothing typed, a bare scheme, or a host that ends in ":" or "." because
 * the next character is still to come. These are never checked.
 */
internal fun isIncompleteServerInput(input: String): Boolean {
    val text = input.trim()
    val host = if (text.contains("://")) text.substringAfter("://") else text
    return host.isEmpty() || host.endsWith(":") || host.endsWith(".")
}

/**
 * Results that are information rather than an alarm. A host that answers as some other web server (for example
 * a plain web server on port 80 or 443 before the Navidrome port is typed, over https or over the http fallback) is not an error, and a certificate this
 * phone does not trust is something to know. Everything else keeps the chip's own states.
 */
internal enum class ServerNote(val text: String) {
    NoServerYet("No music server found here yet"),
    CertificateNotTrusted("Certificate not trusted by this phone"),
}

internal fun serverNote(check: ServerCheck?): ServerNote? = when {
    check is ServerCheck.Found && !check.subsonic -> ServerNote.NoServerYet
    check is ServerCheck.Failed && check.otherServer && !check.untrusted -> ServerNote.NoServerYet
    check is ServerCheck.Failed && check.untrusted -> ServerNote.CertificateNotTrusted
    else -> null
}

/** Shown when the certificate note is tapped. */
internal const val CertificateExplanation =
    "This phone can't verify the server's certificate, so Coda won't sign in here. It may be self-signed or made for another name."
