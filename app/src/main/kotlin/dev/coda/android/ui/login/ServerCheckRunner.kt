package dev.coda.android.ui.login

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.coda.android.session.ServerCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One server check per address, shared by the field's check and Sign in: there is one probe and one
 * writer of [shown], so the scheme on screen is always the one Sign in authenticates. A superseded check never writes.
 * Main-thread use only ([scope] is the screen's scope).
 */
internal class ServerCheckRunner(private val scope: CoroutineScope, private val check: suspend (String) -> ServerCheck?) {
    var shown by mutableStateOf<Pair<String, ServerCheck>?>(null)
        private set
    private var address: String? = null
    private var job: Deferred<ServerCheck?>? = null
    private var result: ServerCheck? = null
    private var finished = false
    private var generation = 0

    /** The running or finished check of [input], or a new one; [retryFailed] checks a finished failure again. */
    fun request(input: String, retryFailed: Boolean = false): Deferred<ServerCheck?> {
        val current = job
        if (current != null && address == input && !current.isCancelled &&
            !(retryFailed && finished && result !is ServerCheck.Found)) return current
        current?.cancel()
        val mine = ++generation
        address = input
        finished = false
        result = null
        shown = input to ServerCheck.Checking
        return scope.async {
            check(input).also { outcome ->
                if (generation == mine) {
                    result = outcome
                    finished = true
                    shown = outcome?.let { input to it }
                }
            }
        }.also { job = it }
    }

    /**
     * The field's live check: drop the old check, then check [input] once it has rested for [debounceMillis] or
     * [serverLeft] reports that the field lost focus. Incomplete input is never checked. Runs in the screen's effect
     * for [input], so the next keystroke cancels the wait.
     */
    suspend fun checkAfterPause(input: String, serverLeft: Flow<Boolean>, debounceMillis: Long = ServerCheckDebounceMillis) {
        reset(input)
        if (isIncompleteServerInput(input)) return
        withTimeoutOrNull(debounceMillis) { serverLeft.first { it } }
        request(input) // joins a check Sign in already started
    }

    /** The address changed: drop the old check and what it showed. */
    fun reset(input: String) {
        if (address == input) return
        job?.cancel()
        generation++
        job = null
        address = null
        result = null
        finished = false
        shown = null
    }
}
