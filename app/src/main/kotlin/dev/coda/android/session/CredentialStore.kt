package dev.coda.android.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey("coda-login", null) as? SecretKey ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder("coda-login", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
    }
    override suspend fun read(): Credentials? = withContext(Dispatchers.IO) {
        if (!file.baseFile.exists()) return@withContext null
        try {
            val bytes = file.readFully()
            require(bytes.size > 28 && bytes[0] == 1.toByte())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), Charsets.UTF_8))
            Credentials(json.getString("serverUrl"), json.getString("username"), json.getString("password"))
        } catch (_: Exception) {
            // Missing/invalidated Keystore keys and damaged files require a fresh login.
            file.delete()
            null
        }
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
