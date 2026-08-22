package io.github.iamtoolino.coda.player

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.github.iamtoolino.coda.AppGraph
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Gives Android Auto local artwork URIs. The provider fetches private Navidrome artwork through
 * Coda's own network connection and keeps a bounded disk cache on the phone.
 */
internal object CarArtwork {
    private const val COVER_PATH = "cover"
    private const val EXTERNAL_PATH = "external"
    private const val CACHE_DIRECTORY = "android-auto-artwork"
    private const val MAX_EXTERNAL_URLS = 2_048
    private val diskLock = Any()
    private var knownCacheRootPath: String? = null
    private var knownCacheBytes: Long? = null
    private val externalUrls = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(MAX_EXTERNAL_URLS + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
                size > MAX_EXTERNAL_URLS
        },
    )

    fun cover(
        context: Context,
        id: String?,
        size: Int,
        namespace: String = AppGraph.cacheNamespace,
    ): Uri? {
        val artworkId = id?.takeIf(String::isNotBlank) ?: return null
        namespace.takeIf(String::isNotBlank) ?: return null
        return Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(authority(context))
            .appendPath(COVER_PATH)
            .appendPath(namespace)
            .appendPath(artworkId)
            .appendPath(size.toString())
            .build()
    }

    fun external(
        context: Context,
        url: String?,
        stableKey: String,
        namespace: String = AppGraph.cacheNamespace,
    ): Uri? {
        val remoteUrl = url?.takeIf(String::isNotBlank) ?: return null
        namespace.takeIf(String::isNotBlank) ?: return null
        val key = sha256("external:$stableKey")
        externalUrls[externalMapKey(namespace, key)] = remoteUrl
        return Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(authority(context))
            .appendPath(EXTERNAL_PATH)
            .appendPath(namespace)
            .appendPath(key)
            .build()
    }

    data class ResolvedArtwork(
        val namespace: String,
        val cacheKey: String,
        val remoteUrl: String,
    )

    fun resolve(uri: Uri): ResolvedArtwork? {
        val namespace = uri.pathSegments.getOrNull(1)?.takeIf(String::isNotBlank) ?: return null
        val session = AppGraph.sessionSnapshot() ?: return null
        if (namespace != session.cacheNamespace) return null
        return when (uri.pathSegments.firstOrNull()) {
            COVER_PATH -> {
                val id = uri.pathSegments.getOrNull(2)?.takeIf(String::isNotBlank) ?: return null
                val size = uri.pathSegments.getOrNull(3)?.toIntOrNull()?.coerceIn(64, 1_600)
                    ?: return null
                val remoteUrl = session.client.coverArtUrl(id, size) ?: return null
                ResolvedArtwork(namespace, "$namespace:cover:${sha256(id)}:$size", remoteUrl)
            }
            EXTERNAL_PATH -> {
                val key = uri.pathSegments.getOrNull(2) ?: return null
                val remoteUrl = externalUrls[externalMapKey(namespace, key)] ?: return null
                ResolvedArtwork(namespace, "$namespace:external:$key", remoteUrl)
            }
            else -> null
        }
    }

    fun clear(context: Context, namespace: String) {
        if (namespace.isBlank()) return
        val prefix = "$namespace:"
        synchronized(externalUrls) {
            externalUrls.keys.removeAll { it.startsWith(prefix) }
        }
        withDiskLock {
            namespaceDirectory(context, namespace).deleteRecursively()
            cacheRoot(context).takeIf { it.listFiles()?.isEmpty() == true }?.delete()
            knownCacheRootPath = null
            knownCacheBytes = null
        }
    }

    fun cacheRoot(context: Context): File = File(context.cacheDir, CACHE_DIRECTORY)

    fun namespaceDirectory(context: Context, namespace: String): File =
        File(cacheRoot(context), sha256("namespace:$namespace"))

    fun <T> withDiskLock(block: () -> T): T = synchronized(diskLock, block)

    fun recordCacheReplacement(
        root: File,
        replacedBytes: Long,
        replacementBytes: Long,
        maximumBytes: Long,
    ) {
        val rootPath = root.absolutePath
        if (knownCacheRootPath != rootPath) {
            knownCacheRootPath = rootPath
            knownCacheBytes = null
        }
        val currentBytes = knownCacheBytes
            ?.let { (it - replacedBytes).coerceAtLeast(0L) + replacementBytes }
            ?: cacheFiles(root).sumOf(File::length)
        knownCacheBytes = if (currentBytes > maximumBytes) {
            trimCache(root, maximumBytes)
        } else {
            currentBytes
        }
    }

    private fun trimCache(root: File, maximumBytes: Long): Long {
        val files = cacheFiles(root).sortedBy(File::lastModified)
        var size = files.sumOf(File::length)
        for (file in files) {
            if (size <= maximumBytes) break
            val length = file.length()
            if (file.delete()) size -= length
        }
        return size
    }

    private fun cacheFiles(root: File): List<File> = root.walkTopDown()
        .filter { it.isFile && !it.name.endsWith(".tmp") }
        .toList()

    private fun externalMapKey(namespace: String, key: String) = "$namespace:$key"

    private fun authority(context: Context) = "${context.packageName}.artwork"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

class CarArtworkProvider : ContentProvider() {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val downloadExecutor = Executors.newFixedThreadPool(DOWNLOAD_WORKER_COUNT)
    private val pipeExecutor = Executors.newFixedThreadPool(PIPE_WORKER_COUNT)
    private val downloads = ConcurrentHashMap<String, Future<Unit>>()

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Artwork is read-only")
        val context = context ?: throw FileNotFoundException("No application context")
        val resolved = CarArtwork.resolve(uri)
            ?: throw FileNotFoundException("Unknown artwork URI")
        val directory = CarArtwork.namespaceDirectory(context, resolved.namespace).apply { mkdirs() }
        val file = File(directory, resolved.cacheKey.toFileName())

        if (!file.isFile || file.length() == 0L) {
            val download = downloads[resolved.cacheKey] ?: startDownload(
                uri = uri,
                resolved = resolved,
                destination = file,
                directory = directory,
                context = context,
            )
            try {
                download.get(CACHE_MISS_WAIT_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                return openDownloadPipe(download, file)
            } catch (error: ExecutionException) {
                throw FileNotFoundException("Could not load artwork").apply {
                    initCause(error.cause ?: error)
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw FileNotFoundException("Artwork load was interrupted").apply {
                    initCause(error)
                }
            }
        }
        if (resolved.namespace != AppGraph.cacheNamespace) {
            throw FileNotFoundException("Artwork belongs to a previous account")
        }
        return CarArtwork.withDiskLock {
            if (!file.isFile || file.length() == 0L) {
                throw FileNotFoundException("Artwork cache entry is not ready")
            }
            file.setLastModified(System.currentTimeMillis())
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    private fun openDownloadPipe(
        download: Future<Unit>,
        file: File,
    ): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createReliablePipe()
        pipeExecutor.execute {
            try {
                download.get()
                file.inputStream().use { input ->
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (_: Throwable) {
                runCatching { pipe[1].closeWithError("Artwork unavailable") }
            }
        }
        return pipe[0]
    }

    private fun startDownload(
        uri: Uri,
        resolved: CarArtwork.ResolvedArtwork,
        destination: File,
        directory: File,
        context: Context,
    ): Future<Unit> {
        lateinit var task: FutureTask<Unit>
        task = FutureTask {
            try {
                if (!destination.isFile || destination.length() == 0L) {
                    download(resolved, destination, directory, context)
                    context.contentResolver.notifyChange(uri, null)
                }
            } finally {
                downloads.remove(resolved.cacheKey, task)
            }
        }
        val existing = downloads.putIfAbsent(resolved.cacheKey, task)
        return existing ?: task.also(downloadExecutor::execute)
    }

    private fun download(
        resolved: CarArtwork.ResolvedArtwork,
        destination: File,
        directory: File,
        context: Context,
    ) {
        directory.mkdirs()
        val temporary = File.createTempFile("artwork-", ".tmp", directory)
        try {
            httpClient.newCall(Request.Builder().url(resolved.remoteUrl).get().build()).execute().use { response ->
                if (!response.isSuccessful) throw FileNotFoundException("Artwork returned HTTP ${response.code}")
                response.header("Content-Type")
                    ?.substringBefore(';')
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let { contentType ->
                        if (!contentType.startsWith("image/", ignoreCase = true)) {
                            throw FileNotFoundException("Artwork returned $contentType")
                        }
                    }
                val declaredLength = response.body.contentLength()
                if (declaredLength > MAX_IMAGE_BYTES) {
                    throw FileNotFoundException("Artwork is too large")
                }
                response.body.byteStream().use { input ->
                    temporary.outputStream().use { output -> input.copyToLimited(output) }
                }
            }
            CarArtwork.withDiskLock {
                if (resolved.namespace != AppGraph.cacheNamespace) {
                    throw FileNotFoundException("Artwork belongs to a previous account")
                }
                directory.mkdirs()
                val replacedBytes = destination.takeIf(File::isFile)?.length() ?: 0L
                if (destination.exists() && !destination.delete()) {
                    throw FileNotFoundException("Could not replace cached artwork")
                }
                if (temporary.length() == 0L || !temporary.renameTo(destination)) {
                    throw FileNotFoundException("Could not cache artwork")
                }
                CarArtwork.recordCacheReplacement(
                    root = CarArtwork.cacheRoot(context),
                    replacedBytes = replacedBytes,
                    replacementBytes = destination.length(),
                    maximumBytes = MAX_CACHE_BYTES,
                )
            }
        } catch (error: Throwable) {
            temporary.delete()
            throw FileNotFoundException("Could not load artwork").apply { initCause(error) }
        }
    }

    private fun InputStream.copyToLimited(output: java.io.OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_IMAGE_BYTES) throw FileNotFoundException("Artwork is too large")
            output.write(buffer, 0, count)
        }
    }

    override fun getType(uri: Uri): String = "image/*"
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun String.toFileName() = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val DOWNLOAD_WORKER_COUNT = 2
        const val PIPE_WORKER_COUNT = 2
        const val CACHE_MISS_WAIT_MILLIS = 250L
        const val MAX_IMAGE_BYTES = 16L * 1024L * 1024L
        const val MAX_CACHE_BYTES = 128L * 1024L * 1024L
    }
}
