package dev.coda.android.ui.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * When the network comes back, a screen that is showing its error or "saved copy" state reloads itself,
 * so the Retry line goes away without a tap. [needed] is read after a short settle delay.
 */
internal fun CoroutineScope.retryWhenBack(recovered: Flow<Unit>, needed: () -> Boolean, retry: () -> Unit, settleMs: Long = 800): Job =
    launch { recovered.collect { delay(settleMs); if (needed()) retry() } }
