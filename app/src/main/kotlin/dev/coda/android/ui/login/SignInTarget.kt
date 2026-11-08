package dev.coda.android.ui.login

import dev.coda.android.session.LoginError
import dev.coda.android.session.LoginField
import dev.coda.android.session.PlainHttpConsent
import dev.coda.android.session.ServerCheck
import dev.coda.core.subsonic.ServerProbe

/** Where Sign in authenticates, or why it does not. */
internal sealed interface SignInTarget {
    data class Url(val url: String) : SignInTarget
    data class Refused(val error: LoginError) : SignInTarget
    /** [url] is plain http and the user has not agreed to it: nothing is sent, the screen shows the notice. */
    data class NeedsConsent(val url: String) : SignInTarget
}

/**
 * The URL Sign in authenticates against. A typed (or recent-server) scheme is used as it is. Without one, only a
 * finished, displayed check counts: otherwise [check] runs (or joins) the address's one shared check, whose result is
 * what the screen shows, and [awaitShown] returns once that is on screen, before the URL is handed out. So credentials
 * never go to an http address the user has not seen. Nor to one the user has not agreed to ([consented], which
 * must fail closed: false when the stored agreements cannot be read).
 */
internal suspend fun signInTarget(
    input: String,
    current: ServerCheck?,
    check: suspend (String) -> ServerCheck?,
    consented: suspend (String) -> Boolean,
    awaitShown: suspend () -> Unit,
): SignInTarget {
    val target = if (input.isBlank() || ServerProbe.hasScheme(input)) SignInTarget.Url(input) else {
        val result = current as? ServerCheck.Found ?: check(input).also { awaitShown() }
        when (result) {
            is ServerCheck.Found -> SignInTarget.Url(result.url)
            is ServerCheck.Failed -> SignInTarget.Refused(LoginError(LoginField.Server, result.message))
            // Not an address at all: sign-in reports it ("That doesn't look like a server address.").
            else -> SignInTarget.Url(input)
        }
    }
    // Plain http (resolved or typed) only after the user agreed, on this screen or earlier ([consented]).
    return if (target is SignInTarget.Url && PlainHttpConsent.isPlainHttp(target.url) && !consented(target.url))
        SignInTarget.NeedsConsent(target.url) else target
}
