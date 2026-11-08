package dev.coda.android.playback

import android.content.Context
import dev.coda.android.session.Session
import dev.coda.core.cache.TrackCache
import dev.coda.core.sync.LocalQueueStore
import java.io.File

private object AtomicFileIo : LocalQueueStore.FileIo {
    override fun exists(file: File) = file.isFile || File(file.path + ".bak").isFile
    override fun read(file: File) = android.util.AtomicFile(file).readFully()
    override fun write(file: File, bytes: ByteArray) {
        val atomic = android.util.AtomicFile(file)
        val out = atomic.startWrite()
        try { out.write(bytes); atomic.finishWrite(out) } catch (e: Exception) { atomic.failWrite(out); throw e }
    }
    override fun delete(file: File) = android.util.AtomicFile(file).delete()
}

/** Where one account's local queue copy lives (app-private, not backed up; keyed like the music cache). */
internal object OwnQueueFiles {
    // One store instance per account until sign-out, so that retiring it fences every writer that still holds it.
    private val stores = java.util.concurrent.ConcurrentHashMap<File, LocalQueueStore>()
    private fun file(context: Context, accountKey: String) = File(context.noBackupFilesDir, "queue/$accountKey.json")

    fun store(context: Context, session: Session): LocalQueueStore {
        val config = session.client.configuration
        val file = file(context, TrackCache.serverKey(config.serverUrl, config.username))
        return stores.getOrPut(file) { LocalQueueStore(file, AtomicFileIo) }
    }

    /** Sign-out of this account ([TrackCache.serverKey]), whatever the playback service has seen: no copy, and no late write recreates it. */
    fun forget(context: Context, accountKey: String) {
        val file = file(context, accountKey)
        (stores.remove(file) ?: LocalQueueStore(file, AtomicFileIo)).retire()
    }
}
