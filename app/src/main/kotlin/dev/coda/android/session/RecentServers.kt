package dev.coda.android.session

import android.content.Context
import dev.coda.android.settings.save
import dev.coda.android.settings.readOrDefaults
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dev.coda.android.settings.SettingsKeys
import dev.coda.android.settings.appSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A server address the user signed in to: the resolved URL (with its scheme) and when. No names, no passwords. */
data class RecentServer(val url: String, val usedAt: Long)

/**
 * Up to [LIMIT] resolved server URLs, newest first, for the sign-in dropdown. Kept in the app settings DataStore
 * (not secret: it is the same address the sign-in field shows). Reads and writes run off the main thread.
 */
class RecentServers internal constructor(
    private val store: DataStore<Preferences>,
    private val scope: CoroutineScope,
    /** The user forgot this server (×); eviction past [LIMIT] is not forgetting. */
    private val onForget: suspend (String) -> Unit = {},
) {
    constructor(context: Context, scope: CoroutineScope, onForget: suspend (String) -> Unit = {}) :
        this(context.applicationContext.appSettings, scope, onForget)

    private val mutable = MutableStateFlow<List<RecentServer>>(emptyList())
    val servers: StateFlow<List<RecentServer>> = mutable.asStateFlow()
    private val loaded = kotlinx.coroutines.CompletableDeferred<Unit>()

    init {
        scope.launch {
            val saved = decode(store.readOrDefaults()[SettingsKeys.RECENT_SERVERS])
            mutable.value = saved // changes wait for this read, so none is lost
            loaded.complete(Unit)
        }
    }

    suspend fun add(url: String): Unit = change { added(it, url, System.currentTimeMillis()) }
    /** Forgets [url]; to the end (also its consent and https history) even when the caller leaves the screen. */
    suspend fun remove(url: String) = withContext(NonCancellable) {
        change { removed(it, url) }
        onForget(url)
    }

    private suspend fun change(block: (List<RecentServer>) -> List<RecentServer>) {
        loaded.await()
        mutable.update(block)
        // The latest list when the write runs, so overlapping changes cannot persist a stale one.
        store.save { it[SettingsKeys.RECENT_SERVERS] = encode(mutable.value) }
    }

    companion object {
        const val LIMIT = 5

        /** Newest first, one entry per resolved URL, at most [LIMIT]. */
        fun added(list: List<RecentServer>, url: String, now: Long): List<RecentServer> {
            val clean = credentialFreeServerUrl(url) ?: return list // never an address with user info, query or fragment
            return (listOf(RecentServer(clean, now)) + list.filterNot { same(it.url, clean) }).take(LIMIT)
        }

        fun removed(list: List<RecentServer>, url: String): List<RecentServer> = list.filterNot { same(it.url, url) }

        /** Same server: equal after the sign-in normalisation, ignoring the case of scheme and host. */
        fun same(a: String, b: String): Boolean = key(a) == key(b)
        private fun key(url: String): String = runCatching {
            val normalized = dev.coda.core.subsonic.SubsonicConfig.normalizeServerURL(url)
            val parsed = java.net.URI(normalized)
            "${parsed.scheme.lowercase()}://${parsed.rawAuthority.lowercase()}${parsed.rawPath.orEmpty().trimEnd('/')}"
        }.getOrElse { url.trim().lowercase() }

        internal fun encode(list: List<RecentServer>): String = JSONArray().apply {
            list.forEach { put(JSONObject().put("url", it.url).put("usedAt", it.usedAt)) }
        }.toString()

        internal fun decode(value: String?): List<RecentServer> = runCatching {
            val array = JSONArray(value ?: return emptyList())
            (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let { item -> credentialFreeServerUrl(item.optString("url"))?.let { RecentServer(it, item.optLong("usedAt")) } }
            }.take(LIMIT)
        }.getOrDefault(emptyList())
    }
}
