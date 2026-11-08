package dev.coda.android.artwork

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import dev.coda.android.BuildConfig
import dev.coda.android.CodaApp
import dev.coda.android.session.SessionState
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking

/**
 * Serves cover art to Android Auto and the system UI as `content://<id>.artwork/art/<coverArtId>?size=N&k=<mac>`.
 * The URI carries no credentials and no server address: bytes come from the copies already on the phone at any size
 * ([localCopy]), fetched with the signed-in session only when there is no copy of the cover at all, and Coda's
 * placeholder when neither works. The provider is not exported: the media host reads it
 * through a URI grant the session gives each trusted controller on connect. `k` is an HMAC of session namespace, id and size under
 * a per-install key, so a URI cannot be forged or guessed even if a grant leaks.
 */
class ArtworkContentProvider : ContentProvider() {
    private var fetcher: Pair<java.util.UUID, CoverArtFetcher>? = null

    override fun onCreate(): Boolean {
        appContext = context?.applicationContext
        return true
    }
    override fun getType(uri: Uri) = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        val context = context ?: throw FileNotFoundException()
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != "art" || segments[1].isEmpty()) throw FileNotFoundException()
        val size = uri.getQueryParameter("size")?.toIntOrNull()?.coerceIn(CoverGuard.MIN_EDGE, MAX_SIZE) ?: DEFAULT_SIZE
        val app = context.applicationContext as CodaApp
        val session = (app.sessions.state.value as? SessionState.Connected)?.session ?: throw FileNotFoundException()
        val namespace = uri.getQueryParameter("account") ?: throw FileNotFoundException()
        if (namespace != session.token.toString()) throw FileNotFoundException()
        val mac = uri.getQueryParameter("k")?.toByteArray() ?: throw FileNotFoundException()
        val expected = mac(namespace, segments[1], size) ?: throw FileNotFoundException()
        if (!java.security.MessageDigest.isEqual(mac, expected.toByteArray())) throw FileNotFoundException()
        val source = synchronized(this) {
            fetcher?.takeIf { it.first == session.token }?.second
                ?: CoverArtFetcher(context, session).also { fetcher = session.token to it }
        }
        val id = segments[1]
        val local = if (id == PLACEHOLDER_ID) null else runCatching { localCopy(context, session, source, id, size) }.getOrNull()
        val fetched = local?.second ?: if (id == PLACEHOLDER_ID) null else try { runBlocking { source.bytes(CoverArt(id, size)) } } catch (_: Exception) { null }
        if (id != PLACEHOLDER_ID) dev.coda.android.diag.BatteryLog.count(if (local != null) dev.coda.android.diag.BatteryIds.PROVIDER_LOCAL_COVERS else dev.coda.android.diag.BatteryIds.PROVIDER_COVER_FETCHES)
        if (BuildConfig.DEBUG) trace("${callingPackage ?: "?"} $size ${local?.first ?: if (fetched != null) "fetcher" else "placeholder"}")
        // A server that ignores `size` returns the original: shrink it before a host decodes it (never serve oversize data).
        // No copy on the phone and none from the server (offline, a playlist mosaic never stored, a bad image) is
        // Coda's placeholder, as on the phone; a failed read is drawn by Android Auto as a warning triangle.
        val bytes = fetched?.let { CoverGuard.fit(it, size, CoverGuard.BROWSE_MAX_BYTES) } ?: placeholderCover(size)
        if (!app.sessions.isCurrent(session.token)) throw FileNotFoundException()
        val (read, write) = ParcelFileDescriptor.createPipe()
        Thread {
            try { ParcelFileDescriptor.AutoCloseOutputStream(write).use { it.write(bytes) } } catch (_: java.io.IOException) { /* reader went away */ }
        }.start()
        return read
    }

    /**
     * A copy of this cover already on the phone, at any size, scaled down by [CoverGuard.fit] afterwards. Hosts
     * (lock screen, system media controls, Bluetooth cover art, Android Auto) ask at their own size; a size the cache
     * doesn't hold must not mean a server fetch while the session's 1024 px or another copy is here. The OF-1c1 order
     * comes first (store exact, picture cache exact, store larger), then the picture cache's other sizes (smallest
     * larger first, then largest smaller), then the store's smaller copy. Null: no copy of this cover at all, the
     * fetcher decides (network). Never touches the network.
     */
    private fun localCopy(context: android.content.Context, session: dev.coda.android.session.Session, source: CoverArtFetcher,
        id: String, size: Int): Pair<String, ByteArray>? {
        val config = session.client.configuration
        val musicKey = dev.coda.core.cache.TrackCache.serverKey(config.serverUrl, config.username)
        val covers = (context.applicationContext as CodaApp).trackCache?.takeIf { it.directory.name == musicKey }?.covers
        val pictures = java.io.File(context.cacheDir, "artwork")
        fun read(file: java.io.File): ByteArray? = runCatching { file.readBytes().takeIf { CoverGuard.longestEdge(it) != null } }.getOrNull()
        // The durable store holds every size the app fetched; the old picture cache is read for copies not moved yet.
        fun picture(edge: Int): ByteArray? = covers?.exact(id, edge)?.let(::read) ?: CoverArtKeyer.pictureFile(pictures, source.serverKey, CoverArt(id, edge))
            .takeIf { it.isFile }?.let { file -> file.readBytes().takeIf { CoverGuard.longestEdge(it) != null } }
        (if (covers != null) covers.beforeNetwork(id, size, ::read) { picture(size) } else picture(size))?.let { return "exact" to it }
        LOCAL_SIZES.filter { it > size }.firstNotNullOfOrNull(::picture)?.let { return "larger" to it }
        // At least half the asked edge: a 64 px atmosphere copy upscaled to 288 would be a blur that never gets replaced.
        LOCAL_SIZES.filter { it < size && it * 2 >= size }.reversed().firstNotNullOfOrNull(::picture)?.let { return "smaller" to it }
        covers?.fallback(id, size)?.let(::read)?.let { return "smaller" to it }
        return null
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        /** Browse grid/list artwork: small enough for fast scrolling and tiny files; card asks for 256-320 px. */
        const val DEFAULT_SIZE = 288
        /**
         * Artwork id for a car item that has no cover id (a playlist kept while offline, an album without art):
         * the phone draws Coda's placeholder for those, and a grid tile without artwork is a warning triangle in Android
         * Auto. Not the shape of a Navidrome cover id.
         */
        const val PLACEHOLDER_ID = "coda:placeholder"
        /** Largest size a URI can request or be served, whatever the caller passes. */
        const val MAX_SIZE = CoverGuard.MAX_EDGE
        /** Sizes the app asks the server for, ascending (all kept in the durable store). */
        private val LOCAL_SIZES = dev.coda.core.cache.CoverStore.SIZES
        /** Debug builds: the last provider reads as "caller size source" (who asks for which sizes). */
        private val reads = java.util.ArrayDeque<String>()
        private fun trace(line: String) {
            android.util.Log.d("CodaArtwork", "openFile $line")
            synchronized(reads) { reads.addLast(line); while (reads.size > 64) reads.removeFirst() }
        }
        internal fun recentReads(): List<String> = synchronized(reads) { reads.toList() }
        val AUTHORITY = "${BuildConfig.APPLICATION_ID}.artwork"
        private var appContext: android.content.Context? = null
        @Volatile private var key: ByteArray? = null

        /** Prefix covering every artwork URI, for the one grant per trusted controller. */
        val BASE_URI: Uri = Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("art").build()

        fun uri(coverArtId: String, requested: Int = DEFAULT_SIZE): Uri? {
            val size = requested.coerceIn(CoverGuard.MIN_EDGE, MAX_SIZE)
            val app = appContext as? CodaApp ?: return null
            val namespace = ((app.sessions.state.value as? SessionState.Connected)?.session ?: return null).token.toString()
            val memoKey = "$namespace|$coverArtId|$size"
            synchronized(uris) { uris[memoKey] }?.let { return it }
            val signature = mac(namespace, coverArtId, size) ?: return null
            return BASE_URI.buildUpon().appendPath(coverArtId).appendQueryParameter("size", size.toString())
                .appendQueryParameter("account", namespace).appendQueryParameter("k", signature).build()
                .also { synchronized(uris) { uris[memoKey] = it } }
        }

        /**
         * Signed URIs by (account, id, size). The player maps its whole queue on every state change; an HMAC per
         * item kept Main busy with a few hundred tracks. The key is fixed per install, so a URI
         * never changes for the same inputs.
         */
        private val uris = object : LinkedHashMap<String, Uri>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Uri>?) = size > 4096
        }

        private fun mac(namespace: String, id: String, size: Int): String? {
            val readyKey = key ?: return null
            val hmac = javax.crypto.Mac.getInstance("HmacSHA256")
            hmac.init(javax.crypto.spec.SecretKeySpec(readyKey, "HmacSHA256"))
            return hmac.doFinal("$namespace|$id|$size".toByteArray()).take(16).joinToString("") { "%02x".format(it) }
        }

        /** Initialize before restoring the session. Metadata mapping never reads or creates files. */
        internal suspend fun initialize(context: android.content.Context) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            loadKey(context)
        }

        /** Random per install, kept out of backups; losing it only invalidates URIs already handed out. */
        private fun loadKey(context: android.content.Context): ByteArray = key ?: synchronized(this) {
            key ?: java.io.File(context.noBackupFilesDir, "artwork-key").let { file ->
                (if (file.isFile && file.length() == 32L) file.readBytes()
                else ByteArray(32).also { java.security.SecureRandom().nextBytes(it); file.writeBytes(it) }).also { key = it }
            }
        }
    }
}
