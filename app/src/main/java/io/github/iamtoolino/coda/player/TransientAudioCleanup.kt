package io.github.iamtoolino.coda.player

import android.content.Context

internal interface PendingAudioCleanupLedger {
    fun pending(): Set<String>
    fun replace(namespaces: Set<String>)
}

internal class TransientAudioCleanupCoordinator(
    private val ledger: PendingAudioCleanupLedger,
    private val requestActiveCleanup: (Set<String>) -> Unit,
) {
    @Synchronized
    fun request(namespace: String) {
        if (namespace.isBlank()) return
        val requested = ledger.pending() + namespace
        ledger.replace(requested)
        requestActiveCleanup(requested)
    }

    @Synchronized
    fun resumePending() {
        ledger.pending().takeIf { it.isNotEmpty() }?.let(requestActiveCleanup)
    }

    @Synchronized
    fun complete(namespace: String) {
        ledger.replace(ledger.pending() - namespace)
    }
}

internal class SharedPreferencesAudioCleanupLedger(context: Context) : PendingAudioCleanupLedger {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun pending(): Set<String> = preferences
        .getStringSet(PENDING_NAMESPACES_KEY, emptySet())
        ?.toSet()
        .orEmpty()

    override fun replace(namespaces: Set<String>) {
        check(
            preferences.edit()
                .putStringSet(PENDING_NAMESPACES_KEY, namespaces)
                .commit(),
        ) { "Could not persist pending audio-cache cleanup" }
    }

    private companion object {
        const val PREFERENCES_NAME = "transient_audio_cleanup"
        const val PENDING_NAMESPACES_KEY = "pending_namespaces"
    }
}
