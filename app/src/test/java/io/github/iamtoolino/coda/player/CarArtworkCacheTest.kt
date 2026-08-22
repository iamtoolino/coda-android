package io.github.iamtoolino.coda.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CarArtworkCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `cache remains bounded and evicts the least recently used file`() {
        val root = temporaryFolder.newFolder("car-artwork")
        val oldest = root.file("oldest", size = 6, modifiedAt = 1_000)
        val newest = root.file("newest", size = 6, modifiedAt = 2_000)

        CarArtwork.withDiskLock {
            CarArtwork.recordCacheReplacement(
                root = root,
                replacedBytes = 0,
                replacementBytes = newest.length(),
                maximumBytes = 10,
            )
        }

        assertFalse(oldest.exists())
        assertTrue(newest.exists())
    }

    private fun File.file(name: String, size: Int, modifiedAt: Long): File =
        resolve(name).apply {
            writeBytes(ByteArray(size))
            setLastModified(modifiedAt)
        }
}
