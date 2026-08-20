package io.github.iamtoolino.coda.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

internal sealed interface CodaThemeRequest {
    data object Brand : CodaThemeRequest
    data object Pending : CodaThemeRequest
    data object InheritPlayback : CodaThemeRequest

    data class Artwork(
        val identity: String,
        val artworkUrl: String,
    ) : CodaThemeRequest
}

internal fun resolveThemeRequest(
    foreground: CodaThemeRequest?,
    playback: CodaThemeRequest?,
): CodaThemeRequest = when (foreground) {
    null, CodaThemeRequest.InheritPlayback -> playback ?: CodaThemeRequest.Brand
    else -> foreground
}

@Stable
internal class CodaThemeRouter {
    var foregroundRequest by mutableStateOf<CodaThemeRequest?>(null)
        private set
    private var foregroundOwner: String? = null

    fun present(owner: String, request: CodaThemeRequest) {
        if (foregroundOwner == owner && foregroundRequest == request) return
        foregroundOwner = owner
        foregroundRequest = request
    }

    fun clear(owner: String) {
        if (foregroundOwner != owner) return
        foregroundOwner = null
        foregroundRequest = null
    }
}

@Composable
internal fun rememberCodaThemeRouter(): CodaThemeRouter = remember { CodaThemeRouter() }

@Composable
internal fun RegisterForegroundTheme(
    router: CodaThemeRouter,
    owner: String,
    request: CodaThemeRequest,
) {
    SideEffect { router.present(owner, request) }
    DisposableEffect(router, owner) {
        onDispose { router.clear(owner) }
    }
}

internal class ThemeCommitGate {
    private var generation = 0L

    fun begin(): Long = ++generation

    fun isCurrent(token: Long): Boolean = token == generation
}
