package com.hangge.rootpilot.data.prefs

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class EncryptedToken(val ivBase64: String, val cipherBase64: String)

interface TokenCipher {
    fun encrypt(plainText: String): EncryptedToken
    fun decrypt(token: EncryptedToken): String
}

/**
 * AES/GCM key material lives in the Android Keystore; only the ciphertext and IV touch
 * SharedPreferences. The key alias is deliberately stable so the token survives restarts.
 */
class KeystoreTokenCipher(
    private val alias: String = KEY_ALIAS,
) : TokenCipher {

    override fun encrypt(plainText: String): EncryptedToken {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return EncryptedToken(
            ivBase64 = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            cipherBase64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP),
        )
    }

    override fun decrypt(token: EncryptedToken): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = Base64.decode(token.ivBase64, Base64.NO_WRAP)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(Base64.decode(token.cipherBase64, Base64.NO_WRAP))
        return String(plain, Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val KEY_ALIAS = "rootpilot_agent_token"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
