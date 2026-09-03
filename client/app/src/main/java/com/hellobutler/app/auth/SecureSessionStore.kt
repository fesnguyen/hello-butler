package com.hellobutler.app.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.hellobutler.app.data.remote.AuthTokensDto
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("butler_session", Context.MODE_PRIVATE)
    @Volatile var accessToken: String? = null
        private set

    fun save(tokens: AuthTokensDto) {
        accessToken = tokens.accessToken
        prefs.edit().putString(REFRESH_TOKEN, encrypt(tokens.refreshToken)).apply()
    }

    fun refreshToken(): String? {
        val encoded = prefs.getString(REFRESH_TOKEN, null) ?: return null
        return runCatching { decrypt(encoded) }.getOrElse {
            clear() // Keystore invalidation makes the saved refresh token unusable.
            null
        }
    }

    fun clear() {
        accessToken = null
        prefs.edit().remove(REFRESH_TOKEN).apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = ByteBuffer.allocate(Int.SIZE_BYTES + cipher.iv.size + encrypted.size)
            .putInt(cipher.iv.size).put(cipher.iv).put(encrypted).array()
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val payload = ByteBuffer.wrap(Base64.decode(encoded, Base64.NO_WRAP))
        val iv = ByteArray(payload.int).also(payload::get)
        val encrypted = ByteArray(payload.remaining()).also(payload::get)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        }
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val REFRESH_TOKEN = "refresh_token"
        const val KEY_ALIAS = "hello_butler_refresh_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
