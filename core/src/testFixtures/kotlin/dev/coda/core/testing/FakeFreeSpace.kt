package dev.coda.core.testing

import dev.coda.core.cache.FreeSpace
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * A phone of [capacityBytes] where [othersBytes] belong to other apps and the files under [root] are Coda's own:
 * allocatable space shrinks as the cache grows, like `StorageManager.getAllocatableBytes`. [allocate] extends the file
 * like `posix_fallocate`, which is what `StorageManager.allocateBytes` does.
 */
class FakeFreeSpace(private val root: File, var capacityBytes: Long, var othersBytes: Long = 0) : FreeSpace {
    var refuseAllocation = false
    val allocations = mutableListOf<Long>()
    /** The test caches' `library.db` ([TestCacheIndex]) is left out: its size changes with every write, unlike the phone's. */
    fun cacheBytes() = root.walkTopDown().onEnter { it.name != ".index" }.filter { it.isFile }.sumOf { it.length() }
    override fun allocatableBytes(): Long = capacityBytes - othersBytes - cacheBytes()
    /** Make [free] bytes allocatable now. */
    fun setFree(free: Long) { othersBytes = capacityBytes - cacheBytes() - free }
    override fun allocate(fd: FileDescriptor, bytes: Long) {
        // Not closed: the stream doesn't own the descriptor.
        val channel = FileOutputStream(fd).channel
        if (refuseAllocation || bytes - channel.size() > allocatableBytes()) throw IOException("No space")
        allocations += bytes
        if (bytes > channel.size()) channel.write(ByteBuffer.allocate(1), bytes - 1)
    }
}
