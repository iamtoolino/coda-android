package dev.coda.android.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal class SessionEnded : CancellationException("Session ended")

/** Cancel network work on account changes, and reject even a cancellation-resistant late response. */
internal suspend fun <T> SessionRepository.request(session: Session, block: suspend () -> T): T = coroutineScope {
    if (!isCurrent(session.token)) throw SessionEnded()
    val work = async(start = CoroutineStart.LAZY) { block() }
    val monitor = launch(start = CoroutineStart.UNDISPATCHED) {
        state.first { !isCurrent(session.token) }
        work.cancel(SessionEnded())
    }
    try {
        work.await().also { if (!isCurrent(session.token)) throw SessionEnded() }
    } finally {
        monitor.cancel()
    }
}

/** Untagged items (phone UI, resumption) are always admitted; a tagged item only while its session is current. */
internal fun admitsSessionTag(tag: String?, isCurrent: (java.util.UUID) -> Boolean): Boolean =
    tag == null || runCatching { java.util.UUID.fromString(tag) }.getOrNull()?.let(isCurrent) == true
