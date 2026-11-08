package dev.coda.core.connectivity

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Covers that failed while offline are tried again once the server is reachable again. [ticks]
 * moves when [reachable] turns true after a time without it, but only with the screen on: a return with the screen off
 * waits until the screen comes on (no artwork work with the screen off beyond the current and next cover).
 */
class CoverRetries(scope: CoroutineScope, reachable: Flow<Boolean>, screenOn: Flow<Boolean>) {
    private val tick = MutableStateFlow(0)
    val ticks: StateFlow<Int> = tick

    init {
        scope.launch {
            var missed = false
            combine(reachable, screenOn) { online, lit -> online to lit }.collect { (online, lit) ->
                if (!online) missed = true
                else if (missed && lit) { missed = false; tick.value++ }
            }
        }
    }
}
