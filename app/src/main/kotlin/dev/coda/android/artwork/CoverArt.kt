package dev.coda.android.artwork

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.withTransform
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import dev.coda.android.net.HttpClientFactory
import dev.coda.android.session.Session
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import androidx.compose.ui.graphics.graphicsLayer
import okhttp3.Request

/** Authenticated URLs are transient: neither cache ever uses them as a key. */
data class CoverArt(val id: String, val size: Int = 472)
object CoverArtKeyer {
    fun key(serverKey: String, art: CoverArt): String = "art:$serverKey:${art.id}:${art.size}"
    fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    /** The picture cache's account key (server + user), shared with the kept-cover store's copies. */
    fun account(serverUrl: String, username: String): String = digest("$serverUrl|$username")
    fun pictureFile(directory: File, account: String, art: CoverArt): File = File(directory, digest(key(account, art)))
}
class CoverArtFetcher internal constructor(
    context: Context,
    private val session: Session,
    /** Decodes the bytes fetched for a cover requested at the given edge (px). */
    private val decode: (ByteArray, Int) -> android.graphics.Bitmap,
) {
    constructor(context: Context, session: Session) : this(context, session, ::decodeForEdge)

    companion object {
        /**
         * A server may ignore `size` and send the original (a 3000 px cover decoded in full is 36 MB, and a
         * home grid of them held 262 MB). Subsample by powers of two while the result stays at least [edge], so a
         * correctly sized cover decodes unchanged and an oversized one never decodes below the requested size.
         */
        internal fun decodeForEdge(data: ByteArray, edge: Int): android.graphics.Bitmap {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (edge > 0 && longest / (sample * 2) >= edge) sample *= 2
            return (BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: throw IOException("Artwork unavailable")).also { it.prepareToDraw() }
        }
    }
    private val directory by lazy { File(context.cacheDir, "artwork") }
    val serverKey = CoverArtKeyer.account(session.client.configuration.serverUrl, session.client.configuration.username)
    private val app = context.applicationContext as? dev.coda.android.CodaApp
    private val musicKey = dev.coda.core.cache.TrackCache.serverKey(session.client.configuration.serverUrl, session.client.configuration.username)
    /** This account's music cache, whose one cover store holds the kept covers (same lock, line and size total as their owners). */
    private fun kept(): dev.coda.core.cache.TrackCache? = app?.trackCache?.takeIf { it.directory.name == musicKey }
    private fun readImage(file: File): ByteArray? = runCatching { file.readBytes().takeIf(::isImage) }.getOrNull()
    private val lock = Mutex()
    private val generations = mutableMapOf<String, Long>()
    private val bitmaps = object : android.util.LruCache<String, android.graphics.Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.allocationByteCount
    }
    fun cached(art: CoverArt): android.graphics.Bitmap? = bitmaps.get(CoverArtKeyer.key(serverKey, art))
    private val changed = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val revision: kotlinx.coroutines.flow.StateFlow<Long> = changed
    suspend fun refresh(art: CoverArt) {
        // Prepare bytes and decoded pixels before publishing: the existing image stays throughout.
        val result = loadBytes(art, force = true)
        val data = result.data
        val bitmap = withContext(Dispatchers.Default) {
            decodeGate.withPermit {
                decode(data, art.size)
            }
        }
        lock.withLock {
            if ((generations[result.key] ?: 0) == result.generation) {
                bitmaps.put(result.key, bitmap)
                changed.value += 1
            }
        }
    }
    private data class Bytes(val key: String, val generation: Long, val data: ByteArray)
    /**
     * [exact]: only a copy of exactly this size, from the phone or the server, never a larger or smaller one (the
     * accent is always derived from the 472 copy, so it never depends on which other sizes happen to be stored).
     */
    suspend fun bytes(art: CoverArt, force: Boolean = false, exact: Boolean = false): ByteArray = loadBytes(art, force, exact).data
    /** Covers renewed because the server reports another stamp (accent and looks are derived again). */
    private val renewedCovers = kotlinx.coroutines.flow.MutableSharedFlow<CoverArt>(extraBufferCapacity = 16)
    val renewed: kotlinx.coroutines.flow.SharedFlow<CoverArt> = renewedCovers
    /** Moves when covers that failed may load now (back ONLINE with the screen on); [CoverImage] tries again. */
    val retries: kotlinx.coroutines.flow.StateFlow<Int> = app?.coverRetries?.ticks ?: kotlinx.coroutines.flow.MutableStateFlow(0)
    /** Artwork fetches beyond the current and next cover wait for the screen (revalidation is one). */
    private fun screenOn() = app?.screenOn() ?: true
    /** On ART-1: a stale copy is revalidated only while the server answers; otherwise it is shown at once. */
    private fun online() = app?.reachability?.state?.value?.link?.let { it == dev.coda.core.connectivity.Reachability.Link.ONLINE } ?: true
    private suspend fun loadBytes(art: CoverArt, force: Boolean = false, exact: Boolean = false): Bytes = withContext(Dispatchers.IO) {
        dev.coda.android.diag.BatteryLog.count(dev.coda.android.diag.BatteryIds.ARTWORK_LOADS)
        val key = CoverArtKeyer.key(serverKey, art)
        val since = System.currentTimeMillis()
        val store = kept()?.covers
        // The server's coverArt id this session saw for the album (Navidrome `al-<id>_<stamp>`): kept next to the
        // copies, a different one means the cover changed on the server. Memory only, no database here.
        val stamp = session.client.serverCoverArt(art.id)
        var stale: ByteArray? = null
        val started = lock.withLock {
            if (force) generations[key] = (generations[key] ?: 0) + 1
            // Order: the store's exact size, the old picture cache's exact size (moved into the store), the store's
            // larger copy; then the network, then (failed) the store's smaller copy. Never a request from the store.
            if (!force) local(art.id, art.size, exact)?.let { found ->
                // Revalidation: a copy fetched under another stamp is fetched again (online, screen on); offline,
                // with the screen off or on failure it is still served.
                if (store?.stale(art.id, art.size, stamp) == true && screenOn() && online()) stale = found
                else return@withContext Bytes(key, generations[key] ?: 0, found)
            }
            generations[key] ?: 0
        }
        if (dev.coda.android.BuildConfig.DEBUG) android.util.Log.d("CodaArtwork", "network ${art.size}") // Trace: size only
        val call = HttpClientFactory.client.newCall(Request.Builder().url(session.client.coverArtUrl(art.id, art.size)).apply { if (force || stale != null) cacheControl(okhttp3.CacheControl.FORCE_NETWORK) }.build())
        val bytes = try { suspendCancellableCoroutine<ByteArray> { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) { continuation.resumeWith(Result.failure(IOException("Artwork unavailable"))) }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val result = runCatching { response.use {
                        if (!it.isSuccessful) throw IOException("Artwork unavailable")
                        it.body?.bytes() ?: throw IOException("Artwork unavailable")
                    } }
                    continuation.resumeWith(result)
                }
            })
        } } catch (e: IOException) {
            stale?.let { return@withContext Bytes(key, started, it) }
            // Offline: a 1440 request falls back to the stored 472 copy.
            if (!force && !exact) store?.fallback(art.id, art.size)?.let(::readImage)?.let { return@withContext Bytes(key, started, it) }
            throw e
        }
        coroutineContext.ensureActive()
        lock.withLock {
            if ((generations[key] ?: 0) != started) return@withContext Bytes(key, started, bytes)
            // A successful HTTP status can still contain a proxy/login/error page. Never poison the cache.
            if (!isImage(bytes)) throw IOException("Artwork unavailable")
            val cache = kept()
            // Every size fetched is kept as delivered in the durable store (songs give way, never the artwork).
            // A pull-to-refresh also drops the older sizes written before it began.
            val stored = cache != null && runCatching { if (force) cache.refreshCover(art.id, art.size, bytes, since, stamp) else cache.putCover(art.id, art.size, bytes, stamp) }.getOrDefault(false)
            // No music cache for this account yet (the first moments after sign-in), or Rex P2-1: the store refused it
            // (below Keep free space, nothing can give way): the old picture cache holds it, and local() moves it in later.
            val kept = stored || (cache?.retired != true && runCatching { writePicture(art, bytes) }.isSuccess)
            // Old disk reads made while this request was in flight must not publish after these bytes.
            generations[key] = started + 1
            // Renewed (accent and looks derived again) only once the new bytes are where the next load finds them.
            if (kept) stale?.let { old -> if (!old.contentEquals(bytes)) renewedCovers.tryEmit(art) }
            Bytes(key, started + 1, bytes)
        }
    }
    /** The old picture cache (`cacheDir/artwork`): written only while this account has no durable store yet. Under [lock]. */
    private fun writePicture(art: CoverArt, bytes: ByteArray) {
        directory.mkdirs()
        val file = CoverArtKeyer.pictureFile(directory, serverKey, art)
        val temp = File.createTempFile(file.name, ".tmp", directory)
        try { temp.writeBytes(bytes); check(temp.renameTo(file)) } finally { temp.delete() }
        var total = directory.listFiles().orEmpty().sumOf { it.length() }
        directory.listFiles().orEmpty().sortedBy { it.lastModified() }.forEach {
            if (total > 256L * 1024 * 1024) { total -= it.length(); it.delete() }
        }
    }
    /**
     * The copies on the phone for [id] at [size], in the order (store exact, old picture cache exact, store larger;
     * [exact]: no larger copy). An old picture-cache copy moves into the store without a download. Call under [lock].
     */
    private fun local(id: String, size: Int, exact: Boolean): ByteArray? {
        val file = CoverArtKeyer.pictureFile(directory, serverKey, CoverArt(id, size))
        val covers = kept()?.covers
        val picture = {
            if (!file.isFile) null else file.readBytes().let { cached ->
                if (isImage(cached)) cached.also { if (covers == null) file.setLastModified(System.currentTimeMillis()) else covers.adopt(id, size, file) }
                // Older builds could persist a 200 response containing an error page forever.
                else { file.delete(); null }
            }
        }
        if (covers == null) return picture()
        return covers.exact(id, size)?.let(::readImage) ?: picture() ?: if (exact) null else covers.larger(id, size)?.let(::readImage)
    }
    private fun isImage(bytes: ByteArray): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    /** Decoding is CPU-bound: two at a time keeps a fast flick through a large grid from starving the UI and render threads. */
    private val decodeGate = kotlinx.coroutines.sync.Semaphore(2)
    suspend fun bitmap(art: CoverArt): android.graphics.Bitmap = withContext(Dispatchers.Default) {
        val key = CoverArtKeyer.key(serverKey, art)
        while (true) {
            bitmaps.get(key)?.let { return@withContext it }
            val result = loadBytes(art)
            val bitmap = decodeGate.withPermit {
                decode(result.data, art.size)
            }
            val published = lock.withLock {
                if ((generations[key] ?: 0) != result.generation) false
                else { bitmaps.put(key, bitmap); true }
            }
            if (published) return@withContext bitmap
        }
        @Suppress("UNREACHABLE_CODE")
        error("Unreachable")
    }
}

/** The composed items on the page, including its lazy viewport and artist portraits. */
val LocalPageArtwork = staticCompositionLocalOf<MutableMap<CoverArt, Int>?> { null }

@Composable
fun CoverImage(fetcher: CoverArtFetcher, id: String?, modifier: Modifier = Modifier, size: Int = 472,
    alignment: androidx.compose.ui.Alignment = androidx.compose.ui.Alignment.Center,
    /** Drawn instead of the Coda logo when there is no id or the load failed (artists show their initials). */
    missing: (@Composable BoxScope.() -> Unit)? = null) {
    val page = LocalPageArtwork.current
    DisposableEffect(page, id, size) {
        val art = id?.let { CoverArt(it, size) }
        if (art != null && page != null) page[art] = (page[art] ?: 0) + 1
        onDispose { if (art != null && page != null) {
            val count = (page[art] ?: 1) - 1
            if (count == 0) page.remove(art) else page[art] = count
        } }
    }
    val revision by fetcher.revision.collectAsState()
    val cached = remember(fetcher, id, size, revision) { id?.let { fetcher.cached(CoverArt(it, size)) } }
    var failed by remember(fetcher, id, size) { mutableStateOf(false) }
    // A cover that failed (offline) is tried again when ONLINE returns with the screen on,
    // without being scrolled off and back.
    val retry by fetcher.retries.collectAsState()
    var attempt by remember(fetcher, id, size) { mutableIntStateOf(0) }
    LaunchedEffect(retry) { if (failed) attempt++ }
    val bitmap by produceState<android.graphics.Bitmap?>(cached, fetcher, id, size, revision, attempt) {
        value = cached
        if (id != null && cached == null) try { value = fetcher.bitmap(CoverArt(id, size)); failed = false }
        catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
    }
    // A cached cover draws at once. A late one fades in over the placeholder; a Crossfade per cell is too heavy for a 2,000-cell grid.
    Box(modifier) {
        val image = bitmap
        var settled by remember(fetcher, id, size) { mutableStateOf(cached != null) }
        if (image == null || !settled) {
            val absent = id == null || failed
            if (absent && missing != null) missing() else CoverPlaceholder(missing = absent)
        }
        if (image != null) {
            val alpha = remember(image) { androidx.compose.animation.core.Animatable(if (settled) 1f else 0f) }
            LaunchedEffect(image) { if (!settled) { alpha.animateTo(1f, androidx.compose.animation.core.tween(150)); settled = true } }
            Image(image.asImageBitmap(), null, Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }, alignment = alignment, contentScale = ContentScale.Crop)
        }
    }
}

/** Static loading tile, or the macOS espresso/ink barred monogram (CodaPlaceholderCover.svg). */
@Composable
internal fun CoverPlaceholder(missing: Boolean) {
    if (!missing) { Box(Modifier.fillMaxSize().background(Color(0xFF1A1C20))); return }
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) { drawCodaPlaceholder() }
}

/** The barred monogram on its espresso ground, in a 1024 box scaled to the draw size (shared by [CoverPlaceholder] and [placeholderCover]). */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCodaPlaceholder() {
    drawRect(Color(0xFF211F1C))
    val ink = androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color(0xFF6B5638), Color(0xFF4B3E2E)), Offset(527.62f, 512f), 250f)
    withTransform({ scale(size.width / 1024f, size.height / 1024f, Offset.Zero) }) {
        drawRoundRect(Color(0xFFFFE9C8).copy(alpha = .09f), Offset(327.625f, 405.85f), Size(28.89f, 218.30f), CornerRadius(14.445f))
        drawRoundRect(Color(0xFFFFE9C8).copy(alpha = .09f), Offset(370.105f, 334.93f), Size(28.89f, 360.14f), CornerRadius(14.445f))
        drawRoundRect(Color(0xFFFFE9C8).copy(alpha = .09f), Offset(412.585f, 296.75f), Size(28.89f, 184.04f), CornerRadius(14.445f))
        drawRoundRect(Color(0xFFFFE9C8).copy(alpha = .09f), Offset(412.585f, 549.21f), Size(28.89f, 184.04f), CornerRadius(14.445f))
        drawRoundRect(Color(0xFFFFE9C8).copy(alpha = .09f), Offset(455.075f, 275.13f), Size(28.89f, 150.92f), CornerRadius(14.445f))
        drawRoundRect(Color(0xFFFFE9C8).copy(alpha = .09f), Offset(455.075f, 603.95f), Size(28.89f, 150.92f), CornerRadius(14.445f))
        drawRoundRect(Color(0xFFFFE9C8).copy(alpha = .09f), Offset(497.555f, 265.72f), Size(28.89f, 144.19f), CornerRadius(14.445f))
        drawRoundRect(Color(0xFFFFE9C8).copy(alpha = .09f), Offset(497.555f, 620.09f), Size(28.89f, 144.19f), CornerRadius(14.445f))
        drawRoundRect(Color(0xFFFFE9C8).copy(alpha = .09f), Offset(540.035f, 267.13f), Size(28.89f, 145.07f), CornerRadius(14.445f))
        drawRoundRect(Color(0xFFFFE9C8).copy(alpha = .09f), Offset(540.035f, 617.80f), Size(28.89f, 145.07f), CornerRadius(14.445f))
        drawRoundRect(Color(0xFFFFE9C8).copy(alpha = .09f), Offset(582.525f, 279.55f), Size(28.89f, 154.95f), CornerRadius(14.445f))
        drawRoundRect(Color(0xFFFFE9C8).copy(alpha = .09f), Offset(582.525f, 595.50f), Size(28.89f, 154.95f), CornerRadius(14.445f))
        drawRoundRect(Color(0xFFFFE9C8).copy(alpha = .09f), Offset(625.005f, 304.93f), Size(28.89f, 141.32f), CornerRadius(14.445f))
        drawRoundRect(Color(0xFFFFE9C8).copy(alpha = .09f), Offset(625.005f, 583.75f), Size(28.89f, 141.32f), CornerRadius(14.445f))
        drawRoundRect(Color(0xFFFFE9C8).copy(alpha = .09f), Offset(667.485f, 349.12f), Size(28.89f, 97.13f), CornerRadius(14.445f))
        drawRoundRect(Color(0xFFFFE9C8).copy(alpha = .09f), Offset(667.485f, 583.75f), Size(28.89f, 97.13f), CornerRadius(14.445f))
        drawRoundRect(Color(0xFF0B0A09).copy(alpha = .55f), Offset(327.625f, 402.85f), Size(28.89f, 218.30f), CornerRadius(14.445f))
        drawRoundRect(Color(0xFF0B0A09).copy(alpha = .55f), Offset(370.105f, 331.93f), Size(28.89f, 360.14f), CornerRadius(14.445f))
        drawRoundRect(Color(0xFF0B0A09).copy(alpha = .55f), Offset(412.585f, 293.75f), Size(28.89f, 184.04f), CornerRadius(14.445f))
        drawRoundRect(Color(0xFF0B0A09).copy(alpha = .55f), Offset(412.585f, 546.21f), Size(28.89f, 184.04f), CornerRadius(14.445f))
        drawRoundRect(Color(0xFF0B0A09).copy(alpha = .55f), Offset(455.075f, 272.13f), Size(28.89f, 150.92f), CornerRadius(14.445f))
        drawRoundRect(Color(0xFF0B0A09).copy(alpha = .55f), Offset(455.075f, 600.95f), Size(28.89f, 150.92f), CornerRadius(14.445f))
        drawRoundRect(Color(0xFF0B0A09).copy(alpha = .55f), Offset(497.555f, 262.72f), Size(28.89f, 144.19f), CornerRadius(14.445f))
        drawRoundRect(Color(0xFF0B0A09).copy(alpha = .55f), Offset(497.555f, 617.09f), Size(28.89f, 144.19f), CornerRadius(14.445f))
        drawRoundRect(Color(0xFF0B0A09).copy(alpha = .55f), Offset(540.035f, 264.13f), Size(28.89f, 145.07f), CornerRadius(14.445f))
        drawRoundRect(Color(0xFF0B0A09).copy(alpha = .55f), Offset(540.035f, 614.80f), Size(28.89f, 145.07f), CornerRadius(14.445f))
        drawRoundRect(Color(0xFF0B0A09).copy(alpha = .55f), Offset(582.525f, 276.55f), Size(28.89f, 154.95f), CornerRadius(14.445f))
        drawRoundRect(Color(0xFF0B0A09).copy(alpha = .55f), Offset(582.525f, 592.50f), Size(28.89f, 154.95f), CornerRadius(14.445f))
        drawRoundRect(Color(0xFF0B0A09).copy(alpha = .55f), Offset(625.005f, 301.93f), Size(28.89f, 141.32f), CornerRadius(14.445f))
        drawRoundRect(Color(0xFF0B0A09).copy(alpha = .55f), Offset(625.005f, 580.75f), Size(28.89f, 141.32f), CornerRadius(14.445f))
        drawRoundRect(Color(0xFF0B0A09).copy(alpha = .55f), Offset(667.485f, 346.12f), Size(28.89f, 97.13f), CornerRadius(14.445f))
        drawRoundRect(Color(0xFF0B0A09).copy(alpha = .55f), Offset(667.485f, 580.75f), Size(28.89f, 97.13f), CornerRadius(14.445f))
        drawRoundRect(ink, Offset(327.625f, 406.85f), Size(28.89f, 214.30f), CornerRadius(14.445f))
        drawRoundRect(ink, Offset(370.105f, 335.93f), Size(28.89f, 356.14f), CornerRadius(14.445f))
        drawRoundRect(ink, Offset(412.585f, 297.75f), Size(28.89f, 180.04f), CornerRadius(14.445f))
        drawRoundRect(ink, Offset(412.585f, 550.21f), Size(28.89f, 180.04f), CornerRadius(14.445f))
        drawRoundRect(ink, Offset(455.075f, 276.13f), Size(28.89f, 146.92f), CornerRadius(14.445f))
        drawRoundRect(ink, Offset(455.075f, 604.95f), Size(28.89f, 146.92f), CornerRadius(14.445f))
        drawRoundRect(ink, Offset(497.555f, 266.72f), Size(28.89f, 140.19f), CornerRadius(14.445f))
        drawRoundRect(ink, Offset(497.555f, 621.09f), Size(28.89f, 140.19f), CornerRadius(14.445f))
        drawRoundRect(ink, Offset(540.035f, 268.13f), Size(28.89f, 141.07f), CornerRadius(14.445f))
        drawRoundRect(ink, Offset(540.035f, 618.80f), Size(28.89f, 141.07f), CornerRadius(14.445f))
        drawRoundRect(ink, Offset(582.525f, 280.55f), Size(28.89f, 150.95f), CornerRadius(14.445f))
        drawRoundRect(ink, Offset(582.525f, 596.50f), Size(28.89f, 150.95f), CornerRadius(14.445f))
        drawRoundRect(ink, Offset(625.005f, 305.93f), Size(28.89f, 137.32f), CornerRadius(14.445f))
        drawRoundRect(ink, Offset(625.005f, 584.75f), Size(28.89f, 137.32f), CornerRadius(14.445f))
        drawRoundRect(ink, Offset(667.485f, 350.12f), Size(28.89f, 93.13f), CornerRadius(14.445f))
        drawRoundRect(ink, Offset(667.485f, 584.75f), Size(28.89f, 93.13f), CornerRadius(14.445f))
    }
}

/**
 * [CoverPlaceholder] as JPEG bytes at [edge] px, for hosts that read covers through [ArtworkContentProvider]
 * (Android Auto): a cover with no copy on the phone that the server can't send shows what the phone shows, not a failed URI.
 */
internal fun placeholderCover(edge: Int): ByteArray {
    val bitmap = android.graphics.Bitmap.createBitmap(edge, edge, android.graphics.Bitmap.Config.ARGB_8888)
    androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(androidx.compose.ui.unit.Density(1f), androidx.compose.ui.unit.LayoutDirection.Ltr,
        androidx.compose.ui.graphics.Canvas(bitmap.asImageBitmap()), Size(edge.toFloat(), edge.toFloat())) { drawCodaPlaceholder() }
    return java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it); bitmap.recycle() }.toByteArray()
}
