package dev.coda.android.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/** Intentionally has no generated toString: all three fields are private user data. */
class Credentials(val serverUrl: String, val username: String, val password: String)

internal interface CredentialStorage {
    suspend fun read(): Credentials?
    suspend fun write(value: Credentials)
    suspend fun clear()
}

class CredentialStore(context: Context) : CredentialStorage {
    private val file by lazy { AtomicFile(java.io.File(context.noBackupFilesDir, "credentials.bin")) }
    private fun existingKey(): SecretKey? =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey("coda-login", null) as? SecretKey
    private fun key(): SecretKey {
        return existingKey() ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder("coda-login", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
    }
    override suspend fun read(): Credentials? = withContext(Dispatchers.IO) {
        readSaved(exists = { file.baseFile.exists() }, bytes = { file.readFully() }, delete = { file.delete() }, key = ::existingKey)
    }
    override suspend fun write(value: Credentials) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val plain = JSONObject().put("serverUrl", value.serverUrl).put("username", value.username)
            .put("password", value.password).toString().toByteArray(Charsets.UTF_8)
        val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    override suspend fun clear() = withContext(Dispatchers.IO) { file.delete() }
}

/**
 * FR-2: decrypts the saved login. The file is deleted only when it can never be read again: a missing key
 * ([key] returns null, so nothing can decrypt it), a key Keystore reports as invalidated or unrecoverable,
 * a failed GCM tag check, or a malformed file (format check or JSON). Any other error (a transient Keystore
 * or IO failure) returns null and keeps the file, so the next start can still sign in. Internal for tests.
 */
internal fun readSaved(
    exists: () -> Boolean, bytes: () -> ByteArray, delete: () -> Unit, key: () -> SecretKey?,
): Credentials? {
    if (!exists()) return null
    try {
        val data = bytes()
        if (data.size <= 28 || data[0] != 1.toByte()) { delete(); return null }
        val secret = key() ?: run { delete(); return null }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, data.copyOfRange(1, 13)))
        val json = JSONObject(String(cipher.doFinal(data.copyOfRange(13, data.size)), Charsets.UTF_8))
        return Credentials(json.getString("serverUrl"), json.getString("username"), json.getString("password"))
    } catch (error: Exception) {
        if (unusable(error)) delete()
        return null
    }
}

private fun unusable(error: Exception) = error is AEADBadTagException || error is KeyPermanentlyInvalidatedException ||
    error is UnrecoverableKeyException || error is JSONException
