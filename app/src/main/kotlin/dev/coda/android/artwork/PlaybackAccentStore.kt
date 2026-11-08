package dev.coda.android.artwork

import android.content.Context
import dev.coda.android.settings.save
import dev.coda.android.settings.readOrDefaults
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dev.coda.android.settings.AccentKeys
import dev.coda.android.settings.playbackAccentData
import dev.coda.core.accent.AccentSelection
import dev.coda.core.accent.AccountPlaybackAccent
import dev.coda.core.accent.AccentSelectionKind
import dev.coda.core.accent.ArtworkColor
import dev.coda.core.accent.PlaybackAccentMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The last committed playback colour (one record: subject identity, cover id, colour, kind), so the first frame after a cold
 * start has it (AccentResolver.restore). Ids only, no names or URLs, plus the hashed account key (TrackCache.serverKey: server + user) it
 * belongs to: it is used only for that account. Its own single-record DataStore, opened and read on IO as soon as the app starts
 * ([preload]), so the cold-start read is answered from memory. Forgotten on sign-out and on an account change.
 */
class PlaybackAccentStore internal constructor(private val store: DataStore<Preferences>, private val scope: CoroutineScope) {
    constructor(context: Context, scope: CoroutineScope) : this(context.applicationContext.playbackAccentData, scope)

    /** One record, one writer at a time, in call order: a sign-out's clear never races an earlier write. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(1)

    /** Opens the store and reads the record on IO now, so the first frame's [read] finds it in memory. */
    fun preload() { scope.launch(Dispatchers.IO) { store.readOrDefaults() } }

    suspend fun read(): AccountPlaybackAccent? = withContext(io) {
        val p = store.readOrDefaults()
        val account = p[AccentKeys.ACCOUNT] ?: return@withContext null
        val identity = p[AccentKeys.IDENTITY] ?: return@withContext null
        val cover = p[AccentKeys.COVER] ?: return@withContext null
        val kind = p[AccentKeys.KIND]?.let { name -> AccentSelectionKind.entries.firstOrNull { it.name == name } } ?: return@withContext null
        fun channel(key: Preferences.Key<Long>) = p[key]?.let(Double::fromBits) ?: -1.0
        val color = ArtworkColor(channel(AccentKeys.RED), channel(AccentKeys.GREEN), channel(AccentKeys.BLUE))
        if (listOf(color.red, color.green, color.blue).any { it !in 0.0..1.0 }) return@withContext null
        AccountPlaybackAccent(account, PlaybackAccentMemory(identity, cover, AccentSelection(color, kind)))
    }

    /** The kept colour for [accountKey] only; an entry of another account (or when nobody is stored) is forgotten, not kept for later. */
    suspend fun readFor(accountKey: String?): PlaybackAccentMemory? {
        val kept = read() ?: return null
        return kept.forAccount(accountKey) ?: run { clear(); null }
    }

    fun write(accountKey: String, memory: PlaybackAccentMemory) {
        scope.launch(io) {
            store.save {
                it[AccentKeys.ACCOUNT] = accountKey; it[AccentKeys.IDENTITY] = memory.identity; it[AccentKeys.COVER] = memory.coverId
                it[AccentKeys.KIND] = memory.selection.kind.name
                // Exact doubles (raw bits), so the restored colour equals the derived one and nothing re-animates.
                it[AccentKeys.RED] = memory.selection.color.red.toRawBits(); it[AccentKeys.GREEN] = memory.selection.color.green.toRawBits()
                it[AccentKeys.BLUE] = memory.selection.color.blue.toRawBits()
            }
        }
    }

    fun clear() { scope.launch(io) { store.save { it.clear() } } }
}
