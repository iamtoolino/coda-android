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
import java.util.concurrent.TimeUnit
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
        }
    }

    fun cacheRoot(context: Context): File = File(context.cacheDir, CACHE_DIRECTORY)

    fun namespaceDirectory(context: Context, namespace: String): File =
        File(cacheRoot(context), sha256("namespace:$namespace"))

    fun <T> withDiskLock(block: () -> T): T = synchronized(diskLock, block)

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
        .build()
    private val downloadLocks = Array(DOWNLOAD_LOCK_COUNT) { Any() }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Artwork is read-only")
        val context = context ?: throw FileNotFoundException("No application context")
        val resolved = CarArtwork.resolve(uri)
            ?: throw FileNotFoundException("Unknown artwork URI")
        val directory = CarArtwork.namespaceDirectory(context, resolved.namespace).apply { mkdirs() }
        val file = File(directory, resolved.cacheKey.toFileName())
        val lock = downloadLocks[(resolved.cacheKey.hashCode() and Int.MAX_VALUE) % downloadLocks.size]

        synchronized(lock) {
            if (!file.isFile || file.length() == 0L) download(resolved, file, directory, context)
            if (resolved.namespace != AppGraph.cacheNamespace) {
                throw FileNotFoundException("Artwork belongs to a previous account")
            }
            return CarArtwork.withDiskLock {
                if (!file.isFile || file.length() == 0L) {
                    throw FileNotFoundException("Artwork cache entry disappeared")
                }
                file.setLastModified(System.currentTimeMillis())
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }
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
                if (destination.exists() && !destination.delete()) {
                    throw FileNotFoundException("Could not replace cached artwork")
                }
                if (temporary.length() == 0L || !temporary.renameTo(destination)) {
                    throw FileNotFoundException("Could not cache artwork")
                }
                trimCache(CarArtwork.cacheRoot(context))
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

    private fun trimCache(root: File) {
        val files = root.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".tmp") }
            .sortedBy(File::lastModified)
            .toList()
        var size = files.sumOf(File::length)
        for (file in files) {
            if (size <= MAX_CACHE_BYTES) break
            val length = file.length()
            if (file.delete()) size -= length
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
        const val DOWNLOAD_LOCK_COUNT = 64
        const val MAX_IMAGE_BYTES = 16L * 1024L * 1024L
        const val MAX_CACHE_BYTES = 128L * 1024L * 1024L
    }
}
