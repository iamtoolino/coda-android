package dev.coda.android.session

import android.security.keystore.KeyPermanentlyInvalidatedException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-2: the saved login is deleted only when it is unusable; transient errors keep it. Synthetic values only. */
class CredentialStoreReadTest {
    private val dir: File = Files.createTempDirectory("fr2").toFile()
    private val file = File(dir, "credentials.bin")
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @After fun cleanUp() { dir.deleteRecursively() }

    /** Same layout as CredentialStore.write: version byte 1, 12-byte IV, GCM ciphertext. */
    private fun save(plain: String = JSONObject().put("serverUrl", "https://music.example.org")
        .put("username", "demo").put("password", "secret").toString(), with: SecretKey = key) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, with) }
        file.writeBytes(byteArrayOf(1) + cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    private fun read(bytes: () -> ByteArray = { file.readBytes() }, key: () -> SecretKey? = { this.key }) =
        readSaved(exists = { file.exists() }, bytes = bytes, delete = { file.delete() }, key = key)

    /** android.jar stubs throw from every constructor, so allocate the Keystore exception without one. */
    private fun invalidatedKey(): KeyPermanentlyInvalidatedException {
        val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        return unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, KeyPermanentlyInvalidatedException::class.java) as KeyPermanentlyInvalidatedException
    }

    @Test fun readsAValidLogin() {
        save()
        val read = read()!!
        assertEquals("https://music.example.org", read.serverUrl)
        assertEquals("demo", read.username)
        assertEquals("secret", read.password)
        assertTrue(file.exists())
    }

    @Test fun noFileIsNull() { assertNull(read()) }

    // Unusable: the file is deleted.

    @Test fun badTagDeletes() {
        save(with = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()) // AEADBadTagException
        assertNull(read()); assertFalse(file.exists())
    }

    @Test fun invalidatedKeyDeletes() {
        save(); assertNull(read(key = { throw invalidatedKey() })); assertFalse(file.exists())
    }

    @Test fun unrecoverableKeyDeletes() {
        save(); assertNull(read(key = { throw UnrecoverableKeyException("test") })); assertFalse(file.exists())
    }

    @Test fun missingKeyDeletes() {
        save(); assertNull(read(key = { null })); assertFalse(file.exists())
    }

    @Test fun malformedFileDeletes() {
        file.writeBytes(byteArrayOf(2) + ByteArray(40)) // wrong version byte
        assertNull(read()); assertFalse(file.exists())
        file.writeBytes(byteArrayOf(1, 0, 0)) // too short
        assertNull(read()); assertFalse(file.exists())
    }

    @Test fun malformedJsonDeletes() {
        save(plain = "not json"); assertNull(read()); assertFalse(file.exists())
        save(plain = "{\"serverUrl\":\"https://music.example.org\"}"); assertNull(read()); assertFalse(file.exists())
    }

    // Transient: null, the file is kept and still reads afterwards.

    @Test fun keyStoreExceptionKeeps() {
        save(); assertNull(read(key = { throw KeyStoreException("test") })); assertKeptAndReadable()
    }

    @Test fun ioExceptionKeeps() {
        save(); assertNull(read(bytes = { throw IOException("test") })); assertKeptAndReadable()
    }

    @Test fun runtimeExceptionKeeps() {
        save(); assertNull(read(key = { throw RuntimeException("test") })); assertKeptAndReadable()
    }

    private fun assertKeptAndReadable() {
        assertTrue(file.exists())
        assertEquals("demo", read()!!.username)
    }
}
