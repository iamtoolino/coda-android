package io.github.iamtoolino.coda.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

data class ServerCredentials(
    val serverUrl: String,
    val username: String,
    val password: String,
)

/** Stores one Navidrome account encrypted with a non-exportable Android Keystore key. */
class CredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): ServerCredentials? {
        val encoded = preferences.getString(CREDENTIALS_KEY, null) ?: return null
        return runCatching {
            val parts = encoded.split('.', limit = 2)
            require(parts.size == 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                encryptionKey(),
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
            )
            val json = JSONObject(
                cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).decodeToString(),
            )
            ServerCredentials(
                serverUrl = json.getString("serverUrl"),
                username = json.getString("username"),
                password = json.getString("password"),
            )
        }.getOrElse {
            clear()
            null
        }
    }

    fun save(credentials: ServerCredentials) {
        val payload = JSONObject()
            .put("serverUrl", credentials.serverUrl)
            .put("username", credentials.username)
            .put("password", credentials.password)
            .toString()
            .encodeToByteArray()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val encoded = listOf(cipher.iv, cipher.doFinal(payload)).joinToString(".") {
            Base64.encodeToString(it, Base64.NO_WRAP)
        }
        check(preferences.edit().putString(CREDENTIALS_KEY, encoded).commit()) {
            "Could not store credentials"
        }
    }

    fun clear() {
        preferences.edit().remove(CREDENTIALS_KEY).commit()
    }

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "server_credentials"
        const val CREDENTIALS_KEY = "encrypted_credentials"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "coda_server_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
