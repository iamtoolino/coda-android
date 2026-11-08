package dev.coda.android.cache

import android.content.Context
import android.os.storage.StorageManager
import dev.coda.core.cache.FreeSpace
import java.io.File
import java.io.FileDescriptor

/**
 * Free space as Android reports it to apps: `getAllocatableBytes` already leaves out Android's own low-storage line
 * (at most 500 MB) and counts Coda's own files as used. `allocateBytes` reserves a download's space up front.
 */
class AndroidFreeSpace(context: Context, private val directory: File) : FreeSpace {
    private val storage = context.getSystemService(StorageManager::class.java)
    private val volume = storage.getUuidForPath(directory.apply { mkdirs() })
    override fun allocatableBytes(): Long = storage.getAllocatableBytes(volume)
    /**
     * Free space as the user sees it, without other apps' clearable caches (which `getAllocatableBytes`
     * counts as free). Compared with Keep free space; [allocatableBytes] stays the read-ahead floor.
     */
    override fun freeBytes(): Long = directory.usableSpace
    override fun allocate(fd: FileDescriptor, bytes: Long) = storage.allocateBytes(fd, bytes)
}
